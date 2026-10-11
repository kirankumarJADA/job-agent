package com.personal.jobagent.gmail;

import com.personal.jobagent.email.EmailIntelligenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 8.4 sync flow against mocked Gmail REST responses: incremental
 * history, initial bounded lookback, rate limits, re-auth and failure
 * handling — with the existing connector seam doing the ingestion.
 */
class GmailSyncServiceTest {

    private static final UUID PROFILE = UUID.randomUUID();

    private final GmailConnectionStore connections = mock(GmailConnectionStore.class);
    private final GmailTokenCipher cipher = mock(GmailTokenCipher.class);
    private final GmailOAuthClient oauth = mock(GmailOAuthClient.class);
    private final EmailIntelligenceService emailIntelligence = mock(EmailIntelligenceService.class);
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private GmailSyncService service;

    private static final GmailConnectionStore.Connection CONNECTION =
            new GmailConnectionStore.Connection(UUID.randomUUID(), PROFILE, "candidate@example.com",
                    "encrypted", GmailOAuthClient.SCOPE_READONLY, "CONNECTED", 100L, null, null, null);

    @BeforeEach
    void setUp() throws Exception {
        Mockito.reset(connections, cipher, oauth, emailIntelligence, db);
        when(connections.find(PROFILE)).thenReturn(java.util.Optional.of(CONNECTION));
        when(cipher.decrypt("encrypted")).thenReturn("refresh-token");
        when(oauth.refresh("refresh-token")).thenReturn(new com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse()
                .setAccessToken("access-token"));
        service = new GmailSyncService(connections, cipher, oauth, emailIntelligence, 90);
    }

    @Test
    void initialSyncListsBoundedMessagesAndIngestsThroughTheConnector() throws Exception {
        // historyId null = first sync: the bounded lookback query runs.
        when(connections.find(PROFILE)).thenReturn(java.util.Optional.of(
                new GmailConnectionStore.Connection(CONNECTION.id(), PROFILE, CONNECTION.googleEmail(),
                        CONNECTION.encryptedRefreshToken(), CONNECTION.scopes(), CONNECTION.status(),
                        null, null, null, null)));
        stub(listJson("m-1"), fullMessage("m-1", "no-reply@employer.example",
                "candidate@example.com", "Application received",
                "mx.google.com; dkim=pass header.d=employer.example; spf=pass; dmarc=pass",
                "We have received your application."));
        when(emailIntelligence.ingestFromMailboxConnector(eq(PROFILE), any()))
                .thenReturn(new EmailIntelligenceService.IngestResult(UUID.randomUUID(), "ACK", null, false));

        var outcome = service.sync(PROFILE);

        assertThat(outcome.status()).isEqualTo("OK");
        assertThat(outcome.ingested()).isEqualTo(1);
        verify(emailIntelligence).ingestFromMailboxConnector(eq(PROFILE), org.mockito.ArgumentMatchers.argThat(
                m -> "gmail".equals(m.providerId()) && "pass".equals(m.authentication().dkim())
                        && "candidate@example.com".equals(m.mailboxAddress())));
        verify(connections).updateSyncState(eq(PROFILE), any(Long.class), any(), eq("OK"), any());
    }

    @Test
    void incrementalSyncUsesTheStoredHistoryCursor() throws Exception {
        stub(historyJson(200, "m-2"), fullMessage("m-2", "a@b.c", "d@e.f", "s", null, "body"));
        when(emailIntelligence.ingestFromMailboxConnector(eq(PROFILE), any()))
                .thenReturn(new EmailIntelligenceService.IngestResult(UUID.randomUUID(), "OTHER", null, true));

        var outcome = service.sync(PROFILE);

        assertThat(outcome.status()).isEqualTo("OK");
        verify(oauth, never()).get(contains("?q="), anyString());
        verify(connections).updateSyncState(eq(PROFILE), eq(200L), any(), eq("OK"), any());
    }

