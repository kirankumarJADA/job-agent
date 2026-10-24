package com.personal.jobagent.mailbox.managed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.security.SecretCipher;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Mailgun's retry contract: 200 and 406 stop retries, everything else is retried. */
class MailgunInboundControllerTest {

    private static final String KEY = "unit-signing-key";
    private static final SecretCipher CIPHER = new SecretCipher("6M5mXo2C0eP1Ztq0QzZ0n7cYk1o0r8sB3m6Gq9v2T8U=");

    private static ManagedMailSettings configured() {
        return new ManagedMailSettings("mail.robin.example", "mailgun", KEY, 900, 120, 30, CIPHER);
    }

    private static MockHttpServletRequest signedRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", MailgunInboundController.PATH);
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String token = "t".repeat(50);
        request.addParameter("timestamp", timestamp);
        request.addParameter("token", token);
        request.addParameter("signature", MailgunSignatureVerifier.hmacHex(KEY, timestamp + token));
        request.addParameter("recipient", "u7kq2m9x4vb3tn5p@mail.robin.example");
        request.addParameter("from", "no-reply@employer.example");
        request.addParameter("body-plain", "hello");
        return request;
    }

    @Test
    void anUnconfiguredDeploymentAsksForARetryAndReadsNothing() {
        ManagedInboundService inbound = mock(ManagedInboundService.class);
        var controller = new MailgunInboundController(
                new ManagedMailSettings("", "mailgun", "", 900, 120, 30, new SecretCipher("")), inbound, new ObjectMapper());
        assertThat(controller.receive(signedRequest()).getStatusCode().value()).isEqualTo(503);
        verifyNoInteractions(inbound);
    }

    @Test
    void aBodyTheServerCouldNotParseIsRefusedWithoutRetry() {
        ManagedInboundService inbound = mock(ManagedInboundService.class);
        MockHttpServletRequest request = signedRequest();
        request.setAttribute("org.apache.catalina.parameter_parse_failed", Boolean.TRUE);
        var response = new MailgunInboundController(configured(), inbound, new ObjectMapper()).receive(request);
        assertThat(response.getStatusCode().value()).isEqualTo(406);
        verifyNoInteractions(inbound);
    }

    @Test
    void aStorageFailureAsksForARetrySoNoMailIsLost() {
        ManagedInboundService inbound = mock(ManagedInboundService.class);
        when(inbound.accept(any(), anyString(), any(), anyString()))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));
        var response = new MailgunInboundController(configured(), inbound, new ObjectMapper()).receive(signedRequest());
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).containsEntry("status", "retry");
    }

    @Test
    void outcomesMapToMailgunStatusCodes() {
        ManagedInboundService inbound = mock(ManagedInboundService.class);
        var controller = new MailgunInboundController(configured(), inbound, new ObjectMapper());
        when(inbound.accept(any(), anyString(), any(), anyString())).thenReturn(ManagedInboundService.Outcome.STORED);
        assertThat(controller.receive(signedRequest()).getBody()).containsEntry("status", "stored");
        when(inbound.accept(any(), anyString(), any(), anyString())).thenReturn(ManagedInboundService.Outcome.DUPLICATE_MESSAGE);
        assertThat(controller.receive(signedRequest()).getStatusCode().value()).isEqualTo(200);
        when(inbound.accept(any(), anyString(), any(), anyString())).thenReturn(ManagedInboundService.Outcome.UNKNOWN_RECIPIENT);
        assertThat(controller.receive(signedRequest()).getStatusCode().value()).isEqualTo(406);
        when(inbound.accept(any(), anyString(), any(), anyString())).thenReturn(ManagedInboundService.Outcome.REPLAYED_TOKEN);
        assertThat(controller.receive(signedRequest()).getStatusCode().value()).isEqualTo(406);
    }
}
