package com.personal.jobagent.track;

import com.personal.jobagent.track.TrackModels.CandidateReport;
import com.personal.jobagent.track.TrackModels.MessageRecord;
import com.personal.jobagent.track.TrackModels.PlanState;
import com.personal.jobagent.track.TrackModels.Preparation;
import com.personal.jobagent.track.TrackModels.ReceiptRecord;
import com.personal.jobagent.track.TrackModels.StatusChange;
import com.personal.jobagent.track.TrackModels.TrackSnapshot;
import com.personal.jobagent.track.TrackModels.TrackState;
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
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Conservative reconciliation: what moves the stage, what never does, how
 * contradictions surface, and how missing information is reported.
 */
class ApplicationReconcilerTest {

    private static final UUID APP = UUID.fromString("00000000-0000-7000-8000-0000000000aa");
    private static final Instant CREATED = Instant.parse("2026-09-01T09:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

    private final ApplicationReconciler reconciler = new ApplicationReconciler();

    /** A mutable snapshot builder; every list starts empty. */
    private static final class S {
        String recorded = "READY_TO_APPLY";
        Preparation preparation = new Preparation(true, true, false);
        PlanState plan;
        final List<StatusChange> changes = new ArrayList<>();
        final List<CandidateReport> reports = new ArrayList<>();
        final List<ReceiptRecord> receipts = new ArrayList<>();
        final List<MessageRecord> messages = new ArrayList<>();

        TrackSnapshot build() {
            return new TrackSnapshot(APP, recorded, CREATED, preparation, plan, changes, reports, receipts, messages, false, NOW);
        }
    }

    private TrackState reconcile(S s) {
        return reconciler.reconcile(s.build());
    }

    private static Instant at(String date) {
        return Instant.parse(date + "T10:00:00Z");
    }

    private static ReceiptRecord ack(Verification v, String reference, Instant time) {
        return new ReceiptRecord(UUID.randomUUID(), EvidenceType.EMPLOYER_ACKNOWLEDGEMENT_EMAIL,
                v == Verification.VERIFIED ? Channel.MAILBOX_CONNECTOR : Channel.CANDIDATE_FORWARDED, v, null, false,
                reference, time, TimePrecision.EXACT, time);
    }

    private static MessageRecord linked(EmailCategory inferred, EmailCategory confirmed, Instant received) {
        return new MessageRecord(UUID.randomUUID(), confirmed == null ? LinkStatus.LINKED : LinkStatus.CONFIRMED, true, false,
                inferred, confirmed, received, true, received);
    }

    private static CandidateReport report(ReportKind kind, String date, Instant recorded) {
        return new CandidateReport(UUID.randomUUID(), kind, date == null ? null : LocalDate.parse(date), recorded);
    }

    // ── nothing is a submission unless evidence says so ─────────────────

    @Test
    void anApplicationStillBeingPreparedIsPreparing() {
        S s = new S();
        s.preparation = Preparation.none();
        TrackState state = reconcile(s);
        assertThat(state.status()).isEqualTo(Stage.PREPARING);
        assertThat(state.submission().state()).isEqualTo(SubmissionState.UNKNOWN);
    }

    @Test
    void readyToApplyIsNotSubmitted() {
        TrackState state = reconcile(new S());
        assertThat(state.status()).isEqualTo(Stage.READY_TO_APPLY);
        assertThat(state.basis()).isEqualTo(Basis.INTERNAL);
        assertThat(state.submission().state()).isEqualTo(SubmissionState.UNKNOWN);
        assertThat(state.robinSubmitted()).isFalse();
        assertThat(state.label()).doesNotContainIgnoringCase("submitted —").isEqualTo("Prepared — not submitted");
    }

    @Test
    void anApprovedPackageIsNotASubmission() {
        S s = new S();
        s.plan = new PlanState("READY_TO_SUBMIT", true);
        TrackState state = reconcile(s);
        assertThat(state.status()).isEqualTo(Stage.READY_TO_APPLY);
        assertThat(state.submission().state()).isEqualTo(SubmissionState.UNKNOWN);
        assertThat(state.reason()).contains("an approval is not a submission");
    }

    @Test
    void aQueuedOrAttemptedRunIsNotASubmission() {
        for (String planStatus : List.of("PREPARED", "RUNNING", "COMPLETED", "FAILED", "AWAITING_APPROVAL")) {
            S s = new S();
            s.plan = new PlanState(planStatus, false);
            TrackState state = reconcile(s);
            assertThat(state.status()).as(planStatus).isEqualTo(Stage.READY_TO_APPLY);
            assertThat(state.submission().state()).as(planStatus).isEqualTo(SubmissionState.UNKNOWN);
        }
    }

    @Test
    void aConfirmedRobinSubmissionIsAppliedAndVerified() {
        S s = new S();
        s.plan = new PlanState("SUBMITTED", true);
        TrackState state = reconcile(s);
        assertThat(state.status()).isEqualTo(Stage.APPLIED);
        assertThat(state.basis()).isEqualTo(Basis.VERIFIED);
        assertThat(state.label()).isEqualTo("Applied — verified");
        assertThat(state.submission().state()).isEqualTo(SubmissionState.CONFIRMED);
        assertThat(state.robinSubmitted()).isTrue();
        assertThat(state.robinSubmissionNote()).contains("Robin submitted this application and confirmed it with platform evidence");
        assertThat(state.reason()).contains("Robin submitted this application and confirmed it with platform evidence");
    }

    @Test
    void anUnconfirmedRealSubmitClickRequiresReconciliationAndNeverAutoRetries() {
        S s = new S();
        s.plan = new PlanState("RECONCILIATION_REQUIRED", true, "[FORM_STILL_PRESENT] the form was still visible");
        TrackState state = reconcile(s);
        assertThat(state.status()).isEqualTo(Stage.READY_TO_APPLY);
        assertThat(state.basis()).isEqualTo(Basis.INTERNAL);
        assertThat(state.robinSubmitted()).isFalse();
        assertThat(state.submission().state()).isEqualTo(SubmissionState.UNVERIFIED);
        assertThat(state.reason()).contains("A real submission was attempted but its outcome could not be confirmed — Robin will never retry it automatically");
        assertThat(state.attention()).extracting(TrackModels.Attention::code).contains("RECONCILIATION_REQUIRED");
        assertThat(state.attention()).extracting(TrackModels.Attention::message)
                .anyMatch(m -> m.contains("[FORM_STILL_PRESENT] the form was still visible") && m.contains("verify with the employer"));
        assertThat(state.robinSubmissionNote()).contains("A real submission was attempted by Robin, but the employer's acceptance could not be confirmed");
    }

    // ── candidate reports and receipts ──────────────────────────────────

    @Test
    void aCandidateReportedSubmissionIsAppliedButUnverified() {
        S s = new S();
        s.reports.add(report(ReportKind.SUBMITTED, "2026-10-02", at("2026-10-02")));
        TrackState state = reconcile(s);
        assertThat(state.status()).isEqualTo(Stage.APPLIED);
        assertThat(state.basis()).isEqualTo(Basis.CANDIDATE_REPORTED);
        assertThat(state.label()).isEqualTo("Applied — reported by you");
        assertThat(state.submission().state()).isEqualTo(SubmissionState.UNVERIFIED);
        assertThat(state.sincePrecision()).isEqualTo(TimePrecision.DATE);
        assertThat(state.unknowns()).anyMatch(u -> u.contains("not verified"));
    }

    @Test
    void aVerifiedAcknowledgementConfirmsSubmission() {
        S s = new S();
        s.receipts.add(ack(Verification.VERIFIED, "APP-1", at("2026-10-03")));
        TrackState state = reconcile(s);
        assertThat(state.status()).isEqualTo(Stage.ACKNOWLEDGED);
        assertThat(state.basis()).isEqualTo(Basis.VERIFIED);
        assertThat(state.submission().state()).isEqualTo(SubmissionState.CONFIRMED);
        assertThat(state.acknowledgement().state()).isEqualTo(AcknowledgementState.VERIFIED);
    }

    @Test
    void anUnverifiedAcknowledgementForThisApplicationIsShownAsUnverified() {
        S s = new S();
        s.receipts.add(ack(Verification.UNVERIFIED, null, at("2026-10-03")));
        TrackState state = reconcile(s);
        assertThat(state.status()).isEqualTo(Stage.ACKNOWLEDGED);
        assertThat(state.basis()).isEqualTo(Basis.UNVERIFIED);
        assertThat(state.label()).isEqualTo("Acknowledged by the employer — not verified");
        assertThat(state.submission().state()).isEqualTo(SubmissionState.UNVERIFIED);
        assertThat(state.acknowledgement().state()).isEqualTo(AcknowledgementState.UNVERIFIED);
    }

    @Test
    void anAcknowledgementThatDoesNotIdentifyTheApplicationNeedsTheCandidate() {
        S s = new S();
        s.receipts.add(ack(Verification.UNKNOWN, null, at("2026-10-03")));
        TrackState state = reconcile(s);
        assertThat(state.status()).isEqualTo(Stage.READY_TO_APPLY);
        assertThat(state.attention()).extracting(TrackModels.Attention::code).contains("RECEIPT_NEEDS_CONFIRMATION");

        S confirmed = new S();
        ReceiptRecord r = ack(Verification.UNKNOWN, null, at("2026-10-03"));
        confirmed.receipts.add(new ReceiptRecord(r.id(), r.type(), r.channel(), r.verification(), "CONFIRMED", false,
                null, r.evidenceTime(), r.precision(), r.capturedAt()));
        TrackState after = reconcile(confirmed);
        assertThat(after.status()).isEqualTo(Stage.ACKNOWLEDGED);
        assertThat(after.basis()).isEqualTo(Basis.CANDIDATE_CONFIRMED);
        assertThat(after.submission().state()).isEqualTo(SubmissionState.UNVERIFIED);
    }

    @Test
    void evidenceTheCandidateRejectedOrThePolicyRejectedNeverCounts() {
        S s = new S();
        ReceiptRecord v = ack(Verification.VERIFIED, "APP-1", at("2026-10-03"));
        s.receipts.add(new ReceiptRecord(v.id(), v.type(), v.channel(), v.verification(), "REJECTED", false, "APP-1",
                v.evidenceTime(), v.precision(), v.capturedAt()));
        s.receipts.add(ack(Verification.REJECTED, "APP-9", at("2026-10-04")));
        TrackState state = reconcile(s);
        assertThat(state.status()).isEqualTo(Stage.READY_TO_APPLY);
        assertThat(state.submission().state()).isEqualTo(SubmissionState.UNKNOWN);
    }

    // ── inference never moves the stage ─────────────────────────────────

    @Test
    void anInferredInterviewIsSuggestedNotApplied() {
        S s = new S();
        s.reports.add(report(ReportKind.SUBMITTED, "2026-10-02", at("2026-10-02")));
        MessageRecord interview = linked(EmailCategory.INTERVIEW_INVITATION, null, at("2026-10-06"));
        s.messages.add(interview);
        TrackState state = reconcile(s);
        assertThat(state.status()).isEqualTo(Stage.APPLIED);
        assertThat(state.pending()).isNotNull();
        assertThat(state.pending().stage()).isEqualTo(Stage.INTERVIEW);
        assertThat(state.pending().emailId()).isEqualTo(interview.emailId());
        assertThat(state.attention()).extracting(TrackModels.Attention::code).contains("CONFIRM_MESSAGE_MEANING");
        assertThat(state.needsReview()).isTrue();
    }

    @Test
    void aConfirmedInterviewMovesTheStageAsConfirmedByTheCandidate() {
        S s = new S();
        s.reports.add(report(ReportKind.SUBMITTED, "2026-10-02", at("2026-10-02")));
        s.messages.add(linked(EmailCategory.INTERVIEW_INVITATION, EmailCategory.INTERVIEW_INVITATION, at("2026-10-06")));
        TrackState state = reconcile(s);
        assertThat(state.status()).isEqualTo(Stage.INTERVIEW);
        assertThat(state.basis()).isEqualTo(Basis.CANDIDATE_CONFIRMED);
        assertThat(state.label()).isEqualTo("Interview — confirmed by you");
        // An interview implies the employer has the application, but it does not verify a submission.
        assertThat(state.submission().state()).isEqualTo(SubmissionState.UNVERIFIED);
    }

    @Test
    void aPendingAssociationReviewIsAttentionNotAStage() {
        S s = new S();
        s.messages.add(new MessageRecord(UUID.randomUUID(), LinkStatus.PENDING_REVIEW, false, true,
                EmailCategory.REJECTION, null, at("2026-10-06"), true, at("2026-10-06")));
        TrackState state = reconcile(s);
        assertThat(state.status()).isEqualTo(Stage.READY_TO_APPLY);
        assertThat(state.attention()).extracting(TrackModels.Attention::code).containsExactly("MESSAGE_ASSOCIATION_REVIEW");
        assertThat(state.communications().awaitingReview()).isEqualTo(1);
    }

    // ── ordering and strength ───────────────────────────────────────────

    @Test
    void aLaterRejectionEndsProgressButAnEarlierOneDoesNot() {
        S later = new S();
        later.messages.add(linked(EmailCategory.INTERVIEW_INVITATION, EmailCategory.INTERVIEW_INVITATION, at("2026-10-06")));
        later.messages.add(linked(EmailCategory.REJECTION, EmailCategory.REJECTION, at("2026-10-12")));
        assertThat(reconcile(later).status()).isEqualTo(Stage.REJECTED);

        S earlier = new S();
        earlier.messages.add(linked(EmailCategory.REJECTION, EmailCategory.REJECTION, at("2026-10-01")));
        earlier.messages.add(linked(EmailCategory.INTERVIEW_INVITATION, EmailCategory.INTERVIEW_INVITATION, at("2026-10-06")));
        assertThat(reconcile(earlier).status()).isEqualTo(Stage.INTERVIEW);
    }

    @Test
    void eventTimeNotIngestionTimeDecidesOrder() {
        S s = new S();
        // A rejection on 8 Oct, and an older acknowledgement (1 Oct) added much later (20 Oct).
        s.messages.add(linked(EmailCategory.REJECTION, EmailCategory.REJECTION, at("2026-10-08")));
        ReceiptRecord oldAck = new ReceiptRecord(UUID.randomUUID(), EvidenceType.EMPLOYER_ACKNOWLEDGEMENT_EMAIL,
                Channel.CANDIDATE_FORWARDED, Verification.UNVERIFIED, null, false, null, at("2026-10-01"),
                TimePrecision.EXACT, at("2026-10-20"));
        s.receipts.add(oldAck);
        assertThat(reconcile(s).status()).isEqualTo(Stage.REJECTED);
    }

    @Test
    void weakerOrOlderEvidenceNeverPullsStrongerEvidenceBack() {
        S s = new S();
        s.receipts.add(ack(Verification.VERIFIED, "APP-1", at("2026-10-02")));
        s.reports.add(report(ReportKind.SUBMITTED, "2026-10-10", at("2026-10-10")));
        TrackState state = reconcile(s);
        assertThat(state.status()).isEqualTo(Stage.ACKNOWLEDGED);
        assertThat(state.basis()).isEqualTo(Basis.VERIFIED);
        assertThat(state.submission().state()).isEqualTo(SubmissionState.CONFIRMED);
    }

    @Test
    void undatedEventsAreOrderedByRecordingAndSaySo() {
        S s = new S();
        s.reports.add(report(ReportKind.SUBMITTED, null, at("2026-10-05")));
        TrackState state = reconcile(s);
        assertThat(state.status()).isEqualTo(Stage.APPLIED);
        assertThat(state.since()).isNull();
        assertThat(state.unknowns()).anyMatch(u -> u.contains("no known date"));
    }

    // ── contradictions are surfaced, not guessed ────────────────────────

    @Test
    void acknowledgementsQuotingDifferentReferencesNeedReview() {
        S s = new S();
        s.receipts.add(ack(Verification.UNVERIFIED, "APP-111", at("2026-10-03")));
        s.receipts.add(ack(Verification.UNVERIFIED, "APP-222", at("2026-10-04")));
        TrackState state = reconcile(s);
        assertThat(state.status()).isEqualTo(Stage.NEEDS_REVIEW);
        assertThat(state.submission().state()).isEqualTo(SubmissionState.CONFLICTING);
        assertThat(state.conflicts()).extracting(TrackModels.Conflict::code).contains("RECEIPT_REFERENCE_CONFLICT");
        assertThat(state.evidenceStage()).isEqualTo(Stage.ACKNOWLEDGED);
    }

    @Test
    void aChoiceContradictedByTheMessageNeedsReview() {
        S s = new S();
        s.messages.add(new MessageRecord(UUID.randomUUID(), LinkStatus.CONFLICT, true, true,
                EmailCategory.APPLICATION_ACKNOWLEDGEMENT, null, at("2026-10-03"), true, at("2026-10-03")));
        assertThat(reconcile(s).status()).isEqualTo(Stage.NEEDS_REVIEW);
    }

    @Test
    void anOfferAndARejectionOnTheSameDayNeedReview() {
        S s = new S();
        s.messages.add(linked(EmailCategory.OFFER, EmailCategory.OFFER, Instant.parse("2026-10-09T09:00:00Z")));
        s.reports.add(report(ReportKind.REJECTED, "2026-10-09", at("2026-10-09")));
        TrackState state = reconcile(s);
        assertThat(state.status()).isEqualTo(Stage.NEEDS_REVIEW);
        assertThat(state.conflicts()).extracting(TrackModels.Conflict::code).contains("OUTCOME_CONFLICT");
    }

    // ── Robin's recorded (pre-8.3) status ────────────────────────────────

    @Test
    void aRecordedStatusSetFromAnEmailInferenceNeedsReview() {
        S s = new S();
        s.recorded = "INTERVIEW";
        s.changes.add(new StatusChange(UUID.randomUUID(), "READY_TO_APPLY", "APPLICATION_STARTED", false, at("2026-09-10")));
        s.changes.add(new StatusChange(UUID.randomUUID(), "APPLICATION_STARTED", "APPLICATION_SUBMITTED", false, at("2026-09-11")));
        s.changes.add(new StatusChange(UUID.randomUUID(), "APPLICATION_SUBMITTED", "INTERVIEW", true, at("2026-09-20")));
        TrackState state = reconcile(s);
        assertThat(state.status()).isEqualTo(Stage.NEEDS_REVIEW);
        assertThat(state.evidenceStage()).isEqualTo(Stage.APPLIED);
        assertThat(state.conflicts()).extracting(TrackModels.Conflict::code).contains("RECORDED_STATUS_INFERRED");
    }

    @Test
    void aRecordedStatusTheCandidateSetIsReportedByThem() {
        S s = new S();
        s.recorded = "APPLICATION_SUBMITTED";
        s.changes.add(new StatusChange(UUID.randomUUID(), "READY_TO_APPLY", "APPLICATION_STARTED", false, at("2026-09-10")));
        s.changes.add(new StatusChange(UUID.randomUUID(), "APPLICATION_STARTED", "APPLICATION_SUBMITTED", false, at("2026-09-11")));
        TrackState state = reconcile(s);
        assertThat(state.status()).isEqualTo(Stage.APPLIED);
        assertThat(state.basis()).isEqualTo(Basis.CANDIDATE_REPORTED);
        assertThat(state.submission().state()).isEqualTo(SubmissionState.UNVERIFIED);
    }

    @Test
    void aRecordedStatusWithNoHistoryIsUnknownNotTrusted() {
        S s = new S();
        s.recorded = "OFFER";
        TrackState state = reconcile(s);
        assertThat(state.status()).isEqualTo(Stage.UNKNOWN);
        assertThat(state.reason()).contains("no record of how it was set");
    }

    @Test
    void withdrawingInRobinIsFinalAndLaterEmployerActivityIsFlagged() {
        S s = new S();
        s.recorded = "WITHDRAWN";
        s.changes.add(new StatusChange(UUID.randomUUID(), "READY_TO_APPLY", "WITHDRAWN", false, at("2026-10-01")));
        s.messages.add(linked(EmailCategory.INTERVIEW_INVITATION, EmailCategory.INTERVIEW_INVITATION, at("2026-10-05")));
        TrackState state = reconcile(s);
        assertThat(state.status()).isEqualTo(Stage.WITHDRAWN);
        assertThat(state.basis()).isEqualTo(Basis.INTERNAL);
        assertThat(state.attention()).extracting(TrackModels.Attention::code).contains("ACTIVITY_AFTER_WITHDRAWAL");
    }

    @Test
    void aFailedApplicationIsStopped() {
        S s = new S();
        s.recorded = "FAILED";
        assertThat(reconcile(s).status()).isEqualTo(Stage.STOPPED);
    }

    @Test
    void aReportedWithdrawalWithTheEmployerEndsProgressAsReported() {
        S s = new S();
        s.reports.add(report(ReportKind.SUBMITTED, "2026-10-01", at("2026-10-01")));
        s.reports.add(report(ReportKind.WITHDRAWN, "2026-10-04", at("2026-10-04")));
        TrackState state = reconcile(s);
        assertThat(state.status()).isEqualTo(Stage.WITHDRAWN);
        assertThat(state.basis()).isEqualTo(Basis.CANDIDATE_REPORTED);
    }

    @Test
    void theMissingMailboxConnectionIsStatedAsAnUnknown() {
        TrackState state = reconcile(new S());
        assertThat(state.unknowns()).anyMatch(u -> u.contains("cannot read your mailbox"));
    }

    @Test
    void reconciliationIsDeterministic() {
        S s = new S();
        s.reports.add(report(ReportKind.SUBMITTED, "2026-10-02", at("2026-10-02")));
        s.receipts.add(ack(Verification.UNVERIFIED, null, at("2026-10-03")));
        TrackSnapshot snapshot = s.build();
        assertThat(reconciler.reconcile(snapshot)).isEqualTo(reconciler.reconcile(snapshot));
    }
}
