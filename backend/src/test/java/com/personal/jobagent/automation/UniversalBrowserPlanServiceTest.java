package com.personal.jobagent.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.discovery.platform.AddressGuard;
import com.personal.jobagent.mailbox.RegistrationIdentityService;
import com.personal.jobagent.qa.ApplicationAnswerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UniversalBrowserPlanServiceTest {

    private static AddressGuard publicResolverGuard() {
        return new AddressGuard(host -> new InetAddress[]{InetAddress.getByAddress(host, new byte[]{93, (byte) 184, (byte) 216, 34})});
    }

    @Test
    void enforcesSsrfGuardAndOnlyAllowsLoopbackWhenExplicitlyConfigured() {
        AutomationPlanRepository plans = mock(AutomationPlanRepository.class);
        ApplyDocumentSelector selector = mock(ApplyDocumentSelector.class);
        ApplicationAnswerRepository answers = mock(ApplicationAnswerRepository.class);
        JdbcTemplate db = mock(JdbcTemplate.class);

        var prodService = new UniversalBrowserPlanService(
                plans, selector, answers, null, publicResolverGuard(), db, new ObjectMapper(), false);

        assertThat(prodService.handles("https://careers.acme.example/apply/1")).isTrue();
        assertThat(prodService.handles("http://127.0.0.1:8080/careers/apply")).isFalse();
        assertThat(prodService.handles("http://localhost:8080/careers/apply")).isFalse();
        assertThat(prodService.handles("http://169.254.169.254/latest/meta-data")).isFalse();
        assertThat(prodService.handles("https://10.0.0.5/internal")).isFalse();
        assertThat(prodService.handles("javascript:alert(1)")).isFalse();
        assertThat(prodService.handles("https://user:pass@careers.acme.example/apply")).isFalse();
        assertThat(prodService.handles("https://boards.greenhouse.io/acme/jobs/1")).isFalse();

        var localTestService = new UniversalBrowserPlanService(
                plans, selector, answers, null, publicResolverGuard(), db, new ObjectMapper(), true);
        assertThat(localTestService.handles("http://127.0.0.1:45123/careers/apply")).isTrue();
        assertThat(localTestService.handles("http://169.254.169.254/latest/meta-data")).isFalse();
        assertThat(localTestService.handles("https://10.0.0.5/internal")).isFalse();
    }

    @Test
    void refusesToCreateUniversalPlanWhenDocumentSelectionIsBlocked() {
        UUID profileId = UUID.randomUUID();
        UUID appId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();

        AutomationPlanRepository plans = mock(AutomationPlanRepository.class);
        ApplyDocumentSelector selector = mock(ApplyDocumentSelector.class);
        ApplicationAnswerRepository answers = mock(ApplicationAnswerRepository.class);
        JdbcTemplate db = mock(JdbcTemplate.class);

        when(db.queryForMap(contains("from applications"), eq(appId), eq(profileId), eq(jobId)))
                .thenReturn(Map.of("id", appId, "application_url", "https://careers.acme.example/jobs/42"));
        when(plans.create(eq(profileId), eq(appId), eq(jobId), anyString(), eq("universal:" + appId), any()))
                .thenReturn(planId);
        when(plans.findById(planId)).thenReturn(java.util.Optional.of(new AutomationPlanRepository.PlanRow(
                planId, appId, "https://careers.acme.example/jobs/42", "PREPARED", Map.of(), null, false, null, java.time.Instant.now())));
        when(selector.select(profileId, jobId, appId)).thenReturn(new ApplyDocumentSelector.DocumentSelection(
                null, null, com.personal.jobagent.ats.JobFormQuestionService.CoverLetterRequirement.UNKNOWN,
                List.of(), List.of(Map.of("code", "CV_REVIEW_REQUIRED", "message", "review CV"))));

        var service = new UniversalBrowserPlanService(
                plans, selector, answers, mock(RegistrationIdentityService.class), publicResolverGuard(),
                db, new ObjectMapper(), false);

        assertThatThrownBy(() -> service.createExecutionPlan(profileId, appId, jobId))
                .isInstanceOf(ExecutionPackageBlockedException.class);
        verify(db, never()).update(contains("update automation_plans"), anyString(), any(), any(), any());
    }
}
