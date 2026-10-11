package com.personal.jobagent.track;

import com.personal.jobagent.audit.AuditEntry;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.track.ReceiptVerificationPolicy.SenderAuthentication;
import com.personal.jobagent.track.TrackRepository.EventWrite;
import com.personal.jobagent.track.TrackRepository.ReceiptWrite;
import com.personal.jobagent.track.TrackVocabulary.Channel;
import com.personal.jobagent.track.TrackVocabulary.EmailCategory;
import com.personal.jobagent.track.TrackVocabulary.EventClassification;
import com.personal.jobagent.track.TrackVocabulary.EvidenceType;
import com.personal.jobagent.track.TrackVocabulary.TimePrecision;
import com.personal.jobagent.track.TrackVocabulary.Verification;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Writes message-derived evidence onto an application: the timeline row for an
 * employer message, and — for an acknowledgement — the receipt assessed by
 * {@link ReceiptVerificationPolicy}. Used at ingestion (strong link or the
 * candidate's own choice) and when the candidate confirms an association, so
 * the two paths can never assess the same message differently.
 */
@Component
public class TrackEvidenceRecorder {

    public static final String MESSAGE_EVENT = "EMPLOYER_MESSAGE_RECEIVED";
    public static final String RECEIPT_EVENT = "RECEIPT_RECORDED";

    private final TrackRepository repository;
    private final AuditLogWriter audit;
    private final ReceiptVerificationPolicy policy = new ReceiptVerificationPolicy();

    public TrackEvidenceRecorder(TrackRepository repository, AuditLogWriter audit) {
        this.repository = repository;
        this.audit = audit;
    }

    /** What is known about one stored message; contains no message body. */
    public record MessageFacts(UUID emailId, String providerMessageId, String contentFingerprint, String fromAddress,
                               Channel channel, Instant receivedAt, boolean receivedTimeKnown, boolean explicitReceipt,
                               String quotedReference, SenderAuthentication authentication,
                               boolean deliveredToConnectedMailbox) {
    }

    /** What the message's own identifiers say about one application. */
    public record IdentityFacts(boolean identifiesThisApplication, boolean identifiesOtherApplication,
                                List<String> identityEvidence) {
    }

    public static String messageEventKey(UUID emailId) {
        return "track-email:" + emailId;
    }

    public static String receiptEventKey(UUID receiptId) {
        return "track-receipt:" + receiptId;
    }

    /** The application's timeline row for this message (idempotent per application). */
    public Optional<UUID> recordMessage(UUID profileId, UUID applicationId, MessageFacts facts, EmailCategory inferred,
                                        boolean candidateChoseApplication) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("category", inferred.name());
        payload.put("associatedBy", candidateChoseApplication ? "CANDIDATE" : "MESSAGE_IDENTIFIER");
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("channel", facts.channel().name());
        provenance.put("classifier", TrackEmailClassifier.VERSION);
        provenance.put("matcher", EmailApplicationMatcher.VERSION);
        provenance.put("senderAuthenticated", senderAuthenticated(facts));
        String meaning = TrackVocabulary.categoryLabel(inferred).toLowerCase(Locale.ROOT);
        String summary = inferred == EmailCategory.UNRELATED_OR_AMBIGUOUS
                ? "Employer message received — Robin could not tell what it means"
                : "Employer message received — Robin thinks it is: " + meaning + " (not confirmed)";
        repository.insertEvent(new EventWrite(applicationId, profileId, MESSAGE_EVENT, messageEventKey(facts.emailId()),
                EventClassification.INFERRED, Verification.UNVERIFIED,
                facts.receivedTimeKnown() ? facts.receivedAt() : null,
                facts.receivedTimeKnown() ? TimePrecision.EXACT : TimePrecision.UNKNOWN,
                summary, facts.providerMessageId(), facts.emailId(), null, null, payload, provenance, "SYSTEM"));
        return repository.eventIdByKey(profileId, applicationId, messageEventKey(facts.emailId()));
    }

    public record ReceiptOutcome(UUID receiptId, Verification verification, boolean created) {
    }

    /**
     * Assesses an acknowledgement for one application and records the receipt
     * (once per message and application) with its timeline row and audit entry.
     */
    public ReceiptOutcome recordAcknowledgement(UUID profileId, UUID applicationId, MessageFacts facts,
                                                IdentityFacts identity, String actor) {
        ReceiptVerificationPolicy.Outcome outcome = policy.evaluate(new ReceiptVerificationPolicy.Evidence(
                EvidenceType.EMPLOYER_ACKNOWLEDGEMENT_EMAIL, facts.channel(), identity.identifiesThisApplication(),
                identity.identifiesOtherApplication(), facts.explicitReceipt(), TrackText.domainOf(facts.fromAddress()),
                facts.authentication(), facts.deliveredToConnectedMailbox(),
                facts.receivedTimeKnown() ? facts.receivedAt() : null, Instant.now()));
        String fingerprint = TrackText.sha256("EMAIL|" + (facts.providerMessageId() != null
                ? "mid:" + facts.providerMessageId() : "fp:" + facts.contentFingerprint()));
        String summary = "Employer acknowledgement email — " + TrackVocabulary.verificationLabel(outcome.status()).toLowerCase(Locale.ROOT);
        Optional<UUID> inserted = repository.insertReceipt(new ReceiptWrite(profileId, applicationId,
                EvidenceType.EMPLOYER_ACKNOWLEDGEMENT_EMAIL, facts.channel(), facts.emailId(), facts.providerMessageId(),
                facts.quotedReference(), TrackText.domainOf(facts.fromAddress()),
                facts.receivedTimeKnown() ? facts.receivedAt() : null,
                facts.receivedTimeKnown() ? TimePrecision.EXACT : TimePrecision.UNKNOWN, fingerprint, outcome.status(),
                outcome.reasons(), summary, identity.identityEvidence()));
        if (inserted.isEmpty()) {
            UUID existing = repository.receiptIdByFingerprint(profileId, applicationId, fingerprint).orElse(null);
            return new ReceiptOutcome(existing, outcome.status(), false);
        }
        UUID receiptId = inserted.get();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("evidenceType", EvidenceType.EMPLOYER_ACKNOWLEDGEMENT_EMAIL.name());
        payload.put("verification", outcome.status().name());
        payload.put("reasons", outcome.reasons().stream().map(r -> r.get("code")).toList());
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("channel", facts.channel().name());
        provenance.put("policy", ReceiptVerificationPolicy.VERSION);
        repository.insertEvent(new EventWrite(applicationId, profileId, RECEIPT_EVENT, receiptEventKey(receiptId),
                EventClassification.EXTERNAL, outcome.status(),
                facts.receivedTimeKnown() ? facts.receivedAt() : null,
                facts.receivedTimeKnown() ? TimePrecision.EXACT : TimePrecision.UNKNOWN,
                summary, facts.quotedReference(), facts.emailId(), receiptId, null, payload, provenance, "SYSTEM"));
        audit.write(new AuditEntry(actor == null ? "SYSTEM" : actor, "TRACK_RECEIPT_RECORDED", "APPLICATION", applicationId,
                null, Map.of("receiptId", receiptId.toString(), "evidenceType", EvidenceType.EMPLOYER_ACKNOWLEDGEMENT_EMAIL.name(),
                "verification", outcome.status().name(), "policy", ReceiptVerificationPolicy.VERSION),
                null, UuidV7.generate()));
        return new ReceiptOutcome(receiptId, outcome.status(), true);
    }

    private static boolean senderAuthenticated(MessageFacts facts) {
        return facts.channel() == Channel.MAILBOX_CONNECTOR && facts.authentication() != null
                && facts.authentication().passesFor(TrackText.domainOf(facts.fromAddress()));
    }
}
