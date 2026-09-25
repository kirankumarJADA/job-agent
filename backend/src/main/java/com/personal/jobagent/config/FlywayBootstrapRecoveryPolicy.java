package com.personal.jobagent.config;

import java.util.List;
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

    /**
     * @param appliedMigrationVersions   successful, versioned, non-baseline
     *                                   history rows in application order
     *                                   (non-numeric versions map to -1 and
     *                                   break the chain)
     * @param expectedMigrationVersions  versions of the migration scripts
     *                                   shipped with this build; empty means
     *                                   enumeration failed and fails closed
     */
    public record SchemaState(boolean historyExists, int historyRows, int successfulRows, int baselineRows,
                              boolean usersExists, Set<String> applicationObjects, Set<String> platformObjects,
                              List<String> objectDetails, int failedRows, boolean versionZeroBaselinePresent,
                              List<Integer> appliedMigrationVersions, List<Integer> expectedMigrationVersions) {
        public SchemaState {
            applicationObjects = applicationObjects == null ? Set.of() : Set.copyOf(applicationObjects);
            platformObjects = platformObjects == null ? Set.of() : Set.copyOf(platformObjects);
            objectDetails = objectDetails == null ? List.of() : List.copyOf(objectDetails);
            appliedMigrationVersions = appliedMigrationVersions == null ? List.of() : List.copyOf(appliedMigrationVersions);
            expectedMigrationVersions = expectedMigrationVersions == null ? List.of() : List.copyOf(expectedMigrationVersions);
        }
    }

    /**
     * Healthy migrated database invariants (all must hold):
     * <ul>
     *   <li>history table exists with no failed rows</li>
     *   <li>at least one successful versioned migration row</li>
     *   <li>either no baseline rows, or exactly one successful version-0
     *       baseline — the artifact our guarded recovery flow intentionally
     *       produces — so a recovered database is recognized as MIGRATED on
     *       every subsequent startup</li>
     *   <li>the applied versions form an unbroken V001.. prefix of the
     *       expected chain — no skipped, duplicated or foreign versions. This
     *       covers both a complete chain and a pending upgrade: a database
     *       baselined at zero by an earlier deploy whose jar shipped fewer
     *       migrations (its recovery legitimately applied only that jar's
     *       chain) is behind this build's chain and continues with plain
     *       migrate(), which validates every applied checksum before applying
     *       the rest. An EMPTY applied chain under a baseline with application
     *       tables is never accepted — that shape is a baseline laid over an
     *       unknown schema and fails closed</li>
     *   <li>the application schema (public.users) exists</li>
     * </ul>
     * Anything else fails closed for review.
     */
    public static Classification classify(SchemaState state) {
        if (state.historyExists()) {
            // Failed rows mean a migration was interrupted or corrupted;
            // never treat that history as healthy.
            if (state.failedRows() > 0) {
                return Classification.UNEXPECTED;
            }
            // Lone successful baseline with an otherwise empty schema: the
            // known broken bootstrap metadata state (legacy version-1 auto
            // baseline, or a baseline whose chain never ran). Recovery drops
            // only the history table and applies the complete chain.
            if (state.historyRows() == 1 && state.baselineRows() == 1 && state.successfulRows() == 1
                    && !state.usersExists() && state.applicationObjects().isEmpty()) {
                return Classification.INVALID_BASELINE;
            }
            boolean baselineOk = state.baselineRows() == 0
                    || (state.baselineRows() == 1 && state.versionZeroBaselinePresent());
            // Unbroken-prefix verification is mandatory whenever a baseline
            // is present (it certifies the recovery flow's invariant). An
            // unenumerable expected chain only fails closed for baseline
            // states; a clean baseline-free history migrates on.
            boolean chainVerified = state.expectedMigrationVersions().isEmpty()
                    ? state.baselineRows() == 0
                    : isUnbrokenPrefix(state.appliedMigrationVersions(), state.expectedMigrationVersions());
            // A baselined database trailing this build's chain is the normal
            // pending-upgrade state (earlier deploys applied only the chain
            // their jar shipped), not corruption: plain migrate() applies the
            // remainder after validating the applied checksums. What is NOT
            // acceptable under a baseline: an empty applied chain (a baseline
            // over pre-existing tables whose provenance nothing certifies),
            // gaps, duplicates, foreign versions, or failed rows — all of
            // which fail closed above or below.
            if (baselineOk && chainVerified && state.usersExists()
                    && !state.appliedMigrationVersions().isEmpty()) {
                return Classification.MIGRATED;
            }
            return Classification.UNEXPECTED;
        }
        if (state.usersExists() || !state.applicationObjects().isEmpty()) {
            return Classification.UNEXPECTED;
        }
        return state.platformObjects().isEmpty() ? Classification.FRESH : Classification.PLATFORM_BOOTSTRAP_ONLY;
    }

    /** True when applied is a strict order-preserving prefix of expected. */
    private static boolean isUnbrokenPrefix(List<Integer> applied, List<Integer> expected) {
        if (expected.isEmpty() || applied.size() > expected.size()) {
            return false;
        }
        for (int i = 0; i < applied.size(); i++) {
            if (!applied.get(i).equals(expected.get(i))) {
                return false;
            }
        }
        return true;
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
