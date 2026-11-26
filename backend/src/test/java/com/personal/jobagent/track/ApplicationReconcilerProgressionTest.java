package com.personal.jobagent.track;

import com.personal.jobagent.track.ReceiptTemporalPolicy.AnchorSource;
import com.personal.jobagent.track.ReceiptTemporalPolicy.SubmissionAnchor;
import com.personal.jobagent.track.TrackModels.CandidateReport;
import com.personal.jobagent.track.TrackModels.MessageRecord;
import com.personal.jobagent.track.TrackModels.PlanState;
import com.personal.jobagent.track.TrackModels.Preparation;
import com.personal.jobagent.track.TrackModels.ReceiptRecord;
import com.personal.jobagent.track.TrackModels.SubmissionFacts;
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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 3I Phase 2 (Gate 2-4): lifecycle progression from a Robin submission through employer
 * acknowledgement and later lifecycle updates, reference and timing conflicts, and determinism.
 */
class ApplicationReconcilerProgressionTest {

    private static final UUID APP = UUID.fromString("00000000-0000-7000-8000-0000000000bb");
    private static final UUID PLAN = UUID.fromString("00000000-0000-7000-8000-0000000000cc");
    private static final Instant CREATED = Instant.parse("2026-11-01T09:00:00Z");
    private static final Instant FENCE_AT = Instant.parse("2026-11-10T10:00:00Z");
    private static final Instant SUBMITTED_AT = FENCE_AT.plusSeconds(20);
    private static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");
    private static final SubmissionAnchor FENCE = new SubmissionAnchor(FENCE_AT, TimePrecision.EXACT, AnchorSource.PRE_SUBMIT_ATTEMPT);

    private final ApplicationReconciler reconciler = new ApplicationReconciler();

    private static final class S {
        String recorded = "READY_TO_APPLY";
        PlanState plan;
        SubmissionFacts submission;
        SubmissionAnchor anchor = SubmissionAnchor.none();
        Set<String> disqualified = Set.of();
        Set<String> foreign = Set.of();
        final List<CandidateReport> reports = new ArrayList<>();
        final List<ReceiptRecord> receipts = new ArrayList<>();
        final List<MessageRecord> messages = new ArrayList<>();

        TrackSnapshot build() {
            return new TrackSnapshot(APP, recorded, CREATED, new Preparation(true, true, false), plan, List.of(), reports,
                    receipts, messages, true, NOW, submission, anchor, disqualified, foreign);
        }
    }

    /** A clean Level B Universal browser observation recorded at SUBMITTED_AT, with the given reference. */
    private static S browserObserved(String reference) {
        S s = new S();
        s.plan = new PlanState("SUBMITTED", true, null, false, true);
        s.submission = new SubmissionFacts(PLAN, UUID.randomUUID(), "a".repeat(64), "UNIVERSAL", reference,
                reference != null, SUBMITTED_AT, true, false, "V2", List.of(), "BROWSER_OBSERVED_V2");
        s.anchor = FENCE;
        return s;
    }

    private static ReceiptRecord ack(Verification v, String reference, Instant time) {
        return ack(v, reference, time, null);
    }

    private static ReceiptRecord ack(Verification v, String reference, Instant time, String review) {
        return new ReceiptRecord(UUID.randomUUID(), EvidenceType.EMPLOYER_ACKNOWLEDGEMENT_EMAIL,
                v == Verification.VERIFIED ? Channel.MAILBOX_CONNECTOR : Channel.CANDIDATE_FORWARDED, v, review, false,
                reference, time, time == null ? TimePrecision.UNKNOWN : TimePrecision.EXACT, time == null ? NOW : time);
    }

    private static MessageRecord confirmed(EmailCategory category, Instant received) {
        return new MessageRecord(UUID.randomUUID(), LinkStatus.CONFIRMED, true, false, category, category, received, true, received);
    }

    private TrackState reconcile(S s) {
        return reconciler.reconcile(s.build());
    }