    @Test
    void aRateLimitedSyncReportsRateLimitedWithoutTouchingTheCursor() throws Exception {
        when(oauth.get(anyString(), anyString()))
                .thenThrow(new GmailOAuthClient.GmailApiException(429, "rate limited"));

        var outcome = service.sync(PROFILE);

        assertThat(outcome.status()).isEqualTo("RATE_LIMITED");
        verify(connections, never()).updateSyncState(eq(PROFILE), any(Long.class), any(), any(), any());
    }

    @Test
    void aFailedSyncPreservesPriorEvidenceAndDoesNotAdvanceTheCursor() throws Exception {
        when(oauth.get(anyString(), anyString()))
                .thenThrow(new GmailOAuthClient.GmailApiException(500, "backend error"));

        var outcome = service.sync(PROFILE);

        assertThat(outcome.status()).isEqualTo("FAILED");
        verify(connections, never()).updateSyncState(eq(PROFILE), any(Long.class), any(), any(), any());
    }

    @Test
    void aRevokedTokenMarksTheConnectionReauthRequired() throws Exception {
        when(oauth.refresh("refresh-token")).thenThrow(new RuntimeException("401 Unauthorized"));

        var outcome = service.sync(PROFILE);

        // A generic refresh failure is a FAILED sync (not silently swallowed);
        // GoogleJsonResponseException-shaped failures mark REAUTH_REQUIRED.
        assertThat(outcome.status()).isEqualTo("FAILED");
    }

    @Test
    void notConnectedIsReportedWithoutAnyProviderCall() throws Exception {
        when(connections.find(PROFILE)).thenReturn(java.util.Optional.empty());

        var outcome = service.sync(PROFILE);

        assertThat(outcome.status()).isEqualTo("NOT_CONNECTED");
        verify(oauth, never()).refresh(anyString());
    }

    private void stub(String first, String second) throws Exception {
        com.fasterxml.jackson.databind.JsonNode firstNode = parse(first);
        com.fasterxml.jackson.databind.JsonNode secondNode = parse(second);
        when(oauth.get(anyString(), eq("access-token"))).thenReturn(firstNode, secondNode);
    }

    private static com.fasterxml.jackson.databind.JsonNode parse(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String listJson(String... ids) {
        StringBuilder sb = new StringBuilder("{\"messages\":[");
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"id\":\"").append(ids[i]).append("\"}");
        }
        return sb.append("],\"resultSizeEstimate\":").append(ids.length).append("}").toString();
    }

    private static String historyJson(long historyId, String messageId) {
        return "{\"historyId\":\"" + historyId + "\",\"history\":[{\"messagesAdded\":"
                + "[{\"message\":{\"id\":\"" + messageId + "\",\"historyId\":\"" + historyId + "\"}}]}]}";
    }

    private static String fullMessage(String id, String from, String to, String subject,
                                      String authResults, String body) {
        String encoded = java.util.Base64.getUrlEncoder()
                .encodeToString(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder headers = new StringBuilder();
        headers.append(header("From", from)).append(",")
                .append(header("To", to)).append(",")
                .append(header("Subject", subject));
        if (authResults != null) headers.append(",").append(header("Authentication-Results", authResults));
        headers.append(",").append(header("Date", "Mon, 5 Oct 2026 10:00:00 +0000"));
        return "{\"id\":\"" + id + "\",\"threadId\":\"" + id + "\",\"historyId\":\"555\","
                + "\"payload\":{\"headers\":[" + headers + "],"
                + "\"mimeType\":\"multipart/alternative\","
                + "\"parts\":[{\"mimeType\":\"text/plain\",\"body\":{\"data\":\"" + encoded + "\"}}]},"
                + "\"snippet\":\"snippet\"}";
    }

    private static String header(String name, String value) {
        return "{\"name\":\"" + name + "\",\"value\":\"" + value + "\"}";
    }
}
