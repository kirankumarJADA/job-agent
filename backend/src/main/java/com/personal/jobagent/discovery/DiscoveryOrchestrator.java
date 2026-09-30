package com.personal.jobagent.discovery;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class DiscoveryOrchestrator {
    public record DiscoveryRun(String correlationId, List<JobDiscoveryService.IngestResult> ingested,
                               boolean verificationUsed, List<String> errors) {}

    private final List<ScraperProvider> providers;
    private final JobDiscoveryService ingestion;
    private final JobDeduplicationService dedup;
    private final ExtractionComparisonService comparison;
    private final DiscoveryTelemetry telemetry;
    private final double conflictThreshold;
    private final double primaryConfidenceThreshold;
    private final List<String> secondaryPriority;
    private final GreenhouseProvider greenhouseProvider;
    private final AshbyProvider ashbyProvider;

    @Autowired
    public DiscoveryOrchestrator(List<ScraperProvider> providers, JobDiscoveryService ingestion,
                                 JobDeduplicationService dedup, ExtractionComparisonService comparison,
                                 DiscoveryTelemetry telemetry,
                                 @Value("${app.discovery.comparison-conflict-threshold:0.20}") double conflictThreshold,
                                 @Value("${app.discovery.primary-confidence-threshold:0.60}") double primaryConfidenceThreshold,
                                 @Value("${app.discovery.secondary-priority:firecrawl,apify,browserless,scraperapi,scrapingbee}") String secondaryPriority) {
        this(providers, ingestion, dedup, comparison, telemetry, conflictThreshold, primaryConfidenceThreshold,
                secondaryPriority, new GreenhouseProvider(), new AshbyProvider());
    }

    DiscoveryOrchestrator(List<ScraperProvider> providers, JobDiscoveryService ingestion,
                          JobDeduplicationService dedup, ExtractionComparisonService comparison,
                          DiscoveryTelemetry telemetry, double conflictThreshold,
                          double primaryConfidenceThreshold, String secondaryPriority,
                          GreenhouseProvider greenhouseProvider, AshbyProvider ashbyProvider) {
        this.providers = providers;
        this.ingestion = ingestion;
        this.dedup = dedup;
        this.comparison = comparison;
        this.telemetry = telemetry;
        this.conflictThreshold = conflictThreshold;
        this.primaryConfidenceThreshold = primaryConfidenceThreshold;
        this.secondaryPriority = List.of(secondaryPriority.split(",")).stream()
                .map(String::trim).filter(value -> !value.isBlank()).toList();
        this.greenhouseProvider = greenhouseProvider;
        this.ashbyProvider = ashbyProvider;
    }

    /** Generic URL discovery path; structured ATS connectors are intentionally not mixed into it. */
    public DiscoveryRun discover(UUID sourceId, String sourceType, String url) {
        String correlationId = UUID.randomUUID().toString();
        List<ScraperProvider> primary = available(ScraperProvider.ProviderRole.PRIMARY);
        List<ScraperProvider> secondary = available(ScraperProvider.ProviderRole.SECONDARY);
        List<ScraperProvider.ExtractionResult> primaryResults = runParallel(primary, sourceType, url, correlationId);
        List<ScraperProvider.ExtractedJob> primaryJobs = successfulJobs(primaryResults);

        boolean primaryFailed = primaryJobs.isEmpty();
        boolean primaryIncomplete = primaryJobs.stream().anyMatch(job -> job.confidence() < primaryConfidenceThreshold);
        ExtractionComparisonService.ComparisonResult primaryComparison = primaryJobs.size() >= 2
                ? comparison.compare(primaryJobs.get(0), primaryJobs.get(1), conflictThreshold) : null;
        boolean verify = primaryFailed || primaryIncomplete
                || primaryComparison != null && primaryComparison.needsVerification();

        List<ScraperProvider.ExtractionResult> secondaryResults = verify
                ? runSecondaryByPriority(secondary, sourceType, url, correlationId) : List.of();
        Map<String, Object> comparisonMetadata = comparisonMetadata(primaryComparison, primaryJobs);
        primaryResults.forEach(result -> telemetry.record(result, comparisonMetadata));
        secondaryResults.forEach(result -> telemetry.record(result, comparisonMetadata));

        List<ScraperProvider.ExtractionResult> all = new ArrayList<>(primaryResults);
        all.addAll(secondaryResults);
        return ingestResults(sourceId, correlationId, all, verify && !secondaryResults.isEmpty());
    }

    /** Dispatch a persisted Greenhouse board through its public board API. */
    public DiscoveryRun discoverGreenhouseBoard(UUID sourceId, String boardName) {
        String correlationId = UUID.randomUUID().toString();
        return ingestPlatformBoard(sourceId, greenhouseProvider.extractBoard(boardName, correlationId), greenhouseProvider);
    }

    /** Dispatch a persisted Ashby source through the unauthenticated public Job Postings API. */
    public DiscoveryRun discoverAshbyBoard(UUID sourceId, String boardIdentifier) {
        String correlationId = UUID.randomUUID().toString();
        String boardName = AshbyProvider.boardNameFromIdentifier(boardIdentifier);
        return ingestPlatformBoard(sourceId, ashbyProvider.extractBoard(boardName, correlationId), ashbyProvider);
    }

    private DiscoveryRun ingestPlatformBoard(UUID sourceId, ScraperProvider.ExtractionResult extraction,
                                             ScraperProvider provider) {
        if (extraction.error() != null) telemetry.record(extraction);
        List<JobDiscoveryService.IngestResult> ingested = new ArrayList<>();
        List<String> errors = extraction.error() == null ? List.of()
                : List.of(extraction.provider() + ":" + extraction.error());
        for (ScraperProvider.ExtractedJob job : extraction.jobs()) {
            JobDiscoveryService.IngestResult result = ingestion.ingestJob(toCommand(sourceId, job));
            ingested.add(result);
            dedup.recordObservation(result.jobId(), provider, job, job.confidence());
        }
        return new DiscoveryRun(extraction.correlationId(), ingested, false, errors);
    }

    private DiscoveryRun ingestResults(UUID sourceId, String correlationId,
                                       List<ScraperProvider.ExtractionResult> results,
                                       boolean verificationUsed) {
        List<JobDiscoveryService.IngestResult> ingested = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (ScraperProvider.ExtractionResult extraction : results) {
            if (extraction.error() != null) errors.add(extraction.provider() + ":" + extraction.error());
            ScraperProvider provider = providers.stream()
                    .filter(candidate -> candidate.providerId().equals(extraction.provider()))
                    .findFirst().orElse(null);
            if (provider == null) continue;
            for (ScraperProvider.ExtractedJob job : extraction.jobs()) {
                JobDiscoveryService.IngestResult result = ingestion.ingestJob(toCommand(sourceId, job));
                ingested.add(result);
                dedup.recordObservation(result.jobId(), provider, job, job.confidence());
            }
        }
        return new DiscoveryRun(correlationId, ingested, verificationUsed, errors);
    }

    private List<ScraperProvider> available(ScraperProvider.ProviderRole role) {
        return providers.stream().filter(provider -> provider.role() == role && provider.enabled()
                && provider.state() == ScraperProvider.ProviderState.AVAILABLE).toList();
    }

    private List<ScraperProvider.ExtractionResult> runParallel(List<ScraperProvider> selected, String type,
                                                                String url, String correlationId) {
        if (selected.isEmpty()) return List.of();
        ExecutorService executor = Executors.newFixedThreadPool(selected.size());
        try {
            List<CompletableFuture<ScraperProvider.ExtractionResult>> futures = selected.stream()
                    .map(provider -> CompletableFuture.supplyAsync(() -> provider.extract(
                            new ScraperProvider.DiscoveryRequest(url, type, correlationId, Map.of())), executor))
                    .toList();
            return futures.stream().map(CompletableFuture::join).toList();
        } finally {
            executor.shutdown();
        }
    }

    private List<ScraperProvider.ExtractionResult> runSecondaryByPriority(List<ScraperProvider> selected,
                                                                           String type, String url,
                                                                           String correlationId) {
        List<ScraperProvider> ordered = selected.stream().sorted(Comparator.comparingInt(provider -> {
            int index = secondaryPriority.indexOf(provider.providerId());
            return index < 0 ? Integer.MAX_VALUE : index;
        })).toList();
        List<ScraperProvider.ExtractionResult> results = new ArrayList<>();
        for (ScraperProvider provider : ordered) {
            ScraperProvider.ExtractionResult result = provider.extract(
                    new ScraperProvider.DiscoveryRequest(url, type, correlationId, Map.of()));
            results.add(result);
            if (result.successful()) break;
        }
        return results;
    }

    private static List<ScraperProvider.ExtractedJob> successfulJobs(List<ScraperProvider.ExtractionResult> results) {
        return results.stream().filter(ScraperProvider.ExtractionResult::successful)
                .flatMap(result -> result.jobs().stream()).toList();
    }

    private Map<String, Object> comparisonMetadata(ExtractionComparisonService.ComparisonResult result,
                                                    List<ScraperProvider.ExtractedJob> jobs) {
        if (result == null) return Map.of("status", jobs.isEmpty() ? "NO_PRIMARY_RESULT" : "INSUFFICIENT_PRIMARY_RESULTS");
        return Map.of("status", result.agreement() ? "AGREED"
                        : result.needsVerification() ? "CONFLICT" : "MINOR_DIFFERENCE",
                "confidence", result.confidence(), "decision", result.decision());
    }

    private static JobDiscoveryService.IngestJobCommand toCommand(UUID sourceId, ScraperProvider.ExtractedJob job) {
        String source = job.sourceUrl() == null ? job.applicationUrl() : job.sourceUrl();
        String externalId = job.externalJobId() == null
                ? JobDeduplicationService.normalizeUrl(source) : job.externalJobId();
        return new JobDiscoveryService.IngestJobCommand(sourceId, externalId, null, job.company(), job.title(),
                job.location(), null, null, job.remoteType(), job.employmentType(), null, null, null, null,
                job.description(), job.skills(), job.applicationUrl(), source, job.postedAt());
    }
}
