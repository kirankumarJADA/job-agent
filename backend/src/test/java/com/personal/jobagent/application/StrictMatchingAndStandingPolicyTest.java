package com.personal.jobagent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.apply.ApplyReadinessService;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.automation.ApplyDocumentSelector;
import com.personal.jobagent.automation.AutomationPlanRepository;
import com.personal.jobagent.automation.ExecutionPackageService;
import com.personal.jobagent.automation.StandingAuthorizationService;
import com.personal.jobagent.common.AutomationMetrics;
import com.personal.jobagent.common.QuotaService;
import com.personal.jobagent.jobs.HardFilterResult;
import com.personal.jobagent.jobs.HardFilterService;
import com.personal.jobagent.jobs.JobRecord;
import com.personal.jobagent.jobs.JobRepository;
import com.personal.jobagent.preferences.PreferenceSetRecord;
import com.personal.jobagent.preferences.PreferenceSetRepository;
import com.personal.jobagent.preferences.StandingAutoApplyPolicy;
import com.personal.jobagent.security.OwnerContext;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StrictMatchingAndStandingPolicyTest {

    private static final UUID PROFILE_ID = UUID.randomUUID();
    private static final UUID JOB_ID = UUID.randomUUID();
    private static final UUID PLAN_ID = UUID.randomUUID();
    private static final UUID APP_ID = UUID.randomUUID();

    private static JobRecord job(String title, String locationRaw, String city, String country,
                                 String remoteType, String employmentType, String experienceLevel,
                                 BigDecimal salaryMin, BigDecimal salaryMax, UUID companyId) {
        return new JobRecord(
                JOB_ID, UUID.randomUUID(), "ext-1", companyId, "Acme Ltd",
                title, locationRaw, city, country, remoteType, employmentType, experienceLevel,
                salaryMin, salaryMax, "GBP", "Java role", List.of("Java"),
                "https://careers.acme.example/apply", "https://careers.acme.example/apply",
                Instant.now(), "SCORED");
    }

    private static PreferenceSetRecord prefs(String mode, Long salaryMin, String sponsorship,
                                             Map<String, Object> extraFilters) {
        return new PreferenceSetRecord(
                UUID.randomUUID(), PROFILE_ID,
                List.of("Software Engineer"), List.of(), List.of(), List.of(),
                List.of("London", "UK"), List.of("REMOTE", "HYBRID"),
                List.of("FULL_TIME"), List.of("MID"),
                salaryMin, sponsorship, List.of(), List.of(),
                mode, Map.of(), extraFilters, true);
    }

    @Test
    void hardFilterEvaluateStrictRejectsMissingMetadataWhileLenientEvaluateAllowsDiscovery() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        HardFilterService service = new HardFilterService(db);

        JobRecord incomplete = job("Software Engineer", null, null, null, null, null, null, null, null, null);
        PreferenceSetRecord strictPrefs = prefs("CONTROLLED_AUTO", 70000L, "SPONSORSHIP_REQUIRED", Map.of());

        assertThat(service.evaluate(incomplete, strictPrefs).passed()).isTrue();

        HardFilterResult strict = service.evaluateStrict(incomplete, strictPrefs, List.of("United Kingdom"));
        assertThat(strict.passed()).isFalse();
        assertThat(strict.reasons()).extracting(HardFilterResult.FilterReason::criterion)
                .contains("LOCATION", "COUNTRY", "REMOTE_TYPE", "EMPLOYMENT_TYPE", "EXPERIENCE_LEVEL", "SALARY_MIN", "SPONSORSHIP");

        UUID sponsorCo = UUID.randomUUID();
        when(db.queryForList(anyString(), eq(sponsorCo))).thenReturn(List.of(
                Map.of("licence_indicator", "CONFIRMED", "confidence", BigDecimal.valueOf(0.95))));
        JobRecord complete = job("Software Engineer", "London, United Kingdom", "London", "United Kingdom",
                "HYBRID", "FULL_TIME", "MID", BigDecimal.valueOf(75000), BigDecimal.valueOf(90000), sponsorCo);
        assertThat(service.evaluateStrict(complete, strictPrefs, List.of("United Kingdom")).passed()).isTrue();
    }

    @Test
    void standingAutoApplyPolicyFailsClosedByDefaultOrMalformedValues() {
        assertThat(StandingAutoApplyPolicy.fromExtraFilters(Map.of()).enabled()).isFalse();
        assertThat(StandingAutoApplyPolicy.fromExtraFilters(Map.of(
                "standingAutoSubmit", Map.of("enabled", true, "minScore", 105))).enabled()).isFalse();
        assertThat(StandingAutoApplyPolicy.fromExtraFilters(Map.of(
                "standingAutoSubmit", Map.of("enabled", true, "dailySubmitCap", 99))).enabled()).isFalse();

        StandingAutoApplyPolicy valid = StandingAutoApplyPolicy.fromExtraFilters(Map.of(
                "standingAutoSubmit", Map.of(
                        "enabled", true,
                        "minScore", 88,
                        "dailySubmitCap", 5,
                        "allowedCountries", List.of("UK"),
                        "permittedRoles", List.of("Software Engineer"))));
        assertThat(valid.enabled()).isTrue();
        assertThat(valid.minScore()).isEqualTo(88);
        assertThat(valid.dailySubmitCap()).isEqualTo(5);
        assertThat(valid.matchesPermittedRole("Senior Software Engineer", null)).isTrue();
        assertThat(valid.matchesPermittedRole("Engineering Manager", null)).isFalse();
    }

    @Test
    void decisionServiceRoutesMissingStrictMetadataToNeedsReviewWithoutQuota() {
        PreferenceSetRepository prefRepo = mock(PreferenceSetRepository.class);
        QuotaService quota = mock(QuotaService.class);
        JdbcTemplate db = mock(JdbcTemplate.class);
        AutomationMetrics metrics = mock(AutomationMetrics.class);
        ApprovalRuleHealthMonitor health = mock(ApprovalRuleHealthMonitor.class);
        HardFilterService hardFilters = new HardFilterService(db);
        JobRepository jobRepo = mock(JobRepository.class);

        PreferenceSetRecord standingPrefs = prefs("CONTROLLED_AUTO", 70000L, "SHOW_ALL",
                Map.of("standingAutoSubmit", Map.of("enabled", true, "minScore", 85, "dailySubmitCap", 5)));
        when(prefRepo.findActiveByProfileId(PROFILE_ID)).thenReturn(Optional.of(standingPrefs));
        when(db.queryForList(anyString(), eq(PROFILE_ID))).thenReturn(List.of(
                Map.of("auto_approve_enabled", true, "min_score", 80)));
        JobRecord missingSalaryJob = job("Software Engineer", "London, UK", "London", "UK",
                "HYBRID", "FULL_TIME", "MID", null, null, null);
        when(jobRepo.findById(JOB_ID)).thenReturn(Optional.of(missingSalaryJob));

        ApplicationDecisionService decisionService = new ApplicationDecisionService(
                prefRepo, quota, db, metrics, health, hardFilters, jobRepo);
        var result = decisionService.decide(PROFILE_ID, JOB_ID, 92, "APPLY");

        assertThat(result.decision()).isEqualTo("NEEDS_REVIEW");
        assertThat(result.reason()).contains("Strict auto-apply review required");
        verify(quota, never()).tryReserve(any(), anyString(), anyInt());
    }

    @Test
    void approvalRulesControllerDisablesAndInvalidatesStandingApprovals() {
        ApplicationDecisionService decisions = mock(ApplicationDecisionService.class);
        AuditLogWriter audit = mock(AuditLogWriter.class);
        OwnerContext owner = mock(OwnerContext.class);
        QuotaService quotas = mock(QuotaService.class);
        PreferenceSetRepository prefRepo = mock(PreferenceSetRepository.class);
        AutomationPlanRepository planRepo = mock(AutomationPlanRepository.class);
        HttpServletRequest req = mock(HttpServletRequest.class);

        when(owner.profileIdOrNull()).thenReturn(PROFILE_ID);
        when(owner.actorOr("user")).thenReturn("candidate");
        when(req.getRemoteAddr()).thenReturn("127.0.0.1");
        when(decisions.ruleState(PROFILE_ID)).thenReturn(new ApplicationDecisionService.RuleState(
                ApplicationDecisionService.RuleAvailability.CONFIGURED,
                new ApplicationDecisionService.UserApprovalRule(true, 85), null));
        when(decisions.saveRule(PROFILE_ID, true, 85))
                .thenReturn(new ApplicationDecisionService.UserApprovalRule(true, 85));
        when(prefRepo.findActiveByProfileId(PROFILE_ID)).thenReturn(Optional.of(prefs(
                "CONTROLLED_AUTO", 70000L, "SHOW_ALL",
                Map.of("standingAutoSubmit", Map.of("enabled", true, "minScore", 85, "dailySubmitCap", 5)))));

        @SuppressWarnings("unchecked")
        ObjectProvider<PreferenceSetRepository> prefProvider = mock(ObjectProvider.class);
        when(prefProvider.getIfAvailable()).thenReturn(prefRepo);
        @SuppressWarnings("unchecked")
        ObjectProvider<AutomationPlanRepository> planProvider = mock(ObjectProvider.class);
        when(planProvider.getIfAvailable()).thenReturn(planRepo);

        ApprovalRulesController controller = new ApprovalRulesController(
                decisions, audit, owner, quotas, null, prefProvider, planProvider);

        ResponseEntity<?> invalid = controller.put(new ApprovalRulesController.RuleRequest(
                true, 85, true, 85, 99, null, null), req);
        assertThat(invalid.getStatusCode().value()).isEqualTo(400);

        ResponseEntity<?> disabled = controller.put(new ApprovalRulesController.RuleRequest(
                true, 85, false, null, null, null, null), req);
        assertThat(disabled.getStatusCode().value()).isEqualTo(200);
        verify(planRepo).invalidateApprovalsForProfile(PROFILE_ID);
    }

    @Test
    @SuppressWarnings("unchecked")
    void standingAuthorizationDefersOnProhibitedDeclarationAndStaysDisabledByDefault() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        AutomationPlanRepository plans = mock(AutomationPlanRepository.class);
        ApplyReadinessService readiness = mock(ApplyReadinessService.class);
        ApplyDocumentSelector selector = mock(ApplyDocumentSelector.class);
        ExecutionPackageService packages = mock(ExecutionPackageService.class);
        HardFilterService hardFilters = new HardFilterService(db);
        PreferenceSetRepository prefRepo = mock(PreferenceSetRepository.class);
        ApplicationDecisionService decisions = mock(ApplicationDecisionService.class);
        QuotaService quota = mock(QuotaService.class);
        AuditLogWriter audit = mock(AuditLogWriter.class);

        StandingAuthorizationService standing = new StandingAuthorizationService(
                db, plans, readiness, selector, packages, null,
                hardFilters, prefRepo, decisions, quota, audit, new ObjectMapper(), null);

        Map<String, Object> planBody = Map.of(
                "planType", "UNIVERSAL",
                "steps", List.of(Map.of("id", "u1", "type", "UNIVERSAL_APPLY")));
        List<Object> outcomeWithDeclaration = List.of(Map.of(
                "type", "UNIVERSAL_OUTCOME",
                "requiredGaps", List.of(Map.of(
                        "questionText", "I certify under penalty of perjury",
                        "category", "PROHIBITED_DECLARATION",
                        "prohibitedDeclaration", true))));
        when(plans.findById(PLAN_ID)).thenReturn(Optional.of(new AutomationPlanRepository.PlanRow(
                PLAN_ID, APP_ID, "https://careers.acme.example/apply",
                "AWAITING_APPROVAL", planBody, outcomeWithDeclaration, false, null, Instant.now())));
        when(plans.findProfileId(PLAN_ID)).thenReturn(Optional.of(PROFILE_ID));

        // 1. Standing disabled -> returns false silently without audit or quota write
        when(prefRepo.findActiveByProfileId(PROFILE_ID)).thenReturn(Optional.of(
                prefs("CONTROLLED_AUTO", 70000L, "SHOW_ALL", Map.of())));
        assertThat(standing.tryAutoAuthorizeAfterPreparation(PLAN_ID)).isFalse();
        verify(audit, never()).write(any());

        // 2. Standing enabled + prohibited declaration -> defers with PROHIBITED_DECLARATION audit, never calls approve/authorize
        when(prefRepo.findActiveByProfileId(PROFILE_ID)).thenReturn(Optional.of(
                prefs("CONTROLLED_AUTO", 70000L, "SHOW_ALL",
                        Map.of("standingAutoSubmit", Map.of("enabled", true, "minScore", 85, "dailySubmitCap", 5)))));
        when(decisions.ruleState(PROFILE_ID)).thenReturn(new ApplicationDecisionService.RuleState(
                ApplicationDecisionService.RuleAvailability.CONFIGURED,
                new ApplicationDecisionService.UserApprovalRule(true, 85), null));

        assertThat(standing.tryAutoAuthorizeAfterPreparation(PLAN_ID)).isFalse();
        verify(plans, never()).authorizeStandingSubmission(any(), anyString());
        verify(quota, never()).tryReserve(any(), anyString(), anyInt(), anyInt());
    }
}
