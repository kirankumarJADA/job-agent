package com.personal.jobagent.gmail;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Gmail message mapper turns provider-retrieved message resources into
 * connector messages: headers, minimal body, and the Authentication-Results
 * evidence Gmail evaluated at delivery time.
 */
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
                + "\"payload\":{\"headers\":[" + String.join(",", headerObjects) + "],"
                + "\"mimeType\":\"multipart/alternative\","
                + "\"parts\":[{\"mimeType\":\"text/plain\",\"body\":{\"data\":\"" + encodedBody + "\"}}]},"
                + "\"snippet\":\"snippet fallback\"}";
    }

    private static String header(String name, String value) {
        return "{\"name\":\"" + name + "\",\"value\":\"" + value + "\"}";
    }

    @Test
    void mapsHeadersBodyAndSenderAuthentication() throws Exception {
        String json = message("no-reply@jobs.greenhouse.io", "candidate@example.com",
                "Application received", "mx.google.com; dkim=pass header.d=greenhouse.io; spf=pass; dmarc=pass",
                "We have received your application.");
        GmailMessageMapper.GmailMessage mapped = GmailMessageMapper.fromApi(
                new ObjectMapper().readTree(json), "candidate@example.com");

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
    }

    @Test
    void missingAuthenticationResultsYieldsNoneSoReceiptsStayUnverified() throws Exception {
        String json = message("no-reply@unknown.example", "candidate@example.com",
                "Application received", null, "Body");
        GmailMessageMapper.GmailMessage mapped = GmailMessageMapper.fromApi(
                new ObjectMapper().readTree(json), "candidate@example.com");
        assertThat(mapped.authentication().dkim()).isNull();
        assertThat(mapped.authentication().dkimDomain()).isNull();
    }

    @Test
    void receivedAtIsParsedFromTheDateHeader() throws Exception {
        String json = message("a@b.c", "d@e.f", "s", null, "body");
        GmailMessageMapper.GmailMessage mapped = GmailMessageMapper.fromApi(
                new ObjectMapper().readTree(json), "candidate@example.com");
        assertThat(mapped.receivedAt()).isNotNull();
    }

    @Test
    void connectedAddressIsPreservedForDeliveryEvidence() throws Exception {
        String json = message("a@b.c", "candidate@example.com", "s",
                "mx.google.com; dkim=pass header.d=b.c; spf=pass; dmarc=pass", "body");
        GmailMessageMapper.GmailMessage mapped = GmailMessageMapper.fromApi(
                new ObjectMapper().readTree(json), "candidate@example.com");
        assertThat(mapped.connectedAddress()).isEqualTo("candidate@example.com");
    }
}
