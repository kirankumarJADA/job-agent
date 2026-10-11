package com.personal.jobagent.track;

import java.util.Locale;
import java.util.Map;

/**
 * The closed vocabularies TRACK uses, in one place, with the candidate-facing
 * labels. Every value here is also enforced by a CHECK constraint in V038, so a
 * typo fails loudly instead of persisting an unknown state.
 *
 * <p>Labels never name a job board, ATS or aggregator: the evidence channel is
 * described ("employer acknowledgement email"), the discovery source is not.
 */
public final class TrackVocabulary {

    private TrackVocabulary() {
    }

    /** Who/what is the source of a timeline event. */
    public enum EventClassification {
        /** Robin's own record of something Robin (or the candidate inside Robin) did. */
        INTERNAL,
        /** Employer-side evidence: an acknowledgement or other employer message. */
        EXTERNAL,
        /** Robin's interpretation of evidence, awaiting the candidate's confirmation. */
        INFERRED,
        /** A statement, confirmation or correction made by the candidate. */
        CANDIDATE_REPORTED
    }

    /** Verification outcome of evidence (receipts) and of external timeline events. */
    public enum Verification {
        NOT_APPLICABLE, VERIFIED, UNVERIFIED, REJECTED, UNKNOWN
    }

    public enum TimePrecision {
        EXACT, DATE, UNKNOWN
    }

    /** How evidence reached Robin. Always assigned by the server from the ingestion path. */
    public enum Channel {
        /** Retrieved by an authorised mailbox connector that reports sender authentication. */
        MAILBOX_CONNECTOR,
        /** Pasted/forwarded by the candidate through the authenticated API. */
        CANDIDATE_FORWARDED,
        /** Typed by the candidate (a statement, not a message). */
        CANDIDATE_ENTERED
    }

    public enum EvidenceType {
        EMPLOYER_ACKNOWLEDGEMENT_EMAIL, CANDIDATE_STATEMENT
    }

    /** Deterministic email categories (see {@link TrackEmailClassifier}). */
    public enum EmailCategory {
        APPLICATION_ACKNOWLEDGEMENT,
        RECRUITER_FOLLOW_UP,
        INTERVIEW_INVITATION,
        ASSESSMENT_INVITATION,
        INFORMATION_REQUEST,
        REJECTION,
        OFFER,
        VERIFICATION_CODE,
        UNRELATED_OR_AMBIGUOUS;

