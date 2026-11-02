package com.personal.jobagent.discovery.platform;

import com.personal.jobagent.common.QuotaService;
import com.personal.jobagent.preferences.PreferenceSetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Phase 9: "Robin should select applicable configured sources internally" —
 * the user-triggered, preference-driven, multi-source discovery run. This is
 * deliberately a thin orchestration layer, not a second discovery engine: the
 * actual unit of work for every selected source is the exact same
 * {@link BoardDiscoveryService#run(UUID, BoardDiscoveryService.Mode)} the
 * scheduler and {@code POST /discovery/run} already use — same adapters, same
 * {@link SafeHttpClient} budgets/timeouts/retries, same ingestion,
 * normalization and dedup. This class adds exactly three things the existing
 * single-source path never had: (1) selecting WHICH sources apply to one
 * candidate's configured region(s), (2) running the selected sources
 * CONCURRENTLY instead of one at a time, each bounded by its own timeout so a
 * hanging or failing source never blocks the others, and (3) enforcing the
 * {@code discovery_daily} quota dimension ({@link QuotaService}), which was
 * defined from the start but had no caller until now.
 *
 * <p><b>Region selection.</b> A source whose {@code job_sources.region} is
 * {@code 'GLOBAL'} is always eligible (every existing source before this
 * phase defaults to {@code GLOBAL} — see V048's own migration comment — so
 * this never narrows what a candidate already saw). A source scoped to a
 * specific region (so far, only the seeded Adzuna/GB row) is eligible only
 * when the candidate's preferences name that region. The candidate's regions
 * come from {@code preference_sets.extra_filters.countries} — reusing the
 * EXISTING, already fully wired {@code extraFilters} jsonb bag
 * ({@link PreferenceSetRepository}, {@code PreferenceSetRecord}) rather than
 * adding a new column or a parallel preferences concept — with 'GB' as the
 * honest default (matching {@code PreferencesPage}'s own existing default
 * {@code locationsAllowed: ['UK']}) when the candidate has never set one.
 *
 * <p><b>Quota.</b> One reservation of the {@code discovery_daily} dimension
 * per triggered run (not per source) — this mirrors how {@code
 * application_daily} reserves once per application, not once per pipeline
 * step. A refused reservation runs nothing; it is reported honestly, never
 * silently ignored.
 *
 * <p><b>Partial failure.</b> Every selected source's
 * {@link CompletableFuture} is independently bounded by
 * {@code per-source-timeout-ms} and never lets one source's exception or
 * timeout propagate into the batch — the same {@code orTimeout}/{@code
 * exceptionally} idiom {@link com.personal.jobagent.discovery.DiscoveryOrchestrator}
 * already uses for its own (unrelated) generic-scraper fan-out.
 *
 * <p><b>Never leaks a source's identity to the caller.</b> {@link RunSummary}
 * reports only aggregate counts — how many sources were queried, how many
 * succeeded, failed, or are missing configuration, and how many jobs were
 * found/updated in total. The per-source kind, org identifier and raw
 * {@link BoardDiscoveryService.SourceRunReport} never leave this class — the
 * candidate-facing controller that calls this service must stay consistent
 * with Robin's existing "the candidate feed never reveals source/platform"
 * rule ({@code Job}'s own contract, enforced server-side since Phase 8.2.5).
 */
@Service
public class PreferredSourcesDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(PreferredSourcesDiscoveryService.class);
    static final String QUOTA_KEY = "discovery_daily";
    private static final String DEFAULT_REGION = "GB";

    public record SourceSelection(UUID id, String region) {}

    public record RunSummary(boolean quotaExceeded, int sourcesQueried, int sourcesSucceeded, int sourcesFailed,
                             int sourcesRequireConfiguration, int newJobs, int updatedJobs, int totalListed,
                             List<String> notes) {
        public static RunSummary forQuotaExceeded() {
            return new RunSummary(true, 0, 0, 0, 0, 0, 0, 0,
                    List.of("Today's discovery limit has been reached. Try again tomorrow."));
        }

        public static RunSummary forNoSourcesConfigured() {
            return new RunSummary(false, 0, 0, 0, 0, 0, 0, 0,
                    List.of("No discovery sources are configured for your selected region(s) yet."));
        }
    }

    private final JdbcTemplate db;
    private final BoardDiscoveryService boards;
    private final QuotaService quotas;
    private final PreferenceSetRepository preferences;
    private final int maxParallel;
    private final long perSourceTimeoutMs;

    public PreferredSourcesDiscoveryService(JdbcTemplate db, BoardDiscoveryService boards, QuotaService quotas,
                                            PreferenceSetRepository preferences,
                                            @Value("${app.discovery.preferred.max-parallel:6}") int maxParallel,
                                            @Value("${app.discovery.preferred.per-source-timeout-ms:45000}") long perSourceTimeoutMs) {
        this.db = db;
        this.boards = boards;
        this.quotas = quotas;
        this.preferences = preferences;
        this.maxParallel = maxParallel;
        this.perSourceTimeoutMs = perSourceTimeoutMs;
    }

    /** The candidate's configured regions, defaulting to {@value #DEFAULT_REGION} when never set. */
    Set<String> regionsFor(UUID profileId) {
        return preferences.findActiveByProfileId(profileId)
                .map(p -> p.extraFilters().get("countries"))
                .filter(List.class::isInstance)
                .map(value -> {
                    Set<String> out = new java.util.LinkedHashSet<>();
                    for (Object item : (List<?>) value) {
                        if (item != null && !String.valueOf(item).isBlank()) {
                            out.add(String.valueOf(item).strip().toUpperCase(Locale.ROOT));
                        }
                    }
                    return out;
                })
                .filter(set -> !set.isEmpty())
                .orElseGet(() -> Set.of(DEFAULT_REGION));
    }

    List<SourceSelection> selectSources(Set<String> regions) {
        List<Map<String, Object>> rows = db.queryForList(
                "select id, region from job_sources where enabled = true and policy <> 'DISABLED'");
        List<SourceSelection> selected = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String region = String.valueOf(row.get("region"));
            if ("GLOBAL".equalsIgnoreCase(region) || regions.contains(region.toUpperCase(Locale.ROOT))) {
                selected.add(new SourceSelection((UUID) row.get("id"), region));
            }
        }
        return selected;
    }

    public RunSummary runForProfile(UUID profileId) {
        QuotaService.ReservationResult reservation = quotas.tryReserve(profileId, QUOTA_KEY, 1.0);
        if (!reservation.reserved()) {
            log.info("discovery_daily quota exhausted for profile {} ({}/{})", profileId,
                    reservation.used(), reservation.limit());
            return RunSummary.forQuotaExceeded();
        }

        Set<String> regions = regionsFor(profileId);
        List<SourceSelection> selected = selectSources(regions);
        if (selected.isEmpty()) return RunSummary.forNoSourcesConfigured();

        List<BoardDiscoveryService.SourceRunReport> reports = runConcurrently(selected);

        int succeeded = 0, failed = 0, requireConfig = 0, newJobs = 0, updatedJobs = 0, listed = 0;
        for (BoardDiscoveryService.SourceRunReport report : reports) {
            listed += toInt(report.counts().get("listed"));
            newJobs += toInt(report.counts().get("created"));
            updatedJobs += toInt(report.counts().get("updated"));
            if (report.ok()) {
                succeeded++;
            } else if ("CONFIGURATION".equals(report.failureCategory())) {
                requireConfig++;
            } else {
                failed++;
            }
        }
        List<String> notes = new ArrayList<>();
        if (requireConfig > 0) {
            notes.add(requireConfig + " source(s) are not fully configured yet and were skipped.");
        }
        if (failed > 0) {
            notes.add(failed + " source(s) could not be reached this time; Robin will retry on the next run.");
        }
        return new RunSummary(false, selected.size(), succeeded, failed, requireConfig, newJobs, updatedJobs, listed, notes);
    }

    private List<BoardDiscoveryService.SourceRunReport> runConcurrently(List<SourceSelection> selected) {
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, Math.min(maxParallel, selected.size())));
        try {
            List<CompletableFuture<BoardDiscoveryService.SourceRunReport>> futures = selected.stream()
                    .map(source -> CompletableFuture.supplyAsync(
                                    () -> boards.run(source.id(), BoardDiscoveryService.Mode.MANUAL), executor)
                            .orTimeout(perSourceTimeoutMs, TimeUnit.MILLISECONDS)
                            .exceptionally(ex -> timedOutOrFailed(source, ex)))
                    .toList();
            return futures.stream().map(CompletableFuture::join).toList();
        } finally {
            executor.shutdown();
        }
    }

    private static BoardDiscoveryService.SourceRunReport timedOutOrFailed(SourceSelection source, Throwable ex) {
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String key : List.of("listed", "created", "updated", "unchanged", "duplicates")) counts.put(key, 0);
        String category = ex instanceof java.util.concurrent.TimeoutException ? "TIMEOUT" : "NETWORK";
        return new BoardDiscoveryService.SourceRunReport(UUID.randomUUID().toString(), source.id(), "UNKNOWN",
                "failed", false, counts, category, ex.getClass().getSimpleName(), List.of(), List.of(category));
    }

    private static int toInt(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }
}
