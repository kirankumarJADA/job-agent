package com.personal.jobagent.email;

import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.identity.CredentialVault;
import com.personal.jobagent.notifications.NotificationEvents;
import com.personal.jobagent.notifications.NotificationService;
import com.personal.jobagent.security.OwnerContext;
import com.personal.jobagent.track.EmailApplicationMatcher;
import com.personal.jobagent.track.EmailApplicationMatcher.Association;
import com.personal.jobagent.track.EmailApplicationMatcher.MessageEvidence;
import com.personal.jobagent.track.ReceiptVerificationPolicy.SenderAuthentication;
import com.personal.jobagent.track.TrackEmailClassifier;
import com.personal.jobagent.track.TrackEvidenceRecorder;
import com.personal.jobagent.track.TrackEvidenceRecorder.IdentityFacts;
import com.personal.jobagent.track.TrackEvidenceRecorder.MessageFacts;
import com.personal.jobagent.track.TrackRepository;
import com.personal.jobagent.track.TrackText;
import com.personal.jobagent.track.TrackVocabulary.Channel;
import com.personal.jobagent.track.TrackVocabulary.EmailCategory;
import com.personal.jobagent.track.TrackVocabulary.LinkStatus;
import com.personal.jobagent.track.TrackVocabulary.Verification;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ingests employer messages for TRACK and serves the employer-portal
 * verification-code flow.
 *
 * <p><b>Ownership is a required parameter, not an inference.</b> Every entry
 * point takes the owning profile id and every statement filters on it.
 * Message de-duplication is per owner (V022: unique (profile_id, message_id);
 * V038 adds a per-owner content fingerprint for messages without an id),
 * matching only ever considers the owner's own applications, and the
 * verification-code extraction and resolution require that both the message
 * and the automation session belong to the caller.
 *
 * <p><b>What changed in Phase 8.3 (TRACK).</b> Before, a message was attached
 * to whichever single application's company name appeared in its text, was
 * classified by single keywords ("confirm" → confirmation), stored with its
 * whole body, and then <em>moved the application's status</em> on that
 * inference. Now:
 * <ul>
 *   <li>classification is the versioned rule set in {@link TrackEmailClassifier},
 *       always labelled as an inference;</li>
 *   <li>association needs a strong identifier (job link, posting identifier,
 *       requisition number) — company/title matches only propose candidates for
 *       the candidate to review ({@link EmailApplicationMatcher});</li>
 *   <li>nothing here changes an application's status. Evidence is recorded on
 *       the timeline and, for acknowledgements, assessed by the receipt policy;
 *       the candidate-facing status is derived from that evidence;</li>
 *   <li>only a sign-in verification message keeps its body (the code flow below
 *       needs it, briefly); every other message keeps subject, sender, time and a
 *       short redacted excerpt.</li>
 * </ul>
 *
 * <p>Message content is untrusted input: it is only measured by bounded rules.
 * No link in a message is fetched, and nothing in it is executed or acted on.
 */
@Service
public class EmailIntelligenceService {

    private static final Logger log = LoggerFactory.getLogger(EmailIntelligenceService.class);

    /**
     * Result of one ingestion. {@code applicationId} is set only when the message
     * is established for one application (strong identifier or the candidate's
     * own choice); {@code associationStatus} says why it is or is not.
     */
    public record IngestResult(UUID emailId, String classification, UUID applicationId, boolean replayed,
                               String associationStatus, boolean needsReview, String receiptVerification) {

        public IngestResult(UUID emailId, String classification, UUID applicationId, boolean replayed) {
            this(emailId, classification, applicationId, replayed, null, false, null);
        }
    }

    /**
     * A message retrieved by an authorised mailbox connector. This is the only
     * path that can carry provider-reported sender authentication; nothing in a
     * request body can. No connector is configured in this version (the
     * provider classes in {@code identity/ExternalMailboxProviders} refuse to
     * poll), so in production this boundary is exercised only by tests.
     */
    public record ConnectorMessage(String providerId, String messageId, String from, String to, String subject,
                                   String body, Instant receivedAt, String mailboxAddress,
                                   SenderAuthentication authentication) {
    }

