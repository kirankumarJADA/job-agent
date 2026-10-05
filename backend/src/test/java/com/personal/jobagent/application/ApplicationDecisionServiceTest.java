package com.personal.jobagent.application;

import com.personal.jobagent.common.AutomationMetrics;
import com.personal.jobagent.common.QuotaService;
import com.personal.jobagent.preferences.PreferenceSetRecord;
import com.personal.jobagent.preferences.PreferenceSetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 5 decision engine: mode + quota decide whether an APPLY-level match
 * auto-applies or queues for human review, and every outcome is persisted
 * (upsert — event replays never duplicate a decision row).
 */
class ApplicationDecisionServiceTest {

    private static final UUID PROFILE = UUID.randomUUID();
    private static final UUID JOB = UUID.randomUUID();

    private final PreferenceSetRepository preferenceSets = mock(PreferenceSetRepository.class);
    private final QuotaService quotaService = mock(QuotaService.class);
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final AutomationMetrics metrics = mock(AutomationMetrics.class);
    private ApplicationDecisionService service;

    @BeforeEach
    void setUp() {
        Mockito.reset(preferenceSets, quotaService, db, metrics);
        when(db.update(anyString(), any(Object[].class))).thenReturn(1);
        service = new ApplicationDecisionService(preferenceSets, quotaService, db, metrics);
    }

    private void mode(String applicationMode) {
        com.personal.jobagent.preferences.PreferenceSetRecord record =
                mock(com.personal.jobagent.preferences.PreferenceSetRecord.class);
        when(record.applicationMode()).thenReturn(applicationMode);
        when(preferenceSets.findActiveByProfileId(PROFILE)).thenReturn(Optional.of(record));
    }

    private void quotaAllowed(boolean allowed) {
        when(quotaService.check(eq(PROFILE), eq("application_daily")))
                .thenReturn(new QuotaService.QuotaCheckResult(allowed, allowed ? 1 : 50, allowed ? 50 : 50, "applications"));
    }

    @Test
    void assistedModeHighConfidenceMatchAutoApplies() {
        mode("ASSISTED");
        quotaAllowed(true);

        var decision = service.decide(PROFILE, JOB, 90, "APPLY");

        assertThat(decision.decision()).isEqualTo("AUTO_APPLY");
        verify(db).update(contains("application_decisions"), any(Object[].class));
        verify(metrics).decisionRecorded("AUTO_APPLY");
    }

    @Test
    void assistedModeBelowTheAutoApplyThresholdQueuesForReview() {
        mode("ASSISTED");
        quotaAllowed(true);

        var decision = service.decide(PROFILE, JOB, 75, "APPLY");

        assertThat(decision.decision()).isEqualTo("NEEDS_REVIEW");
        assertThat(decision.reason()).contains("below auto-apply threshold");
        verify(metrics).decisionRecorded("NEEDS_REVIEW");
    }

    @Test
    void controlledAutoModeAutoAppliesEveryApplyMatch() {
        mode("CONTROLLED_AUTO");
        quotaAllowed(true);

        var decision = service.decide(PROFILE, JOB, 72, "APPLY");

        assertThat(decision.decision()).isEqualTo("AUTO_APPLY");
    }

    @Test
    void manualModeNeverAutoApplies() {
        mode("MANUAL");
        quotaAllowed(true);

        var decision = service.decide(PROFILE, JOB, 95, "APPLY");

        assertThat(decision.decision()).isEqualTo("NEEDS_REVIEW");
        assertThat(decision.reason()).contains("Manual mode");
    }

    @Test
    void anExhaustedDailyQuotaFallsToReviewRegardlessOfMode() {
        mode("CONTROLLED_AUTO");
        quotaAllowed(false);

        var decision = service.decide(PROFILE, JOB, 95, "APPLY");

        assertThat(decision.decision()).isEqualTo("NEEDS_REVIEW");
        assertThat(decision.reason()).contains("quota exceeded");
        verify(metrics).decisionRecorded("NEEDS_REVIEW");
    }

    @Test
    void skipAndReviewRecommendationsNeverAutoApply() {
        mode("CONTROLLED_AUTO");
        quotaAllowed(true);

        assertThat(service.decide(PROFILE, JOB, 30, "SKIP").decision()).isEqualTo("SKIP");
        assertThat(service.decide(PROFILE, JOB, 60, "REVIEW").decision()).isEqualTo("NEEDS_REVIEW");
    }

    @Test
    void aProfileWithoutPreferencesDefaultsToAssisted() {
        when(preferenceSets.findActiveByProfileId(PROFILE)).thenReturn(Optional.empty());
        quotaAllowed(true);

        assertThat(service.decide(PROFILE, JOB, 90, "APPLY").decision()).isEqualTo("AUTO_APPLY");
        assertThat(service.decide(PROFILE, JOB, 75, "APPLY").decision()).isEqualTo("NEEDS_REVIEW");
    }

    @Test
    void decisionPersistenceIsBestEffortAndNeverBlocksThePipeline() {
        mode("CONTROLLED_AUTO");
        quotaAllowed(true);
        when(db.update(contains("application_decisions"), any(Object[].class)))
                .thenThrow(new RuntimeException("db down"));

        var decision = service.decide(PROFILE, JOB, 90, "APPLY");

        // The pipeline still gets its decision even though recording failed:
        assertThat(decision.decision()).isEqualTo("AUTO_APPLY");
    }
}
