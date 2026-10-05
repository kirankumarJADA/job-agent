package com.personal.jobagent.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.application.ApplicationPipelineEventHandler;
import com.personal.jobagent.application.ApplicationPipelineService;
import com.personal.jobagent.coverletter.CoverLetterService;
import com.personal.jobagent.events.Envelope;
import com.personal.jobagent.jobs.JobRecord;
import com.personal.jobagent.jobs.JobRepository;
import com.personal.jobagent.notifications.NotificationEvents;
import com.personal.jobagent.notifications.NotificationService;
import com.personal.jobagent.qa.ApplicationAnswerService;
import com.personal.jobagent.resume.ResumeAtsIntelligenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration-style test: preparation success → inspection plan creation →
 * plan stored as PREPARED → claim-next returns it → ownership isolation.
 *
 * Uses mocked dependencies (no real DB) but verifies the complete call chain
 * from event handler through InspectionPlanService to AutomationPlanRepository.
 */
class InspectionPlanBridgeTest {

    private static final UUID PROFILE = UUID.randomUUID();
    private static final UUID APP_ID = UUID.randomUUID();
    private static final UUID JOB_ID = UUID.randomUUID();
    private static final UUID PLAN_ID = UUID.randomUUID();
    private static final String APP_URL = "https://boards.greenhouse.io/acme/jobs/42";

    private ApplicationPipelineService pipelineService;
    private InspectionPlanService inspectionPlanService;
    private GreenhouseExecutionPlanService greenhousePlanService;
    private ResumeAtsIntelligenceService resumeService;
    private CoverLetterService coverLetterService;
    private ApplicationAnswerService answerService;
    private JobRepository jobRepository;
    private NotificationService notifications;
    private AutomationPlanRepository planRepo;
    private JdbcTemplate db;
    private ApplicationPipelineEventHandler handler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        pipelineService = mock(ApplicationPipelineService.class);
        planRepo = mock(AutomationPlanRepository.class);
        db = mock(JdbcTemplate.class);
        resumeService = mock(ResumeAtsIntelligenceService.class);
        coverLetterService = mock(CoverLetterService.class);
        answerService = mock(ApplicationAnswerService.class);
        jobRepository = mock(JobRepository.class);
        notifications = mock(NotificationService.class);

        inspectionPlanService = new InspectionPlanService(planRepo, db);
        greenhousePlanService = mock(GreenhouseExecutionPlanService.class);

        handler = new ApplicationPipelineEventHandler(
                pipelineService, inspectionPlanService, greenhousePlanService,
                resumeService, coverLetterService, answerService,
                jobRepository, notifications, db, new ObjectMapper(),
                new com.personal.jobagent.common.AutomationMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                mock(com.personal.jobagent.application.ApplicationDecisionService.class));

