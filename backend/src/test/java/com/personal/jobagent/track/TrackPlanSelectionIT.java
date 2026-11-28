package com.personal.jobagent.track;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.config.FlywayTestSupport;
import com.personal.jobagent.track.TrackModels.TrackSnapshot;
import com.personal.jobagent.track.TrackModels.TrackState;
import com.personal.jobagent.track.TrackVocabulary.Stage;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Stage 3I Phase 6, P2-8: which automation plan represents an application in TRACK, through the real
 * {@link TrackRepository#snapshotParts} query on PostgreSQL 16. A SUBMITTED plan is never hidden by a
 * later failed, paused or re-prepared one, and nothing that is not SUBMITTED is ever shown as applied.
 */
@Testcontainers
class TrackPlanSelectionIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private static JdbcTemplate jdbc;
    private static TrackRepository repository;
    private static UUID source;
    private static final Instant T0 = Instant.parse("2026-11-20T10:00:00Z");

    @BeforeAll
    static void migrate() {
        FlywayTestSupport.load(Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .placeholders(Map.of("remove_seed_dev_account", "false"))).migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        repository = new TrackRepository(jdbc, new ObjectMapper());
        source = UUID.randomUUID();
        jdbc.update("insert into job_sources (id, kind, org_identifier, display_name, capabilities, policy) values (?, 'GREENHOUSE', ?, 'S', '{}', 'DISCOVERY_ONLY')",
                source, "plan-select-" + source);
    }

    private static UUID profile() {
        UUID user = UUID.randomUUID();
        UUID profile = UUID.randomUUID();
        jdbc.update("insert into users (id, email, password_hash, display_name, auth_provider) values (?, ?, null, 'C', 'LOCAL')",
                user, "sel-" + user + "@track.test");
        jdbc.update("insert into profiles (id, user_id, setup_status, phone, location, links) values (?, ?, 'READY', null, null, '{}')",
                profile, user);
        return profile;
    }

    private static UUID application(UUID profile) {
        UUID job = UUID.randomUUID();
        UUID app = UUID.randomUUID();
        jdbc.update("""
                insert into jobs (id, source_id, external_id, dedup_key, company_name_raw, title, location_raw, description_text,
                                  skills_extracted, status, content_hash, application_url)
                values (?, ?, ?, ?, 'Acme', 'Engineer', 'Remote', 'x', '{}'::text[], 'DISCOVERED', ?, 'https://careers.acme-sel.example/jobs/1')
                """, job, source, "ext-" + job, "dedup-" + job, "hash-" + job);
        jdbc.update("insert into applications (id, job_id, profile_id, mode, identity_key, status) values (?, ?, ?, 'ASSISTED', ?, 'READY_TO_APPLY')",
                app, job, profile, "identity-" + app);
        return app;
    }

    private static UUID plan(UUID profile, UUID app, String status, int minutesAfterT0) {
        return plan(profile, app, status, minutesAfterT0, UUID.randomUUID());
    }

    private static UUID plan(UUID profile, UUID app, String status, int minutesAfterT0, UUID id) {
        Timestamp at = Timestamp.from(T0.plusSeconds(minutesAfterT0 * 60L));
        jdbc.update("""
                insert into automation_plans (id, profile_id, application_id, target_url, status, submit_approved, idempotency_key,
                                              plan, result, created_at, updated_at)
                values (?, ?, ?, 'https://careers.acme-sel.example/jobs/1/apply', ?, ?, ?, '{"planType":"UNIVERSAL"}'::jsonb, '[]'::jsonb, ?, ?)
                """, id, profile, app, status, "SUBMITTED".equals(status), "sel-" + id, at, at);
        return id;
    }

    private static TrackState state(UUID profile, UUID app) {
        TrackRepository.SnapshotParts parts = repository.snapshotParts(profile, app);
        TrackRepository.ApplicationHeader h = parts.applications().get(app);
        return new ApplicationReconciler().reconcile(new TrackSnapshot(app, h.status(), h.createdAt(),
                parts.preparation().getOrDefault(app, TrackModels.Preparation.none()), parts.plans().get(app),
                parts.statusChanges().getOrDefault(app, List.of()), parts.reports().getOrDefault(app, List.of()),
                parts.receipts().getOrDefault(app, List.of()), parts.messages().getOrDefault(app, List.of()), true, Instant.now(),
                parts.submissions().get(app), parts.anchorFor(app), parts.disqualifiedReferences().getOrDefault(app, java.util.Set.of()),
                parts.foreignReferences().getOrDefault(app, java.util.Set.of())));
    }

    private static String selectedStatus(UUID profile, UUID app) {
        return repository.snapshotParts(profile, app).plans().get(app).status();
    }

    @Test
    void aSubmittedPlanFollowedByAFailedPlanStillShowsTheSubmission() {
        UUID p = profile();
        UUID app = application(p);
        plan(p, app, "SUBMITTED", 0);
        plan(p, app, "FAILED", 30);
        assertThat(selectedStatus(p, app)).isEqualTo("SUBMITTED");
        TrackState s = state(p, app);
        assertThat(s.status()).isEqualTo(Stage.APPLIED);
        assertThat(s.robinSubmitted()).isTrue();
    }

    @Test
    void aSubmittedPlanFollowedByAPausedOrRePreparedPlanStillShowsTheSubmission() {
        for (String later : List.of("HANDOFF_REQUIRED", "PREPARED", "AWAITING_APPROVAL", "RECONCILIATION_REQUIRED", "RUNNING")) {
            UUID p = profile();
            UUID app = application(p);
            plan(p, app, "SUBMITTED", 0);
            plan(p, app, later, 60);
            assertThat(selectedStatus(p, app)).as(later).isEqualTo("SUBMITTED");
            assertThat(state(p, app).robinSubmitted()).as(later).isTrue();
        }
    }

    @Test
    void anApplicationCanNeverHoldTwoSubmittedPlans() {
        UUID p = profile();
        UUID app = application(p);
        plan(p, app, "SUBMITTED", 0);
        // V043: at most one SUBMITTED plan per application, so "multiple submitted plans" cannot arise.
        assertThatThrownBy(() -> plan(p, app, "SUBMITTED", 10)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(selectedStatus(p, app)).isEqualTo("SUBMITTED");
    }

    @Test
    void withoutASubmittedPlanTheLatestPlanIsShownAndNothingIsApplied() {
        UUID p = profile();
        UUID app = application(p);
        plan(p, app, "RECONCILIATION_REQUIRED", 0);
        plan(p, app, "AWAITING_APPROVAL", 30);
        assertThat(selectedStatus(p, app)).isEqualTo("AWAITING_APPROVAL");
        TrackState s = state(p, app);
        assertThat(s.status()).isNotEqualTo(Stage.APPLIED);
        assertThat(s.robinSubmitted()).isFalse();
        assertThat(s.submission().state()).isNotEqualTo(TrackVocabulary.SubmissionState.CONFIRMED);
    }

    @Test
    void anotherCandidatesSubmittedPlanNeverAffectsThisApplication() {
        UUID mine = profile();
        UUID theirs = profile();
        UUID myApp = application(mine);
        UUID theirApp = application(theirs);
        plan(theirs, theirApp, "SUBMITTED", 0);
        plan(mine, myApp, "FAILED", 30);
        assertThat(selectedStatus(mine, myApp)).isEqualTo("FAILED");
        assertThat(state(mine, myApp).robinSubmitted()).isFalse();
        assertThat(repository.snapshotParts(mine, null).plans()).doesNotContainKey(theirApp);
    }

    @Test
    void plansCreatedAtTheSameInstantAreOrderedDeterministically() {
        UUID p = profile();
        UUID app = application(p);
        UUID low = UUID.fromString("00000000-0000-7000-8000-000000000001");
        UUID high = UUID.fromString("ffffffff-ffff-7fff-bfff-ffffffffffff");
        plan(p, app, "FAILED", 5, low);
        plan(p, app, "AWAITING_APPROVAL", 5, high);
        for (int i = 0; i < 5; i++) {
            assertThat(selectedStatus(p, app)).isEqualTo("AWAITING_APPROVAL");
        }
    }
}
