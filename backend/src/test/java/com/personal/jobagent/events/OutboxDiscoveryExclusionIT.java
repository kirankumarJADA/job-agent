package com.personal.jobagent.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.discovery.JobDiscoveryService;
import com.personal.jobagent.llm.ModelRouter;
import com.personal.jobagent.notifications.NotificationEvents;
import com.personal.jobagent.notifications.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Database migration Option C (V052): the historical pending job.discovered
 * events are excluded from the drain, with the real dispatcher, the real
 * consumers and the real exclusion functions on PostgreSQL.
 *
 * <p>Each test restores the same shape as production's restored outbox:
 * historical job.discovered events (3 jobs seen 3 times), one of them already
 * dead-lettered and one part-way through its retries, with the three kinds of
 * user-facing event between them (verification.completed, job.matched,
 * signup.completed) and one user-facing dead letter. A recording consumer
 * sees every event that is handed to consumers, in order. The migration guard
 * is on, as at the cutover.
 */
@SpringBootTest(properties = {
        "spring.flyway.placeholders.remove_seed_dev_account=false",
        "app.migration.manual-review-only=true",
        // Startup's scheduled rounds stay no-ops; each test turns dispatch on
        // and drives it itself.
        "app.events.dispatch-enabled=false",
        "app.events.dispatch-interval-ms=86400000",
        "app.events.reconcile-interval-ms=86400000",
        "app.discovery.scheduler-enabled=false",
        "app.discovery.identity-backfill-enabled=false",
        "app.discovery.maintenance-enabled=false"})
@Testcontainers
class OutboxDiscoveryExclusionIT {

    private static final UUID SOURCE = UUID.fromString("00000000-0000-7000-8000-0000000e0a01");
    private static final UUID USER = UUID.fromString("00000000-0000-7000-8000-0000000e0b01");
    private static final UUID PROFILE = UUID.fromString("00000000-0000-7000-8000-0000000e0c01");
    private static boolean seeded;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    /** Records every event handed to consumers; can fail an event once, on request. */
    static final class Recorder implements EventHandler {
        final List<UUID> seen = Collections.synchronizedList(new ArrayList<>());
        final Set<UUID> failOnce = ConcurrentHashMap.newKeySet();

        @Override public String consumerName() { return "it-outbox-recorder"; }
        @Override public boolean supports(String eventType) { return true; }
        @Override public void handle(Envelope envelope) {
            if (failOnce.remove(envelope.id())) {
                throw new IllegalStateException("planned failure for " + envelope.id());
            }
            seen.add(envelope.id());
        }
    }

    @TestConfiguration
    static class RecorderConfig {
        @Bean Recorder outboxRecorder() { return new Recorder(); }
    }

    @MockBean ModelRouter modelRouter;

    @Autowired private JobDiscoveryService discovery;
    @Autowired private NotificationService notifications;
    @Autowired private OutboxProcessor outbox;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private Recorder recorder;

    /** The restored backlog: event ids by kind, in sequence order. */
    private record Backlog(List<UUID> historical, UUID historicalDead, UUID historicalRetrying,
                           List<UUID> userFacing, UUID userFacingDead) {
        List<UUID> historicalAlive() {
            return historical.stream().filter(id -> !id.equals(historicalDead)).toList();
        }
    }

    @BeforeEach
    void reset() {
        jdbc.execute("truncate outbox_exclusions, outbox_exclusion_batches, consumed_events, outbox_events");
        recorder.seen.clear();
        recorder.failOnce.clear();
        if (!seeded) {
            seed();
            seeded = true;
        }
        ReflectionTestUtils.setField(outbox, "dispatchEnabled", true);
    }

