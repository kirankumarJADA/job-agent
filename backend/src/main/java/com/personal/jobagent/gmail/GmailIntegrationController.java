package com.personal.jobagent.gmail;

import com.personal.jobagent.audit.AuditEntry;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.security.OwnerContext;
import com.personal.jobagent.security.RateLimitService;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * /api/v1/integrations/gmail — OAuth connection, sync and disconnect for the
 * candidate's own Gmail mailbox (read-only, TRACK workflow).
 *
 * <p>Security posture: every route requires an authenticated session; the
 * OAuth state binds the consent round-trip to the signed-in profile (HMAC
 * signed, 10-minute expiry) so credentials can never be linked to the wrong
 * user; the redirect target is fixed by the server and never taken from the
 * request; manual syncs are rate-limited per profile; disconnect revokes the
 * grant server-side, deletes the stored credentials and stops all further
 * synchronisation. Every connection, sync and disconnect action is audited.
 */
@RestController
@RequestMapping("/api/v1/integrations/gmail")
public class GmailIntegrationController {

    private final GmailOAuthClient oauth;
    private final GmailStateSigner stateSigner;
    private final GmailConnectionStore connections;
    private final GmailTokenCipher cipher;
    private final GmailSyncService syncService;
    private final RateLimitService rateLimiter;
    private final AuditLogWriter audit;
    private final OwnerContext ownerContext;
    private final String configuredRedirectUri;
    private final int expireDays;

    public GmailIntegrationController(GmailOAuthClient oauth, GmailStateSigner stateSigner,
                                      GmailConnectionStore connections, GmailTokenCipher cipher,
                                      GmailSyncService syncService, RateLimitService rateLimiter,
                                      AuditLogWriter audit, OwnerContext ownerContext,
                                      @Value("${app.gmail.redirect-uri:}") String configuredRedirectUri,
                                      @Value("${app.review.expire-days:14}") int expireDays) {
        this.oauth = oauth;
        this.stateSigner = stateSigner;
        this.connections = connections;
        this.cipher = cipher;
        this.syncService = syncService;
        this.rateLimiter = rateLimiter;
        this.audit = audit;
        this.ownerContext = ownerContext;
        this.configuredRedirectUri = configuredRedirectUri == null ? "" : configuredRedirectUri.trim();
        this.expireDays = expireDays;
    }

    private boolean configured() {
        return oauth.configured() && !configuredRedirectUri.isEmpty();
    }

    private UUID profileId() {
        return ownerContext.profileIdOrNull();
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        UUID profile = profileId();
        if (profile == null) return ResponseEntity.badRequest().body(Map.of("error", "a profile is required"));
        GmailConnectionStore.Connection connection = connections.find(profile).orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configured", configured());
        out.put("connected", connection != null && connection.active());
        if (connection != null) {
            out.put("accountEmail", connection.googleEmail());
            out.put("scopes", connection.scopes());
            out.put("status", connection.status());
            out.put("lastSyncAt", connection.lastSyncAt());
            out.put("lastSyncStatus", connection.lastSyncStatus());
            out.put("lastSyncError", connection.lastSyncError());
        }
        return ResponseEntity.ok(out);
    }

