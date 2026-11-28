package com.personal.jobagent.track;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.track.TrackModels.CandidateReport;
import com.personal.jobagent.track.TrackModels.MessageRecord;
import com.personal.jobagent.track.TrackModels.Preparation;
import com.personal.jobagent.track.TrackModels.TrackSnapshot;
import com.personal.jobagent.track.TrackModels.TrackState;
import com.personal.jobagent.track.TrackRepository.TimelineRow;
import com.personal.jobagent.track.TrackVocabulary.Basis;
import com.personal.jobagent.track.TrackVocabulary.EmailCategory;
import com.personal.jobagent.track.TrackVocabulary.EventClassification;
import com.personal.jobagent.track.TrackVocabulary.LinkStatus;
import com.personal.jobagent.track.TrackVocabulary.ReportKind;
import com.personal.jobagent.track.TrackVocabulary.Stage;
import com.personal.jobagent.track.TrackVocabulary.Verification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Agent E: Recruiter Lifecycle, Intent Classification & Timeline Tracking")
class RecruiterLifecycleTrackTest {

    private final TrackEmailClassifier classifier = new TrackEmailClassifier();
    private final TrackTimelineMapper timelineMapper = new TrackTimelineMapper(new ObjectMapper());
    private final ApplicationReconciler reconciler = new ApplicationReconciler();

    private static final UUID APP_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final Instant CREATED_AT = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant BASE_TIME = Instant.parse("2026-10-05T14:30:00Z");

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Recruiter Message Intent Classification
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Recruiter Message Intent Classification")
    class MessageIntentClassificationTests {

        @Test
        @DisplayName("Identifies interview scheduling links (Calendly, ChiliPiper, self-schedule)")
        void testInterviewSchedulingLinks() {
            var c1 = classifier.classify("Invitation to chat",
                    "We'd love to discuss the role with you. Please select a time slot that works best for you on my calendar: https://calendly.com/talent/intro");
            assertThat(c1.category()).isEqualTo(EmailCategory.INTERVIEW_INVITATION);
            assertThat(c1.confidence()).isGreaterThanOrEqualTo(0.6);

            var c2 = classifier.classify("Schedule our call",
                    "Please use this link to schedule our conversation: https://chilipiper.com/router/meet");
            assertThat(c2.category()).isEqualTo(EmailCategory.INTERVIEW_INVITATION);

            var c3 = classifier.classify("Recruiter Screen",
                    "Thank you for your interest in our Engineering team. Please self-schedule your interview using the portal below.");
            assertThat(c3.category()).isEqualTo(EmailCategory.INTERVIEW_INVITATION);
            assertThat(c3.confidence()).isEqualTo(0.9);
        }

        @Test
        @DisplayName("Identifies interview stages (screening, panel, technical, confirmed)")
        void testInterviewStagesAndConfirmation() {
            var c1 = classifier.classify("Next steps in process",
                    "We were impressed by your background and want to invite you to a phone screen with our engineering manager.");
            assertThat(c1.category()).isEqualTo(EmailCategory.INTERVIEW_INVITATION);

            var c2 = classifier.classify("Interview Confirmed",
                    "Your technical interview has been confirmed for Thursday at 10:00 AM UTC.");
            assertThat(c2.category()).isEqualTo(EmailCategory.INTERVIEW_INVITATION);

            var c3 = classifier.classify("Great progress",
                    "We would love to advance you forward to the next round with our leadership team.");
            assertThat(c3.category()).isEqualTo(EmailCategory.INTERVIEW_INVITATION);
        }

        @Test
        @DisplayName("Identifies assessment invitations (take-home projects, coding tasks, platforms)")
        void testAssessmentInvitations() {
            var c1 = classifier.classify("Technical evaluation",
                    "As the next step, please complete the take-home project within 5 business days.");
            assertThat(c1.category()).isEqualTo(EmailCategory.ASSESSMENT_INVITATION);
            assertThat(c1.confidence()).isEqualTo(0.9);

            var c2 = classifier.classify("Coding Challenge Invitation",
                    "You are invited to complete our online coding challenge on HackerEarth. Follow the link to your assessment.");
            assertThat(c2.category()).isEqualTo(EmailCategory.ASSESSMENT_INVITATION);

            var c3 = classifier.classify("Skills Evaluation",
                    "Please take our programming exercise via Karat to continue in the process.");
            assertThat(c3.category()).isEqualTo(EmailCategory.ASSESSMENT_INVITATION);
        }

