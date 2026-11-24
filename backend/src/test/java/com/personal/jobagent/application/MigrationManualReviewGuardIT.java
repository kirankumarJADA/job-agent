package com.personal.jobagent.application;

import com.personal.jobagent.discovery.JobDiscoveryService;
import com.personal.jobagent.events.OutboxProcessor;
import com.personal.jobagent.jobs.JobMatchService;
import com.personal.jobagent.llm.ModelRouter;
import com.personal.jobagent.llm.TaskType;
import com.personal.jobagent.preferences.PreferenceSetRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Database migration: with {@code app.migration.manual-review-only=true}, the
 * real dispatcher and both real consumers drain a restored backlog without
 * applying anything automatically.
 *
 * <p>The backlog has the two shapes production's has:
 * <ul>
 *   <li>restored pending {@code job.matched} events with no decision yet,
 *       like the 11 of 9 Nov (ASSISTED, no approval rule, score 88);</li>
 *   <li>historical {@code job.discovered} events (first sight, re-crawls, an
 *       edit), whose replay scores the job again for every profile and
 *       generates new {@code job.matched} events.</li>
 * </ul>
 *
 * <p>Profiles cover every mode: ASSISTED without a rule, CONTROLLED_AUTO with
 * an enabled rule, MANUAL, and no preferences at all. Every match scores at
 * least 85, the ASSISTED floor, so without the guard all but MANUAL would
 * auto-apply. With it, every one is recorded as NEEDS_REVIEW and raises an
 * approval request; no application, plan, quota use, preparation step, retry
 * or dead letter results. An explicit owner action still creates an
 * application.
 */
@SpringBootTest(properties = {
        "spring.flyway.placeholders.remove_seed_dev_account=false",
        "app.migration.manual-review-only=true",
        "app.discovery.scheduler-enabled=false",
        "app.discovery.identity-backfill-enabled=false",
        "app.discovery.maintenance-enabled=false",
        // The test drives the dispatcher itself.
        "app.events.dispatch-interval-ms=86400000",
        "app.events.reconcile-interval-ms=86400000"})
@Testcontainers
class MigrationManualReviewGuardIT {

    private static final UUID SOURCE = UUID.fromString("00000000-0000-7000-8000-0000000d0a01");
    private static final UUID ASSISTED = UUID.fromString("00000000-0000-7000-8000-0000000d0c01");
    private static final UUID CONTROLLED_AUTO = UUID.fromString("00000000-0000-7000-8000-0000000d0c02");
    private static final UUID MANUAL = UUID.fromString("00000000-0000-7000-8000-0000000d0c03");
    private static final UUID NO_PREFERENCES = UUID.fromString("00000000-0000-7000-8000-0000000d0c04");
    private static final List<UUID> PROFILES = List.of(ASSISTED, CONTROLLED_AUTO, MANUAL, NO_PREFERENCES);
    /** Weights that make the score the skill overlap alone: 100 for a full match. */
    private static final String SKILL_ONLY =
            "{\"skill\":100,\"experience\":0,\"visa\":0,\"location\":0,\"salary\":0,\"career\":0,\"difficulty\":0}";

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
    @Autowired private JobMatchService matcher;
    @Autowired private PreferenceSetRepository preferenceSets;
    @Autowired private OutboxProcessor outbox;
    @Autowired private ApplicationDecisionService decisionService;
    @Autowired private AutoApplyBatchService batchService;
    @Autowired private ApplicationPipelineService pipeline;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void theGuardIsWiredIntoTheDecisionEngineAndTheBatch() {
        assertThat(decisionService.isManualReviewOnly()).isTrue();
        long runsBefore = count("select count(*) from auto_apply_runs");

        assertThatThrownBy(() -> batchService.runBatch(CONTROLLED_AUTO))
                .isInstanceOf(AutoApplyBatchService.BatchDisabledException.class)
                .hasMessageContaining("database migration");

        assertThat(count("select count(*) from auto_apply_runs")).isEqualTo(runsBefore);
    }

