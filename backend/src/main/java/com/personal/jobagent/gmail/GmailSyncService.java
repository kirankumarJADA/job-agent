package com.personal.jobagent.gmail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.email.EmailIntelligenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * Incremental, bounded synchronisation of the connected Gmail mailbox into TRACK.
 *
 * <p>The initial list is paginated in batches. The mailbox history cursor is
 * captured from users.getProfile BEFORE the first list request, then retained
 * until every initial page is processed. Incremental history advances only to a
 * completely processed history-record boundary when more pages or a batch limit
 * remain. This prevents a successful partial batch from skipping unseen messages.
 *
 * <p>Only messages that Gmail's structured resource labels as INBOX and not SENT,
 * DRAFT, SPAM or TRASH are passed to receipt verification. Email To/Date headers
 * are not treated as authoritative delivery/time data.
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
        this.lookbackDays = lookbackDays > 0 ? Math.min(lookbackDays, DEFAULT_LOOKBACK_DAYS) : DEFAULT_LOOKBACK_DAYS;
    }

    public record SyncOutcome(String status, int ingested, int considered, Long historyId, String error) {}

    public SyncOutcome sync(UUID profileId) {
        GmailConnectionStore.Connection connection = connections.find(profileId).orElse(null);
        if (connection == null || !connection.active()) {
            return new SyncOutcome("NOT_CONNECTED", 0, 0, null, "No active Gmail connection");
        }

        try {
            String refreshToken = cipher.decrypt(connection.encryptedRefreshToken());
            String accessToken = accessToken(profileId, refreshToken);
            String initialPageToken = connections.initialPageToken(profileId);
            boolean initialSync = connection.historyId() == null || connection.historyId() <= 0
                    || initialPageToken != null;

            JsonNode history = null;
            if (!initialSync) {
                try {
                    history = oauth.get("/users/me/history?startHistoryId=" + connection.historyId()
                            + "&historyTypes=messageAdded&maxResults=100", accessToken);
                } catch (GmailOAuthClient.GmailApiException e) {
                    if (e.status == 404) {
                        // An expired cursor is not a permanent failure: start a new
                        // bounded initial scan from a fresh, provider-reported cursor.
                        initialSync = true;
                        initialPageToken = null;
                    } else {
                        throw e;
                    }
                }
            }

            List<String> messageIds = new ArrayList<>();
            long newHistoryId;
            String nextInitialPageToken = null;
            boolean partial = false;

            if (initialSync) {
                if (connection.historyId() == null || connection.historyId() <= 0 || initialPageToken == null) {
                    JsonNode profile = oauth.get("/users/me/profile", accessToken);
                    newHistoryId = requiredHistoryId(profile);
                } else {
                    // Continue an initial lookback scan against the original cursor.
                    newHistoryId = connection.historyId();
                }

                JsonNode listed;
                try {
                    listed = initialList(accessToken, initialPageToken);
                } catch (GmailOAuthClient.GmailApiException e) {
                    if (e.status == 400 && initialPageToken != null) {
                        // Expired pagination token: safely restart the bounded
                        // idempotent list from a fresh cursor rather than loop forever.
                        JsonNode profile = oauth.get("/users/me/profile", accessToken);
                        newHistoryId = requiredHistoryId(profile);
                        listed = initialList(accessToken, null);
                    } else {
                        throw e;
                    }
                }
                for (JsonNode message : listed.path("messages")) {
                    String id = message.path("id").asText("");
                    if (!id.isBlank()) messageIds.add(id);
                }
                nextInitialPageToken = listed.path("nextPageToken").asText(null);
                partial = nextInitialPageToken != null;
            } else {
                LinkedHashSet<String> selectedIds = new LinkedHashSet<>();
                long boundary = connection.historyId();
                boolean batchLimitReached = false;
                JsonNode records = history.path("history");
                if (records.isArray()) {
                    for (JsonNode record : records) {
                        LinkedHashSet<String> recordIds = new LinkedHashSet<>();
                        for (JsonNode added : record.path("messagesAdded")) {
                            JsonNode message = added.path("message");
                            String id = message.path("id").asText("");
                            if (!id.isBlank() && GmailMessageMapper.isInboxReceived(message)) recordIds.add(id);
                        }

                        // Never split one history record: nextHistoryId must not jump
                        // past messages in that same record. If a single record
                        // itself has >50 messages, process it whole to preserve truth.
                        if (!selectedIds.isEmpty()
                                && selectedIds.size() + recordIds.size() > MAX_MESSAGES_PER_SYNC) {
                            batchLimitReached = true;
                            break;
                        }
                        selectedIds.addAll(recordIds);
                        long recordId = record.path("id").asLong(boundary);
                        if (recordId > 0) boundary = recordId;
                    }
                }
                messageIds.addAll(selectedIds);
                boolean hasNextHistoryPage = history.hasNonNull("nextPageToken");
                partial = batchLimitReached || hasNextHistoryPage;
                newHistoryId = partial ? boundary : history.path("historyId").asLong(boundary);
                if (newHistoryId <= 0) {
                    throw new IllegalStateException("Gmail history response did not include a usable history cursor");
                }
            }

            int ingested = 0;
            int considered = 0;
            int fetched = 0;
            for (String id : messageIds) {
                if (fetched >= MAX_MESSAGES_PER_SYNC && !initialSync) break;
                if (fetched >= MAX_MESSAGES_PER_SYNC) break;
                JsonNode message = oauth.get("/users/me/messages/" + encodePathSegment(id) + "?format=full", accessToken);
                fetched++;
                if (!GmailMessageMapper.isInboxReceived(message)) continue;
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

            String status = partial ? "PARTIAL" : "OK";
            if (initialSync) {
                // Cursor snapshot + pagination state are committed atomically.
                connections.updateInitialSyncState(profileId, newHistoryId, Instant.now(),
                        status, null, nextInitialPageToken);
            } else {
                connections.updateSyncState(profileId, newHistoryId, Instant.now(), status, null);
            }
            log.info("Gmail sync for profile {}: fetched {} message(s), considered {} inbox message(s), ingested {}",
                    profileId, fetched, considered, ingested);
            return new SyncOutcome(status, ingested, considered, newHistoryId,
                    partial ? "More messages remain; run sync again to continue safely." : null);
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
            connections.updateSyncState(profileId, null, null, "FAILED", "Gmail API HTTP " + e.status);
            log.warn("Gmail sync failed for profile {}: HTTP {}", profileId, e.status);
            return new SyncOutcome("FAILED", 0, 0, null, "Gmail API HTTP " + e.status);
        } catch (Exception e) {
            connections.updateSyncState(profileId, null, null, "FAILED", safe(e.getMessage()));
            log.warn("Gmail sync failed for profile {}: {}", profileId, safe(e.getMessage()));
            return new SyncOutcome("FAILED", 0, 0, null, safe(e.getMessage()));
        }
    }

    private JsonNode initialList(String accessToken, String pageToken) throws Exception {
        String query = "in:inbox newer_than:" + lookbackDays + "d";
        String path = "/users/me/messages?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&maxResults=" + MAX_MESSAGES_PER_SYNC;
        if (pageToken != null && !pageToken.isBlank()) {
            path += "&pageToken=" + URLEncoder.encode(pageToken, StandardCharsets.UTF_8);
        }
        return oauth.get(path, accessToken);
    }

    private static long requiredHistoryId(JsonNode profile) {
        long historyId = profile.path("historyId").asLong(0);
        if (historyId <= 0) {
            throw new IllegalStateException("Gmail profile response did not include a usable history cursor");
        }
        return historyId;
    }

    private String accessToken(UUID profileId, String refreshToken) throws Exception {
        try {
            String token = oauth.refresh(refreshToken).getAccessToken();
            if (token == null || token.isBlank()) throw new IllegalStateException("Google returned an empty access token");
            return token;
        } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
            if (e.getStatusCode() == 401 || e.getStatusCode() == 400) {
                connections.markReauthRequired(profileId, "Google refused the stored refresh token");
            }
            throw e;
        }
    }

    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String safe(String message) {
        return message == null ? "unknown error" : message.substring(0, Math.min(message.length(), 300));
    }
}
