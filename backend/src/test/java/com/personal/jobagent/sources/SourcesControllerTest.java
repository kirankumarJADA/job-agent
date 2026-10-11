package com.personal.jobagent.sources;

import com.personal.jobagent.discovery.platform.BoardDiscoveryService;
import com.personal.jobagent.discovery.platform.PlatformCoverageService;
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
 * The health check must actually verify a board source through the real run
 * path and report the recorded outcome; it must never fake a clean bill of
 * health for a source it cannot check, and never fetch a disabled source.
 *
 * <p>Phase 8.2.5 rewrite: the check now delegates to {@link BoardDiscoveryService}
 * (all supported platforms, one path). Outcome recording — success resets the
 * streak, failure increments it — is asserted in BoardDiscoveryServiceTest;
 * this test keeps every controller-level behaviour previously asserted.
 */
class SourcesControllerTest {

    private static final UUID SOURCE = UUID.fromString("00000000-0000-7000-8000-00000000dd30");
    private static final String KIND_QUERY = "select kind, enabled, policy from job_sources where id = ?";

    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final BoardDiscoveryService boards = mock(BoardDiscoveryService.class);
    private SourcesController controller;

    @BeforeEach
    void setUp() {
        controller = new SourcesController(db, boards, mock(PlatformCoverageService.class));
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
        when(boards.isRunnable("GREENHOUSE")).thenReturn(true);
        when(boards.isRunnable("WORKDAY")).thenReturn(true);
    }

    private static Map<String, Object> row(String kind, boolean enabled, String policy) {
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("kind", kind);
        row.put("enabled", enabled);
        row.put("policy", policy);
        return row;
    }

    private static BoardDiscoveryService.SourceRunReport report(String failure) {
        return new BoardDiscoveryService.SourceRunReport("corr", SOURCE, "GREENHOUSE", failure == null ? "ok" : "failed",
                failure == null, Map.of("listed", 0), failure, null, List.of(), List.of());
    }

    @Test
    void healthCheckRunsARealBoundedBoardRunAndReturnsTheRecordedOutcome() {
        when(db.queryForList(KIND_QUERY, SOURCE)).thenReturn(List.of(row("GREENHOUSE", true, "DISCOVERY_ONLY")));
        when(boards.run(SOURCE, BoardDiscoveryService.Mode.HEALTH_CHECK)).thenReturn(report(null));

        ResponseEntity<?> response = controller.healthCheck(SOURCE);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(boards).run(SOURCE, BoardDiscoveryService.Mode.HEALTH_CHECK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsKey("run");
    }

    @Test
    void everySupportedPlatformIsCheckedNotOnlyGreenhouseAndAshby() {
        when(db.queryForList(KIND_QUERY, SOURCE)).thenReturn(List.of(row("WORKDAY", true, "DISCOVERY_ONLY")));
        when(boards.run(SOURCE, BoardDiscoveryService.Mode.HEALTH_CHECK)).thenReturn(report("UPSTREAM_ERROR"));

        controller.healthCheck(SOURCE);

        verify(boards).run(SOURCE, BoardDiscoveryService.Mode.HEALTH_CHECK);
        // A failed run is never turned into a reset by the controller.
        verify(db, never()).update(contains("failure_streak = 0"), any(Object[].class));
    }

    @Test
    void aSourceWithoutABoardAdapterIsReportedUncheckedInsteadOfFakeReset() {
        when(db.queryForList(KIND_QUERY, SOURCE)).thenReturn(List.of(row("COMPANY_SITE", true, "DISCOVERY_ONLY")));

        ResponseEntity<?> response = controller.healthCheck(SOURCE);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(boards, never()).run(any(), any());
        verify(db, never()).update(contains("failure_streak = 0"), any(Object[].class));
    }

    @Test
    void aDisabledBoardSourceIsNeverFetchedLiveAndKeepsItsRecordedState() {
        when(db.queryForList(KIND_QUERY, SOURCE)).thenReturn(List.of(row("GREENHOUSE", false, "DISCOVERY_ONLY")));

        ResponseEntity<?> response = controller.healthCheck(SOURCE);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(boards, never()).run(any(), any());
        verify(db, never()).update(contains("update job_sources"), any(Object[].class));
    }

    @Test
    void aDisabledPolicyIsTreatedAsDisabled() {
        when(db.queryForList(KIND_QUERY, SOURCE)).thenReturn(List.of(row("GREENHOUSE", true, "DISABLED")));

        controller.healthCheck(SOURCE);

        verify(boards, never()).run(any(), any());
    }

    @Test
    void anUnknownSourceIsNotFound() {
        when(db.queryForList(KIND_QUERY, SOURCE)).thenReturn(List.of());

        assertThat(controller.healthCheck(SOURCE).getStatusCode().value()).isEqualTo(404);
    }
}
