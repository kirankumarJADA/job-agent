package com.personal.jobagent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.automation.AshbyExecutionPlanService;
import com.personal.jobagent.automation.GreenhouseExecutionPlanService;
import com.personal.jobagent.automation.InspectionPlanService;
import com.personal.jobagent.automation.LeverExecutionPlanService;
import com.personal.jobagent.automation.WorkdayExecutionPlanService;
import com.personal.jobagent.automation.ZohoRecruitExecutionPlanService;
import com.personal.jobagent.common.AutomationMetrics;
import com.personal.jobagent.common.QuotaService;
import com.personal.jobagent.coverletter.CoverLetterService;
import com.personal.jobagent.events.Envelope;
import com.personal.jobagent.jobs.JobRepository;
import com.personal.jobagent.notifications.NotificationService;
import com.personal.jobagent.preferences.PreferenceSetRepository;
import com.personal.jobagent.qa.ApplicationAnswerService;
import com.personal.jobagent.resume.ResumeAtsIntelligenceService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Gate 2 verification: processes the ACTUAL 11 retained job.matched events
 * directly from public.outbox_events in the restored production-derived database
 * through the real ApplicationPipelineEventHandler and ApplicationDecisionService,
 * under app.migration.manual-review-only=true.
 *
 * Asserts:
 * - Exactly the 11 actual outbox events are processed (no synthetic replacements);
 * - All 11 decisions are persisted as NEEDS_REVIEW with the migration guard reason;
 * - Zero applications created;
 * - Zero automation plans created;
 * - Zero application.created events emitted;
 * - Zero quota consumed;
 * - Zero automated preparation tasks dispatched.
 */
@EnabledIfSystemProperty(named = "robin.migration.verify-actual-events.url", matches = ".+",
        disabledReason = "operator tool: set -Drobin.migration.verify-actual-events.url to run it")
class ExternalDatabaseActualReviewIT {

    @Test
    void processesActualRetainedElevenJobMatchedEventsUnderManualReviewGuard() throws Exception {
        String url = System.getProperty("robin.migration.verify-actual-events.url");
        String user = System.getProperty("robin.migration.verify-actual-events.user", "robin_app");
        // From the environment (set by scripts/db-migration/common.ps1 for the Maven process
        // only), so the password never appears on a command line or in the failsafe report.
        String envPassword = System.getenv("ROBIN_EXTERNAL_DB_PASSWORD");
        String password = envPassword != null && !envPassword.isEmpty()
                ? envPassword : System.getProperty("robin.migration.verify-actual-events.password", "");
        String reportPath = System.getProperty("robin.migration.verify-actual-events.report");

        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, user, password);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ObjectMapper mapper = new ObjectMapper();

        // 1. Query the actual retained job.matched events from outbox_events
        List<Map<String, Object>> actualEvents = jdbc.queryForList("""
                SELECT id, sequence_id, aggregate_type, aggregate_id, event_type,
                       schema_version, payload::text AS payload_json, correlation_id,
                       causation_id, created_at, attempt_count
                FROM public.outbox_events
                WHERE published_at IS NULL AND event_type = 'job.matched'
                ORDER BY sequence_id
                """);

        assertThat(actualEvents)
                .as("Actual production archive must contain exactly 11 pending job.matched events")
                .hasSize(11);

        // 2. Query initial system state
        int appsBefore = jdbc.queryForObject("SELECT count(*)::int FROM public.applications", Integer.class);
        int plansBefore = jdbc.queryForObject("SELECT count(*)::int FROM public.automation_plans", Integer.class);
        int appCreatedBefore = jdbc.queryForObject(
                "SELECT count(*)::int FROM public.outbox_events WHERE event_type = 'application.created'", Integer.class);
        int quotaRowsBefore = jdbc.queryForObject("SELECT count(*)::int FROM public.usage_quotas", Integer.class);
        double quotaUsedBefore = jdbc.queryForObject(
                "SELECT coalesce(sum(used), 0.0)::double precision FROM public.usage_quotas", Double.class);
        int decisionsBefore = jdbc.queryForObject("SELECT count(*)::int FROM public.application_decisions", Integer.class);

