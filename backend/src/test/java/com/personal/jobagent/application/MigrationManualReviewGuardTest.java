package com.personal.jobagent.application;

import com.personal.jobagent.common.AutomationMetrics;
import com.personal.jobagent.common.QuotaService;
import com.personal.jobagent.preferences.PreferenceSetRecord;
import com.personal.jobagent.preferences.PreferenceSetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Database migration Phase 2: the manual-review-only guard
 * ({@code app.migration.manual-review-only}). While it is on, no APPLY match is
 * applied automatically, in any mode and whatever the approval rule says; no
 * quota is reserved and the rule is not read; a human decision already on
 * record is kept. With it off, nothing changes.
 */
class MigrationManualReviewGuardTest {

    private static final UUID PROFILE = UUID.randomUUID();
    private static final UUID JOB = UUID.randomUUID();
    private static final LocalDate PERIOD = LocalDate.of(2026, 11, 11);

    private final PreferenceSetRepository preferenceSets = mock(PreferenceSetRepository.class);
    private final QuotaService quotaService = mock(QuotaService.class);
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final AutomationMetrics metrics = mock(AutomationMetrics.class);
    private final ApprovalRuleHealthMonitor health = mock(ApprovalRuleHealthMonitor.class);
    /** Arguments of every application_decisions write, as JdbcTemplate received them. */
    private final List<List<Object>> decisionWrites = new ArrayList<>();
    private ApplicationDecisionService service;

    @BeforeEach
    void setUp() {
        when(db.update(anyString(), any(Object[].class))).thenReturn(1);
        when(db.update(contains("application_decisions"), any(Object[].class))).thenAnswer(inv -> {
            decisionWrites.add(Arrays.asList(inv.getArguments()));
            return 1;
        });
        when(db.queryForList(contains("user_approval_rules"), eq(PROFILE))).thenReturn(Collections.emptyList());
        when(quotaService.tryReserve(eq(PROFILE), eq("application_daily"), eq(1.0)))
                .thenReturn(new QuotaService.ReservationResult(true, 1, 25, PERIOD));
        service = new ApplicationDecisionService(preferenceSets, quotaService, db, metrics, health);
        guard(true);
    }

    private void guard(boolean on) {
        ReflectionTestUtils.setField(service, "manualReviewOnly", on);
    }

    private void mode(String applicationMode) {
        PreferenceSetRecord record = mock(PreferenceSetRecord.class);
        when(record.applicationMode()).thenReturn(applicationMode);
        when(preferenceSets.findActiveByProfileId(PROFILE)).thenReturn(Optional.of(record));
    }

    private void rule(boolean autoApproveEnabled, int minScore) {
        when(db.queryForList(contains("user_approval_rules"), eq(PROFILE)))
                .thenReturn(List.of(Map.of("auto_approve_enabled", autoApproveEnabled, "min_score", minScore)));
    }

    /** Held for human review by the guard: recorded as such, nothing reserved, the rule never read. */
    private void assertHeldForReview(ApplicationDecisionService.DecisionResult result) {
        assertThat(result.decision()).isEqualTo("NEEDS_REVIEW");
        assertThat(result.reason()).isEqualTo(ApplicationDecisionService.MIGRATION_GUARD_REASON);
        assertThat(result.quotaPeriodStart()).isNull();
        assertThat(decisionWrites).hasSize(1);
        assertThat(decisionWrites.get(0)).contains("NEEDS_REVIEW", ApplicationDecisionService.MIGRATION_GUARD_REASON);
        assertThat(decisionWrites.get(0)).doesNotContain("AUTO_APPLY");
        verify(quotaService, never()).tryReserve(any(), anyString(), anyDouble());
        verify(quotaService, never()).tryReserve(any(), anyString(), anyDouble(), anyDouble());
        verify(db, never()).queryForList(contains("user_approval_rules"), any(Object[].class));
        verifyNoInteractions(health);
    }

