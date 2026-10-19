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
        service = new QuotaService(db, 10, 5, 8);
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
    void applicationQuotaUsesItsOwnLimitRatherThanTheGenericDefault() {
        // Robin x Tsenta continuation: application_daily is the Auto Apply
        // daily cap (ApplicationDecisionService#decide) — it must use its own
        // configured limit (8 here), never the 1000 generic fallback.
        when(db.queryForList(contains("usage_quotas"), any(UUID.class), anyString(), any(LocalDate.class)))
                .thenReturn(List.of(Map.of("used", 9.0, "quota_limit", 8.0, "unit", "calls")));

        var result = service.recordAndCheck(UUID.randomUUID(), "application_daily", 1.0);
        // used - amount = 8, equals the configured limit → soft limit, still allowed
        assertThat(result.allowed()).isTrue();
        assertThat(result.limit()).isEqualTo(8.0);
    }

    @Test
    void applicationQuotaBlocksOnceExceeded() {
        when(db.queryForList(contains("usage_quotas"), any(UUID.class), anyString(), any(LocalDate.class)))
                .thenReturn(List.of(Map.of("used", 10.0, "quota_limit", 8.0, "unit", "calls")));

        var result = service.recordAndCheck(UUID.randomUUID(), "application_daily", 1.0);
        // used - amount = 9 > 8 → blocked
        assertThat(result.allowed()).isFalse();
    }

    // ── tryReserve / release (atomic, concurrency-safe reservation) ──

    @Test
    void tryReserveSucceedsExactlyAtTheLimitBoundary() {
        // Already at used=7 against limit 8; reserving 1 more lands exactly
        // on the limit (used=8 <= limit=8) — the UPDATE's WHERE clause must
        // accept this, not just strictly-below cases.
        UUID profile = UUID.randomUUID();
        when(db.queryForList(contains("RETURNING"), eq(1.0), eq(profile), eq("application_daily"),
                any(LocalDate.class), eq(1.0)))
                .thenReturn(List.of(Map.of("used", 8.0, "quota_limit", 8.0)));

        var result = service.tryReserve(profile, "application_daily", 1.0);

        assertThat(result.reserved()).isTrue();
        assertThat(result.used()).isEqualTo(8.0);
        assertThat(result.remaining()).isEqualTo(0.0);
    }

    @Test
    void tryReserveRefusesOneOverTheLimit() {
        // The conditional UPDATE's WHERE (used + amount <= quota_limit)
        // matches no row once that would push used past the limit — the
        // query returns empty, and tryReserve falls back to reading the
        // row's actual current state for an honest refusal.
        UUID profile = UUID.randomUUID();
        when(db.queryForList(contains("RETURNING"), eq(1.0), eq(profile), eq("application_daily"),
                any(LocalDate.class), eq(1.0)))
                .thenReturn(List.of()); // conditional UPDATE matched nothing
        when(db.queryForList(contains("SELECT used, quota_limit"), eq(profile), eq("application_daily"), any(LocalDate.class)))
                .thenReturn(List.of(Map.of("used", 8.0, "quota_limit", 8.0)));

        var result = service.tryReserve(profile, "application_daily", 1.0);

        assertThat(result.reserved()).isFalse();
        assertThat(result.used()).isEqualTo(8.0);
        assertThat(result.limit()).isEqualTo(8.0);
    }

    @Test
    void tryReserveEnsuresTheRowExistsBeforeAttemptingTheConditionalUpdate() {
        UUID profile = UUID.randomUUID();
        when(db.queryForList(contains("RETURNING"), anyDouble(), eq(profile), anyString(), any(LocalDate.class), anyDouble()))
                .thenReturn(List.of(Map.of("used", 1.0, "quota_limit", 8.0)));

        service.tryReserve(profile, "application_daily", 1.0);

        verify(db).update(contains("ON CONFLICT (profile_id, quota_key, period_start) DO NOTHING"),
                eq(profile), eq("application_daily"), any(LocalDate.class), eq(8.0));
    }

    @Test
    void tryReserveOnADisabledQuotaAlwaysSucceedsWithoutTouchingTheDatabase() {
        var disabledService = new QuotaService(db, 10, 5, 8, false);

        var result = disabledService.tryReserve(UUID.randomUUID(), "application_daily", 1.0);

        assertThat(result.reserved()).isTrue();
        verifyNoInteractions(db);
    }

    @Test
    void releaseDecrementsUsageForToday() {
        UUID profile = UUID.randomUUID();

        service.release(profile, "application_daily", 1.0, LocalDate.now());

        verify(db).update(contains("GREATEST(0, used - ?)"), eq(1.0), eq(profile), eq("application_daily"), any(LocalDate.class));
    }

    @Test
    void releaseTargetsTheExactReservationPeriodRatherThanTodaysDate() {
        // Robin x Tsenta continuation: a reservation made just before
        // midnight must be released against THAT day's row even if the
        // release itself happens after midnight. release() must bind
        // whatever LocalDate it is given — never its own LocalDate.now() —
        // or a late release would silently target the wrong (empty) row
        // and leak the original reservation forever.
        UUID profile = UUID.randomUUID();
        LocalDate reservationDay = LocalDate.now().minusDays(1);

        service.release(profile, "application_daily", 1.0, reservationDay);

        verify(db).update(contains("GREATEST(0, used - ?)"), eq(1.0), eq(profile), eq("application_daily"), eq(reservationDay));
        verify(db, never()).update(contains("GREATEST(0, used - ?)"), eq(1.0), eq(profile), eq("application_daily"), eq(LocalDate.now()));
    }

    @Test
    void reservationResultCarriesTheExactPeriodStartUsedForTheReservation() {
        // tryReserve must hand back the SAME LocalDate it reserved against
        // so callers can thread it through to release() later, rather than
        // callers re-deriving "today" themselves at release time.
        UUID profile = UUID.randomUUID();
        when(db.queryForList(contains("RETURNING"), eq(1.0), eq(profile), eq("application_daily"),
                any(LocalDate.class), eq(1.0)))
                .thenReturn(List.of(Map.of("used", 1.0, "quota_limit", 8.0)));

        var result = service.tryReserve(profile, "application_daily", 1.0);

        assertThat(result.periodStart()).isEqualTo(LocalDate.now());
    }

    @Test
    void releaseOnADisabledQuotaNeverTouchesTheDatabase() {
        var disabledService = new QuotaService(db, 10, 5, 8, false);

        disabledService.release(UUID.randomUUID(), "application_daily", 1.0, LocalDate.now());

        verifyNoInteractions(db);
    }

    @Test
    void releaseNeverDrivesUsageNegativeEvenOnADoubleRelease() {
        // GREATEST(0, used - amount) in the SQL itself is the actual
        // guarantee; this test only confirms the call shape that enforces
        // it, since a mocked JdbcTemplate cannot evaluate real SQL.
        UUID profile = UUID.randomUUID();

        service.release(profile, "application_daily", 1.0, LocalDate.now());
        service.release(profile, "application_daily", 1.0, LocalDate.now());

        verify(db, times(2)).update(contains("GREATEST(0,"), eq(1.0), eq(profile), eq("application_daily"), any(LocalDate.class));
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
        var disabledService = new QuotaService(db, 10, 5, 8, false);
        var result = disabledService.recordAndCheck(UUID.randomUUID(), "llm_daily", 1.0);
        assertThat(result.allowed()).isTrue();
        verify(db, never()).update(anyString(), any(Object[].class));
    }
}
