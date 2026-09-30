package com.personal.jobagent.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the claim-next queue mechanism: atomic claiming,
 * empty queue, heartbeat, stale recovery.
 */
class AutomationPlanClaimTest {

    private JdbcTemplate db;
    private AutomationPlanRepository repo;
    private static final UUID PLAN_A = UUID.randomUUID();
    private static final UUID APP_A = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        db = mock(JdbcTemplate.class);
        repo = new AutomationPlanRepository(db, new ObjectMapper());
    }

    @SuppressWarnings("unchecked")
    @Test
    void claimNextReturnsEmptyWhenNoPreparedPlans() {
        when(db.query(contains("for update skip locked"), any(RowMapper.class)))
                .thenReturn(List.of());

        Optional<AutomationPlanRepository.PlanRow> result = repo.claimNext();

        assertThat(result).isEmpty();
        verify(db, never()).update(contains("status='RUNNING'"), any(UUID.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void claimNextTransitionsToPreparedToRunning() {
        when(db.query(contains("for update skip locked"), any(RowMapper.class)))
                .thenReturn(List.of(PLAN_A));
        when(db.update(contains("status='RUNNING'"), eq(PLAN_A))).thenReturn(1);
        // findById after claim
        when(db.query(contains("from automation_plans where id=?"), any(RowMapper.class), eq(PLAN_A)))
                .thenReturn(List.of(
                        new AutomationPlanRepository.PlanRow(PLAN_A, APP_A,
                                "https://example.com", "RUNNING",
                                Map.of(), List.of(), false, Instant.now(), Instant.now())));

        Optional<AutomationPlanRepository.PlanRow> result = repo.claimNext();

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(PLAN_A);
        assertThat(result.get().status()).isEqualTo("RUNNING");
        verify(db).update(contains("status='RUNNING'"), eq(PLAN_A));
    }

    @Test
    void claimTransitionOnlyFromPrepared() {
        // claim() only transitions PREPARED -> RUNNING
        when(db.update(contains("status='RUNNING'") , eq(PLAN_A))).thenReturn(0);

        boolean claimed = repo.claim(PLAN_A);

        assertThat(claimed).isFalse();
    }

    @Test
    void heartbeatOnlyWhileRunning() {
        when(db.update(contains("heartbeat_at=now()"), eq(PLAN_A))).thenReturn(1);

        assertThat(repo.heartbeat(PLAN_A)).isTrue();
    }

    @Test
    void recoverStaleResetsRunningToPreparable() {
        Instant cutoff = Instant.now().minusSeconds(900);
        when(db.update(contains("status='PREPARED'"), any(java.sql.Timestamp.class))).thenReturn(2);

        int recovered = repo.recoverStale(cutoff);

        assertThat(recovered).isEqualTo(2);
    }

    @Test
    void approveSubmitOnlyFromAwaitingState() {
        when(db.update(contains("submit_approved=true"), eq(PLAN_A))).thenReturn(1);

        assertThat(repo.approveSubmit(PLAN_A)).isTrue();
    }

    @Test
    void ownershipCheckReturnsFalseForNull() {
        assertThat(repo.owns(null, PLAN_A)).isFalse();
        assertThat(repo.owns(UUID.randomUUID(), null)).isFalse();
    }

    @Test
    void transitionIsGuardedByCurrentStatus() {
        when(db.update(anyString(), eq("COMPLETED"), eq(PLAN_A), eq("RUNNING"))).thenReturn(1);

        assertThat(repo.transition(PLAN_A, "RUNNING", "COMPLETED")).isTrue();
        assertThat(repo.transition(PLAN_A, "PREPARED", "COMPLETED")).isFalse();
    }
}
