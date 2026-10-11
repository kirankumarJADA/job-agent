package com.personal.jobagent.gmail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeTokenRequest;
import com.google.api.client.googleapis.auth.oauth2.GoogleRefreshTokenRequest;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

/**
 * OAuth + REST transport for the Gmail integration, built on the official
 * Google OAuth client (already on the classpath via firebase-admin) and the
 * documented Gmail REST endpoints.
 *
 * <p>Scopes are read-only by construction: the authorize URL hard-codes
 * {@code https://www.googleapis.com/auth/gmail.readonly} and the granted
 * scopes are re-checked at token exchange. Robin never requests a scope that
 * could send, delete, label or modify mail, and no code path calls any Gmail
 * write endpoint.
 */
@Component
public class GmailOAuthClient {

    public static final String SCOPE_READONLY = "https://www.googleapis.com/auth/gmail.readonly";

    private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    private static final String REVOKE_ENDPOINT = "https://oauth2.googleapis.com/revoke";
    private static final String GMAIL_API = "https://gmail.googleapis.com/gmail/v1";

    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public GmailOAuthClient(@Value("${app.gmail.client-id:}") String clientId,
                            @Value("${app.gmail.client-secret:}") String clientSecret,
                            @Value("${app.gmail.redirect-uri:}") String redirectUri) {
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
        this.redirectUri = redirectUri == null ? "" : redirectUri.trim();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public boolean configured() {
        return !clientId.isEmpty() && !clientSecret.isEmpty() && !redirectUri.isEmpty();
    }

    public String clientId() {
        return clientId;
    }

    /** The consent URL. {@code state} is signed upstream; {@code loginHint} is optional. */
    public String authorizeUrl(String state, String loginHint) {
        StringBuilder url = new StringBuilder(
                "https://accounts.google.com/o/oauth2/v2/auth?response_type=code")
                .append("&client_id=").append(urlEncode(clientId))
                .append("&redirect_uri=").append(urlEncode(redirectUri))
                .append("&scope=").append(urlEncode(SCOPE_READONLY))
                .append("&state=").append(urlEncode(state))
                .append("&access_type=offline")
                .append("&prompt=consent");
        if (loginHint != null && !loginHint.isBlank()) {
            url.append("&login_hint=").append(urlEncode(loginHint.trim()));
        }
        return url.toString();
    }

    /** Exchanges an authorization code for tokens; the refresh token is required (offline access). */
    public GoogleTokenResponse exchangeCode(String code) throws Exception {
        return new GoogleAuthorizationCodeTokenRequest(
                new NetHttpTransport(), new GsonFactory(),
                TOKEN_ENDPOINT, clientId, clientSecret, code, redirectUri).execute();
    }

    /** Refreshes an access token from the stored refresh token. */
    public GoogleTokenResponse refresh(String refreshToken) throws Exception {
        // GoogleRefreshTokenRequest targets Google's token endpoint itself.
        return new GoogleRefreshTokenRequest(
                new NetHttpTransport(), new GsonFactory(),
                refreshToken, clientId, clientSecret).execute();
    }

    /** Revokes the grant server-side (used on disconnect). Best-effort upstream. */
    public void revoke(String refreshToken) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(REVOKE_ENDPOINT))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "token=" + java.net.URLEncoder.encode(refreshToken, java.nio.charset.StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(10))
                    .build();
            http.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
            // Revocation is best-effort: local credentials are deleted regardless.
        }
    }

    /** Builds a path under the fixed Gmail API origin. Package-private for contract tests. */
    static URI apiUri(String pathWithQuery) {
        if (pathWithQuery == null || !pathWithQuery.startsWith("/")
                || pathWithQuery.startsWith("//") || pathWithQuery.contains("#")) {
            throw new IllegalArgumentException("Gmail API path must be an absolute path under the fixed API origin");
        }
        return URI.create(GMAIL_API + pathWithQuery);
    }

    /** GET on the Gmail REST API with a Bearer access token; returns the parsed JSON tree. */
    public JsonNode get(String pathWithQuery, String accessToken) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(apiUri(pathWithQuery))
                .header("Authorization", "Bearer " + accessToken)
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new GmailApiException(response.statusCode(), response.body());
        }
        return json.readTree(response.body());
    }

    public record TokenSet(String accessToken, String refreshToken, long expiresInSeconds, String scope) {}

    /**
     * Re-checks explicit granted scopes at exchange time. OAuth 2.0 permits
     * Google's token response to omit "scope" when it is identical to the
     * requested scope; in that case the consent request above asked for exactly
     * gmail.readonly and did not use include_granted_scopes. When Google does
     * return a scope list, fail closed unless it contains exactly the one
     * requested read-only scope (substring matching could admit gmail.send).
     */
    public static void assertReadonlyScopes(String grantedScope) {
        if (grantedScope == null || grantedScope.isBlank()) {
            return;
        }
        java.util.Set<String> granted = new java.util.HashSet<>(
                java.util.Arrays.asList(grantedScope.trim().split("\\s+")));
        if (!granted.equals(java.util.Set.of(SCOPE_READONLY))) {
            throw new IllegalStateException("Gmail consent granted unexpected scopes; only gmail.readonly is permitted");
        }
    }

    /** Distinguishes credential faults (re-auth needed) from other API errors. */
    public static class GmailApiException extends RuntimeException {
        public final int status;

        public GmailApiException(int status, String body) {
            super("Gmail API returned HTTP " + status
                    + (body == null || body.length() > 200 ? "" : ": " + truncate(body)));
            this.status = status;
        }

        private static String truncate(String body) {
            return body.length() > 200 ? body.substring(0, 200) : body;
        }
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