    private static final Pattern OTP = Pattern.compile("\\b\\d{4,8}\\b");

    private final JdbcTemplate db;
    private final CredentialVault vault;
    private final NotificationService notifications;
    private final OwnerContext ownerContext;
    private final TrackRepository track;
    private final TrackEvidenceRecorder evidence;
    private final ObjectMapper json;
    private final TrackEmailClassifier classifier = new TrackEmailClassifier();
    private final EmailApplicationMatcher matcher = new EmailApplicationMatcher();

    public EmailIntelligenceService(JdbcTemplate db, CredentialVault vault, NotificationService notifications,
                                    OwnerContext ownerContext, TrackRepository track, TrackEvidenceRecorder evidence,
                                    ObjectMapper json) {
        this.db = db;
        this.vault = vault;
        this.notifications = notifications;
        this.ownerContext = ownerContext;
        this.track = track;
        this.evidence = evidence;
        this.json = json;
    }

    /**
     * Stores a candidate-supplied message against the given owner and processes
     * it for TRACK.
     *
     * @param ownerProfileId the profile the message belongs to. Required: an
     *                       email that cannot be attributed is not stored,
     *                       because storing it unowned would either hide it
     *                       from its owner or expose it to everyone.
     */
    @Transactional
    public IngestResult ingest(UUID ownerProfileId, String messageId, String from, String to,
                               String subject, String body, Instant received) {
        return ingest(ownerProfileId, messageId, from, to, subject, body, received, null);
    }

    /**
     * As {@link #ingest(UUID, String, String, String, String, String, Instant)},
     * with the application the candidate is adding the message to. That choice is
     * recorded as the candidate's (never as verification), and is checked against
     * the message's own identifiers.
     *
     * @throws NoSuchElementException when the named application is not the owner's
     */
    @Transactional
    public IngestResult ingest(UUID ownerProfileId, String messageId, String from, String to,
                               String subject, String body, Instant received, UUID applicationId) {
        Objects.requireNonNull(ownerProfileId, "ownerProfileId is required to ingest an email");
        return process(ownerProfileId, messageId, from, to, subject, body, received, applicationId,
                Channel.CANDIDATE_FORWARDED, null, null);
    }

    /** The authenticated mailbox-connector boundary (see {@link ConnectorMessage}). */
    @Transactional
    public IngestResult ingestFromMailboxConnector(UUID ownerProfileId, ConnectorMessage message) {
        Objects.requireNonNull(ownerProfileId, "ownerProfileId is required to ingest an email");
        Objects.requireNonNull(message, "message");
        return process(ownerProfileId, message.messageId(), message.from(), message.to(), message.subject(),
                message.body(), message.receivedAt(), null, Channel.MAILBOX_CONNECTOR,
                message.authentication() == null ? SenderAuthentication.none() : message.authentication(),
                message.mailboxAddress());
    }

