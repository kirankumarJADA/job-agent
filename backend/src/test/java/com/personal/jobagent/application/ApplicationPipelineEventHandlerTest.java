package com.personal.jobagent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.automation.GreenhouseExecutionPlanService;
import com.personal.jobagent.automation.InspectionPlanService;
import com.personal.jobagent.coverletter.CoverLetterService;
import com.personal.jobagent.events.Envelope;
import com.personal.jobagent.jobs.JobRecord;
import com.personal.jobagent.jobs.JobRepository;
import com.personal.jobagent.llm.LlmCompletion;
import com.personal.jobagent.llm.ModelRouter;
import com.personal.jobagent.llm.RoutingTrace;
import com.personal.jobagent.llm.TaskType;
import com.personal.jobagent.notifications.NotificationEvents;
import com.personal.jobagent.notifications.NotificationService;
import com.personal.jobagent.qa.ApplicationAnswerService;
import com.personal.jobagent.resume.ResumeAtsIntelligenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contract for the pipeline's outbox consumer: APPLY recommendations create
 * exactly one application, REVIEW/SKIP create none, and preparation runs the
 * existing services with per-step failure isolation — a failing LLM step is
 * recorded on the timeline without corrupting the application's state.
 */
class ApplicationPipelineEventHandlerTest {

    private static final UUID APPLICATION = UUID.randomUUID();
    private static final UUID PROFILE = UUID.randomUUID();
    private static final UUID JOB = UUID.randomUUID();

