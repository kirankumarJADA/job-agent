package com.personal.jobagent.discovery.platform;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Operational coverage view: one row per platform with its CURRENT status.
 *
 * <ul>
 *   <li>{@code BLOCKED} / {@code UNVERIFIED} — from the manifest; no adapter is registered;</li>
 *   <li>{@code NEEDS_BOARD_CONFIGURATION} — the adapter is live-validated but no enabled
 *       source of this platform is configured, so nothing is being discovered from it;</li>
 *   <li>{@code TEMPORARILY_UNAVAILABLE} — every configured, enabled source of this platform
 *       failed its most recent run;</li>
 *   <li>{@code SUPPORTED} — live-validated adapter with at least one enabled source whose
 *       most recent run did not fail (or that has not run yet).</li>
 * </ul>
 * This is an operations surface (Sources page), never part of a candidate's job view.
 */
@Service
public class PlatformCoverageService {

    public enum CoverageStatus { SUPPORTED, NEEDS_BOARD_CONFIGURATION, TEMPORARILY_UNAVAILABLE, BLOCKED, UNVERIFIED }

    private final JdbcTemplate db;
    private final PlatformRegistry registry;

    public PlatformCoverageService(JdbcTemplate db, PlatformRegistry registry) {
        this.db = db;
        this.registry = registry;
    }

    public List<Map<String, Object>> coverage() {
        Map<String, Map<String, Object>> stats = new LinkedHashMap<>();
        for (Map<String, Object> r : db.queryForList("""
                select kind,
                       count(*) filter (where enabled and policy <> 'DISABLED') as enabled_sources,
                       count(*) filter (where enabled and policy <> 'DISABLED' and failure_streak > 0) as failing_sources,
                       count(*) as configured_sources,
                       max(last_success_at) as last_success_at
                from job_sources group by kind
                """)) {
            stats.put(String.valueOf(r.get("kind")), r);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (PlatformKind kind : PlatformKind.values()) {
            Map<String, Object> s = stats.getOrDefault(kind.name(), Map.of());
            long enabled = number(s.get("enabled_sources"));
            long failing = number(s.get("failing_sources"));
            boolean adapter = registry.adapter(kind).isPresent();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", kind.name());
            row.put("name", kind.displayName());
            row.put("group", kind.group().name());
            row.put("aliases", kind.aliases());
            row.put("readiness", kind.readiness().name());
            row.put("adapter", adapter);
            row.put("status", status(kind, adapter, enabled, failing).name());
            row.put("configuration", kind.configHint());
            row.put("configured_sources", number(s.get("configured_sources")));
            row.put("enabled_sources", enabled);
            row.put("failing_sources", failing);
            row.put("last_success_at", s.get("last_success_at") == null ? null : String.valueOf(s.get("last_success_at")));
            rows.add(row);
        }
        return rows;
    }

    static CoverageStatus status(PlatformKind kind, boolean adapter, long enabledSources, long failingSources) {
        if (kind.readiness() == PlatformKind.Readiness.BLOCKED) return CoverageStatus.BLOCKED;
        if (kind.readiness() == PlatformKind.Readiness.UNVERIFIED || !adapter) return CoverageStatus.UNVERIFIED;
        if (enabledSources == 0) return CoverageStatus.NEEDS_BOARD_CONFIGURATION;
        if (failingSources >= enabledSources) return CoverageStatus.TEMPORARILY_UNAVAILABLE;
        return CoverageStatus.SUPPORTED;
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }
}
