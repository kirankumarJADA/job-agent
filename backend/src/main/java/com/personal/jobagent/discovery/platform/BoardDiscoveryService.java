package com.personal.jobagent.discovery.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.discovery.JobDiscoveryService;
import com.personal.jobagent.discovery.platform.adapters.TsentaJobSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Runs one persisted board source end to end. This is the single code path
 * used by the scheduler, {@code POST /discovery/run}, the source health check
 * and the URL import — for every supported platform, Greenhouse and Ashby
 * included.
 *
 * <p>Run contract:
 * <ol>
 *   <li>a disabled source (or policy DISABLED) is refused and its recorded
 *       state is left untouched;</li>
 *   <li>the adapter lists the board; complete listings and partial ones are
 *       both ingested, but ONLY a complete listing may mark this source's
 *       unseen postings as missing — a failed, capped, budget-limited or
 *       aggregator listing never removes anything;</li>
 *   <li>entries the listing already describes fully are ingested; entries
 *       that need a detail page are touched when this source already knows
 *       them, otherwise fetched within the per-run detail budget, and the rest
 *       are deferred to the next run;</li>
 *   <li>a job is marked removed ({@code deleted_at}) only when NO source still
 *       lists it; it is restored automatically if any source lists it again;</li>
 *   <li>the run's counters, duration and failure category are written to
 *       {@code job_sources.last_run}/{@code health}; nothing secret is stored.</li>
 * </ol>
 */