    @Test
    void excludedEventsAreNeverDispatchedRetriedConsumedOrDeadLettered() {
        Backlog b = buildBacklog("a");
        String pendingBefore = fingerprint("where o.published_at is null");
        String allBefore = fingerprint("");
        String historicalBefore = fingerprint("where o.published_at is null and o.event_type = 'job.discovered'");

        Map<String, Object> batch = exclude("it-a");

        assertThat(batch.get("created")).isEqualTo(true);
        assertThat(number(batch, "excluded_count")).isEqualTo(9);
        assertThat(number(batch, "max_sequence_id")).isEqualTo(sequenceOf(b.historical().get(8)));
        // The outbox rows are untouched, and the record matches them one for one.
        assertThat(fingerprint("where o.published_at is null")).isEqualTo(pendingBefore);
        assertThat(fingerprint("")).isEqualTo(allBefore);
        assertThat(jdbc.queryForList("select event_id from outbox_exclusions", UUID.class))
                .containsExactlyInAnyOrderElementsOf(b.historical());
        assertThat(count("select count(*) from outbox_exclusions where attempt_count = 5 and event_id = ?",
                b.historicalDead())).isEqualTo(1);

        // Written after the exclusion, as after the cutover.
        discovery.ingestJob(job("a-fresh", "Java Engineer fresh"));
        UUID freshDiscovered = jdbc.queryForObject(
                "select id from outbox_events where event_type = 'job.discovered' order by sequence_id desc limit 1", UUID.class);
        UUID freshSignup = userFacing(NotificationEvents.SIGNUP_COMPLETED);

        drain();
        outbox.reconcileDlq();

        // Excluded: never handed to a consumer, never published, retried or consumed.
        assertThat(recorder.seen).doesNotContainAnyElementsOf(b.historical());
        assertThat(count("select count(*) from outbox_events o join outbox_exclusions x on x.event_id = o.id "
                + "where o.published_at is not null or o.attempt_count <> x.attempt_count")).isZero();
        assertThat(count("select count(*) from consumed_events c join outbox_exclusions x on x.event_id = c.event_id"))
                .isZero();
        assertThat(jdbc.queryForObject("select last_error from outbox_events where id = ?", String.class,
                b.historicalRetrying())).isEqualTo("restored retry");
        // Dead letters: the excluded one raises nothing; the user-facing one still does.
        assertThat(count("select count(*) from notifications where dedup_key = ?", "outbox-dlq:" + b.historicalDead()))
                .isZero();
        assertThat(count("select count(*) from notifications where dedup_key = ?", "outbox-dlq:" + b.userFacingDead()))
                .isEqualTo(1);
        // Everything else ran once, in sequence order: the user-facing events
        // below the watermark first, then what came after, then what they caused.
        List<UUID> published = jdbc.queryForList(
                "select id from outbox_events where published_at is not null order by sequence_id", UUID.class);
        assertThat(recorder.seen).containsExactlyElementsOf(published);
        assertThat(recorder.seen).containsAll(b.userFacing()).contains(freshDiscovered, freshSignup);
        assertThat(recorder.seen.subList(0, 3)).containsExactlyElementsOf(b.userFacing());
        // The excluded rows are byte for byte what was restored, after the whole drain.
        assertThat(fingerprint("where o.id in (select event_id from outbox_exclusions)")).isEqualTo(historicalBefore);
    }

    @Test
    void aFailingEventIsRetriedInOrderWhileTheExcludedOnesStayUntouched() {
        Backlog b = buildBacklog("b");
        exclude("it-b");
        UUID flaky = b.userFacing().get(0);
        recorder.failOnce.add(flaky);

        outbox.dispatchPending();

        assertThat(count("select attempt_count from outbox_events where id = ?", flaky)).isEqualTo(1);
        // The publisher records its own wrapper around the handler's exception.
        assertThat(jdbc.queryForObject("select last_error from outbox_events where id = ?", String.class, flaky))
                .isEqualTo("Handler it-outbox-recorder failed for event " + flaky);
        assertThat(count("select count(*) from outbox_events where id = ? and published_at is null", flaky)).isEqualTo(1);
        assertThat(recorder.seen).doesNotContain(flaky).contains(b.userFacing().get(1), b.userFacing().get(2));

        drain();

        assertThat(count("select count(*) from outbox_events where id = ? and published_at is not null", flaky))
                .isEqualTo(1);
        assertThat(recorder.seen).containsOnlyOnce(flaky);
        assertThat(count("select count(*) from outbox_events o join outbox_exclusions x on x.event_id = o.id "
                + "where o.published_at is not null or o.attempt_count <> x.attempt_count")).isZero();
        assertThat(recorder.seen).doesNotContainAnyElementsOf(b.historical());
    }