    @Test
    void restoredAndReplayedMatchesAreHeldForHumanReview() {
        seed();
        drain(); // nothing should be pending yet; makes the start state explicit
        long applicationsBefore = count("select count(*) from applications");
        long plansBefore = count("select count(*) from automation_plans");
        long decisionsBefore = count("select count(*) from application_decisions");
        long matchedBefore = count("select count(*) from outbox_events where event_type = 'job.matched'");
        long approvalsBefore = count("select count(*) from outbox_events where event_type = 'approval.required'");
        double quotaBefore = applicationQuotaUsed();

        // 1. Restored: the job's discovery was processed before the pause, its
        //    APPLY matches were written, and their job.matched events are still
        //    pending with no decision yet.
        UUID restoredJob = discovery.ingestJob(job("guard-restored", "Java Platform Engineer", "Java and Spring services."))
                .jobId();
        jdbc.update("update outbox_events set published_at = now() where event_type = 'job.discovered' and aggregate_id = ?",
                restoredJob);
        for (UUID profile : List.of(ASSISTED, CONTROLLED_AUTO)) {
            var match = matcher.evaluateMatch(restoredJob, profile, preferenceSets.findActiveByProfileId(profile).orElse(null));
            assertThat(match.recommendation()).isEqualTo("APPLY");
            assertThat(match.overall()).isGreaterThanOrEqualTo(85);
        }
        assertThat(count("select count(*) from outbox_events where published_at is null and event_type = 'job.matched'"))
                .isEqualTo(2);

        // 2. Historical discovery: first sight, three re-crawls and an edit, all
        //    still pending. Replaying them scores the job for every profile.
        UUID replayedJob = null;
        for (int i = 0; i < 4; i++) {
            replayedJob = discovery.ingestJob(job("guard-replayed", "Java Backend Engineer", "Java and Spring APIs."))
                    .jobId();
        }
        discovery.ingestJob(job("guard-replayed", "Java Backend Engineer", "Java and Spring APIs, edited."));
        long pendingDiscovered = count(
                "select count(*) from outbox_events where published_at is null and event_type = 'job.discovered'");
        assertThat(pendingDiscovered).isGreaterThanOrEqualTo(5);

        int rounds = drain();

        // Matches were generated by the replay, at auto-apply scores, for every profile.
        List<Map<String, Object>> replayedMatches = jdbc.queryForList(
                "select profile_id, score, recommendation from job_matches where job_id = ? and profile_id in (?, ?, ?, ?)",
                replayedJob, ASSISTED, CONTROLLED_AUTO, MANUAL, NO_PREFERENCES);
        assertThat(replayedMatches).hasSize(4);
        for (Map<String, Object> m : replayedMatches) {
            assertThat(m.get("recommendation")).isEqualTo("APPLY");
            assertThat(((Number) m.get("score")).intValue()).as("score of %s", m.get("profile_id"))
                    .isGreaterThanOrEqualTo(85);
        }

        // Every decision is a hold for human review, with the guard's reason.
        List<Map<String, Object>> decisions = jdbc.queryForList("""
                select profile_id, job_id, application_mode, decision, reason from application_decisions
                 where job_id in (?, ?) and profile_id in (?, ?, ?, ?) order by profile_id, job_id
                """, restoredJob, replayedJob, ASSISTED, CONTROLLED_AUTO, MANUAL, NO_PREFERENCES);
        assertThat(decisions).as("2 restored matches + 4 replayed ones").hasSize(6);
        for (Map<String, Object> d : decisions) {
            assertThat(d.get("decision")).as("decision of %s", d).isEqualTo("NEEDS_REVIEW");
            assertThat(d.get("reason")).isEqualTo(ApplicationDecisionService.MIGRATION_GUARD_REASON);
        }
        assertThat(count("select count(*) from application_decisions") - decisionsBefore)
                .as("decisions taken during the drain").isGreaterThanOrEqualTo(6);
        assertThat(count("select count(*) from application_decisions where decision <> 'NEEDS_REVIEW' or reason is distinct from ?",
                ApplicationDecisionService.MIGRATION_GUARD_REASON))
                .as("nothing anywhere was decided otherwise").isEqualTo(0);

        // Nothing was applied, planned, reserved or prepared.
        assertThat(count("select count(*) from applications")).isEqualTo(applicationsBefore);
        assertThat(count("select count(*) from automation_plans")).isEqualTo(plansBefore);
        assertThat(count("select count(*) from outbox_events where event_type = 'application.created'")).isZero();
        assertThat(applicationQuotaUsed()).isEqualTo(quotaBefore);
        Set<TaskType> preparation = Set.of(TaskType.CV_TAILORING, TaskType.COVER_LETTER, TaskType.APPLICATION_QA);
        List<Object> tasks = Mockito.mockingDetails(modelRouter).getInvocations().stream()
                .map(inv -> inv.getArguments().length == 0 ? null : inv.getArguments()[0]).toList();
        assertThat(tasks).as("no preparation step reached the (mocked) model router")
                .noneMatch(preparation::contains);

        // Every held match asked its owner for review; deduplicated across replayed runs by event idempotency.
        long matched = count("select count(*) from outbox_events where event_type = 'job.matched'") - matchedBefore;
        long approvals = count("select count(*) from outbox_events where event_type = 'approval.required'") - approvalsBefore;
        assertThat(matched).isGreaterThanOrEqualTo(6);
        assertThat(approvals).isGreaterThanOrEqualTo(6);
        for (UUID profile : PROFILES) {
            assertThat(count("select count(*) from notifications where category = 'APPROVAL_REQUIRED' and profile_id = ?",
                    profile)).as("approval requests for %s", profile).isGreaterThan(0);
        }

        // The drain finished cleanly: nothing pending, retried or dead-lettered.
        assertThat(count("select count(*) from outbox_events where published_at is null")).isZero();
        assertThat(count("select count(*) from outbox_events where attempt_count > 0 or last_error is not null")).isZero();

        System.out.printf("GUARD backlog: 2 restored job.matched, %d job.discovered; drained in %d rounds%n",
                pendingDiscovered, rounds);
        System.out.printf("GUARD replayed scores: %s%n", replayedMatches);
        System.out.printf("GUARD job.matched %d, approval.required %d, decisions %d (all NEEDS_REVIEW), applications +%d, "
                        + "plans +%d, application_daily used +%.1f%n",
                matched, approvals, decisions.size(), count("select count(*) from applications") - applicationsBefore,
                count("select count(*) from automation_plans") - plansBefore, applicationQuotaUsed() - quotaBefore);

        // An explicit owner action (what review approval calls) is not blocked.
        var created = pipeline.createApplicationFromMatch(ASSISTED, replayedJob);
        assertThat(created.created()).isTrue();
        assertThat(count("select count(*) from applications")).isEqualTo(applicationsBefore + 1);
        assertThat(count("select count(*) from outbox_events where published_at is null and event_type = 'application.created'"))
                .isEqualTo(1);
    }

