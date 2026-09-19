package com.personal.jobagent.config;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayBootstrapRecoveryPolicyTest {
    @Test
    void completelyFreshDatabaseUsesNormalMigrationWithoutBaseline() {
        var state = new FlywayBootstrapRecoveryPolicy.SchemaState(false, 0, 0, 0, false, Set.of(), Set.of());
        assertThat(FlywayBootstrapRecoveryPolicy.classify(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Classification.FRESH);
        assertThat(FlywayBootstrapRecoveryPolicy.decide(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Action.MIGRATE);
    }

    @Test
    void productionRegressionNoHistoryNonEmptyPublicExtensionOwnedPlatformObjectBaselinesAtZero() {
        // Exact production condition: flyway_schema_history absent, public
        // non-empty, the only relation is an extension-owned platform object.
        var state = new FlywayBootstrapRecoveryPolicy.SchemaState(
                false, 0, 0, 0, false, Set.of(), Set.of("public.spatial_ref_sys"));
        assertThat(FlywayBootstrapRecoveryPolicy.classify(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Classification.PLATFORM_BOOTSTRAP_ONLY);
        assertThat(FlywayBootstrapRecoveryPolicy.decide(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Action.BASELINE_AT_ZERO_THEN_MIGRATE);
    }

    @Test
    void knownPostgisObjectWithoutDependRowIsTreatedAsPlatformObject() {
        assertThat(FlywayBootstrapRecoveryPolicy.isKnownPostgisObject("public.spatial_ref_sys")).isTrue();
        assertThat(FlywayBootstrapRecoveryPolicy.isKnownPostgisObject("public.customer_data")).isFalse();
        var state = new FlywayBootstrapRecoveryPolicy.SchemaState(
                false, 0, 0, 0, false, Set.of(), Set.of("public.spatial_ref_sys"));
        assertThat(FlywayBootstrapRecoveryPolicy.decide(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Action.BASELINE_AT_ZERO_THEN_MIGRATE);
    }

    @Test
    void invalidLoneBaselineHistoryIsDroppedAndRecreatedAtZero() {
        var state = new FlywayBootstrapRecoveryPolicy.SchemaState(true, 1, 1, 1, false, Set.of(), Set.of());
        assertThat(FlywayBootstrapRecoveryPolicy.classify(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Classification.INVALID_BASELINE);
        assertThat(FlywayBootstrapRecoveryPolicy.decide(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Action.DROP_HISTORY_BASELINE_AT_ZERO_THEN_MIGRATE);
    }

    @Test
    void alreadyMigratedDatabaseOnlyMigratesPendingVersions() {
        var state = new FlywayBootstrapRecoveryPolicy.SchemaState(
                true, 19, 19, 0, true, Set.of("public.users"), Set.of("public.spatial_ref_sys"));
        assertThat(FlywayBootstrapRecoveryPolicy.classify(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Classification.MIGRATED);
        assertThat(FlywayBootstrapRecoveryPolicy.decide(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Action.MIGRATE);
    }

    @Test
    void unexpectedApplicationTableWithoutHistoryFailsClosed() {
        var state = new FlywayBootstrapRecoveryPolicy.SchemaState(
                false, 0, 0, 0, false, Set.of("public.customer_data"), Set.of());
        assertThat(FlywayBootstrapRecoveryPolicy.classify(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Classification.UNEXPECTED);
        assertThat(FlywayBootstrapRecoveryPolicy.decide(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Action.FAIL_CLOSED);
    }

    @Test
    void applicationTableWithLoneBaselineIsNeverDropped() {
        var state = new FlywayBootstrapRecoveryPolicy.SchemaState(
                true, 1, 1, 1, true, Set.of("public.users"), Set.of());
        assertThat(FlywayBootstrapRecoveryPolicy.classify(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Classification.UNEXPECTED);
        assertThat(FlywayBootstrapRecoveryPolicy.decide(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Action.FAIL_CLOSED);
    }

    @Test
    void unexpectedHistoryShapeFailsClosed() {
        var state = new FlywayBootstrapRecoveryPolicy.SchemaState(true, 0, 0, 0, false, Set.of(), Set.of());
        assertThat(FlywayBootstrapRecoveryPolicy.classify(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Classification.UNEXPECTED);
        assertThat(FlywayBootstrapRecoveryPolicy.decide(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Action.FAIL_CLOSED);
    }
}