    @Test
    void concurrentRunsRecordOneBatchAndNothingCommitsBelowTheWatermarkUnrecorded() throws Exception {
        buildBacklog("c");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch writersRunning = new CountDownLatch(2);
        AtomicBoolean writing = new AtomicBoolean(true);
        try {
            // Two writers each commit 25 new job.discovered events, one
            // transaction each, before, during and after the exclusion.
            List<Future<Integer>> writers = new ArrayList<>();
            for (int w = 0; w < 2; w++) {
                int writer = w;
                writers.add(pool.submit(() -> {
                    int n = 0;
                    while (writing.get() && n < 25) {
                        discovery.ingestJob(job("c-w" + writer + "-" + n, "Java Engineer c" + writer + " " + n));
                        n++;
                        if (n == 3) writersRunning.countDown();
                    }
                    return n;
                }));
            }
            // Once both are writing, two operators run the same exclusion at the same moment.
            assertThat(writersRunning.await(60, TimeUnit.SECONDS)).isTrue();
            CountDownLatch start = new CountDownLatch(1);
            Future<Map<String, Object>> first = pool.submit(() -> { start.await(); return exclude("it-c"); });
            Future<Map<String, Object>> second = pool.submit(() -> { start.await(); return exclude("it-c"); });
            start.countDown();
            Map<String, Object> r1 = first.get(60, TimeUnit.SECONDS);
            Map<String, Object> r2 = second.get(60, TimeUnit.SECONDS);
            int written = 0;
            for (Future<Integer> f : writers) written += f.get(120, TimeUnit.SECONDS);
            assertThat(written).isEqualTo(50);
            long writtenAfter = count("select count(*) from outbox_events where event_type = 'job.discovered' "
                    + "and sequence_id > ?", number(r1, "max_sequence_id"));

            assertThat(List.of(r1.get("created"), r2.get("created"))).containsExactlyInAnyOrder(true, false);
            assertThat(r1.get("batch_id")).isEqualTo(r2.get("batch_id"));
            assertThat(count("select count(*) from outbox_exclusion_batches")).isEqualTo(1);
            long watermark = number(r1, "max_sequence_id");
            long recorded = count("select count(*) from outbox_exclusions");
            assertThat(recorded).isEqualTo(number(r1, "excluded_count")).isGreaterThanOrEqualTo(9);
            // What the dispatcher relies on: at or below the watermark every
            // pending job.discovered is recorded, above it none is.
            assertThat(count("""
                    select count(*) from outbox_events o
                     where o.published_at is null and o.event_type = 'job.discovered' and o.sequence_id <= ?
                       and not exists (select 1 from outbox_exclusions x where x.event_id = o.id)
                    """, watermark)).isZero();
            assertThat(count("select count(*) from outbox_exclusions where sequence_id > ?", watermark)).isZero();

            // Repeating it changes nothing; another batch is refused while this one is active.
            assertThat(exclude("it-c").get("created")).isEqualTo(false);
            assertThat(count("select count(*) from outbox_exclusions")).isEqualTo(recorded);
            assertThatThrownBy(() -> exclude("it-c-other")).hasMessageContaining("already active");

            // What the writers committed after the exclusion is dispatched; the recorded events are not.
            drain();
            assertThat(count("select count(*) from outbox_events where event_type = 'job.discovered' "
                    + "and sequence_id > ? and published_at is null", watermark)).isZero();
            assertThat(count("select count(*) from outbox_events o join outbox_exclusions x on x.event_id = o.id "
                    + "where o.published_at is not null")).isZero();
            System.out.printf("EXCLUSION concurrent: %d events written by 2 writers, %d recorded (9 restored + %d "
                            + "committed before the lock), %d committed after it; watermark %d%n",
                    written, recorded, recorded - 9, writtenAfter, watermark);
            assertThat(recorded - 9 + writtenAfter).as("every written event is either recorded or above the watermark")
                    .isEqualTo(50);
        } finally {
            writing.set(false);
            pool.shutdownNow();
        }
    }

