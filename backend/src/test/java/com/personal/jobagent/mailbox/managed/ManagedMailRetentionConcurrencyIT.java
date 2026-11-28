package com.personal.jobagent.mailbox.managed;

import com.personal.jobagent.config.FlywayTestSupport;
import com.personal.jobagent.security.SecretCipher;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Stage 3I Phase 6, A2: managed-mail retention against concurrent lease activity
 * on real PostgreSQL 16, with deterministic interleavings rather than thread timing.
 *
 * <p>A second JDBC connection plays the lease refresh: it takes the row locks a
 * {@code /resolve} refresh takes and extends the lease inside an open
 * transaction while retention runs. Retention must skip the locked rows without
 * waiting ({@code FOR UPDATE SKIP LOCKED}), and once the refresh commits the
 * lease and its message content must be intact. The purge must also be atomic
 * (a failure in its last statements undoes its first) and safe to run twice at
 * once.
 */
@Testcontainers
class ManagedMailRetentionConcurrencyIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager tx;
    private ManagedMailRetentionJob job;
    private UUID profile;
    private UUID source;
    private UUID address;

    @BeforeAll
    static void migrate() {
        FlywayTestSupport.load(Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .placeholders(Map.of("remove_seed_dev_account", "false"))).migrate();
        DriverManagerDataSource ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(ds);
        tx = new DataSourceTransactionManager(ds);
    }

    @BeforeEach
    void seed() {
        ManagedMailSettings settings = new ManagedMailSettings("mail.robin-retention.test", "cloudflare", "", 900,
                "cf-secret", 900, "robin+", 120, 30, new SecretCipher("6M5mXo2C0eP1Ztq0QzZ0n7cYk1o0r8sB3m6Gq9v2T8U="));
        job = new ManagedMailRetentionJob(jdbc, settings);
        ReflectionTestUtils.setField(job, "transactionManager", tx);

        UUID user = UUID.randomUUID();
        profile = UUID.randomUUID();
        source = UUID.randomUUID();
        address = UUID.randomUUID();
        jdbc.update("insert into users (id, email, password_hash, display_name, auth_provider) values (?, ?, null, 'C', 'LOCAL')",
                user, "ret-" + user + "@retention.test");
        jdbc.update("insert into profiles (id, user_id, setup_status, phone, location, links) values (?, ?, 'READY', null, null, '{}')",
                profile, user);
        jdbc.update("insert into job_sources (id, kind, org_identifier, display_name, capabilities, policy) values (?, 'GREENHOUSE', ?, 'S', '{}', 'DISCOVERY_ONLY')",
                source, "src-" + source);
        String local = "r" + UUID.randomUUID().toString().replace("-", "").substring(0, 15);
        jdbc.update("insert into managed_mail_addresses (id, profile_id, local_part, domain) values (?, ?, ?, 'mail.robin-retention.test')",
                address, profile, local);
    }

    /** One application on its own job (one active lease is allowed per application and session). */
    private UUID application() {
        UUID jobId = UUID.randomUUID();
        UUID app = UUID.randomUUID();
        jdbc.update("""
                insert into jobs (id, source_id, external_id, dedup_key, company_name_raw, title, location_raw, description_text,
                                  skills_extracted, status, content_hash, application_url)
                values (?, ?, ?, ?, 'Acme', 'Engineer', 'Remote', 'x', '{}'::text[], 'DISCOVERED', ?, 'https://careers.acme-ret.example/jobs/1')
                """, jobId, source, "ext-" + jobId, "dedup-" + jobId, "hash-" + jobId);
        jdbc.update("insert into applications (id, job_id, profile_id, mode, identity_key, status) values (?, ?, ?, 'ASSISTED', ?, 'READY_TO_APPLY')",
                app, jobId, profile, "identity-" + app);
        return app;
    }

    private record Leased(UUID application, UUID message, UUID lease) {}

    /** A lease on a managed message received {@code ageMinutes} ago, both reserved until {@code until}. */
    private Leased leased(long ageMinutes, Instant until) {
        UUID app = application();
        UUID msg = message(ageMinutes, until, app);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into mailbox_verifications (id, application_id, employer_domain, provider_id, source_managed_message_id,
                    status, reserved_until, reservation_token_sha256, created_at)
                values (?, ?, 'acme-ret.example', 'managed', ?, 'RESERVED', ?, ?, now())
                """, id, app, msg, Timestamp.from(until), hex());
        return new Leased(app, msg, id);
    }

    private Instant dbNow() {
        return jdbc.queryForObject("select now()", Timestamp.class).toInstant();
    }

    private static String hex() {
        byte[] b = new byte[32];
        new java.security.SecureRandom().nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /** A managed message received {@code ageMinutes} ago, optionally reserved until {@code reservedUntil}. */
    private UUID message(long ageMinutes, Instant reservedUntil, UUID application) {
        UUID id = UUID.randomUUID();
        Instant now = dbNow();
        jdbc.update("""
                insert into managed_inbound_messages (id, profile_id, address_id, provider_id, content_sha256, envelope_recipient,
                    subject_redacted, content_encrypted, signed_at, received_at, auth_trusted,
                    reserved_for_application_id, reserved_at, reservation_expires_at, reservation_token_sha256)
                values (?, ?, ?, 'cloudflare', ?, 'robin+x@mail.robin-retention.test', '[redacted]', 'ciphertext', ?, ?, true,
                        ?, ?, ?, ?)
                """, id, profile, address, hex(), Timestamp.from(now.minusSeconds(ageMinutes * 60)),
                Timestamp.from(now.minusSeconds(ageMinutes * 60)),
                reservedUntil == null ? null : application, reservedUntil == null ? null : Timestamp.from(now.minusSeconds(60)),
                reservedUntil == null ? null : Timestamp.from(reservedUntil), reservedUntil == null ? null : hex());
        return id;
    }

    private Map<String, Object> leaseRow(UUID id) {
        return jdbc.queryForMap("select status, reserved_until from mailbox_verifications where id = ?", id);
    }

    @Test
    void retentionSkipsALeaseBeingRefreshedConcurrentlyWithoutWaitingAndTheRefreshWins() throws Exception {
        Instant now = dbNow();
        // A lease that has just run out, so retention would expire it and erase its old message...
        Leased l = leased(180, now.minusSeconds(30));
        UUID msg = l.message();
        UUID lease = l.lease();
        // ...while a /resolve refresh holds its rows and extends it in an open transaction.
        try (Connection refresh = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            refresh.setAutoCommit(false);
            try (PreparedStatement lock = refresh.prepareStatement("select id from mailbox_verifications where id = ? for update")) {
                lock.setObject(1, lease);
                lock.executeQuery();
            }
            try (PreparedStatement ps = refresh.prepareStatement(
                    "update mailbox_verifications set reserved_until = now() + interval '3 minutes' where id = ?")) {
                ps.setObject(1, lease);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = refresh.prepareStatement(
                    "update managed_inbound_messages set reservation_expires_at = now() + interval '3 minutes' where id = ?")) {
                ps.setObject(1, msg);
                ps.executeUpdate();
            }

            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<ManagedMailRetentionJob.Result> purge = pool.submit(() -> job.purge(dbNow()));
                // SKIP LOCKED: the purge finishes while the refresh still holds its locks.
                purge.get(10, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }
            refresh.commit();
        }
        Map<String, Object> row = leaseRow(lease);
        assertThat(row.get("status")).isEqualTo("RESERVED");
        assertThat(((Timestamp) row.get("reserved_until")).toInstant()).isAfter(dbNow());
        assertThat(jdbc.queryForObject("select content_encrypted is not null and reserved_at is not null from managed_inbound_messages where id = ?",
                Boolean.class, msg)).isTrue();
    }

    @Test
    void anExpiredLeaseIsExpiredAndItsOldMessageReleasedAndErased() {
        Instant now = dbNow();
        Leased l = leased(180, now.minusSeconds(30));
        UUID msg = l.message();
        UUID lease = l.lease();
        ManagedMailRetentionJob.Result result = job.purge(dbNow());
        Map<String, Object> row = leaseRow(lease);
        assertThat(row.get("status")).isEqualTo("EXPIRED");
        assertThat(row.get("reserved_until")).isNull();
        Map<String, Object> m = jdbc.queryForMap("select content_encrypted, reserved_at, reservation_expires_at from managed_inbound_messages where id = ?", msg);
        assertThat(m.get("content_encrypted")).isNull();
        assertThat(m.get("reserved_at")).isNull();
        assertThat(m.get("reservation_expires_at")).isNull();
        assertThat(result.contentErased()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void anActiveLeaseKeepsItsMessageEvenPastContentAndMetadataRetention() {
        Instant now = dbNow();
        Leased l = leased(40L * 24 * 60, now.plusSeconds(120));
        UUID msg = l.message();
        UUID lease = l.lease();
        job.purge(dbNow());
        assertThat(leaseRow(lease).get("status")).isEqualTo("RESERVED");
        assertThat(jdbc.queryForObject("select count(*) from managed_inbound_messages where id = ? and content_encrypted is not null",
                Integer.class, msg)).isEqualTo(1);
    }

    @Test
    void thePurgeIsOneTransactionAFailureUndoesEverythingItDid() {
        Instant now = dbNow();
        Leased l = leased(180, now.minusSeconds(30));
        UUID msg = l.message();
        UUID lease = l.lease();
        jdbc.update("insert into managed_mail_webhook_tokens (token_sha256, provider_id, signed_at, payload_sha256, received_at) "
                + "values (?, 'cloudflare', now() - interval '2 days', ?, now() - interval '2 days')", hex(), hex());
        jdbc.execute("""
                create or replace function phase6_fail_token_delete() returns trigger language plpgsql as $$
                begin raise exception 'simulated failure late in the purge'; end; $$
                """);
        jdbc.execute("create trigger phase6_fail_token_delete before delete on managed_mail_webhook_tokens "
                + "for each row execute function phase6_fail_token_delete()");
        try {
            assertThatThrownBy(() -> job.purge(dbNow())).hasMessageContaining("simulated failure");
            // The lease expiry and content erasure that ran earlier in the same purge were rolled back.
            assertThat(leaseRow(lease).get("status")).isEqualTo("RESERVED");
            assertThat(jdbc.queryForObject("select content_encrypted is not null from managed_inbound_messages where id = ?",
                    Boolean.class, msg)).isTrue();
        } finally {
            jdbc.execute("drop trigger phase6_fail_token_delete on managed_mail_webhook_tokens");
            jdbc.execute("drop function phase6_fail_token_delete()");
        }
        job.purge(dbNow());
        assertThat(leaseRow(lease).get("status")).isEqualTo("EXPIRED");
    }

    @Test
    void twoPurgesAtOnceProcessEveryExpiredLeaseExactlyOnceWithoutDeadlock() throws Exception {
        Instant now = dbNow();
        List<UUID> leases = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            leases.add(leased(30, now.minusSeconds(60)).lease());
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ManagedMailRetentionJob.Result>> runs = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            runs.add(pool.submit(() -> {
                go.await();
                return job.purge(dbNow());
            }));
        }
        go.countDown();
        for (Future<ManagedMailRetentionJob.Result> f : runs) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(jdbc.queryForObject("select count(*) from mailbox_verifications where id = any(?) and status = 'EXPIRED' and reserved_until is null",
                Integer.class, (Object) leases.toArray(new UUID[0]))).isEqualTo(300);
    }

    @Test
    void theScheduledSweepJudgesExpiryByTheDatabaseClock() {
        Instant db = job.databaseNow();
        assertThat(Math.abs(db.toEpochMilli() - dbNow().toEpochMilli())).isLessThan(5_000);
        UUID lease = leased(5, dbNow().plusSeconds(90)).lease();
        job.sweep();
        assertThat(leaseRow(lease).get("status")).isEqualTo("RESERVED");
    }
}
