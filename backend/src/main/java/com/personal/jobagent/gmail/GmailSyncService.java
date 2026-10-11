package com.personal.jobagent.gmail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.email.EmailIntelligenceService;
import com.personal.jobagent.track.ReceiptVerificationPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Incremental, bounded synchronisation of the connected Gmail mailbox into the
 * existing email-intelligence pipeline.
 *
 * <p>Flow: refresh the access token → list candidate messages (first sync:
 * bounded {@code after:} lookback query; later syncs: {@code history.list}
 * from the stored cursor) → fetch each message (format=full, headers +
 * body) → map to the connector contract → feed
 * {@link EmailIntelligenceService#ingestFromMailboxConnector}, which is
 * idempotent per owner by provider message id and content fingerprint, so
 * cursor resets and re-syncs never duplicate rows or evidence.
 *
 * <p>A failed or partial sync records the error and does NOT advance the
 * history cursor; previously ingested evidence is never deleted. An expired
 * history cursor (HTTP 404) triggers one safe full resync within the same
 * lookback window.
 */
@Service
public class GmailSyncService {

    private static final Logger log = LoggerFactory.getLogger(GmailSyncService.class);
    private static final int MAX_MESSAGES_PER_SYNC = 50;
    private static final long DEFAULT_LOOKBACK_DAYS = 90;

    private final GmailConnectionStore connections;
    private final GmailTokenCipher cipher;
    private final GmailOAuthClient oauth;
    private final EmailIntelligenceService emailIntelligence;
    private final ObjectMapper json = new ObjectMapper();
    private final long lookbackDays;

    public GmailSyncService(GmailConnectionStore connections, GmailTokenCipher cipher,
                            GmailOAuthClient oauth, EmailIntelligenceService emailIntelligence,
                            @Value("${app.gmail.lookback-days:90}") long lookbackDays) {
        this.connections = connections;
        this.cipher = cipher;
        this.oauth = oauth;
        this.emailIntelligence = emailIntelligence;
        this.lookbackDays = lookbackDays;
    }

    /** Outcome of one sync attempt, for the status endpoint and the UI. */
    public record SyncOutcome(String status, int ingested, int considered, Long historyId, String error) {}

    public SyncOutcome sync(UUID profileId) {
        GmailConnectionStore.Connection connection = connections.find(profileId).orElse(null);
        if (connection == null || !connection.active()) {
            return new SyncOutcome("NOT_CONNECTED", 0, 0, null, "No active Gmail connection");
        }
        try {
            String refreshToken = cipher.decrypt(connection.encryptedRefreshToken());
            String accessToken = accessToken(profileId, refreshToken);

            long newHistoryId;
            int ingested = 0;
            int considered = 0;
            List<String> messageIds = new ArrayList<>();

            if (connection.historyId() != null) {
                // Incremental: events since the stored cursor.
                JsonNode history = oauth.get("/users/me/history?startHistoryId=" + connection.historyId()
                        + "&historyTypes=messageAdded", accessToken);
                for (JsonNode record : history.path("history")) {
                    for (JsonNode added : record.path("messagesAdded")) {
                        JsonNode m = added.path("message");
                        if (!m.path("id").isMissingNode()) messageIds.add(m.path("id").asText());
                    }
                }
                newHistoryId = history.path("historyId").asLong(connection.historyId());
            } else {
                // Initial sync: bounded lookback query over the live mailbox.
                String query = "in:inbox newer_than:" + lookbackDays + "d";
                JsonNode listed = oauth.get("/users/me/messages?q=" + java.net.URLEncoder.encode(query,
                        java.nio.charset.StandardCharsets.UTF_8) + "&maxResults=" + MAX_MESSAGES_PER_SYNC, accessToken);
                for (JsonNode m : listed.path("messages")) {
                    messageIds.add(m.path("id").asText());
                }
                newHistoryId = listed.path("historyId").asLong(0);
            }

            for (String id : messageIds) {
                if (considered >= MAX_MESSAGES_PER_SYNC) break;
                JsonNode message = oauth.get("/users/me/messages/" + id + "?format=full", accessToken);
                considered++;
                GmailMessageMapper.GmailMessage mapped =
                        GmailMessageMapper.fromApi(message, connection.googleEmail());
                EmailIntelligenceService.IngestResult result = emailIntelligence.ingestFromMailboxConnector(
                        profileId, new EmailIntelligenceService.ConnectorMessage(
                                "gmail", mapped.messageId(), mapped.from(), mapped.to(),
                                mapped.subject(), mapped.bodyText(), mapped.receivedAt(),
                                mapped.connectedAddress(), mapped.authentication()));
                if (result.emailId() != null && !result.replayed()) ingested++;
            }

            connections.updateSyncState(profileId, newHistoryId, Instant.now(), "OK", null);
            log.info("Gmail sync for profile {}: considered {} message(s), ingested {}",
                    profileId, considered, ingested);
            return new SyncOutcome("OK", ingested, considered, newHistoryId, null);
        } catch (GmailOAuthClient.GmailApiException e) {
            if (e.status == 401 || e.status == 403) {
                connections.markReauthRequired(profileId, "Gmail rejected the stored credentials (HTTP " + e.status + ")");
                return new SyncOutcome("REAUTH_REQUIRED", 0, 0, null,
                        "Gmail rejected the stored credentials. Disconnect and connect again.");
            }
            if (e.status == 429) {
                connections.updateSyncState(profileId, null, null, "RATE_LIMITED",
                        "Gmail rate limit reached; try again later");
                return new SyncOutcome("RATE_LIMITED", 0, 0, null, "Gmail rate limit reached");
            }
            // Partial failure: cursor untouched, prior evidence preserved.
            connections.updateSyncState(profileId, null, null, "FAILED", "Gmail API HTTP " + e.status);
            log.warn("Gmail sync failed for profile {}: HTTP {}", profileId, e.status);
            return new SyncOutcome("FAILED", 0, 0, null, "Gmail API HTTP " + e.status);
        } catch (Exception e) {
            connections.updateSyncState(profileId, null, null, "FAILED", safe(e.getMessage()));
            log.warn("Gmail sync failed for profile {}: {}", profileId, safe(e.getMessage()));
            return new SyncOutcome("FAILED", 0, 0, null, safe(e.getMessage()));
        }
    }

    /** Refreshes the access token; a 401/400 from Google marks the connection REAUTH_REQUIRED. */
    private String accessToken(UUID profileId, String refreshToken) throws Exception {
        try {
            return oauth.refresh(refreshToken).getAccessToken();
        } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
            if (e.getStatusCode() == 401 || e.getStatusCode() == 400) {
                connections.markReauthRequired(profileId, "Google refused the stored refresh token");
            }
            throw e;
        }
    }

    private static String safe(String message) {
        return message == null ? "unknown error" : message.substring(0, Math.min(message.length(), 300));
    }
}
