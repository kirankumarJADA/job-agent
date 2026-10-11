package com.personal.jobagent.gmail;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class GmailMessageMapperTest {

    private static String message(String from, String to, String subject, String authResults, String bodyPlain) {
        java.util.List<String> headerObjects = new java.util.ArrayList<>();
        headerObjects.add(header("From", from));
        headerObjects.add(header("To", to));
        headerObjects.add(header("Subject", subject));
        headerObjects.add(header("Date", "Mon, 5 Oct 2026 10:00:00 +0000"));
        if (authResults != null) headerObjects.add(header("Authentication-Results", authResults));
        String encodedBody = java.util.Base64.getUrlEncoder()
                .encodeToString(bodyPlain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return "{\"id\":\"msg-1\",\"threadId\":\"th-1\",\"historyId\":\"42\","
                + "\"internalDate\":\"1791194400000\",\"labelIds\":[\"INBOX\"],"
                + "\"payload\":{\"headers\":[" + String.join(",", headerObjects) + "],"
                + "\"mimeType\":\"multipart/alternative\","
                + "\"parts\":[{\"mimeType\":\"text/plain\",\"body\":{\"data\":\"" + encodedBody + "\"}}]},"
                + "\"snippet\":\"snippet fallback\"}";
    }

    private static String header(String name, String value) {
        return "{\"name\":\"" + name + "\",\"value\":\"" + value + "\"}";
    }

    private static com.fasterxml.jackson.databind.JsonNode parse(String json) throws Exception {
        return new ObjectMapper().readTree(json);
    }

    @Test
    void mapsHeadersBodyAndTrustedGmailAuthentication() throws Exception {
        String json = message("no-reply@jobs.greenhouse.io", "candidate@example.com",
                "Application received", "mx.google.com; dkim=pass header.d=greenhouse.io; spf=pass; dmarc=pass",
                "We have received your application.");
        GmailMessageMapper.GmailMessage mapped = GmailMessageMapper.fromApi(
                parse(json), "candidate@example.com");

        assertThat(mapped.messageId()).isEqualTo("msg-1");
        assertThat(mapped.from()).contains("no-reply@jobs.greenhouse.io");
        assertThat(mapped.to()).isEqualTo("candidate@example.com");
        assertThat(mapped.subject()).isEqualTo("Application received");
        assertThat(mapped.bodyText()).contains("We have received your application.");
        assertThat(mapped.authentication().dkim()).isEqualTo("pass");
        assertThat(mapped.authentication().dkimDomain()).isEqualTo("greenhouse.io");
        assertThat(mapped.authentication().spf()).isEqualTo("pass");
        assertThat(mapped.authentication().dmarc()).isEqualTo("pass");
        assertThat(mapped.connectedAddress()).isEqualTo("candidate@example.com");
        assertThat(mapped.receivedAt()).isEqualTo(Instant.ofEpochMilli(1791194400000L));
    }

    @Test
    void missingAuthenticationResultsYieldsNoneSoReceiptsStayUnverified() throws Exception {
        GmailMessageMapper.GmailMessage mapped = GmailMessageMapper.fromApi(
                parse(message("no-reply@unknown.example", "candidate@example.com", "Application received", null, "Body")),
                "candidate@example.com");
        assertThat(mapped.authentication().dkim()).isNull();
        assertThat(mapped.authentication().dkimDomain()).isNull();
    }

    @Test
    void arbitraryAuthenticationResultsCannotEstablishSenderAuthentication() throws Exception {
        GmailMessageMapper.GmailMessage mapped = GmailMessageMapper.fromApi(
                parse(message("no-reply@employer.example", "candidate@example.com", "Application received",
                        "attacker.example; dkim=pass header.d=employer.example; spf=pass; dmarc=pass", "Body")),
                "candidate@example.com");
        assertThat(mapped.authentication().dkim()).isNull();
        assertThat(mapped.authentication().dkimDomain()).isNull();
    }

    @Test
    void inboxDeliveryUsesGmailLabelsRatherThanSenderControlledRecipientHeaders() throws Exception {
        String sent = message("candidate@example.com", "candidate@example.com", "Application received",
                "mx.google.com; dkim=pass header.d=example.com; spf=pass; dmarc=pass",
                "We have received your application.").replace("\"labelIds\":[\"INBOX\"]",
                "\"labelIds\":[\"SENT\"]");
        var node = parse(sent);
        GmailMessageMapper.GmailMessage mapped = GmailMessageMapper.fromApi(node, "candidate@example.com");
        assertThat(GmailMessageMapper.isInboxReceived(node)).isFalse();
        assertThat(mapped.to()).isEqualTo("candidate@example.com");
    }

    @Test
    void missingInternalDateDoesNotTrustTheSenderControlledDateHeader() throws Exception {
        String noInternalDate = message("a@b.c", "candidate@example.com", "s", null, "body")
                .replace("\"internalDate\":\"1791194400000\",", "");
        var mapped = GmailMessageMapper.fromApi(parse(noInternalDate), "candidate@example.com");
        assertThat(mapped.receivedAt()).isNull();
    }
}
