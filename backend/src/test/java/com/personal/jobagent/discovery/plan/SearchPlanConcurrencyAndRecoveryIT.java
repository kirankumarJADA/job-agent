package com.personal.jobagent.discovery.plan;

import com.personal.jobagent.common.MaintenanceModeService;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.discovery.JobDiscoveryService;
import com.personal.jobagent.discovery.platform.BoardDiscoveryService;
import com.personal.jobagent.discovery.platform.PlatformRegistry;
import com.personal.jobagent.jobs.JobMatchService;
import com.personal.jobagent.preferences.PreferenceSetRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.flyway.placeholders.remove_seed_dev_account=false",
        "app.discovery.scheduler-enabled=false",
        "app.security.operator-emails=dev@example.local"
})
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("Phase 2B Hardened: Search Plan Concurrency, Heartbeat Renewal, Side-Effect Fencing, and Recovery IT")
public class SearchPlanConcurrencyAndRecoveryIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SearchPlanRepository searchPlanRepository;

    @Autowired
    private SearchPlanExecutionCoordinator coordinator;

    @Autowired
    private SearchPlanScheduler scheduler;

    @Autowired
    private JobDiscoveryService jobDiscoveryService;

    @Autowired
    private JobMatchService jobMatchService;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @MockBean
    private BoardDiscoveryService boardDiscoveryService;

    @Test
    @DisplayName("Task 3 Scenario A-F: Expired Worker A cannot ingest, match, notify, or overwrite Worker B")
    void testScenarioAThroughF_StaleWorkerSideEffectsFenced() {
        UUID profileId = createTestProfile("adversarial-fencing@example.com");

        SearchPlanRecord plan = searchPlanRepository.create(new SearchPlanRecord(
                UuidV7.generate(),
                profileId,
                "Adversarial Fencing Plan",
                List.of("Distributed Systems Engineer"),
                Map.of(),
                List.of("MOCK_SOURCE"),
                "DAILY",
                50,
                true,
                null,
                Instant.now(),
                Instant.now()
        ));

        // Create test source
        UUID sourceId = UuidV7.generate();
        jdbcTemplate.update("""
                INSERT INTO job_sources (id, kind, org_identifier, display_name, capabilities, policy, enabled)
                VALUES (?, 'GREENHOUSE', 'stripe-fencing', 'MOCK_SOURCE', '{}'::jsonb, 'DISCOVERY_ONLY', true)
                ON CONFLICT (kind, org_identifier) DO NOTHING
                """, sourceId);

        // A. Worker A acquires a lease with very short TTL (100ms)
        UUID tokenWorkerA = UUID.randomUUID();
        boolean claimedA = searchPlanRepository.claimExecutionLease(plan.id(), tokenWorkerA, 100L);
        assertThat(claimedA).isTrue();

        // B. Worker A begins a slow provider request (simulated by sleep while lease expires)
        // C & D. Worker A stops renewing (simulated process pause); its lease expires
        try {
            Thread.sleep(150);
        } catch (InterruptedException ignored) {}

        // E. Worker B acquires the lease
        UUID tokenWorkerB = UUID.randomUUID();
        boolean claimedB = searchPlanRepository.claimExecutionLease(plan.id(), tokenWorkerB, 60000L);
        assertThat(claimedB).isTrue();

        // Worker B executes and persists side-effects under tokenWorkerB
        JobDiscoveryService.IngestJobCommand cmdB = new JobDiscoveryService.IngestJobCommand(
                sourceId,
                "job-worker-b-101",
                null,
                "Stripe",
                "Senior Distributed Systems Engineer B",
                "London",
                "London",
                "GB",
                "REMOTE",
                "FULL_TIME",
                "SENIOR",
                BigDecimal.valueOf(90000),
                BigDecimal.valueOf(110000),
                "GBP",
                "Core infra Java distributed systems.",
                List.of("Java", "Distributed Systems"),
                "https://stripe.com/jobs/b-101",
                "https://stripe.com/jobs/b-101",
                Instant.now()
        );
        JobDiscoveryService.IngestResult ingestB = jobDiscoveryService.ingestJob(cmdB, plan.id(), tokenWorkerB);
        assertThat(ingestB.jobId()).isNotNull();

        // Worker B evaluates match and notifies under tokenWorkerB
        PreferenceSetRecord prefs = new PreferenceSetRecord(
                UUID.randomUUID(), profileId, List.of("Distributed Systems Engineer"), List.of(), List.of(),
                List.of("Java"), List.of("London"), List.of("REMOTE"), List.of(), List.of("SENIOR"),
                80000L, "NONE", List.of(), List.of(), "MANUAL", Map.of(), Map.of(), true
        );
        JobMatchService.MatchResult matchB = jobMatchService.evaluateMatch(ingestB.jobId(), profileId, prefs, plan.id(), tokenWorkerB);
        assertThat(matchB).isNotNull();

        // Worker B completes execution successfully
        boolean completedB = searchPlanRepository.completeExecution(plan.id(), tokenWorkerB, "SUCCESS", null, 1);
        assertThat(completedB).isTrue();

        // F. Worker A resumes and receives its old provider response
        // Prove 1: Worker A CANNOT ingest the result (fails atomically at DB boundary with ExecutionFencedException)
        JobDiscoveryService.IngestJobCommand cmdA = new JobDiscoveryService.IngestJobCommand(
                sourceId,
                "job-worker-a-999",
                null,
                "Stripe",
                "Stale Job From Worker A",
                "London",
                "London",
                "GB",
                "REMOTE",
                "FULL_TIME",
                "SENIOR",
                BigDecimal.valueOf(90000),
                BigDecimal.valueOf(110000),
                "GBP",
                "Stale posting from obsolete provider call.",
                List.of("Java"),
                "https://stripe.com/jobs/a-999",
                "https://stripe.com/jobs/a-999",
                Instant.now()
        );

        assertThatThrownBy(() -> jobDiscoveryService.ingestJob(cmdA, plan.id(), tokenWorkerA))
                .isInstanceOf(ExecutionFencedException.class)
                .hasMessageContaining("lease lost or expired");

        // Prove 1b: BoardDiscoveryService itself rejects stale Worker A on touch/close/ingest
        BoardDiscoveryService realBoardService = new BoardDiscoveryService(
                jdbcTemplate, jobDiscoveryService, new PlatformRegistry(50), 50, 150, 400, 3, 1500L, 10
        );
        realBoardService.setSearchPlanRepository(searchPlanRepository);
        assertThatThrownBy(() -> realBoardService.run(sourceId, BoardDiscoveryService.Mode.SCHEDULED, Map.of(), plan.id(), tokenWorkerA))
                .isInstanceOf(ExecutionFencedException.class);

        // Verify Worker A's job was NEVER inserted into jobs table
        Integer staleJobCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM jobs WHERE title = 'Stale Job From Worker A'", Integer.class);
        assertThat(staleJobCount).isEqualTo(0);

        // Prove 2 & 3: Worker A CANNOT create matches or outbox notifications
        // Even for an existing job (such as Worker B's job), Worker A's evaluateMatch call is fenced
        assertThatThrownBy(() -> jobMatchService.evaluateMatch(ingestB.jobId(), profileId, prefs, plan.id(), tokenWorkerA))
                .isInstanceOf(ExecutionFencedException.class);

        // Verify outbox_events has only Worker B's notification, none from Worker A
        Integer outboxCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", Integer.class, ingestB.jobId());
        assertThat(outboxCount).isEqualTo(1);

        // Prove 4: Worker A CANNOT overwrite Worker B's execution result
        boolean completedA = searchPlanRepository.completeExecution(plan.id(), tokenWorkerA, "SUCCESS", null, 999);
        assertThat(completedA).isFalse();

        // Prove 5: Worker B's execution state remains authoritative
        SearchPlanRecord finalPlan = searchPlanRepository.findByIdAndProfileId(plan.id(), profileId).orElseThrow();
        assertThat(finalPlan.lastExecutionStatus()).isEqualTo("SUCCESS");
        assertThat(finalPlan.lastExecutionResults()).isEqualTo(1); // 1 from Worker B, NOT 999 from Worker A!
    }

    @Test
    @DisplayName("Heartbeat Renewal: Active worker continuously extends lease during long execution")
    void testHeartbeatRenewalDuringLongHealthyExecution() {
        UUID profileId = createTestProfile("heartbeat-long-run@example.com");

        SearchPlanRecord plan = searchPlanRepository.create(new SearchPlanRecord(
                UuidV7.generate(),
                profileId,
                "Heartbeat Long Run Plan",
                List.of("Staff Engineer"),
                Map.of(),
                List.of("MOCK_SOURCE"),
                "DAILY",
                50,
                true,
                null,
                Instant.now(),
                Instant.now()
        ));

        UUID sourceId = UuidV7.generate();
        jdbcTemplate.update("""
                INSERT INTO job_sources (id, kind, org_identifier, display_name, capabilities, policy, enabled)
                VALUES (?, 'GREENHOUSE', 'heartbeat-source', 'MOCK_SOURCE', '{}'::jsonb, 'DISCOVERY_ONLY', true)
                ON CONFLICT (kind, org_identifier) DO NOTHING
                """, sourceId);

        // Slow provider simulates healthy 600ms network crawl
        when(boardDiscoveryService.run(any(UUID.class), any(BoardDiscoveryService.Mode.class), anyMap(), any(), any(), anyInt(), anyLong()))
                .thenAnswer(inv -> {
                    Thread.sleep(600);
                    return new BoardDiscoveryService.SourceRunReport(
                            "hb-run", sourceId, "GREENHOUSE", "OK", true, Map.of(), null, null, List.of(), List.of()
                    );
                });

        // Lease TTL is 400ms, heartbeat is 100ms.
        // Total run takes ~600ms (> 400ms initial lease TTL).
        // Heartbeat will renew lease at least 4 times during execution!
        SearchPlanExecutionCoordinator.ExecutionSummary summary =
                coordinator.executePlan(plan.id(), profileId, true, 400L, 100L);

        assertThat(summary.status()).isEqualTo("SUCCESS");

        SearchPlanRecord recorded = searchPlanRepository.findByIdAndProfileId(plan.id(), profileId).orElseThrow();
        assertThat(recorded.lastExecutionStatus()).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("Heartbeat Failure: Worker cancels execution immediately when lease is overtaken")
    void testFailedHeartbeatStopsWorkerAndFailsExecution() {
        UUID profileId = createTestProfile("failed-hb@example.com");

        SearchPlanRecord plan = searchPlanRepository.create(new SearchPlanRecord(
                UuidV7.generate(),
                profileId,
                "Failed Heartbeat Plan",
                List.of("DevOps Engineer"),
                Map.of(),
                List.of("MOCK_SOURCE"),
                "DAILY",
                50,
                true,
                null,
                Instant.now(),
                Instant.now()
        ));

        UUID sourceId = UuidV7.generate();
        jdbcTemplate.update("""
                INSERT INTO job_sources (id, kind, org_identifier, display_name, capabilities, policy, enabled)
                VALUES (?, 'GREENHOUSE', 'failed-hb-source', 'MOCK_SOURCE', '{}'::jsonb, 'DISCOVERY_ONLY', true)
                ON CONFLICT (kind, org_identifier) DO NOTHING
                """, sourceId);

        // When provider is called, simulate external worker stealing the lease
        when(boardDiscoveryService.run(any(UUID.class), any(BoardDiscoveryService.Mode.class), anyMap(), any(), any(), anyInt(), anyLong()))
                .thenAnswer(inv -> {
                    // Overwrite the lease in database to a different owner
                    jdbcTemplate.update("UPDATE search_plans SET execution_owner_token = ? WHERE id = ?",
                            UUID.randomUUID(), plan.id());
                    Thread.sleep(300);
                    return new BoardDiscoveryService.SourceRunReport(
                            "hb-failed-run", sourceId, "GREENHOUSE", "OK", true, Map.of(), null, null, List.of(), List.of()
                    );
                });

        // Lease duration 500ms, heartbeat interval 100ms
        SearchPlanExecutionCoordinator.ExecutionSummary summary =
                coordinator.executePlan(plan.id(), profileId, true, 500L, 100L);

        // Worker must detect ownership loss and report FAILED, never SUCCESS
        assertThat(summary.status()).isEqualTo("FAILED");
        assertThat(summary.errors()).anyMatch(e -> e.contains("lease") || e.contains("ownership"));
    }

    @Test
    @DisplayName("Concurrency: Two competing workers attempt same plan simultaneously; exactly ONE obtains lease")
    void testConcurrentWorkersMutuallyExcluded() throws Exception {
        UUID profileId = createTestProfile("concurrency-test@example.com");

        SearchPlanRecord plan = searchPlanRepository.create(new SearchPlanRecord(
                UuidV7.generate(),
                profileId,
                "Concurrent Test Plan",
                List.of("Software Engineer"),
                Map.of(),
                List.of("MOCK_SOURCE"),
                "HOURLY",
                50,
                true,
                null,
                Instant.now(),
                Instant.now()
        ));

        UUID sourceId = UuidV7.generate();
        jdbcTemplate.update("""
                INSERT INTO job_sources (id, kind, org_identifier, display_name, capabilities, policy, enabled)
                VALUES (?, 'GREENHOUSE', 'concurrent-test-org', 'MOCK_SOURCE', '{}'::jsonb, 'DISCOVERY_ONLY', true)
                ON CONFLICT (kind, org_identifier) DO NOTHING
                """, sourceId);

        // Slow mock to keep the winner inside execution for 400ms
        when(boardDiscoveryService.run(any(UUID.class), any(BoardDiscoveryService.Mode.class), anyMap(), any(), any(), anyInt(), anyLong()))
                .thenAnswer(inv -> {
                    Thread.sleep(400);
                    return new BoardDiscoveryService.SourceRunReport(
                            "run-1", sourceId, "GREENHOUSE", "OK", true, Map.of(), null, null, List.of(), List.of()
                    );
                });

        int numThreads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);

        List<SearchPlanExecutionCoordinator.ExecutionSummary> results = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    SearchPlanExecutionCoordinator.ExecutionSummary summary =
                            coordinator.executePlan(plan.id(), profileId, false);
                    results.add(summary);
                } catch (Exception e) {
                    // ignored
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean finished = doneLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).isTrue();
        assertThat(results).hasSize(2);

        long successCount = results.stream().filter(r -> "SUCCESS".equals(r.status())).count();
        long skippedCount = results.stream().filter(r -> "SKIPPED".equals(r.status())).count();

        // Mutually exclusive: exactly 1 acquired lease and ran; the other was skipped
        assertThat(successCount).isEqualTo(1);
        assertThat(skippedCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Concurrency: Second independent search plan can execute concurrently without interference")
    void testIndependentPlansExecuteInParallel() throws Exception {
        UUID profileId = createTestProfile("independent-plans@example.com");

        SearchPlanRecord plan1 = searchPlanRepository.create(new SearchPlanRecord(
                UuidV7.generate(), profileId, "Plan 1", List.of(), Map.of(), List.of(), "DAILY", 50, true, null, Instant.now(), Instant.now()
        ));
        SearchPlanRecord plan2 = searchPlanRepository.create(new SearchPlanRecord(
                UuidV7.generate(), profileId, "Plan 2", List.of(), Map.of(), List.of(), "DAILY", 50, true, null, Instant.now(), Instant.now()
        ));

        when(boardDiscoveryService.run(any(UUID.class), any(BoardDiscoveryService.Mode.class), anyMap(), any(), any(), anyInt(), anyLong()))
                .thenReturn(new BoardDiscoveryService.SourceRunReport("run", UUID.randomUUID(), "GREENHOUSE", "OK", true, Map.of(), null, null, List.of(), List.of()));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<SearchPlanExecutionCoordinator.ExecutionSummary> f1 = executor.submit(() -> coordinator.executePlan(plan1.id(), profileId, true));
        Future<SearchPlanExecutionCoordinator.ExecutionSummary> f2 = executor.submit(() -> coordinator.executePlan(plan2.id(), profileId, true));

        SearchPlanExecutionCoordinator.ExecutionSummary s1 = f1.get(5, TimeUnit.SECONDS);
        SearchPlanExecutionCoordinator.ExecutionSummary s2 = f2.get(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(s1.status()).isEqualTo("SUCCESS");
        assertThat(s2.status()).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("Scheduler Competition: Multiple scheduler instances sweeping concurrently execute each plan once")
    void testSchedulerCompetingAcrossSeparateContexts() throws Exception {
        UUID profileId = createTestProfile("scheduler-competing@example.com");

        SearchPlanRecord plan = searchPlanRepository.create(new SearchPlanRecord(
                UuidV7.generate(), profileId, "Due Hourly Plan", List.of(), Map.of(), List.of("MOCK_SOURCE"),
                "HOURLY", 50, true, null, Instant.now(), Instant.now()
        ));

        UUID sourceId = UuidV7.generate();
        jdbcTemplate.update("""
                INSERT INTO job_sources (id, kind, org_identifier, display_name, capabilities, policy, enabled)
                VALUES (?, 'GREENHOUSE', 'sched-competing-org', 'MOCK_SOURCE', '{}'::jsonb, 'DISCOVERY_ONLY', true)
                ON CONFLICT (kind, org_identifier) DO NOTHING
                """, sourceId);

        when(boardDiscoveryService.run(any(UUID.class), any(BoardDiscoveryService.Mode.class), anyMap(), any(), any(), anyInt(), anyLong()))
                .thenAnswer(inv -> {
                    Thread.sleep(300);
                    return new BoardDiscoveryService.SourceRunReport("sched-run", sourceId, "GREENHOUSE", "OK", true, Map.of(), null, null, List.of(), List.of());
                });

        SearchPlanScheduler activeScheduler = new SearchPlanScheduler(
                searchPlanRepository, coordinator, new MaintenanceModeService(false), true);

        int numSchedulers = 2;
        ExecutorService executor = Executors.newFixedThreadPool(numSchedulers);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numSchedulers);

        for (int i = 0; i < numSchedulers; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    activeScheduler.sweepDueSearchPlans();
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean done = doneLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(done).isTrue();

        SearchPlanRecord updated = searchPlanRepository.findByIdAndProfileId(plan.id(), profileId).orElseThrow();
        assertThat(updated.lastExecutionStatus()).isEqualTo("SUCCESS");
        assertThat(updated.lastRunAt()).isNotNull();
    }

    @Test
    @DisplayName("Recovery & Stale Execution: Expired running plan is cleanly recovered to FAILED")
    void testStaleExecutionRecovery() {
        UUID profileId = createTestProfile("stale-recovery@example.com");

        SearchPlanRecord plan = searchPlanRepository.create(new SearchPlanRecord(
                UuidV7.generate(), profileId, "Stale Plan", List.of(), Map.of(), List.of(), "DAILY", 50, true, null, Instant.now(), Instant.now()
        ));

        UUID abandonedToken = UUID.randomUUID();
        // Claim lease with 1 ms duration
        searchPlanRepository.claimExecutionLease(plan.id(), abandonedToken, 1L);

        try { Thread.sleep(50); } catch (InterruptedException ignored) {}

        // Scheduler sweep performs stale recovery
        int recovered = searchPlanRepository.recoverStaleExecutions(Instant.now());
        assertThat(recovered).isGreaterThanOrEqualTo(1);

        SearchPlanRecord updated = searchPlanRepository.findByIdAndProfileId(plan.id(), profileId).orElseThrow();
        assertThat(updated.lastExecutionStatus()).isEqualTo("FAILED");
        assertThat(updated.lastExecutionError()).contains("expired");
        assertThat(updated.executionOwnerToken()).isNull();
        assertThat(updated.executionLeaseExpiresAt()).isNull();
        assertThat(updated.lastRunAt()).isNotNull();
    }

    @Test
    @DisplayName("Scheduler: MANUAL search plans are excluded from automated scheduling")
    void testManualPlansExcludedFromScheduler() {
        UUID profileId = createTestProfile("manual-plan@example.com");

        SearchPlanRecord manualPlan = searchPlanRepository.create(new SearchPlanRecord(
                UuidV7.generate(), profileId, "Manual Only Plan", List.of(), Map.of(), List.of(), "MANUAL", 50, true, null, Instant.now(), Instant.now()
        ));

        List<SearchPlanRecord> due = searchPlanRepository.findDuePlans(Instant.now());
        assertThat(due).noneMatch(p -> p.id().equals(manualPlan.id()));
    }

    @Test
    @DisplayName("F2 Correctness: FOR NO KEY UPDATE lock remains held until observation mutation commits, blocking concurrent takeover")
    void testObservationMutationHoldsRowLockAndBlocksConcurrentTakeover() throws Exception {
        UUID profileId = createTestProfile("f2-lock-test@example.com");

        SearchPlanRecord plan = searchPlanRepository.create(new SearchPlanRecord(
                UuidV7.generate(), profileId, "F2 Observation Lock Plan", List.of("Security Engineer"), Map.of(),
                List.of("MOCK"), "DAILY", 50, true, null, Instant.now(), Instant.now()
        ));

        UUID tokenA = UUID.randomUUID();
        boolean claimedA = searchPlanRepository.claimExecutionLease(plan.id(), tokenA, 60000L);
        assertThat(claimedA).isTrue();

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        CountDownLatch lockAcquiredLatch = new CountDownLatch(1);
        CountDownLatch releaseLockLatch = new CountDownLatch(1);
        AtomicBoolean workerBCompletedEarly = new AtomicBoolean(false);

        // Worker A executes a transaction that acquires FOR NO KEY UPDATE and pauses
        ExecutorService executorA = Executors.newSingleThreadExecutor();
        Future<?> workerAFuture = executorA.submit(() -> {
            txTemplate.executeWithoutResult(status -> {
                // 1. Assert lease ownership inside transaction -> acquires FOR NO KEY UPDATE lock
                searchPlanRepository.assertLeaseOwnership(plan.id(), tokenA);
                lockAcquiredLatch.countDown();

                // 2. Pause while holding the row lock
                try {
                    boolean released = releaseLockLatch.await(5, TimeUnit.SECONDS);
                    assertThat(released).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                // 3. Perform observation mutation while still holding lock
                jdbcTemplate.update("UPDATE search_plans SET updated_at = clock_timestamp() WHERE id = ?", plan.id());
            });
        });

        // Wait until Worker A has acquired the lock
        assertThat(lockAcquiredLatch.await(3, TimeUnit.SECONDS)).isTrue();

        // Worker B attempts to overwrite the lease row concurrently on another thread/connection
        UUID tokenB = UUID.randomUUID();
        ExecutorService executorB = Executors.newSingleThreadExecutor();
        Future<Integer> workerBFuture = executorB.submit(() -> {
            int rows = jdbcTemplate.update("UPDATE search_plans SET execution_owner_token = ? WHERE id = ?", tokenB, plan.id());
            workerBCompletedEarly.set(true);
            return rows;
        });

        // Prove Worker B is blocked on PostgreSQL FOR NO KEY UPDATE row lock and CANNOT complete
        assertThatThrownBy(() -> workerBFuture.get(300, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
        assertThat(workerBCompletedEarly.get()).isFalse();

        // Release Worker A to complete its observation mutation and commit transaction
        releaseLockLatch.countDown();
        workerAFuture.get(3, TimeUnit.SECONDS);

        // Now Worker B unblocks and finishes its update
        int updatedB = workerBFuture.get(3, TimeUnit.SECONDS);
        assertThat(updatedB).isEqualTo(1);

        executorA.shutdown();
        executorB.shutdown();
    }

    @Test
    @DisplayName("P1 Regression A5: Expired worker cannot mark execution SUCCESS via completeExecution")
    void testExpiredWorkerCannotCompleteExecution() throws InterruptedException {
        UUID profileId = createTestProfile("expired-worker-complete@example.com");

        SearchPlanRecord plan = searchPlanRepository.create(new SearchPlanRecord(
                UuidV7.generate(), profileId, "A5 Expiry Complete Plan", List.of("Staff SRE"), Map.of(),
                List.of("MOCK"), "DAILY", 50, true, null, Instant.now(), Instant.now()
        ));

        UUID tokenA = UUID.randomUUID();
        // Claim lease with 50ms TTL
        boolean claimed = searchPlanRepository.claimExecutionLease(plan.id(), tokenA, 50L);
        assertThat(claimed).isTrue();

        // Wait for lease to expire against PostgreSQL clock
        Thread.sleep(120);

        // Expired Worker A attempts completeExecution
        boolean completed = searchPlanRepository.completeExecution(plan.id(), tokenA, "SUCCESS", null, 25);
        assertThat(completed).isFalse();

        // Assert database state was NOT corrupted by the expired worker
        SearchPlanRecord current = searchPlanRepository.findByIdAndProfileId(plan.id(), profileId).orElseThrow();
        assertThat(current.lastExecutionStatus()).isEqualTo("RUNNING"); // Still RUNNING, awaiting recovery or takeover
        assertThat(current.lastExecutionResults()).isEqualTo(0); // Results count was NOT updated
    }

    @Test
    @DisplayName("P1 Regression A6: Stale lease recovery and due plan polling evaluate against database clock")
    void testDatabaseClockRecoveryAndDuePlans() throws InterruptedException {
        UUID profileId = createTestProfile("db-clock-test@example.com");

        SearchPlanRecord plan = searchPlanRepository.create(new SearchPlanRecord(
                UuidV7.generate(), profileId, "A6 DB Clock Plan", List.of("DevOps Engineer"), Map.of(),
                List.of("MOCK"), "HOURLY", 50, true, null, Instant.now(), Instant.now()
        ));

        // 1. Test database-clock stale recovery
        UUID token = UUID.randomUUID();
        searchPlanRepository.claimExecutionLease(plan.id(), token, 50L);
        Thread.sleep(120);

        int recovered = searchPlanRepository.recoverStaleExecutions();
        assertThat(recovered).isGreaterThanOrEqualTo(1);

        SearchPlanRecord afterRecovery = searchPlanRepository.findByIdAndProfileId(plan.id(), profileId).orElseThrow();
        assertThat(afterRecovery.lastExecutionStatus()).isEqualTo("FAILED");
        assertThat(afterRecovery.lastExecutionError()).contains("Execution lease expired");

        // 2. Test database-clock findDuePlans
        // Set last_run_at to 2 hours ago via PostgreSQL clock
        jdbcTemplate.update("UPDATE search_plans SET last_run_at = clock_timestamp() - INTERVAL '2 hours' WHERE id = ?", plan.id());
        List<SearchPlanRecord> due = searchPlanRepository.findDuePlans();
        assertThat(due).anyMatch(p -> p.id().equals(plan.id()));
    }

    @Test
    @DisplayName("Task 4: Concurrent Search Plans preserve independent scan offsets in PostgreSQL without overwriting")
    void testConcurrentSearchPlansPreserveIndependentScanOffsetsInRealPostgres() throws Exception {
        UUID sourceId = UuidV7.generate();
        jdbcTemplate.update("""
                INSERT INTO job_sources (id, kind, org_identifier, display_name, capabilities, policy, enabled, last_run)
                VALUES (?, 'GREENHOUSE', 'concurrent-offset-test', 'MOCK_SOURCE', '{}'::jsonb, 'DISCOVERY_ONLY', true, '{}'::jsonb)
                ON CONFLICT (kind, org_identifier) DO NOTHING
                """, sourceId);

        UUID planA = UuidV7.generate();
        UUID planB = UuidV7.generate();

        int numThreads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);

        // Thread 1: Plan A updates offset to 50
        executor.submit(() -> {
            try {
                startLatch.await();
                jdbcTemplate.update("""
                        UPDATE job_sources SET
                            last_run = jsonb_set(
                                coalesce(last_run, '{}'::jsonb),
                                '{plan_offsets}',
                                coalesce(last_run->'plan_offsets', '{}'::jsonb) || jsonb_build_object(?::text, ?::int)
                            )
                        WHERE id = ?
                        """, planA.toString(), 50, sourceId);
            } catch (Exception ignored) {
            } finally {
                doneLatch.countDown();
            }
        });

        // Thread 2: Plan B updates offset to 75
        executor.submit(() -> {
            try {
                startLatch.await();
                jdbcTemplate.update("""
                        UPDATE job_sources SET
                            last_run = jsonb_set(
                                coalesce(last_run, '{}'::jsonb),
                                '{plan_offsets}',
                                coalesce(last_run->'plan_offsets', '{}'::jsonb) || jsonb_build_object(?::text, ?::int)
                            )
                        WHERE id = ?
                        """, planB.toString(), 75, sourceId);
            } catch (Exception ignored) {
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        assertThat(doneLatch.await(5, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // 1. Verify in real PostgreSQL that both plan offsets are persisted
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT
                    last_run->'plan_offsets'->>? as offset_a,
                    last_run->'plan_offsets'->>? as offset_b
                FROM job_sources WHERE id = ?
                """, planA.toString(), planB.toString(), sourceId);

        assertThat(row.get("offset_a")).isEqualTo("50");
        assertThat(row.get("offset_b")).isEqualTo("75");

        // 2. Verify a fresh BoardDiscoveryService instance (application restart) reads both offsets independently
        BoardDiscoveryService freshInstance = new BoardDiscoveryService(
                jdbcTemplate, jobDiscoveryService, new PlatformRegistry(50), 50, 150, 400, 3, 1500L, 10
        );

        java.lang.reflect.Method getOffsetMethod = BoardDiscoveryService.class.getDeclaredMethod(
                "getPersistedScanOffset", UUID.class, UUID.class);
        getOffsetMethod.setAccessible(true);

        int offsetA = (int) getOffsetMethod.invoke(freshInstance, sourceId, planA);
        int offsetB = (int) getOffsetMethod.invoke(freshInstance, sourceId, planB);

        assertThat(offsetA).isEqualTo(50);
        assertThat(offsetB).isEqualTo(75);
    }

    private UUID createTestProfile(String email) {
        UUID userId = UuidV7.generate();
        UUID profileId = UuidV7.generate();

        jdbcTemplate.update("""
                INSERT INTO users (id, email, password_hash, display_name, created_at)
                VALUES (?, ?, 'hash', 'Test Candidate', now())
                """, userId, email);

        jdbcTemplate.update("""
                INSERT INTO profiles (id, user_id, updated_at)
                VALUES (?, ?, now())
                """, profileId, userId);

        return profileId;
    }
}
