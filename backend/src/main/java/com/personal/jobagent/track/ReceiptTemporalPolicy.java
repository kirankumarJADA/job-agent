package com.personal.jobagent.track;

import com.personal.jobagent.track.TrackVocabulary.Channel;
import com.personal.jobagent.track.TrackVocabulary.TimePrecision;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Server-side temporal plausibility of an employer acknowledgement
 * ({@value #VERSION}). Pure and deterministic.
 *
 * <p><b>Which clock is trusted.</b> A receipt's own time is trusted only when
 * the server or the mail provider assigned it: Gmail's {@code internalDate} or
 * the managed receiver's HMAC-signed delivery time ({@link TimeSource#PROVIDER_RECEIPT}).
 * A sender's {@code Date} header never reaches TRACK as a receipt time at all,
 * and a time the candidate typed in is {@link TimeSource#CANDIDATE_SUPPLIED}.
 *
 * <p><b>Which anchor.</b> An acknowledgement acknowledges a submission, so it is
 * checked against the earliest server-recorded moment a submission could have
 * happened — never against the application's or the plan's creation time:
 * <ul>
 *   <li>{@link AnchorSource#PRE_SUBMIT_ATTEMPT}: the {@code pre-submit-attempting}
 *       fence step, inserted by the server ({@code automation_steps.created_at})
 *       immediately before Robin's real submit click;</li>
 *   <li>{@link AnchorSource#SUBMIT_APPROVAL}: for a Robin attempt without a fence
 *       step, the candidate's {@code *_SUBMIT_APPROVED} audit row — a click can
 *       never precede the approval that authorises it;</li>
 *   <li>{@link AnchorSource#CANDIDATE_REPORTED_SUBMISSION}: the day the
 *       candidate said they submitted it themselves (date precision);</li>
 *   <li>none: Robin never attempted it and the candidate gave no date. Then
 *       there is nothing to be early against: application and plan creation
 *       times say when Robin started tracking or preparing, not when the
 *       candidate applied, so they are deliberately not used.</li>
 * </ul>
 * The caller passes the earliest available anchor.
 *
 * <p><b>Outcomes.</b> {@link Status#EVIDENCE_TIME_UNKNOWN} when the receipt has no
 * known time (never verified: a time is not invented); {@link Status#FUTURE_DATED}
 * beyond the skew allowance; {@link Status#BEFORE_SUBMISSION} when it predates
 * the anchor by more than the skew allowance; otherwise {@link Status#PLAUSIBLE},
 * or {@link Status#NO_SUBMISSION_ANCHOR} when no anchor exists. Delayed delivery
 * is plausible: there is no upper bound relative to the submission.
 */
public final class ReceiptTemporalPolicy {

    public static final String VERSION = "track-temporal-policy-v1";

    /** Allowance between the server clock and a provider clock, for exact times. */
    public static final Duration CLOCK_SKEW = Duration.ofMinutes(5);

    /**
     * Allowance when either side only has a date (candidate-entered dates are
     * local calendar days, which can differ from the UTC day by up to a day).
     */
    public static final Duration DATE_TOLERANCE = Duration.ofDays(1);

    public enum TimeSource { PROVIDER_RECEIPT, CANDIDATE_SUPPLIED, NONE }

    public enum AnchorSource { PRE_SUBMIT_ATTEMPT, SUBMIT_APPROVAL, CANDIDATE_REPORTED_SUBMISSION, NONE }

    public enum Status { PLAUSIBLE, BEFORE_SUBMISSION, FUTURE_DATED, EVIDENCE_TIME_UNKNOWN, NO_SUBMISSION_ANCHOR }

    /** The earliest moment a submission could have happened, with where that time came from. */
    public record SubmissionAnchor(Instant time, TimePrecision precision, AnchorSource source) {
        public SubmissionAnchor {
            if (source == null || source == AnchorSource.NONE || time == null) {
                time = null;
                precision = null;
                source = AnchorSource.NONE;
            } else if (precision == null || precision == TimePrecision.UNKNOWN) {
                precision = TimePrecision.EXACT;
            }
        }

        public static SubmissionAnchor none() {
            return new SubmissionAnchor(null, null, AnchorSource.NONE);
        }

        public boolean present() {
            return source != AnchorSource.NONE;
        }

        /** The earlier of two anchors; on a tie, the server-recorded one. */
        public SubmissionAnchor earliest(SubmissionAnchor other) {
            if (other == null || !other.present()) return this;
            if (!present()) return other;
            int cmp = time.compareTo(other.time);
            if (cmp != 0) return cmp < 0 ? this : other;
            return source.ordinal() <= other.source.ordinal() ? this : other;
        }
    }

    public record Assessment(Status status, String code, String message, TimeSource evidenceSource,
                             Instant evidenceTime, TimePrecision evidencePrecision, SubmissionAnchor anchor,
                             Duration skew) {

        /** True when this outcome must stop an acknowledgement from being verified. */
        public boolean blocksVerification() {
            return status == Status.BEFORE_SUBMISSION || status == Status.FUTURE_DATED
                    || status == Status.EVIDENCE_TIME_UNKNOWN;
        }

        /** True when the timing contradicts the submission and needs the candidate's review. */
        public boolean contradictsSubmission() {
            return status == Status.BEFORE_SUBMISSION;
        }
    }

    /** Where a receipt's time came from, from the ingestion channel (server-assigned). */
    public static TimeSource sourceFor(Channel channel, Instant evidenceTime, TimePrecision precision) {
        if (evidenceTime == null || precision == null || precision == TimePrecision.UNKNOWN) return TimeSource.NONE;
        return channel == Channel.MAILBOX_CONNECTOR ? TimeSource.PROVIDER_RECEIPT : TimeSource.CANDIDATE_SUPPLIED;
    }

    public Assessment assess(Instant evidenceTime, TimePrecision precision, TimeSource source,
                             SubmissionAnchor anchor, Instant now) {
        Objects.requireNonNull(now, "now");
        SubmissionAnchor a = anchor == null ? SubmissionAnchor.none() : anchor;
        TimeSource src = source == null ? TimeSource.NONE : source;
        TimePrecision p = precision == null ? TimePrecision.UNKNOWN : precision;
        if (evidenceTime == null || p == TimePrecision.UNKNOWN || src == TimeSource.NONE) {
            return new Assessment(Status.EVIDENCE_TIME_UNKNOWN, "EVIDENCE_TIME_UNKNOWN",
                    "The message has no time assigned by the mail provider, so when it arrived cannot be established.",
                    TimeSource.NONE, null, TimePrecision.UNKNOWN, a, CLOCK_SKEW);
        }
        boolean exactProvider = p == TimePrecision.EXACT && src == TimeSource.PROVIDER_RECEIPT;
        Duration futureAllowance = exactProvider ? CLOCK_SKEW : DATE_TOLERANCE;
        if (evidenceTime.isAfter(now.plus(futureAllowance))) {
            return new Assessment(Status.FUTURE_DATED, "FUTURE_DATED",
                    "The message is dated after the current time beyond the clock-skew allowance, which is impossible.",
                    src, evidenceTime, p, a, futureAllowance);
        }
        if (!a.present()) {
            return new Assessment(Status.NO_SUBMISSION_ANCHOR, "NO_SUBMISSION_ANCHOR",
                    "There is no recorded submission time to compare the message with.",
                    src, evidenceTime, p, a, CLOCK_SKEW);
        }
        boolean exact = p == TimePrecision.EXACT && a.precision() == TimePrecision.EXACT;
        boolean before;
        Duration allowance;
        if (exact) {
            allowance = CLOCK_SKEW;
            before = evidenceTime.isBefore(a.time().minus(CLOCK_SKEW));
        } else {
            allowance = DATE_TOLERANCE;
            LocalDate evidenceDay = LocalDate.ofInstant(evidenceTime, ZoneOffset.UTC);
            LocalDate anchorDay = LocalDate.ofInstant(a.time(), ZoneOffset.UTC);
            before = evidenceDay.isBefore(anchorDay.minusDays(DATE_TOLERANCE.toDays()));
        }
        if (before) {
            return new Assessment(Status.BEFORE_SUBMISSION, "RECEIPT_PREDATES_SUBMISSION",
                    "The message arrived before this application was submitted ("
                            + anchorLabel(a.source()) + "), beyond the clock-skew allowance.",
                    src, evidenceTime, p, a, allowance);
        }
        return new Assessment(Status.PLAUSIBLE, "TIMING_PLAUSIBLE",
                "The message arrived after this application was submitted (" + anchorLabel(a.source()) + ").",
                src, evidenceTime, p, a, allowance);
    }

    static String anchorLabel(AnchorSource source) {
        return switch (source) {
            case PRE_SUBMIT_ATTEMPT -> "Robin's recorded submit attempt";
            case SUBMIT_APPROVAL -> "your recorded submission approval";
            case CANDIDATE_REPORTED_SUBMISSION -> "the date you reported submitting it";
            case NONE -> "no recorded submission";
        };
    }
}