@Service
public class BoardDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(BoardDiscoveryService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public enum Mode { SCHEDULED, MANUAL, HEALTH_CHECK, IMPORT }

    public record SourceRunReport(String correlationId, UUID sourceId, String kind, String status, boolean complete,
                                  Map<String, Object> counts, String failureCategory, String failureDetail,
                                  List<JobDiscoveryService.IngestResult> ingested, List<String> errors) {
        public boolean ok() {
            return failureCategory == null;
        }
    }

    /** A source that must not be fetched (unknown, disabled, not a board platform, unsupported). */
    public static class RunRefused extends RuntimeException {
        private final boolean notFound;

        public RunRefused(String message, boolean notFound) {
            super(message);
            this.notFound = notFound;
        }

        public boolean notFound() {
            return notFound;
        }
    }

    private final JdbcTemplate db;
    private final JobDiscoveryService ingestion;
    private final PlatformRegistry registry;
    private final Function<PlatformKind, SafeHttpClient> httpFactory;
    private final int maxPages;
    private final int maxDetails;
    private final int tsentaMaxPages;
    private final int circuitOpenAfter;

    @org.springframework.beans.factory.annotation.Autowired
    public BoardDiscoveryService(JdbcTemplate db, JobDiscoveryService ingestion, PlatformRegistry registry,
                                 @Value("${app.discovery.max-pages:50}") int maxPages,
                                 @Value("${app.discovery.max-details-per-run:150}") int maxDetails,
                                 @Value("${app.discovery.max-requests-per-run:400}") int maxRequests,
                                 @Value("${app.discovery.tsenta.max-pages:3}") int tsentaMaxPages,
                                 @Value("${app.discovery.tsenta.min-interval-ms:1500}") long tsentaIntervalMs,
                                 @Value("${app.discovery.circuit-open-after-failures:10}") int circuitOpenAfter) {
        this(db, ingestion, registry, kind -> SafeHttpClient.live(kind == PlatformKind.TSENTA
                        ? SafeHttpClient.Settings.defaults().withMinInterval(tsentaIntervalMs)
                            .withMaxRequests(2 + tsentaMaxPages + Math.max(1, registry.adapter(PlatformKind.TSENTA)
                                    .map(a -> ((TsentaJobSource) a).maxDetailsPerRun()).orElse(40)))
                        : SafeHttpClient.Settings.defaults().withMaxRequests(maxRequests)),
                maxPages, maxDetails, tsentaMaxPages, circuitOpenAfter);
    }

    public BoardDiscoveryService(JdbcTemplate db, JobDiscoveryService ingestion, PlatformRegistry registry,
                                 Function<PlatformKind, SafeHttpClient> httpFactory,
                                 int maxPages, int maxDetails, int tsentaMaxPages, int circuitOpenAfter) {
        this.db = db;
        this.ingestion = ingestion;
        this.registry = registry;
        this.httpFactory = httpFactory;
        this.maxPages = maxPages;
        this.maxDetails = maxDetails;
        this.tsentaMaxPages = tsentaMaxPages;
        this.circuitOpenAfter = circuitOpenAfter;
    }

    /** Whether this persisted kind is a board platform with a registered adapter. */
    public boolean isRunnable(String kind) {
        return PlatformKind.lookup(kind).flatMap(registry::adapter).isPresent();
    }

    /** Run a persisted source by id. */
    public SourceRunReport run(UUID sourceId, Mode mode) {
        List<Map<String, Object>> rows = db.queryForList("""
                select s.kind, s.org_identifier, s.enabled, s.policy, c.name as company_name
                from job_sources s left join companies c on c.id = s.company_id
                where s.id = ?
                """, sourceId);
        if (rows.isEmpty()) throw new RunRefused("Discovery source not found", true);
        Map<String, Object> row = rows.get(0);
        if (!Boolean.TRUE.equals(row.get("enabled")) || "DISABLED".equals(row.get("policy"))) {
            throw new RunRefused("Discovery source is disabled", false);
        }
        String kindName = String.valueOf(row.get("kind"));
        PlatformKind kind = PlatformKind.lookup(kindName).orElseThrow(() ->
                new RunRefused(kindName + " is not a board platform; a url is required", false));
        BoardAdapter adapter = registry.adapter(kind).orElseThrow(() -> new RunRefused(
                kind.displayName() + " discovery is " + kind.readiness().name().toLowerCase() + ": " + kind.configHint(), false));
        String org = row.get("org_identifier") == null ? "" : String.valueOf(row.get("org_identifier"));
        String employer = row.get("company_name") == null ? null : String.valueOf(row.get("company_name"));
        return run(sourceId, adapter, org, employer, mode);
    }

    SourceRunReport run(UUID sourceId, BoardAdapter adapter, String orgIdentifier, String employer, Mode mode) {
        String correlationId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        Instant runStart = dbNow();
        PlatformKind kind = adapter.kind();
        Map<String, Object> counts = newCounts();
        List<JobDiscoveryService.IngestResult> ingested = new ArrayList<>();

        BoardAdapter.BoardRef board;
        try {
            board = adapter.parseIdentifier(orgIdentifier);
        } catch (BoardConfigException e) {
            return finish(sourceId, kind, correlationId, started, counts, ingested, false,
                    FailureCategory.CONFIGURATION, e.getMessage(), mode);
        }

        boolean healthCheck = mode == Mode.HEALTH_CHECK;
        int pageCap = kind == PlatformKind.TSENTA ? (healthCheck ? 1 : tsentaMaxPages) : (healthCheck ? 2 : maxPages);
        int detailBudget = kind == PlatformKind.TSENTA
                ? Math.min(healthCheck ? 3 : maxDetails, ((TsentaJobSource) adapter).maxDetailsPerRun())
                : (healthCheck ? Math.min(5, maxDetails) : maxDetails);
        SafeHttpClient http = httpFactory.apply(kind);
        BoardAdapter.CrawlContext context = new BoardAdapter.CrawlContext(http, pageCap, Instant.now(), employer);

        BoardAdapter.ListingResult listing;
        try {
            listing = adapter.list(board, context);
        } catch (RuntimeException e) {
            // An adapter bug must not take the sweep down; it is recorded, never hidden.
            log.warn("Adapter {} failed for source {}: {}", kind, sourceId, e.getClass().getSimpleName());
            listing = BoardAdapter.ListingResult.failed(FailureCategory.MALFORMED_RESPONSE, e.getClass().getSimpleName());
        }
        counts.put("listed", listing.entries().size());
        counts.put("pages", listing.pagesFetched());
        String employerName = Normalize.first(employer, listing.employerName());

        Set<String> known = knownExternalIds(sourceId);
        int detailsUsed = 0;
        boolean detailBudgetGone = false;
        for (BoardAdapter.ListingEntry entry : listing.entries()) {
            try {
                if (!entry.needsDetail()) {
                    ingest(sourceId, kind, entry.job(), employerName, counts, ingested);
                } else if (known.contains(entry.externalId())) {
                    touch(sourceId, entry.externalId());
                    bump(counts, "unchanged");
                } else if (!detailBudgetGone && detailsUsed < detailBudget) {
                    detailsUsed++;
                    NormalizedJob job = adapter.detail(board, entry, context);
                    ingest(sourceId, kind, job, employerName, counts, ingested);
                } else {
                    bump(counts, "deferred");
                }
            } catch (SafeHttpClient.FetchException e) {
                bump(counts, "detail_failures");
                if (e.category() == FailureCategory.BUDGET_EXHAUSTED || e.category() == FailureCategory.RATE_LIMITED
                        || e.category() == FailureCategory.ACCESS_DENIED) {
                    detailBudgetGone = true; // stop asking; the rest wait for the next run
                }
            } catch (Parsing.MalformedException | BoardConfigException | UnsupportedOperationException e) {
                bump(counts, "detail_failures");
            }
        }
        counts.put("requests", http.requestsMade());

        if (listing.complete() && listing.failure() == null) {
            counts.put("removed", markUnlisted(sourceId, runStart));
        }
        return finish(sourceId, kind, correlationId, started, counts, ingested, listing.complete(),
                listing.failure(), listing.failureDetail(), mode);
    }

    private void ingest(UUID sourceId, PlatformKind kind, NormalizedJob job, String employer,
                        Map<String, Object> counts, List<JobDiscoveryService.IngestResult> ingested) {
        if (job == null || job.externalId() == null || job.title() == null) {
            bump(counts, "skipped_invalid");
            return;
        }
        if (Boolean.FALSE.equals(job.active())) {
            closeObservation(sourceId, job.externalId());
            bump(counts, "closed");
            return;
        }
        if (kind.isAggregator() && job.applicationUrl() == null) {
            // Without the employer's own application URL the posting cannot be
            // applied to honestly; it is not stored with an aggregator URL instead.
            bump(counts, "skipped_no_employer_url");
            return;
        }
        JobDiscoveryService.IngestResult result = ingestion.ingestJob(new JobDiscoveryService.IngestJobCommand(
                sourceId, job.externalId(), null, Normalize.first(employer, job.employerName()), job.title(),
                job.locationRaw(), job.city(), job.country(), job.remoteType(), job.employmentType(), null,
                job.salaryMin(), job.salaryMax(), job.salaryCurrency(),
                job.descriptionText() == null ? "" : job.descriptionText(), job.skills(),
                job.applicationUrl(), job.sourceUrl(), job.postedAt(), job.salaryPeriod(), job.requisitionId()));
        ingested.add(result);
        switch (result.action()) {
            case "INSERTED" -> bump(counts, "created");
            case "UPDATED" -> bump(counts, "updated");
            default -> bump(counts, "unchanged");
        }
        if (result.crossSource()) bump(counts, "duplicates");
    }

    private Set<String> knownExternalIds(UUID sourceId) {
        Set<String> known = new HashSet<>();
        for (Map<String, Object> r : db.queryForList("""
                select external_job_id from job_source_observations
                where source_id = ? and source_status = 'SEEN'
                """, sourceId)) {
            Object id = r.get("external_job_id");
            if (id != null) known.add(String.valueOf(id));
        }
        return known;
    }

    private void touch(UUID sourceId, String externalId) {
        db.update("update job_source_observations set last_seen_at = now() where source_id = ? and external_job_id = ?",
                sourceId, externalId);
        db.update("""
                update jobs set last_seen_at = now(), deleted_at = null
                where id in (select job_id from job_source_observations where source_id = ? and external_job_id = ?)
                """, sourceId, externalId);
    }

    /** The source itself says the posting is closed. */
    private void closeObservation(UUID sourceId, String externalId) {
        db.update("""
                update job_source_observations set source_status = 'CLOSED', removed_at = now()
                where source_id = ? and external_job_id = ? and source_status <> 'CLOSED'
                """, sourceId, externalId);
        db.update("""
                update jobs j set deleted_at = now()
                where j.deleted_at is null
                  and j.id in (select job_id from job_source_observations where source_id = ? and external_job_id = ?)
                  and not exists (select 1 from job_source_observations o
                                  where o.job_id = j.id and o.source_id is not null and o.source_status = 'SEEN')
                """, sourceId, externalId);
    }

    /**
     * After a COMPLETE listing: this source's postings not seen since the run
     * started are no longer listed by it. A job is removed only when no source
     * still lists it.
     */
    private int markUnlisted(UUID sourceId, Instant runStart) {
        java.sql.Timestamp since = java.sql.Timestamp.from(runStart);
        db.update("""
                update job_source_observations set source_status = 'MISSING', removed_at = now()
                where source_id = ? and source_status = 'SEEN' and last_seen_at < ?
                """, sourceId, since);
        return db.update("""
                update jobs j set deleted_at = now()
                where j.deleted_at is null
                  and exists (select 1 from job_source_observations o
                              where o.job_id = j.id and o.source_id = ? and o.source_status in ('MISSING','CLOSED'))
                  and not exists (select 1 from job_source_observations o
                                  where o.job_id = j.id and o.source_id is not null and o.source_status = 'SEEN')
                """, sourceId);
    }

    private Instant dbNow() {
        try {
            java.sql.Timestamp ts = db.queryForObject("select clock_timestamp()", java.sql.Timestamp.class);
            return ts == null ? Instant.now() : ts.toInstant();
        } catch (RuntimeException e) {
            return Instant.now();
        }
    }

    private SourceRunReport finish(UUID sourceId, PlatformKind kind, String correlationId, long startedNanos,
                                   Map<String, Object> counts, List<JobDiscoveryService.IngestResult> ingested,
                                   boolean complete, FailureCategory failure, String detail, Mode mode) {
        long durationMs = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
        counts.put("duration_ms", durationMs);
        counts.put("complete", complete);
        boolean ok = failure == null;
        String status = !ok ? (ingested.isEmpty() ? "failed" : "degraded") : "ok";
        Map<String, Object> lastRun = new LinkedHashMap<>(counts);
        lastRun.put("mode", mode.name());
        lastRun.put("status", status);
        if (failure != null) lastRun.put("failure_category", failure.name());
        if (detail != null) lastRun.put("detail", truncate(detail));
        String lastRunJson = toJson(lastRun);
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", status);
        health.put("checked_at", Instant.now().toString());
        health.put("detail", summary(counts));
        if (failure != null) health.put("error", failure.name() + (detail == null ? "" : ": " + truncate(detail)));
        db.update(ok
                        ? """
                          update job_sources set last_run_at = now(), last_attempted_at = now(), last_success_at = now(),
                              failure_streak = 0, last_run = ?::jsonb,
                              health = ?::jsonb || jsonb_build_object('failure_streak', 0, 'circuit', 'closed')
                          where id = ?
                          """
                        : """
                          update job_sources set last_run_at = now(), last_attempted_at = now(),
                              failure_streak = failure_streak + 1, last_run = ?::jsonb,
                              health = ?::jsonb || jsonb_build_object('failure_streak', failure_streak + 1,
                                  'circuit', case when failure_streak + 1 >= %d then 'open' else 'closed' end)
                          where id = ?
                          """.formatted(circuitOpenAfter),
                lastRunJson, toJson(health), sourceId);
        List<String> errors = failure == null ? List.of() : List.of(kind.name().toLowerCase() + ":" + failure.name());
        return new SourceRunReport(correlationId, sourceId, kind.name(), status, complete, counts,
                failure == null ? null : failure.name(), detail == null ? null : truncate(detail), ingested, errors);
    }

    private static Map<String, Object> newCounts() {
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String key : List.of("listed", "created", "updated", "unchanged", "duplicates", "deferred", "closed",
                "removed", "skipped_no_employer_url", "skipped_invalid", "detail_failures", "pages", "requests")) {
            counts.put(key, 0);
        }
        return counts;
    }

    private static void bump(Map<String, Object> counts, String key) {
        counts.merge(key, 1, (a, b) -> ((Integer) a) + ((Integer) b));
    }

    private static String summary(Map<String, Object> c) {
        return c.get("listed") + " listed, " + c.get("created") + " new, " + c.get("updated") + " updated, "
                + c.get("unchanged") + " unchanged, " + c.get("duplicates") + " cross-source duplicates"
                + (Integer.valueOf(0).equals(c.get("deferred")) ? "" : ", " + c.get("deferred") + " deferred");
    }

    private static String truncate(String value) {
        return value.length() > 300 ? value.substring(0, 300) : value;
    }

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{}";
        }
    }
}