    // ── Level B: browser-observed, never verified ─────────────────────────

    @Test
    void levelBIsAppliedNotVerifiedAndNeedsNoMandatoryReview() {
        TrackState state = reconcile(browserObserved("APP-50599"));
        assertThat(state.status()).isEqualTo(Stage.APPLIED);
        assertThat(state.basis()).isEqualTo(Basis.UNVERIFIED);
        assertThat(state.label()).isEqualTo("Applied — not verified");
        assertThat(state.submission().state()).isEqualTo(SubmissionState.UNVERIFIED);
        assertThat(state.submission().verificationLevel()).isEqualTo("BROWSER_OBSERVED");
        assertThat(state.submission().sandbox()).isFalse();
        assertThat(state.acknowledgement().state()).isEqualTo(AcknowledgementState.NONE);
        assertThat(state.attention()).isEmpty();
        assertThat(state.needsReview()).isFalse();
        assertThat(state.since()).isEqualTo(SUBMITTED_AT);
        assertThat(state.robinSubmissionNote()).contains("observed the employer's confirmation page")
                .contains("has not independently verified");
        assertThat(state.unknowns()).anyMatch(u -> u.contains("employer has not verified receipt yet"));
    }

    @Test
    void levelBFollowedByAVerifiedAcknowledgementAdvancesToReceivedVerified() {
        S s = browserObserved("APP-50599");
        s.receipts.add(ack(Verification.VERIFIED, "APP-50599", SUBMITTED_AT.plus(Duration.ofMinutes(3))));
        TrackState state = reconcile(s);
        assertThat(state.status()).isEqualTo(Stage.ACKNOWLEDGED);
        assertThat(state.basis()).isEqualTo(Basis.VERIFIED);
        assertThat(state.label()).isEqualTo("Acknowledged by the employer — verified");
        assertThat(state.submission().state()).isEqualTo(SubmissionState.CONFIRMED);
        assertThat(state.submission().verificationLevel()).isEqualTo("EMPLOYER_EMAIL_VERIFIED");
        assertThat(state.acknowledgement().state()).isEqualTo(AcknowledgementState.VERIFIED);
        assertThat(state.conflicts()).isEmpty();
        assertThat(state.robinSubmitted()).isTrue();
    }

    @Test
    void levelBFollowedByAnUnverifiedEmailStaysUnverified() {
        S s = browserObserved("APP-50599");
        s.receipts.add(ack(Verification.UNVERIFIED, null, SUBMITTED_AT.plus(Duration.ofHours(1))));
        TrackState state = reconcile(s);
        assertThat(state.status()).isEqualTo(Stage.ACKNOWLEDGED);
        assertThat(state.basis()).isEqualTo(Basis.UNVERIFIED);
        assertThat(state.submission().state()).isEqualTo(SubmissionState.UNVERIFIED);
        assertThat(state.submission().verificationLevel()).isEqualTo("BROWSER_OBSERVED");
        assertThat(state.acknowledgement().state()).isEqualTo(AcknowledgementState.UNVERIFIED);
    }

    // ── Level D: later lifecycle stages; history preserved ────────────────

    @Test
    void laterInterviewAndAssessmentAdvancePastAcknowledgementWithoutLosingIt() {
        S s = browserObserved("APP-50599");
        s.receipts.add(ack(Verification.VERIFIED, "APP-50599", SUBMITTED_AT.plus(Duration.ofMinutes(3))));
        s.messages.add(confirmed(EmailCategory.ASSESSMENT_INVITATION, SUBMITTED_AT.plus(Duration.ofDays(2))));
        TrackState assessment = reconcile(s);
        assertThat(assessment.status()).isEqualTo(Stage.ASSESSMENT);
        assertThat(assessment.basis()).isEqualTo(Basis.CANDIDATE_CONFIRMED);

        s.messages.add(confirmed(EmailCategory.INTERVIEW_INVITATION, SUBMITTED_AT.plus(Duration.ofDays(6))));
        TrackState interview = reconcile(s);
        assertThat(interview.status()).isEqualTo(Stage.INTERVIEW);
        assertThat(interview.acknowledgement().state()).isEqualTo(AcknowledgementState.VERIFIED);
        assertThat(interview.submission().state()).isEqualTo(SubmissionState.CONFIRMED);
    }

