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
import org.springframework.beans.factory.annotation.Autowired;
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
    private final EmployerDomainPolicy domainPolicy;
    private final ReceiptVerificationPolicy policy = new ReceiptVerificationPolicy();
    private final ReceiptTemporalPolicy temporal = new ReceiptTemporalPolicy();
    private final SubmissionReferencePolicy references = new SubmissionReferencePolicy();

    @Autowired
    public TrackEvidenceRecorder(TrackRepository repository, AuditLogWriter audit,
                                 @Autowired(required = false) EmployerDomainPolicy domainPolicy) {
        this.repository = repository;
        this.audit = audit;
        this.domainPolicy = domainPolicy != null ? domainPolicy : new EmployerDomainPolicy();
    }

    public TrackEvidenceRecorder(TrackRepository repository, AuditLogWriter audit) {
        this(repository, audit, new EmployerDomainPolicy());
    }

    /**
     * What is known about one stored message; contains no message body.
     *
     * @param providerId the mailbox connector that retrieved it (gmail, cloudflare, mailgun…), when known;
     *     assigned by the ingestion boundary, never by the message
     */
    public record MessageFacts(UUID emailId, String providerMessageId, String contentFingerprint, String fromAddress,
                               Channel channel, Instant receivedAt, boolean receivedTimeKnown, boolean explicitReceipt,
                               String quotedReference, SenderAuthentication authentication,
                               boolean deliveredToConnectedMailbox, String providerId) {
        public MessageFacts(UUID emailId, String providerMessageId, String contentFingerprint, String fromAddress,
                            Channel channel, Instant receivedAt, boolean receivedTimeKnown, boolean explicitReceipt,
                            String quotedReference, SenderAuthentication authentication,
                            boolean deliveredToConnectedMailbox) {
            this(emailId, providerMessageId, contentFingerprint, fromAddress, channel, receivedAt, receivedTimeKnown,
                    explicitReceipt, quotedReference, authentication, deliveredToConnectedMailbox, null);
        }
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
        String fromDomain = TrackText.domainOf(facts.fromAddress());
        String dkimDomain = facts.authentication() == null ? null : facts.authentication().dkimDomain();
        EmployerDomainPolicy.ApplicationTarget target = repository.applicationTarget(profileId, applicationId).orElse(null);
        EmployerDomainPolicy.DomainBinding domainBinding = domainPolicy.evaluate(target, fromDomain, dkimDomain);

        Instant now = Instant.now();
        Instant evidenceTime = facts.receivedTimeKnown() ? facts.receivedAt() : null;
        TimePrecision precision = facts.receivedTimeKnown() ? TimePrecision.EXACT : TimePrecision.UNKNOWN;
        TrackRepository.SubmissionContext context = repository.submissionContext(profileId, applicationId);
        if (context == null) {
            context = new TrackRepository.SubmissionContext(ReceiptTemporalPolicy.SubmissionAnchor.none(), null, null, java.util.Set.of());
        }
        ReceiptTemporalPolicy.Assessment timing = temporal.assess(evidenceTime, precision,
                ReceiptTemporalPolicy.sourceFor(facts.channel(), evidenceTime, precision), context.anchor(), now);

        ReceiptVerificationPolicy.Outcome outcome = policy.evaluate(new ReceiptVerificationPolicy.Evidence(
                EvidenceType.EMPLOYER_ACKNOWLEDGEMENT_EMAIL, facts.channel(), identity.identifiesThisApplication(),
                identity.identifiesOtherApplication(), facts.explicitReceipt(), fromDomain,
                facts.authentication(), facts.deliveredToConnectedMailbox(), evidenceTime, now, domainBinding, timing));
        String fingerprint = TrackText.sha256("EMAIL|" + (facts.providerMessageId() != null
                ? "mid:" + facts.providerMessageId() : "fp:" + facts.contentFingerprint()));
        String summary = "Employer acknowledgement email — " + TrackVocabulary.verificationLabel(outcome.status()).toLowerCase(Locale.ROOT);
        Optional<UUID> inserted = repository.insertReceipt(new ReceiptWrite(profileId, applicationId,
                EvidenceType.EMPLOYER_ACKNOWLEDGEMENT_EMAIL, facts.channel(), facts.emailId(), facts.providerMessageId(),
                facts.quotedReference(), fromDomain, evidenceTime, precision, fingerprint, outcome.status(),
                outcome.reasons(), summary, identity.identityEvidence()));
        if (inserted.isEmpty()) {
            // A duplicate delivery: the receipt exists. Make sure its link exists too (idempotent: one root per receipt).
            UUID existing = repository.receiptIdByFingerprint(profileId, applicationId, fingerprint).orElse(null);
            if (existing != null) {
                TrackRepository.SubmissionContext ctx = context;
                repository.receipt(profileId, applicationId, existing).ifPresent(row ->
                        writeIngestionLink(profileId, applicationId, existing, row.verification(), fingerprint, facts,
                                identity, ctx, timing, actor));
            }
            return new ReceiptOutcome(existing, outcome.status(), false);
        }
        UUID receiptId = inserted.get();
        writeIngestionLink(profileId, applicationId, receiptId, outcome.status(), fingerprint, facts, identity, context,
                timing, actor);
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

    /**
     * The durable correlation decision for a newly recorded acknowledgement: what it was compared with
     * (Robin's submission evidence, when there is one), the reference comparison with both original
     * values, and the temporal assessment with the provenance of both timestamps.
     */
    private void writeIngestionLink(UUID profileId, UUID applicationId, UUID receiptId, Verification verification,
                                    String fingerprint, MessageFacts facts, IdentityFacts identity,
                                    TrackRepository.SubmissionContext context, ReceiptTemporalPolicy.Assessment timing,
                                    String actor) {
        TrackModels.SubmissionFacts submission = context.submission();
        SubmissionReferencePolicy.Comparison comparison = submission == null
                ? new SubmissionReferencePolicy.Comparison(SubmissionReferencePolicy.Consistency.NOT_COMPARABLE,
                "NO_ROBIN_SUBMISSION_EVIDENCE", SubmissionReferencePolicy.normalize(facts.quotedReference()).orElse(null), null)
                : !submission.referenceTrusted() && submission.employerConfirmationId() != null
                ? new SubmissionReferencePolicy.Comparison(SubmissionReferencePolicy.Consistency.NOT_COMPARABLE,
                "SUBMISSION_REFERENCE_NOT_TRUSTED", SubmissionReferencePolicy.normalize(facts.quotedReference()).orElse(null), null)
                : references.compare(facts.quotedReference(), submission.employerConfirmationId(),
                context.disqualifiedReferences());
        UUID planId = submission != null ? submission.planId() : context.anchorPlanId();
        Map<String, Object> correlation = new LinkedHashMap<>();
        correlation.put("identityEvidence", identity.identityEvidence());
        correlation.put("identifiesOtherApplication", identity.identifiesOtherApplication());
        correlation.put("referencePolicy", SubmissionReferencePolicy.VERSION);
        correlation.put("temporalPolicy", ReceiptTemporalPolicy.VERSION);
        correlation.put("temporalReason", timing.code());
        if (submission != null) {
            correlation.put("submissionObservation", submission.observationCode());
            correlation.put("submissionEvidenceSchema", submission.schemaKind());
        }
        ReceiptTemporalPolicy.SubmissionAnchor anchor = timing.anchor();
        repository.insertReceiptLink(new TrackRepository.ReceiptLinkWrite(profileId, applicationId, receiptId, planId,
                submission == null ? null : submission.evidenceId(), null, "INGESTION", "LINKED", facts.channel(),
                facts.providerId(), verification, fingerprint, submission == null ? null : submission.evidenceSha256(),
                facts.quotedReference(), submission == null ? null : submission.employerConfirmationId(),
                comparison.consistency().name(), comparison.code(), timing.evidenceTime(), timing.evidencePrecision(),
                timing.evidenceSource().name(), anchor.time(), anchor.precision(), anchor.source().name(),
                timing.status().name(), timing.skew().toSeconds(), ReceiptVerificationPolicy.VERSION, correlation,
                actor == null ? "SYSTEM" : actor));
    }

    /**
     * The durable link for the candidate's own submission statement (a CANDIDATE_STATEMENT receipt): never
     * compared as a confirmation reference, never verified, with its date marked as candidate-supplied.
     */
    public void recordCandidateStatementLink(UUID profileId, UUID applicationId, UUID receiptId, String fingerprint,
                                             Instant occurredAt, TimePrecision precision, String actor) {
        TrackRepository.SubmissionContext context = repository.submissionContext(profileId, applicationId);
        ReceiptTemporalPolicy.SubmissionAnchor robinAnchor = context == null || context.anchorPlanId() == null
                ? ReceiptTemporalPolicy.SubmissionAnchor.none() : context.anchor();
        ReceiptTemporalPolicy.Assessment timing = temporal.assess(occurredAt, precision,
                ReceiptTemporalPolicy.sourceFor(Channel.CANDIDATE_ENTERED, occurredAt, precision), robinAnchor, Instant.now());
        Map<String, Object> correlation = new LinkedHashMap<>();
        correlation.put("statement", "CANDIDATE_REPORTED_SUBMISSION");
        correlation.put("temporalPolicy", ReceiptTemporalPolicy.VERSION);
        correlation.put("temporalReason", timing.code());
        ReceiptTemporalPolicy.SubmissionAnchor anchor = timing.anchor();
        repository.insertReceiptLink(new TrackRepository.ReceiptLinkWrite(profileId, applicationId, receiptId,
                context == null ? null : context.anchorPlanId(), null, null, "INGESTION", "LINKED", Channel.CANDIDATE_ENTERED,
                null, Verification.UNVERIFIED, fingerprint, null, null, null,
                SubmissionReferencePolicy.Consistency.NOT_COMPARABLE.name(), "CANDIDATE_STATEMENT",
                timing.evidenceTime(), timing.evidencePrecision(), timing.evidenceSource().name(), anchor.time(),
                anchor.precision(), anchor.source().name(), timing.status().name(), timing.skew().toSeconds(),
                ReceiptVerificationPolicy.VERSION, correlation, actor == null ? "SYSTEM" : actor));
    }

    /**
     * Records the candidate's decision on a receipt as a new link row superseding the current one
     * (append-only). Idempotent per decision version: a concurrent second write is a no-op.
     */
    public void recordReceiptReview(UUID profileId, UUID applicationId, UUID receiptId, String decision, String actor) {
        repository.currentReceiptLink(profileId, applicationId, receiptId).ifPresent(current -> {
            String linkDecision = "CONFIRMED".equals(decision) ? "CANDIDATE_CONFIRMED" : "CANDIDATE_REJECTED";
            if (linkDecision.equals(current.decision())) return;
            Map<String, Object> correlation = new LinkedHashMap<>();
            correlation.put("previousDecision", current.decision());
            correlation.put("candidateReview", decision);
            repository.insertReceiptLink(new TrackRepository.ReceiptLinkWrite(profileId, applicationId, receiptId,
                    current.planId(), current.evidenceId(), current.id(), "CANDIDATE_REVIEW", linkDecision,
                    Channel.valueOf(current.channel()), current.providerId(), Verification.valueOf(current.receiptVerification()),
                    current.receiptFingerprint(), current.evidenceSha256(), current.receiptReference(),
                    current.submissionReference(), current.referenceConsistency(), current.referenceDetail(),
                    current.evidenceTime(), TimePrecision.valueOf(current.evidencePrecision()), current.evidenceTimeSource(),
                    current.anchorTime(), current.anchorPrecision() == null ? null : TimePrecision.valueOf(current.anchorPrecision()),
                    current.anchorSource(), current.temporalStatus(), current.clockSkewSeconds(), current.policyVersion(),
                    correlation, actor));
        });
    }

    private static boolean senderAuthenticated(MessageFacts facts) {
        return facts.channel() == Channel.MAILBOX_CONNECTOR && facts.authentication() != null
                && facts.authentication().passesFor(TrackText.domainOf(facts.fromAddress()));
    }
}
