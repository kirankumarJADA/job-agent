package com.personal.jobagent.config;

import java.util.Set;

public final class FlywayBootstrapRecoveryPolicy {
    private FlywayBootstrapRecoveryPolicy() {}

    public enum Action {
        MIGRATE,
        BASELINE_ZERO_THEN_MIGRATE,
        DROP_HISTORY_BASELINE_ZERO_THEN_MIGRATE,
        FAIL_CLOSED
    }

    /** Objects allowed before the application has ever migrated. */
    private static final Set<String> KNOWN_BOOTSTRAP_OBJECTS = Set.of(
            "public.spatial_ref_sys",
            "public.geography_columns",
            "public.geometry_columns",
            "public.raster_columns",
            "public.raster_overviews"
    );

    public record SchemaState(boolean historyExists, int historyRows, int successfulRows, int baselineRows,
                              boolean usersExists, Set<String> nonExtensionObjects) {
        public SchemaState {
            nonExtensionObjects = nonExtensionObjects == null ? Set.of() : Set.copyOf(nonExtensionObjects);
        }

        public boolean physicallyEmpty() {
            return nonExtensionObjects.isEmpty() && !usersExists;
        }

        public boolean knownBootstrapOnly() {
            return !usersExists && nonExtensionObjects.stream().allMatch(KNOWN_BOOTSTRAP_OBJECTS::contains);
        }
    }

    public static Action decide(SchemaState state) {
        if (state.historyExists()) {
            if (state.historyRows() == 1 && state.successfulRows() == 1 && state.baselineRows() == 1
                    && state.knownBootstrapOnly()) {
                return Action.DROP_HISTORY_BASELINE_ZERO_THEN_MIGRATE;
            }
            if (state.successfulRows() > 0 && state.baselineRows() == 0) {
                return Action.MIGRATE;
            }
            return Action.FAIL_CLOSED;
        }

        if (state.physicallyEmpty()) {
            return Action.MIGRATE;
        }
        if (state.knownBootstrapOnly()) {
            // Version 0 is not a fake application baseline: V001 still runs.
            return Action.BASELINE_ZERO_THEN_MIGRATE;
        }
        return Action.FAIL_CLOSED;
    }
}
