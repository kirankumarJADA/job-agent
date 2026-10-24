package com.personal.jobagent.mailbox.managed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.track.TrackText;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

/**
 * Cloudflare Email Worker inbound receiver for Robin-managed email (Phase 20).
 *
 * <p>Cloudflare Email Routing dispatches inbound mail to a dedicated Email Worker,
 * which securely forwards the envelope recipient, sender and message payload
 * to this endpoint. Authenticated via HMAC-SHA256 request signature over
 * (timestamp + "." + token + "." + payloadDigest) using the shared secret
 * {@code CLOUDFLARE_EMAIL_WORKER_SECRET}.
 */
@RestController
@RequestMapping("/api/v1/managed-mail/inbound")
public class CloudflareInboundController {

    public static final String PATH = "/api/v1/managed-mail/inbound/cloudflare";

    private static final Logger log = LoggerFactory.getLogger(CloudflareInboundController.class);

    private final ManagedMailSettings settings;
    private final ManagedInboundService inbound;
    private final ObjectMapper json;

    public CloudflareInboundController(ManagedMailSettings settings, ManagedInboundService inbound, ObjectMapper json) {
        this.settings = settings;
        this.inbound = inbound;
        this.json = json;
    }

    @PostMapping("/cloudflare")
    public ResponseEntity<Map<String, String>> receive(HttpServletRequest request) {
        if (!settings.configured() || !ManagedMailSettings.PROVIDER_CLOUDFLARE.equals(settings.providerId())) {
            log.warn("Cloudflare inbound delivery refused: managed mail is not configured for cloudflare (missing: {})", settings.missing());
            return answer(503, "not_configured");
        }

        String timestamp = request.getHeader("X-Robin-Timestamp");
        String token = request.getHeader("X-Robin-Token");
        String signature = request.getHeader("X-Robin-Signature");

        byte[] rawBytes;
        try {
            rawBytes = StreamUtils.copyToByteArray(request.getInputStream());
        } catch (IOException e) {
            log.warn("Cloudflare inbound delivery read failed: {}", e.getMessage());
            return answer(406, "unprocessable");
        }

        String payloadSha256 = TrackText.sha256(rawBytes);
        CloudflareSignatureVerifier.Outcome check = CloudflareSignatureVerifier.verify(
                settings.cloudflareWebhookSecret(),
                timestamp,
                token,
                payloadSha256,
                signature,
                Instant.now(),
                settings.cloudflareMaxAge(),
                settings.futureSkew()
        );

        if (!check.authenticated()) {
            log.warn("Cloudflare inbound delivery refused: signature {}", check);
            return answer(401, "unauthenticated");
        }
        if (check != CloudflareSignatureVerifier.Outcome.VALID) {
            log.info("Cloudflare inbound delivery refused: timestamp {}", check);
            return answer(406, "stale");
        }

        String body = new String(rawBytes, StandardCharsets.UTF_8);
        CloudflareInboundParser.Result parsed = CloudflareInboundParser.parse(body, json);
        try {
            ManagedInboundService.Outcome outcome = inbound.accept(
                    ManagedMailSettings.PROVIDER_CLOUDFLARE,
                    parsed.message(),
                    parsed.rejection(),
                    token,
                    Instant.ofEpochSecond(Long.parseLong(timestamp)),
                    payloadSha256
            );
            return outcome.accepted()
                    ? answer(200, outcome == ManagedInboundService.Outcome.STORED ? "stored" : "duplicate")
                    : answer(406, "rejected");
        } catch (DataAccessException | TransactionException | IllegalStateException failure) {
            log.error("Cloudflare inbound delivery could not be stored: {}", failure.getClass().getSimpleName());
            return answer(503, "retry");
        }
    }

    private static ResponseEntity<Map<String, String>> answer(int status, String outcome) {
        return ResponseEntity.status(status).body(Map.of("status", outcome));
    }
}