    @Test
    void aLaterRejectionEndsProgressButPreservesTheSubmissionAndAcknowledgement() {
        S s = browserObserved("APP-50599");
        s.receipts.add(ack(Verification.VERIFIED, "APP-50599", SUBMITTED_AT.plus(Duration.ofMinutes(3))));
        s.messages.add(confirmed(EmailCategory.REJECTION, SUBMITTED_AT.plus(Duration.ofDays(9))));
        TrackState state = reconcile(s);
        assertThat(state.status()).isEqualTo(Stage.REJECTED);
        assertThat(state.robinSubmitted()).isTrue();
        assertThat(state.submission().state()).isEqualTo(SubmissionState.CONFIRMED);
        assertThat(state.acknowledgement().state()).isEqualTo(AcknowledgementState.VERIFIED);
    }

    @Test
    void aWithdrawalPreservesTheBrowserObservedSubmission() {
        S s = browserObserved("APP-50599");
        s.recorded = "WITHDRAWN";
        TrackState state = reconcile(s);
        assertThat(state.status()).isEqualTo(Stage.WITHDRAWN);
        assertThat(state.robinSubmitted()).isTrue();
        assertThat(state.submission().verificationLevel()).isEqualTo("BROWSER_OBSERVED");
        assertThat(state.submission().state()).isEqualTo(SubmissionState.UNVERIFIED);
    }

    @Test
    void anOlderMessageArrivingLaterNeverOverwritesANewerStage() {
        S s = browserObserved("APP-50599");
        s.messages.add(confirmed(EmailCategory.INTERVIEW_INVITATION, SUBMITTED_AT.plus(Duration.ofDays(6))));
        // An acknowledgement dated before the interview, ingested after it.
        s.receipts.add(new ReceiptRecord(UUID.randomUUID(), EvidenceType.EMPLOYER_ACKNOWLEDGEMENT_EMAIL, Channel.MAILBOX_CONNECTOR,
                Verification.VERIFIED, null, false, "APP-50599", SUBMITTED_AT.plus(Duration.ofMinutes(4)), TimePrecision.EXACT,
                NOW));
        // And a rejection dated before the interview.
        s.messages.add(confirmed(EmailCategory.REJECTION, SUBMITTED_AT.plus(Duration.ofDays(1))));
        TrackState state = reconcile(s);
        assertThat(state.status()).isEqualTo(Stage.INTERVIEW);
        assertThat(state.acknowledgement().state()).isEqualTo(AcknowledgementState.VERIFIED);
    }

    @Test
    void outOfOrderInputsReconcileToTheSameStateWhateverTheOrder() {
        S s = browserObserved("APP-50599");
        s.receipts.add(ack(Verification.VERIFIED, "APP-50599", SUBMITTED_AT.plus(Duration.ofMinutes(3))));
        s.receipts.add(ack(Verification.UNVERIFIED, null, SUBMITTED_AT.plus(Duration.ofMinutes(3))));
        s.messages.add(confirmed(EmailCategory.ASSESSMENT_INVITATION, SUBMITTED_AT.plus(Duration.ofDays(2))));
        s.messages.add(confirmed(EmailCategory.RECRUITER_FOLLOW_UP, SUBMITTED_AT.plus(Duration.ofDays(2))));
        s.messages.add(new MessageRecord(UUID.randomUUID(), LinkStatus.LINKED, true, false, EmailCategory.OFFER, null,
                SUBMITTED_AT.plus(Duration.ofDays(8)), true, SUBMITTED_AT.plus(Duration.ofDays(8))));
        s.reports.add(new CandidateReport(UUID.randomUUID(), ReportKind.INTERVIEW, LocalDate.parse("2026-11-14"), NOW));
        TrackState reference = reconcile(s);
        Random random = new Random(42);
        for (int i = 0; i < 25; i++) {
            Collections.shuffle(s.receipts, random);
            Collections.shuffle(s.messages, random);
            assertThat(reconcile(s)).isEqualTo(reference);
        }
        assertThat(reference.status()).isEqualTo(Stage.INTERVIEW);
        assertThat(reference.pending()).isNotNull();
        assertThat(reference.pending().stage()).isEqualTo(Stage.OFFER);
    }

