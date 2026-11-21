package com.personal.jobagent.discovery.plan;

import com.personal.jobagent.common.MaintenanceModeService;
import com.personal.jobagent.discovery.JobDiscoveryService;
import com.personal.jobagent.discovery.platform.BoardDiscoveryService;
import com.personal.jobagent.jobs.JobMatchService;
import com.personal.jobagent.jobs.JobRecord;
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

@DisplayName("Phase 2 Deliverable A: SearchPlanExecutionCoordinator Unit Tests")
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

        // Default: advisory lock acquired successfully
        when(jdbcTemplate.queryForObject(contains("pg_try_advisory_lock"), eq(Boolean.class), any()))
                .thenReturn(Boolean.TRUE);
    }

    @Test
    @DisplayName("Executes plan successfully, respects per-plan limit, and triggers match reranker")
    void executePlan_success() {
        SearchPlanRecord plan = new SearchPlanRecord(
                planId, profileId, "Senior Java Plan",
                List.of("Java Engineer"), Map.of("skills", List.of("Java", "Spring Boot")),
                List.of("ADZUNA"), "DAILY", 10, true, null, Instant.now(), Instant.now()
        );
        when(repository.findByIdAndProfileId(planId, profileId)).thenReturn(Optional.of(plan));

        // Mock job sources in DB
        when(jdbcTemplate.queryForList(contains("FROM job_sources"))).thenReturn(List.of(
                Map.of("id", sourceId1, "kind", "ADZUNA", "display_name", "Adzuna UK", "org_identifier", "gb")
        ));

        UUID jobId1 = UUID.randomUUID();
        UUID jobId2 = UUID.randomUUID();

        // Mock board discovery report
        BoardDiscoveryService.SourceRunReport report = new BoardDiscoveryService.SourceRunReport(
                "corr-1", sourceId1, "ADZUNA", "OK", true, Map.of(), null, null,
                List.of(
                        new JobDiscoveryService.IngestResult(jobId1, "INSERTED", "key1", "hash1"),
                        new JobDiscoveryService.IngestResult(jobId2, "UPDATED", "key2", "hash2")
                ),
                List.of()
        );
        when(boardDiscoveryService.run(eq(sourceId1), eq(BoardDiscoveryService.Mode.SCHEDULED))).thenReturn(report);

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

        // Verify match evaluation was called for both jobs
        verify(jobMatchService).evaluateMatch(eq(jobId1), eq(profileId), eq(prefs));
        verify(jobMatchService).evaluateMatch(eq(jobId2), eq(profileId), eq(prefs));

        // Verify repository recorded execution outcome
        verify(repository).recordExecution(eq(planId), eq("SUCCESS"), isNull(), eq(2));
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

        // First source already yields 2 jobs (matching ceiling 2)
        BoardDiscoveryService.SourceRunReport report1 = new BoardDiscoveryService.SourceRunReport(
                "corr-1", sourceId1, "ADZUNA", "OK", true, Map.of(), null, null,
                List.of(
                        new JobDiscoveryService.IngestResult(UUID.randomUUID(), "INSERTED", "key1", "hash1"),
                        new JobDiscoveryService.IngestResult(UUID.randomUUID(), "INSERTED", "key2", "hash2")
                ),
                List.of()
        );
        when(boardDiscoveryService.run(eq(sourceId1), eq(BoardDiscoveryService.Mode.SCHEDULED))).thenReturn(report1);

        SearchPlanExecutionCoordinator.ExecutionSummary summary = coordinator.executePlan(planId, profileId, false);

        assertThat(summary.status()).isEqualTo("SUCCESS");
        assertThat(summary.jobsIngested()).isEqualTo(2);
        assertThat(summary.sourcesQueried()).isEqualTo(1); // second source was not queried because cap reached
        assertThat(summary.notes()).anyMatch(n -> n.contains("Reached per-plan result cap"));

        verify(boardDiscoveryService, times(1)).run(any(), any());
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

        // Source 1 succeeds
        BoardDiscoveryService.SourceRunReport report1 = new BoardDiscoveryService.SourceRunReport(
                "corr-1", sourceId1, "ADZUNA", "OK", true, Map.of(), null, null,
                List.of(new JobDiscoveryService.IngestResult(UUID.randomUUID(), "INSERTED", "key1", "hash1")),
                List.of()
        );
        when(boardDiscoveryService.run(eq(sourceId1), eq(BoardDiscoveryService.Mode.SCHEDULED))).thenReturn(report1);

        // Source 2 fails
        BoardDiscoveryService.SourceRunReport report2 = new BoardDiscoveryService.SourceRunReport(
                "corr-2", sourceId2, "FREEHIRE", "FAILED", false, Map.of(),
                "RATE_LIMITED", "HTTP 429 Too Many Requests", List.of(), List.of("Rate limited")
        );
        when(boardDiscoveryService.run(eq(sourceId2), eq(BoardDiscoveryService.Mode.SCHEDULED))).thenReturn(report2);

        SearchPlanExecutionCoordinator.ExecutionSummary summary = coordinator.executePlan(planId, profileId, false);

        assertThat(summary.status()).isEqualTo("PARTIAL");
        assertThat(summary.sourcesQueried()).isEqualTo(2);
        assertThat(summary.sourcesSucceeded()).isEqualTo(1);
        assertThat(summary.sourcesFailed()).isEqualTo(1);
        assertThat(summary.jobsIngested()).isEqualTo(1);
        assertThat(summary.errors()).anyMatch(e -> e.contains("RATE_LIMITED"));

        verify(repository).recordExecution(eq(planId), eq("PARTIAL"), contains("RATE_LIMITED"), eq(1));
    }

    @Test
    @DisplayName("Prevents overlapping execution when advisory lock is held")
    void executePlan_overlappingExecutionPrevented() {
        SearchPlanRecord plan = new SearchPlanRecord(
                planId, profileId, "Plan", List.of(), Map.of(), List.of(), "HOURLY", 50, true, null, Instant.now(), Instant.now()
        );
        when(repository.findByIdAndProfileId(planId, profileId)).thenReturn(Optional.of(plan));

        // Advisory lock returns false (another process has the lock)
        when(jdbcTemplate.queryForObject(contains("pg_try_advisory_lock"), eq(Boolean.class), any()))
                .thenReturn(Boolean.FALSE);

        SearchPlanExecutionCoordinator.ExecutionSummary summary = coordinator.executePlan(planId, profileId, false);

        assertThat(summary.status()).isEqualTo("SKIPPED");
        assertThat(summary.notes()).anyMatch(n -> n.contains("already active"));

        // No board discovery should have been run
        verifyNoInteractions(boardDiscoveryService);
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
}