    private final ApplicationPipelineService pipeline = mock(ApplicationPipelineService.class);
    private final InspectionPlanService inspectionPlanService = mock(InspectionPlanService.class);
    private final GreenhouseExecutionPlanService greenhousePlanService = mock(GreenhouseExecutionPlanService.class);
    private final ResumeAtsIntelligenceService resumeService = mock(ResumeAtsIntelligenceService.class);
    private final CoverLetterService coverLetterService = mock(CoverLetterService.class);
    private final ApplicationAnswerService answerService = mock(ApplicationAnswerService.class);
    private final JobRepository jobRepository = mock(JobRepository.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private ApplicationPipelineEventHandler handler;

    @BeforeEach
    void setUp() {
        reset(pipeline, inspectionPlanService, greenhousePlanService, resumeService, coverLetterService,
                answerService, jobRepository, notifications, db);
        handler = new ApplicationPipelineEventHandler(pipeline, inspectionPlanService, greenhousePlanService,
                resumeService, coverLetterService, answerService, jobRepository, notifications, db, new ObjectMapper());
        when(db.queryForObject(contains("count(*) from applications"), eq(Integer.class), eq(APPLICATION)))
                .thenReturn(1);
        when(db.update(contains("application_events"), any(UUID.class), eq(APPLICATION), any(String.class)))
                .thenReturn(1);
        when(jobRepository.findById(JOB)).thenReturn(java.util.Optional.of(job()));
    }

    private static JobRecord job() {
        return new JobRecord(JOB, UUID.randomUUID(), "gh-1", null, "Monzo", "Java Engineer",
                "London", null, null, null, null, null,
                new BigDecimal("40000"), new BigDecimal("60000"), "GBP",
                "Java services role", List.of("Java"), "https://jobs.example.org/1",
                "https://jobs.example.org/1", Instant.now(), "DISCOVERED");
    }

    private static Envelope envelope(String type, Map<String, Object> payload) {
        return new Envelope(UUID.randomUUID(), type, 1, Instant.now(),
                "APPLICATION", APPLICATION, UUID.randomUUID(), null, payload);
    }

    private static LlmCompletion completion(String text) {
        return new LlmCompletion(text, new LlmCompletion.TokenUsage(10, 20), 15, LlmCompletion.FinishReason.STOP);
    }

    @Test
    void anApplyMatchCreatesTheApplicationThroughThePipeline() {
        when(pipeline.createApplicationFromMatch(PROFILE, JOB))
                .thenReturn(new ApplicationPipelineService.CreatedApplication(APPLICATION, true, "READY_TO_APPLY"));

        handler.handle(envelope(NotificationEvents.JOB_MATCHED, Map.of(
                "job_id", JOB.toString(), "profile_id", PROFILE.toString(), "recommendation", "APPLY")));

        verify(pipeline).createApplicationFromMatch(PROFILE, JOB);
    }

    @Test
    void reviewAndSkipMatchesNeverCreateAnApplication() {
        handler.handle(envelope(NotificationEvents.JOB_MATCHED, Map.of(
                "job_id", JOB.toString(), "profile_id", PROFILE.toString(), "recommendation", "REVIEW")));
        handler.handle(envelope(NotificationEvents.JOB_MATCHED, Map.of(
                "job_id", JOB.toString(), "profile_id", PROFILE.toString(), "recommendation", "SKIP")));

        verify(pipeline, never()).createApplicationFromMatch(any(), any());
    }

    @Test
    void aMalformedMatchPayloadIsSkippedWithoutThrowing() {
        handler.handle(envelope(NotificationEvents.JOB_MATCHED, Map.of("recommendation", "APPLY")));

        verify(pipeline, never()).createApplicationFromMatch(any(), any());
    }

    @Test
    void preparationRunsTheThreeExistingServicesAndEmitsApplicationPrepared() {
        handler.handle(envelope(NotificationEvents.APPLICATION_CREATED, Map.of(
                "application_id", APPLICATION.toString(), "profile_id", PROFILE.toString(), "job_id", JOB.toString())));

        verify(resumeService).tailor(PROFILE, JOB, APPLICATION);
        verify(coverLetterService).generateCoverLetter(PROFILE, JOB, APPLICATION);
        verify(answerService).draftAnswer(eq(PROFILE), eq(JOB), eq(APPLICATION),
                contains("Why do you want to work at Monzo"));
        // Three OK steps on the timeline (bound payloads captured):
        ArgumentCaptor<String> payloads = ArgumentCaptor.forClass(String.class);
        verify(db, times(3)).update(contains("application_events"), any(UUID.class), eq(APPLICATION),
                eq("PREPARATION"), payloads.capture());
        List<String> steps = payloads.getAllValues().stream()
                .map(v -> v.replaceAll(".*\"step\":\"([A-Z_]+)\".*", "$1"))
                .toList();
        assertThat(steps).containsExactlyInAnyOrder("CV", "COVER_LETTER", "ANSWERS");
        assertThat(payloads.getAllValues()).allSatisfy(v -> assertThat(v).contains("\"status\":\"OK\""));
        // The prepared event is the notification-fanout signal:
        ArgumentCaptor<NotificationService.NotificationCommand> event =
                ArgumentCaptor.forClass(NotificationService.NotificationCommand.class);
        verify(notifications).emit(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo(NotificationEvents.APPLICATION_PREPARED);
    }

    @Test
    void aFailingLlmStepIsRecordedAndDoesNotBlockTheOtherStepsOrChangeState() {
        when(coverLetterService.generateCoverLetter(PROFILE, JOB, APPLICATION))
                .thenThrow(new IllegalStateException("no model credentials"));

        handler.handle(envelope(NotificationEvents.APPLICATION_CREATED, Map.of(
                "application_id", APPLICATION.toString(), "profile_id", PROFILE.toString(), "job_id", JOB.toString())));

        // The other two steps still ran:
        verify(resumeService).tailor(PROFILE, JOB, APPLICATION);
        verify(answerService).draftAnswer(any(), any(), any(), any());
        // Per-step outcomes on the timeline: FAILED for the cover letter, OK for the rest.
        ArgumentCaptor<String> payloads = ArgumentCaptor.forClass(String.class);
        verify(db, times(3)).update(contains("application_events"), any(UUID.class), eq(APPLICATION),
                eq("PREPARATION"), payloads.capture());
        Map<String, String> stepStatus = new java.util.LinkedHashMap<>();
        payloads.getAllValues().forEach(v -> {
            String step = v.replaceAll(".*\"step\":\"([A-Z_]+)\".*", "$1");
            String status = v.replaceAll(".*\"status\":\"([A-Z_]+)\".*", "$1");
            stepStatus.put(step, status);
        });
        assertThat(stepStatus)
                .containsEntry("COVER_LETTER", "FAILED")
                .containsEntry("CV", "OK")
                .containsEntry("ANSWERS", "OK");
        // No prepared event when preparation is incomplete:
        verify(notifications, never()).emit(any());
    }

    @Test
    void greenhouseApplicationUsesControlledPlanInsteadOfInspectionPlan() {
        String greenhouseUrl = "https://boards.greenhouse.io/acme/jobs/42";
        when(jobRepository.findById(JOB)).thenReturn(java.util.Optional.of(
                new JobRecord(JOB, UUID.randomUUID(), "gh-1", null, "Acme", "Java Engineer",
                        "London", null, null, null, null, null,
                        new BigDecimal("40000"), new BigDecimal("60000"), "GBP",
                        "Java role", List.of("Java"), greenhouseUrl, greenhouseUrl, Instant.now(), "DISCOVERED")));
        when(greenhousePlanService.handles(greenhouseUrl)).thenReturn(true);
        when(greenhousePlanService.createExecutionPlan(PROFILE, APPLICATION, JOB))
                .thenReturn(java.util.Optional.of(UUID.randomUUID()));

        handler.handle(envelope(NotificationEvents.APPLICATION_CREATED, Map.of(
                "application_id", APPLICATION.toString(), "profile_id", PROFILE.toString(), "job_id", JOB.toString())));

        verify(greenhousePlanService).createExecutionPlan(PROFILE, APPLICATION, JOB);
        verify(inspectionPlanService, never()).createInspectionPlan(any(), any(), any());
    }

    @Test
    void preparationIsSkippedForAMissingApplication() {
        when(db.queryForObject(contains("count(*) from applications"), eq(Integer.class), eq(APPLICATION)))
                .thenReturn(0);

        handler.handle(envelope(NotificationEvents.APPLICATION_CREATED, Map.of(
                "application_id", APPLICATION.toString(), "profile_id", PROFILE.toString(), "job_id", JOB.toString())));

        verify(resumeService, never()).tailor(any(), any(), any());
        verify(db, never()).update(contains("application_events"), any(), any(), any());
    }

    @Test
    void unsupportedEventTypesAreIgnored() {
        handler.handle(envelope(NotificationEvents.JOB_DISCOVERED, Map.of()));

        verify(pipeline, never()).createApplicationFromMatch(any(), any());
        verify(resumeService, never()).tailor(any(), any(), any());
    }
}