    // ── Gate 3: submission-reference conflicts ────────────────────────────

    @Test
    void aVerifiedAcknowledgementQuotingADifferentReferenceIsAConflictNeverVerified() {
        S s = browserObserved("APP-50599");
        ReceiptRecord receipt = ack(Verification.VERIFIED, "APP-77777", SUBMITTED_AT.plus(Duration.ofMinutes(3)));
        s.receipts.add(receipt);
        TrackState state = reconcile(s);
        assertThat(state.status()).isEqualTo(Stage.NEEDS_REVIEW);
        assertThat(state.basis()).isNull();
        assertThat(state.submission().state()).isEqualTo(SubmissionState.CONFLICTING);
        assertThat(state.acknowledgement().state()).isNotEqualTo(AcknowledgementState.VERIFIED);
        assertThat(state.conflicts()).extracting(TrackModels.Conflict::code).containsExactly("SUBMISSION_REFERENCE_CONFLICT");
        assertThat(state.conflicts().get(0).references())
                .contains("acknowledgement:APP-77777", "browser-observed:APP-50599", "receipt:" + receipt.id());
        assertThat(state.conflicts().get(0).message()).contains("APP-77777").contains("APP-50599");
        assertThat(state.attention()).anyMatch(a -> a.code().equals("RESOLVE_REFERENCE_CONFLICT") && receipt.id().equals(a.receiptId()));
    }

    @Test
    void theCandidateResolvesAReferenceConflictAsTheirOwnConfirmationNotVerification() {
        S s = browserObserved("APP-50599");
        s.receipts.add(ack(Verification.VERIFIED, "APP-77777", SUBMITTED_AT.plus(Duration.ofMinutes(3)), "CONFIRMED"));
        TrackState state = reconcile(s);
        assertThat(state.conflicts()).isEmpty();
        assertThat(state.status()).isEqualTo(Stage.ACKNOWLEDGED);
        assertThat(state.basis()).isEqualTo(Basis.CANDIDATE_CONFIRMED);
        assertThat(state.acknowledgement().state()).isEqualTo(AcknowledgementState.UNVERIFIED);
        assertThat(state.submission().state()).isEqualTo(SubmissionState.UNVERIFIED);

        S rejected = browserObserved("APP-50599");
        rejected.receipts.add(ack(Verification.VERIFIED, "APP-77777", SUBMITTED_AT.plus(Duration.ofMinutes(3)), "REJECTED"));
        TrackState afterReject = reconcile(rejected);
        assertThat(afterReject.conflicts()).isEmpty();
        assertThat(afterReject.status()).isEqualTo(Stage.APPLIED);
        assertThat(afterReject.submission().verificationLevel()).isEqualTo("BROWSER_OBSERVED");
    }

