package com.personal.jobagent.discovery.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.discovery.JobDiscoveryService;
import com.personal.jobagent.discovery.plan.ExecutionFencedException;
import com.personal.jobagent.discovery.plan.SearchPlanRepository;
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
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.personal.jobagent.discovery.DiscoveryMetrics metrics;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SearchPlanRepository searchPlanRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    private final java.util.concurrent.ConcurrentHashMap<String, Integer> scanOffsets = new java.util.concurrent.ConcurrentHashMap<>();

    public int getPersistedScanOffset(UUID sourceId, UUID searchPlanId) {
        if (sourceId == null) {
            throw new IllegalArgumentException("sourceId must not be null when reading persisted scan offset");
        }
        String memoryKey = (searchPlanId != null) ? searchPlanId + ":" + sourceId : sourceId.toString();
        if (db == null) {
            return scanOffsets.getOrDefault(memoryKey, 0);
        }

        List<Map<String, Object>> rows;
        try {
            if (searchPlanId != null) {
                String planIdStr = searchPlanId.toString();
                String legacyKey = "scan_offset_" + searchPlanId;
                rows = db.queryForList("""
                        select
                            last_run->'plan_offsets'->>? as plan_offset,
                            last_run->>? as legacy_plan_offset
                        from job_sources where id = ?
                        """, planIdStr, legacyKey, sourceId);
            } else {
                rows = db.queryForList(
                        "select last_run->>'scan_offset' as default_offset from job_sources where id = ?",
                        sourceId);
            }
        } catch (Exception e) {
            log.error("Failed to query persisted scan offset for source {} (searchPlan: {}): {}",
                    sourceId, searchPlanId, e.getMessage());
            throw new IllegalStateException("Failed to query persisted scan offset for source " + sourceId
                    + " (database query failure; failing closed): " + e.getMessage(), e);
        }

        if (rows.isEmpty()) {
            throw new IllegalStateException("Job source " + sourceId
                    + " does not exist in database; cannot determine authoritative scan offset (failing closed)");
        }

        Map<String, Object> row = rows.get(0);
        Object val;
        if (searchPlanId != null) {
            val = row.get("plan_offset");
            if (val == null) {
                val = row.get("legacy_plan_offset");
            }
        } else {
            val = row.get("default_offset");
        }

        if (val != null) {
            try {
                int offset = Integer.parseInt(String.valueOf(val));
                if (offset < 0) {
                    throw new IllegalStateException("Negative persisted scan offset for source " + sourceId
                            + ": " + offset + " is invalid (failing closed)");
                }
                scanOffsets.put(memoryKey, offset);
                return offset;
            } catch (NumberFormatException e) {
                throw new IllegalStateException("Malformed persisted scan offset for source " + sourceId
                        + ": '" + val + "' is not a valid integer (failing closed)", e);
            }
        }

        // Source row exists, but no offset recorded for this plan: authoritatively start at 0
        scanOffsets.put(memoryKey, 0);
        return 0;
    }

    public void setSearchPlanRepository(SearchPlanRepository searchPlanRepository) {
        this.searchPlanRepository = searchPlanRepository;
    }

    public void setTransactionTemplate(org.springframework.transaction.support.TransactionTemplate transactionTemplate) {
        this.transactionTemplate = transactionTemplate;
    }

    public void setTransactionManager(org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.transactionTemplate = transactionManager != null ? new org.springframework.transaction.support.TransactionTemplate(transactionManager) : null;
    }

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

    /** Run a persisted source by id with default/empty query params. */
    public SourceRunReport run(UUID sourceId, Mode mode) {
        return run(sourceId, mode, Map.of());
    }

    /** Run a persisted source by id with targeted query params. */
    public SourceRunReport run(UUID sourceId, Mode mode, Map<String, String> queryParams) {
        return run(sourceId, mode, queryParams, null, null);
    }

    /** Run a persisted source by id with targeted query params and search plan lease fencing. */
    public SourceRunReport run(UUID sourceId, Mode mode, Map<String, String> queryParams, UUID searchPlanId, UUID executionOwnerToken) {
        return run(sourceId, mode, queryParams, searchPlanId, executionOwnerToken, null, null);
    }

    /** Run a persisted source by id with targeted query params, search plan lease fencing, max results budget and timeout. */
    public SourceRunReport run(UUID sourceId, Mode mode, Map<String, String> queryParams, UUID searchPlanId, UUID executionOwnerToken,
                               Integer maxResults, Long timeoutMs) {
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
        return run(sourceId, adapter, org, employer, mode, queryParams, searchPlanId, executionOwnerToken, maxResults, timeoutMs);
    }

    SourceRunReport run(UUID sourceId, BoardAdapter adapter, String orgIdentifier, String employer, Mode mode) {
        return run(sourceId, adapter, orgIdentifier, employer, mode, Map.of(), null, null, null, null);
    }

    SourceRunReport run(UUID sourceId, BoardAdapter adapter, String orgIdentifier, String employer, Mode mode, Map<String, String> queryParams) {
        return run(sourceId, adapter, orgIdentifier, employer, mode, queryParams, null, null, null, null);
    }

    SourceRunReport run(UUID sourceId, BoardAdapter adapter, String orgIdentifier, String employer, Mode mode,
                        Map<String, String> queryParams, UUID searchPlanId, UUID executionOwnerToken) {
        return run(sourceId, adapter, orgIdentifier, employer, mode, queryParams, searchPlanId, executionOwnerToken, null, null);
    }

    public SourceRunReport run(UUID sourceId, BoardAdapter adapter, String orgIdentifier, String employer, Mode mode,
                               Map<String, String> queryParams, UUID searchPlanId, UUID executionOwnerToken,
                               Integer maxResults, Long timeoutMs) {
        String correlationId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        Instant runStart = dbNow();
        PlatformKind kind = adapter.kind();
        Map<String, Object> counts = newCounts();
        List<JobDiscoveryService.IngestResult> ingested = new ArrayList<>();

        BoardAdapter.BoardRef board;
        try {
            board = adapter.parseIdentifier(orgIdentifier);
            if (queryParams != null && !queryParams.isEmpty()) {
                Map<String, String> merged = new LinkedHashMap<>(board.params() != null ? board.params() : Map.of());
                merged.putAll(queryParams);
                board = new BoardAdapter.BoardRef(board.kind(), board.identifier(), merged);
            }
        } catch (BoardConfigException e) {
            return finish(sourceId, kind, correlationId, started, counts, ingested, false,
                    FailureCategory.CONFIGURATION, e.getMessage(), mode, searchPlanId);
        }

        long deadlineNanos = (timeoutMs != null && timeoutMs > 0)
                ? started + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs)
                : Long.MAX_VALUE;

        boolean healthCheck = mode == Mode.HEALTH_CHECK;
        int pageCap = kind == PlatformKind.TSENTA ? (healthCheck ? 1 : tsentaMaxPages) : (healthCheck ? 2 : maxPages);
        int detailBudget = kind == PlatformKind.TSENTA
                ? Math.min(healthCheck ? 3 : maxDetails, ((TsentaJobSource) adapter).maxDetailsPerRun())
                : (healthCheck ? Math.min(5, maxDetails) : maxDetails);
        SafeHttpClient http = httpFactory.apply(kind);
        if (timeoutMs != null && timeoutMs > 0) {
            http = http.withDeadline(deadlineNanos);
        }
        BoardAdapter.CrawlContext context = new BoardAdapter.CrawlContext(http, pageCap, Instant.now(), employer, deadlineNanos);

        // Pre-request deadline check
        if (System.nanoTime() >= deadlineNanos) {
            return finish(sourceId, kind, correlationId, started, counts, ingested, false,
                    FailureCategory.TIMEOUT, "Execution timed out before listing request (" + timeoutMs + "ms)", mode, searchPlanId);
        }

        BoardAdapter.ListingResult listing;
        try {
            listing = adapter.list(board, context);
        } catch (SafeHttpClient.FetchException e) {
            log.warn("Adapter {} fetch failed for source {}: category={} message={}", kind, sourceId, e.category(), e.getMessage());
            listing = BoardAdapter.ListingResult.failed(e.category(), e.getMessage());
        } catch (RuntimeException e) {
            // An adapter bug must not take the sweep down; it is recorded, never hidden.
            log.warn("Adapter {} failed for source {}: {}", kind, sourceId, e.getClass().getSimpleName());
            listing = BoardAdapter.ListingResult.failed(FailureCategory.MALFORMED_RESPONSE, e.getClass().getSimpleName());
        }
        counts.put("listed", listing.entries().size());
        counts.put("pages", listing.pagesFetched());
        String employerName = Normalize.first(employer, listing.employerName());

        // Post-listing deadline check
        if (System.nanoTime() >= deadlineNanos || listing.failure() == FailureCategory.TIMEOUT) {
            log.warn("Search plan {} source {} exceeded timeout ({}ms) during listing request",
                    searchPlanId, sourceId, timeoutMs);
            return finish(sourceId, kind, correlationId, started, counts, ingested, false,
                    FailureCategory.TIMEOUT, "Source execution exceeded timeout (" + timeoutMs + "ms)", mode, searchPlanId);
        }

        // Fail-closed fencing guard: verify lease ownership before performing any ingestion writes
        if (searchPlanId != null || executionOwnerToken != null) {
            if (searchPlanId == null || executionOwnerToken == null) {
                throw new IllegalArgumentException("searchPlanId and executionOwnerToken must both be non-null when running with search plan fencing");
            }
            if (searchPlanRepository == null) {
                throw new IllegalStateException("SearchPlanRepository is required for search plan lease fencing but is null (failing closed)");
            }
            if (!searchPlanRepository.isLeaseOwner(searchPlanId, executionOwnerToken)) {
                log.warn("Search plan {} lost lease before ingestion; discarding {} entries", searchPlanId, listing.entries().size());
                throw new ExecutionFencedException("Search plan execution lease lost or expired before ingestion for plan " + searchPlanId);
            }
        }

        boolean authoritative = !kind.isAggregator();
        Set<String> known = knownExternalIds(sourceId);
        int detailsUsed = 0;
        boolean detailBudgetGone = false;
        boolean truncatedByBudget = false;
        boolean truncatedByTimeout = false;

        int maxEntriesToProcess = (maxResults != null && maxResults > 0) ? Math.max(maxResults * 20, 2000) : 5000;
        int entriesProcessed = 0;
        int resultCount = 0;

        int totalEntries = listing.entries().size();
        int startOffset = getPersistedScanOffset(sourceId, searchPlanId);
        int offset = (totalEntries > 0) ? (startOffset % totalEntries) : 0;

        for (int i = 0; i < totalEntries; i++) {
            if (maxResults != null && maxResults > 0 && resultCount >= maxResults) {
                truncatedByBudget = true;
                log.info("Search plan {} source {} reached max results budget ({} new/updated jobs); ceasing further ingestion",
                        searchPlanId, sourceId, maxResults);
                break;
            }
            if (entriesProcessed >= maxEntriesToProcess) {
                truncatedByBudget = true;
                log.info("Search plan {} source {} reached max entry inspection cap ({}); halting",
                        searchPlanId, sourceId, maxEntriesToProcess);
                break;
            }
            if (System.nanoTime() >= deadlineNanos) {
                truncatedByTimeout = true;
                log.warn("Search plan {} source {} exceeded timeout ({}ms); ceasing further ingestion",
                        searchPlanId, sourceId, timeoutMs);
                break;
            }

            int entryIndex = (offset + i) % totalEntries;
            BoardAdapter.ListingEntry entry = listing.entries().get(entryIndex);
            entriesProcessed++;

            try {
                if (!entry.needsDetail()) {
                    if (System.nanoTime() >= deadlineNanos) {
                        truncatedByTimeout = true;
                        break;
                    }
                    JobDiscoveryService.IngestResult res = ingest(sourceId, kind, entry.job(), employerName, counts, ingested, searchPlanId, executionOwnerToken);
                    if (res != null && ("INSERTED".equals(res.action()) || "UPDATED".equals(res.action()))) {
                        resultCount++;
                    }
                } else if (known.contains(entry.externalId())) {
                    if (System.nanoTime() >= deadlineNanos) {
                        truncatedByTimeout = true;
                        break;
                    }
                    touch(sourceId, entry.externalId(), authoritative, searchPlanId, executionOwnerToken);
                    bump(counts, "unchanged");
                } else if (!detailBudgetGone && detailsUsed < detailBudget) {
                    if (System.nanoTime() >= deadlineNanos) {
                        truncatedByTimeout = true;
                        break;
                    }
                    detailsUsed++;
                    NormalizedJob job = adapter.detail(board, entry, context);
                    if (System.nanoTime() >= deadlineNanos) {
                        truncatedByTimeout = true;
                        break;
                    }
                    JobDiscoveryService.IngestResult res = ingest(sourceId, kind, job, employerName, counts, ingested, searchPlanId, executionOwnerToken);
                    if (res != null && ("INSERTED".equals(res.action()) || "UPDATED".equals(res.action()))) {
                        resultCount++;
                    }
                } else {
                    bump(counts, "deferred");
                }
            } catch (ExecutionFencedException e) {
                log.warn("Search plan {} execution fenced out during ingestion: {}", searchPlanId, e.getMessage());
                throw e;
            } catch (SafeHttpClient.FetchException e) {
                bump(counts, "detail_failures");
                if (e.category() == FailureCategory.TIMEOUT) {
                    truncatedByTimeout = true;
                    break;
                }
                if (e.category() == FailureCategory.BUDGET_EXHAUSTED || e.category() == FailureCategory.RATE_LIMITED
                        || e.category() == FailureCategory.ACCESS_DENIED) {
                    detailBudgetGone = true; // stop asking; the rest wait for the next run
                }
            } catch (Parsing.MalformedException | BoardConfigException | UnsupportedOperationException e) {
                bump(counts, "detail_failures");
            }
        }
        counts.put("requests", http.requestsMade());
        counts.put("result_count", resultCount);

        int nextOffset = (totalEntries > 0) ? (offset + entriesProcessed) % totalEntries : 0;
        counts.put("scan_offset", nextOffset);
        if (searchPlanId != null) {
            counts.put("scan_offset_" + searchPlanId, nextOffset);
        }

        // Safe deletion guard:
        // A listing truncated by maxResults or timeout must NEVER remove or unlist active jobs.
        // Removal requires a complete, non-failed listing that was NOT truncated and actually returned entries.
        boolean isDeadlineExceeded = System.nanoTime() >= deadlineNanos;
        if (!truncatedByBudget && !truncatedByTimeout && !isDeadlineExceeded
                && listing.complete() && listing.failure() == null && !listing.entries().isEmpty()) {
            counts.put("removed", markUnlisted(sourceId, runStart, authoritative, searchPlanId, executionOwnerToken));
        } else if (truncatedByBudget || truncatedByTimeout || isDeadlineExceeded) {
            log.info("Listing was truncated (budget={}, timeout={}, deadlineExceeded={}); skipping markUnlisted to protect active jobs for source {}",
                    truncatedByBudget, truncatedByTimeout, isDeadlineExceeded, sourceId);
        } else if (listing.complete() && listing.failure() == null && listing.entries().isEmpty()) {
            log.warn("Safe deletion guard activated: listing completed with 0 jobs for source {} in {} mode; skipping markUnlisted",
                    sourceId, mode);
        }

        boolean isComplete = listing.complete() && !truncatedByBudget && !truncatedByTimeout && !isDeadlineExceeded;
        FailureCategory failureCat = listing.failure();
        String failureDetail = listing.failureDetail();
        if ((truncatedByTimeout || isDeadlineExceeded) && failureCat == null) {
            failureCat = FailureCategory.TIMEOUT;
            failureDetail = "Source execution exceeded timeout (" + timeoutMs + "ms)";
        }

        return finish(sourceId, kind, correlationId, started, counts, ingested, isComplete,
                failureCat, failureDetail, mode, searchPlanId);
    }

    private JobDiscoveryService.IngestResult ingest(UUID sourceId, PlatformKind kind, NormalizedJob job, String employer,
                                                    Map<String, Object> counts, List<JobDiscoveryService.IngestResult> ingested) {
        return ingest(sourceId, kind, job, employer, counts, ingested, null, null);
    }

    private JobDiscoveryService.IngestResult ingest(UUID sourceId, PlatformKind kind, NormalizedJob job, String employer,
                                                    Map<String, Object> counts, List<JobDiscoveryService.IngestResult> ingested,
                                                    UUID searchPlanId, UUID executionOwnerToken) {
        if (job == null || job.externalId() == null || job.title() == null) {
            bump(counts, "skipped_invalid");
            return null;
        }
        if (Boolean.FALSE.equals(job.active())) {
            closeObservation(sourceId, job.externalId(), !kind.isAggregator(), searchPlanId, executionOwnerToken);
            bump(counts, "closed");
            return null;
        }
        if (kind.isAggregator() && job.applicationUrl() == null) {
            // Without the employer's own application URL the posting cannot be
            // applied to honestly; it is not stored with an aggregator URL instead.
            bump(counts, "skipped_no_employer_url");
            return null;
        }
        JobDiscoveryService.IngestJobCommand cmd = new JobDiscoveryService.IngestJobCommand(
                sourceId, job.externalId(), null, Normalize.first(employer, job.employerName()), job.title(),
                job.locationRaw(), job.city(), job.country(), job.remoteType(), job.employmentType(), null,
                job.salaryMin(), job.salaryMax(), job.salaryCurrency(),
                job.descriptionText() == null ? "" : job.descriptionText(), job.skills(),
                job.applicationUrl(), job.sourceUrl(), job.postedAt(), job.salaryPeriod(), job.requisitionId());
        JobDiscoveryService.IngestResult result = (searchPlanId != null && executionOwnerToken != null)
                ? ingestion.ingestJob(cmd, searchPlanId, executionOwnerToken)
                : ingestion.ingestJob(cmd);
        if (result == null) {
            return null;
        }
        ingested.add(result);
        switch (result.action()) {
            case "INSERTED" -> bump(counts, "created");
            case "UPDATED" -> bump(counts, "updated");
            default -> bump(counts, "unchanged");
        }
        if (result.crossSource()) bump(counts, "duplicates");
        if (metrics != null) {
            metrics.recordJobIngestion(kind.name(), result.action(), result.crossSource());
        }
        return result;
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

    private void touch(UUID sourceId, String externalId, boolean authoritative) {
        touch(sourceId, externalId, authoritative, null, null);
    }

    private void touch(UUID sourceId, String externalId, boolean authoritative, UUID searchPlanId, UUID executionOwnerToken) {
        Runnable block = () -> {
            if (searchPlanId != null || executionOwnerToken != null) {
                if (searchPlanId == null || executionOwnerToken == null) {
                    throw new IllegalArgumentException("searchPlanId and executionOwnerToken must both be non-null when running with search plan fencing");
                }
                if (searchPlanRepository == null) {
                    throw new IllegalStateException("SearchPlanRepository is required for search plan lease fencing but is null (failing closed)");
                }
                searchPlanRepository.assertLeaseOwnership(searchPlanId, executionOwnerToken);
            }
            db.update("update job_source_observations set last_seen_at = now() where source_id = ? and external_job_id = ?",
                    sourceId, externalId);
            db.update("""
                    update jobs set last_seen_at = now(),
                        deleted_at = case
                            when ? then null
                            when exists (
                                select 1 from job_source_observations o
                                join job_sources s on s.id = o.source_id
                                where o.job_id = jobs.id
                                  and s.kind not in ('TSENTA', 'FREEHIRE', 'ARBEITNOW', 'ADZUNA')
                                  and o.source_status in ('MISSING', 'CLOSED')
                            ) then deleted_at
                            else null
                        end
                    where id in (select job_id from job_source_observations where source_id = ? and external_job_id = ?)
                    """, authoritative, sourceId, externalId);
        };
        if (transactionTemplate != null) {
            transactionTemplate.executeWithoutResult(status -> block.run());
        } else {
            block.run();
        }
    }

    /** The source itself says the posting is closed. */
    private void closeObservation(UUID sourceId, String externalId, boolean authoritative) {
        closeObservation(sourceId, externalId, authoritative, null, null);
    }

    private void closeObservation(UUID sourceId, String externalId, boolean authoritative, UUID searchPlanId, UUID executionOwnerToken) {
        Runnable block = () -> {
            if (searchPlanId != null || executionOwnerToken != null) {
                if (searchPlanId == null || executionOwnerToken == null) {
                    throw new IllegalArgumentException("searchPlanId and executionOwnerToken must both be non-null when running with search plan fencing");
                }
                if (searchPlanRepository == null) {
                    throw new IllegalStateException("SearchPlanRepository is required for search plan lease fencing but is null (failing closed)");
                }
                searchPlanRepository.assertLeaseOwnership(searchPlanId, executionOwnerToken);
            }
            db.update("""
                    update job_source_observations set source_status = 'CLOSED', removed_at = now()
                    where source_id = ? and external_job_id = ? and source_status <> 'CLOSED'
                    """, sourceId, externalId);
            if (authoritative) {
                db.update("""
                        update jobs j set deleted_at = now()
                        where j.deleted_at is null
                          and j.id in (select job_id from job_source_observations where source_id = ? and external_job_id = ?)
                          and not exists (select 1 from job_source_observations o
                                          join job_sources s on s.id = o.source_id
                                          where o.job_id = j.id
                                            and o.source_status = 'SEEN'
                                            and s.kind not in ('TSENTA', 'FREEHIRE', 'ARBEITNOW', 'ADZUNA'))
                        """, sourceId, externalId);
            } else {
                db.update("""
                        update jobs j set deleted_at = now()
                        where j.deleted_at is null
                          and j.id in (select job_id from job_source_observations where source_id = ? and external_job_id = ?)
                          and not exists (select 1 from job_source_observations o
                                          where o.job_id = j.id and o.source_id is not null and o.source_status = 'SEEN')
                        """, sourceId, externalId);
            }
        };
        if (transactionTemplate != null) {
            transactionTemplate.executeWithoutResult(status -> block.run());
        } else {
            block.run();
        }
    }

    /**
     * After a COMPLETE listing: this source's postings not seen since the run
     * started are no longer listed by it. If this source is authoritative,
     * aggregator observations cannot keep the job alive.
     */
    private int markUnlisted(UUID sourceId, Instant runStart, boolean authoritative) {
        return markUnlisted(sourceId, runStart, authoritative, null, null);
    }

    private int markUnlisted(UUID sourceId, Instant runStart, boolean authoritative, UUID searchPlanId, UUID executionOwnerToken) {
        java.util.function.Supplier<Integer> block = () -> {
            if (searchPlanId != null || executionOwnerToken != null) {
                if (searchPlanId == null || executionOwnerToken == null) {
                    throw new IllegalArgumentException("searchPlanId and executionOwnerToken must both be non-null when running with search plan fencing");
                }
                if (searchPlanRepository == null) {
                    throw new IllegalStateException("SearchPlanRepository is required for search plan lease fencing but is null (failing closed)");
                }
                searchPlanRepository.assertLeaseOwnership(searchPlanId, executionOwnerToken);
            }
            java.sql.Timestamp since = java.sql.Timestamp.from(runStart);
            db.update("""
                    update job_source_observations set source_status = 'MISSING', removed_at = now()
                    where source_id = ? and source_status = 'SEEN' and last_seen_at < ?
                    """, sourceId, since);
            if (authoritative) {
                return db.update("""
                        update jobs j set deleted_at = now()
                        where j.deleted_at is null
                          and exists (select 1 from job_source_observations o
                                      where o.job_id = j.id and o.source_id = ? and o.source_status in ('MISSING','CLOSED'))
                          and not exists (select 1 from job_source_observations o
                                          join job_sources s on s.id = o.source_id
                                          where o.job_id = j.id
                                            and o.source_status = 'SEEN'
                                            and s.kind not in ('TSENTA', 'FREEHIRE', 'ARBEITNOW', 'ADZUNA'))
                        """, sourceId);
            } else {
                return db.update("""
                        update jobs j set deleted_at = now()
                        where j.deleted_at is null
                          and exists (select 1 from job_source_observations o
                                      where o.job_id = j.id and o.source_id = ? and o.source_status in ('MISSING','CLOSED'))
                          and not exists (select 1 from job_source_observations o
                                      where o.job_id = j.id and o.source_id is not null and o.source_status = 'SEEN')
                        """, sourceId);
            }
        };
        if (transactionTemplate != null) {
            Integer res = transactionTemplate.execute(status -> block.get());
            return res != null ? res : 0;
        } else {
            return block.get();
        }
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
        return finish(sourceId, kind, correlationId, startedNanos, counts, ingested, complete, failure, detail, mode, null);
    }

    private SourceRunReport finish(UUID sourceId, PlatformKind kind, String correlationId, long startedNanos,
                                   Map<String, Object> counts, List<JobDiscoveryService.IngestResult> ingested,
                                   boolean complete, FailureCategory failure, String detail, Mode mode,
                                   UUID searchPlanId) {
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

        Runnable persistAction = () -> {
            if (db == null) {
                return;
            }
            if (ok) {
                db.update("""
                        update job_sources set last_run_at = now(), last_attempted_at = now(), last_success_at = now(),
                            failure_streak = 0,
                            last_run = ?::jsonb || jsonb_build_object('plan_offsets', coalesce(last_run->'plan_offsets', '{}'::jsonb)),
                            health = ?::jsonb || jsonb_build_object('failure_streak', 0, 'circuit', 'closed'),
                            health_status = 'HEALTHY', consecutive_failures = 0, last_successful_run_at = now(), error_summary = null
                        where id = ?
                        """, lastRunJson, toJson(health), sourceId);
            } else {
                String errSummary = failure.name() + (detail == null ? "" : ": " + truncate(detail));
                db.update("""
                        update job_sources set last_run_at = now(), last_attempted_at = now(),
                            failure_streak = failure_streak + 1,
                            last_run = ?::jsonb || jsonb_build_object('plan_offsets', coalesce(last_run->'plan_offsets', '{}'::jsonb)),
                            health = ?::jsonb || jsonb_build_object('failure_streak', failure_streak + 1,
                                'circuit', case when failure_streak + 1 >= %d then 'open' else 'closed' end),
                            health_status = case when failure_streak + 1 >= %d then 'UNHEALTHY' else 'DEGRADED' end,
                            consecutive_failures = consecutive_failures + 1,
                            error_summary = ?
                        where id = ?
                        """.formatted(circuitOpenAfter, circuitOpenAfter),
                        lastRunJson, toJson(health), errSummary, sourceId);
            }

            if (searchPlanId != null && counts.containsKey("scan_offset")) {
                db.update("""
                        update job_sources set
                            last_run = jsonb_set(
                                coalesce(last_run, '{}'::jsonb),
                                '{plan_offsets}',
                                coalesce(last_run->'plan_offsets', '{}'::jsonb) || jsonb_build_object(?::text, ?::int)
                            )
                        where id = ?
                        """, searchPlanId.toString(), counts.get("scan_offset"), sourceId);
            }
        };

        if (transactionTemplate != null) {
            transactionTemplate.executeWithoutResult(txStatus -> persistAction.run());
        } else {
            persistAction.run();
        }

        // Write newly calculated offset to local cache only after successful persistence
        if (counts.containsKey("scan_offset")) {
            String memoryKey = (searchPlanId != null) ? searchPlanId + ":" + sourceId : sourceId.toString();
            scanOffsets.put(memoryKey, (Integer) counts.get("scan_offset"));
        }

        // V055 run history
        long startedEpochMs = System.currentTimeMillis() - durationMs;
        UUID runId = com.personal.jobagent.common.UuidV7.generate();
        try {
            db.update("""
                    insert into discovery_runs (id, source_id, kind, mode, started_at, finished_at, duration_ms,
                                                status, failure_category, detail, counters, complete)
                    values (?, ?, ?, ?, ?::timestamptz, ?::timestamptz, ?, ?, ?, ?, ?::jsonb, ?)
                    """, runId, sourceId, kind.name(), mode.name(),
                    java.sql.Timestamp.from(Instant.ofEpochMilli(startedEpochMs)),
                    java.sql.Timestamp.from(Instant.now()), durationMs, status,
                    failure != null ? failure.name() : null, detail != null ? truncate(detail) : null,
                    lastRunJson, complete);
        } catch (Exception e) {
            log.debug("Run history persistence skipped: {}", e.getMessage());
        }

        if (metrics != null) {
            metrics.recordRun(kind.name(), mode.name(), status);
        }

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