    @Test
    void theGuardIsReported() {
        assertThat(service.isManualReviewOnly()).isTrue();
        guard(false);
        assertThat(service.isManualReviewOnly()).isFalse();
    }

    @Test
    void assistedWithoutARuleAtAHighScoreIsHeld() {
        // Production's case: ASSISTED, no approval rule, scores 88 and above.
        mode("ASSISTED");
        assertHeldForReview(service.decide(PROFILE, JOB, 91, "APPLY"));
    }

    @Test
    void aProfileWithoutPreferencesIsHeld() {
        // No preference set at all: the effective mode is ASSISTED.
        when(preferenceSets.findActiveByProfileId(PROFILE)).thenReturn(Optional.empty());
        assertHeldForReview(service.decide(PROFILE, JOB, 100, "APPLY"));
    }

    @Test
    void aBlankModeIsHeld() {
        mode("  ");
        assertHeldForReview(service.decide(PROFILE, JOB, 99, "APPLY"));
    }

    @Test
    void assistedWithAnEnabledRuleThatWouldLowerTheFloorIsHeld() {
        mode("ASSISTED");
        rule(true, 70);
        assertHeldForReview(service.decide(PROFILE, JOB, 75, "APPLY"));
    }

    @Test
    void assistedWithADisabledRuleIsHeld() {
        mode("ASSISTED");
        rule(false, 85);
        assertHeldForReview(service.decide(PROFILE, JOB, 95, "APPLY"));
    }

    @Test
    void controlledAutoWithAnEnabledRuleIsHeld() {
        mode("CONTROLLED_AUTO");
        rule(true, 80);
        assertHeldForReview(service.decide(PROFILE, JOB, 95, "APPLY"));
    }

    @Test
    void controlledAutoWithoutARuleIsHeld() {
        mode("CONTROLLED_AUTO");
        assertHeldForReview(service.decide(PROFILE, JOB, 95, "APPLY"));
    }

    @Test
    void manualModeIsHeld() {
        mode("MANUAL");
        assertHeldForReview(service.decide(PROFILE, JOB, 95, "APPLY"));
    }

    @Test
    void aRuleThatCannotBeReadIsNotEvenReadUnderTheGuard() {
        mode("ASSISTED");
        when(db.queryForList(contains("user_approval_rules"), eq(PROFILE)))
                .thenThrow(new IllegalStateException("database unavailable"));
        assertHeldForReview(service.decide(PROFILE, JOB, 95, "APPLY"));
    }

    @Test
    void aHumanDecisionAlreadyOnRecordIsKept() {
        // record()'s upsert refuses to overwrite a terminal decision; decide()
        // returns the persisted one, which the event handler then ignores.
        mode("ASSISTED");
        when(db.update(contains("application_decisions"), any(Object[].class))).thenReturn(0);
        when(db.queryForList(contains("select decision, reason"), any(UUID.class), any(UUID.class)))
                .thenReturn(List.of(Map.of("decision", "APPROVED", "reason", "Manually approved")));

        var result = service.decide(PROFILE, JOB, 95, "APPLY");

        assertThat(result.decision()).isEqualTo("APPROVED");
        assertThat(result.quotaPeriodStart()).isNull();
        verify(quotaService, never()).tryReserve(any(), anyString(), anyDouble());
    }

    @Test
    void skipAndReviewRecommendationsAreUnchanged() {
        mode("ASSISTED");
        assertThat(service.decide(PROFILE, JOB, 30, "SKIP").decision()).isEqualTo("SKIP");
        var review = service.decide(PROFILE, JOB, 60, "REVIEW");
        assertThat(review.decision()).isEqualTo("NEEDS_REVIEW");
        assertThat(review.reason()).isEqualTo("Score in review range");
        verify(quotaService, never()).tryReserve(any(), anyString(), anyDouble());
    }