    @Test
    void matchingMissingUntrustedAndNonConfirmationReferencesNeverConflict() {
        S matching = browserObserved("APP-50599");
        matching.receipts.add(ack(Verification.VERIFIED, "app-50599", SUBMITTED_AT.plus(Duration.ofMinutes(3))));
        assertThat(reconcile(matching).acknowledgement().state()).isEqualTo(AcknowledgementState.VERIFIED);

        S missing = browserObserved(null);
        missing.receipts.add(ack(Verification.VERIFIED, "APP-77777", SUBMITTED_AT.plus(Duration.ofMinutes(3))));
        assertThat(reconcile(missing).conflicts()).isEmpty();

        S untrusted = browserObserved("APP-50599");
        untrusted.submission = new SubmissionFacts(PLAN, UUID.randomUUID(), null, "UNIVERSAL", "APP-50599", false,
                SUBMITTED_AT, false, false, "UNREADABLE", List.of(), "EVIDENCE_INTEGRITY_FAILED");
        untrusted.receipts.add(ack(Verification.VERIFIED, "APP-77777", SUBMITTED_AT.plus(Duration.ofMinutes(3))));
        assertThat(reconcile(untrusted).conflicts()).isEmpty();

        S requisition = browserObserved("APP-50599");
        requisition.disqualified = Set.of("R-12345");
        requisition.receipts.add(ack(Verification.VERIFIED, "R-12345", SUBMITTED_AT.plus(Duration.ofMinutes(3))));
        assertThat(reconcile(requisition).conflicts()).isEmpty();

        // An unverified acknowledgement is not independently trusted: a different reference is not compared.
        S unverified = browserObserved("APP-50599");
        unverified.receipts.add(ack(Verification.UNVERIFIED, "APP-77777", SUBMITTED_AT.plus(Duration.ofMinutes(3))));
        assertThat(reconcile(unverified).conflicts()).isEmpty();
    }

    @Test
    void aReferenceRecordedForADifferentApplicationIsAConflict() {
        S s = new S();
        s.foreign = Set.of("APP-12121");
        s.receipts.add(ack(Verification.VERIFIED, "APP-12121", NOW.minus(Duration.ofDays(1))));
        TrackState state = reconcile(s);
        assertThat(state.status()).isEqualTo(Stage.NEEDS_REVIEW);
        assertThat(state.conflicts()).extracting(TrackModels.Conflict::code).containsExactly("SUBMISSION_REFERENCE_CONFLICT");
        assertThat(state.conflicts().get(0).message()).contains("different one of your applications");
    }

    // ── Gate 4: timing ───────────────────────────────────────────────────

    @Test
    void anAcknowledgementBeforeTheSubmitAttemptIsATimingConflict() {
        S s = browserObserved("APP-50599");
        s.receipts.add(ack(Verification.VERIFIED, "APP-50599", FENCE_AT.minus(Duration.ofHours(2))));
        TrackState state = reconcile(s);
        assertThat(state.status()).isEqualTo(Stage.NEEDS_REVIEW);
        assertThat(state.conflicts()).extracting(TrackModels.Conflict::code).containsExactly("RECEIPT_TEMPORAL_CONFLICT");
        assertThat(state.acknowledgement().state()).isEqualTo(AcknowledgementState.NONE);
        assertThat(state.attention()).extracting(TrackModels.Attention::code).contains("RESOLVE_RECEIPT_TIMING");

        S withinSkew = browserObserved("APP-50599");
        withinSkew.receipts.add(ack(Verification.VERIFIED, "APP-50599", FENCE_AT.minus(Duration.ofMinutes(3))));
        assertThat(reconcile(withinSkew).acknowledgement().state()).isEqualTo(AcknowledgementState.VERIFIED);
    }

    @Test
    void anImpossibleFutureReceiptIsNotUsed() {
        S s = browserObserved("APP-50599");
        s.receipts.add(ack(Verification.VERIFIED, "APP-50599", NOW.plus(Duration.ofHours(2))));
        TrackState state = reconcile(s);
        assertThat(state.status()).isEqualTo(Stage.APPLIED);
        assertThat(state.acknowledgement().state()).isEqualTo(AcknowledgementState.NONE);
        assertThat(state.attention()).extracting(TrackModels.Attention::code).contains("RECEIPT_TIMING_IMPOSSIBLE");
    }

