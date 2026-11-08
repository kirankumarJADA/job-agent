package com.personal.jobagent.events;

import com.personal.jobagent.discovery.JobDiscoveryService;
import com.personal.jobagent.llm.LlmCompletion;
import com.personal.jobagent.llm.ModelRouter;
import com.personal.jobagent.llm.RoutingTrace;
import com.personal.jobagent.llm.TaskType;
import com.personal.jobagent.notifications.NotificationEvents;
import com.personal.jobagent.notifications.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Database migration Phase 2 evidence: is it safe to replace a backlog of
 * pending {@code job.discovered} events with ONE rebuilt event per job?
 *
 * <p>Production holds 771,636 pending {@code job.discovered} events for about
 * 61,000 jobs (about 12.7 per job, mostly TOUCHED re-crawls). This test builds
 * such a backlog through the REAL ingestion path, snapshots the whole database,
 * then drains it twice with the real {@link OutboxProcessor} and both real
 * consumers ({@code application-pipeline}, {@code notification-fanout}):
 *
 * <ol>
 *   <li>A, the exact backlog: every pending event, in sequence order;</li>
 *   <li>B, the rebuilt backlog: the database restored to the snapshot, the
 *       pending {@code job.discovered} events replaced by one NEW event per
 *       job (new id and correlation id, action TOUCHED, the job's own source)
 *       at the sequence position of that job's last pending event. Other
 *       pending events (the user-facing ones) are kept as they are.</li>
 * </ol>
 *
 * <p>It then asserts that A and B leave the same business state: jobs,
 * matches, decisions, applications, their timeline, notifications and every
 * other table's row count. Only the bookkeeping is allowed to differ
 * (outbox rows, consumed_events) and the test states by how much.
 */
@SpringBootTest(properties = {
        "spring.flyway.placeholders.remove_seed_dev_account=false",
        "app.discovery.scheduler-enabled=false",
        // The test drives the dispatcher itself, so that nothing runs between
        // building the backlog and taking the snapshot.
        "app.events.dispatch-interval-ms=86400000",
        "app.events.reconcile-interval-ms=86400000"})
@Testcontainers
class OutboxBacklogCompactionEquivalenceIT {

    private static final UUID SOURCE_A = UUID.fromString("00000000-0000-7000-8000-0000000c0a01");
    private static final UUID SOURCE_B = UUID.fromString("00000000-0000-7000-8000-0000000c0a02");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @MockBean ModelRouter modelRouter;

    @Autowired private JobDiscoveryService discovery;
    @Autowired private OutboxProcessor outbox;
    @Autowired private NotificationService notifications;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void oneRebuiltEventPerJobLeavesTheSameStateAsTheWholeBacklog() {
        when(modelRouter.execute(eq(TaskType.COVER_LETTER), any(), any(Duration.class)))
                .thenAnswer(inv -> new ModelRouter.ExecutionResult(
                        new LlmCompletion("Deterministic cover letter.", new LlmCompletion.TokenUsage(10, 20), 5,
                                LlmCompletion.FinishReason.STOP),
                        new RoutingTrace(TaskType.COVER_LETTER, "mock", "test", List.of())));
        when(modelRouter.execute(eq(TaskType.APPLICATION_QA), any(), any(Duration.class)))
                .thenAnswer(inv -> new ModelRouter.ExecutionResult(
                        new LlmCompletion("Deterministic answer.", new LlmCompletion.TokenUsage(10, 20), 5,
                                LlmCompletion.FinishReason.STOP),
                        new RoutingTrace(TaskType.APPLICATION_QA, "mock", "test", List.of())));

        seedProfilesAndSources();
        drain(); // nothing should be pending yet; makes the start state explicit
        buildBacklog();

        Map<String, Object> pending = jdbc.queryForMap("""
                select count(*) filter (where event_type = 'job.discovered') as discovered,
                       count(distinct aggregate_id) filter (where event_type = 'job.discovered') as jobs,
                       count(*) filter (where event_type <> 'job.discovered') as other
                  from outbox_events where published_at is null
                """);
        long discovered = ((Number) pending.get("discovered")).longValue();
        long jobs = ((Number) pending.get("jobs")).longValue();
        assertThat(discovered).as("the backlog has several events per job").isGreaterThan(jobs * 2);
        assertThat(((Number) pending.get("other")).longValue()).as("user-facing events are pending too").isGreaterThan(0);

        snapshot();

        // A: the exact backlog.
        int roundsA = drain();
        Map<String, List<String>> stateA = businessState();
        Map<String, Long> bookkeepingA = bookkeeping();

        // B: the same start state, with one rebuilt job.discovered per job.
        restore();
        int replaced = jdbc.update("""
                with lastp as (
                  select aggregate_id, max(sequence_id) as seq
                    from outbox_events where published_at is null and event_type = 'job.discovered'
                   group by aggregate_id)
                delete from outbox_events o using lastp
                 where o.published_at is null and o.event_type = 'job.discovered' and o.aggregate_id = lastp.aggregate_id
                """);
        assertThat(replaced).isEqualTo((int) discovered);
        List<Map<String, Object>> last = snapshotLastPerJob();
        for (Map<String, Object> row : last) {
            UUID job = (UUID) row.get("aggregate_id");
            UUID source = (UUID) row.get("source_id");
            jdbc.update("""
                    insert into outbox_events (sequence_id, id, aggregate_type, aggregate_id, event_type, schema_version,
                                               payload, correlation_id, causation_id, created_at, published_at)
                    values (?, ?, 'JOB', ?, 'job.discovered', 1, ?::jsonb, ?, null, now(), null)
                    """, ((Number) row.get("seq")).longValue(), com.personal.jobagent.common.UuidV7.generate(), job,
                    "{\"action\":\"TOUCHED\",\"job_id\":\"" + job + "\",\"source_id\":\"" + (source == null ? "" : source) + "\"}",
                    com.personal.jobagent.common.UuidV7.generate());
        }
        assertThat(last).hasSize((int) jobs);
        int roundsB = drain();
        Map<String, List<String>> stateB = businessState();
        Map<String, Long> bookkeepingB = bookkeeping();

        System.out.printf("EQUIVALENCE backlog: %d job.discovered events for %d jobs, %d other pending%n",
                discovered, jobs, ((Number) pending.get("other")).longValue());
        System.out.printf("EQUIVALENCE dispatcher rounds: exact %d, rebuilt %d%n", roundsA, roundsB);
        stateA.forEach((k, v) -> System.out.printf("EQUIVALENCE state %-28s %6d rows (exact) %6d rows (rebuilt)%n",
                k, v.size(), stateB.getOrDefault(k, List.of()).size()));
        bookkeepingA.forEach((k, v) -> System.out.printf("EQUIVALENCE bookkeeping %-22s %6d (exact) %6d (rebuilt)%n",
                k, v, bookkeepingB.get(k)));

        assertThat(stateB).as("business state after the rebuilt backlog").isEqualTo(stateA);
        assertThat(bookkeepingA.get("pending_after")).isZero();
        assertThat(bookkeepingB.get("pending_after")).isZero();
        assertThat(bookkeepingB.get("dead_letter")).isEqualTo(bookkeepingA.get("dead_letter"));
        assertThat(bookkeepingB.get("job.discovered")).isLessThan(bookkeepingA.get("job.discovered"));
        assertThat(bookkeepingB.get("consumed_events")).isLessThan(bookkeepingA.get("consumed_events"));
    }

    // ── fixture ──────────────────────────────────────────────────────

    private void seedProfilesAndSources() {
        UUID[] users = {UUID.fromString("00000000-0000-7000-8000-0000000c0b01"),
                UUID.fromString("00000000-0000-7000-8000-0000000c0b02"),
                UUID.fromString("00000000-0000-7000-8000-0000000c0b03")};
        UUID[] profiles = {UUID.fromString("00000000-0000-7000-8000-0000000c0c01"),
                UUID.fromString("00000000-0000-7000-8000-0000000c0c02"),
                UUID.fromString("00000000-0000-7000-8000-0000000c0c03")};
        for (int i = 0; i < 3; i++) {
            jdbc.update("""
                    insert into users (id, email, password_hash, display_name, auth_provider)
                    values (?, ?, 'x', ?, 'LOCAL') on conflict (email) do nothing
                    """, users[i], "equivalence-" + i + "@example.com", "Equivalence " + i);
            jdbc.update("insert into profiles (id, user_id) values (?, ?) on conflict do nothing", profiles[i], users[i]);
        }
        // Profile 0 auto-applies (CONTROLLED_AUTO with an enabled rule at 70):
        // APPLY matches create applications, which are then prepared.
        jdbc.update("""
                insert into preference_sets (id, profile_id, application_mode, scoring_weights, salary_min_gbp)
                values (?, ?, 'CONTROLLED_AUTO', '{"skill":70,"experience":10,"visa":5,"location":5,"salary":5,"career":5,"difficulty":0}', 40000)
                """, UUID.randomUUID(), profiles[0]);
        jdbc.update("insert into user_approval_rules (id, profile_id, auto_approve_enabled, min_score) values (?, ?, true, 70)",
                UUID.randomUUID(), profiles[0]);
        // Profile 1 is ASSISTED without a rule: APPLY matches below 85 go to
        // human review and raise approval-required notifications.
        jdbc.update("""
                insert into preference_sets (id, profile_id, application_mode, scoring_weights, salary_min_gbp)
                values (?, ?, 'ASSISTED', '{"skill":70,"experience":10,"visa":5,"location":5,"salary":5,"career":5,"difficulty":0}', 60000)
                """, UUID.randomUUID(), profiles[1]);
        // Profile 2 has no preferences at all, like two of production's three.
        for (UUID p : new UUID[]{profiles[0], profiles[1]}) {
            for (String skill : new String[]{"Java", "Spring"}) {
                jdbc.update("insert into skills (id, profile_id, name, category, mastery) values (?, ?, ?, 'Test', 4)",
                        UUID.randomUUID(), p, skill);
            }
        }
        jdbc.update("""
                insert into job_sources (id, kind, org_identifier, display_name, capabilities, policy)
                values (?, 'GREENHOUSE', 'equivalence-a', 'Equivalence A', '{}', 'DISCOVERY_ONLY'),
                       (?, 'LEVER', 'equivalence-b', 'Equivalence B', '{}', 'DISCOVERY_ONLY')
                on conflict (kind, org_identifier) do nothing
                """, SOURCE_A, SOURCE_B);
    }

    private JobDiscoveryService.IngestJobCommand job(UUID source, String ext, String title, String description,
                                                     List<String> skills, int salaryMin, String url) {
        return new JobDiscoveryService.IngestJobCommand(
                source, ext, null, "Equivalence Corp", title, "London", "London", "GB", "HYBRID", "FULL_TIME", "MID",
                salaryMin, salaryMin + 20000, "GBP", description, skills, url, url, null);
    }

    /** The production pattern: first sight (INSERTED), re-crawls (TOUCHED), edits (UPDATED), other boards. */
    private void buildBacklog() {
        for (int j = 0; j < 14; j++) {
            boolean javaJob = j % 3 != 2;
            String title = javaJob ? "Java Platform Engineer " + j : "Marketing Lead " + j;
            String text = javaJob ? "Build Java services with Spring Boot. Role " + j : "Run brand campaigns. Role " + j;
            List<String> skills = javaJob ? List.of("Java", "Spring") : List.of("Marketing");
            int salary = j % 4 == 0 ? 30000 : 70000; // every fourth is below profile 0's floor
            String url = "https://boards.greenhouse.io/equivalence/jobs/" + (5000 + j);
            JobDiscoveryService.IngestJobCommand first = job(SOURCE_A, "eq-" + j, title, text, skills, salary, url);
            discovery.ingestJob(first);                                   // INSERTED
            int recrawls = 1 + (j * 5) % 12;                              // 1..12 TOUCHED
            for (int r = 0; r < recrawls; r++) discovery.ingestJob(first);
            if (j % 5 == 1) {                                             // UPDATED, then seen again
                discovery.ingestJob(job(SOURCE_A, "eq-" + j, title, text + " (updated)", skills, salary, url));
                discovery.ingestJob(job(SOURCE_A, "eq-" + j, title, text + " (updated)", skills, salary, url));
            }
            if (j % 4 == 3) {                                             // the same posting on another board
                discovery.ingestJob(job(SOURCE_B, "lever-" + j, title, text, skills, salary, url));
            }
        }
        // The user-facing pending events of production's mix, interleaved with the backlog.
        UUID someJob = jdbc.queryForObject("select id from jobs order by id limit 1", UUID.class);
        notifications.emit(new NotificationService.NotificationCommand(NotificationEvents.VERIFICATION_COMPLETED, "USER",
                UUID.fromString("00000000-0000-7000-8000-0000000c0b01"), Map.of("message", "Employer verification completed"),
                com.personal.jobagent.common.UuidV7.generate(), null));
        notifications.emit(new NotificationService.NotificationCommand(NotificationEvents.SIGNUP_COMPLETED, "USER",
                UUID.fromString("00000000-0000-7000-8000-0000000c0b02"), Map.of("message", "Employer signup completed"),
                com.personal.jobagent.common.UuidV7.generate(), null));
        discovery.ingestJob(job(SOURCE_A, "eq-0", "Java Platform Engineer 0", "Build Java services with Spring Boot. Role 0",
                List.of("Java", "Spring"), 30000, "https://boards.greenhouse.io/equivalence/jobs/5000"));
        assertThat(someJob).isNotNull();
    }

    // ── draining, snapshot and comparison ────────────────────────────

    private int drain() {
        int rounds = 0;
        while (rounds < 5000) {
            Integer pending = jdbc.queryForObject(
                    "select count(*) from outbox_events where published_at is null and attempt_count < 5", Integer.class);
            if (pending == null || pending == 0) return rounds;
            outbox.dispatchPending();
            rounds++;
        }
        throw new AssertionError("the outbox did not drain");
    }

    private List<String> tables() {
        return jdbc.queryForList("""
                select tablename from pg_tables
                 where schemaname = 'public' and tablename <> 'flyway_schema_history'
                 order by tablename
                """, String.class);
    }

    private void snapshot() {
        jdbc.execute("create schema snap");
        for (String t : tables()) jdbc.execute("create table snap.\"" + t + "\" as select * from public.\"" + t + "\"");
        jdbc.execute("create table snap.robin_sequence as select last_value from outbox_events_sequence_id_seq");
    }

    /** Back to the snapshot, in one transaction on one connection (FK triggers off while copying). */
    private void restore() {
        List<String> all = tables();
        Map<String, String> columns = new LinkedHashMap<>();
        for (String t : all) {
            columns.put(t, jdbc.queryForObject("""
                    select string_agg(quote_ident(a.attname), ', ' order by a.attnum)
                      from pg_attribute a
                     where a.attrelid = ('public.' || quote_ident(?))::regclass and a.attnum > 0
                       and not a.attisdropped and a.attgenerated = ''
                    """, String.class, t));
        }
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) con -> {
            boolean auto = con.getAutoCommit();
            con.setAutoCommit(false);
            try (var st = con.createStatement()) {
                st.execute("set local session_replication_role = replica");
                st.execute("truncate " + String.join(", ", all.stream().map(t -> "public.\"" + t + "\"").toList()) + " cascade");
                for (String t : all) {
                    String c = columns.get(t);
                    st.execute("insert into public.\"" + t + "\" (" + c + ") overriding system value select " + c
                            + " from snap.\"" + t + "\"");
                }
                con.commit();
            } catch (java.sql.SQLException e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(auto);
            }
            return null;
        });
    }

    private List<Map<String, Object>> snapshotLastPerJob() {
        return jdbc.queryForList("""
                select o.aggregate_id, max(o.sequence_id) as seq, max(j.source_id::text)::uuid as source_id
                  from snap.outbox_events o join public.jobs j on j.id = o.aggregate_id
                 where o.published_at is null and o.event_type = 'job.discovered'
                 group by o.aggregate_id order by 2
                """);
    }

    /** What a user, the pipeline or an operator can observe; generated ids and timestamps are masked. */
    private Map<String, List<String>> businessState() {
        Map<String, List<String>> s = new LinkedHashMap<>();
        s.put("jobs", rows("select id || '|' || status || '|' || coalesce(filter_reasons::text, '') from jobs"));
        s.put("job_matches", rows("select profile_id || '|' || job_id || '|' || score || '|' || recommendation || '|' || breakdown::text from job_matches"));
        s.put("application_decisions", rows("""
                select profile_id || '|' || job_id || '|' || match_score || '|' || recommendation || '|'
                       || application_mode || '|' || decision || '|' || coalesce(reason, '') from application_decisions"""));
        s.put("applications", rows("select profile_id || '|' || job_id || '|' || status from applications"));
        s.put("application_events", rows("""
                select a.profile_id || '|' || a.job_id || '|' || e.type || '|' || coalesce(e.payload ->> 'step', '')
                       || '|' || coalesce(e.payload ->> 'status', '') from application_events e
                  join applications a on a.id = e.application_id"""));
        s.put("notifications", rows("""
                select n.category || '|' || n.severity || '|' || n.title || '|' || coalesce(n.body, '') || '|'
                       || regexp_replace(coalesce(n.link, ''), '[0-9a-f-]{36}', 'U', 'g') || '|'
                       || coalesce(n.job_id::text, '') || '|' || coalesce(n.profile_id::text, '') || '|'
                       || coalesce((select a.profile_id || ':' || a.job_id from applications a where a.id = n.application_id), '')
                       || '|' || regexp_replace(coalesce(n.dedup_key, ''), '[0-9a-f-]{36}', 'U', 'g')
                  from notifications n"""));
        List<String> counts = new ArrayList<>();
        for (String t : tables()) {
            if (t.equals("outbox_events") || t.equals("consumed_events") || t.equals("audit_logs")) continue;
            counts.add(t + "=" + jdbc.queryForObject("select count(*) from public.\"" + t + "\"", Long.class));
        }
        s.put("row_counts_of_every_other_table", counts);
        return s;
    }

    private Map<String, Long> bookkeeping() {
        Map<String, Long> b = new LinkedHashMap<>();
        for (String type : List.of("job.discovered", "job.matched", "application.created", "application.prepared")) {
            b.put(type, jdbc.queryForObject("select count(*) from outbox_events where event_type = ?", Long.class, type));
        }
        b.put("consumed_events", jdbc.queryForObject("select count(*) from consumed_events", Long.class));
        b.put("pending_after", jdbc.queryForObject(
                "select count(*) from outbox_events where published_at is null and attempt_count < 5", Long.class));
        b.put("dead_letter", jdbc.queryForObject(
                "select count(*) from outbox_events where published_at is null and attempt_count >= 5", Long.class));
        b.put("audit_logs", jdbc.queryForObject("select count(*) from audit_logs", Long.class));
        return b;
    }

    private List<String> rows(String sql) {
        List<String> r = new ArrayList<>(jdbc.queryForList(sql, String.class));
        r.sort(null);
        return r;
    }
}
