package com.personal.jobagent.discovery.plan;

import com.personal.jobagent.common.MaintenanceModeService;
import com.personal.jobagent.discovery.JobDiscoveryService;
import com.personal.jobagent.discovery.platform.BoardDiscoveryService;
import com.personal.jobagent.jobs.JobMatchService;
import com.personal.jobagent.jobs.JobRepository;
import com.personal.jobagent.preferences.PreferenceSetRecord;
import com.personal.jobagent.preferences.PreferenceSetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("Phase 2A Hardened: SearchPlanExecutionCoordinator Unit Tests")
class SearchPlanExecutionCoordinatorTest {

    private SearchPlanRepository repository;
    private SearchPlanQueryExpander queryExpander;
    private BoardDiscoveryService boardDiscoveryService;
    private JobRepository jobRepository;
    private JobMatchService jobMatchService;
    private PreferenceSetRepository preferenceSetRepository;
    private JdbcTemplate jdbcTemplate;
    private MaintenanceModeService maintenanceMode;
    private SearchPlanExecutionCoordinator coordinator;

    private final UUID profileId = UUID.randomUUID();
    private final UUID planId = UUID.randomUUID();
    private final UUID sourceId1 = UUID.randomUUID();
    private final UUID sourceId2 = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(SearchPlanRepository.class);
        queryExpander = new SearchPlanQueryExpander();
        boardDiscoveryService = mock(BoardDiscoveryService.class);
        jobRepository = mock(JobRepository.class);
        jobMatchService = mock(JobMatchService.class);
        preferenceSetRepository = mock(PreferenceSetRepository.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        maintenanceMode = new MaintenanceModeService(false);

        coordinator = new SearchPlanExecutionCoordinator(
                repository, queryExpander, boardDiscoveryService, jobRepository,
                jobMatchService, preferenceSetRepository, jdbcTemplate, 45000L, maintenanceMode
        );

        // Default: exclusive lease claimed successfully and completed with fencing
        when(repository.claimExecutionLease(eq(planId), any(UUID.class), anyLong()))
                .thenReturn(true);
        when(repository.completeExecution(eq(planId), any(UUID.class), anyString(), any(), anyInt()))
                .thenReturn(true);
    }

    @Test
    @DisplayName("Executes plan successfully, passes query params to Adzuna, and triggers match reranker")
    void executePlan_success() {
        SearchPlanRecord plan = new SearchPlanRecord(
                planId, profileId, "Senior Java Plan",
                List.of("Java Engineer"), Map.of("skills", List.of("Java", "Spring Boot"), "locations", List.of("London")),
                List.of("ADZUNA"), "DAILY", 10, true, null, Instant.now(), Instant.now()
        );
        when(repository.findByIdAndProfileId(planId, profileId)).thenReturn(Optional.of(plan));

        when(jdbcTemplate.queryForList(contains("FROM job_sources"))).thenReturn(List.of(
                Map.of("id", sourceId1, "kind", "ADZUNA", "display_name", "Adzuna UK", "org_identifier", "gb")
        ));

        UUID jobId1 = UUID.randomUUID();
        UUID jobId2 = UUID.randomUUID();

        BoardDiscoveryService.SourceRunReport report = new BoardDiscoveryService.SourceRunReport(
                "corr-1", sourceId1, "ADZUNA", "OK", true, Map.of(), null, null,
                List.of(
                        new JobDiscoveryService.IngestResult(jobId1, "INSERTED", "key1", "hash1"),
                        new JobDiscoveryService.IngestResult(jobId2, "UPDATED", "key2", "hash2")
                ),
                List.of()
        );
        when(boardDiscoveryService.run(eq(sourceId1), eq(BoardDiscoveryService.Mode.SCHEDULED), anyMap(), any(), any(), anyInt(), anyLong())).thenReturn(report);

        PreferenceSetRecord prefs = new PreferenceSetRecord(
                UUID.randomUUID(), profileId, List.of("Java Engineer"), List.of(), List.of(),
                List.of("Java"), List.of("UK"), List.of("HYBRID"), List.of(), List.of("SENIOR"),
                70000L, "NONE", List.of(), List.of(), "MANUAL", Map.of(), Map.of(), true
        );
        when(preferenceSetRepository.findActiveByProfileId(profileId)).thenReturn(Optional.of(prefs));

        SearchPlanExecutionCoordinator.ExecutionSummary summary = coordinator.executePlan(planId, profileId, true);

        assertThat(summary.status()).isEqualTo("SUCCESS");
        assertThat(summary.sourcesQueried()).isEqualTo(1);
        assertThat(summary.sourcesSucceeded()).isEqualTo(1);
        assertThat(summary.jobsIngested()).isEqualTo(2);
        assertThat(summary.jobsMatched()).isEqualTo(2);
        assertThat(summary.errors()).isEmpty();

        // Verify Adzuna received query parameters
        verify(boardDiscoveryService).run(eq(sourceId1), eq(BoardDiscoveryService.Mode.SCHEDULED), argThat(params ->
                params.containsKey("what") && params.containsKey("where") && params.get("where").equals("London")
        ), eq(planId), any(UUID.class), anyInt(), anyLong());

        // Verify match evaluation was called for both jobs
        verify(jobMatchService).evaluateMatch(eq(jobId1), eq(profileId), any(com.personal.jobagent.jobs.EffectiveMatchingCriteria.class), eq(planId), any(UUID.class));
        verify(jobMatchService).evaluateMatch(eq(jobId2), eq(profileId), any(com.personal.jobagent.jobs.EffectiveMatchingCriteria.class), eq(planId), any(UUID.class));

        // Verify repository completed execution with fencing token
        verify(repository).completeExecution(eq(planId), any(UUID.class), eq("SUCCESS"), isNull(), eq(2));
    }