    @Test
    void withTheGuardOffAssistedStillAutoAppliesAtEightyFive() {
        guard(false);
        mode("ASSISTED");

        var result = service.decide(PROFILE, JOB, 85, "APPLY");

        assertThat(result.decision()).isEqualTo("AUTO_APPLY");
        assertThat(result.quotaPeriodStart()).isEqualTo(PERIOD);
        verify(quotaService).tryReserve(eq(PROFILE), eq("application_daily"), eq(1.0));
    }

    @Test
    void withTheGuardOffAControlledAutoRuleStillAutoApplies() {
        guard(false);
        mode("CONTROLLED_AUTO");
        rule(true, 80);

        assertThat(service.decide(PROFILE, JOB, 95, "APPLY").decision()).isEqualTo("AUTO_APPLY");
    }

    // ── Scores around the floor of 85 (ASSISTED's default, and a rule set at 85) ──

    @ParameterizedTest(name = "ASSISTED without a rule, score {0}: held")
    @ValueSource(ints = {84, 85, 86})
    void underTheGuardAssistedWithoutARuleIsHeldAroundTheFloor(int score) {
        mode("ASSISTED");
        assertHeldForReview(service.decide(PROFILE, JOB, score, "APPLY"));
    }

    @ParameterizedTest(name = "ASSISTED without a rule, guard off, score {0}: {1}")
    @CsvSource({"84, NEEDS_REVIEW", "85, AUTO_APPLY", "86, AUTO_APPLY"})
    void withTheGuardOffAssistedWithoutARuleAppliesFromEightyFive(int score, String expected) {
        guard(false);
        mode("ASSISTED");

        var result = service.decide(PROFILE, JOB, score, "APPLY");

        assertThat(result.decision()).isEqualTo(expected);
        assertThat(result.reason()).isNotEqualTo(ApplicationDecisionService.MIGRATION_GUARD_REASON);
        if ("AUTO_APPLY".equals(expected)) {
            assertThat(result.quotaPeriodStart()).isEqualTo(PERIOD);
            verify(quotaService).tryReserve(eq(PROFILE), eq("application_daily"), eq(1.0));
        } else {
            verify(quotaService, never()).tryReserve(any(), anyString(), anyDouble());
        }
    }

    @ParameterizedTest(name = "CONTROLLED_AUTO with a rule at 85, score {0}: held")
    @ValueSource(ints = {84, 85, 86})
    void underTheGuardAControlledAutoRuleAtEightyFiveIsHeldAroundTheFloor(int score) {
        mode("CONTROLLED_AUTO");
        rule(true, 85);
        assertHeldForReview(service.decide(PROFILE, JOB, score, "APPLY"));
    }

    @ParameterizedTest(name = "CONTROLLED_AUTO with a rule at 85, guard off, score {0}: {1}")
    @CsvSource({"84, NEEDS_REVIEW", "85, AUTO_APPLY", "86, AUTO_APPLY"})
    void withTheGuardOffAControlledAutoRuleAtEightyFiveIsTheFloor(int score, String expected) {
        guard(false);
        mode("CONTROLLED_AUTO");
        rule(true, 85);

        assertThat(service.decide(PROFILE, JOB, score, "APPLY").decision()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "MANUAL, score {0}, guard on {1}: review")
    @CsvSource({"84, true", "85, true", "86, true", "84, false", "85, false", "86, false"})
    void manualModeNeverAppliesAroundTheFloor(int score, boolean guardOn) {
        guard(guardOn);
        mode("MANUAL");

        var result = service.decide(PROFILE, JOB, score, "APPLY");

        assertThat(result.decision()).isEqualTo("NEEDS_REVIEW");
        assertThat(result.reason()).isEqualTo(guardOn ? ApplicationDecisionService.MIGRATION_GUARD_REASON
                : "Manual mode requires human review");
        verify(quotaService, never()).tryReserve(any(), anyString(), anyDouble());
    }
}
