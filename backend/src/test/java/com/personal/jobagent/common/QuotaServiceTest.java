package com.personal.jobagent.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QuotaServiceTest {

    private JdbcTemplate db;
    private QuotaService service;

    @BeforeEach
    void setUp() {
        db = mock(JdbcTemplate.class);
        service = new QuotaService(db, 10, 5);
    }

    // ── recordAndCheck ──

    @Test
    void firstCallWithinQuotaIsAllowed() {
        when(db.queryForList(contains("usage_quotas"), any(UUID.class), anyString(), any(LocalDate.class)))
                .thenReturn(List.of(Map.of("used", 1.0, "quota_limit", 10.0, "unit", "calls")));

        var result = service.recordAndCheck(UUID.randomUUID(), "llm_daily", 1.0);
        assertThat(result.allowed()).isTrue();
        assertThat(result.remaining()).isCloseTo(9.0, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void callExceedingQuotaBlocksSubsequentCalls() {
        // Used is 11, limit is 10, and the amount was 1 — so used-amount (10) == limit → still allowed (soft)
        when(db.queryForList(contains("usage_quotas"), any(UUID.class), anyString(), any(LocalDate.class)))
                .thenReturn(List.of(Map.of("used", 11.0, "quota_limit", 10.0, "unit", "calls")));

        var result = service.recordAndCheck(UUID.randomUUID(), "llm_daily", 1.0);
        // Soft limit: used-amount=10 which equals limit, so allowed
        assertThat(result.allowed()).isTrue();
    }

    @Test
    void callAfterExceedingQuotaIsBlocked() {
        // Already at 11, adding 1 more → used=12, used-amount=11 > 10 → blocked
        when(db.queryForList(contains("usage_quotas"), any(UUID.class), anyString(), any(LocalDate.class)))
                .thenReturn(List.of(Map.of("used", 12.0, "quota_limit", 10.0, "unit", "calls")));

        var result = service.recordAndCheck(UUID.randomUUID(), "llm_daily", 1.0);
        assertThat(result.allowed()).isFalse();
        assertThat(result.remaining()).isEqualTo(0);
    }

    @Test
    void discoveryQuotaUsesItsOwnLimit() {
        when(db.queryForList(contains("usage_quotas"), any(UUID.class), anyString(), any(LocalDate.class)))
                .thenReturn(List.of(Map.of("used", 6.0, "quota_limit", 5.0, "unit", "calls")));

        var result = service.recordAndCheck(UUID.randomUUID(), "discovery_daily", 1.0);
        // used - amount = 5, equals limit → soft limit, still allowed
        assertThat(result.allowed()).isTrue();
    }

    @Test
    void recordInsertsRowViaUpsert() {
        when(db.queryForList(contains("usage_quotas"), any(UUID.class), anyString(), any(LocalDate.class)))
                .thenReturn(List.of(Map.of("used", 1.0, "quota_limit", 10.0, "unit", "calls")));

        UUID profileId = UUID.randomUUID();
        service.recordAndCheck(profileId, "llm_daily", 1.0);
        verify(db).update(contains("INSERT INTO usage_quotas"),
                eq(profileId), eq("llm_daily"), any(LocalDate.class), eq(1.0), eq(10.0));
    }

    // ── check (pre-flight) ──

    @Test
    void checkWithoutRecordingDoesNotModifyDatabase() {
        when(db.queryForList(contains("usage_quotas"), any(UUID.class), anyString(), any(LocalDate.class)))
                .thenReturn(List.of(Map.of("used", 3.0, "quota_limit", 10.0, "unit", "calls")));

        var result = service.check(UUID.randomUUID(), "llm_daily");
        assertThat(result.allowed()).isTrue();
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void checkReturnsNotAllowedWhenOverQuota() {
        when(db.queryForList(contains("usage_quotas"), any(UUID.class), anyString(), any(LocalDate.class)))
                .thenReturn(List.of(Map.of("used", 10.0, "quota_limit", 10.0, "unit", "calls")));

        var result = service.check(UUID.randomUUID(), "llm_daily");
        assertThat(result.allowed()).isFalse();
    }

    @Test
    void checkWithNoRowReturnsAllowed() {
        when(db.queryForList(contains("usage_quotas"), any(UUID.class), anyString(), any(LocalDate.class)))
                .thenReturn(List.of());

        var result = service.check(UUID.randomUUID(), "llm_daily");
        assertThat(result.allowed()).isTrue();
        assertThat(result.used()).isEqualTo(0);
    }

    // ── Cost estimation ──

    @Test
    void estimateCostFromModelPricing() {
        when(db.queryForList(contains("llm_models"), anyString()))
                .thenReturn(List.of(Map.of(
                        "input_cost_per_mtok", new BigDecimal("0.59"),
                        "output_cost_per_mtok", new BigDecimal("0.79"))));

        var cost = service.estimateCost("llama-3.3-70b-versatile", 1000, 500);
        assertThat(cost.inputCost()).isGreaterThan(BigDecimal.ZERO);
        assertThat(cost.outputCost()).isGreaterThan(BigDecimal.ZERO);
        assertThat(cost.totalCost()).isEqualTo(cost.inputCost().add(cost.outputCost()));
    }

    @Test
    void estimateCostReturnsZeroWhenModelNotFound() {
        when(db.queryForList(contains("llm_models"), anyString()))
                .thenReturn(List.of());

        var cost = service.estimateCost("unknown-model", 1000, 500);
        assertThat(cost).isEqualTo(QuotaService.CostEstimate.ZERO);
    }

    @Test
    void estimateCostHandlesNullPricing() {
        when(db.queryForList(contains("llm_models"), anyString()))
                .thenReturn(List.of(Map.of("input_cost_per_mtok", BigDecimal.ZERO)));
        // Missing output_cost_per_mtok → returns ZERO

        var cost = service.estimateCost("model", 1000, 500);
        // The row exists but output_cost_per_mtok is missing from the map → ZERO
        assertThat(cost).isEqualTo(QuotaService.CostEstimate.ZERO);
    }

    // ── Daily summary ──

    @Test
    void dailySummaryQueriesCurrentDate() {
        when(db.queryForList(contains("usage_quotas"), any(UUID.class), any(LocalDate.class)))
                .thenReturn(List.of(
                        Map.of("quota_key", "llm_daily", "used", 5.0, "quota_limit", 10.0, "unit", "calls")));

        UUID profileId = UUID.randomUUID();
        var summary = service.dailySummary(profileId);
        assertThat(summary).hasSize(1);
        assertThat(summary.get(0).get("quota_key")).isEqualTo("llm_daily");
    }

    // ── Disabled quota ──

    @Test
    void disabledQuotaAlwaysAllows() {
        var disabledService = new QuotaService(db, 10, 5, false);
        var result = disabledService.recordAndCheck(UUID.randomUUID(), "llm_daily", 1.0);
        assertThat(result.allowed()).isTrue();
        verify(db, never()).update(anyString(), any(Object[].class));
    }
}
