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

    /** Latest automation plan for the application, or null when none exists. */
    public record PlanState(String status, boolean submitApproved) {
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

    public record TrackSnapshot(UUID applicationId, String recordedStatus, Instant applicationCreatedAt,
                                Preparation preparation, PlanState plan, List<StatusChange> statusChanges,
                                List<CandidateReport> reports, List<ReceiptRecord> receipts,
                                List<MessageRecord> messages, boolean mailboxConnected, Instant now) {
    }

    // ── state (output) ───────────────────────────────────────────────────

    public record Attention(String code, String message, UUID emailId, UUID receiptId) {
    }

    public record Conflict(String code, String message, List<String> references) {
    }

    public record SubmissionView(SubmissionState state, String label, String reason) {
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
