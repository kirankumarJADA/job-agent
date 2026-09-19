package com.personal.jobagent.config;

import java.util.Set;

public final class FlywayBootstrapRecoveryPolicy {
    private FlywayBootstrapRecoveryPolicy() {}

    public enum Action {
        MIGRATE,
        BASELINE_AT_ZERO_THEN_MIGRATE,
        DROP_HISTORY_BASELINE_AT_ZERO_THEN_MIGRATE,
        FAIL_CLOSED
    }

    /** Human-facing classification of the inspected schema state. */
    public enum Classification {
        FRESH,
        PLATFORM_BOOTSTRAP_ONLY,
        INVALID_BASELINE,
        MIGRATED,
        UNEXPECTED
    }

    /**
     * Objects supplied by the PostGIS extension that may live in public yet
     * lack a pg_depend extension row (e.g. after pg_dump/restore). Only these
     * legacy names are tolerated; anything else is treated as application
     * data and fails closed.
     */
    private static final Set<String> KNOWN_POSTGIS_OBJECTS = Set.of(
            "public.spatial_ref_sys",
            "public.geography_columns",
            "public.geometry_columns",
            "public.raster_columns",
            "public.raster_overviews"
    );

    public record SchemaState(boolean historyExists, int historyRows, int successfulRows, int baselineRows,
                              boolean usersExists, Set<String> applicationObjects, Set<String> platformObjects) {
        public SchemaState {
            applicationObjects = applicationObjects == null ? Set.of() : Set.copyOf(applicationObjects);
            platformObjects = platformObjects == null ? Set.of() : Set.copyOf(platformObjects);
        }
    }

    public static Classification classify(SchemaState state) {
        if (state.historyExists()) {
            if (state.historyRows() == 1 && state.successfulRows() == 1 && state.baselineRows() == 1
                    && !state.usersExists() && state.applicationObjects().isEmpty()) {
                return Classification.INVALID_BASELINE;
            }
            if (state.successfulRows() > 0 && state.baselineRows() == 0) {
                return Classification.MIGRATED;
            }
            return Classification.UNEXPECTED;
        }
        if (state.usersExists() || !state.applicationObjects().isEmpty()) {
            return Classification.UNEXPECTED;
        }
        return state.platformObjects().isEmpty() ? Classification.FRESH : Classification.PLATFORM_BOOTSTRAP_ONLY;
    }

    public static Action decide(SchemaState state) {
        return switch (classify(state)) {
            case FRESH -> Action.MIGRATE;
            // Version 0 is not a fake application baseline: Flyway applies
            // every version > 0, so the complete V001 -> latest chain runs.
            case PLATFORM_BOOTSTRAP_ONLY -> Action.BASELINE_AT_ZERO_THEN_MIGRATE;
            case INVALID_BASELINE -> Action.DROP_HISTORY_BASELINE_AT_ZERO_THEN_MIGRATE;
            case MIGRATED -> Action.MIGRATE;
            case UNEXPECTED -> Action.FAIL_CLOSED;
        };
    }

    public static boolean isKnownPostgisObject(String qualifiedName) {
        return KNOWN_POSTGIS_OBJECTS.contains(qualifiedName);
    }
}
