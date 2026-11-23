package com.personal.jobagent.discovery.plan;

import com.personal.jobagent.common.MaintenanceModeService;
import com.personal.jobagent.discovery.JobDiscoveryService;
import com.personal.jobagent.discovery.platform.BoardDiscoveryService;
import com.personal.jobagent.jobs.EffectiveMatchingCriteria;
import com.personal.jobagent.jobs.JobMatchService;
import com.personal.jobagent.jobs.JobRecord;
import com.personal.jobagent.jobs.JobRepository;
import com.personal.jobagent.preferences.PreferenceSetRecord;
import com.personal.jobagent.preferences.PreferenceSetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Phase 2B Hardened: Search Plan Execution Coordinator with Lease Renewal and Side-Effect Fencing.
 * <p>
 * Orchestrates candidate-scoped discovery search plans:
 * 1. Expands plan intents into concrete facets via {@link SearchPlanQueryExpander}.
 * 2. Enforces mutual exclusion via database-backed execution leases with owner tokens and fencing protection.
 * 3. Periodically renews execution lease via background heartbeat, terminating processing if ownership is lost.
 * 4. Routes query parameters to query-capable providers (Adzuna) and harvests company boards (Greenhouse/Lever).
 * 5. Executes provider discovery bounded by per-plan result limits ({@code maxResultsPerRun}).
 * 6. Atomically fences all side-effects (jobs ingestion, job_matches persistence, outbox notification emissions).
 * 7. Handles provider failures, timeouts, and rate limits gracefully with fail-closed semantics.
 * 8. Triggers immediate {@link JobMatchService} evaluation and authoritative multi-stage reranking on discovered jobs.
 * 9. Records execution outcome, errors, and metrics in {@code search_plans} with fencing protection.
 */
