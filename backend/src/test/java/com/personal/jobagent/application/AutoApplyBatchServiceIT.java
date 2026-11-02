package com.personal.jobagent.application;

import com.personal.jobagent.llm.LlmCompletion;
import com.personal.jobagent.llm.ModelRouter;
import com.personal.jobagent.llm.RoutingTrace;
import com.personal.jobagent.llm.TaskType;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Real-PostgreSQL proof of the Phase 5 Auto Apply batch workflow: candidate
 * configuration, threshold/eligibility filtering against PERSISTED match
 * scores (nothing here re-scores a job), duplicate and quota safety under
 * real concurrency, owner isolation, and run-history persistence.
 *
 * <p>Fixtures seed {@code job_matches} directly with chosen scores instead of
 * running the real scorer or the outbox dispatch loop — {@link
 * AutoApplyBatchService} reads only the persisted score (the behaviour this
 * suite exists to prove), so a seeded score is exactly the input it is meant
 * to see, deterministically and without an LLM dependency.
 */
@SpringBootTest(properties = {"spring.flyway.placeholders.remove_seed_dev_account=false",
        "app.discovery.scheduler-enabled=false"})
@Testcontainers
class AutoApplyBatchServiceIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @MockBean ModelRouter modelRouter;

    @Autowired private AutoApplyBatchService batchService;
    @Autowired private AutoApplySettingsService settingsService;
    @Autowired private JdbcTemplate jdbc;

    private static final UUID SOURCE = UUID.randomUUID();

    @BeforeEach
    void stubModelRouter() {
        // Deterministic, network-free: application creation fans out into the
        // REAL asynchronous preparation pipeline (CV/cover-letter/answers),
        // exactly as it should — this IT only ensures that fan-out never
        // depends on a live LLM provider, mirroring ApplicationPipelineIT.
        org.mockito.Mockito.reset(modelRouter);
        when(modelRouter.execute(eq(TaskType.COVER_LETTER), any(), any(Duration.class)))
                .thenAnswer(inv -> ok());
        when(modelRouter.execute(eq(TaskType.APPLICATION_QA), any(), any(Duration.class)))
                .thenAnswer(inv -> ok());
    }

    private static ModelRouter.ExecutionResult ok() {
        String text = "Deterministic test completion — verified experience only.";
        return new ModelRouter.ExecutionResult(
                new LlmCompletion(text, new LlmCompletion.TokenUsage(10, 20), 5, LlmCompletion.FinishReason.STOP),
                new RoutingTrace(TaskType.COVER_LETTER, "mock", "test", List.of()));
    }

    private UUID seedProfile(boolean ready) {
        UUID userId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        jdbc.update("""
                insert into users (id, email, password_hash, display_name, auth_provider)
                values (?, ?, 'x', 'Auto Apply Test', 'LOCAL')
                """, userId, "auto-apply-" + profileId + "@example.com");
        jdbc.update("insert into profiles (id, user_id, setup_status) values (?, ?, ?)",
                profileId, userId, ready ? "READY" : "INCOMPLETE");
        return profileId;
    }

    private void seedSource() {
        jdbc.update("""
                insert into job_sources (id, kind, org_identifier, display_name, capabilities, policy)
                values (?, 'GREENHOUSE', 'auto-apply-org', 'Auto Apply Source', '{}', 'DISCOVERY_ONLY')
                on conflict (id) do nothing
                """, SOURCE);
    }

    private UUID seedJob(String title) {
        UUID jobId = UUID.randomUUID();
        jdbc.update("""
                insert into jobs (id, source_id, external_id, dedup_key, company_name_raw, title,
                                  location_raw, description_text, skills_extracted, status, content_hash,
                                  application_url)
                values (?, ?, ?, ?, 'Auto Apply Corp', ?, 'London', 'A role', '{}'::text[], 'DISCOVERED', ?,
                        'https://example.com/apply/' || ?)
                """, jobId, SOURCE, "ext-" + jobId, "dedup-" + jobId, title, "hash-" + jobId, jobId);
        return jobId;
    }

    private void seedMatch(UUID profileId, UUID jobId, int score, String recommendation) {
        jdbc.update("""
                insert into job_matches (profile_id, job_id, score, recommendation, breakdown, scored_at)
                values (?, ?, ?, ?, '{}'::jsonb, now())
                on conflict (profile_id, job_id) do update set score = excluded.score, recommendation = excluded.recommendation
                """, profileId, jobId, score, recommendation);
    }

    private void enableBatch(UUID profileId, int minScore, int dailyCap, int maxBatchSize) {
        settingsService.save(profileId, true, minScore, dailyCap, maxBatchSize, null, null, null, null, false);
    }

    // ── Configuration defaults and validation ──────────────────────

    @Test
    void defaultsAreSafeAndConservativeForAProfileThatNeverConfiguredThis() {
        UUID profile = seedProfile(true);
        AutoApplySettingsService.Settings settings = settingsService.get(profile);
        assertThat(settings.enabled()).isFalse();
        assertThat(settings.configured()).isFalse();
        assertThat(settings.minScore()).isEqualTo(AutoApplySettingsService.DEFAULT_MIN_SCORE);
        assertThat(settings.dailyCap()).isEqualTo(AutoApplySettingsService.DEFAULT_DAILY_CAP);
    }

    @Test
    void savingRejectsOutOfBoundsValues() {
        UUID profile = seedProfile(true);
        assertThatThrownBy(() -> settingsService.save(profile, true, 0, 10, 10, null, null, null, null, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settingsService.save(profile, true, 70, 999, 10, null, null, null, null, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settingsService.save(profile, true, 70, 10, 0, null, null, null, null, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void savingPreservesUnspecifiedFieldsRatherThanResettingThemToDefaults() {
        UUID profile = seedProfile(true);
        settingsService.save(profile, true, 80, 15, 20, "REMOTE", "Berlin", null, null, false);

        // A partial update that touches only dailyCap must not disturb minScore,
        // maxBatchSize or the filters already saved.
        AutoApplySettingsService.Settings after = settingsService.save(
                profile, null, null, 7, null, null, null, null, null, false);

        assertThat(after.enabled()).isTrue();
        assertThat(after.minScore()).isEqualTo(80);
        assertThat(after.dailyCap()).isEqualTo(7);
        assertThat(after.maxBatchSize()).isEqualTo(20);
        assertThat(after.remoteType()).isEqualTo("REMOTE");
        assertThat(after.location()).isEqualTo("Berlin");
    }

    @Test
    void clearFiltersExplicitlyRemovesSavedFiltersInsteadOfKeepingThemForever() {
        UUID profile = seedProfile(true);
        settingsService.save(profile, true, 80, 15, 20, "REMOTE", "Berlin", null, null, false);

        AutoApplySettingsService.Settings cleared = settingsService.save(
                profile, null, null, null, null, null, null, null, null, true);

        assertThat(cleared.remoteType()).isNull();
        assertThat(cleared.location()).isNull();
        // Non-filter fields are untouched by clearFilters.
        assertThat(cleared.minScore()).isEqualTo(80);
    }

    @Test
    void aDisabledConfigurationRefusesARunWithoutCreatingAnything() {
        UUID profile = seedProfile(true);
        seedSource();
        UUID job = seedJob("Disabled Co Role");
        seedMatch(profile, job, 90, "APPLY");
        // enabled stays false (default) — never saved.

        assertThatThrownBy(() -> batchService.runBatch(profile))
                .isInstanceOf(AutoApplyBatchService.BatchDisabledException.class);

        Integer runs = jdbc.queryForObject("select count(*) from auto_apply_runs where profile_id = ?", Integer.class, profile);
        Integer apps = jdbc.queryForObject("select count(*) from applications where profile_id = ?", Integer.class, profile);
        assertThat(runs).isZero();
        assertThat(apps).isZero();
    }

    @Test
    void anIncompleteProfileRefusesARunRatherThanFabricatingAnApplication() {
        UUID profile = seedProfile(false); // INCOMPLETE
        seedSource();
        UUID job = seedJob("Incomplete Profile Role");
        seedMatch(profile, job, 95, "APPLY");
        enableBatch(profile, 70, 10, 10);

        assertThatThrownBy(() -> batchService.runBatch(profile))
                .isInstanceOf(AutoApplyBatchService.ProfileNotReadyException.class);

        Integer apps = jdbc.queryForObject("select count(*) from applications where profile_id = ?", Integer.class, profile);
        assertThat(apps).isZero();
    }

    // ── Core eligibility and outcome accounting ────────────────────

    @Test
    void preparesOnlyJobsAtOrAboveTheConfiguredThresholdAndRecordsEveryOutcome() {
        UUID profile = seedProfile(true);
        seedSource();
        UUID high1 = seedJob("High Match 1");
        UUID high2 = seedJob("High Match 2");
        UUID low = seedJob("Low Match");
        seedMatch(profile, high1, 90, "APPLY");
        seedMatch(profile, high2, 75, "APPLY");
        seedMatch(profile, low, 40, "SKIP");
        enableBatch(profile, 70, 10, 10);

        UUID runId = batchService.runBatch(profile);

        AutoApplyBatchService.RunSummary run = batchService.findRun(profile, runId).orElseThrow();
        assertThat(run.status()).isEqualTo("COMPLETED");
        assertThat(run.jobsConsidered()).isEqualTo(3);
        assertThat(run.jobsEligible()).isEqualTo(2);
        assertThat(run.prepared()).isEqualTo(2);
        assertThat(run.skippedThreshold()).isEqualTo(1);

        List<AutoApplyBatchService.RunItem> items = batchService.findRunItems(profile, runId);
        assertThat(items).hasSize(3);
        assertThat(items.stream().filter(i -> "PREPARED".equals(i.outcome())).count()).isEqualTo(2);
        assertThat(items.stream().filter(i -> "SKIPPED_THRESHOLD".equals(i.outcome())).count()).isEqualTo(1);

        assertThat(countLiveApplications(profile, high1)).isEqualTo(1);
        assertThat(countLiveApplications(profile, high2)).isEqualTo(1);
        assertThat(countLiveApplications(profile, low)).isZero();
    }

    @Test
    void duplicateAndPreviouslyAttemptedApplicationsAreExcludedNotReprepared() {
        UUID profile = seedProfile(true);
        seedSource();
        UUID job = seedJob("Already Applied Role");
        seedMatch(profile, job, 90, "APPLY");
        enableBatch(profile, 70, 10, 10);

        // Pre-existing live application for this exact job (e.g. the candidate applied manually).
        UUID existingApplication = UUID.randomUUID();
        jdbc.update("""
                insert into applications (id, job_id, profile_id, mode, status)
                values (?, ?, ?, 'ASSISTED', 'READY_TO_APPLY')
                """, existingApplication, job, profile);

        UUID runId = batchService.runBatch(profile);
        AutoApplyBatchService.RunSummary run = batchService.findRun(profile, runId).orElseThrow();

        assertThat(run.prepared()).isZero();
        assertThat(run.skippedDuplicate()).isEqualTo(1);
        assertThat(countLiveApplications(profile, job)).isEqualTo(1); // still exactly one, not a second
    }

    @Test
    void runningTheBatchTwiceIsIdempotentAndNeverDoublesAnApplication() {
        UUID profile = seedProfile(true);
        seedSource();
        UUID job = seedJob("Repeat Run Role");
        seedMatch(profile, job, 90, "APPLY");
        enableBatch(profile, 70, 10, 10);

        UUID firstRun = batchService.runBatch(profile);
        UUID secondRun = batchService.runBatch(profile);

        assertThat(batchService.findRun(profile, firstRun).orElseThrow().prepared()).isEqualTo(1);
        AutoApplyBatchService.RunSummary second = batchService.findRun(profile, secondRun).orElseThrow();
        assertThat(second.prepared()).isZero();
        assertThat(second.skippedDuplicate()).isEqualTo(1);
        assertThat(countLiveApplications(profile, job)).isEqualTo(1);
    }

    // ── Daily cap: boundary and atomic concurrency ─────────────────

    @Test
    void stopsExactlyAtTheConfiguredDailyCapAndMarksTheRestQuotaLimited() {
        UUID profile = seedProfile(true);
        seedSource();
        List<UUID> jobs = IntStream.range(0, 5).mapToObj(i -> seedJob("Cap Role " + i)).collect(Collectors.toList());
        jobs.forEach(j -> seedMatch(profile, j, 80, "APPLY"));
        enableBatch(profile, 70, 2, 10); // cap = 2, 5 eligible

        UUID runId = batchService.runBatch(profile);
        AutoApplyBatchService.RunSummary run = batchService.findRun(profile, runId).orElseThrow();

        assertThat(run.jobsEligible()).isEqualTo(5);
        assertThat(run.prepared()).isEqualTo(2);
        assertThat(run.quotaLimited()).isEqualTo(3);
        Integer totalApplications = jdbc.queryForObject(
                "select count(*) from applications where profile_id = ?", Integer.class, profile);
        assertThat(totalApplications).isEqualTo(2);
    }

    @Test
    void concurrentBatchStartsForTheSameProfileNeverCombinedExceedTheDailyCap() throws Exception {
        UUID profile = seedProfile(true);
        seedSource();
        // Kept deliberately small: each created application also fans out
        // into the REAL asynchronous preparation pipeline (this IT does not
        // mock ModelRouter) — the property under test is atomic reservation,
        // which a handful of concurrent runs over a small backlog already
        // proves, without piling unrelated async load onto the shared
        // Testcontainers database.
        int cap = 2;
        int totalEligibleJobs = 8;
        List<UUID> jobs = IntStream.range(0, totalEligibleJobs)
                .mapToObj(i -> seedJob("Concurrent Role " + i)).collect(Collectors.toList());
        jobs.forEach(j -> seedMatch(profile, j, 85, "APPLY"));
        // maxBatchSize large enough that each concurrent run sees the whole backlog.
        enableBatch(profile, 70, cap, totalEligibleJobs);

        int concurrentRuns = 4;
        ExecutorService pool = Executors.newFixedThreadPool(concurrentRuns);
        try {
            List<Callable<UUID>> tasks = IntStream.range(0, concurrentRuns)
                    .<Callable<UUID>>mapToObj(i -> () -> batchService.runBatch(profile))
                    .collect(Collectors.toList());
            List<Future<UUID>> futures = pool.invokeAll(tasks);
            for (Future<UUID> f : futures) f.get();
        } finally {
            pool.shutdownNow();
        }

        Integer totalApplications = jdbc.queryForObject(
                "select count(*) from applications where profile_id = ? and status not in ('FAILED','WITHDRAWN')",
                Integer.class, profile);
        assertThat(totalApplications)
                .as("real concurrent batch starts for the same profile must never combine to exceed the configured daily cap")
                .isEqualTo(cap);
    }

    // ── Owner isolation ─────────────────────────────────────────────

    @Test
    void settingsQuotaAndRunHistoryAreIsolatedPerProfile() {
        UUID profileA = seedProfile(true);
        UUID profileB = seedProfile(true);
        seedSource();
        UUID jobA = seedJob("Isolation Role A");
        UUID jobB = seedJob("Isolation Role B");
        seedMatch(profileA, jobA, 90, "APPLY");
        seedMatch(profileB, jobB, 90, "APPLY");
        enableBatch(profileA, 70, 10, 10);
        enableBatch(profileB, 70, 10, 10);

        UUID runA = batchService.runBatch(profileA);

        assertThat(batchService.findRun(profileA, runA)).isPresent();
        // Profile B must never see profile A's run.
        assertThat(batchService.findRun(profileB, runA)).isEmpty();
        assertThat(batchService.history(profileB, 20)).isEmpty();
        // Profile B's own eligible job is untouched by profile A's run.
        assertThat(countLiveApplications(profileB, jobB)).isZero();
    }

    // ── Preview is read-only ─────────────────────────────────────────

    @Test
    void previewPredictsOutcomesWithoutCreatingAnythingOrConsumingQuota() {
        UUID profile = seedProfile(true);
        seedSource();
        UUID high = seedJob("Preview High");
        UUID low = seedJob("Preview Low");
        seedMatch(profile, high, 90, "APPLY");
        seedMatch(profile, low, 40, "SKIP");
        enableBatch(profile, 70, 10, 10);

        AutoApplyBatchService.PreviewResult preview = batchService.preview(profile);

        assertThat(preview.jobsConsidered()).isEqualTo(2);
        assertThat(preview.jobsEligible()).isEqualTo(1);
        assertThat(preview.items()).anySatisfy(i -> {
            if (i.jobId().equals(high)) assertThat(i.predictedOutcome()).isEqualTo("WOULD_PREPARE");
        });
        assertThat(countLiveApplications(profile, high)).isZero();
        assertThat(countLiveApplications(profile, low)).isZero();
        Integer runs = jdbc.queryForObject("select count(*) from auto_apply_runs where profile_id = ?", Integer.class, profile);
        assertThat(runs).isZero();
    }

    // ── Run history survives independent of the in-process call ─────

    @Test
    void runHistoryIsPersistedAndReadableAsEvidenceNotJustAReturnValue() {
        UUID profile = seedProfile(true);
        seedSource();
        UUID job = seedJob("Persisted Role");
        seedMatch(profile, job, 90, "APPLY");
        enableBatch(profile, 70, 10, 10);

        UUID runId = batchService.runBatch(profile);

        // Read back purely from storage, exactly as a restarted process would.
        Map<String, Object> row = jdbc.queryForMap("select status, prepared, jobs_considered from auto_apply_runs where id = ?", runId);
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(((Number) row.get("prepared")).intValue()).isEqualTo(1);
        assertThat(((Number) row.get("jobs_considered")).intValue()).isEqualTo(1);

        List<AutoApplyBatchService.RunSummary> history = batchService.history(profile, 10);
        assertThat(history).extracting(AutoApplyBatchService.RunSummary::id).contains(runId);
    }

    @Test
    void settingsFiltersFollowRealCandidateAndJobFields() {
        UUID profile = seedProfile(true);
        seedSource();
        UUID remoteJob = seedJob("Remote Filtered Role");
        jdbc.update("update jobs set remote_type = 'REMOTE' where id = ?", remoteJob);
        UUID onsiteJob = seedJob("Onsite Filtered Role");
        jdbc.update("update jobs set remote_type = 'ONSITE' where id = ?", onsiteJob);
        seedMatch(profile, remoteJob, 90, "APPLY");
        seedMatch(profile, onsiteJob, 90, "APPLY");
        settingsService.save(profile, true, 70, 10, 10, "REMOTE", null, null, null, false);

        UUID runId = batchService.runBatch(profile);
        AutoApplyBatchService.RunSummary run = batchService.findRun(profile, runId).orElseThrow();

        assertThat(run.jobsConsidered()).isEqualTo(1);
        assertThat(run.prepared()).isEqualTo(1);
        assertThat(countLiveApplications(profile, remoteJob)).isEqualTo(1);
        assertThat(countLiveApplications(profile, onsiteJob)).isZero();
    }

    private int countLiveApplications(UUID profileId, UUID jobId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from applications
                where profile_id = ? and job_id = ? and status not in ('FAILED','WITHDRAWN')
                """, Integer.class, profileId, jobId);
        return count == null ? 0 : count;
    }
}
