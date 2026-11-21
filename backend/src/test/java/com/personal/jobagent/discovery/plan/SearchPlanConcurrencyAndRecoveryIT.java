package com.personal.jobagent.discovery.plan;

import com.personal.jobagent.common.MaintenanceModeService;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.discovery.platform.BoardDiscoveryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.flyway.placeholders.remove_seed_dev_account=false",
        "app.discovery.scheduler-enabled=false",
        "app.security.operator-emails=dev@example.local"
})
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("Phase 2A Hardened: Search Plan Concurrency, Fencing, and Recovery IT")
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

    @MockBean
    private BoardDiscoveryService boardDiscoveryService;

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

        // Create a test job source
        UUID sourceId = UuidV7.generate();
        jdbcTemplate.update("""
                INSERT INTO job_sources (id, kind, org_identifier, display_name, capabilities, policy, enabled)
                VALUES (?, 'GREENHOUSE', 'concurrent-test-org', 'MOCK_SOURCE', '{}'::jsonb, 'DISCOVERY_ONLY', true)
                ON CONFLICT (kind, org_identifier) DO NOTHING
                """, sourceId);

        // Slow mock to keep the winner inside execution for 500ms
        when(boardDiscoveryService.run(any(UUID.class), any(BoardDiscoveryService.Mode.class), anyMap()))
                .thenAnswer(inv -> {
                    Thread.sleep(500);
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
                    // unexpected
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Fire both threads simultaneously
        startLatch.countDown();
        boolean finished = doneLatch.await(10, TimeUnit.SECONDS);
        assertThat(finished).isTrue();
        executor.shutdown();

        assertThat(results).hasSize(2);

        long successCount = results.stream().filter(r -> "SUCCESS".equals(r.status())).count();
        long skippedCount = results.stream().filter(r -> "SKIPPED".equals(r.status())).count();

        // Exactly ONE worker obtained the lease and succeeded; the other was skipped
        assertThat(successCount).isEqualTo(1);
        assertThat(skippedCount).isEqualTo(1);

        SearchPlanExecutionCoordinator.ExecutionSummary skippedSummary = results.stream()
                .filter(r -> "SKIPPED".equals(r.status()))
                .findFirst()
                .orElseThrow();
        assertThat(skippedSummary.notes()).anyMatch(n -> n.contains("already active"));

        // After completion, verify lease was cleanly released in database
        SearchPlanRecord finalPlan = searchPlanRepository.findByIdAndProfileId(plan.id(), profileId).orElseThrow();
        assertThat(finalPlan.executionOwnerToken()).isNull();
        assertThat(finalPlan.executionLeaseExpiresAt()).isNull();
        assertThat(finalPlan.lastExecutionStatus()).isEqualTo("SUCCESS");
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

        when(boardDiscoveryService.run(any(UUID.class), any(BoardDiscoveryService.Mode.class), anyMap()))
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
    @DisplayName("Fencing Protection: An expired worker cannot overwrite a newer execution")
    void testFencingProtectionAgainstExpiredWorker() {
        UUID profileId = createTestProfile("fencing-test@example.com");

        SearchPlanRecord plan = searchPlanRepository.create(new SearchPlanRecord(
                UuidV7.generate(), profileId, "Fencing Plan", List.of(), Map.of(), List.of(), "DAILY", 50, true, null, Instant.now(), Instant.now()
        ));

        UUID tokenWorker1 = UUID.randomUUID();
        UUID tokenWorker2 = UUID.randomUUID();

        // Worker 1 claims lease with a very short duration (1 ms) so it immediately expires
        boolean claimed1 = searchPlanRepository.claimExecutionLease(plan.id(), tokenWorker1, 1L);
        assertThat(claimed1).isTrue();

        // Worker 2 claims lease after Worker 1 expired
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}

        boolean claimed2 = searchPlanRepository.claimExecutionLease(plan.id(), tokenWorker2, 600000L);
        assertThat(claimed2).isTrue();

        // Worker 1 finishes late and attempts to complete execution with old token
        boolean worker1FencedOut = searchPlanRepository.completeExecution(plan.id(), tokenWorker1, "SUCCESS", null, 999);

        // FENCING VERIFICATION: Worker 1 update was rejected (0 rows updated)
        assertThat(worker1FencedOut).isFalse();

        // Worker 2 completes execution with authoritative token
        boolean worker2Completed = searchPlanRepository.completeExecution(plan.id(), tokenWorker2, "SUCCESS", null, 42);
        assertThat(worker2Completed).isTrue();

        SearchPlanRecord recordedPlan = searchPlanRepository.findByIdAndProfileId(plan.id(), profileId).orElseThrow();
        assertThat(recordedPlan.lastExecutionResults()).isEqualTo(42); // Not 999!
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