@Service
public class SearchPlanExecutionCoordinator implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SearchPlanExecutionCoordinator.class);

    public record ExecutionSummary(
            UUID searchPlanId,
            String planName,
            String status, // SUCCESS, PARTIAL, FAILED, SKIPPED
            int sourcesQueried,
            int sourcesSucceeded,
            int sourcesFailed,
            int jobsIngested,
            int jobsMatched,
            List<String> errors,
            List<String> notes,
            SearchPlanQueryExpander.ExpandedQueryPlan expandedQuery,
            Map<String, String> providerFacetSupport
    ) {
        public static ExecutionSummary skipped(UUID planId, String planName, String reason) {
            return new ExecutionSummary(
                    planId,
                    planName != null ? planName : "Unknown",
                    "SKIPPED",
                    0, 0, 0, 0, 0,
                    List.of(),
                    List.of(reason),
                    null,
                    Map.of()
            );
        }
    }

    private final SearchPlanRepository repository;
    private final SearchPlanQueryExpander queryExpander;
    private final BoardDiscoveryService boardDiscoveryService;
    private final JobRepository jobRepository;
    private final JobMatchService jobMatchService;
    private final PreferenceSetRepository preferenceSetRepository;
    private final JdbcTemplate jdbcTemplate;
    private final MaintenanceModeService maintenanceMode;
    private final long perSourceTimeoutMs;
    private final long defaultLeaseDurationMs;
    private final long defaultHeartbeatIntervalMs;
    private final ScheduledExecutorService heartbeatExecutor;

    @Autowired
    public SearchPlanExecutionCoordinator(SearchPlanRepository repository,
                                          SearchPlanQueryExpander queryExpander,
                                          BoardDiscoveryService boardDiscoveryService,
                                          JobRepository jobRepository,
                                          JobMatchService jobMatchService,
                                          PreferenceSetRepository preferenceSetRepository,
                                          JdbcTemplate jdbcTemplate,
                                          @Value("${app.discovery.search-plan.per-source-timeout-ms:45000}") long perSourceTimeoutMs,
                                          @Value("${app.discovery.search-plan.lease-duration-ms:60000}") long defaultLeaseDurationMs,
                                          @Value("${app.discovery.search-plan.heartbeat-interval-ms:15000}") long defaultHeartbeatIntervalMs,
                                          @Value("${app.discovery.search-plan.heartbeat-threads:4}") int heartbeatThreads,
                                          MaintenanceModeService maintenanceMode) {
        this.repository = repository;
        this.queryExpander = queryExpander;
        this.boardDiscoveryService = boardDiscoveryService;
        this.jobRepository = jobRepository;
        this.jobMatchService = jobMatchService;
        this.preferenceSetRepository = preferenceSetRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.perSourceTimeoutMs = perSourceTimeoutMs > 0 ? perSourceTimeoutMs : 45000L;
        this.defaultLeaseDurationMs = defaultLeaseDurationMs > 0 ? defaultLeaseDurationMs : 60000L;
        this.defaultHeartbeatIntervalMs = defaultHeartbeatIntervalMs > 0 ? defaultHeartbeatIntervalMs : 15000L;
        this.maintenanceMode = maintenanceMode != null ? maintenanceMode : new MaintenanceModeService(false);
        int poolSize = Math.max(2, Math.min(50, heartbeatThreads));
        this.heartbeatExecutor = Executors.newScheduledThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "search-plan-heartbeat");
            t.setDaemon(true);
            return t;
        });
    }

    public SearchPlanExecutionCoordinator(SearchPlanRepository repository,
                                          SearchPlanQueryExpander queryExpander,
                                          BoardDiscoveryService boardDiscoveryService,
                                          JobRepository jobRepository,
                                          JobMatchService jobMatchService,
                                          PreferenceSetRepository preferenceSetRepository,
                                          JdbcTemplate jdbcTemplate,
                                          long perSourceTimeoutMs,
                                          MaintenanceModeService maintenanceMode) {
        this(repository, queryExpander, boardDiscoveryService, jobRepository,
                jobMatchService, preferenceSetRepository, jdbcTemplate, perSourceTimeoutMs,
                60000L, 15000L, 4, maintenanceMode);
    }

    @Override
    public void destroy() {
        heartbeatExecutor.shutdownNow();
    }

    /**
     * Executes a search plan by ID and profile ID using database-backed execution leases.
     *
     * @param planId search plan identifier
     * @param profileId candidate profile identifier
     * @param manual true if triggered on-demand by user
     * @return ExecutionSummary describing the run outcome
     */
    public ExecutionSummary executePlan(UUID planId, UUID profileId, boolean manual) {
        return executePlan(planId, profileId, manual, defaultLeaseDurationMs, defaultHeartbeatIntervalMs);
    }

    /**
     * Executes a search plan with custom lease timing (primarily for testing and specialized workloads).
     */
    public ExecutionSummary executePlan(UUID planId, UUID profileId, boolean manual,
                                        long leaseDurationMs, long heartbeatIntervalMs) {
        if (planId == null || profileId == null) {
            throw new IllegalArgumentException("planId and profileId cannot be null");
        }

        if (maintenanceMode.isMaintenanceMode()) {
            log.warn("Search plan execution paused: maintenance mode is active");
            return ExecutionSummary.skipped(planId, null, "Execution paused: system maintenance mode active");
        }

        Optional<SearchPlanRecord> optPlan = repository.findByIdAndProfileId(planId, profileId);
        if (optPlan.isEmpty()) {
            return ExecutionSummary.skipped(planId, null, "Search plan not found or not owned by profile");
        }

        SearchPlanRecord plan = optPlan.get();
        if (!manual && !plan.active()) {
            return ExecutionSummary.skipped(planId, plan.name(), "Search plan is disabled/inactive");
        }

        // Mutual exclusion: atomically claim database-backed execution lease with owner token and expiry
        UUID ownerToken = UUID.randomUUID();
        long effectiveLeaseDuration = leaseDurationMs > 0 ? leaseDurationMs : 60000L;
        long effectiveHeartbeatInterval = heartbeatIntervalMs > 0 ? heartbeatIntervalMs : 15000L;

        boolean claimed;
        try {
            claimed = repository.claimExecutionLease(planId, ownerToken, effectiveLeaseDuration);
        } catch (Exception e) {
            log.error("Failed to claim execution lease for search plan {} (failing closed): {}", planId, e.getMessage(), e);
            return ExecutionSummary.skipped(planId, plan.name(), "Could not acquire execution lease: " + e.getMessage());
        }

        if (!claimed) {
            log.warn("Search plan {} is already executing with active lease; overlapping execution prevented", planId);
            return ExecutionSummary.skipped(planId, plan.name(), "Another execution is already active for this search plan");
        }

        AtomicBoolean ownershipLost = new AtomicBoolean(false);
        ScheduledFuture<?> heartbeatFuture = null;
        try {
            heartbeatFuture = heartbeatExecutor.scheduleWithFixedDelay(() -> {
                try {
                    boolean renewed = repository.renewExecutionLease(planId, ownerToken, effectiveLeaseDuration);
                    if (!renewed) {
                        log.warn("Search plan {} lease renewal failed for token {} (lease expired or ownership lost)",
                                planId, ownerToken);
                        ownershipLost.set(true);
                    }
                } catch (Exception e) {
                    log.error("Exception during lease renewal for search plan {}: {}", planId, e.getMessage());
                    ownershipLost.set(true);
                }
            }, effectiveHeartbeatInterval, effectiveHeartbeatInterval, TimeUnit.MILLISECONDS);

            return doExecute(plan, ownerToken, ownershipLost);
        } catch (Exception e) {
            log.error("Unhandled execution exception for search plan {}: {}", planId, e.getMessage(), e);
            repository.completeExecution(planId, ownerToken, "FAILED", "Execution exception: " + e.getMessage(), 0);
            return new ExecutionSummary(
                    plan.id(),
                    plan.name(),
                    "FAILED",
                    0, 0, 0, 0, 0,
                    List.of("Execution failure: " + e.getMessage()),
                    List.of("Execution aborted due to unexpected exception"),
                    null,
                    Map.of()
            );
        } finally {
            if (heartbeatFuture != null) {
                heartbeatFuture.cancel(false);
            }
        }
    }

    private ExecutionSummary doExecute(SearchPlanRecord plan, UUID ownerToken, AtomicBoolean ownershipLost) {
        SearchPlanQueryExpander.ExpandedQueryPlan expanded = queryExpander.expand(plan);
        Map<String, String> facetSupport = evaluateFacetSupport(expanded);

        List<SourceCandidate> matchingSources = resolveMatchingSources(expanded.targetSources());
        if (matchingSources.isEmpty()) {
            String note = "No enabled discovery sources matched target sources: " + expanded.targetSources();
            repository.completeExecution(plan.id(), ownerToken, "SUCCESS", null, 0);
            return new ExecutionSummary(
                    plan.id(), plan.name(), "SUCCESS", 0, 0, 0, 0, 0,
                    List.of(), List.of(note), expanded, facetSupport
            );
        }

        int sourcesQueried = 0;
        int sourcesSucceeded = 0;
        int sourcesFailed = 0;
        int totalIngested = 0;
        List<String> errors = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Set<UUID> ingestedJobIds = new LinkedHashSet<>();

        for (SourceCandidate source : matchingSources) {
            if (ownershipLost.get()) {
                notes.add("Aborted provider queries: lease ownership lost or expired");
                break;
            }

            int remainingBudget = plan.maxResultsPerRun() - totalIngested;
            if (remainingBudget <= 0) {
                notes.add("Reached per-plan result cap (" + plan.maxResultsPerRun() + "); ceased further provider queries");
                break;
            }

            sourcesQueried++;

            // Provider Query Routing: Build query params for query-capable providers (Adzuna)
            Map<String, String> queryParams = new LinkedHashMap<>();
            if ("ADZUNA".equalsIgnoreCase(source.kind())) {
                if (!expanded.primaryKeywords().isEmpty()) {
                    queryParams.put("what", String.join(" ", expanded.primaryKeywords()));
                } else if (!expanded.expandedTitles().isEmpty()) {
                    queryParams.put("what", expanded.expandedTitles().get(0));
                }
                if (!expanded.targetLocations().isEmpty()) {
                    queryParams.put("where", expanded.targetLocations().get(0));
                }
            }

            try {
                BoardDiscoveryService.SourceRunReport report = boardDiscoveryService.run(
                        source.id(), BoardDiscoveryService.Mode.SCHEDULED, queryParams, plan.id(), ownerToken,
                        remainingBudget, perSourceTimeoutMs);

                if (ownershipLost.get()) {
                    notes.add("Discarded provider results for source " + source.displayName() + ": lease lost in-flight");
                    break;
                }

                if (report != null && report.ok()) {
                    sourcesSucceeded++;
                    if (report.ingested() != null) {
                        for (JobDiscoveryService.IngestResult res : report.ingested()) {
                            if (totalIngested < plan.maxResultsPerRun()) {
                                totalIngested++;
                                if (res.jobId() != null) {
                                    ingestedJobIds.add(res.jobId());
                                }
                            }
                        }
                    }
                } else if (report != null) {
                    sourcesFailed++;
                    String err = String.format("Source %s (%s) reported failure: %s - %s",
                            source.displayName(), source.kind(), report.failureCategory(), report.failureDetail());
                    errors.add(err);
                } else {
                    sourcesFailed++;
                    String err = String.format("Source %s (%s) returned null report",
                            source.displayName(), source.kind());
                    errors.add(err);
                }
            } catch (ExecutionFencedException e) {
                sourcesFailed++;
                ownershipLost.set(true);
                String err = String.format("Source %s (%s) fenced out: %s",
                        source.displayName(), source.kind(), e.getMessage());
                log.warn("Execution fenced out for search plan {} during source {}: {}", plan.id(), source.displayName(), e.getMessage());
                errors.add(err);
                notes.add("Execution lease lost during provider ingestion for source " + source.displayName());
                break;
            } catch (Exception e) {
                sourcesFailed++;
                String err = String.format("Source %s (%s) threw exception: %s",
                        source.displayName(), source.kind(), e.getMessage());
                log.error("Error executing source {} for plan {}: {}", source.id(), plan.id(), e.getMessage(), e);
                errors.add(err);
            }
        }

        // Trigger matching & authoritative multi-stage reranking on newly ingested jobs
        int jobsMatched = 0;
        PreferenceSetRecord preferences = preferenceSetRepository.findActiveByProfileId(plan.profileId()).orElse(null);
        EffectiveMatchingCriteria criteria = EffectiveMatchingCriteria.create(plan.profileId(), preferences, expanded);
        if (!ingestedJobIds.isEmpty() && !ownershipLost.get()) {
            for (UUID jobId : ingestedJobIds) {
                if (ownershipLost.get()) {
                    notes.add("Aborted match evaluations: lease ownership lost or expired");
                    break;
                }
                try {
                    jobMatchService.evaluateMatch(jobId, plan.profileId(), criteria, plan.id(), ownerToken);
                    jobsMatched++;
                } catch (ExecutionFencedException e) {
                    ownershipLost.set(true);
                    log.warn("Match evaluation fenced out for job {} in search plan {}: {}", jobId, plan.id(), e.getMessage());
                    errors.add("Match evaluation fenced out: " + e.getMessage());
                    notes.add("Execution lease lost during match evaluation for job " + jobId);
                    break;
                } catch (Exception e) {
                    log.warn("Failed match evaluation for job {} during plan execution: {}", jobId, e.getMessage());
                }
            }
        }

        if (ownershipLost.get()) {
            log.warn("Search plan {} execution completed by token {} but worker lost ownership; marking FAILED and discarding success",
                    plan.id(), ownerToken);
            errors.add("Execution lease lost or expired during processing");
            repository.completeExecution(plan.id(), ownerToken, "FAILED", "Execution lease lost or expired", totalIngested);
            return new ExecutionSummary(
                    plan.id(),
                    plan.name(),
                    "FAILED",
                    sourcesQueried,
                    sourcesSucceeded,
                    sourcesFailed,
                    totalIngested,
                    jobsMatched,
                    errors,
                    notes,
                    expanded,
                    facetSupport
            );
        }

        String overallStatus = determineStatus(sourcesQueried, sourcesSucceeded, sourcesFailed);
        String errorSummary = errors.isEmpty() ? null : String.join("; ", errors);

        // Fencing release: complete execution only if lease owner token still matches
        boolean fenced = repository.completeExecution(plan.id(), ownerToken, overallStatus, errorSummary, totalIngested);
        if (!fenced) {
            log.warn("Search plan {} execution completed by token {} was fenced out (lease expired and claimed by another worker)",
                    plan.id(), ownerToken);
            notes.add("Warning: execution took longer than lease deadline; completed with fencing protection");
            overallStatus = "FAILED";
            errors.add("Completion update fenced out: lease expired before terminal write");
        }

        notes.add(String.format("Ingested %d jobs across %d sources; evaluated %d matches with multi-stage reranker",
                totalIngested, sourcesQueried, jobsMatched));

        return new ExecutionSummary(
                plan.id(),
                plan.name(),
                overallStatus,
                sourcesQueried,
                sourcesSucceeded,
                sourcesFailed,
                totalIngested,
                jobsMatched,
                errors,
                notes,
                expanded,
                facetSupport
        );
    }

    private String determineStatus(int queried, int succeeded, int failed) {
        if (queried == 0) return "SUCCESS";
        if (failed == 0) return "SUCCESS";
        if (succeeded > 0) return "PARTIAL";
        return "FAILED";
    }

    private record SourceCandidate(UUID id, String kind, String displayName, String orgIdentifier) {}

    private List<SourceCandidate> resolveMatchingSources(List<String> targetSources) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, kind, display_name, org_identifier
                FROM job_sources
                WHERE enabled = true AND policy <> 'DISABLED'
                ORDER BY display_name ASC
                """);

        List<SourceCandidate> result = new ArrayList<>();
        Set<String> targetsUpper = new HashSet<>();
        if (targetSources != null) {
            for (String ts : targetSources) {
                if (ts != null && !ts.isBlank()) {
                    targetsUpper.add(ts.trim().toUpperCase(Locale.ROOT));
                }
            }
        }

        for (Map<String, Object> row : rows) {
            String kind = String.valueOf(row.get("kind")).toUpperCase(Locale.ROOT);
            String name = String.valueOf(row.get("display_name")).toUpperCase(Locale.ROOT);

            if (targetsUpper.isEmpty() || targetsUpper.contains(kind) || targetsUpper.contains(name)) {
                result.add(new SourceCandidate(
                        (UUID) row.get("id"),
                        String.valueOf(row.get("kind")),
                        String.valueOf(row.get("display_name")),
                        (String) row.get("org_identifier")
                ));
            }
        }
        return result;
    }

    private Map<String, String> evaluateFacetSupport(SearchPlanQueryExpander.ExpandedQueryPlan plan) {
        Map<String, String> support = new LinkedHashMap<>();
        support.put("targetSources", "Adzuna: targeted query sent natively. ATS (Greenhouse, Lever, Ashby, Workday, Rippling): company board harvest with candidate query filtering applied downstream during matching.");
        support.put("keywords", "Adzuna: sent natively via 'what' search parameter. ATS: evaluated downstream during skills overlap & title matching.");
        support.put("locations", "Adzuna: sent natively via 'where' search parameter. ATS: enforced downstream via Stage 1 Hard Eligibility Gate.");
        support.put("maxResults", "Natively bounded by execution coordinator ingestion ceiling.");
        support.put("titles", "Expanded to synonyms; filtered downstream via MultiStageReranker Stage 1 & Stage 4.");
        support.put("skills", "Canonicalised; scored via SemanticSkillMatcher in MultiStageReranker Stage 2.");
        support.put("seniority", "Classified and aligned via MultiStageReranker Stage 3 trajectory curve.");
        support.put("remoteTypes", "Enforced downstream via MultiStageReranker Stage 1 Hard Eligibility Gate.");
        support.put("negativeKeywords", "Filtered downstream via MultiStageReranker Stage 1 Hard Eligibility Gate.");
        return support;
    }
}
