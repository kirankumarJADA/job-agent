package com.personal.jobagent.track;

import com.personal.jobagent.track.ReceiptTemporalPolicy.AnchorSource;
import com.personal.jobagent.track.ReceiptTemporalPolicy.Status;
import com.personal.jobagent.track.ReceiptTemporalPolicy.SubmissionAnchor;
import com.personal.jobagent.track.ReceiptTemporalPolicy.TimeSource;
import com.personal.jobagent.track.TrackVocabulary.Channel;
import com.personal.jobagent.track.TrackVocabulary.TimePrecision;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Adversarial cases for the server-side temporal plausibility policy (Stage 3I Phase 2, Gate 4). */
class ReceiptTemporalPolicyTest {

    private static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");
    private static final Instant SUBMIT = Instant.parse("2026-11-20T10:00:00Z");
    private static final SubmissionAnchor FENCE = new SubmissionAnchor(SUBMIT, TimePrecision.EXACT, AnchorSource.PRE_SUBMIT_ATTEMPT);

    private final ReceiptTemporalPolicy policy = new ReceiptTemporalPolicy();

    private ReceiptTemporalPolicy.Assessment provider(Instant at, SubmissionAnchor anchor) {
        return policy.assess(at, TimePrecision.EXACT, TimeSource.PROVIDER_RECEIPT, anchor, NOW);
    }

    @Test
    void anEmailThatPredatesTheSubmitAttemptBeyondTheSkewIsBeforeSubmission() {
        var a = provider(SUBMIT.minus(Duration.ofHours(3)), FENCE);
        assertThat(a.status()).isEqualTo(Status.BEFORE_SUBMISSION);
        assertThat(a.code()).isEqualTo("RECEIPT_PREDATES_SUBMISSION");
        assertThat(a.blocksVerification()).isTrue();
        assertThat(a.contradictsSubmission()).isTrue();
        assertThat(a.anchor().source()).isEqualTo(AnchorSource.PRE_SUBMIT_ATTEMPT);
    }

    @Test
    void clockSkewWithinTheAllowanceIsPlausibleAndJustBeyondItIsNot() {
        assertThat(provider(SUBMIT.minus(Duration.ofMinutes(4)), FENCE).status()).isEqualTo(Status.PLAUSIBLE);
        assertThat(provider(SUBMIT.minus(ReceiptTemporalPolicy.CLOCK_SKEW), FENCE).status()).isEqualTo(Status.PLAUSIBLE);
        assertThat(provider(SUBMIT.minus(ReceiptTemporalPolicy.CLOCK_SKEW).minusSeconds(1), FENCE).status())
                .isEqualTo(Status.BEFORE_SUBMISSION);
    }

    @Test
    void delayedDeliveryIsPlausible() {
        var a = provider(SUBMIT.plus(Duration.ofDays(5)), FENCE);
        assertThat(a.status()).isEqualTo(Status.PLAUSIBLE);
        assertThat(a.blocksVerification()).isFalse();
    }

    @Test
    void anImpossibleFutureProviderTimeIsFutureDatedButSmallSkewIsTolerated() {
        assertThat(provider(NOW.plus(Duration.ofMinutes(6)), FENCE).status()).isEqualTo(Status.FUTURE_DATED);
        assertThat(provider(NOW.plus(Duration.ofMinutes(4)), FENCE).status()).isEqualTo(Status.PLAUSIBLE);
        // A candidate-typed time may be off by a time zone: a day is tolerated, more is not.
        assertThat(policy.assess(NOW.plus(Duration.ofHours(20)), TimePrecision.EXACT, TimeSource.CANDIDATE_SUPPLIED, FENCE, NOW)
                .status()).isEqualTo(Status.PLAUSIBLE);
        assertThat(policy.assess(NOW.plus(Duration.ofHours(25)), TimePrecision.EXACT, TimeSource.CANDIDATE_SUPPLIED, FENCE, NOW)
                .status()).isEqualTo(Status.FUTURE_DATED);
    }

    @Test
    void aMissingTimeIsUnknownAndNeverFabricated() {
        for (var a : new ReceiptTemporalPolicy.Assessment[]{
                policy.assess(null, TimePrecision.EXACT, TimeSource.PROVIDER_RECEIPT, FENCE, NOW),
                policy.assess(SUBMIT, TimePrecision.UNKNOWN, TimeSource.PROVIDER_RECEIPT, FENCE, NOW),
                policy.assess(SUBMIT, TimePrecision.EXACT, TimeSource.NONE, FENCE, NOW)}) {
            assertThat(a.status()).isEqualTo(Status.EVIDENCE_TIME_UNKNOWN);
            assertThat(a.evidenceTime()).isNull();
            assertThat(a.blocksVerification()).isTrue();
            assertThat(a.contradictsSubmission()).isFalse();
        }
    }

    @Test
    void withoutASubmissionAnchorTheCheckCannotFailButSaysSo() {
        var a = provider(SUBMIT, SubmissionAnchor.none());
        assertThat(a.status()).isEqualTo(Status.NO_SUBMISSION_ANCHOR);
        assertThat(a.blocksVerification()).isFalse();
        assertThat(a.anchor().present()).isFalse();
    }

    @Test
    void dateOnlyComparisonsUseWholeDaysWithADayOfTolerance() {
        SubmissionAnchor reported = new SubmissionAnchor(Instant.parse("2026-11-20T00:00:00Z"), TimePrecision.DATE,
                AnchorSource.CANDIDATE_REPORTED_SUBMISSION);
        // The day before the reported day is within tolerance (local-time-zone dates); two days before is not.
        assertThat(provider(Instant.parse("2026-11-19T22:00:00Z"), reported).status()).isEqualTo(Status.PLAUSIBLE);
        assertThat(provider(Instant.parse("2026-11-18T09:00:00Z"), reported).status()).isEqualTo(Status.BEFORE_SUBMISSION);
    }

    @Test
    void theEarliestAnchorWinsAndTiesPreferTheServerRecordedOne() {
        SubmissionAnchor approval = new SubmissionAnchor(SUBMIT.minus(Duration.ofHours(1)), TimePrecision.EXACT, AnchorSource.SUBMIT_APPROVAL);
        assertThat(FENCE.earliest(approval)).isSameAs(approval);
        assertThat(approval.earliest(FENCE)).isSameAs(approval);
        assertThat(FENCE.earliest(SubmissionAnchor.none())).isSameAs(FENCE);
        SubmissionAnchor sameTimeReport = new SubmissionAnchor(SUBMIT, TimePrecision.DATE, AnchorSource.CANDIDATE_REPORTED_SUBMISSION);
        assertThat(sameTimeReport.earliest(FENCE)).isSameAs(FENCE);
    }

    @Test
    void timeSourceComesFromTheChannelNotTheMessage() {
        assertThat(ReceiptTemporalPolicy.sourceFor(Channel.MAILBOX_CONNECTOR, SUBMIT, TimePrecision.EXACT))
                .isEqualTo(TimeSource.PROVIDER_RECEIPT);
        assertThat(ReceiptTemporalPolicy.sourceFor(Channel.CANDIDATE_FORWARDED, SUBMIT, TimePrecision.EXACT))
                .isEqualTo(TimeSource.CANDIDATE_SUPPLIED);
        assertThat(ReceiptTemporalPolicy.sourceFor(Channel.MAILBOX_CONNECTOR, null, TimePrecision.UNKNOWN))
                .isEqualTo(TimeSource.NONE);
    }
}
