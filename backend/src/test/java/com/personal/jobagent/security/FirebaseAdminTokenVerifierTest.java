package com.personal.jobagent.security;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Regression tests for the Firebase Admin credential path.
 *
 * <p>These pin a real production failure: Render supplied all three
 * {@code FIREBASE_*} variables, yet every Firebase sign-in logged
 * <em>"Firebase token verification unavailable: Firebase service-account
 * credentials could not be read."</em> The private key was never the problem —
 * the hand-built service-account JSON omitted {@code client_id}, which
 * {@code ServiceAccountCredentials.fromJson} requires alongside
 * {@code client_email}, {@code private_key} and {@code private_key_id}. Every
 * key format (real newlines, escaped {@code \n}, quoted) failed identically,
 * which is why the first two tests here construct a genuinely valid key and
 * demand that the SDK accept it.
 *
 * <p>The key material below is generated in memory at test time, so nothing
 * here is, or ever was, a real credential. No secret value is logged.
 */
class FirebaseAdminTokenVerifierTest {

    private static final String PROJECT_ID = "example-test-project";
    private static final String CLIENT_EMAIL =
            "test-sa@example-test-project.iam.gserviceaccount.com";

    /** A fresh PKCS#8 PEM for a throwaway in-process RSA key pair. */
    private static final String SYNTHETIC_PEM;

    /** The same key as it is conventionally carried in an environment variable. */
    private static final String SYNTHETIC_PEM_ESCAPED;