    @Test
    void revertingBeforeDispatchRestoresTheWholeBacklogInOrder() {
        Backlog b = buildBacklog("d");
        String pendingBefore = fingerprint("where o.published_at is null");
        exclude("it-d");

        Map<String, Object> reverted = revert("it-d", false);

        assertThat(reverted.get("reverted")).isEqualTo(true);
        assertThat(number(reverted, "still_pending")).isEqualTo(9);
        assertThat(outbox.exclusionWatermark()).isZero();
        assertThat(fingerprint("where o.published_at is null")).isEqualTo(pendingBefore);
        // The audit trail is kept, stamped.
        assertThat(count("select count(*) from outbox_exclusion_batches where label = 'it-d' "
                + "and reverted_at is not null and reverted_by = 'it' and revert_reason = 'IT revert'")).isEqualTo(1);
        assertThat(count("select count(*) from outbox_exclusions")).isEqualTo(9);
        // A second revert changes nothing; the label cannot be reused; a new one can.
        assertThat(revert("it-d", false).get("reverted")).isEqualTo(false);
        assertThatThrownBy(() -> exclude("it-d")).hasMessageContaining("was reverted");
        assertThat(number(exclude("it-d2"), "excluded_count")).isEqualTo(9);
        revert("it-d2", false);

        drain();

        // Option A behaviour: every live historical event is dispatched, in sequence order.
        List<UUID> published = jdbc.queryForList(
                "select id from outbox_events where published_at is not null order by sequence_id", UUID.class);
        assertThat(recorder.seen).containsExactlyElementsOf(published);
        assertThat(recorder.seen).containsAll(b.historicalAlive()).doesNotContain(b.historicalDead());
    }

