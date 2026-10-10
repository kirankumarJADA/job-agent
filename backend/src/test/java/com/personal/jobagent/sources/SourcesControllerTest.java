package com.personal.jobagent.sources;

import com.personal.jobagent.discovery.DiscoveryOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The health check must actually verify a schedulable board source and record
 * the outcome; it must never fake a clean bill of health for a source it
 * cannot check.
 */
class SourcesControllerTest {

    private static final UUID SOURCE = UUID.fromString("00000000-0000-7000-8000-00000000dd30");

    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final DiscoveryOrchestrator orchestrator = mock(DiscoveryOrchestrator.class);
    private SourcesController controller;

    @BeforeEach
    void setUp() {
        controller = new SourcesController(db, orchestrator);
        Map<String, Object> source = new java.util.HashMap<>();
        source.put("id", SOURCE);
        source.put("display_name", "Acme");
        source.put("kind", "GREENHOUSE");
        source.put("policy", "DISCOVERY_ONLY");
        source.put("enabled", true);
        source.put("failure_streak", 0);
        source.put("last_run_at", null);
        source.put("health", "{}");
        when(db.queryForMap(contains("from job_sources where id = ?"), eq(SOURCE))).thenReturn(source);
    }

    @Test
    void healthCheckRunsARealBoardFetchAndRecordsTheOutcome() {
        when(db.queryForList("select kind, org_identifier, enabled from job_sources where id = ?", SOURCE))
                .thenReturn(List.of(Map.of("kind", "GREENHOUSE", "org_identifier", "acme", "enabled", true)));
        when(orchestrator.discoverGreenhouseBoard(SOURCE, "acme")).thenReturn(
                new DiscoveryOrchestrator.DiscoveryRun("corr", List.of(), false, List.of()));

        ResponseEntity<?> response = controller.healthCheck(SOURCE);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(db).update(contains("failure_streak = 0"), eq("0 job(s) seen"), eq(SOURCE));
    }

    @Test
    void aFailedBoardFetchIsRecordedAsAFailureNotReset() {
        when(db.queryForList("select kind, org_identifier, enabled from job_sources where id = ?", SOURCE))
                .thenReturn(List.of(Map.of("kind", "GREENHOUSE", "org_identifier", "acme", "enabled", true)));
        when(orchestrator.discoverGreenhouseBoard(SOURCE, "acme")).thenReturn(
                new DiscoveryOrchestrator.DiscoveryRun("corr", List.of(), false, List.of("greenhouse:UPSTREAM_503")));

        controller.healthCheck(SOURCE);

        verify(db).update(contains("failure_streak = failure_streak + 1"), eq("greenhouse:UPSTREAM_503"), eq(SOURCE));
    }

    @Test
    void aSourceWithoutABoardApiIsReportedUncheckedInsteadOfFakeReset() {
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("kind", "COMPANY_SITE");
        row.put("org_identifier", null);
        row.put("enabled", true);
        when(db.queryForList("select kind, org_identifier, enabled from job_sources where id = ?", SOURCE))
                .thenReturn(List.of(row));

        ResponseEntity<?> response = controller.healthCheck(SOURCE);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(orchestrator, never()).discoverGreenhouseBoard(any(), any());
        verify(orchestrator, never()).discoverAshbyBoard(any(), any());
        verify(db, never()).update(contains("failure_streak = 0"), eq("0 job(s) seen"), eq(SOURCE));
    }

    @Test
    void aDisabledBoardSourceIsNeverFetchedLiveAndKeepsItsRecordedState() {
        when(db.queryForList("select kind, org_identifier, enabled from job_sources where id = ?", SOURCE))
                .thenReturn(List.of(Map.of("kind", "GREENHOUSE", "org_identifier", "acme", "enabled", false)));

        ResponseEntity<?> response = controller.healthCheck(SOURCE);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(orchestrator, never()).discoverGreenhouseBoard(any(), any());
        verify(orchestrator, never()).discoverAshbyBoard(any(), any());
        verify(db, never()).update(contains("update job_sources"), any(), any());
    }

    @Test
    void anUnknownSourceIsNotFound() {
        when(db.queryForList("select kind, org_identifier, enabled from job_sources where id = ?", SOURCE))
                .thenReturn(List.of());

        assertThat(controller.healthCheck(SOURCE).getStatusCode().value()).isEqualTo(404);
    }
}
