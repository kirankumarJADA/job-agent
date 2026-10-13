package com.personal.jobagent.mailbox.managed;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.function.Function;

/**
 * Mailgun inbound-route receiver for Robin-managed email (Phase 8.5).
 *
 * <p>Configure in Mailgun: a route with {@code match_recipient(".*@<domain>")},
 * action {@code forward("https://<backend>/api/v1/managed-mail/inbound/mailgun")}
 * and {@code stop()}. Mailgun posts the parsed message form-encoded (multipart
 * when it has attachments, which are ignored).
 *
 * <p>Public by necessity (Mailgun cannot hold a session), so every request is
 * authenticated here by the documented HMAC signature. Status codes follow
 * Mailgun's retry contract — 200 accepted (no retry), 406 refused (no retry),
 * anything else retried for several hours:
 * <ul>
 *   <li>401 — signature missing, malformed or wrong (retried, so a rotated or
 *       mistyped signing key loses no mail while the operator fixes it);</li>
 *   <li>406 — authentic but stale or future-dated, a replayed token, a malformed
 *       payload, a recipient outside the domain, or an unknown alias;</li>
 *   <li>503 — not configured, or a storage failure (retried; nothing was kept);</li>
 *   <li>200 — stored, or a duplicate of something already stored.</li>
 * </ul>
 * Response bodies carry an outcome word only, never anything from the message.
 */
@RestController
@RequestMapping("/api/v1/managed-mail/inbound")
public class MailgunInboundController {

    public static final String PATH = "/api/v1/managed-mail/inbound/mailgun";

    private static final Logger log = LoggerFactory.getLogger(MailgunInboundController.class);
    /** Tomcat marks a form body it refused to parse (e.g. above its size limit). */
    private static final String PARAMETER_PARSE_FAILED = "org.apache.catalina.parameter_parse_failed";

    private final ManagedMailSettings settings;
    private final ManagedInboundService inbound;
    private final ObjectMapper json;

    public MailgunInboundController(ManagedMailSettings settings, ManagedInboundService inbound, ObjectMapper json) {
        this.settings = settings;
        this.inbound = inbound;
        this.json = json;
    }

    @PostMapping("/mailgun")
    public ResponseEntity<Map<String, String>> receive(HttpServletRequest request) {
        if (!settings.configured() || !ManagedMailSettings.PROVIDER_MAILGUN.equals(settings.providerId())) {
            log.warn("Managed inbound delivery refused: managed mail is not configured (missing: {})", settings.missing());
            return answer(503, "not_configured");
        }
        if (request.getAttribute(PARAMETER_PARSE_FAILED) != null) {
            // The body could not be read (too large or not a form): retrying cannot help.
            return answer(406, "unprocessable");
        }
        Function<String, String[]> params = request::getParameterValues;
        String timestamp = single(params, "timestamp");
        String token = single(params, "token");
        String signature = single(params, "signature");

        MailgunSignatureVerifier.Outcome check = MailgunSignatureVerifier.verify(settings.mailgunSigningKey(),
                timestamp, token, signature, Instant.now(), settings.mailgunMaxAge(), settings.futureSkew());
        if (!check.authenticated()) {
            log.warn("Managed inbound delivery refused: signature {}", check);
            return answer(401, "unauthenticated");
        }
        if (check != MailgunSignatureVerifier.Outcome.VALID) {
            log.info("Managed inbound delivery refused: timestamp {}", check);
            return answer(406, "stale");
        }

        MailgunInboundParser.Result parsed = MailgunInboundParser.parse(params, json);
        try {
            ManagedInboundService.Outcome outcome = inbound.accept(parsed, token,
                    Instant.ofEpochSecond(Long.parseLong(timestamp)), MailgunInboundParser.payloadDigest(params));
            return outcome.accepted()
                    ? answer(200, outcome == ManagedInboundService.Outcome.STORED ? "stored" : "duplicate")
                    : answer(406, "rejected");
        } catch (DataAccessException | TransactionException | IllegalStateException failure) {
            // Nothing was committed: let the provider retry later.
            log.error("Managed inbound delivery could not be stored: {}", failure.getClass().getSimpleName());
            return answer(503, "retry");
        }
    }

    /** The value when the field is present exactly once; null when absent or repeated. */
    private static String single(Function<String, String[]> params, String name) {
        String[] values = params.apply(name);
        return values != null && values.length == 1 ? values[0] : null;
    }

    private static ResponseEntity<Map<String, String>> answer(int status, String outcome) {
        return ResponseEntity.status(status).body(Map.of("status", outcome));
    }
}
