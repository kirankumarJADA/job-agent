package com.personal.jobagent.application;

import com.personal.jobagent.common.AutomationMetrics;
import com.personal.jobagent.common.QuotaService;
import com.personal.jobagent.preferences.PreferenceSetRecord;
import com.personal.jobagent.preferences.PreferenceSetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 5–7 decision engine tests.
 *
 * <p>Phase 5: mode + quota decide whether an APPLY-level match auto-applies
 * or queues for human review.
 *
 * <p>Phase 7: per-user auto-approval rules sit between the quota gate and
 * the mode thresholds. They can toggle automatic application creation and
 * raise or lower the score threshold — but they can never bypass quotas,
 * hard stops, or any safety gate.
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
        // Default: no per-user rule configured (Phase 5 defaults)
        when(db.queryForList(contains("user_approval_rules"), eq(PROFILE)))
                .thenReturn(Collections.emptyList());
        service = new ApplicationDecisionService(preferenceSets, quotaService, db, metrics);
    }

    private void mode(String applicationMode) {
        PreferenceSetRecord record = mock(PreferenceSetRecord.class);
        when(record.applicationMode()).thenReturn(applicationMode);
        when(preferenceSets.findActiveByProfileId(PROFILE)).thenReturn(Optional.of(record));
    }

    private void quotaAllowed(boolean allowed) {
        when(quotaService.check(eq(PROFILE), eq("application_daily")))
                .thenReturn(new QuotaService.QuotaCheckResult(
                        allowed, allowed ? 1 : 50, allowed ? 50 : 50, "applications"));
    }

    /** Simulates a persisted user_approval_rules row for PROFILE only. */
    private void ruleExists(boolean autoApproveEnabled, int minScore) {
        when(db.queryForList(contains("user_approval_rules"), eq(PROFILE)))
                .thenReturn(List.of(Map.of(
                        "auto_approve_enabled", autoApproveEnabled,
                        "min_score", minScore)));
    }

    // ── Phase 5 baseline tests (unchanged from original) ──────────────

    @Nested
    class Phase5Baseline {

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

            assertThat(service.decide(PROFILE, JOB, 30, "SKIP").decision())
                    .isEqualTo("SKIP");
            assertThat(service.decide(PROFILE, JOB, 60, "REVIEW").decision())
                    .isEqualTo("NEEDS_REVIEW");
        }

        @Test
        void aProfileWithoutPreferencesDefaultsToAssisted() {
            when(preferenceSets.findActiveByProfileId(PROFILE))
                    .thenReturn(Optional.empty());
            quotaAllowed(true);

            assertThat(service.decide(PROFILE, JOB, 90, "APPLY").decision())
                    .isEqualTo("AUTO_APPLY");
            assertThat(service.decide(PROFILE, JOB, 75, "APPLY").decision())
                    .isEqualTo("NEEDS_REVIEW");
        }

        @Test
        void decisionPersistenceIsBestEffortAndNeverBlocksThePipeline() {
            mode("CONTROLLED_AUTO");
            quotaAllowed(true);
            when(db.update(contains("application_decisions"), any(Object[].class)))
                    .thenThrow(new RuntimeException("db down"));

            var decision = service.decide(PROFILE, JOB, 90, "APPLY");

            assertThat(decision.decision()).isEqualTo("AUTO_APPLY");
        }
    }

    // ── Phase 7: per-user auto-approval rules ─────────────────────────

    @Nested
    class Phase7RuleEngine {

        // ── Rule opt-out: autoApproveEnabled=false ──

        @Test
        void disabledRuleForcesReviewInAssistedMode() {
            mode("ASSISTED");
            quotaAllowed(true);
            ruleExists(false, 85);

            var decision = service.decide(PROFILE, JOB, 95, "APPLY");

            assertThat(decision.decision()).isEqualTo("NEEDS_REVIEW");
            assertThat(decision.reason()).contains("disabled by user rule");
        }

        @Test
        void disabledRuleForcesReviewInControlledAutoMode() {
            mode("CONTROLLED_AUTO");
            quotaAllowed(true);
            ruleExists(false, 70);

            var decision = service.decide(PROFILE, JOB, 99, "APPLY");

            assertThat(decision.decision()).isEqualTo("NEEDS_REVIEW");
            assertThat(decision.reason()).contains("disabled by user rule");
        }

        @Test
        void disabledRuleDoesNotAffectManualMode() {
            mode("MANUAL");
            quotaAllowed(true);
            ruleExists(false, 85);

            var decision = service.decide(PROFILE, JOB, 95, "APPLY");

            assertThat(decision.decision()).isEqualTo("NEEDS_REVIEW");
            assertThat(decision.reason()).contains("Manual mode");
        }

        // ── Rule threshold in ASSISTED mode ──

        @Test
        void enabledRuleLowersAssistedThreshold() {
            mode("ASSISTED");
            quotaAllowed(true);
            ruleExists(true, 70);

            var decision = service.decide(PROFILE, JOB, 75, "APPLY");

            assertThat(decision.decision()).isEqualTo("AUTO_APPLY");
            assertThat(decision.reason()).contains("score 75 >= 70");
        }

        @Test
        void enabledRuleRaisesAssistedThreshold() {
            mode("ASSISTED");
            quotaAllowed(true);
            ruleExists(true, 95);

            var decision = service.decide(PROFILE, JOB, 90, "APPLY");

            assertThat(decision.decision()).isEqualTo("NEEDS_REVIEW");
            assertThat(decision.reason()).contains("below auto-apply threshold (95)");
        }

        // ── Rule threshold in CONTROLLED_AUTO mode ──

        @Test
        void enabledRuleEnforcesMinScoreInControlledAutoMode() {
            mode("CONTROLLED_AUTO");
            quotaAllowed(true);
            ruleExists(true, 80);

            var decision = service.decide(PROFILE, JOB, 75, "APPLY");

            assertThat(decision.decision()).isEqualTo("NEEDS_REVIEW");
            assertThat(decision.reason()).contains("below user threshold (80)");
        }

        @Test
        void controlledAutoScoreAboveRuleThresholdAutoApplies() {
            mode("CONTROLLED_AUTO");
            quotaAllowed(true);
            ruleExists(true, 80);

            var decision = service.decide(PROFILE, JOB, 85, "APPLY");

            assertThat(decision.decision()).isEqualTo("AUTO_APPLY");
        }

        // ── Boundary values ──

        @Test
        void scoreExactlyAtAssistedThresholdAutoApplies() {
            mode("ASSISTED");
            quotaAllowed(true);
            ruleExists(true, 80);

            var decision = service.decide(PROFILE, JOB, 80, "APPLY");

            assertThat(decision.decision()).isEqualTo("AUTO_APPLY");
        }

        @Test
        void scoreOnePointBelowAssistedThresholdQueuesForReview() {
            mode("ASSISTED");
            quotaAllowed(true);
            ruleExists(true, 80);

            var decision = service.decide(PROFILE, JOB, 79, "APPLY");

            assertThat(decision.decision()).isEqualTo("NEEDS_REVIEW");
        }

        @Test
        void scoreExactlyAtControlledAutoRuleThresholdAutoApplies() {
            mode("CONTROLLED_AUTO");
            quotaAllowed(true);
            ruleExists(true, 75);

            var decision = service.decide(PROFILE, JOB, 75, "APPLY");

            assertThat(decision.decision()).isEqualTo("AUTO_APPLY");
        }

        // ── Quotas cannot be bypassed ──

        @Test
        void quotaExhaustedOverridesEnabledRuleInAssistedMode() {
            mode("ASSISTED");
            quotaAllowed(false);
            ruleExists(true, 50);

            var decision = service.decide(PROFILE, JOB, 99, "APPLY");

            assertThat(decision.decision()).isEqualTo("NEEDS_REVIEW");
            assertThat(decision.reason()).contains("quota exceeded");
        }

        @Test
        void quotaExhaustedOverridesEnabledRuleInControlledAutoMode() {
            mode("CONTROLLED_AUTO");
            quotaAllowed(false);
            ruleExists(true, 50);

            var decision = service.decide(PROFILE, JOB, 99, "APPLY");

            assertThat(decision.decision()).isEqualTo("NEEDS_REVIEW");
            assertThat(decision.reason()).contains("quota exceeded");
        }

        // ── Missing or failed rule loading fails safe ──

        @Test
        void missingRuleFallsBackToPhase5DefaultsInAssistedMode() {
            mode("ASSISTED");
            quotaAllowed(true);
            // No ruleExists() — returns empty list (setUp default)

            // Phase 5 default assisted threshold = 85
            assertThat(service.decide(PROFILE, JOB, 90, "APPLY").decision())
                    .isEqualTo("AUTO_APPLY");
            assertThat(service.decide(PROFILE, JOB, 80, "APPLY").decision())
                    .isEqualTo("NEEDS_REVIEW");
        }

        @Test
        void missingRuleInControlledAutoStillAutoApplies() {
            mode("CONTROLLED_AUTO");
            quotaAllowed(true);

            var decision = service.decide(PROFILE, JOB, 72, "APPLY");

            assertThat(decision.decision()).isEqualTo("AUTO_APPLY");
        }

        @Test
        void ruleLoadExceptionFailsSafeToPhase5Defaults() {
            mode("ASSISTED");
            quotaAllowed(true);
            when(db.queryForList(contains("user_approval_rules"), eq(PROFILE)))
                    .thenThrow(new RuntimeException("connection lost"));

            assertThat(service.decide(PROFILE, JOB, 90, "APPLY").decision())
                    .isEqualTo("AUTO_APPLY");
            assertThat(service.decide(PROFILE, JOB, 80, "APPLY").decision())
                    .isEqualTo("NEEDS_REVIEW");
        }

        // ── Owner isolation ──

        @Test
        void differentProfilesCannotAccessEachOthersRules() {
            UUID otherProfile = UUID.randomUUID();
            ruleExists(true, 50);  // Only matches PROFILE's UUID

            assertThat(service.ruleFor(otherProfile)).isNull();
        }

        // ── Manual mode is always immune to rules ──

        @Test
        void manualModeNeverAutoAppliesRegardlessOfEnabledRule() {
            mode("MANUAL");
            quotaAllowed(true);
            ruleExists(true, 1);  // Most permissive possible rule

            var decision = service.decide(PROFILE, JOB, 100, "APPLY");

            assertThat(decision.decision()).isEqualTo("NEEDS_REVIEW");
            assertThat(decision.reason()).contains("Manual mode");
        }

        // ── Skip/Review recommendations are still immune ──

        @Test
        void skipRecommendationUnaffectedByRule() {
            mode("CONTROLLED_AUTO");
            quotaAllowed(true);
            ruleExists(true, 1);

            assertThat(service.decide(PROFILE, JOB, 30, "SKIP").decision())
                    .isEqualTo("SKIP");
        }

        @Test
        void reviewRecommendationUnaffectedByRule() {
            mode("CONTROLLED_AUTO");
            quotaAllowed(true);
            ruleExists(true, 1);

            assertThat(service.decide(PROFILE, JOB, 60, "REVIEW").decision())
                    .isEqualTo("NEEDS_REVIEW");
        }

        // ── Metrics recorded correctly with rules ──

        @Test
        void metricsRecordedForRuleBasedDecision() {
            mode("CONTROLLED_AUTO");
            quotaAllowed(true);
            ruleExists(true, 90);

            service.decide(PROFILE, JOB, 80, "APPLY");

            verify(metrics).decisionRecorded("NEEDS_REVIEW");
        }
    }

    // ── Phase 7: saveRule validation ───────────────────────────────────

    @Nested
    class Phase7SaveRule {

        @Test
        void saveRuleRejectsScoreBelowOne() {
            assertThatThrownBy(() -> service.saveRule(PROFILE, true, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("between 1 and 100");
        }

        @Test
        void saveRuleRejectsScoreAboveHundred() {
            assertThatThrownBy(() -> service.saveRule(PROFILE, true, 101))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("between 1 and 100");
        }

        @Test
        void saveRuleAcceptsBoundaryValueOne() {
            var rule = service.saveRule(PROFILE, true, 1);
            assertThat(rule.minScore()).isEqualTo(1);
            assertThat(rule.autoApproveEnabled()).isTrue();
        }

        @Test
        void saveRuleAcceptsBoundaryValueHundred() {
            var rule = service.saveRule(PROFILE, false, 100);
            assertThat(rule.minScore()).isEqualTo(100);
            assertThat(rule.autoApproveEnabled()).isFalse();
        }

        @Test
        void saveRuleDefaultsToExistingValuesWhenFieldsAreNull() {
            ruleExists(true, 90);

            var rule = service.saveRule(PROFILE, null, null);

            assertThat(rule.autoApproveEnabled()).isTrue();
            assertThat(rule.minScore()).isEqualTo(90);
        }

        @Test
        void saveRuleDefaultsToPhase5WhenNoExistingRule() {
            var rule = service.saveRule(PROFILE, null, null);

            assertThat(rule.autoApproveEnabled()).isFalse();
            assertThat(rule.minScore())
                    .isEqualTo(ApplicationDecisionService.HIGH_CONFIDENCE_THRESHOLD);
        }

        @Test
        void saveRuleUpsertsViaJdbc() {
            service.saveRule(PROFILE, true, 75);

            verify(db).update(contains("user_approval_rules"), any(Object[].class));
        }
    }

    // ── Phase 7: ruleFor (read path) ──────────────────────────────────

    @Nested
    class Phase7RuleFor {

        @Test
        void ruleForReturnsNullWhenNoRowExists() {
            assertThat(service.ruleFor(PROFILE)).isNull();
        }

        @Test
        void ruleForReturnsSavedRule() {
            ruleExists(true, 72);

            var rule = service.ruleFor(PROFILE);

            assertThat(rule).isNotNull();
            assertThat(rule.autoApproveEnabled()).isTrue();
            assertThat(rule.minScore()).isEqualTo(72);
        }
    }

    // ── Idempotency ───────────────────────────────────────────────────

    @Nested
    class Idempotency {

        @Test
        void repeatedDecideCallsReturnConsistentResult() {
            mode("ASSISTED");
            quotaAllowed(true);
            ruleExists(true, 80);

            var first = service.decide(PROFILE, JOB, 85, "APPLY");
            var second = service.decide(PROFILE, JOB, 85, "APPLY");

            assertThat(first.decision()).isEqualTo(second.decision());
            assertThat(first.matchScore()).isEqualTo(second.matchScore());
        }

        @Test
        void upsertGuardPreservesTerminalDecision() {
            mode("CONTROLLED_AUTO");
            quotaAllowed(true);
            when(db.update(contains("application_decisions"), any(Object[].class)))
                    .thenReturn(0);
            when(db.queryForList(
                    contains("select decision, reason"),
                    any(UUID.class), any(UUID.class)))
                    .thenReturn(List.of(Map.of(
                            "decision", "APPROVED",
                            "reason", "Manually approved")));

            var decision = service.decide(PROFILE, JOB, 90, "APPLY");

            assertThat(decision.decision()).isEqualTo("APPROVED");
        }
    }
}