    @Test
    @DisplayName("Halts querying additional providers once maxResultsPerRun ceiling is reached")
    void executePlan_enforcesMaxResultsCeiling() {
        SearchPlanRecord plan = new SearchPlanRecord(
                planId, profileId, "Capped Plan",
                List.of("Developer"), Map.of(),
                List.of("ADZUNA", "ARBEITNOW"), "DAILY", 2, true, null, Instant.now(), Instant.now()
        );
        when(repository.findByIdAndProfileId(planId, profileId)).thenReturn(Optional.of(plan));

        when(jdbcTemplate.queryForList(contains("FROM job_sources"))).thenReturn(List.of(
                Map.of("id", sourceId1, "kind", "ADZUNA", "display_name", "Adzuna UK", "org_identifier", "gb"),
                Map.of("id", sourceId2, "kind", "ARBEITNOW", "display_name", "Arbeitnow Global", "org_identifier", "global")
        ));

        BoardDiscoveryService.SourceRunReport report1 = new BoardDiscoveryService.SourceRunReport(
                "corr-1", sourceId1, "ADZUNA", "OK", true, Map.of(), null, null,
                List.of(
                        new JobDiscoveryService.IngestResult(UUID.randomUUID(), "INSERTED", "key1", "hash1"),
                        new JobDiscoveryService.IngestResult(UUID.randomUUID(), "INSERTED", "key2", "hash2")
                ),
                List.of()
        );
        when(boardDiscoveryService.run(eq(sourceId1), eq(BoardDiscoveryService.Mode.SCHEDULED), anyMap(), any(), any(), anyInt(), anyLong())).thenReturn(report1);

        SearchPlanExecutionCoordinator.ExecutionSummary summary = coordinator.executePlan(planId, profileId, false);

        assertThat(summary.status()).isEqualTo("SUCCESS");
        assertThat(summary.jobsIngested()).isEqualTo(2);
        assertThat(summary.sourcesQueried()).isEqualTo(1);
        assertThat(summary.notes()).anyMatch(n -> n.contains("Reached per-plan result cap"));

        verify(boardDiscoveryService, times(1)).run(any(), any(), any(), any(), any(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("Handles partial source failures and records PARTIAL status")
    void executePlan_partialFailure() {
        SearchPlanRecord plan = new SearchPlanRecord(
                planId, profileId, "Multi Source Plan",
                List.of("Developer"), Map.of(),
                List.of("ADZUNA", "FREEHIRE"), "DAILY", 50, true, null, Instant.now(), Instant.now()
        );
        when(repository.findByIdAndProfileId(planId, profileId)).thenReturn(Optional.of(plan));

        when(jdbcTemplate.queryForList(contains("FROM job_sources"))).thenReturn(List.of(
                Map.of("id", sourceId1, "kind", "ADZUNA", "display_name", "Adzuna UK", "org_identifier", "gb"),
                Map.of("id", sourceId2, "kind", "FREEHIRE", "display_name", "FreeHire Tech", "org_identifier", "tech")
        ));

        BoardDiscoveryService.SourceRunReport report1 = new BoardDiscoveryService.SourceRunReport(
                "corr-1", sourceId1, "ADZUNA", "OK", true, Map.of(), null, null,
                List.of(new JobDiscoveryService.IngestResult(UUID.randomUUID(), "INSERTED", "key1", "hash1")),
                List.of()
        );
        when(boardDiscoveryService.run(eq(sourceId1), eq(BoardDiscoveryService.Mode.SCHEDULED), anyMap(), any(), any(), anyInt(), anyLong())).thenReturn(report1);

        BoardDiscoveryService.SourceRunReport report2 = new BoardDiscoveryService.SourceRunReport(
                "corr-2", sourceId2, "FREEHIRE", "FAILED", false, Map.of(),
                "RATE_LIMITED", "HTTP 429 Too Many Requests", List.of(), List.of("Rate limited")
        );
        when(boardDiscoveryService.run(eq(sourceId2), eq(BoardDiscoveryService.Mode.SCHEDULED), anyMap(), any(), any(), anyInt(), anyLong())).thenReturn(report2);

        SearchPlanExecutionCoordinator.ExecutionSummary summary = coordinator.executePlan(planId, profileId, false);

        assertThat(summary.status()).isEqualTo("PARTIAL");
        assertThat(summary.sourcesQueried()).isEqualTo(2);
        assertThat(summary.sourcesSucceeded()).isEqualTo(1);
        assertThat(summary.sourcesFailed()).isEqualTo(1);
        assertThat(summary.jobsIngested()).isEqualTo(1);
        assertThat(summary.errors()).anyMatch(e -> e.contains("RATE_LIMITED"));

        verify(repository).completeExecution(eq(planId), any(UUID.class), eq("PARTIAL"), contains("RATE_LIMITED"), eq(1));
    }

    @Test
    @DisplayName("Prevents overlapping execution when execution lease is held by another worker")
    void executePlan_overlappingExecutionPrevented() {
        SearchPlanRecord plan = new SearchPlanRecord(
                planId, profileId, "Plan", List.of(), Map.of(), List.of(), "HOURLY", 50, true, null, Instant.now(), Instant.now()
        );
        when(repository.findByIdAndProfileId(planId, profileId)).thenReturn(Optional.of(plan));

        // Lease claim returns false (another process has the lease)
        when(repository.claimExecutionLease(eq(planId), any(UUID.class), anyLong()))
                .thenReturn(false);

        SearchPlanExecutionCoordinator.ExecutionSummary summary = coordinator.executePlan(planId, profileId, false);

        assertThat(summary.status()).isEqualTo("SKIPPED");
        assertThat(summary.notes()).anyMatch(n -> n.contains("already active"));

        verifyNoInteractions(boardDiscoveryService);
    }

    @Test
    @DisplayName("Catches unexpected execution runtime exception and persists FAILED status")
    void executePlan_handlesUnexpectedExceptionAndPersistsFailed() {
        SearchPlanRecord plan = new SearchPlanRecord(
                planId, profileId, "Plan", List.of("Crash Engineer"), Map.of(), List.of("ADZUNA"), "DAILY", 50, true, null, Instant.now(), Instant.now()
        );
        when(repository.findByIdAndProfileId(planId, profileId)).thenReturn(Optional.of(plan));

        when(jdbcTemplate.queryForList(contains("FROM job_sources"))).thenThrow(new RuntimeException("Simulated DB connection failure"));

        SearchPlanExecutionCoordinator.ExecutionSummary summary = coordinator.executePlan(planId, profileId, false);

        assertThat(summary.status()).isEqualTo("FAILED");
        assertThat(summary.errors()).anyMatch(e -> e.contains("Simulated DB connection failure"));

        // Verifies FAILED status is persisted with the owner token
        verify(repository).completeExecution(eq(planId), any(UUID.class), eq("FAILED"), contains("Simulated DB connection failure"), eq(0));
    }

    @Test
    @DisplayName("Honors maintenance mode by pausing search plan execution")
    void executePlan_skipsDuringMaintenance() {
        MaintenanceModeService mm = mock(MaintenanceModeService.class);
        when(mm.isMaintenanceMode()).thenReturn(true);

        SearchPlanExecutionCoordinator coordWithMm = new SearchPlanExecutionCoordinator(
                repository, queryExpander, boardDiscoveryService, jobRepository,
                jobMatchService, preferenceSetRepository, jdbcTemplate, 45000L, mm
        );

        SearchPlanExecutionCoordinator.ExecutionSummary summary = coordWithMm.executePlan(planId, profileId, false);

        assertThat(summary.status()).isEqualTo("SKIPPED");
        assertThat(summary.notes()).anyMatch(n -> n.contains("maintenance mode"));
        verifyNoInteractions(boardDiscoveryService);
    }

    @Test
    @DisplayName("Global budget counts only INSERTED and UPDATED, ignoring TOUCHED across multiple sources")
    void globalBudgetCountsOnlyInsertedAndUpdated() {
        SearchPlanRecord plan = new SearchPlanRecord(
                planId, profileId, "Multi-source Plan",
                List.of("Engineer"), Map.of(), List.of("ADZUNA", "GREENHOUSE"), "DAILY", 5, true, null, Instant.now(), Instant.now()
        );
        when(repository.findByIdAndProfileId(planId, profileId)).thenReturn(Optional.of(plan));

        when(jdbcTemplate.queryForList(contains("FROM job_sources"))).thenReturn(List.of(
                Map.of("id", sourceId1, "kind", "ADZUNA", "display_name", "Source 1", "org_identifier", "gb"),
                Map.of("id", sourceId2, "kind", "GREENHOUSE", "display_name", "Source 2", "org_identifier", "gh")
        ));

        // Source 1 returns 10 TOUCHED jobs and 2 INSERTED jobs
        List<JobDiscoveryService.IngestResult> s1Results = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            s1Results.add(new JobDiscoveryService.IngestResult(UUID.randomUUID(), "TOUCHED", "k" + i, "h" + i));
        }
        s1Results.add(new JobDiscoveryService.IngestResult(UUID.randomUUID(), "INSERTED", "new1", "h_new1"));
        s1Results.add(new JobDiscoveryService.IngestResult(UUID.randomUUID(), "INSERTED", "new2", "h_new2"));

        BoardDiscoveryService.SourceRunReport report1 = new BoardDiscoveryService.SourceRunReport(
                "corr-1", sourceId1, "ADZUNA", "OK", true, Map.of(), null, null, s1Results, List.of()
        );
        // Source 2 returns 3 INSERTED jobs (meeting global budget of 5)
        UUID s2Job1 = UUID.randomUUID();
        UUID s2Job2 = UUID.randomUUID();
        UUID s2Job3 = UUID.randomUUID();
        BoardDiscoveryService.SourceRunReport report2 = new BoardDiscoveryService.SourceRunReport(
                "corr-2", sourceId2, "GREENHOUSE", "OK", true, Map.of(), null, null,
                List.of(
                        new JobDiscoveryService.IngestResult(s2Job1, "INSERTED", "s2_1", "h1"),
                        new JobDiscoveryService.IngestResult(s2Job2, "INSERTED", "s2_2", "h2"),
                        new JobDiscoveryService.IngestResult(s2Job3, "INSERTED", "s2_3", "h3")
                ),
                List.of()
        );

        // When source 1 runs, remainingBudget is 5
        when(boardDiscoveryService.run(eq(sourceId1), any(), anyMap(), any(), any(), eq(5), anyLong())).thenReturn(report1);
        // Source 1 produced 2 new results (10 TOUCHED did not count); remaining budget passed to source 2 is 3
        when(boardDiscoveryService.run(eq(sourceId2), any(), anyMap(), any(), any(), eq(3), anyLong())).thenReturn(report2);

        SearchPlanExecutionCoordinator.ExecutionSummary summary = coordinator.executePlan(planId, profileId, true);

        assertThat(summary.status()).isEqualTo("SUCCESS");
        assertThat(summary.sourcesQueried()).isEqualTo(2);
        assertThat(summary.jobsIngested()).isEqualTo(5); // exactly 2 from s1 + 3 from s2
    }

    @Test
    @DisplayName("executePlanWithReservedLease uses existing reserved owner token and skips claiming new lease")
    void executePlanWithReservedLease_usesReservedToken() {
        UUID reservedToken = UUID.randomUUID();
        SearchPlanRecord plan = new SearchPlanRecord(
                planId, profileId, "Reserved Plan",
                List.of("DevOps"), Map.of(), List.of("ADZUNA"), "DAILY", 10, true, null, Instant.now(), Instant.now()
        );
        when(repository.findByIdAndProfileId(planId, profileId)).thenReturn(Optional.of(plan));
        when(jdbcTemplate.queryForList(contains("FROM job_sources"))).thenReturn(List.of(
                Map.of("id", sourceId1, "kind", "ADZUNA", "display_name", "Adzuna UK", "org_identifier", "gb")
        ));
        BoardDiscoveryService.SourceRunReport report = new BoardDiscoveryService.SourceRunReport(
                "corr-res", sourceId1, "ADZUNA", "OK", true, Map.of(), null, null, List.of(), List.of()
        );
        when(boardDiscoveryService.run(eq(sourceId1), any(), anyMap(), any(), any(), anyInt(), anyLong())).thenReturn(report);
        when(repository.completeExecution(eq(planId), eq(reservedToken), anyString(), any(), anyInt())).thenReturn(true);

        SearchPlanExecutionCoordinator.ExecutionSummary summary =
                coordinator.executePlanWithReservedLease(planId, profileId, reservedToken, true);

        assertThat(summary.status()).isEqualTo("SUCCESS");
        // Verify repository.claimExecutionLease was NEVER invoked because lease was already reserved
        verify(repository, never()).claimExecutionLease(eq(planId), any(UUID.class), anyLong());
        // Verify completeExecution was called with the exact reservedToken
        verify(repository).completeExecution(eq(planId), eq(reservedToken), eq("SUCCESS"), any(), eq(0));
    }
}