        // 3. Set up the REAL application decision engine and event handler with guard ON
        PreferenceSetRepository preferenceSets = new PreferenceSetRepository(jdbc, mapper);
        QuotaService quotaService = new QuotaService(jdbc, 500, 200, 25, true);
        AutomationMetrics metrics = new AutomationMetrics(new SimpleMeterRegistry());
        ApprovalRuleHealthMonitor health = mock(ApprovalRuleHealthMonitor.class);
        ApplicationDecisionService decisionService = new ApplicationDecisionService(
                preferenceSets, quotaService, jdbc, metrics, health);
        ReflectionTestUtils.setField(decisionService, "manualReviewOnly", true);

        // Mocked downstream preparation services to guarantee zero downstream side-effects
        ApplicationPipelineService mockPipeline = mock(ApplicationPipelineService.class);
        InspectionPlanService mockInspection = mock(InspectionPlanService.class);
        GreenhouseExecutionPlanService mockGh = mock(GreenhouseExecutionPlanService.class);
        ZohoRecruitExecutionPlanService mockZoho = mock(ZohoRecruitExecutionPlanService.class);
        LeverExecutionPlanService mockLever = mock(LeverExecutionPlanService.class);
        WorkdayExecutionPlanService mockWorkday = mock(WorkdayExecutionPlanService.class);
        AshbyExecutionPlanService mockAshby = mock(AshbyExecutionPlanService.class);
        ResumeAtsIntelligenceService mockResume = mock(ResumeAtsIntelligenceService.class);
        CoverLetterService mockCover = mock(CoverLetterService.class);
        ApplicationAnswerService mockAnswer = mock(ApplicationAnswerService.class);
        JobRepository mockJobRepo = mock(JobRepository.class);
        NotificationService mockNotifications = mock(NotificationService.class);
        AuditLogWriter mockAudit = mock(AuditLogWriter.class);

        ApplicationPipelineEventHandler handler = new ApplicationPipelineEventHandler(
                mockPipeline, mockInspection, mockGh, mockZoho, mockLever, mockWorkday, mockAshby,
                mockResume, mockCover, mockAnswer, mockJobRepo, mockNotifications, jdbc, mapper,
                metrics, decisionService, mockAudit, quotaService);

        // 4. Process each actual retained event through the real event handler
        List<String> auditLines = new ArrayList<>();
        auditLines.add("=== ACTUAL 11 JOB.MATCHED EVENTS MANUAL-REVIEW VERIFICATION ===");
        auditLines.add("Target Database: " + url);
        auditLines.add("Execution Time: " + Instant.now());
        auditLines.add("Guard Status: app.migration.manual-review-only=true");
        auditLines.add("");

        for (Map<String, Object> row : actualEvents) {
            UUID eventId = (UUID) row.get("id");
            long seqId = ((Number) row.get("sequence_id")).longValue();
            UUID aggregateId = (UUID) row.get("aggregate_id");
            String aggregateType = (String) row.get("aggregate_type");
            String eventType = (String) row.get("event_type");
            String payloadJson = (String) row.get("payload_json");
            UUID correlationId = (UUID) row.get("correlation_id");
            UUID causationId = (UUID) row.get("causation_id");
            Instant createdAt = ((java.sql.Timestamp) row.get("created_at")).toInstant();

            @SuppressWarnings("unchecked")
            Map<String, Object> payloadMap = mapper.readValue(payloadJson, Map.class);
            UUID jobId = payloadMap.get("job_id") != null ? UUID.fromString(String.valueOf(payloadMap.get("job_id"))) : aggregateId;
            UUID profileId = null;
            if (payloadMap.get("profile_id") != null) {
                profileId = UUID.fromString(String.valueOf(payloadMap.get("profile_id")));
            } else {
                List<UUID> candidateProfiles = jdbc.queryForList("SELECT profile_id FROM public.job_matches WHERE job_id = ?", UUID.class, jobId);
                if (!candidateProfiles.isEmpty()) {
                    profileId = candidateProfiles.get(0);
                } else {
                    List<UUID> anyProfile = jdbc.queryForList("SELECT id FROM public.profiles LIMIT 1", UUID.class);
                    if (!anyProfile.isEmpty()) profileId = anyProfile.get(0);
                }
                if (profileId != null) payloadMap.put("profile_id", profileId.toString());
            }
            int score = ((Number) payloadMap.getOrDefault("score", 0)).intValue();
            String rec = String.valueOf(payloadMap.getOrDefault("recommendation", ""));

            Envelope envelope = new Envelope(
                    eventId, eventType, 1, createdAt,
                    aggregateType, aggregateId, correlationId, causationId, payloadMap);

            // Real pipeline execution:
            handler.handle(envelope);

            auditLines.add(String.format("EVENT seq=%d id=%s job=%s profile=%s score=%d rec=%s -> DISPATCHED",
                    seqId, eventId, jobId, profileId, score, rec));
        }

