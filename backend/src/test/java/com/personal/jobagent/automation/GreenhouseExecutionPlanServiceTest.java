package com.personal.jobagent.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.ats.GreenhouseAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GreenhouseExecutionPlanServiceTest {
    private static final UUID PROFILE = UUID.randomUUID();
    private static final UUID APPLICATION = UUID.randomUUID();
    private static final UUID JOB = UUID.randomUUID();
    private static final UUID PLAN = UUID.randomUUID();
    private static final String URL = "https://boards.greenhouse.io/acme/jobs/42";

    private AutomationPlanRepository plans;
    private GreenhouseAdapter adapter;
    private ExecutionPackageService packages;
    private JdbcTemplate db;
    private ObjectMapper json;
    private GreenhouseExecutionPlanService service;

    @BeforeEach
    void setUp() {
        plans = mock(AutomationPlanRepository.class);
        adapter = mock(GreenhouseAdapter.class);
        packages = mock(ExecutionPackageService.class);
        db = mock(JdbcTemplate.class);
        json = new ObjectMapper();
        service = new GreenhouseExecutionPlanService(plans, adapter, packages, db, json);
        when(adapter.matchesUrl(URL)).thenReturn(true);
        when(db.queryForMap(contains("from applications a join jobs"), eq(APPLICATION), eq(PROFILE), eq(JOB)))
                .thenReturn(Map.of("id", APPLICATION, "profile_id", PROFILE, "job_id", JOB, "application_url", URL));
        when(plans.create(eq(PROFILE), eq(APPLICATION), eq(JOB), eq(URL), eq("greenhouse:" + APPLICATION), anyList()))
                .thenReturn(PLAN);
        when(plans.findById(PLAN)).thenReturn(Optional.of(new AutomationPlanRepository.PlanRow(
                PLAN, APPLICATION, URL, "PREPARED", Map.of(), List.of(), false, null, Instant.now())));
        when(db.update(contains("update automation_plans set plan"), anyString(), eq(PLAN), eq(PROFILE), eq(APPLICATION)))
                .thenReturn(1);
    }

    @Test
    void onlySafeContactValuesBecomeFillStepsAndHumanAnswersStayOutOfPlan() throws Exception {
        when(packages.build(PLAN, PROFILE, APPLICATION, JOB)).thenReturn(executionPackage(URL,
                List.of(field("email", "text", "SUPPORTED_AUTO", "jane@example.com", "users.email"),
                        field("question_42", "textarea", "REQUIRES_HUMAN", "", "")),
                List.of(Map.of("key", "question_42", "label", "Why this role?", "classification", "REQUIRES_HUMAN", "reason", "user review"))));

        Optional<UUID> created = service.createExecutionPlan(PROFILE, APPLICATION, JOB);

        assertThat(created).contains(PLAN);
        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(db).update(contains("update automation_plans set plan"), stored.capture(), eq(PLAN), eq(PROFILE), eq(APPLICATION));
        Map<?, ?> workerPlan = json.readValue(stored.getValue(), Map.class);
        List<Map<String, Object>> steps = (List<Map<String, Object>>) workerPlan.get("steps");
        assertThat(steps).extracting(step -> step.get("type"))
                .containsExactly("NAVIGATE", "SCREENSHOT", "FILL_FIELD", "VALIDATE");
        assertThat(steps.get(2)).containsEntry("id", "fill-email");
        assertThat(steps.get(3)).containsEntry("id", "validate-form");
        assertThat(workerPlan.get("requiredGaps")).isEqualTo(List.of(Map.of(
                "key", "question_42", "label", "Why this role?", "classification", "REQUIRES_HUMAN", "reason", "user review")));
    }

    @Test
    void mismatchedExecutionPackageCorrelationIsRejectedBeforePersistence() {
        when(packages.build(PLAN, PROFILE, APPLICATION, JOB)).thenReturn(executionPackage(
                "https://boards.greenhouse.io/other/jobs/42", List.of(), List.of()));

        assertThatThrownBy(() -> service.createExecutionPlan(PROFILE, APPLICATION, JOB))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("GREENHOUSE_PACKAGE_CORRELATION_MISMATCH");
        verify(db, never()).update(contains("update automation_plans set plan"), anyString(), any(), any(), any());
    }

    @Test
    void nullCorrelationIsRejectedBeforeDatabaseRead() {
        assertThatThrownBy(() -> service.createExecutionPlan(PROFILE, null, JOB))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(db);
    }

    private ExecutionPackageService.ExecutionPackage executionPackage(
            String expectedUrl, List<Map<String, Object>> fields, List<Map<String, Object>> gaps) {
        return new ExecutionPackageService.ExecutionPackage(PLAN, APPLICATION, JOB, expectedUrl,
                Map.of(), null, null, List.of(), List.of(), fields, List.of(), List.of(), gaps,
                AutomationPlan.SAFETY_CONTRACT);
    }

    private static Map<String, Object> field(String key, String type, String classification,
                                             String value, String source) {
        return Map.of("key", key, "label", key, "htmlType", type, "required", false,
                "selector", "#" + key, "options", List.of(), "classification", classification,
                "valueSource", source, "value", value, "reason", "");
    }
}
