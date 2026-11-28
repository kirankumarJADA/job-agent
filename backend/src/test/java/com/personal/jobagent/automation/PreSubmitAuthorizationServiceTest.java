package com.personal.jobagent.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.application.ApplicationDecisionService;
import com.personal.jobagent.apply.ApplyReadinessService;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.common.MaintenanceModeService;
import com.personal.jobagent.jobs.HardFilterService;
import com.personal.jobagent.preferences.PreferenceSetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PreSubmitAuthorizationServiceTest {

    private PreSubmitAuthorizationService serviceWithMocks(
            JdbcTemplate db,
            AutomationPlanRepository plans,
            ApplyReadinessService readiness,
            ApplyDocumentSelector selector,
            ExecutionPackageService executionPackages,
            PreferenceSetRepository prefs,
            ApplicationDecisionService decisions,
            MaintenanceModeService maintenance,
            boolean allowLocalTestHosts) {
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new PreSubmitAuthorizationService(
                db, plans, readiness, selector, executionPackages,
                new HardFilterService(db), prefs, decisions,
                mock(AuditLogWriter.class), new ObjectMapper(),
                tx, maintenance, allowLocalTestHosts);
    }

    @Test
    void missingCollaboratorsAndMaintenanceModeFailClosedWith503() {
        UUID planId = UUID.randomUUID();
        PreSubmitAuthorizationService broken = new PreSubmitAuthorizationService(
                null, null, null, null, null, null, null, null,
                mock(AuditLogWriter.class), new ObjectMapper(), null, new MaintenanceModeService(false), false);
        var resMissing = broken.authorizeAndFence(planId, 99, "RUNNING", Map.of("phase", "PRE_SUBMIT_ATTEMPTING"), null);
        assertThat(resMissing.authorized()).isFalse();
        assertThat(resMissing.httpStatus()).isEqualTo(503);
        assertThat(resMissing.code()).isEqualTo("PRE_SUBMIT_AUTH_UNAVAILABLE");

        PreSubmitAuthorizationService maint = serviceWithMocks(
                mock(JdbcTemplate.class), mock(AutomationPlanRepository.class), mock(ApplyReadinessService.class),
                mock(ApplyDocumentSelector.class), mock(ExecutionPackageService.class),
                mock(PreferenceSetRepository.class), mock(ApplicationDecisionService.class),
                new MaintenanceModeService(true), false);
        var resMaint = maint.authorizeAndFence(planId, 99, "RUNNING", Map.of("phase", "PRE_SUBMIT_ATTEMPTING"), null);
        assertThat(resMaint.authorized()).isFalse();
        assertThat(resMaint.httpStatus()).isEqualTo(503);
        assertThat(resMaint.code()).isEqualTo("MAINTENANCE_MODE_ACTIVE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ZOHO_RECRUIT", "LEVER", "INSPECTION"})
    void nonSubmitAdaptersAreAlwaysRefusedAtPreSubmitFence(String nonSubmitAdapter) throws Exception {
        UUID planId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        UUID appId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        JdbcTemplate db = mock(JdbcTemplate.class);
        AutomationPlanRepository plans = mock(AutomationPlanRepository.class);
        when(plans.hasPreSubmitAttemptCheckpoint(planId)).thenReturn(false);

        String targetUrl = "https://careers.example.com/jobs/1";
        String planJson = new ObjectMapper().writeValueAsString(Map.of(
                "planType", nonSubmitAdapter,
                "correlation", Map.of("applicationId", appId.toString(), "jobId", jobId.toString()),
                "steps", List.of(Map.of("id", "inspect", "type", "NAVIGATE"))));
        stubLockedContext(db, planId, profileId, appId, jobId, targetUrl, "RUNNING", true,
                "READY_TO_APPLY", "ASSISTED", planJson, targetUrl, null, "{}");

        var svc = serviceWithMocks(db, plans, mock(ApplyReadinessService.class),
                mock(ApplyDocumentSelector.class), mock(ExecutionPackageService.class),
                mock(PreferenceSetRepository.class), mock(ApplicationDecisionService.class),
                new MaintenanceModeService(false), false);

        var out = svc.authorizeAndFence(planId, 99, "RUNNING", Map.of("phase", "PRE_SUBMIT_ATTEMPTING"), null);
        assertThat(out.authorized()).isFalse();
        assertThat(out.httpStatus()).isEqualTo(409);
        assertThat(out.code()).isEqualTo("ADAPTER_SUBMIT_UNSUPPORTED");
        verify(plans, never()).recordPreSubmitAttemptIfAbsent(any(), anyInt(), any(), any(), any());
    }

    @ParameterizedTest
    @CsvSource({
            "GREENHOUSE, REAL_SUBMIT_CLICK, https://boards.greenhouse.io/acme/jobs/1, https://evil.example/acme/jobs/1",
            "ASHBY, ASHBY_SUBMIT_CLICK, https://jobs.ashbyhq.com/ramp/123/application, https://jobs.ashbyhq.com/other/123/application",
            "WORKDAY, WORKDAY_SUBMIT_CLICK, https://acme.wd5.myworkdayjobs.com/en-US/Ext/job/x/apply, https://evil.wd5.myworkdayjobs.com/en-US/Ext/job/x/apply",
            "UNIVERSAL, UNIVERSAL_SUBMIT_CLICK, https://careers.acme.com/jobs/1, https://phishing-acme.example/jobs/1"
    })
    void domainAndTenantMismatchesAcrossAllFourAdaptersRefuseAuthorization(
            String adapter, String submitStep, String targetUrl, String maliciousCurrentUrl) throws Exception {
        UUID planId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        UUID appId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        JdbcTemplate db = mock(JdbcTemplate.class);
        AutomationPlanRepository plans = mock(AutomationPlanRepository.class);
        when(plans.hasPreSubmitAttemptCheckpoint(planId)).thenReturn(false);

        String planJson = new ObjectMapper().writeValueAsString(Map.of(
                "planType", adapter,
                "correlation", Map.of("applicationId", appId.toString(), "jobId", jobId.toString()),
                "steps", List.of(Map.of("id", "submit", "type", submitStep))));
        stubLockedContext(db, planId, profileId, appId, jobId, targetUrl, "RUNNING", true,
                "READY_TO_APPLY", "ASSISTED", planJson, targetUrl, null, "{}");

        var svc = serviceWithMocks(db, plans, mock(ApplyReadinessService.class),
                mock(ApplyDocumentSelector.class), mock(ExecutionPackageService.class),
                mock(PreferenceSetRepository.class), mock(ApplicationDecisionService.class),
                new MaintenanceModeService(false), false);

        var out = svc.authorizeAndFence(planId, 99, "RUNNING",
                Map.of("phase", "PRE_SUBMIT_ATTEMPTING", "atsPlatform", adapter,
                        "stepType", submitStep, "currentUrl", maliciousCurrentUrl),
                null);
        assertThat(out.authorized()).isFalse();
        assertThat(out.httpStatus()).isEqualTo(409);
        assertThat(out.code()).isEqualTo("TARGET_DOMAIN_MISMATCH");
        verify(plans, never()).recordPreSubmitAttemptIfAbsent(any(), anyInt(), any(), any(), any());
    }

    @Test
    void readinessAndDatabaseAndUnreadableRulesFailClosedWith503() throws Exception {
        UUID planId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        UUID appId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        JdbcTemplate db = mock(JdbcTemplate.class);
        AutomationPlanRepository plans = mock(AutomationPlanRepository.class);
        ApplyReadinessService readiness = mock(ApplyReadinessService.class);
        when(readiness.evaluate(profileId, appId))
                .thenThrow(new ApplyReadinessService.ReadinessUnavailableException("DB_DOWN"));

        String url = "https://boards.greenhouse.io/acme/jobs/1";
        String planJson = new ObjectMapper().writeValueAsString(Map.of(
                "planType", "GREENHOUSE",
                "correlation", Map.of("applicationId", appId.toString(), "jobId", jobId.toString()),
                "steps", List.of(Map.of("id", "real-submit-click", "type", "REAL_SUBMIT_CLICK"))));
        stubLockedContext(db, planId, profileId, appId, jobId, url, "RUNNING", true,
                "READY_TO_APPLY", "ASSISTED", planJson, url, null, "{}");

        PreferenceSetRepository prefs = mock(PreferenceSetRepository.class);
        when(prefs.findActiveByProfileId(profileId)).thenReturn(Optional.empty());

        var svc = serviceWithMocks(db, plans, readiness,
                mock(ApplyDocumentSelector.class), mock(ExecutionPackageService.class),
                prefs, mock(ApplicationDecisionService.class),
                new MaintenanceModeService(false), false);

        var res503 = svc.authorizeAndFence(planId, 99, "RUNNING", Map.of("phase", "PRE_SUBMIT_ATTEMPTING"), null);
        assertThat(res503.httpStatus()).isEqualTo(503);
        assertThat(res503.code()).isEqualTo("READINESS_UNAVAILABLE");

        JdbcTemplate failingDb = mock(JdbcTemplate.class);
        when(failingDb.query(anyString(), any(RowMapper.class), any()))
                .thenThrow(new DataRetrievalFailureException("pg connection lost"));
        var dbErrSvc = serviceWithMocks(failingDb, plans, readiness,
                mock(ApplyDocumentSelector.class), mock(ExecutionPackageService.class),
                prefs, mock(ApplicationDecisionService.class),
                new MaintenanceModeService(false), false);
        var resDb503 = dbErrSvc.authorizeAndFence(planId, 99, "RUNNING", Map.of("phase", "PRE_SUBMIT_ATTEMPTING"), null);
        assertThat(resDb503.httpStatus()).isEqualTo(503);
        assertThat(resDb503.code()).isEqualTo("PRE_SUBMIT_AUTH_UNAVAILABLE");

        var localDenied = svc.verifyTargetAndCurrentUrls(
                "UNIVERSAL", "http://127.0.0.1:4100/apply", "http://127.0.0.1:4100/apply", null, null);
        assertThat(localDenied).contains("allow-local-test-hosts=false");
    }

    @SuppressWarnings("unchecked")
    private static void stubLockedContext(
            JdbcTemplate db, UUID planId, UUID profileId, UUID appId, UUID jobId,
            String targetUrl, String planStatus, boolean submitApproved,
            String appStatus, String appMode, String planJson,
            String jobUrl, Timestamp deletedAt, String workEligibilityJson) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("plan_id")).thenReturn(planId);
        when(rs.getObject("plan_profile_id")).thenReturn(profileId);
        when(rs.getObject("application_id")).thenReturn(appId);
        when(rs.getString("target_url")).thenReturn(targetUrl);
        when(rs.getString("plan_status")).thenReturn(planStatus);
        when(rs.getBoolean("submit_approved")).thenReturn(submitApproved);
        when(rs.getString("plan_json")).thenReturn(planJson);
        when(rs.getObject("app_id")).thenReturn(appId);
        when(rs.getObject("app_profile_id")).thenReturn(profileId);
        when(rs.getString("app_status")).thenReturn(appStatus);
        when(rs.getString("app_mode")).thenReturn(appMode);
        when(rs.getObject("job_row_id")).thenReturn(jobId);
        when(rs.getObject("source_id")).thenReturn(UUID.randomUUID());
        when(rs.getString("external_id")).thenReturn("ext-1");
        when(rs.getString("company_name_raw")).thenReturn("Acme");
        when(rs.getString("title")).thenReturn("Platform Engineer");
        when(rs.getString("location_raw")).thenReturn("London");
        when(rs.getString("job_application_url")).thenReturn(jobUrl);
        when(rs.getString("job_status")).thenReturn("SCORED");
        when(rs.getTimestamp("job_deleted_at")).thenReturn(deletedAt);
        when(rs.getObject("prof_id")).thenReturn(profileId);
        when(rs.getString("work_eligibility_json")).thenReturn(workEligibilityJson);

        when(db.query(anyString(), any(RowMapper.class), any())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql.contains("for update of p")) {
                RowMapper<?> mapper = inv.getArgument(1);
                return List.of(mapper.mapRow(rs, 0));
            }
            return List.of();
        });
        when(db.queryForObject(anyString(), any(Class.class), any(), any())).thenReturn(false);
    }
}
