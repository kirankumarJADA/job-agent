package com.personal.jobagent.config;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayBootstrapRecoveryPolicyTest {
    @Test
    void completelyFreshDatabaseUsesNormalMigrationWithoutBaseline() {
        var state = new FlywayBootstrapRecoveryPolicy.SchemaState(false, 0, 0, 0, false, Set.of());
        assertThat(FlywayBootstrapRecoveryPolicy.decide(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Action.MIGRATE);
    }

    @Test
    void noHistoryKnownSupabaseExtensionObjectsBaselinesAtZeroThenMigrates() {
        var state = new FlywayBootstrapRecoveryPolicy.SchemaState(
                false, 0, 0, 0, false, Set.of("public.spatial_ref_sys"));
        assertThat(FlywayBootstrapRecoveryPolicy.decide(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Action.BASELINE_ZERO_THEN_MIGRATE);
    }

    @Test
    void invalidLoneBaselineHistoryIsDroppedAndRecreatedAtZero() {
        var state = new FlywayBootstrapRecoveryPolicy.SchemaState(true, 1, 1, 1, false, Set.of());
        assertThat(FlywayBootstrapRecoveryPolicy.decide(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Action.DROP_HISTORY_BASELINE_ZERO_THEN_MIGRATE);
    }

    @Test
    void alreadyMigratedDatabaseOnlyMigratesPendingVersions() {
        var state = new FlywayBootstrapRecoveryPolicy.SchemaState(
                true, 19, 19, 0, true, Set.of("public.users"));
        assertThat(FlywayBootstrapRecoveryPolicy.decide(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Action.MIGRATE);
    }

    @Test
    void unexpectedPublicTableWithoutHistoryFailsClosed() {
        var state = new FlywayBootstrapRecoveryPolicy.SchemaState(
                false, 0, 0, 0, false, Set.of("public.customer_data"));
        assertThat(FlywayBootstrapRecoveryPolicy.decide(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Action.FAIL_CLOSED);
    }

    @Test
    void applicationTableWithLoneBaselineIsNeverDropped() {
        var state = new FlywayBootstrapRecoveryPolicy.SchemaState(
                true, 1, 1, 1, true, Set.of("public.users"));
        assertThat(FlywayBootstrapRecoveryPolicy.decide(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Action.FAIL_CLOSED);
    }

    @Test
    void unexpectedHistoryShapeFailsClosed() {
        var state = new FlywayBootstrapRecoveryPolicy.SchemaState(
                true, 0, 0, 0, false, Set.of());
        assertThat(FlywayBootstrapRecoveryPolicy.decide(state))
                .isEqualTo(FlywayBootstrapRecoveryPolicy.Action.FAIL_CLOSED);
    }
}
