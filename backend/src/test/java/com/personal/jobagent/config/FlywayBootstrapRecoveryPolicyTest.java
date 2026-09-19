package com.personal.jobagent.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayBootstrapRecoveryPolicyTest {
    private static final List<Integer> CHAIN = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19);

    private FlywayBootstrapRecoveryPolicy.SchemaState state(boolean historyExists, int historyRows,
            int successfulRows, int baselineRows, boolean usersExists, Set<String> applicationObjects,
            Set<String> platformObjects, int failedRows, boolean v0Baseline, List<Integer> applied,
            List<Integer> expected) {
        return new FlywayBootstrapRecoveryPolicy.SchemaState(historyExists, historyRows, successfulRows,
                baselineRows, usersExists, applicationObjects, platformObjects, List.of(),
                failedRows, v0Baseline, applied, expected);
    }

    // A. Normal migrated DB, no baseline.
    @Test
    void normalMigratedDatabaseWithoutBaselineClassifiesMigrated() {
        var state = state(true, 19, 19, 0, true, Set.of("public.users"), Set.of(), 0, false, CHAIN, CHAIN);
        assertThat(FlywayBootstrapRecoveryPolicy.classify(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Classification.MIGRATED);
        assertThat(FlywayBootstrapRecoveryPolicy.decide(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Action.MIGRATE);
    }

    // B. The exact current Render state: one version-0 baseline from our
    // recovery flow followed by the complete successful V001..V019 chain.
    @Test
    void baselineAtZeroWithFullChainClassifiesMigrated() {
        var state = state(true, 20, 20, 1, true, Set.of("public.users"), Set.of(), 0, true, CHAIN, CHAIN);
        assertThat(FlywayBootstrapRecoveryPolicy.classify(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Classification.MIGRATED);
        assertThat(FlywayBootstrapRecoveryPolicy.decide(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Action.MIGRATE);
    }

    // C. Baseline at zero whose chain never ran — not healthy.
    @Test
    void baselineAtZeroOnlyIsNotMigrated() {
        var state = state(true, 1, 1, 1, false, Set.of(), Set.of(), 0, true, List.of(), CHAIN);
        assertThat(FlywayBootstrapRecoveryPolicy.classify(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Classification.INVALID_BASELINE);
        assertThat(FlywayBootstrapRecoveryPolicy.decide(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Action.DROP_HISTORY_BASELINE_AT_ZERO_THEN_MIGRATE);
    }

    // D. Baseline at zero with only part of the chain — external
    // interference with the recovery flow; fails closed, never "healthy".
    @Test
    void baselineAtZeroWithIncompleteChainFailsClosed() {
        var state = state(true, 11, 11, 1, true, Set.of("public.users"), Set.of(), 0, true,
                CHAIN.subList(0, 10), CHAIN);
        assertThat(FlywayBootstrapRecoveryPolicy.decide(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Action.FAIL_CLOSED);
    }

    // E. Failed migration rows are never healthy history.
    @Test
    void failedMigrationRowsFailClosed() {
        var state = state(true, 20, 19, 1, true, Set.of("public.users"), Set.of(), 1, true, CHAIN, CHAIN);
        assertThat(FlywayBootstrapRecoveryPolicy.decide(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Action.FAIL_CLOSED);
    }

    // F. Fresh DB migrates normally.
    @Test
    void completelyFreshDatabaseUsesNormalMigrationWithoutBaseline() {
        var state = state(false, 0, 0, 0, false, Set.of(), Set.of(), 0, false, List.of(), CHAIN);
        assertThat(FlywayBootstrapRecoveryPolicy.classify(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Classification.FRESH);
        assertThat(FlywayBootstrapRecoveryPolicy.decide(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Action.MIGRATE);
    }

    // G. Legacy lone version-1 baseline is recovered with the full chain.
    @Test
    void invalidLoneBaselineHistoryIsDroppedAndRecreatedAtZero() {
        var state = state(true, 1, 1, 1, false, Set.of(), Set.of(), 0, false, List.of(), CHAIN);
        assertThat(FlywayBootstrapRecoveryPolicy.classify(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Classification.INVALID_BASELINE);
        assertThat(FlywayBootstrapRecoveryPolicy.decide(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Action.DROP_HISTORY_BASELINE_AT_ZERO_THEN_MIGRATE);
    }

    // H. Arbitrary populated database with no valid history fails closed.
    @Test
    void unexpectedApplicationTableWithoutHistoryFailsClosed() {
        var state = state(false, 0, 0, 0, false, Set.of("public.customer_data"), Set.of(), 0, false, List.of(), CHAIN);
        assertThat(FlywayBootstrapRecoveryPolicy.classify(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Classification.UNEXPECTED);
        assertThat(FlywayBootstrapRecoveryPolicy.decide(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Action.FAIL_CLOSED);
    }

    // Baselines other than version 0 are never accepted as healthy.
    @Test
    void baselineOtherThanZeroIsNeverHealthy() {
        var state = state(true, 20, 20, 1, true, Set.of("public.users"), Set.of(), 0, false, CHAIN, CHAIN);
        assertThat(FlywayBootstrapRecoveryPolicy.decide(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Action.FAIL_CLOSED);
    }

    // Duplicate versions break the unbroken-prefix invariant.
    @Test
    void duplicateAppliedVersionsBreakTheChain() {
        var applied = new java.util.ArrayList<Integer>(CHAIN);
        applied.set(2, 2); // 1,2,2,4,... — duplicate, missing 3
        var state = state(true, 20, 20, 1, true, Set.of("public.users"), Set.of(), 0, true, applied, CHAIN);
        assertThat(FlywayBootstrapRecoveryPolicy.decide(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Action.FAIL_CLOSED);
    }

    // Skipped versions break the unbroken-prefix invariant.
    @Test
    void skippedAppliedVersionsBreakTheChain() {
        var state = state(true, 19, 19, 1, true, Set.of("public.users"), Set.of(), 0, true,
                CHAIN.stream().filter(v -> v != 7).toList(), CHAIN);
        assertThat(FlywayBootstrapRecoveryPolicy.decide(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Action.FAIL_CLOSED);
    }

    // Migrated DB without a baseline may trail the latest script (pending
    // upgrade) and migrates on as long as the history is clean.
    @Test
    void migratedDatabaseWithoutBaselineMayTrailLatestScript() {
        var state = state(true, 17, 17, 0, true, Set.of("public.users"), Set.of(), 0, false,
                CHAIN.subList(0, 17), CHAIN);
        assertThat(FlywayBootstrapRecoveryPolicy.decide(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Action.MIGRATE);
    }

    // Baseline-at-zero trailing chain is NOT a pending upgrade: recovery
    // always applies the complete chain in one startup, so a shorter chain
    // with a baseline fails closed even without other anomalies.
    @Test
    void baselineTrailingChainIsNotAPendingUpgrade() {
        var state = state(true, 5, 5, 1, true, Set.of("public.users"), Set.of(), 0, true, CHAIN.subList(0, 4), CHAIN);
        assertThat(FlywayBootstrapRecoveryPolicy.decide(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Action.FAIL_CLOSED);
    }

    // Enumeration failure of the expected chain must fail closed for
    // baseline states, never silently accept an unverifiable history.
    @Test
    void unverifiableExpectedChainFailsClosedForBaselineStates() {
        var state = state(true, 20, 20, 1, true, Set.of("public.users"), Set.of(), 0, true, CHAIN, List.of());
        assertThat(FlywayBootstrapRecoveryPolicy.decide(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Action.FAIL_CLOSED);
    }

    // The normal migrated database (no baseline) may still migrate on when
    // the expected chain cannot be enumerated: the history itself is clean.
    @Test
    void migratedDatabaseWithoutBaselineSurvivesUnverifiableChain() {
        var state = state(true, 19, 19, 0, true, Set.of("public.users"), Set.of(), 0, false, CHAIN, List.of());
        assertThat(FlywayBootstrapRecoveryPolicy.decide(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Action.MIGRATE);
    }

    // Platform-only bootstrap state still baselines at zero.
    @Test
    void productionRegressionNoHistoryNonEmptyPublicExtensionOwnedPlatformObjectBaselinesAtZero() {
        var state = state(false, 0, 0, 0, false, Set.of(), Set.of("public.spatial_ref_sys"), 0, false, List.of(), CHAIN);
        assertThat(FlywayBootstrapRecoveryPolicy.classify(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Classification.PLATFORM_BOOTSTRAP_ONLY);
        assertThat(FlywayBootstrapRecoveryPolicy.decide(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Action.BASELINE_AT_ZERO_THEN_MIGRATE);
    }

    // A platform-owned bootstrap function must classify as platform, not fail closed.
    @Test
    void platformOwnedFunctionIsPlatformBootstrapNotApplicationData() {
        var state = state(false, 0, 0, 0, false, Set.of(), Set.of("public.some_platform_function"), 0, false,
                List.of(), CHAIN);
        assertThat(FlywayBootstrapRecoveryPolicy.classify(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Classification.PLATFORM_BOOTSTRAP_ONLY);
        assertThat(FlywayBootstrapRecoveryPolicy.decide(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Action.BASELINE_AT_ZERO_THEN_MIGRATE);
    }

    @Test
    void knownPostgisObjectWithoutDependRowIsTreatedAsPlatformObject() {
        assertThat(FlywayBootstrapRecoveryPolicy.isKnownPostgisObject("public.spatial_ref_sys")).isTrue();
        assertThat(FlywayBootstrapRecoveryPolicy.isKnownPostgisObject("public.customer_data")).isFalse();
    }

    // Application table under a lone baseline is never "recovered" by dropping history.
    @Test
    void applicationTableWithLoneBaselineIsNeverDropped() {
        var state = state(true, 1, 1, 1, true, Set.of("public.users"), Set.of(), 0, false, List.of(), CHAIN);
        assertThat(FlywayBootstrapRecoveryPolicy.decide(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Action.FAIL_CLOSED);
    }

    // A history table with zero rows is an unexpected shape.
    @Test
    void unexpectedHistoryShapeFailsClosed() {
        var state = state(true, 0, 0, 0, false, Set.of(), Set.of(), 0, false, List.of(), CHAIN);
        assertThat(FlywayBootstrapRecoveryPolicy.decide(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Action.FAIL_CLOSED);
    }
}