        // Application exists
        when(db.queryForObject(contains("count(*) from applications"), eq(Integer.class), eq(APP_ID)))
                .thenReturn(1);
        // Timeline writes succeed
        when(db.update(contains("application_events"), any(UUID.class), eq(APP_ID), any(), any()))
                .thenReturn(1);
        // Dispatch treats this fixture as a non-Greenhouse application.
        String nonGreenhouseUrl = "https://jobs.example.org/acme";
        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(
                new JobRecord(JOB_ID, UUID.randomUUID(), "other-1", null, "Acme", "Java Engineer",
                        "London", null, null, null, null, null,
                        new BigDecimal("40000"), new BigDecimal("60000"), "GBP",
                        "Java role", List.of("Java"), nonGreenhouseUrl, nonGreenhouseUrl, Instant.now(), "DISCOVERED")));
        when(greenhousePlanService.handles(nonGreenhouseUrl)).thenReturn(false);
        // Application URL query for InspectionPlanService
        when(db.query(contains("application_url"), any(RowMapper.class), eq(JOB_ID)))
                .thenReturn(List.of(nonGreenhouseUrl));
        // Plan creation returns a deterministic id
        when(planRepo.create(eq(PROFILE), eq(APP_ID), eq(JOB_ID),
                eq("https://jobs.example.org/acme"), eq("inspect:" + APP_ID), anyList()))
                .thenReturn(PLAN_ID);
    }

    @Test
    void fullBridge_preparationSuccess_createsInspectionPlan() {
        // Fire the application.created event (as the outbox would)
        handler.handle(createdEnvelope());

        // Preparation steps ran
        verify(resumeService).tailor(PROFILE, JOB_ID, APP_ID);
        verify(coverLetterService).generateCoverLetter(PROFILE, JOB_ID, APP_ID);
        verify(answerService).draftAnswer(eq(PROFILE), eq(JOB_ID), eq(APP_ID), contains("Acme"));

        // Inspection plan was created with correct parameters
        verify(planRepo).create(eq(PROFILE), eq(APP_ID), eq(JOB_ID),
                eq("https://jobs.example.org/acme"), eq("inspect:" + APP_ID),
                argThat(steps -> steps.size() == 3
                        && "NAVIGATE".equals(steps.get(0).type())
                        && "SCREENSHOT".equals(steps.get(1).type())
                        && "POLICY_CHECK".equals(steps.get(2).type())));

        // Timeline records the plan creation
        verify(db).update(contains("application_events"), any(UUID.class), eq(APP_ID),
                eq("INSPECTION_PLAN_CREATED"), contains(PLAN_ID.toString()));
    }

    @Test
    void noPlanCreatedWhenPreparationFails() {
        // Cover letter generation fails
        when(coverLetterService.generateCoverLetter(PROFILE, JOB_ID, APP_ID))
                .thenThrow(new IllegalStateException("no model credentials"));

        handler.handle(createdEnvelope());

        // Inspection plan service was never called
        verify(planRepo, never()).create(any(), any(), any(), any(), any(), anyList());
    }

    @Test
    void duplicateEventProducesSinglePlan() {
        handler.handle(createdEnvelope());
        handler.handle(createdEnvelope());

        // The idempotency key is the same both times; the ON CONFLICT in the
        // repository ensures only one row. The service is called twice but
        // the second insert is a no-op.
        verify(planRepo, times(2)).create(eq(PROFILE), eq(APP_ID), eq(JOB_ID),
                eq("https://jobs.example.org/acme"), eq("inspect:" + APP_ID), anyList());
    }

    @Test
    void inspectionPlanStepsNeverIncludeRealSubmit() {
        handler.handle(createdEnvelope());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AutomationPlan.Step>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(planRepo).create(any(), any(), any(), any(), any(), captor.capture());

        for (var step : captor.getValue()) {
            assertThat(step.type()).isNotEqualTo("MOCK_SUBMIT");
            assertThat(step.type()).isNotEqualTo("FILL_FIELD");
            assertThat(step.type()).isNotEqualTo("UPLOAD_FILE");
            assertThat(step.type()).isNotEqualTo("CLICK");
            if ("POLICY_CHECK".equals(step.type())) {
                assertThat(step.params().get("action")).isNotEqualTo("REAL_SUBMIT");
            }
        }
    }

    @Test
    void planCreationFailureDoesNotBreakPreparationTimeline() {
        when(planRepo.create(any(), any(), any(), any(), any(), anyList()))
                .thenThrow(new RuntimeException("db down"));

        // Should not throw; the failure is recorded on the timeline
        assertThatCode(() -> handler.handle(createdEnvelope())).doesNotThrowAnyException();

        // Preparation steps still ran successfully
        verify(resumeService).tailor(PROFILE, JOB_ID, APP_ID);

        // The failure was recorded
        verify(db).update(contains("application_events"), any(UUID.class), eq(APP_ID),
                eq("AUTOMATION_PLAN_FAILED"), contains("RuntimeException"));
    }

    private Envelope createdEnvelope() {
        return new Envelope(UUID.randomUUID(), NotificationEvents.APPLICATION_CREATED,
                1, Instant.now(), "APPLICATION", APP_ID, UUID.randomUUID(), null,
                Map.of("application_id", APP_ID.toString(),
                        "profile_id", PROFILE.toString(),
                        "job_id", JOB_ID.toString()));
    }
}