    // ── fixture ──────────────────────────────────────────────────────

    private void seed() {
        for (int i = 0; i < PROFILES.size(); i++) {
            UUID user = UUID.fromString("00000000-0000-7000-8000-0000000d0b0" + (i + 1));
            jdbc.update("""
                    insert into users (id, email, password_hash, display_name, auth_provider)
                    values (?, ?, 'x', ?, 'LOCAL') on conflict (email) do nothing
                    """, user, "guard-" + i + "@example.com", "Guard " + i);
            jdbc.update("insert into profiles (id, user_id) values (?, ?) on conflict do nothing", PROFILES.get(i), user);
            for (String skill : new String[]{"Java", "Spring"}) {
                jdbc.update("insert into skills (id, profile_id, name, category, mastery) values (?, ?, ?, 'Test', 4)",
                        UUID.randomUUID(), PROFILES.get(i), skill);
            }
        }
        for (Object[] p : Arrays.asList(new Object[]{ASSISTED, "ASSISTED"}, new Object[]{CONTROLLED_AUTO, "CONTROLLED_AUTO"},
                new Object[]{MANUAL, "MANUAL"})) {
            jdbc.update("""
                    insert into preference_sets (id, profile_id, application_mode, scoring_weights, salary_min_gbp)
                    values (?, ?, ?, ?::jsonb, 40000)
                    """, UUID.randomUUID(), p[0], p[1], SKILL_ONLY);
        }
        // CONTROLLED_AUTO with an enabled rule well below the scores.
        jdbc.update("insert into user_approval_rules (id, profile_id, auto_approve_enabled, min_score) values (?, ?, true, 50)",
                UUID.randomUUID(), CONTROLLED_AUTO);
        // ASSISTED has no rule (production's case); NO_PREFERENCES has no preference set at all.
        jdbc.update("""
                insert into job_sources (id, kind, org_identifier, display_name, capabilities, policy)
                values (?, 'GREENHOUSE', 'guard-test', 'Guard Test', '{}', 'DISCOVERY_ONLY')
                on conflict (kind, org_identifier) do nothing
                """, SOURCE);
    }

    /** An unresolvable host: nothing in this test can reach a real job board. */
    private static JobDiscoveryService.IngestJobCommand job(String externalId, String title, String description) {
        String url = "https://jobs.guard-test.invalid/" + externalId;
        return new JobDiscoveryService.IngestJobCommand(
                SOURCE, externalId, null, "Guard Test Corp", title, "London", "London", "GB", "HYBRID", "FULL_TIME", "MID",
                70000, 90000, "GBP", description, List.of("Java", "Spring"), url, url, null);
    }

    private int drain() {
        int rounds = 0;
        while (rounds < 1000) {
            if (count("select count(*) from outbox_events where published_at is null and attempt_count < 5") == 0) {
                return rounds;
            }
            outbox.dispatchPending();
            rounds++;
        }
        throw new AssertionError("the outbox did not drain");
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private double applicationQuotaUsed() {
        Double used = jdbc.queryForObject(
                "select coalesce(sum(used), 0)::float8 from usage_quotas where quota_key = 'application_daily'", Double.class);
        return used == null ? 0 : used;
    }
}