    static {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                    .encodeToString(keyPair.getPrivate().getEncoded());
            SYNTHETIC_PEM = "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";
            SYNTHETIC_PEM_ESCAPED = SYNTHETIC_PEM.replace("\n", "\\n");
        } catch (Exception e) {
            throw new IllegalStateException("Could not generate a synthetic test key", e);
        }
    }

    // ── 1. valid key, real newlines ──────────────────────────────────────────

    @Test
    void aPrivateKeyWithRealNewlinesIsAcceptedByTheAdminSdk() throws Exception {
        GoogleCredentials credentials =
                verifier(PROJECT_ID, CLIENT_EMAIL, SYNTHETIC_PEM).credentials();

        assertThat(credentials).isInstanceOf(ServiceAccountCredentials.class);
        ServiceAccountCredentials serviceAccount = (ServiceAccountCredentials) credentials;
        assertThat(serviceAccount.getClientEmail()).isEqualTo(CLIENT_EMAIL);
        assertThat(serviceAccount.getProjectId()).isEqualTo(PROJECT_ID);
    }

    // ── 2. valid key, literal \n escapes ─────────────────────────────────────

    @Test
    void aPrivateKeyWithEscapedNewlinesIsAcceptedByTheAdminSdk() throws Exception {
        // The conventional single-line form a Render environment variable holds.
        GoogleCredentials credentials =
                verifier(PROJECT_ID, CLIENT_EMAIL, SYNTHETIC_PEM_ESCAPED).credentials();

        assertThat(credentials).isInstanceOf(ServiceAccountCredentials.class);
        assertThat(((ServiceAccountCredentials) credentials).getClientEmail()).isEqualTo(CLIENT_EMAIL);
    }

    @Test
    void theTwoKeySpellingsProduceTheSameAcceptedCredential() throws Exception {
        // Guards the normalisation itself: escaping must be the only difference.
        assertThat(SYNTHETIC_PEM_ESCAPED).isNotEqualTo(SYNTHETIC_PEM).contains("\\n");

        GoogleCredentials fromRealNewlines =
                verifier(PROJECT_ID, CLIENT_EMAIL, SYNTHETIC_PEM).credentials();
        GoogleCredentials fromEscaped =
                verifier(PROJECT_ID, CLIENT_EMAIL, SYNTHETIC_PEM_ESCAPED).credentials();

        assertThat(((ServiceAccountCredentials) fromEscaped).getPrivateKey().getEncoded())
                .isEqualTo(((ServiceAccountCredentials) fromRealNewlines).getPrivateKey().getEncoded());
    }

    // ── 3. missing FIREBASE_PRIVATE_KEY ──────────────────────────────────────

    @Test
    void aMissingPrivateKeyFailsClosedAndNamesTheVariable() {
        assertUnavailableNaming("FIREBASE_PRIVATE_KEY", () ->
                verifier(PROJECT_ID, CLIENT_EMAIL, null).verifyIdToken("a-firebase-id-token"));
    }

    @Test
    void aBlankPrivateKeyCountsAsMissingRatherThanAsAUsableKey() {
        assertUnavailableNaming("FIREBASE_PRIVATE_KEY", () ->
                verifier(PROJECT_ID, CLIENT_EMAIL, "   ").verifyIdToken("a-firebase-id-token"));
    }

    // ── 4. malformed private key ─────────────────────────────────────────────

    @Test
    void aMalformedPrivateKeyFailsClosedRatherThanAuthenticating() {
        String notBase64 = "-----BEGIN PRIVATE KEY-----\nthis-is-not-base64-pkcs8\n-----END PRIVATE KEY-----\n";

        assertCredentialFault(verifier(PROJECT_ID, CLIENT_EMAIL, notBase64), "this-is-not-base64-pkcs8");
    }

    @Test
    void aPrivateKeyThatIsNotAPemAtAllFailsClosed() {
        assertCredentialFault(
                verifier(PROJECT_ID, CLIENT_EMAIL, "definitely-not-a-private-key"), "definitely-not-a-private-key");
    }

    @Test
    void aPrivateKeyWithoutTheEndMarkerFailsClosed() {
        String truncated = SYNTHETIC_PEM.substring(0, SYNTHETIC_PEM.length() / 2);

        assertCredentialFault(verifier(PROJECT_ID, CLIENT_EMAIL, truncated), truncated.trim());
    }

    // ── 5. missing FIREBASE_CLIENT_EMAIL ─────────────────────────────────────

    @Test
    void aMissingClientEmailFailsClosedAndNamesTheVariable() {
        assertUnavailableNaming("FIREBASE_CLIENT_EMAIL", () ->
                verifier(PROJECT_ID, null, SYNTHETIC_PEM).verifyIdToken("a-firebase-id-token"));
    }

    // ── 6. missing FIREBASE_PROJECT_ID ───────────────────────────────────────

    @Test
    void aMissingProjectIdFailsClosedAndNamesTheVariable() {
        assertUnavailableNaming("FIREBASE_PROJECT_ID", () ->
                verifier(null, CLIENT_EMAIL, SYNTHETIC_PEM).verifyIdToken("a-firebase-id-token"));
    }

    /**
     * A misconfigured server must answer 503 (via {@link FirebaseTokenVerifier.Unavailable})
     * naming the variable an operator has not set — never authenticate the request.
     */
    private static void assertUnavailableNaming(String variable, Runnable call) {
        Throwable thrown = catchThrowable(call::run);

        assertThat(thrown)
                .as(variable + " must be reported as a configuration fault, not as a bad token")
                .isInstanceOf(FirebaseTokenVerifier.Unavailable.class)
                .hasMessageContaining(variable)
                .hasMessageContaining("not configured");
        assertThat(thrown.getCause()).as("nothing was parsed, so there is no underlying parse failure").isNull();
    }

    /**
     * A credential that cannot be built is still a credential fault, and its
     * message must not echo the rejected key material.
     */
    private static void assertCredentialFault(FirebaseAdminTokenVerifier verifier, String secretMaterial) {
        Throwable thrown = catchThrowable(() -> verifier.verifyIdToken("a-firebase-id-token"));

        assertThat(thrown)
                .as("a key present but unusable is a credential fault: configuration was complete")
                .isInstanceOf(FirebaseTokenVerifier.Unavailable.class)
                .hasMessageContaining("credentials")
                .hasMessageNotContaining("FIREBASE_PRIVATE_KEY");
        assertThat(thrown.getMessage()).doesNotContain(secretMaterial);
        assertThat(thrown.getCause()).as("the SDK's parse failure must be kept as the cause").isNotNull();
    }

    /**
     * A production credential fault must be DIAGNOSABLE from the server log:
     * the client response is deliberately generic, so the underlying cause
     * (exception class + scrubbed message) is logged at ERROR without ever
     * echoing the rejected key material. Before this test existed, a Render
     * deployment failing credential load produced NOTHING in the server logs —
     * the operator's only clue was the browser's opaque CORS/500 symptom.
     */
    @Test
    void aCredentialLoadFailureIsLoggedWithItsCauseButNeverWithKeyMaterial() throws Exception {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                        .getLogger(FirebaseAdminTokenVerifier.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> events =
                new ch.qos.logback.core.read.ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            // A truncated PEM is a genuine parse failure with distinctive
            // key material that must NOT surface in any log line.
            String truncated = SYNTHETIC_PEM.substring(0, SYNTHETIC_PEM.length() / 2);
            String secretMaterial = truncated.trim();

            FirebaseAdminTokenVerifier verifier = verifier(PROJECT_ID, CLIENT_EMAIL, truncated);
            Throwable thrown = catchThrowable(() -> verifier.verifyIdToken("a-firebase-id-token"));

            assertThat(thrown).isInstanceOf(FirebaseTokenVerifier.Unavailable.class);
            assertThat(thrown.getMessage())
                    .contains("credentials are malformed")
                    .doesNotContain(secretMaterial);

            assertThat(events.list).as("the credential failure must be logged for the operator").isNotEmpty();
            assertThat(events.list.stream().anyMatch(event ->
                    event.getLevel() == ch.qos.logback.classic.Level.ERROR
                            && event.getFormattedMessage().contains("Firebase Admin credential configuration is malformed")
                            && event.getFormattedMessage().contains("IllegalArgumentException"))).isTrue();
            // The rejected key material never reaches the log.
            assertThat(events.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage))
                    .allSatisfy(message -> assertThat(message).doesNotContain(secretMaterial));
        } finally {
            logger.detachAppender(events);
        }
    }

    private static FirebaseAdminTokenVerifier verifier(String projectId, String clientEmail, String privateKey) {
        FirebaseProperties properties = new FirebaseProperties();
        properties.setProjectId(projectId);
        properties.setClientEmail(clientEmail);
        properties.setPrivateKey(privateKey);
        return new FirebaseAdminTokenVerifier(properties);
    }
}
