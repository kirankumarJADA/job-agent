package com.personal.jobagent.track;

import com.personal.jobagent.config.FlywayTestSupport;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V071 (durable receipt links) against real PostgreSQL 16. Every test gets its
 * own database, migrated to V070 first and populated the way the V038/V069
 * code wrote rows, then upgraded to V071 — so the upgrade, the backfill, the
 * isolation trigger, the append-only guard and the fail-closed precheck are
 * all exercised on a populated schema, not an empty one.
 */
@Testcontainers
class ReceiptLinkMigrationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private String database;
    private JdbcTemplate jdbc;

    @BeforeEach
    void freshDatabase() {
        database = "v071_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()))
                .execute("create database " + database);
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url(), postgres.getUsername(), postgres.getPassword()));
    }

    private String url() {
        return postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
    }

    private Flyway flyway(String target) {
        return FlywayTestSupport.load(Flyway.configure()
                .dataSource(url(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .placeholders(Map.of("remove_seed_dev_account", "false"))
                .target(target));
    }

    // ── fixtures written exactly as V038/V069 code writes them ───────────

    private record Owner(UUID profileId, UUID applicationId, UUID jobId) {}

    private Owner owner(String email) {
        UUID user = UUID.randomUUID();
        UUID profile = UUID.randomUUID();
        UUID source = UUID.randomUUID();
        UUID job = UUID.randomUUID();
        UUID app = UUID.randomUUID();
        jdbc.update("insert into users (id, email, password_hash, display_name, auth_provider) values (?, ?, null, 'Candidate', 'LOCAL')",
                user, email);
        jdbc.update("insert into profiles (id, user_id, setup_status, phone, location, links) values (?, ?, 'READY', null, null, '{}')",
                profile, user);
        jdbc.update("insert into job_sources (id, kind, org_identifier, display_name, capabilities, policy) values (?, 'GREENHOUSE', ?, 'Board', '{}', 'DISCOVERY_ONLY')",
                source, "board-" + source);
        jdbc.update("""
                insert into jobs (id, source_id, external_id, dedup_key, company_name_raw, title, location_raw, description_text,
                                  skills_extracted, status, content_hash, application_url)
                values (?, ?, ?, ?, 'Acme', 'Engineer', 'Remote', 'x', '{}'::text[], 'DISCOVERED', ?, 'https://careers.acme.example/jobs/77')
                """, job, source, "ext-" + job, "dedup-" + job, "hash-" + job);
        jdbc.update("insert into applications (id, job_id, profile_id, mode, identity_key, status) values (?, ?, ?, 'ASSISTED', ?, 'READY_TO_APPLY')",
                app, job, profile, "identity-" + app);
        return new Owner(profile, app, job);
    }

    private UUID plan(Owner o, String status) {
        UUID plan = UUID.randomUUID();
        jdbc.update("""
                insert into automation_plans (id, profile_id, application_id, target_url, status, submit_approved, idempotency_key, plan, result)
                values (?, ?, ?, 'https://careers.acme.example/jobs/77/apply', ?, true, ?, '{"planType":"UNIVERSAL"}'::jsonb, '[]'::jsonb)
                """, plan, o.profileId(), o.applicationId(), status, "idem-" + plan);
        return plan;
    }

    private UUID evidence(Owner o, UUID plan, String confirmationId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into submission_evidence (id, plan_id, application_id, profile_id, worker_principal, recorded_via, ats_platform,
                    target_url, confirmation_category, employer_confirmation_id, observed_signals, evidence_text,
                    raw_evidence_sha256, raw_evidence_ciphertext, raw_evidence_byte_length, raw_evidence_status)
                values (?, ?, ?, ?, 'worker', 'WORKER_COMPLETE_ENDPOINT', 'UNIVERSAL', 'https://careers.acme.example/jobs/77/apply',
                        'CONFIRMED', ?, '["UNIVERSAL_SUBMISSION_RESPONSE_2XX"]'::jsonb, 'observed', ?, 'ciphertext', 10, 'STORED_ENCRYPTED')
                """, id, plan, o.applicationId(), o.profileId(), confirmationId, "a".repeat(64));
        return id;
    }

    private UUID receipt(Owner o, String channel, String verification, String reference, Instant evidenceTime) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into application_receipts (id, profile_id, application_id, evidence_type, channel, external_reference,
                    evidence_time, evidence_time_precision, fingerprint, verification_status, policy_version, summary)
                values (?, ?, ?, 'EMPLOYER_ACKNOWLEDGEMENT_EMAIL', ?, ?, ?, ?, ?, ?, 'track-receipt-policy-v1', 'ack')
                """, id, o.profileId(), o.applicationId(), channel, reference,
                evidenceTime == null ? null : Timestamp.from(evidenceTime), evidenceTime == null ? "UNKNOWN" : "EXACT",
                "fp-" + id, verification);
        return id;
    }

    /** A minimal INGESTION link row; every column the caller does not name takes a valid value. */
    private void insertLink(UUID id, UUID profile, UUID application, UUID receipt, UUID plan, UUID evidence, UUID supersedes,
                            String origin, String decision) {
        jdbc.update("""
                insert into application_receipt_links (id, profile_id, application_id, receipt_id, plan_id, submission_evidence_id,
                    supersedes_link_id, origin, decision, channel, receipt_verification, receipt_fingerprint, reference_consistency,
                    evidence_time, evidence_time_precision, evidence_time_source, anchor_source, temporal_status, clock_skew_seconds,
                    policy_version, actor)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'MAILBOX_CONNECTOR', 'UNVERIFIED', 'fp', 'NOT_COMPARABLE',
                        null, 'UNKNOWN', 'NONE', 'NONE', 'EVIDENCE_TIME_UNKNOWN', 300, 'test', 'SYSTEM')
                """, id, profile, application, receipt, plan, evidence, supersedes, origin, decision);
    }

    // ── 0. the schema V071 builds on, proved against V070 ────────────────

    @Test
    void schemaAssumptionsHoldAtV070() {
        flyway("70").migrate();
        Map<String, String> types = jdbc.queryForList("""
                        select table_name || '.' || column_name as col, data_type
                        from information_schema.columns
                        where table_schema = current_schema()
                          and table_name in ('application_receipts', 'submission_evidence', 'automation_plans', 'applications')
                        """).stream()
                .collect(Collectors.toMap(r -> (String) r.get("col"), r -> (String) r.get("data_type")));
        assertThat(types).containsEntry("application_receipts.id", "uuid")
                .containsEntry("application_receipts.external_reference", "text")
                .containsEntry("application_receipts.evidence_time", "timestamp with time zone")
                .containsEntry("application_receipts.channel", "text")
                .containsEntry("submission_evidence.id", "uuid")
                .containsEntry("submission_evidence.plan_id", "uuid")
                .containsEntry("submission_evidence.employer_confirmation_id", "text")
                .containsEntry("submission_evidence.raw_evidence_sha256", "character")
                .containsEntry("automation_plans.profile_id", "uuid")
                .containsEntry("applications.profile_id", "uuid");
        assertThat(jdbc.queryForObject("select to_regclass('application_receipt_links') is null", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from pg_proc where proname = 'reject_receipt_record_update'", Integer.class))
                .isEqualTo(1);
    }

    // ── 1 + 2. upgrade a populated V070 schema; history and sandbox refs survive ──

    @Test
    void upgradesPopulatedV070SchemaPreservingHistoricalReceiptsAndSandboxReferences() {
        flyway("70").migrate();
        Owner o = owner("history@track.test");
        UUID plan = plan(o, "SUBMITTED");
        UUID ev = evidence(o, plan, "APP-12345");
        UUID registration = UUID.randomUUID();
        jdbc.update("""
                insert into candidate_sandbox_registrations (id, profile_id, ats_platform, posting_url, permitted_domain, approved_by)
                values (?, ?, 'WORKDAY', 'https://acme.wd5.myworkdayjobs.com/x', 'myworkdayjobs.com', 'history@track.test')
                """, registration, o.profileId());
        UUID sandboxReceipt = UUID.randomUUID();
        jdbc.update("""
                insert into submission_receipts (id, idempotency_key, plan_id, application_id, profile_id, attempt_number, evidence_id,
                    evidence_sha256, ats_platform, employer_confirmation_id, observed_signals, approval_audit_id, approval_actor,
                    approved_at, worker_principal, sandbox_registration_id, policy_version)
                values (?, 'idem-sandbox', ?, ?, ?, 1, ?, ?, 'WORKDAY', 'APP-12345', '[]'::jsonb, ?, 'history@track.test', now(),
                        'worker', ?, 'receipt-policy-v1')
                """, sandboxReceipt, plan, o.applicationId(), o.profileId(), ev, "a".repeat(64), UUID.randomUUID(), registration);
        Instant at = Instant.parse("2026-11-20T10:15:30Z");
        UUID verified = receipt(o, "MAILBOX_CONNECTOR", "VERIFIED", "APP-12345", at);
        UUID forwarded = receipt(o, "CANDIDATE_FORWARDED", "UNVERIFIED", null, null);
        Map<String, Object> sandboxBefore = jdbc.queryForMap("select * from submission_receipts where id = ?", sandboxReceipt);
        Map<String, Object> receiptBefore = jdbc.queryForMap("select * from application_receipts where id = ?", verified);

        flyway("71").migrate();

        // Historical rows are byte-for-byte what they were.
        assertThat(jdbc.queryForMap("select * from submission_receipts where id = ?", sandboxReceipt)).isEqualTo(sandboxBefore);
        assertThat(jdbc.queryForMap("select * from application_receipts where id = ?", verified)).isEqualTo(receiptBefore);
        assertThat(jdbc.queryForObject("select sandbox_registration_id from submission_receipts where id = ?", UUID.class, sandboxReceipt))
                .isEqualTo(registration);
        assertThat(jdbc.queryForObject("select count(*) from submission_evidence", Integer.class)).isEqualTo(1);

        // One backfilled root per receipt, recording stored facts only.
        List<Map<String, Object>> links = jdbc.queryForList(
                "select * from application_receipt_links where application_id = ? order by receipt_id", o.applicationId());
        assertThat(links).hasSize(2);
        Map<String, Object> v = jdbc.queryForMap("select * from application_receipt_links where receipt_id = ?", verified);
        assertThat(v.get("origin")).isEqualTo("V071_BACKFILL");
        assertThat(v.get("decision")).isEqualTo("NOT_ASSESSED");
        assertThat(v.get("reference_consistency")).isEqualTo("NOT_ASSESSED");
        assertThat(v.get("temporal_status")).isEqualTo("NOT_ASSESSED");
        assertThat(v.get("receipt_reference")).isEqualTo("APP-12345");
        assertThat(v.get("receipt_verification")).isEqualTo("VERIFIED");
        assertThat(v.get("evidence_time_source")).isEqualTo("PROVIDER_RECEIPT");
        assertThat(((Timestamp) v.get("evidence_time")).toInstant()).isEqualTo(at);
        assertThat(v.get("submission_evidence_id")).isNull();
        assertThat(v.get("anchor_source")).isEqualTo("NONE");
        Map<String, Object> f = jdbc.queryForMap("select * from application_receipt_links where receipt_id = ?", forwarded);
        assertThat(f.get("evidence_time_source")).isEqualTo("NONE");
        assertThat(f.get("channel")).isEqualTo("CANDIDATE_FORWARDED");

        // The V069 sandbox constraints are unchanged: still NOT NULL, still immutable.
        assertThatThrownBy(() -> jdbc.update("update submission_receipts set policy_version = 'x' where id = ?", sandboxReceipt))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("immutable");
        assertThat(jdbc.queryForObject("""
                select is_nullable from information_schema.columns
                where table_name = 'submission_receipts' and column_name = 'sandbox_registration_id'""", String.class)).isEqualTo("NO");
        assertThat(jdbc.queryForObject("""
                select is_nullable from information_schema.columns
                where table_name = 'submission_receipts' and column_name = 'employer_confirmation_id'""", String.class)).isEqualTo("NO");
    }

    // ── 3. cross-profile linking is impossible, even by direct SQL ───────

    @Test
    void preventsCrossProfileAndCrossApplicationLinks() {
        flyway("latest").migrate();
        Owner a = owner("a@track.test");
        Owner b = owner("b@track.test");
        UUID receiptA = receipt(a, "MAILBOX_CONNECTOR", "UNVERIFIED", null, null);
        UUID planB = plan(b, "SUBMITTED");
        UUID evidenceB = evidence(b, planB, null);
        UUID planA = plan(a, "SUBMITTED");

        // Profile B claiming A's receipt.
        assertThatThrownBy(() -> insertLink(UUID.randomUUID(), b.profileId(), b.applicationId(), receiptA, null, null, null,
                "INGESTION", "LINKED")).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("same application");
        // A's receipt with B's plan and evidence.
        assertThatThrownBy(() -> insertLink(UUID.randomUUID(), a.profileId(), a.applicationId(), receiptA, planB, evidenceB, null,
                "INGESTION", "LINKED")).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("plan must belong");
        // A's own plan with B's evidence.
        assertThatThrownBy(() -> insertLink(UUID.randomUUID(), a.profileId(), a.applicationId(), receiptA, planA, evidenceB, null,
                "INGESTION", "LINKED")).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("evidence must belong");
        // Profile/application mismatch on the row itself.
        assertThatThrownBy(() -> insertLink(UUID.randomUUID(), b.profileId(), a.applicationId(), receiptA, null, null, null,
                "INGESTION", "LINKED")).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("owner of its application");
        assertThat(jdbc.queryForObject("select count(*) from application_receipt_links", Integer.class)).isZero();
    }

    // ── 4. one current link per receipt ──────────────────────────────────

    @Test
    void preventsDuplicateActiveLinks() {
        flyway("latest").migrate();
        Owner o = owner("dup@track.test");
        UUID r = receipt(o, "MAILBOX_CONNECTOR", "UNVERIFIED", null, null);
        UUID root = UUID.randomUUID();
        insertLink(root, o.profileId(), o.applicationId(), r, null, null, null, "INGESTION", "LINKED");
        // A second root for the same receipt (duplicate delivery written twice) is refused.
        assertThatThrownBy(() -> insertLink(UUID.randomUUID(), o.profileId(), o.applicationId(), r, null, null, null,
                "INGESTION", "LINKED")).isInstanceOf(DataIntegrityViolationException.class);
        // A review supersedes the root exactly once; a concurrent second review of the same row is refused.
        insertLink(UUID.randomUUID(), o.profileId(), o.applicationId(), r, null, null, root, "CANDIDATE_REVIEW", "CANDIDATE_CONFIRMED");
        assertThatThrownBy(() -> insertLink(UUID.randomUUID(), o.profileId(), o.applicationId(), r, null, null, root,
                "CANDIDATE_REVIEW", "CANDIDATE_REJECTED")).isInstanceOf(DataIntegrityViolationException.class);
        // Exactly one current row.
        assertThat(jdbc.queryForObject("""
                select count(*) from application_receipt_links l
                where l.receipt_id = ? and not exists (select 1 from application_receipt_links s where s.supersedes_link_id = l.id)
                """, Integer.class, r)).isEqualTo(1);
        // A review can never supersede a row of a different receipt.
        UUID other = receipt(o, "MAILBOX_CONNECTOR", "UNVERIFIED", null, null);
        assertThatThrownBy(() -> insertLink(UUID.randomUUID(), o.profileId(), o.applicationId(), other, null, null, root,
                "CANDIDATE_REVIEW", "CANDIDATE_REJECTED")).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("same receipt");
        // Shape rules: an ingestion row cannot supersede; a backfill row cannot carry a decision.
        assertThatThrownBy(() -> insertLink(UUID.randomUUID(), o.profileId(), o.applicationId(), other, null, null, root,
                "INGESTION", "LINKED")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertLink(UUID.randomUUID(), o.profileId(), o.applicationId(), other, null, null, null,
                "V071_BACKFILL", "LINKED")).isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── 5. later rejection / withdrawal never erases history ─────────────

    @Test
    void preservesHistoricalEvidenceAfterLaterRejectionOrWithdrawal() {
        flyway("latest").migrate();
        Owner o = owner("keep@track.test");
        UUID plan = plan(o, "SUBMITTED");
        UUID ev = evidence(o, plan, "APP-77");
        UUID r = receipt(o, "MAILBOX_CONNECTOR", "VERIFIED", "APP-77", Instant.now().minus(1, ChronoUnit.HOURS));
        UUID root = UUID.randomUUID();
        insertLink(root, o.profileId(), o.applicationId(), r, plan, ev, null, "INGESTION", "LINKED");

        // The candidate rejects the receipt and withdraws the application.
        jdbc.update("update application_receipts set candidate_review = 'REJECTED', review_version = review_version + 1 where id = ?", r);
        insertLink(UUID.randomUUID(), o.profileId(), o.applicationId(), r, plan, ev, root, "CANDIDATE_REVIEW", "CANDIDATE_REJECTED");
        jdbc.update("update applications set status = 'WITHDRAWN' where id = ?", o.applicationId());

        assertThat(jdbc.queryForObject("select count(*) from application_receipt_links where receipt_id = ?", Integer.class, r))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from submission_evidence where id = ?", Integer.class, ev)).isEqualTo(1);

        // Append-only: no rewrite, no direct delete, and the linked receipt/evidence/plan cannot be deleted out from under it.
        assertThatThrownBy(() -> jdbc.update("update application_receipt_links set decision = 'LINKED' where id = ?", root))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from application_receipt_links where id = ?", root))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from application_receipts where id = ?", r))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from automation_plans where id = ?", plan))
                .isInstanceOf(DataIntegrityViolationException.class);

        // A full profile purge still removes everything in one statement.
        jdbc.update("delete from profiles where id = ?", o.profileId());
        assertThat(jdbc.queryForObject("select count(*) from application_receipt_links", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from application_receipts", Integer.class)).isZero();
    }

    // ── 6. invalid schema assumptions roll the whole migration back ──────

    @Test
    void rollsBackSafelyOnInvalidSchemaAssumptions() {
        flyway("70").migrate();
        Owner o = owner("drift@track.test");
        receipt(o, "MAILBOX_CONNECTOR", "UNVERIFIED", null, null);
        // Simulate a drifted environment: a column V071 depends on has a different name.
        jdbc.execute("alter table application_receipts rename column external_reference to external_ref");

        assertThatThrownBy(() -> flyway("71").migrate()).isInstanceOf(FlywayException.class)
                .hasMessageContaining("V071 schema assumptions do not hold");
        assertThat(jdbc.queryForObject("select to_regclass('application_receipt_links') is null", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from pg_proc where proname = 'application_receipt_links_guard'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '71' and success",
                Integer.class)).isZero();

        // Restored, the same migration applies cleanly and backfills.
        jdbc.execute("alter table application_receipts rename column external_ref to external_reference");
        jdbc.update("delete from flyway_schema_history where version = '71'");
        flyway("71").migrate();
        assertThat(jdbc.queryForObject("select count(*) from application_receipt_links", Integer.class)).isEqualTo(1);
    }

    // ── 7. indexes and foreign keys ──────────────────────────────────────

    @Test
    void declaresIndexesAndForeignKeys() {
        flyway("latest").migrate();
        Set<String> indexes = Set.copyOf(jdbc.queryForList(
                "select indexname from pg_indexes where tablename = 'application_receipt_links'", String.class));
        assertThat(indexes).contains("application_receipt_links_pkey", "application_receipt_links_root_uq",
                "application_receipt_links_supersedes_uq", "application_receipt_links_owner_idx",
                "application_receipt_links_evidence_idx", "application_receipt_links_plan_idx");
        assertThat(jdbc.queryForObject("select indexdef from pg_indexes where indexname = 'application_receipt_links_root_uq'",
                String.class)).contains("UNIQUE").contains("supersedes_link_id IS NULL");

        // Foreign keys and their delete rules: a = NO ACTION, c = CASCADE.
        Map<String, String> fks = jdbc.queryForList("""
                        select a.attname as col, c.confrelid::regclass::text as target, c.confdeltype::text as on_delete
                        from pg_constraint c
                        join pg_attribute a on a.attrelid = c.conrelid and a.attnum = c.conkey[1]
                        where c.conrelid = 'application_receipt_links'::regclass and c.contype = 'f'
                        """).stream()
                .collect(Collectors.toMap(r -> (String) r.get("col"), r -> r.get("target") + ":" + r.get("on_delete")));
        assertThat(fks).containsEntry("profile_id", "profiles:c")
                .containsEntry("application_id", "applications:c")
                .containsEntry("receipt_id", "application_receipts:a")
                .containsEntry("plan_id", "automation_plans:a")
                .containsEntry("submission_evidence_id", "submission_evidence:a")
                .containsEntry("supersedes_link_id", "application_receipt_links:a");
        Set<String> triggers = Set.copyOf(jdbc.queryForList(
                "select tgname from pg_trigger where tgrelid = 'application_receipt_links'::regclass and not tgisinternal", String.class));
        assertThat(triggers).containsExactlyInAnyOrder("application_receipt_links_consistent", "application_receipt_links_append_only");
    }
}
