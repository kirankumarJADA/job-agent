package com.personal.jobagent.mailbox.managed;

import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.email.EmailIntelligenceService;
import com.personal.jobagent.track.ReceiptVerificationPolicy;
import com.personal.jobagent.track.TrackText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * Accepts one authenticated inbound delivery (Phase 8.5, managed mail; Phase 20 Cloudflare).
 *
 * <p>Runs in one transaction, so a delivery is either fully recorded or not at
 * all — a failure answers 503 and the provider's retry finds nothing half done:
 * <ol>
 *   <li><b>Replay.</b> The webhook token is recorded first (as a SHA-256 hash,
 *       with a digest of the payload). A token seen before with the same payload
 *       is the provider retrying a delivery already accepted: answered as a
 *       duplicate. The same token with different content is a replay: refused.</li>
 *   <li><b>Receiving evidence.</b> A signed delivery for the configured domain
 *       proves MX, route and signing key; it is recorded even when the alias is
 *       unknown, because the receiving path itself worked.</li>
 *   <li><b>Routing.</b> By the SMTP envelope recipient only. The To header is
 *       sender-controlled and is never consulted.</li>
 *   <li><b>De-duplication.</b> One row per address and Message-Id (or content
 *       fingerprint when there is no Message-Id).</li>
 *   <li><b>Minimisation.</b> Subject, text and links are stored only AES-GCM
 *       encrypted and only until the retention job or first use erases them; a
 *       message the provider marked as failing sender checks or as spam keeps no
 *       content at all. The stored subject is redacted (numbers, links and
 *       addresses removed). Nothing from the message reaches a log line.</li>
 *   <li><b>Phase 20 Recruiter Tracking.</b> Content-kept employer messages are
 *       forwarded to {@link EmailIntelligenceService} to automatically associate
 *       recruiter communications and application acknowledgements with the candidate's
 *       applications in TRACK.</li>
 * </ol>
 */
@Service
public class ManagedInboundService {

    private static final Logger log = LoggerFactory.getLogger(ManagedInboundService.class);
    private static final int MAX_REDACTED_SUBJECT = 200;

    public enum Outcome {
        STORED(true),
        /** The provider re-sent a request that was already accepted. */
        DUPLICATE_REQUEST(true),
        /** The same message was already stored for this address (new token). */
        DUPLICATE_MESSAGE(true),
        /** An accepted token re-sent with different content. */
        REPLAYED_TOKEN(false),
        MALFORMED(false),
        WRONG_DOMAIN(false),
        UNKNOWN_RECIPIENT(false);

        private final boolean accepted;

        Outcome(boolean accepted) {
            this.accepted = accepted;
        }

        /** Accepted outcomes answer 200; the rest answer 406 so the provider does not retry. */
        public boolean accepted() {
            return accepted;
        }
    }

    private final JdbcTemplate db;
    private final ManagedMailSettings settings;
    private final ManagedMailboxService mailboxes;
    private final ManagedMessageContent content;
    private final ObjectProvider<EmailIntelligenceService> emailIntelligence;

    @org.springframework.beans.factory.annotation.Autowired
    public ManagedInboundService(JdbcTemplate db, ManagedMailSettings settings, ManagedMailboxService mailboxes,
                                 ManagedMessageContent content,
                                 ObjectProvider<EmailIntelligenceService> emailIntelligence) {
        this.db = db;
        this.settings = settings;
        this.mailboxes = mailboxes;
        this.content = content;
        this.emailIntelligence = emailIntelligence;
    }

    public ManagedInboundService(JdbcTemplate db, ManagedMailSettings settings, ManagedMailboxService mailboxes,
                                 ManagedMessageContent content) {
        this(db, settings, mailboxes, content, null);
    }

    /**
     * Mailgun-specific entry point delegating to provider-neutral accept.
     */
    @Transactional
    public Outcome accept(MailgunInboundParser.Result parsed, String token, Instant signedAt, String payloadSha256) {
        return accept(ManagedMailSettings.PROVIDER_MAILGUN,
                parsed.message(),
                parsed.rejection(),
                token,
                signedAt,
                payloadSha256);
    }