    /** Redirects the signed-in owner to Google's consent screen. Server-fixed redirect URI. */
    @GetMapping("/authorize")
    public ResponseEntity<?> authorize() {
        if (!configured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error",
                    "Gmail integration is not configured on this deployment"));
        }
        UUID profile = profileId();
        if (profile == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "a profile is required"));
        }
        String state = stateSigner.sign(profile);
        audit.write(new AuditEntry(ownerContext.actorOr("user"), "GMAIL_CONNECT_STARTED", "PROFILE",
                profile, null, Map.of("scope", GmailOAuthClient.SCOPE_READONLY), null, UuidV7.generate()));
        return ResponseEntity.status(302)
                .header("Location", oauth.authorizeUrl(state, ownerContext.actorOr(null)))
                .build();
    }

    /**
     * OAuth callback. The state is verified against the signed-in profile;
     * the code is exchanged server-side; the granted scopes are re-checked to
     * be read-only; the mailbox identity is confirmed from Google's profile;
     * and the refresh token is stored encrypted. The redirect target is fixed
     * by the server — never from the request.
     */
    @GetMapping("/callback")
    public ResponseEntity<?> callback(@RequestParam(required = false) String code,
                                      @RequestParam(required = false) String state,
                                      @RequestParam(required = false) String error,
                                      HttpServletRequest request) {
        if (!configured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error",
                    "Gmail integration is not configured on this deployment"));
        }
        UUID profile = profileId();
        if (profile == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "a profile is required"));
        }
        if (error != null) {
            audit.write(new AuditEntry(ownerContext.actorOr("user"), "GMAIL_CONNECT_REFUSED", "PROFILE",
                    profile, null, Map.of("reason", error), request.getRemoteAddr(), UuidV7.generate()));
            return redirectFrontend("gmail=refused");
        }
        if (stateSigner.verify(state, profile) == null) {
            audit.write(new AuditEntry(ownerContext.actorOr("user"), "GMAIL_CONNECT_INVALID_STATE", "PROFILE",
                    profile, null, Map.of(), request.getRemoteAddr(), UuidV7.generate()));
            return redirectFrontend("gmail=invalid_state");
        }
        try {
            GoogleTokenResponse tokens = oauth.exchangeCode(code);
            GmailOAuthClient.assertReadonlyScopes(tokens.getScope());
            String googleEmail = verifyIdentity(tokens.getAccessToken());

            connections.upsertConnected(profile, googleEmail,
                    cipher.encrypt(tokens.getRefreshToken()), tokens.getScope());
            audit.write(new AuditEntry(ownerContext.actorOr("user"), "GMAIL_CONNECTED", "PROFILE",
                    profile, null,
                    Map.of("accountEmail", googleEmail, "scope", GmailOAuthClient.SCOPE_READONLY),
                    request.getRemoteAddr(), UuidV7.generate()));
            return redirectFrontend("gmail=connected");
        } catch (Exception e) {
            audit.write(new AuditEntry(ownerContext.actorOr("user"), "GMAIL_CONNECT_FAILED", "PROFILE",
                    profile, null, Map.of("reason", safeReason(e)), request.getRemoteAddr(), UuidV7.generate()));
            return redirectFrontend("gmail=failed");
        }
    }

    /** Confirms the mailbox identity from Google's own profile endpoint. */
    private String verifyIdentity(String accessToken) throws Exception {
        var profile = oauth.get("/users/me/profile", accessToken);
        String address = profile.path("emailAddress").asText("");
        if (address.isBlank()) throw new IllegalStateException("Google did not confirm the mailbox identity");
        return address;
    }

    /** Manual incremental sync, rate-limited per profile. */
    @PostMapping("/sync")
    public ResponseEntity<?> sync(HttpServletRequest request) {
        UUID profile = profileId();
        if (profile == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "a profile is required"));
        }
        long retryAfter = rateLimiter.checkAndRecord("gmail-sync", "u:" + profile);
        if (retryAfter >= 0) {
            return ResponseEntity.status(429)
                    .header("Retry-After", String.valueOf(retryAfter))
                    .body(Map.of("error", "sync rate limit reached — retry after " + retryAfter + " seconds"));
        }
        GmailSyncService.SyncOutcome outcome = syncService.sync(profile);
        audit.write(new AuditEntry(ownerContext.actorOr("user"), "GMAIL_SYNC", "PROFILE",
                profile, null,
                Map.of("status", outcome.status(), "ingested", outcome.ingested(),
                        "considered", outcome.considered()),
                request.getRemoteAddr(), UuidV7.generate()));
        if ("FAILED".equals(outcome.status()) || "REAUTH_REQUIRED".equals(outcome.status())) {
            return ResponseEntity.status(502).body(Map.of(
                    "status", outcome.status(), "error", outcome.error(),
                    "ingested", outcome.ingested(), "considered", outcome.considered()));
        }
        return ResponseEntity.ok(Map.of(
                "status", outcome.status(),
                "ingested", outcome.ingested(),
                "considered", outcome.considered(),
                "lastSyncAt", Instant.now().toString()));
    }

    /** Disconnect: revokes the grant server-side, deletes stored credentials, stops sync. */
    @PostMapping("/disconnect")
    public ResponseEntity<?> disconnect(HttpServletRequest request) {
        UUID profile = profileId();
        if (profile == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "a profile is required"));
        }
        GmailConnectionStore.Connection connection = connections.find(profile).orElse(null);
        if (connection != null) {
            try {
                oauth.revoke(cipher.decrypt(connection.encryptedRefreshToken()));
            } catch (Exception ignored) {
                // The stored credentials are deleted regardless; revocation of a
                // rotated/unreadable token is best-effort.
            }
            connections.delete(profile);
            audit.write(new AuditEntry(ownerContext.actorOr("user"), "GMAIL_DISCONNECTED", "PROFILE",
                    profile, null, Map.of("accountEmail", connection.googleEmail()),
                    request.getRemoteAddr(), UuidV7.generate()));
        }
        return ResponseEntity.noContent().build();
    }

    /** The frontend redirect target is fixed by the server; query is a status hint only. */
    private ResponseEntity<?> redirectFrontend(String statusHint) {
        return ResponseEntity.status(302)
                .header("Location", "/applications?track&" + statusHint)
                .build();
    }

    private static String safeReason(Exception e) {
        String message = e.getMessage();
        return message == null ? "unknown" : message.substring(0, Math.min(message.length(), 200));
    }
}
