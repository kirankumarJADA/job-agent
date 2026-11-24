package com.personal.jobagent.discovery;

import com.personal.jobagent.common.UuidV7;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * F9 concurrency on real PostgreSQL: the hourly cleanup ({@link JobDiscoveryService#scheduledDiscoveryMaintenance})
 * running at the same time as ingestion.
 *
 * <p>Ingestion ({@code ingestJob}) locks a job row and then that job's observation. Every test here drives two
 * genuinely concurrent transactions on separate connections: one plays ingestion with exactly that order, the
 * other is the real cleanup. Instead of sleeping, the tests wait until {@code pg_stat_activity} shows the cleanup
 * blocked on a lock, and they read the lock order directly with {@code FOR UPDATE NOWAIT} probes.
 */
@SpringBootTest(properties = {"spring.flyway.placeholders.remove_seed_dev_account=false",
        "app.discovery.scheduler-enabled=false",
        // No outbox dispatch: the pipeline it would run takes locks on the test jobs and only adds noise.
        "app.events.dispatch-enabled=false"})
@Testcontainers
class DiscoveryMaintenanceConcurrencyIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static final String DEADLOCK = "40P01";
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private JobDiscoveryService ingestion;
    @Autowired private DataSource dataSource;

    private UUID ats;
    private UUID aggregator;
    private ExecutorService pool;

    /** A posting as ingested, with what is needed to re-ingest it or address its observation row. */
    private record Posting(UUID jobId, UUID sourceId, String externalId, String canonicalUrl, String title) {}

    @BeforeEach
    void setUp() throws InterruptedException {
        stayClearOfTheHourlyRun();
        ats = source("GREENHOUSE", "f9-ats-" + unique());
        aggregator = source("TSENTA", "f9-agg-" + unique());
        pool = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void cleanupAndAConcurrentReIngestionOfTheSameJobDoNotDeadlock() throws Exception {
        Posting p = staleAggregatorPosting();

        Throwable ingestFailure = null;
        Throwable sweepFailure;
        try (Connection ingest = rawTransaction()) {
            // Ingestion's order: the job row first ...
            exec(ingest, "update jobs set last_seen_at = now() where id = ?", p.jobId());
            Future<?> sweep = pool.submit(() -> ingestion.scheduledDiscoveryMaintenance());
            awaitLockWaiter();
            // ... then that posting's observation, upserted exactly as recordObservation does.
            try {
                upsertObservationLikeRecordObservation(ingest, p);
                ingest.commit();
            } catch (SQLException e) {
                ingestFailure = e;
                ingest.rollback();
            }
            sweepFailure = failureOf(sweep);
        }

        assertThat(isSqlState(ingestFailure, DEADLOCK) || isSqlState(sweepFailure, DEADLOCK))
                .as("ingestion and cleanup of the same job must not deadlock").isFalse();
        assertThat(ingestFailure).as("ingestion").isNull();
        assertThat(sweepFailure).as("cleanup").isNull();
        assertThat(deletedAt(p.jobId())).as("the re-ingested job stays live").isNull();
        assertThat(observationStatus(p)).isEqualTo("SEEN");
    }

    @Test
    void anAtsObservationArrivingWhileTheAggregatorObservationExpiresKeepsTheJob() throws Exception {
        Posting p = staleAggregatorPosting();
        String atsExternalId = "ats-" + unique();
        String atsUrl = "https://boards.greenhouse.io/acme/jobs/" + atsExternalId;

        Throwable sweepFailure;
        try (Connection atsIngest = rawTransaction()) {
            exec(atsIngest, "update jobs set last_seen_at = now() where id = ?", p.jobId());
            Future<?> sweep = pool.submit(() -> ingestion.scheduledDiscoveryMaintenance());
            awaitLockWaiter();
            // The ATS posting for the same job is recorded and committed while the cleanup is waiting.
            exec(atsIngest, """
                    insert into job_source_observations(id, job_id, source_type, provider, source_url, external_job_id,
                        content_hash, extraction_hash, extraction_confidence, last_seen_at, source_id, source_status, removed_at)
                    values (?, ?, 'JOB_SOURCE', ?, ?, ?, 'h', 'h', 1.0, now(), ?, 'SEEN', null)
                    """, UuidV7.generate(), p.jobId(), "job-source:" + ats, atsUrl, atsExternalId, ats);
            atsIngest.commit();
            sweepFailure = failureOf(sweep);
        }

        assertThat(sweepFailure).as("cleanup").isNull();
        assertThat(deletedAt(p.jobId())).as("a live ATS observation committed during the sweep keeps the job").isNull();
        assertThat(observationStatus(p)).as("the stale aggregator observation still expires").isEqualTo("MISSING");
        assertThat(jdbc.queryForObject("select source_status from job_source_observations where job_id = ? and source_id = ?",
                String.class, p.jobId(), ats)).isEqualTo("SEEN");
    }

    @Test
    void cleanupLocksAffectedJobsInAscendingIdOrderBeforeTouchingAnyObservation() throws Exception {
        List<Posting> postings = List.of(staleAggregatorPosting(), staleAggregatorPosting(), staleAggregatorPosting());
        List<UUID> ordered = jdbc.queryForList("select id from jobs where id in (?, ?, ?) order by id", UUID.class,
                postings.get(0).jobId(), postings.get(1).jobId(), postings.get(2).jobId());
        UUID lowest = ordered.get(0);
        UUID middle = ordered.get(1);
        UUID highest = ordered.get(2);

        Throwable sweepFailure;
        try (Connection blocker = rawTransaction(); Connection probe = rawTransaction()) {
            exec(blocker, "select id from jobs where id = ? for update", middle);
            Future<?> sweep = pool.submit(() -> ingestion.scheduledDiscoveryMaintenance());
            awaitLockWaiter();

            assertThat(lockableNow(probe, "select id from jobs where id = ? for update nowait", lowest))
                    .as("the lower id is already locked by the cleanup").isFalse();
            assertThat(lockableNow(probe, "select id from jobs where id = ? for update nowait", highest))
                    .as("the higher id is not locked yet: locks are taken in ascending order").isTrue();
            assertThat(lockableNow(probe, "select id from job_source_observations where job_id = ? for update nowait", lowest))
                    .as("no observation row is locked while job locks are still being acquired").isTrue();

            blocker.rollback();
            sweepFailure = failureOf(sweep);
        }

        assertThat(sweepFailure).as("cleanup").isNull();
        for (UUID id : ordered) {
            assertThat(deletedAt(id)).as("stale aggregator-only job %s", id).isNotNull();
        }
    }

    @Test
    void repeatedConcurrentReIngestionAndCleanupNeverDeadlockAndLoseNoJob() throws Exception {
        // 15 rounds by default; soak runs pass e.g. -Df9.rounds=200.
        int rounds = Integer.getInteger("f9.rounds", 15);
        for (int round = 0; round < rounds; round++) {
            List<Posting> postings = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                postings.add(staleAggregatorPosting());
            }
            Posting control = liveAtsPosting();

            CountDownLatch start = new CountDownLatch(1);
            Future<?> sweep = pool.submit(() -> {
                start.await();
                ingestion.scheduledDiscoveryMaintenance();
                return null;
            });
            Future<?> reIngest = pool.submit(() -> {
                start.await();
                for (Posting p : postings) {
                    reIngest(p);
                }
                return null;
            });
            start.countDown();

            assertThat(failureOf(sweep)).as("round %d cleanup", round).isNull();
            assertThat(failureOf(reIngest)).as("round %d re-ingestion", round).isNull();
            for (Posting p : postings) {
                assertThat(deletedAt(p.jobId())).as("round %d: re-ingested job %s", round, p.jobId()).isNull();
                assertThat(observationStatus(p)).as("round %d: observation of %s", round, p.jobId()).isEqualTo("SEEN");
            }
            assertThat(deletedAt(control.jobId())).as("round %d: unrelated live ATS job", round).isNull();
        }
    }

    @Test
    void aFailedCleanupRollsBackTheExpiryAndTheDeletion() {
        Posting expiring = staleAggregatorPosting();
        Posting archiveCandidate = liveAtsPosting();
        jdbc.update("update jobs set status = 'DISCOVERED', last_seen_at = now() - interval '31 days' where id = ?",
                archiveCandidate.jobId());

        // Fail the LAST step (archiving), after the expiry and the deletion have already run in the transaction.
        jdbc.execute("""
                create or replace function f9_test_fail_archive() returns trigger language plpgsql as $$
                begin raise exception 'F9 test: injected failure while archiving'; end $$
                """);
        jdbc.execute("create trigger f9_test_fail_archive before update on jobs for each row "
                + "when (new.status = 'ARCHIVED' and old.status is distinct from 'ARCHIVED' and new.id = '"
                + archiveCandidate.jobId() + "'::uuid) execute function f9_test_fail_archive()");
        try {
            assertThatThrownBy(() -> ingestion.scheduledDiscoveryMaintenance()).isInstanceOf(DataAccessException.class);
        } finally {
            jdbc.execute("drop trigger if exists f9_test_fail_archive on jobs");
            jdbc.execute("drop function if exists f9_test_fail_archive()");
        }

        assertThat(observationStatus(expiring)).as("expiry rolled back").isEqualTo("SEEN");
        assertThat(deletedAt(expiring.jobId())).as("deletion rolled back").isNull();
        assertThat(status(archiveCandidate.jobId())).as("archiving rolled back").isEqualTo("DISCOVERED");

        // A clean re-run then completes the work.
        ingestion.scheduledDiscoveryMaintenance();
        assertThat(observationStatus(expiring)).isEqualTo("MISSING");
        assertThat(deletedAt(expiring.jobId())).isNotNull();
        assertThat(status(archiveCandidate.jobId())).isEqualTo("ARCHIVED");
    }

    @Test
    void aSecondConcurrentCleanupSkipsInsteadOfQueueingBehindTheFirst() throws Exception {
        Posting p = staleAggregatorPosting();
        try (Connection blocker = rawTransaction()) {
            exec(blocker, "select id from jobs where id = ? for update", p.jobId());
            Future<?> first = pool.submit(() -> ingestion.scheduledDiscoveryMaintenance());
            awaitLockWaiter();

            Future<?> second = pool.submit(() -> ingestion.scheduledDiscoveryMaintenance());
            try {
                second.get(10, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                fail("the second cleanup queued behind the first instead of skipping");
            }
            assertThat(first.isDone()).as("the first cleanup is still waiting for the job lock").isFalse();

            blocker.rollback();
            assertThat(failureOf(first)).as("first cleanup").isNull();
        }
        assertThat(deletedAt(p.jobId())).as("the first cleanup did the work").isNotNull();
    }

    @Test
    void anInsertThatOnlyReferencesALockedJobIsNotHeldUpByTheCleanup() throws Exception {
        // The cleanup locks the lower id, then waits for the higher one, which a blocker holds.
        Posting first = staleAggregatorPosting();
        Posting second = staleAggregatorPosting();
        List<UUID> ordered = jdbc.queryForList("select id from jobs where id in (?, ?) order by id", UUID.class,
                first.jobId(), second.jobId());
        UUID held = ordered.get(0);
        UUID blocked = ordered.get(1);
        UUID profile = jdbc.queryForObject(
                "select p.id from profiles p join users u on u.id = p.user_id where u.email = 'dev@example.local'",
                UUID.class);

        Throwable sweepFailure;
        try (Connection blocker = rawTransaction(); Connection probe = rawTransaction();
             Connection writer = rawTransaction()) {
            exec(blocker, "select id from jobs where id = ? for update", blocked);
            Future<?> sweep = pool.submit(() -> ingestion.scheduledDiscoveryMaintenance());
            awaitLockWaiter();
            assertThat(lockableNow(probe, "select id from jobs where id = ? for update nowait", held))
                    .as("the cleanup holds the lower job while it waits for the higher one").isFalse();

            // Matches, applications, bookmarks and notifications reference a job only through a foreign key, whose
            // check takes KEY SHARE on the job row. The cleanup's row lock must not make them wait.
            exec(writer, "set local lock_timeout = '3s'");
            exec(writer, "insert into job_bookmarks (id, profile_id, job_id, created_at) values (?, ?, ?, now())",
                    UuidV7.generate(), profile, held);
            writer.commit();

            blocker.rollback();
            sweepFailure = failureOf(sweep);
        }

        assertThat(sweepFailure).as("cleanup").isNull();
        assertThat(jdbc.queryForObject("select count(*)::int from job_bookmarks where job_id = ?", Integer.class, held))
                .as("the insert went through while the cleanup held the job").isEqualTo(1);
        assertThat(deletedAt(held)).as("the cleanup still did its work").isNotNull();
        assertThat(deletedAt(blocked)).isNotNull();
    }

    // ---------------------------------------------------------------------------------------------------------------

    private UUID source(String kind, String org) {
        UUID id = UuidV7.generate();
        jdbc.update("""
                insert into job_sources (id, kind, org_identifier, display_name, capabilities, policy)
                values (?, ?, ?, ?, '{"discovery": true}', 'DISCOVERY_ONLY')
                """, id, kind, org, org + " (" + kind + ")");
        return id;
    }

    /**
     * The production schedule ({@code @Scheduled(cron = "0 0 * * * *")}) also runs this cleanup in the test context.
     * A test that straddled the top of the hour could meet that run, so each test starts clear of it.
     */
    private static void stayClearOfTheHourlyRun() throws InterruptedException {
        LocalTime now = LocalTime.now();
        int secondsLeftInHour = 3600 - (now.getMinute() * 60 + now.getSecond());
        if (secondsLeftInHour <= 45) {
            Thread.sleep((secondsLeftInHour + 5) * 1000L);
        }
    }

    private static String unique() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private JobDiscoveryService.IngestResult ingest(UUID source, String externalId, String title, String applicationUrl,
                                                    String canonicalUrl) {
        return ingestion.ingestJob(new JobDiscoveryService.IngestJobCommand(source, externalId, null, "Acme Ltd", title,
                "London", null, null, null, null, null, null, null, null, "Body of " + title, List.of(),
                applicationUrl, canonicalUrl, null, null, null));
    }

    /** An aggregator-only job whose single observation is older than the 7-day aggregator TTL. */
    private Posting staleAggregatorPosting() {
        String n = unique();
        String externalId = "ts-" + n;
        String canonical = "https://tsenta.com/jobs/" + n;
        String title = "Aggregator role " + n;
        UUID jobId = ingest(aggregator, externalId, title, null, canonical).jobId();
        jdbc.update("update job_source_observations set last_seen_at = now() - interval '8 days' where job_id = ?", jobId);
        return new Posting(jobId, aggregator, externalId, canonical, title);
    }

    /** A job with a live ATS observation; the cleanup must never touch it. */
    private Posting liveAtsPosting() {
        String n = unique();
        String externalId = "gh-" + n;
        String url = "https://boards.greenhouse.io/acme/jobs/" + n;
        String title = "ATS role " + n;
        UUID jobId = ingest(ats, externalId, title, url, url).jobId();
        return new Posting(jobId, ats, externalId, url, title);
    }

    private void reIngest(Posting p) {
        ingest(p.sourceId(), p.externalId(), p.title(), null, p.canonicalUrl());
    }

    private Connection rawTransaction() throws SQLException {
        Connection c = dataSource.getConnection();
        c.setAutoCommit(false);
        try (var s = c.createStatement()) {
            s.execute("set statement_timeout = '20s'");
        }
        return c;
    }

    private static void exec(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.execute();
        }
    }

    /** The observation upsert of {@code JobDiscoveryService.recordObservation}, with the same conflict target. */
    private static void upsertObservationLikeRecordObservation(Connection c, Posting p) throws SQLException {
        exec(c, "insert into job_source_observations(id,job_id,source_type,provider,source_url,external_job_id,content_hash,"
                + "extraction_hash,extraction_confidence,last_seen_at,source_id,source_status,removed_at) "
                + "values(?,?,?,?,?,?,?,?,?,now(),?,'SEEN',null) on conflict(provider,source_url,external_job_id) do update set "
                + "job_id=excluded.job_id,last_seen_at=now(),content_hash=excluded.content_hash,source_id=excluded.source_id,"
                + "source_status='SEEN',removed_at=null",
                UuidV7.generate(), p.jobId(), "JOB_SOURCE", "job-source:" + p.sourceId(), p.canonicalUrl(), p.externalId(),
                "h", "h", 1.0, p.sourceId());
    }

    /**
     * Waits (polling, not sleeping blindly) until the cleanup itself is blocked on a lock. Only the cleanup's own
     * statements count: they are the only ones that use its temporary tables (this version's
     * {@code maintenance_locked_job_ids}, and {@code expired_job_ids}, which the previous version also used), so no
     * other backend waiting on a lock can end the wait early.
     */
    private void awaitLockWaiter() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("""
                    select count(*)::int from pg_stat_activity
                    where datname = current_database() and pid <> pg_backend_pid() and wait_event_type = 'Lock'
                      and (query like '%maintenance_locked_job_ids%' or query like '%expired_job_ids%')
                    """, Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(25);
        }
        fail("the cleanup never blocked on a lock");
    }

    /** Whether {@code sql} could take its row lock immediately; the probe is rolled back either way. */
    private static boolean lockableNow(Connection probe, String sql, UUID id) throws SQLException {
        try {
            exec(probe, sql, id);
            probe.rollback();
            return true;
        } catch (SQLException e) {
            probe.rollback();
            if (LOCK_NOT_AVAILABLE.equals(e.getSQLState())) {
                return false;
            }
            throw e;
        }
    }

    private static Throwable failureOf(Future<?> f) throws InterruptedException {
        try {
            f.get(30, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (java.util.concurrent.TimeoutException e) {
            return e;
        }
    }

    private static boolean isSqlState(Throwable t, String state) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException se && state.equals(se.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private Object deletedAt(UUID jobId) {
        return jdbc.queryForObject("select deleted_at from jobs where id = ?", Object.class, jobId);
    }

    private String status(UUID jobId) {
        return jdbc.queryForObject("select status from jobs where id = ?", String.class, jobId);
    }

    private String observationStatus(Posting p) {
        return jdbc.queryForObject("select source_status from job_source_observations where job_id = ? and source_id = ?",
                String.class, p.jobId(), p.sourceId());
    }
}