    /**
     * Provider-neutral entry point (Phase 20).
     *
     * @param providerId    the inbound provider identifier (mailgun or cloudflare)
     * @param message       the parsed message (or null if parsing failed)
     * @param rejection     rejection reason if parsing failed
     * @param token         the provider webhook token, already signature-verified
     * @param signedAt      the signed timestamp, already checked against the window
     * @param payloadSha256 SHA-256 digest of the request payload
     */
    @Transactional
    public Outcome accept(String providerId, InboundMailMessage message, String rejection,
                          String token, Instant signedAt, String payloadSha256) {
        String tokenHash = TrackText.sha256(token);
        int fresh = db.update("""
                insert into managed_mail_webhook_tokens (token_sha256, provider_id, signed_at, payload_sha256)
                values (?, ?, ?, ?)
                on conflict (token_sha256) do nothing
                """, tokenHash, providerId, Timestamp.from(signedAt), payloadSha256);
        if (fresh == 0) {
            String seen = db.queryForObject(
                    "select payload_sha256 from managed_mail_webhook_tokens where token_sha256 = ?", String.class, tokenHash);
            Outcome outcome = payloadSha256.equals(seen) ? Outcome.DUPLICATE_REQUEST : Outcome.REPLAYED_TOKEN;
            log.info("Managed inbound delivery repeated or replayed: outcome={}", outcome);
            return outcome;
        }
        if (rejection != null || message == null) {
            log.info("Managed inbound delivery rejected: outcome={} reason={}", Outcome.MALFORMED, rejection);
            return Outcome.MALFORMED;
        }
        String domain = MailAddresses.domainOf(message.envelopeRecipient());
        if (!settings.domain().equals(domain)) {
            log.info("Managed inbound delivery rejected: outcome={}", Outcome.WRONG_DOMAIN);
            return Outcome.WRONG_DOMAIN;
        }
        Instant now = Instant.now();
        mailboxes.recordSignedDelivery(domain, providerId, now);

        Optional<ManagedMailboxService.ManagedAddress> address = mailboxes.routeEnvelopeRecipient(message.envelopeRecipient());
        if (address.isEmpty()) {
            log.info("Managed inbound delivery rejected: outcome={}", Outcome.UNKNOWN_RECIPIENT);
            return Outcome.UNKNOWN_RECIPIENT;
        }
        ManagedMailboxService.ManagedAddress owner = address.get();
        boolean keepContent = !message.authenticationFailed() && !message.spamFlagged();
        String encrypted = keepContent ? content.encrypt(message) : null;
        int stored = db.update("""
                        insert into managed_inbound_messages
                            (id, profile_id, address_id, provider_id, message_id_sha256, content_sha256,
                             envelope_recipient, envelope_sender, from_address, sender_domain, subject_redacted,
                             content_encrypted, content_purged_at, spf_result, dkim_result, spam_flagged,
                             authentication_failed, signed_at, received_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        on conflict do nothing
                        """,
                UuidV7.generate(), owner.profileId(), owner.id(), providerId,
                message.messageId() == null ? null : TrackText.sha256(message.messageId()),
                contentFingerprint(message), message.envelopeRecipient(), message.envelopeSender(),
                message.fromAddress(), message.senderDomain(), redactedSubject(message.subject()),
                encrypted, encrypted == null ? Timestamp.from(now) : null,
                message.spfResult(), message.dkimResult(), message.spamFlagged(), message.authenticationFailed(),
                Timestamp.from(signedAt), Timestamp.from(now));
        Outcome outcome = stored == 1 ? Outcome.STORED : Outcome.DUPLICATE_MESSAGE;
        // Identifiers only: never the alias, subject, body, code or token.
        log.info("Managed inbound delivery accepted: outcome={} address={} contentKept={}", outcome, owner.id(),
                stored == 1 && keepContent);

        if (stored == 1 && keepContent && emailIntelligence != null) {
            EmailIntelligenceService intel = emailIntelligence.getIfAvailable();
            if (intel != null) {
                try {
                    String dkim = message.dkimResult();
                    String spf = message.spfResult();
                    ReceiptVerificationPolicy.SenderAuthentication auth = new ReceiptVerificationPolicy.SenderAuthentication(
                            dkim, message.senderDomain(),
                            spf, null);
                    EmailIntelligenceService.ConnectorMessage connectorMessage = new EmailIntelligenceService.ConnectorMessage(
                            message.providerId(),
                            message.messageId(),
                            message.fromAddress(),
                            message.envelopeRecipient(),
                            message.subject(),
                            message.text(),
                            signedAt != null ? signedAt : now,
                            message.envelopeRecipient(),
                            auth
                    );
                    intel.ingestFromMailboxConnector(owner.profileId(), connectorMessage);
                } catch (Exception e) {
                    log.warn("Managed inbound message {} forwarding to email intelligence failed: {}",
                            message.providerId(), e.getMessage());
                }
            }
        }

        return outcome;
    }

    /** Subject with numbers (codes), links and addresses removed, for display and audit. */
    static String redactedSubject(String subject) {
        String redacted = TrackText.collapse(TrackText.redact(subject == null ? "" : subject));
        return TrackText.cap(redacted, MAX_REDACTED_SUBJECT);
    }

    private static String contentFingerprint(InboundMailMessage message) {
        return TrackText.sha256(TrackText.lower(message.fromHeader()) + "\n" + message.subject() + "\n"
                + (message.date() == null ? "" : message.date().trim()) + "\n" + TrackText.sha256(message.text())
                + "\n" + String.join("\n", message.links()));
    }
}