    private IngestResult process(UUID ownerProfileId, String messageId, String from, String to, String subject,
                                 String body, Instant received, UUID assertedApplicationId, Channel channel,
                                 SenderAuthentication authentication, String mailboxAddress) {
        boolean receivedTimeKnown = received != null;
        Instant receivedAt = received == null ? Instant.now() : received;
        if (assertedApplicationId != null && track.application(ownerProfileId, assertedApplicationId).isEmpty()) {
            throw new NoSuchElementException("No application with that id");
        }

        if (messageId != null) {
            // Owner-scoped replay detection: another account having ingested this
            // message id must not surface their row here.
            List<UUID> ids = db.query("select id from emails where message_id=? and profile_id=?",
                    (rs, n) -> (UUID) rs.getObject(1), messageId, ownerProfileId);
            if (!ids.isEmpty()) {
                return replay(ownerProfileId, ids.get(0));
            }
        }

        TrackEmailClassifier.Classification classification = classifier.classify(subject, body);
        EmailCategory category = classification.category();
        MessageEvidence messageEvidence = MessageEvidence.of(from, subject, body);
        Association association = matcher.match(messageEvidence, track.ownedApplicationsForMatching(ownerProfileId),
                assertedApplicationId, category);
        String contentFingerprint = TrackText.sha256(TrackText.lower(from) + "|" + TrackText.lower(subject) + "|"
                + (receivedTimeKnown ? receivedAt.getEpochSecond() : "unknown") + "|" + TrackText.sha256(body));
        boolean retainBody = category == EmailCategory.VERIFICATION_CODE;
        UUID linkedApplication = association.established() ? association.applicationId() : null;
        boolean delivered = channel == Channel.MAILBOX_CONNECTOR && mailboxAddress != null
                && Objects.equals(TrackText.addressOf(to), TrackText.addressOf(mailboxAddress));
        String quotedReference = TrackText.references(messageEvidence.scanText()).stream().findFirst().orElse(null);

        UUID emailId = UuidV7.generate();
        List<UUID> inserted = db.query("""
                        insert into emails(id,message_id,from_address,to_address,subject,body_text,received_at,
                                           application_id,classification,classification_confidence,classification_reason,
                                           profile_id,channel,content_fingerprint,body_retained,processing_version,
                                           sender_authentication,received_time_known)
                        values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?)
                        on conflict do nothing
                        returning id
                        """, (rs, n) -> (UUID) rs.getObject(1),
                emailId, messageId, from == null ? "" : TrackText.cap(from, 320), TrackText.cap(to, 320),
                subject == null ? "" : TrackText.cap(subject, 500),
                retainBody ? TrackText.cap(body == null ? "" : body, TrackText.MAX_RETAINED_BODY_CHARS) : "",
                Timestamp.from(receivedAt), linkedApplication, category.name(), classification.confidence(),
                TrackEmailClassifier.VERSION + ":" + classification.ruleId(), ownerProfileId, channel.name(),
                contentFingerprint, retainBody, TrackEmailClassifier.VERSION,
                channel == Channel.MAILBOX_CONNECTOR ? authenticationJson(authentication, delivered) : null,
                receivedTimeKnown);
        if (inserted.isEmpty()) {
            // A concurrent or repeated ingestion of the same message won: report it as a replay.
            List<UUID> existing = db.query("""
                            select id from emails where profile_id = ?
                              and (content_fingerprint = ? or (message_id is not null and message_id = ?))
                            """, (rs, n) -> (UUID) rs.getObject(1), ownerProfileId, contentFingerprint, messageId);
            return existing.isEmpty()
                    ? new IngestResult(null, category.name(), null, true, null, false, null)
                    : replay(ownerProfileId, existing.get(0));
        }

        List<Map<String, Object>> candidates = new ArrayList<>();
        for (EmailApplicationMatcher.Candidate candidate : association.candidates()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("applicationId", candidate.applicationId().toString());
            c.put("signals", candidate.signals());
            candidates.add(c);
        }
        track.insertLink(new TrackRepository.LinkWrite(ownerProfileId, emailId,
                association.status() == LinkStatus.CONFLICT ? association.applicationId() : linkedApplication,
                association.status(), association.strength().name(), association.rationale(), candidates, category,
                classification.confidence(), classification.explicitReceipt(), quotedReference, classification.excerpt(),
                TrackEmailClassifier.VERSION + "+" + EmailApplicationMatcher.VERSION));

        String receiptVerification = null;
        if (linkedApplication != null) {
            MessageFacts facts = new MessageFacts(emailId, messageId, contentFingerprint, from, channel, receivedAt,
                    receivedTimeKnown, classification.explicitReceipt(), quotedReference, authentication, delivered);
            evidence.recordMessage(ownerProfileId, linkedApplication, facts, category,
                    association.strength() == com.personal.jobagent.track.TrackVocabulary.MatchStrength.CANDIDATE);
            if (classification.explicitReceipt() || category == EmailCategory.APPLICATION_ACKNOWLEDGEMENT) {
                TrackEvidenceRecorder.ReceiptOutcome outcome = evidence.recordAcknowledgement(ownerProfileId,
                        linkedApplication, facts, new IdentityFacts(!association.identityEvidence().isEmpty(),
                                association.identifiesOtherApplication(), association.identityEvidence()),
                        ownerContext.emailForProfile(ownerProfileId).orElse("SYSTEM"));
                receiptVerification = outcome.verification().name();
            }
        }
        boolean needsReview = association.status() == LinkStatus.PENDING_REVIEW
                || association.status() == LinkStatus.UNMATCHED || association.status() == LinkStatus.CONFLICT
                || (linkedApplication != null && category != EmailCategory.APPLICATION_ACKNOWLEDGEMENT
                && category != EmailCategory.UNRELATED_OR_AMBIGUOUS);
        notifyOwner(ownerProfileId, emailId, messageId, linkedApplication, association.status(), category,
                receiptVerification, needsReview);
        // Identifiers only: message content never reaches a log line.
        log.debug("Ingested message {} for profile {}: category={} association={}", emailId, ownerProfileId,
                category, association.status());
        return new IngestResult(emailId, category.name(), linkedApplication, false, association.status().name(),
                needsReview, receiptVerification);
    }

