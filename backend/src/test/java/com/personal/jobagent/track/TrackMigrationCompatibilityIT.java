package com.personal.jobagent.track;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.track.TrackModels.TrackSnapshot;
import com.personal.jobagent.track.TrackVocabulary.Stage;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V038 against a database that already holds pre-8.3 records: migrate to V037,
 * write legacy rows exactly as the pre-8.3 code wrote them, migrate to V038, and
 * check that nothing was promoted or marked verified, that legacy rows were
 * classified conservatively, and that the new guards apply from then on.
 */
@Testcontainers
class TrackMigrationCompatibilityIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private static Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .placeholders(Map.of("remove_seed_dev_account", "false"))
                .target(target)
                .load();
    }

    @Test
    void legacyRecordsSurviveV038ConservativelyClassified() {
        flyway("37").migrate();
        DriverManagerDataSource ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        UUID user = UUID.randomUUID();
        UUID profile = UUID.randomUUID();
        UUID source = UUID.randomUUID();
        UUID job = UUID.randomUUID();
        UUID app = UUID.randomUUID();
        UUID legacyEmail = UUID.randomUUID();
        UUID unlinkedEmail = UUID.randomUUID();
        jdbc.update("insert into users (id, email, password_hash, display_name, auth_provider) values (?, 'legacy@track.test', null, 'Legacy', 'FIREBASE')", user);
        jdbc.update("insert into profiles (id, user_id, work_eligibility, career_goals, links, master_revision, setup_status) values (?, ?, '{}'::jsonb, '{}'::jsonb, '{}'::jsonb, 1, 'INCOMPLETE')",
                profile, user);
        jdbc.update("insert into job_sources (id, kind, org_identifier, display_name, capabilities, policy) values (?, 'GREENHOUSE', 'legacy-board', 'Legacy Board', '{}', 'DISCOVERY_ONLY')", source);
        jdbc.update("""
                insert into jobs (id, source_id, external_id, dedup_key, company_name_raw, title, description_text,
                                  skills_extracted, status, content_hash, application_url)
                values (?, ?, 'legacy-1', 'dedup-legacy-1', 'Legacy Co', 'Platform Engineer', 'x', '{}', 'SCORED', 'h', 'https://legacy.example/jobs/1')
                """, job, source);
        // Pre-8.3 state: the candidate walked the status machine to SUBMITTED, then the
        // keyword classifier moved it to INTERVIEW from an email.
        jdbc.update("insert into applications (id, job_id, profile_id, mode, status) values (?, ?, ?, 'ASSISTED', 'INTERVIEW')", app, job, profile);
        jdbc.update("insert into application_events (id, application_id, type, payload, actor, occurred_at) values (?, ?, 'APPLICATION_CREATED', '{\"event_key\":\"application-created\"}'::jsonb, 'SYSTEM', now() - interval '10 days')",
                UUID.randomUUID(), app);
        jdbc.update("insert into application_events (id, application_id, type, payload, actor, occurred_at) values (?, ?, 'STATUS_CHANGED', '{\"event_key\":\"manual-1\",\"from\":\"READY_TO_APPLY\",\"to\":\"APPLICATION_STARTED\"}'::jsonb, 'legacy@track.test', now() - interval '9 days')",
                UUID.randomUUID(), app);
        jdbc.update("insert into application_events (id, application_id, type, payload, actor, occurred_at) values (?, ?, 'STATUS_CHANGED', '{\"event_key\":\"manual-2\",\"from\":\"APPLICATION_STARTED\",\"to\":\"APPLICATION_SUBMITTED\"}'::jsonb, 'legacy@track.test', now() - interval '8 days')",
                UUID.randomUUID(), app);
        jdbc.update("""
                insert into emails (id, message_id, from_address, to_address, subject, body_text, received_at, application_id,
                                    classification, classification_confidence, classification_reason, profile_id)
                values (?, 'legacy-msg-1', 'talent@legacy.example', 'legacy@track.test', 'Interview', 'We would like to interview you',
                        now() - interval '5 days', ?, 'INTERVIEW_INVITE', 0.95, 'deterministic local classifier', ?)
                """, legacyEmail, app, profile);
        jdbc.update("insert into application_events (id, application_id, type, payload, actor, occurred_at) values (?, ?, 'STATUS_CHANGED', ?::jsonb, 'legacy@track.test', now() - interval '5 days')",
                UUID.randomUUID(), app, "{\"event_key\":\"email:" + legacyEmail + "\",\"from\":\"APPLICATION_SUBMITTED\",\"to\":\"INTERVIEW\"}");
        jdbc.update("""
                insert into emails (id, message_id, from_address, subject, body_text, received_at, classification,
                                    classification_confidence, classification_reason, profile_id)
                values (?, 'legacy-msg-2', 'news@example.org', 'Digest', 'Weekly digest', now() - interval '4 days', 'OTHER', 0.95, 'x', ?)
                """, unlinkedEmail, profile);

        flyway("38").migrate();

        // Robin's recorded status is untouched; nothing was promoted or verified.
        assertThat(jdbc.queryForObject("select status from applications where id = ?", String.class, app)).isEqualTo("INTERVIEW");
        assertThat(jdbc.queryForObject("select count(*) from application_receipts", Integer.class)).isZero();
        // Every legacy timeline row now carries its owner …
        assertThat(jdbc.queryForObject("select count(*) from application_events where application_id = ? and profile_id is distinct from ?",
                Integer.class, app, profile)).isZero();
        // … and STATUS_CHANGED rows say who set them.
        List<String> classifications = jdbc.queryForList("""
                select classification from application_events where application_id = ? and type = 'STATUS_CHANGED' order by occurred_at
                """, String.class, app);
        assertThat(classifications).containsExactly("CANDIDATE_REPORTED", "CANDIDATE_REPORTED", "INFERRED");
        assertThat(jdbc.queryForObject("select classification from application_events where application_id = ? and type = 'APPLICATION_CREATED'",
                String.class, app)).isEqualTo("INTERNAL");

        // The pre-8.3 company-name correlation becomes a proposal for the candidate, never a link.
        Map<String, Object> link = jdbc.queryForMap("""
                select status, match_strength, application_id, inferred_category, inferred_confidence, candidates::text as candidates,
                       processing_version
                from email_application_links where email_id = ?""", legacyEmail);
        assertThat(link.get("status")).isEqualTo("PENDING_REVIEW");
        assertThat(link.get("match_strength")).isEqualTo("LEGACY");
        assertThat(link.get("application_id")).isNull();
        assertThat(link.get("inferred_category")).isEqualTo("INTERVIEW_INVITATION");
        assertThat(((Number) link.get("inferred_confidence")).doubleValue()).isEqualTo(0.30);
        assertThat(String.valueOf(link.get("candidates"))).contains(app.toString());
        assertThat(link.get("processing_version")).isEqualTo("legacy-keyword-v0");
        assertThat(jdbc.queryForObject("select count(*) from email_application_links where email_id = ?", Integer.class, unlinkedEmail))
                .isZero();
        Map<String, Object> email = jdbc.queryForMap("select channel, body_retained, received_time_known from emails where id = ?", legacyEmail);
        assertThat(email.get("channel")).isEqualTo("CANDIDATE_FORWARDED");
        assertThat(email.get("body_retained")).isEqualTo(true);

        // Reconciled from those records, the inferred legacy status needs the candidate's review.
        TrackRepository repository = new TrackRepository(jdbc, new ObjectMapper());
        TrackRepository.SnapshotParts parts = repository.snapshotParts(profile, app);
        TrackRepository.ApplicationHeader header = parts.applications().get(app);
        TrackModels.TrackState state = new ApplicationReconciler().reconcile(new TrackSnapshot(app, header.status(),
                header.createdAt(), parts.preparation().getOrDefault(app, TrackModels.Preparation.none()), parts.plans().get(app),
                parts.statusChanges().getOrDefault(app, List.of()), parts.reports().getOrDefault(app, List.of()),
                parts.receipts().getOrDefault(app, List.of()), parts.messages().getOrDefault(app, List.of()), false, Instant.now()));
        assertThat(state.status()).isEqualTo(Stage.NEEDS_REVIEW);
        assertThat(state.evidenceStage()).isEqualTo(Stage.APPLIED);
        assertThat(state.attention()).extracting(TrackModels.Attention::code).contains("MESSAGE_ASSOCIATION_REVIEW");

        // From V038 on, the guards apply: owner filled on insert, history append-only.
        UUID fresh = UUID.randomUUID();
        jdbc.update("insert into application_events (id, application_id, type, payload, actor) values (?, ?, 'PREPARATION', '{}'::jsonb, 'SYSTEM')", fresh, app);
        assertThat(jdbc.queryForObject("select profile_id from application_events where id = ?", UUID.class, fresh)).isEqualTo(profile);
        assertThatThrownBy(() -> jdbc.update("update application_events set actor = 'x' where id = ?", fresh))
                .hasMessageContaining("append-only");
        // A purge still cascades through everything.
        jdbc.update("delete from profiles where id = ?", profile);
        assertThat(jdbc.queryForObject("select count(*) from application_events where application_id = ?", Integer.class, app)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from email_application_links where profile_id = ?", Integer.class, profile)).isZero();
    }
}