        @Test
        @DisplayName("Hardens rejection recognition across diverse phrasing and polite closures")
        void testHardenedRejections() {
            var c1 = classifier.classify("Application Update",
                    "After careful review of your experience, we are unable to move forward with your application.");
            assertThat(c1.category()).isEqualTo(EmailCategory.REJECTION);

            var c2 = classifier.classify("Update regarding your candidacy",
                    "At this time we are pursuing other candidates whose skills are a closer match for the role.");
            assertThat(c2.category()).isEqualTo(EmailCategory.REJECTION);

            var c3 = classifier.classify("Role Update",
                    "We appreciate your application, but have concluded this is not the right fit at this time.");
            assertThat(c3.category()).isEqualTo(EmailCategory.REJECTION);

            var c4 = classifier.classify("Thank you for applying",
                    "Thank you for interviewing with us. We wish you the best in your job search and future endeavors.");
            assertThat(c4.category()).isEqualTo(EmailCategory.REJECTION);

            var c5 = classifier.classify("Status Update",
                    "We regret to inform you that the opening has been cancelled.");
            assertThat(c5.category()).isEqualTo(EmailCategory.REJECTION);
        }

        @Test
        @DisplayName("Preserves conditionality and negation suppression")
        void testConditionalityAndNegation() {
            // Conditional interview inside acknowledgement is not an interview invitation
            var c1 = classifier.classify("Application received",
                    "We received your application. If selected, we will invite you to an interview.");
            assertThat(c1.category()).isEqualTo(EmailCategory.APPLICATION_ACKNOWLEDGEMENT);

            // Conditional rejection phrase is not a rejection
            var c2 = classifier.classify("What to expect",
                    "If you are not selected for this role, we will keep your resume for future opportunities.");
            assertThat(c2.category()).isNotEqualTo(EmailCategory.REJECTION);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Timeline Mapping
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Timeline Mapping for Recruiter Messages")
    class TimelineMappingTests {

        @Test
        @DisplayName("Employer message event maps to EMPLOYER actor and INFERRED classification")
        void testEmployerMessageMapping() {
            UUID emailId = UUID.randomUUID();
            TimelineRow row = new TimelineRow(
                    UUID.randomUUID(),
                    "EVENT",
                    TrackEvidenceRecorder.MESSAGE_EVENT,
                    "{\"email_id\":\"" + emailId + "\"}",
                    "SYSTEM",
                    BASE_TIME,
                    BASE_TIME,
                    "EXACT",
                    "INFERRED",
                    "UNVERIFIED",
                    "Employer message received — Robin thinks it is: interview invitation (not confirmed)",
                    null,
                    null,
                    emailId,
                    BASE_TIME,
                    null
            );

            Map<String, Object> mapped = timelineMapper.map(row);
            assertThat(mapped.get("actor")).isEqualTo("EMPLOYER");
            assertThat(mapped.get("classification")).isEqualTo("INFERRED");
            assertThat(mapped.get("verification")).isEqualTo("UNVERIFIED");
            assertThat(mapped.get("label")).isEqualTo("Inferred by Robin — not confirmed");
            assertThat((String) mapped.get("summary")).contains("interview invitation");
            assertThat(mapped.get("emailId")).isEqualTo(emailId.toString());
            assertThat(mapped.get("occurredAt")).isEqualTo(BASE_TIME.toString());
        }

        @Test
        @DisplayName("Candidate message review event maps to YOU actor and CANDIDATE_REPORTED classification")
        void testCandidateMessageReviewMapping() {
            UUID emailId = UUID.randomUUID();
            TimelineRow row = new TimelineRow(
                    UUID.randomUUID(),
                    "EVENT",
                    "MESSAGE_REVIEWED",
                    "{\"decision\":\"CONFIRMED\",\"category\":\"INTERVIEW_INVITATION\"}",
                    "alice@example.com",
                    BASE_TIME,
                    BASE_TIME,
                    "EXACT",
                    "CANDIDATE_REPORTED",
                    "NOT_APPLICABLE",
                    "You confirmed this employer message is about this application: interview invitation",
                    null,
                    null,
                    emailId,
                    BASE_TIME,
                    null
            );

            Map<String, Object> mapped = timelineMapper.map(row);
            assertThat(mapped.get("actor")).isEqualTo("YOU");
            assertThat(mapped.get("classification")).isEqualTo("CANDIDATE_REPORTED");
            assertThat(mapped.get("label")).isEqualTo("Reported by you");
            assertThat((String) mapped.get("summary")).contains("You confirmed this employer message");
            assertThat(mapped.toString()).doesNotContain("alice@example.com"); // no leak of account email
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. Suggested Stage Transitions & Candidate Confirmation
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Suggested Stage Transitions & Candidate Confirmation Lifecycle")
    class StageTransitionLifecycleTests {

        private TrackSnapshot createSnapshot(List<MessageRecord> messages) {
            List<CandidateReport> reports = List.of(
                    new CandidateReport(UUID.randomUUID(), ReportKind.SUBMITTED, LocalDate.parse("2026-10-02"), CREATED_AT)
            );
            return new TrackSnapshot(
                    APP_ID,
                    "READY_TO_APPLY",
                    CREATED_AT,
                    new Preparation(true, true, false),
                    null,
                    List.of(),
                    reports,
                    List.of(),
                    messages,
                    false,
                    BASE_TIME
            );
        }

        @Test
        @DisplayName("Inferred interview message suggests INTERVIEW stage awaiting candidate confirmation")
        void testInferredInterviewSuggestsStage() {
            UUID emailId = UUID.randomUUID();
            MessageRecord unconfirmedInterview = new MessageRecord(
                    emailId,
                    LinkStatus.LINKED,
                    true,
                    false,
                    EmailCategory.INTERVIEW_INVITATION,
                    null, // unconfirmed
                    BASE_TIME,
                    true,
                    BASE_TIME
            );

            TrackSnapshot snapshot = createSnapshot(List.of(unconfirmedInterview));
            TrackState state = reconciler.reconcile(snapshot);

            // Reconciled status remains at APPLIED because inference never prematurely advances stage
            assertThat(state.status()).isEqualTo(Stage.APPLIED);
            // Suggestion is provided for candidate review
            assertThat(state.pending()).isNotNull();
            assertThat(state.pending().stage()).isEqualTo(Stage.INTERVIEW);
            assertThat(state.pending().emailId()).isEqualTo(emailId);
            assertThat(state.needsReview()).isTrue();
            assertThat(state.attention()).anyMatch(a -> "CONFIRM_MESSAGE_MEANING".equals(a.code()));
        }

        @Test
        @DisplayName("Candidate confirming interview transitions stage to INTERVIEW with CANDIDATE_CONFIRMED basis")
        void testConfirmedInterviewTransitionsStage() {
            UUID emailId = UUID.randomUUID();
            MessageRecord confirmedInterview = new MessageRecord(
                    emailId,
                    LinkStatus.CONFIRMED,
                    true,
                    false,
                    EmailCategory.INTERVIEW_INVITATION,
                    EmailCategory.INTERVIEW_INVITATION, // confirmed by candidate
                    BASE_TIME,
                    true,
                    BASE_TIME
            );

            TrackSnapshot snapshot = createSnapshot(List.of(confirmedInterview));
            TrackState state = reconciler.reconcile(snapshot);

            // Now stage transitions to INTERVIEW!
            assertThat(state.status()).isEqualTo(Stage.INTERVIEW);
            assertThat(state.basis()).isEqualTo(Basis.CANDIDATE_CONFIRMED);
            assertThat(state.label()).isEqualTo("Interview — confirmed by you");
            assertThat(state.pending()).isNull();
        }

        @Test
        @DisplayName("Candidate confirming assessment transitions stage to ASSESSMENT")
        void testConfirmedAssessmentTransitionsStage() {
            UUID emailId = UUID.randomUUID();
            MessageRecord confirmedAssessment = new MessageRecord(
                    emailId,
                    LinkStatus.CONFIRMED,
                    true,
                    false,
                    EmailCategory.ASSESSMENT_INVITATION,
                    EmailCategory.ASSESSMENT_INVITATION,
                    BASE_TIME,
                    true,
                    BASE_TIME
            );

            TrackSnapshot snapshot = createSnapshot(List.of(confirmedAssessment));
            TrackState state = reconciler.reconcile(snapshot);

            assertThat(state.status()).isEqualTo(Stage.ASSESSMENT);
            assertThat(state.basis()).isEqualTo(Basis.CANDIDATE_CONFIRMED);
            assertThat(state.label()).isEqualTo("Assessment requested — confirmed by you");
            assertThat(state.pending()).isNull();
        }

        @Test
        @DisplayName("Candidate confirming rejection transitions stage to REJECTED and ends progress")
        void testConfirmedRejectionTransitionsStage() {
            UUID emailId1 = UUID.randomUUID();
            UUID emailId2 = UUID.randomUUID();
            Instant interviewTime = BASE_TIME;
            Instant rejectionTime = BASE_TIME.plusSeconds(86400);

            MessageRecord confirmedInterview = new MessageRecord(
                    emailId1,
                    LinkStatus.CONFIRMED,
                    true,
                    false,
                    EmailCategory.INTERVIEW_INVITATION,
                    EmailCategory.INTERVIEW_INVITATION,
                    interviewTime,
                    true,
                    interviewTime
            );

            MessageRecord confirmedRejection = new MessageRecord(
                    emailId2,
                    LinkStatus.CONFIRMED,
                    true,
                    false,
                    EmailCategory.REJECTION,
                    EmailCategory.REJECTION,
                    rejectionTime,
                    true,
                    rejectionTime
            );

            TrackSnapshot snapshot = createSnapshot(List.of(confirmedInterview, confirmedRejection));
            TrackState state = reconciler.reconcile(snapshot);

            assertThat(state.status()).isEqualTo(Stage.REJECTED);
            assertThat(state.basis()).isEqualTo(Basis.CANDIDATE_CONFIRMED);
            assertThat(state.label()).isEqualTo("Not progressed — confirmed by you");
        }
    }
}
