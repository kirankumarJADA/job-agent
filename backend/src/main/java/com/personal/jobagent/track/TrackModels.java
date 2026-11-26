package com.personal.jobagent.track;

import com.personal.jobagent.track.TrackVocabulary.AcknowledgementState;
import com.personal.jobagent.track.TrackVocabulary.Basis;
import com.personal.jobagent.track.TrackVocabulary.Channel;
import com.personal.jobagent.track.TrackVocabulary.EmailCategory;
import com.personal.jobagent.track.TrackVocabulary.EvidenceType;
import com.personal.jobagent.track.TrackVocabulary.LinkStatus;
import com.personal.jobagent.track.TrackVocabulary.ReportKind;
import com.personal.jobagent.track.TrackVocabulary.Stage;
import com.personal.jobagent.track.TrackVocabulary.SubmissionState;
import com.personal.jobagent.track.TrackVocabulary.TimePrecision;
import com.personal.jobagent.track.TrackVocabulary.Verification;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Input and output shapes of {@link ApplicationReconciler}. The snapshot is a
 * plain, owner-scoped read of persisted records; the state is derived from it
 * and never stored, so reconciliation is always repeatable from the evidence.
 */
public final class TrackModels {

    private TrackModels() {
    }

    // ── snapshot (input) ─────────────────────────────────────────────────

    public record Preparation(boolean started, boolean complete, boolean failed) {
        public static Preparation none() {
            return new Preparation(false, false, false);
        }
    }

    /**
     * Latest automation plan for the application, or null when none exists.
     *
     * @param reconciliationNote Phase 15: the worker's own persisted
     *     evidence for why a real submit-click attempt's outcome could not
     *     be confirmed — populated only when {@code status} is {@code
     *     RECONCILIATION_REQUIRED} (read from the plan's own {@code result}
     *     column, the same {@code SUBMISSION_RECONCILIATION} shape {@code
     *     outcome.js} persists); {@code null} for every other status,
     *     including {@code SUBMITTED} (a confirmed submission's own evidence
     *     lives in the audit trail, not duplicated here).
     * @param verifiedReceipt Sprint 2.2.1: true only when a durable, verified
     *     {@code submission_receipts} row exists for this plan. A plan in
     *     {@code SUBMITTED} without one is shown as applied-but-unverified,
     *     never as verified.
     */
    public record PlanState(String status, boolean submitApproved, String reconciliationNote, boolean verifiedReceipt,
                            boolean browserObserved) {
        public PlanState(String status, boolean submitApproved, String reconciliationNote, boolean verifiedReceipt) {
            this(status, submitApproved, reconciliationNote, verifiedReceipt, false);
        }

        public PlanState(String status, boolean submitApproved, String reconciliationNote) {
            this(status, submitApproved, reconciliationNote, false, false);
        }

        public PlanState(String status, boolean submitApproved) {
            this(status, submitApproved, null, false, false);
        }
    }

    /**
     * Stage 3I Phase 2: what Robin's own submission record says, for the latest SUBMITTED plan.
     *
     * @param referenceTrusted true when {@code employerConfirmationId} comes from intact, worker-recorded
     *     evidence (decrypts, digest matches, worker provenance) — only then is it compared with an
     *     employer acknowledgement's reference
     * @param browserObserved a clean Level B observation ({@code BrowserObservationPolicy})
     * @param sandboxVerified a verified V069 sandbox receipt (Level C2) exists for the plan
     * @param documentsWithoutUploadProof document kinds the plan prepared for which the browser evidence
     *     shows no file part in the accepted submission request
     */
    public record SubmissionFacts(UUID planId, UUID evidenceId, String evidenceSha256, String atsPlatform,
                                  String employerConfirmationId, boolean referenceTrusted, Instant recordedAt,
                                  boolean browserObserved, boolean sandboxVerified, String schemaKind,
                                  List<String> documentsWithoutUploadProof, String observationCode) {
        public SubmissionFacts {
            documentsWithoutUploadProof = documentsWithoutUploadProof == null ? List.of() : List.copyOf(documentsWithoutUploadProof);
        }
    }

    /** A pre-8.3 STATUS_CHANGED row: a candidate transition or an inference from an email. */
    public record StatusChange(UUID eventId, String from, String to, boolean inferred, Instant recordedAt) {
    }

    public record CandidateReport(UUID eventId, ReportKind kind, LocalDate occurredOn, Instant recordedAt) {
    }