    private IngestResult replay(UUID ownerProfileId, UUID emailId) {
        List<Map<String, Object>> rows = db.queryForList("""
                select e.id, e.classification, e.application_id, l.status as link_status
                from emails e left join email_application_links l on l.email_id = e.id and l.profile_id = e.profile_id
                where e.id = ? and e.profile_id = ?
                """, emailId, ownerProfileId);
        if (rows.isEmpty()) {
            return new IngestResult(emailId, null, null, true, null, false, null);
        }
        Map<String, Object> existing = rows.get(0);
        String linkStatus = existing.get("link_status") == null ? null : String.valueOf(existing.get("link_status"));
        boolean needsReview = linkStatus != null && List.of("PENDING_REVIEW", "UNMATCHED", "CONFLICT").contains(linkStatus);
        return new IngestResult((UUID) existing.get("id"), (String) existing.get("classification"),
                (UUID) existing.get("application_id"), true, linkStatus, needsReview, null);
    }

    private String authenticationJson(SenderAuthentication auth, boolean delivered) {
        Map<String, Object> m = new LinkedHashMap<>();
        SenderAuthentication a = auth == null ? SenderAuthentication.none() : auth;
        m.put("dkim", a.dkim());
        m.put("dkimDomain", a.dkimDomain());
        m.put("spf", a.spf());
        m.put("dmarc", a.dmarc());
        m.put("deliveredToConnectedMailbox", delivered);
        try {
            return json.writeValueAsString(m);
        } catch (Exception e) {
            throw new IllegalStateException("could not record sender authentication", e);
        }
    }