    @Test
    void aHistoricalVerifiedReceiptWithoutAProviderTimeIsNotTreatedAsVerified() {
        S s = new S();
        s.receipts.add(ack(Verification.VERIFIED, null, null));
        TrackState state = reconcile(s);
        assertThat(state.status()).isEqualTo(Stage.ACKNOWLEDGED);
        assertThat(state.basis()).isEqualTo(Basis.UNVERIFIED);
        assertThat(state.acknowledgement().state()).isEqualTo(AcknowledgementState.UNVERIFIED);
        assertThat(state.unknowns()).anyMatch(u -> u.contains("no time recorded by the mail provider"));
    }

    // ── never overstating certainty ───────────────────────────────────────

    @Test
    void candidateReportedStatusNeverBecomesVerified() {
        S s = new S();
        s.reports.add(new CandidateReport(UUID.randomUUID(), ReportKind.SUBMITTED, LocalDate.parse("2026-11-05"), NOW));
        s.reports.add(new CandidateReport(UUID.randomUUID(), ReportKind.ACKNOWLEDGED, LocalDate.parse("2026-11-06"), NOW));
        s.reports.add(new CandidateReport(UUID.randomUUID(), ReportKind.OFFER, LocalDate.parse("2026-11-20"), NOW));
        TrackState state = reconcile(s);
        assertThat(state.status()).isEqualTo(Stage.OFFER);
        assertThat(state.basis()).isEqualTo(Basis.CANDIDATE_REPORTED);
        assertThat(state.submission().state()).isEqualTo(SubmissionState.UNVERIFIED);
        assertThat(state.submission().verificationLevel()).isEqualTo("CANDIDATE_REPORTED");
        assertThat(state.acknowledgement().state()).isEqualTo(AcknowledgementState.UNVERIFIED);
    }

    @Test
    void sandboxVerificationStaysSandboxTaggedAndAnEmployerAcknowledgementSupersedesIt() {
        S s = new S();
        s.plan = new PlanState("SUBMITTED", true, null, true, false);
        TrackState sandbox = reconcile(s);
        assertThat(sandbox.label()).isEqualTo("Applied — verified (sandbox)");
        assertThat(sandbox.submission().sandbox()).isTrue();
        assertThat(sandbox.submission().label()).contains("sandbox");
        assertThat(sandbox.reason()).contains("[SANDBOX]");

        s.receipts.add(ack(Verification.VERIFIED, null, NOW.minus(Duration.ofDays(1))));
        TrackState acknowledged = reconcile(s);
        assertThat(acknowledged.status()).isEqualTo(Stage.ACKNOWLEDGED);
        assertThat(acknowledged.label()).doesNotContain("sandbox");
        assertThat(acknowledged.submission().verificationLevel()).isEqualTo("EMPLOYER_EMAIL_VERIFIED");
    }

    @Test
    void missingDocumentUploadProofIsStatedAsUnknownNotAsAnUpload() {
        S s = browserObserved("APP-50599");
        s.submission = new SubmissionFacts(PLAN, UUID.randomUUID(), "a".repeat(64), "UNIVERSAL", "APP-50599", true,
                SUBMITTED_AT, true, false, "V2", List.of("cv"), "BROWSER_OBSERVED_V2");
        TrackState state = reconcile(s);
        assertThat(state.unknowns()).anyMatch(u -> u.contains("could not confirm that your CV was included"));
        assertThat(state.needsReview()).isFalse();
    }

    @Test
    void aSubmittedPlanWhoseEvidenceFailedIntegrityStillAsksForAttention() {
        S s = new S();
        s.plan = new PlanState("SUBMITTED", true, null, false, false);
        TrackState state = reconcile(s);
        assertThat(state.submission().verificationLevel()).isEqualTo("ATTEMPT_RECORDED");
        assertThat(state.attention()).extracting(TrackModels.Attention::code).contains("RECEIPT_UNVERIFIED");
    }
}