        public static EmailCategory parse(String value) {
            if (value == null) return UNRELATED_OR_AMBIGUOUS;
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return UNRELATED_OR_AMBIGUOUS;
            }
        }
    }

    /** State of the association between one message and the candidate's applications. */
    public enum LinkStatus {
        /** Tied to exactly one application by a strong identifier (job URL, platform job id, requisition). */
        LINKED,
        /** The candidate confirmed or assigned the application. */
        CONFIRMED,
        /** Weak or ambiguous evidence: the candidate must choose. */
        PENDING_REVIEW,
        /** No application could be proposed. */
        UNMATCHED,
        /** The candidate said the message is not about any of their applications. */
        REJECTED,
        /** The candidate's choice contradicts the message's own strong identifiers. */
        CONFLICT,
        /** Not tracking evidence (a sign-in verification code). */
        NOT_APPLICABLE
    }

    public enum MatchStrength {
        STRONG, WEAK, NONE, CANDIDATE, LEGACY
    }

    /** Reconciled, candidate-facing application stage. */
    public enum Stage {
        PREPARING, READY_TO_APPLY, APPLYING, APPLIED, ACKNOWLEDGED, IN_CONTACT, ASSESSMENT, INTERVIEW,
        OFFER, REJECTED, WITHDRAWN, STOPPED, NEEDS_REVIEW, UNKNOWN
    }

    /** What the current stage rests on, strongest first. */
    public enum Basis {
        VERIFIED(4), CANDIDATE_CONFIRMED(3), CANDIDATE_REPORTED(3), UNVERIFIED(2), INTERNAL(1), INFERRED(0);

        public final int strength;

        Basis(int strength) {
            this.strength = strength;
        }
    }

    /** What a candidate can report about an application Robin did not (and cannot) submit. */
    public enum ReportKind {
        SUBMITTED, ACKNOWLEDGED, RECRUITER_CONTACT, ASSESSMENT, INTERVIEW, OFFER, REJECTED, WITHDRAWN
    }

    /** Submission confirmation state shown beside the stage. */
    public enum SubmissionState {
        CONFIRMED, UNVERIFIED, UNKNOWN, CONFLICTING
    }

    public enum AcknowledgementState {
        VERIFIED, UNVERIFIED, NONE
    }

    // ── labels ───────────────────────────────────────────────────────────

    private static final Map<Stage, String> STAGE_LABELS = Map.ofEntries(
            Map.entry(Stage.PREPARING, "Preparing documents"),
            Map.entry(Stage.READY_TO_APPLY, "Prepared — not submitted"),
            Map.entry(Stage.APPLYING, "Application started"),
            Map.entry(Stage.APPLIED, "Applied"),
            Map.entry(Stage.ACKNOWLEDGED, "Acknowledged by the employer"),
            Map.entry(Stage.IN_CONTACT, "Employer in contact"),
            Map.entry(Stage.ASSESSMENT, "Assessment requested"),
            Map.entry(Stage.INTERVIEW, "Interview"),
            Map.entry(Stage.OFFER, "Offer"),
            Map.entry(Stage.REJECTED, "Not progressed"),
            Map.entry(Stage.WITHDRAWN, "Withdrawn"),
            Map.entry(Stage.STOPPED, "Stopped"),
            Map.entry(Stage.NEEDS_REVIEW, "Needs your review"),
            Map.entry(Stage.UNKNOWN, "Status unknown"));

    private static final Map<Basis, String> BASIS_LABELS = Map.of(
            Basis.VERIFIED, "verified employer evidence",
            Basis.CANDIDATE_CONFIRMED, "confirmed by you",
            Basis.CANDIDATE_REPORTED, "reported by you",
            Basis.UNVERIFIED, "unverified employer evidence",
            Basis.INTERNAL, "Robin record",
            Basis.INFERRED, "inferred by Robin");

    private static final Map<EmailCategory, String> CATEGORY_LABELS = Map.of(
            EmailCategory.APPLICATION_ACKNOWLEDGEMENT, "Application acknowledgement",
            EmailCategory.RECRUITER_FOLLOW_UP, "Recruiter follow-up",
            EmailCategory.INTERVIEW_INVITATION, "Interview invitation",
            EmailCategory.ASSESSMENT_INVITATION, "Assessment invitation",
            EmailCategory.INFORMATION_REQUEST, "Request for more information",
            EmailCategory.REJECTION, "Rejection",
            EmailCategory.OFFER, "Offer-related message",
            EmailCategory.VERIFICATION_CODE, "Sign-in verification code",
            EmailCategory.UNRELATED_OR_AMBIGUOUS, "Unrelated or unclear message");

    public static String stageLabel(Stage stage) {
        return STAGE_LABELS.getOrDefault(stage, "Status unknown");
    }

    public static String basisLabel(Basis basis) {
        return basis == null ? null : BASIS_LABELS.get(basis);
    }

    public static String categoryLabel(EmailCategory category) {
        return CATEGORY_LABELS.getOrDefault(category, "Unrelated or unclear message");
    }

    /** Candidate-facing label for a timeline event's evidence level. */
    public static String eventLabel(EventClassification classification, Verification verification) {
        if (classification == null) return "Robin activity";
        return switch (classification) {
            case INTERNAL -> "Robin activity";
            case CANDIDATE_REPORTED -> "Reported by you";
            case INFERRED -> "Inferred by Robin — not confirmed";
            case EXTERNAL -> switch (verification == null ? Verification.UNKNOWN : verification) {
                case VERIFIED -> "Verified employer evidence";
                case UNVERIFIED -> "Employer evidence — unverified";
                case REJECTED -> "Evidence rejected";
                case UNKNOWN, NOT_APPLICABLE -> "Employer evidence — insufficient to verify";
            };
        };
    }

    public static String verificationLabel(Verification verification) {
        if (verification == null) return "Unknown";
        return switch (verification) {
            case VERIFIED -> "Verified";
            case UNVERIFIED -> "Not verified";
            case REJECTED -> "Rejected";
            case UNKNOWN -> "Insufficient evidence";
            case NOT_APPLICABLE -> "Not applicable";
        };
    }

    /** Human words for the pre-8.3 application statuses (Robin's internal workflow status). */
    public static String recordedStatusLabel(String status) {
        if (status == null) return "Unknown";
        return switch (status) {
            case "READY_TO_APPLY" -> "Ready to apply";
            case "APPLICATION_STARTED" -> "Application started";
            case "OTP_PENDING" -> "Waiting for a verification code";
            case "APPLICATION_SUBMITTED" -> "Submitted";
            case "CONFIRMATION_RECEIVED" -> "Acknowledged by the employer";
            case "RECRUITER_CONTACT" -> "Recruiter contact";
            case "INTERVIEW" -> "Interview";
            case "ASSESSMENT" -> "Assessment";
            case "REJECTED" -> "Rejected";
            case "OFFER" -> "Offer";
            case "WITHDRAWN" -> "Withdrawn";
            case "FAILED" -> "Failed";
            default -> "Unknown";
        };
    }
}