    /**
     * Neutral wording on purpose: a notification never says "interview" or
     * "confirmed" on the strength of an inference.
     */
    private void notifyOwner(UUID ownerProfileId, UUID emailId, String messageId, UUID applicationId,
                             LinkStatus status, EmailCategory category, String receiptVerification, boolean needsReview) {
        String message;
        String detail;
        String eventType = NotificationEvents.RECRUITER_REPLY;
        if (status == LinkStatus.NOT_APPLICABLE) {
            return;
        }
        if (Verification.VERIFIED.name().equals(receiptVerification)) {
            eventType = NotificationEvents.CONFIRMATION_EMAIL_RECEIVED;
            message = "Employer acknowledgement verified";
            detail = "A verified employer acknowledgement identifies one of your applications.";
        } else if (applicationId != null && receiptVerification != null) {
            message = "Possible application acknowledgement — not verified";
            detail = "Robin recorded it as unverified evidence. It does not confirm a submission by itself.";
        } else if (needsReview) {
            message = "An employer message needs your review";
            detail = applicationId == null
                    ? "Robin could not tie it to one application with certainty. Choose the application or dismiss it."
                    : "Robin thinks it may be: " + com.personal.jobagent.track.TrackVocabulary.categoryLabel(category)
                    .toLowerCase(Locale.ROOT) + " (inferred). Confirm what it means.";
        } else {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("profile_id", ownerProfileId.toString());
        if (applicationId != null) payload.put("application_id", applicationId.toString());
        payload.put("email_id", emailId.toString());
        if (messageId != null) payload.put("email_message_id", messageId);
        payload.put("message", message);
        payload.put("detail", detail);
        payload.put("link", "/applications");
        payload.put("dedup_key", "track-email:" + emailId);
        notifications.emit(new NotificationService.NotificationCommand(eventType,
                applicationId != null ? "APPLICATION" : "EMAIL", applicationId != null ? applicationId : emailId,
                payload, UuidV7.generate(), null));
    }

    /**
     * Extracts a verification code from one of the caller's own emails, for an
     * automation session that is also the caller's.
     */
    public Optional<Map<String, Object>> extractVerification(UUID ownerProfileId, UUID emailId,
                                                             UUID sessionId, String expectedDomain) {
        if (ownerProfileId == null || emailId == null || sessionId == null) {
            return Optional.empty();
        }

        List<Map<String, Object>> owned = db.queryForList("""
                select e.from_address, e.subject, e.body_text, e.received_at
                from emails e
                where e.id = ? and e.profile_id = ?
                """, emailId, ownerProfileId);
        if (owned.isEmpty()) {
            // Not the caller's email: indistinguishable from a missing one.
            return Optional.empty();
        }

        boolean sessionOwned = ownerContext.ownerOfAccountSession(sessionId)
                .filter(ownerProfileId::equals)
                .isPresent();
        if (!sessionOwned) {
            log.debug("Refusing verification extraction: automation session is not owned by the caller");
            return Optional.empty();
        }

        Map<String, Object> email = owned.get(0);
        String sender = String.valueOf(email.get("from_address")).toLowerCase(Locale.ROOT);
        if (expectedDomain != null && !sender.endsWith("@" + expectedDomain.toLowerCase(Locale.ROOT))) {
            return Optional.empty();
        }
        Instant receivedAt = ((Timestamp) email.get("received_at")).toInstant();
        if (receivedAt.isBefore(Instant.now().minus(Duration.ofMinutes(30)))) {
            return Optional.empty();
        }
        Matcher matcher = OTP.matcher(email.get("subject") + " " + email.get("body_text"));
        if (!matcher.find()) {
            return Optional.empty();
        }

        String valueRef = vault.store(matcher.group(), Duration.ofMinutes(10));
        UUID id = UuidV7.generate();
        try {
            db.update("""
                    insert into verification_extractions(id,source_email_id,account_session_id,verification_type,value_ref,confidence,expires_at)
                    values(?,?,?,?,?,?,?)
                    """, id, emailId, sessionId, "OTP", valueRef, 0.95,
                    Timestamp.from(Instant.now().plus(Duration.ofMinutes(10))));
        } catch (org.springframework.dao.DuplicateKeyException ignored) {
            // Already extracted for this email.
        }
        return Optional.of(Map.of("extractionId", id, "verificationType", "OTP", "valueAvailable", true));
    }

    /**
     * Resolves (and consumes) an extracted code. Gated on the code having come
     * from one of the caller's own emails — otherwise a caller who guessed an
     * extraction id would be handed another account's live OTP.
     */
    public String resolve(UUID ownerProfileId, UUID id) {
        if (ownerProfileId == null || id == null) {
            throw new IllegalStateException("verification unavailable");
        }
        List<Map<String, Object>> rows = db.queryForList("""
                select v.value_ref, v.consumed_at, v.expires_at
                from verification_extractions v
                join emails e on e.id = v.source_email_id
                where v.id = ? and e.profile_id = ?
                """, id, ownerProfileId);
        if (rows.isEmpty()) {
            throw new IllegalStateException("verification unavailable");
        }
        Map<String, Object> row = rows.get(0);
        if (row.get("consumed_at") != null
                || ((Timestamp) row.get("expires_at")).toInstant().isBefore(Instant.now())) {
            throw new IllegalStateException("verification unavailable");
        }
        if (db.update("update verification_extractions set consumed_at=now() where id=? and consumed_at is null", id) != 1) {
            throw new IllegalStateException("verification already consumed");
        }
        return vault.resolveOnce((String) row.get("value_ref"));
    }

}
