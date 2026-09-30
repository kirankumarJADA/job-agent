package com.personal.jobagent.automation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for InspectionPlanService: plan creation, idempotency,
 * missing-URL handling.
 */
class InspectionPlanServiceTest {

    private AutomationPlanRepository plans;
    private JdbcTemplate db;
    private InspectionPlanService service;

    private static final UUID PROFILE = UUID.randomUUID();
    private static final UUID APP_ID = UUID.randomUUID();
    private static final UUID JOB_ID = UUID.randomUUID();
    private static final UUID PLAN_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        plans = mock(AutomationPlanRepository.class);
        db = mock(JdbcTemplate.class);
        service = new InspectionPlanService(plans, db);
    }

    @SuppressWarnings("unchecked")
    private void jobHasUrl(String url) {
        when(db.query(contains("application_url"), any(RowMapper.class), eq(JOB_ID)))
                .thenReturn(url == null ? List.of() : List.of(url));
    }

    @Test
    void createsInspectionPlanWhenApplicationUrlExists() {
        jobHasUrl("https://boards.greenhouse.io/acme/jobs/123");
        when(plans.create(eq(PROFILE), eq(APP_ID), eq(JOB_ID),
                eq("https://boards.greenhouse.io/acme/jobs/123"),
                eq("inspect:" + APP_ID), anyList())).thenReturn(PLAN_ID);

        Optional<UUID> result = service.createInspectionPlan(PROFILE, APP_ID, JOB_ID);

        assertThat(result).contains(PLAN_ID);
        verify(plans).create(eq(PROFILE), eq(APP_ID), eq(JOB_ID),
                anyString(), eq("inspect:" + APP_ID), argThat(steps ->
                        steps.size() == 3
                        && "NAVIGATE".equals(steps.get(0).type())
                        && "SCREENSHOT".equals(steps.get(1).type())
                        && "POLICY_CHECK".equals(steps.get(2).type())));
    }

    @Test
    void returnsEmptyWhenNoApplicationUrl() {
        jobHasUrl(null);

        Optional<UUID> result = service.createInspectionPlan(PROFILE, APP_ID, JOB_ID);

        assertThat(result).isEmpty();
        verifyNoInteractions(plans);
    }

    @Test
    void returnsEmptyWhenApplicationUrlIsBlank() {
        jobHasUrl("  ");

        Optional<UUID> result = service.createInspectionPlan(PROFILE, APP_ID, JOB_ID);

        assertThat(result).isEmpty();
        verifyNoInteractions(plans);
    }

    @Test
    void idempotencyKeyIsDeterministic() {
        jobHasUrl("https://boards.greenhouse.io/acme");
        when(plans.create(eq(PROFILE), eq(APP_ID), eq(JOB_ID),
                anyString(), eq("inspect:" + APP_ID), anyList())).thenReturn(PLAN_ID);

        service.createInspectionPlan(PROFILE, APP_ID, JOB_ID);
        service.createInspectionPlan(PROFILE, APP_ID, JOB_ID);

        // Both calls use the same idempotency key; the repository's ON CONFLICT
        // prevents duplicates.
        verify(plans, times(2)).create(eq(PROFILE), eq(APP_ID), eq(JOB_ID),
                anyString(), eq("inspect:" + APP_ID), anyList());
    }

    @Test
    void inspectionStepsNeverContainFillFieldOrUpload() {
        jobHasUrl("https://example.com/apply");
        when(plans.create(any(), any(), any(), any(), any(), anyList())).thenReturn(PLAN_ID);

        service.createInspectionPlan(PROFILE, APP_ID, JOB_ID);

        verify(plans).create(any(), any(), any(), any(), any(), argThat(steps -> {
            for (var step : steps) {
                assertThat(step.type()).isNotIn("FILL_FIELD", "UPLOAD_FILE", "MOCK_SUBMIT", "CLICK");
            }
            return true;
        }));
    }
}