    @Test
    void anExclusionThatDoesNotCommitLeavesNothingBehind() {
        assertThatThrownBy(() -> exclude("it-empty")).hasMessageContaining("no pending job.discovered");
        buildBacklog("e");
        TransactionTemplate tx = new TransactionTemplate(transactions);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            exclude("it-e");
            assertThat(count("select count(*) from outbox_exclusions")).isEqualTo(9);
            throw new IllegalStateException("operator aborted");
        })).hasMessage("operator aborted");

        assertThat(count("select count(*) from outbox_exclusion_batches")).isZero();
        assertThat(count("select count(*) from outbox_exclusions")).isZero();
        assertThat(outbox.exclusionWatermark()).isZero();
        assertThatThrownBy(() -> jdbc.queryForMap("select * from outbox_exclude_pending_discovery(?, ?, ?)",
                "it-e", " ", "it")).hasMessageContaining("a reason is required");
        assertThat(count("select count(*) from outbox_exclusion_batches")).isZero();
        // Retried properly, it goes through.
        assertThat(exclude("it-e").get("created")).isEqualTo(true);
        assertThat(count("select count(*) from outbox_exclusions")).isEqualTo(9);
    }

    @Test
    void revertingAfterDispatchHasStartedNeedsForce() {
        Backlog b = buildBacklog("f");
        exclude("it-f");
        drain();

        assertThatThrownBy(() -> revert("it-f", false)).hasMessageContaining("p_force");
        assertThat(outbox.exclusionWatermark()).isPositive();

        assertThat(revert("it-f", true).get("reverted")).isEqualTo(true);
        drain();
        assertThat(recorder.seen).containsAll(b.historicalAlive());
    }

    @Test
    void theDispatcherDoesNotReadTheExcludedRows() throws Exception {
        buildBacklog("g");
        jdbc.update("""
                insert into outbox_events (id, aggregate_type, aggregate_id, event_type, payload, correlation_id)
                select gen_random_uuid(), 'JOB', gen_random_uuid(), 'job.discovered', '{"action":"TOUCHED"}'::jsonb,
                       gen_random_uuid()
                  from generate_series(1, 20000)
                """);
        long watermark = number(exclude("it-g"), "max_sequence_id");
        jdbc.execute("analyze outbox_events");

        String sql = withLiterals(OutboxProcessor.PENDING_WITH_EXCLUSION_SQL,
                5, watermark, 50, 5, watermark, 50, 50);
        JsonNode plan = new ObjectMapper().readTree(
                jdbc.queryForObject("explain (analyze, format json) " + sql, String.class)).get(0).get("Plan");
        long[] read = new long[1];
        Set<String> indexes = ConcurrentHashMap.newKeySet();
        walk(plan, node -> {
            // Bitmap scans name the index on a child node that has no relation.
            if (node.has("Index Name")) indexes.add(node.get("Index Name").asText());
            if ("outbox_events".equals(node.path("Relation Name").asText())) {
                read[0] += node.path("Actual Rows").asLong() * Math.max(1, node.path("Actual Loops").asLong())
                        + node.path("Rows Removed by Filter").asLong();
            }
        });

        System.out.printf("EXCLUSION plan: %d outbox rows read with 20009 excluded, indexes %s%n", read[0], indexes);
        assertThat(indexes).contains("outbox_pending_non_discovery_idx");
        assertThat(read[0]).as("outbox rows the dispatcher read").isLessThan(100);
    }

    @Test
    void theOperatorScriptsRunUnderPsql() throws Exception {
        buildBacklog("p");
        // The repository's own scripts, as the runbook runs them (the tests run from backend/).
        Path dir = Path.of("..", "scripts", "db-migration", "cutover").toAbsolutePath().normalize();
        for (String script : List.of("outbox-exclusion-apply.sql", "outbox-exclusion-check.sql",
                "outbox-exclusion-revert.sql")) {
            // Written through a shell heredoc rather than the container copy API.
            String content = Files.readString(dir.resolve(script));
            assertThat(content).doesNotContain("ROBIN_SCRIPT_EOF");
            var written = postgres.execInContainer("sh", "-c",
                    "cat > /tmp/" + script + " <<'ROBIN_SCRIPT_EOF'\n" + content + "\nROBIN_SCRIPT_EOF\n"
                            + "test -s /tmp/" + script);
            assertThat(written.getExitCode()).as(script + ": " + written.getStderr()).isZero();
        }
        Map<String, String> before = psqlCheck();
        assertThat(before).containsEntry("active_batches", "0").containsEntry("watermark", "0")
                .containsEntry("pending_total", "13").containsEntry("dispatchable_pending", "11");

        var applied = psql("outbox-exclusion-apply.sql", "label=it-psql", "actor=it", "reason=IT psql run");
        assertThat(applied.getExitCode()).as(applied.getStderr()).isZero();
        assertThat(applied.getStdout()).containsPattern("\\|it-psql\\|\\d+\\|9\\|t");
        Map<String, String> after = psqlCheck();
        assertThat(after).containsEntry("active_batches", "1").containsEntry("active_label", "it-psql")
                .containsEntry("excluded_recorded", "9").containsEntry("excluded_still_pending", "9")
                .containsEntry("dispatchable_pending", "3").containsEntry("dead_letters_not_excluded", "1")
                .containsEntry("unrecorded_below_watermark", "0").containsEntry("recorded_but_published", "0")
                .containsEntry("recorded_out_of_scope", "0")
                .containsEntry("pending_total", before.get("pending_total"))
                .containsEntry("pending_fingerprint", before.get("pending_fingerprint"));

        // The same label again changes nothing; a missing variable runs nothing.
        assertThat(psql("outbox-exclusion-apply.sql", "label=it-psql", "actor=it", "reason=again").getStdout())
                .containsPattern("\\|it-psql\\|\\d+\\|9\\|f");
        assertThat(psql("outbox-exclusion-apply.sql", "label=it-psql-2", "actor=it").getExitCode()).isNotZero();
        assertThat(count("select count(*) from outbox_exclusion_batches")).isEqualTo(1);

        var reverted = psql("outbox-exclusion-revert.sql", "label=it-psql", "actor=it", "reason=IT psql revert");
        assertThat(reverted.getExitCode()).as(reverted.getStderr()).isZero();
        assertThat(reverted.getStdout()).containsPattern("\\|it-psql\\|9\\|9\\|t");
        assertThat(psqlCheck()).containsEntry("active_batches", "0").containsEntry("dispatchable_pending", "11")
                .containsEntry("pending_fingerprint", before.get("pending_fingerprint"));
    }

    private org.testcontainers.containers.Container.ExecResult psql(String script, String... variables)
            throws Exception {
        List<String> command = new ArrayList<>(List.of("psql", "-X", "-At", "-v", "ON_ERROR_STOP=1",
                "-U", postgres.getUsername(), "-d", postgres.getDatabaseName()));
        for (String variable : variables) {
            command.add("-v");
            command.add(variable);
        }
        command.add("-f");
        command.add("/tmp/" + script);
        return postgres.execInContainer(command.toArray(String[]::new));
    }

    private Map<String, String> psqlCheck() throws Exception {
        var result = psql("outbox-exclusion-check.sql");
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : result.getStdout().split("\\R")) {
            int bar = line.indexOf('|');
            if (bar > 0) values.put(line.substring(0, bar), line.substring(bar + 1));
        }
        return values;
    }

    // ── fixture ──────────────────────────────────────────────────────

    private void seed() {
        jdbc.update("""
                insert into users (id, email, password_hash, display_name, auth_provider)
                values (?, 'exclusion-it@example.com', 'x', 'Exclusion IT', 'LOCAL') on conflict (email) do nothing
                """, USER);
        jdbc.update("insert into profiles (id, user_id) values (?, ?) on conflict do nothing", PROFILE, USER);
        jdbc.update("""
                insert into preference_sets (id, profile_id, application_mode, scoring_weights, salary_min_gbp)
                values (?, ?, 'ASSISTED',
                        '{"skill":100,"experience":0,"visa":0,"location":0,"salary":0,"career":0,"difficulty":0}', 40000)
                """, UUID.randomUUID(), PROFILE);
        for (String skill : new String[]{"Java", "Spring"}) {
            jdbc.update("insert into skills (id, profile_id, name, category, mastery) values (?, ?, ?, 'Test', 4)",
                    UUID.randomUUID(), PROFILE, skill);
        }
        jdbc.update("""
                insert into job_sources (id, kind, org_identifier, display_name, capabilities, policy)
                values (?, 'GREENHOUSE', 'exclusion-it', 'Exclusion IT', '{}', 'DISCOVERY_ONLY')
                on conflict (kind, org_identifier) do nothing
                """, SOURCE);
    }

    /** An unresolvable host: nothing here can reach a real job board. */
    private static JobDiscoveryService.IngestJobCommand job(String externalId, String title) {
        String url = "https://jobs.exclusion-it.invalid/" + externalId;
        return new JobDiscoveryService.IngestJobCommand(
                SOURCE, externalId, null, "Exclusion IT Corp", title, "London", "London", "GB", "HYBRID", "FULL_TIME",
                "MID", 70000, 90000, "GBP", "Java and Spring services.", List.of("Java", "Spring"), url, url, null);
    }

    private UUID userFacing(String eventType) {
        return notifications.emit(new NotificationService.NotificationCommand(eventType, "USER", USER,
                Map.of("message", "Exclusion IT " + eventType), UuidV7.generate(), null));
    }

    private UUID jobMatched(UUID jobId) {
        return notifications.emit(new NotificationService.NotificationCommand(NotificationEvents.JOB_MATCHED, "JOB",
                jobId, Map.of("job_id", jobId.toString(), "profile_id", PROFILE.toString(), "job_title", "Java Engineer",
                        "company", "Exclusion IT Corp", "score", 88, "recommendation", "APPLY",
                        "message", "New job match", "detail", "Scored 88/100"),
                UuidV7.generate(), null));
    }

    /** Three jobs seen three times each, the user-facing kinds between them, then the restored attempt state. */
    private Backlog buildBacklog(String tag) {
        List<UUID> userFacing = new ArrayList<>();
        UUID firstJob = null;
        for (int j = 0; j < 3; j++) {
            for (int crawl = 0; crawl < 3; crawl++) {
                UUID jobId = discovery.ingestJob(job(tag + "-" + j, "Java Engineer " + tag + " " + j)).jobId();
                if (firstJob == null) firstJob = jobId;
            }
            userFacing.add(switch (j) {
                case 0 -> userFacing(NotificationEvents.VERIFICATION_COMPLETED);
                case 1 -> jobMatched(firstJob);
                default -> userFacing(NotificationEvents.SIGNUP_COMPLETED);
            });
        }
        UUID userFacingDead = userFacing(NotificationEvents.VERIFICATION_COMPLETED);
        jdbc.update("update outbox_events set attempt_count = 5, last_error = 'restored dead letter' where id = ?",
                userFacingDead);
        List<UUID> historical = jdbc.queryForList(
                "select id from outbox_events where event_type = 'job.discovered' order by sequence_id", UUID.class);
        assertThat(historical).hasSize(9);
        jdbc.update("update outbox_events set attempt_count = 5, last_error = 'restored dead letter' where id = ?",
                historical.get(0));
        jdbc.update("update outbox_events set attempt_count = 2, last_error = 'restored retry' where id = ?",
                historical.get(4));
        return new Backlog(historical, historical.get(0), historical.get(4), userFacing, userFacingDead);
    }

    private Map<String, Object> exclude(String label) {
        return jdbc.queryForMap("select * from outbox_exclude_pending_discovery(?, ?, ?)",
                label, "IT: historical discovery backlog", "it");
    }

    private Map<String, Object> revert(String label, boolean force) {
        return jdbc.queryForMap("select * from outbox_revert_discovery_exclusion(?, ?, ?, ?)",
                label, "IT revert", "it", force);
    }

    /** Dispatches until nothing eligible is left (excluded events stay pending by design). */
    private int drain() {
        for (int round = 0; round < 200; round++) {
            long watermark = count(OutboxProcessor.EXCLUSION_WATERMARK_SQL);
            if (count("select count(*) from outbox_events where published_at is null and attempt_count < 5 "
                    + "and not (event_type = 'job.discovered' and sequence_id <= ?)", watermark) == 0) {
                return round;
            }
            outbox.dispatchPending();
        }
        throw new AssertionError("the outbox did not drain");
    }

    /** The restore tool's fingerprint: row count and the sum of the first 60 bits of md5(to_jsonb(row)). */
    private String fingerprint(String where) {
        return jdbc.queryForObject("select count(*) || ':' || coalesce(sum(('x' || substr(md5(to_jsonb(o)::text), 1, 15))"
                + "::bit(60)::bigint), 0) from outbox_events o " + where, String.class);
    }

    private long sequenceOf(UUID eventId) {
        return count("select sequence_id from outbox_events where id = ?", eventId);
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private static long number(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).longValue();
    }

    private static String withLiterals(String sql, long... values) {
        StringBuilder out = new StringBuilder();
        int next = 0;
        for (char c : sql.toCharArray()) {
            if (c == '?') out.append(values[next++]); else out.append(c);
        }
        assertThat(next).isEqualTo(values.length);
        return out.toString();
    }

    private static void walk(JsonNode node, java.util.function.Consumer<JsonNode> visit) {
        visit.accept(node);
        for (JsonNode child : node.path("Plans")) walk(child, visit);
    }
}