    public record ReceiptRecord(UUID id, EvidenceType type, Channel channel, Verification verification,
                                String candidateReview, boolean candidateAsserted, String externalReference,
                                Instant evidenceTime, TimePrecision precision, Instant capturedAt) {

        public boolean rejectedByCandidate() {
            return "REJECTED".equals(candidateReview);
        }

        public boolean confirmedByCandidate() {
            return "CONFIRMED".equals(candidateReview) || candidateAsserted;
        }
    }

    /**
     * One message that concerns this application: linked/confirmed to it, or a
     * pending/conflicting proposal that lists it as a candidate.
     */
    public record MessageRecord(UUID emailId, LinkStatus status, boolean linkedToThisApplication,
                                boolean candidateForThisApplication, EmailCategory inferredCategory,
                                EmailCategory confirmedCategory, Instant receivedAt, boolean receivedTimeKnown,
                                Instant recordedAt) {
    }

    /**
     * @param submission Robin's own submission record (null when Robin has not submitted, or it was not read)
     * @param anchor the earliest recorded moment a submission could have happened ({@link ReceiptTemporalPolicy})
     * @param disqualifiedReferences Robin's own identifiers and the job's posting/requisition tokens, normalised:
     *     never compared as employer confirmation references
     * @param foreignSubmissionReferences confirmation references Robin recorded for the owner's OTHER applications
     */
    public record TrackSnapshot(UUID applicationId, String recordedStatus, Instant applicationCreatedAt,
                                Preparation preparation, PlanState plan, List<StatusChange> statusChanges,
                                List<CandidateReport> reports, List<ReceiptRecord> receipts,
                                List<MessageRecord> messages, boolean mailboxConnected, Instant now,
                                SubmissionFacts submission, ReceiptTemporalPolicy.SubmissionAnchor anchor,
                                Set<String> disqualifiedReferences, Set<String> foreignSubmissionReferences) {

        public TrackSnapshot {
            anchor = anchor == null ? ReceiptTemporalPolicy.SubmissionAnchor.none() : anchor;
            disqualifiedReferences = disqualifiedReferences == null ? Set.of() : Set.copyOf(disqualifiedReferences);
            foreignSubmissionReferences = foreignSubmissionReferences == null ? Set.of() : Set.copyOf(foreignSubmissionReferences);
        }

        /** Pre-Phase-2 shape: no submission facts, no anchor. */
        public TrackSnapshot(UUID applicationId, String recordedStatus, Instant applicationCreatedAt,
                             Preparation preparation, PlanState plan, List<StatusChange> statusChanges,
                             List<CandidateReport> reports, List<ReceiptRecord> receipts,
                             List<MessageRecord> messages, boolean mailboxConnected, Instant now) {
            this(applicationId, recordedStatus, applicationCreatedAt, preparation, plan, statusChanges, reports,
                    receipts, messages, mailboxConnected, now, null, null, null, null);
        }
    }

    // ── state (output) ───────────────────────────────────────────────────

    public record Attention(String code, String message, UUID emailId, UUID receiptId) {
    }

    public record Conflict(String code, String message, List<String> references) {
    }

    /**
     * @param verificationLevel NOT_ATTEMPTED, ATTEMPT_RECORDED, BROWSER_OBSERVED, CANDIDATE_REPORTED,
     *     SANDBOX_VERIFIED, EMPLOYER_EMAIL_VERIFIED or CONFLICTING — the strongest source behind {@code state}
     * @param sandbox true when the confirmation rests on a synthetic sandbox receipt (never a live employer)
     */
    public record SubmissionView(SubmissionState state, String label, String reason, String verificationLevel,
                                 boolean sandbox) {
        public SubmissionView(SubmissionState state, String label, String reason) {
            this(state, label, reason, null, false);
        }
    }

    public record AcknowledgementView(AcknowledgementState state, String label) {
    }

    public record Communications(int linkedMessages, int awaitingReview) {
    }

    /** A stage an inferred message suggests but that waits for the candidate's confirmation. */
    public record PendingSuggestion(Stage stage, String label, UUID emailId) {
    }

    public record TrackState(Stage status, String label, Basis basis, String basisLabel, String reason,
                             Instant since, TimePrecision sincePrecision, Stage evidenceStage,
                             String recordedStatus, String recordedStatusLabel,
                             SubmissionView submission, AcknowledgementView acknowledgement,
                             Communications communications, PendingSuggestion pending,
                             List<Attention> attention, List<String> unknowns, List<Conflict> conflicts,
                             boolean robinSubmitted, String robinSubmissionNote) {

        public boolean needsReview() {
            return status == Stage.NEEDS_REVIEW || !attention.isEmpty();
        }
    }
}
