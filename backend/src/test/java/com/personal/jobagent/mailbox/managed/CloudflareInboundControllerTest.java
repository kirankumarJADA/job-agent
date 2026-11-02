package com.personal.jobagent.mailbox.managed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.security.SecretCipher;
import com.personal.jobagent.track.TrackText;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CloudflareInboundControllerTest {

    private static final String SECRET = "cloudflare-test-secret-key-123456789";
    private static final String DOMAIN = "mail.aplivenosi.com";
    private static final SecretCipher CIPHER = new SecretCipher("6M5mXo2C0eP1Ztq0QzZ0n7cYk1o0r8sB3m6Gq9v2T8U=");

    private ManagedInboundService inbound;
    private MockMvc mvc;
    private ObjectMapper json;

    @BeforeEach
    void setUp() {
        inbound = mock(ManagedInboundService.class);
        json = new ObjectMapper();
        ManagedMailSettings settings = new ManagedMailSettings(
                DOMAIN, "cloudflare", "", 900, SECRET, 900, "robin+", 120, 30, CIPHER);
        CloudflareInboundController controller = new CloudflareInboundController(settings, inbound, json);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void validAuthenticatedRequestAnswersStored() throws Exception {
        String body = """
                {
                  "envelopeRecipient": "robin+u7kq2m9x4vb3tn5p@mail.aplivenosi.com",
                  "subject": "Interview",
                  "text": "Hello"
                }
                """;
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String token = "randomtoken123456789";
        String payloadSha256 = TrackText.sha256(body.getBytes(StandardCharsets.UTF_8));
        String signature = CloudflareSignatureVerifier.hmacHex(SECRET, timestamp + "." + token + "." + payloadSha256);

        when(inbound.accept(eq(ManagedMailSettings.PROVIDER_CLOUDFLARE), any(), any(), eq(token), any(), eq(payloadSha256)))
                .thenReturn(ManagedInboundService.Outcome.STORED);

        mvc.perform(post(CloudflareInboundController.PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-Robin-Timestamp", timestamp)
                        .header("X-Robin-Token", token)
                        .header("X-Robin-Signature", signature))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("stored"));
    }

    @Test
    void unauthenticatedRequestAnswers401() throws Exception {
        String body = "{\"envelopeRecipient\":\"robin+u7@mail.aplivenosi.com\"}";
        mvc.perform(post(CloudflareInboundController.PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-Robin-Timestamp", String.valueOf(Instant.now().getEpochSecond()))
                        .header("X-Robin-Token", "randomtoken123456789")
                        .header("X-Robin-Signature", "0000000000000000000000000000000000000000000000000000000000000000"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("unauthenticated"));
    }

    @Test
    void staleRequestAnswers406() throws Exception {
        String body = "{\"envelopeRecipient\":\"robin+u7@mail.aplivenosi.com\"}";
        String oldTimestamp = String.valueOf(Instant.now().getEpochSecond() - 1000);
        String token = "randomtoken123456789";
        String payloadSha256 = TrackText.sha256(body.getBytes(StandardCharsets.UTF_8));
        String signature = CloudflareSignatureVerifier.hmacHex(SECRET, oldTimestamp + "." + token + "." + payloadSha256);

        mvc.perform(post(CloudflareInboundController.PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-Robin-Timestamp", oldTimestamp)
                        .header("X-Robin-Token", token)
                        .header("X-Robin-Signature", signature))
                .andExpect(status().is(406))
                .andExpect(jsonPath("$.status").value("stale"));
    }

    @Test
    void unconfiguredServerAnswers503() throws Exception {
        ManagedMailSettings unconfigured = new ManagedMailSettings(
                DOMAIN, "cloudflare", "", 900, "", 900, "robin+", 120, 30, CIPHER);
        CloudflareInboundController controller = new CloudflareInboundController(unconfigured, inbound, json);
        MockMvc unconfiguredMvc = MockMvcBuilders.standaloneSetup(controller).build();

        unconfiguredMvc.perform(post(CloudflareInboundController.PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("not_configured"));
    }
}
