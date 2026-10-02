package com.personal.jobagent.security;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Real Firebase ID-token verification through the Firebase Admin SDK.
 *
 * <p><b>Initialisation is lazy and fail-closed.</b> The SDK is only
 * constructed on the first verification attempt, and only when
 * {@link FirebaseProperties#isConfigured()} is true. A deployment with no
 * Firebase credentials therefore starts up normally (so local development and
 * the existing session-auth tests keep working) but refuses Firebase sign-ins
 * with a 503 naming the missing variables, rather than throwing at boot or —
 * far worse — pretending verification succeeded.
 *
 * <p>The service-account credential is assembled in-memory from the three
 * separate environment variables and handed straight to
 * {@code GoogleCredentials.fromStream}. It is never written to disk, never
 * cached as a string field beyond what {@code FirebaseApp} itself holds, and
 * never logged.
 *
 * <p>Verifying a token requires outbound HTTPS to Google's public key
 * endpoint. That is inherent to ID-token verification (the signature cannot be
 * checked without the signing keys) and is cached by the SDK for the lifetime
 * of the process, so it is a one-time cost after the first sign-in.
 */
@Component
public class FirebaseAdminTokenVerifier implements FirebaseTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(FirebaseAdminTokenVerifier.class);

    private static final String FIREBASE_APP_NAME = "job-agent-firebase";
    private static final String TOKEN_URI = "https://oauth2.googleapis.com/token";

    /**
     * Synthetic, non-secret value for the two service-account identifiers the
     * Google auth library insists on finding in the credential JSON.
     *
     * <p>{@code ServiceAccountCredentials.fromJson} rejects the whole
     * credential unless {@code client_id}, {@code client_email},
     * {@code private_key} <em>and</em> {@code private_key_id} are all present:
     *
     * <pre>
     *   if (clientId == null || clientEmail == null
     *       || privateKey == null || privateKeyId == null) {
     *     throw new IOException("Error reading service account credential ...");
     *   }
     * </pre>
     *
     * <p>Verified against {@code google-auth-library-oauth2-http} 1.23.0, the
     * version firebase-admin 9.4.1 resolves to. The three environment variables
     * this class reads carry no client id, and the JWT-bearer assertion the SDK
     * signs for token refresh identifies the service account by
     * {@code client_email} alone — {@code client_id} is never read when signing
     * (only exposed via {@code getClientId()}). A placeholder is therefore inert
     * here, exactly like the private key id.
     */
    private static final String SYNTHETIC_SERVICE_ACCOUNT_ID = "firebase-admin-env";

    private final FirebaseProperties properties;

    /**
     * Written under {@link #initLock}, read without it — hence volatile. The
     * double-checked read path keeps the common case (already initialised)
     * lock-free on every request.
     */
    private volatile FirebaseAuth firebaseAuth;

    private final Object initLock = new Object();

    public FirebaseAdminTokenVerifier(FirebaseProperties properties) {
        this.properties = properties;
    }

    @Override
    public VerifiedIdentity verifyIdToken(String idToken) {
        if (idToken == null || idToken.isBlank()) {
            throw new InvalidToken("No Firebase ID token was supplied.");
        }

        FirebaseToken decoded;
        try {
            decoded = auth().verifyIdToken(idToken);
        } catch (FirebaseAuthException e) {
            // Covers expiry, bad signature, wrong audience/issuer, revoked
            // tokens and non-ID tokens. The SDK's message names which, which
            // is useful in the server log; the HTTP response stays generic.
            throw new InvalidToken("Firebase ID token could not be verified.", e);
        } catch (IllegalArgumentException e) {
            throw new InvalidToken("Firebase ID token was malformed.", e);
        } catch (Unavailable e) {
            throw e; // configuration fault, not a credential fault
        } catch (RuntimeException e) {
            throw new InvalidToken("Firebase ID token could not be verified.", e);
        }

        String uid = decoded.getUid();
        if (uid == null || uid.isBlank()) {
            throw new InvalidToken("Firebase ID token carried no subject.");
        }

        return new VerifiedIdentity(
                uid,
                decoded.getEmail(),
                decoded.getName(),
                decoded.isEmailVerified());
    }

    /**
     * Deletes a Firebase account created moments ago, so a refused registration
     * leaves nothing behind.
     *
     * <p>The {@code creationTimestamp} guard is what makes this safe rather than
     * destructive: signing in with an existing Firebase account can also reach a
     * refused registration (an account with no local counterpart when
     * registration is closed), and deleting <em>that</em> account would destroy a
     * credential the user already owned. Only an account created within
     * {@code maxAge} — i.e. by the sign-up request being processed right now — is
     * removed.
     *
     * <p>Swallows every failure by design. The caller is already returning a
     * refusal; a cleanup problem must not change that response, and a retry is
     * harmless because the next attempt simply creates a fresh Firebase user.
     *
     * @return true only when an account was deleted
     */
    @Override
    public boolean deleteJustCreatedAccount(String uid, java.time.Duration maxAge) {
        if (uid == null || uid.isBlank()) {
            return false;
        }
        try {
            FirebaseAuth auth = auth();
            com.google.firebase.auth.UserRecord record = auth.getUser(uid);
            com.google.firebase.auth.UserMetadata metadata = record.getUserMetadata();
            long createdAt = metadata == null ? 0L : metadata.getCreationTimestamp();

            if (createdAt <= 0L) {
                log.warn("Not deleting Firebase account {}: it has no creation timestamp to check", uid);
                return false;
            }
            long ageMillis = System.currentTimeMillis() - createdAt;
            if (ageMillis > maxAge.toMillis()) {
                // An account that predates this request. Leaving it alone is the
                // whole point of the guard.
                log.info("Not deleting Firebase account {}: it is {} ms old, older than the registration window",
                        uid, ageMillis);
                return false;
            }

            auth.deleteUser(uid);
            log.info("Deleted the Firebase account created by a refused registration attempt");
            return true;
        } catch (Unavailable e) {
            // No Admin credentials configured: behave exactly as before this
            // feature existed.
            log.debug("Firebase account cleanup unavailable: {}", e.getMessage());
            return false;
        } catch (FirebaseAuthException | RuntimeException e) {
            // Includes "user not found" (already gone) and quota/transport
            // failures. Cleanup is best-effort; the refusal itself is unaffected.
            log.warn("Firebase account cleanup failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Returns a ready {@link FirebaseAuth}, initialising the SDK on first use.
     *
     * @throws Unavailable when credentials are missing or unusable
     */
    private FirebaseAuth auth() {
        FirebaseAuth existing = firebaseAuth;
        if (existing != null) {
            return existing;
        }
        synchronized (initLock) {
            if (firebaseAuth != null) {
                return firebaseAuth;
            }
            if (!properties.isConfigured()) {
                String missing = String.join(", ", properties.missingKeys());
                throw new Unavailable(
                        "Firebase Authentication is not configured on this server; missing: " + missing);
            }
            firebaseAuth = initialise();
            return firebaseAuth;
        }
    }

    private FirebaseAuth initialise() {
        try {
            GoogleCredentials credentials = credentials();

            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(credentials)
                    .setProjectId(properties.getProjectId())
                    .build();

            // A restart or a test context may already have created the named
            // app; reuse it rather than failing on a duplicate name.
            FirebaseApp app = FirebaseApp.getApps().stream()
                    .filter(existing -> FIREBASE_APP_NAME.equals(existing.getName()))
                    .findFirst()
                    .orElseGet(() -> FirebaseApp.initializeApp(options, FIREBASE_APP_NAME));

            log.info("Firebase Admin SDK initialised for project {} (token verification enabled)",
                    properties.getProjectId());
            return FirebaseAuth.getInstance(app);
        } catch (IOException e) {
            // The client gets a deliberately generic message (the parser can
            // echo key material in pathological cases), but the OPERATOR is
            // the one who must fix FIREBASE_PRIVATE_KEY — so the underlying
            // cause is logged server-side: exception class plus the scrubbed
            // message, never the key itself.
            log.error("Firebase Admin credential loading failed: {}: {} | {}",
                    e.getClass().getSimpleName(),
                    com.personal.jobagent.common.LogScrubber.scrub(String.valueOf(e.getMessage())),
                    configurationDiagnostic(e));
            throw new Unavailable("Firebase service-account credentials could not be read.", e);
        } catch (IllegalStateException | IllegalArgumentException e) {
            log.error("Firebase Admin credential configuration is malformed: {}: {} | {}",
                    e.getClass().getSimpleName(),
                    com.personal.jobagent.common.LogScrubber.scrub(String.valueOf(e.getMessage())),
                    configurationDiagnostic(e));
            throw new Unavailable("Firebase service-account credentials are malformed.", e);
        }
    }

    /**
     * Presence-and-shape diagnostic for the three FIREBASE_* variables,
     * logged when Admin initialisation fails. Reports ONLY booleans and a
     * shape verdict — never the project id, client email, or any part of the
     * private key. {@code normalizedPemShapeValid} is false when the stored
     * value does not look like a PKCS#8 PEM after newline normalisation,
     * which catches the two common operator mistakes: the value wrapped in
     * quotation marks, and the whole service-account JSON pasted instead of
     * the key alone.
     */
    private String configurationDiagnostic(Exception cause) {
        boolean projectIdPresent = isPresent(properties.getProjectId());
        boolean clientEmailPresent = isPresent(properties.getClientEmail());
        String normalised = properties.normalisedPrivateKey();
        boolean privateKeyPresent = normalised != null;
        boolean pemShapeValid = privateKeyPresent
                && normalised.startsWith("-----BEGIN PRIVATE KEY-----")
                && normalised.endsWith("-----END PRIVATE KEY-----")
                && normalised.contains("\n");
        return "diagnostic: projectIdPresent=" + projectIdPresent
                + ", clientEmailPresent=" + clientEmailPresent
                + ", privateKeyPresent=" + privateKeyPresent
                + ", normalizedPemShapeValid=" + pemShapeValid
                + ", at=" + cause.getClass().getSimpleName();
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Builds the Admin SDK credential from the configured environment
     * variables. Package-private (rather than private) purely so the regression
     * tests can assert that a Render-shaped private key is genuinely accepted by
     * the SDK without needing network access.
     *
     * @throws IOException when the assembled credential is not acceptable to the
     *                     Google auth library — e.g. a private key that is not a
     *                     parseable PKCS#8 PEM. Callers translate this into
     *                     {@link FirebaseTokenVerifier.Unavailable}, so a bad key
     *                     fails closed instead of authenticating anything.
     */
    GoogleCredentials credentials() throws IOException {
        return GoogleCredentials.fromStream(
                new ByteArrayInputStream(serviceAccountJson().getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * The service-account JSON the Google auth library expects, built from the
     * three environment variables. Built as a {@code Map} so the private key's
     * newlines are escaped correctly rather than hand-concatenated.
     */
    private String serviceAccountJson() {
        Map<String, String> json = new LinkedHashMap<>();
        json.put("type", "service_account");
        json.put("project_id", properties.getProjectId());
        json.put("private_key_id", SYNTHETIC_SERVICE_ACCOUNT_ID);
        // Required by ServiceAccountCredentials.fromJson even though it is never
        // used to sign: omitting it made EVERY deployment fail credential
        // loading with "service-account credentials could not be read", no
        // matter how correct FIREBASE_PRIVATE_KEY was. See the constant above.
        json.put("client_id", SYNTHETIC_SERVICE_ACCOUNT_ID);
        json.put("private_key", properties.normalisedPrivateKey());
        json.put("client_email", properties.getClientEmail());
        json.put("token_uri", TOKEN_URI);
        // Hand-rolled rather than Jackson-serialised: the private key contains
        // PEM newlines and Jackson's escaping rules would need to be exactly
        // right here for a credential we cannot afford to mangle.
        StringBuilder sb = new StringBuilder("{");
        json.forEach((key, value) -> sb.append('"').append(key).append("\":\"")
                .append(jsonEscape(value)).append("\","));
        sb.setLength(sb.length() - 1);
        return sb.append('}').toString();
    }

    private static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