        // 5. Verify the persisted outcomes in application_decisions
        List<Map<String, Object>> decisions = jdbc.queryForList("""
                SELECT profile_id, job_id, decision, reason, application_mode, match_score
                FROM public.application_decisions
                ORDER BY updated_at DESC
                """);

        assertThat(decisions)
                .as("Must have persisted decisions for all 11 actual matched jobs")
                .hasSizeGreaterThanOrEqualTo(11);

        for (Map<String, Object> d : decisions) {
            assertThat(d.get("decision")).as("Decision must be NEEDS_REVIEW").isEqualTo("NEEDS_REVIEW");
            assertThat(d.get("reason")).as("Reason must be migration guard reason")
                    .isEqualTo(ApplicationDecisionService.MIGRATION_GUARD_REASON);
            auditLines.add(String.format("DECISION job=%s profile=%s mode=%s score=%s decision=%s reason=%s",
                    d.get("job_id"), d.get("profile_id"), d.get("application_mode"), d.get("match_score"),
                    d.get("decision"), d.get("reason")));
        }

        // 6. Strict assertions: ZERO automated actions occurred
        int appsAfter = jdbc.queryForObject("SELECT count(*)::int FROM public.applications", Integer.class);
        int plansAfter = jdbc.queryForObject("SELECT count(*)::int FROM public.automation_plans", Integer.class);
        int appCreatedAfter = jdbc.queryForObject(
                "SELECT count(*)::int FROM public.outbox_events WHERE event_type = 'application.created'", Integer.class);
        int quotaRowsAfter = jdbc.queryForObject("SELECT count(*)::int FROM public.usage_quotas", Integer.class);
        double quotaUsedAfter = jdbc.queryForObject(
                "SELECT coalesce(sum(used), 0.0)::double precision FROM public.usage_quotas", Double.class);
        int decisionsAfter = jdbc.queryForObject("SELECT count(*)::int FROM public.application_decisions", Integer.class);

        assertThat(appsAfter).as("applications count must not increase").isEqualTo(appsBefore);
        assertThat(plansAfter).as("automation_plans count must not increase").isEqualTo(plansBefore);
        assertThat(appCreatedAfter).as("application.created events must be 0").isEqualTo(appCreatedBefore);
        assertThat(quotaRowsAfter).as("usage_quotas row count must not increase").isEqualTo(quotaRowsBefore);
        assertThat(quotaUsedAfter).as("quota consumption must be exactly zero").isEqualTo(quotaUsedBefore);
        assertThat(decisionsAfter - decisionsBefore).as("Exactly 11 decisions must be recorded").isEqualTo(11);

        // Downstream pipeline mock verified to have NEVER been invoked
        verifyNoInteractions(mockPipeline);
        verifyNoInteractions(mockInspection);
        verifyNoInteractions(mockGh);
        verifyNoInteractions(mockZoho);
        verifyNoInteractions(mockLever);
        verifyNoInteractions(mockWorkday);
        verifyNoInteractions(mockAshby);
        verifyNoInteractions(mockResume);
        verifyNoInteractions(mockCover);
        verifyNoInteractions(mockAnswer);

        String summaryHeader = String.format("ROBIN_ACTUAL_REVIEW PASS: 11/11 actual job.matched events held as NEEDS_REVIEW; applications=+0, plans=+0, appCreatedEvents=+0, quotaUsed=+0.0, decisions=%d",
                decisions.size());
        auditLines.add(0, summaryHeader);
        auditLines.add("");
        auditLines.add(String.format("VERIFICATION SUCCESS: applications=+0, plans=+0, appCreatedEvents=+0, quotaUsed=+0.0, decisions=%d",
                decisions.size()));
        auditLines.add("STATUS: 11/11 ACTUAL JOB.MATCHED EVENTS HELD AS NEEDS_REVIEW UNDER MIGRATION GUARD");

        String reportContent = String.join(System.lineSeparator(), auditLines);
        System.out.println(reportContent);

        if (reportPath != null && !reportPath.isBlank()) {
            Files.writeString(Path.of(reportPath), reportContent + System.lineSeparator(), StandardCharsets.UTF_8);
        }
    }
}
