package com.personal.jobagent.discovery;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.*;

@Service
public class DiscoveryOrchestrator {
    public record DiscoveryRun(String correlationId, List<JobDiscoveryService.IngestResult> ingested, boolean verificationUsed, List<String> errors) {}
    private final List<ScraperProvider> providers; private final JobDiscoveryService ingestion; private final JobDeduplicationService dedup;
    private final ExtractionComparisonService comparison; private final DiscoveryTelemetry telemetry;    private final double conflictThreshold;
    private final double primaryConfidenceThreshold;
    private final List<String> secondaryPriority;
    public DiscoveryOrchestrator(List<ScraperProvider> providers, JobDiscoveryService ingestion, JobDeduplicationService dedup,
                                  ExtractionComparisonService comparison, DiscoveryTelemetry telemetry,
                                  @Value("${app.discovery.comparison-conflict-threshold:0.20}") double conflictThreshold,
                                  @Value("${app.discovery.primary-confidence-threshold:0.60}") double primaryConfidenceThreshold,
                                 @Value("${app.discovery.secondary-priority:firecrawl,apify,browserless,scraperapi,scrapingbee}") String secondaryPriority) {
        this.providers=providers; this.ingestion=ingestion; this.dedup=dedup; this.comparison=comparison; this.telemetry=telemetry;
        this.conflictThreshold=conflictThreshold;        this.primaryConfidenceThreshold = primaryConfidenceThreshold;
        this.secondaryPriority = Arrays.stream(secondaryPriority.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();
    }
    public DiscoveryRun discover(UUID sourceId, String sourceType, String url) {
        String correlation=UUID.randomUUID().toString(); List<ScraperProvider> primary=available(ScraperProvider.ProviderRole.PRIMARY);
        List<ScraperProvider> secondary=available(ScraperProvider.ProviderRole.SECONDARY);
        List<ScraperProvider.ExtractionResult> primaryResults=parallel(primary,sourceType,url,correlation);
        List<ScraperProvider.ExtractedJob> primaryJobs=jobs(primaryResults);
        boolean failed=primaryJobs.isEmpty(); boolean incomplete=primaryJobs.stream().anyMatch(j->j.confidence()<primaryConfidenceThreshold);
        ExtractionComparisonService.ComparisonResult cmp=primaryJobs.size()>=2?comparison.compare(primaryJobs.get(0),primaryJobs.get(1),conflictThreshold):null;
        boolean conflict=cmp!=null&&cmp.needsVerification(); boolean verify=failed||incomplete||conflict;
        List<ScraperProvider.ExtractionResult> secondaryResults=verify?runSecondaryByPriority(secondary,sourceType,url,correlation):List.of();
        Map<String,Object> comparisonMetadata=metadata(cmp,primaryJobs); primaryResults.forEach(r->telemetry.record(r,comparisonMetadata)); secondaryResults.forEach(r->telemetry.record(r,comparisonMetadata));
        List<ScraperProvider.ExtractionResult> all=new ArrayList<>(primaryResults); all.addAll(secondaryResults); List<JobDiscoveryService.IngestResult> ingested=new ArrayList<>(); List<String> errors=new ArrayList<>();
        for(var extraction:all){if(extraction.error()!=null)errors.add(extraction.provider()+":"+extraction.error()); ScraperProvider provider=providers.stream().filter(p->p.providerId().equals(extraction.provider())).findFirst().orElse(null); if(provider==null)continue; for(var job:extraction.jobs()){var result=ingestion.ingestJob(toCommand(sourceId,job)); ingested.add(result); dedup.recordObservation(result.jobId(),provider,job,job.confidence());}}
        return new DiscoveryRun(correlation,ingested,verify&&!secondaryResults.isEmpty(),errors);
    }
    private List<ScraperProvider> available(ScraperProvider.ProviderRole role){return providers.stream().filter(p->p.role()==role&&p.enabled()&&p.state()==ScraperProvider.ProviderState.AVAILABLE).toList();}
    private List<ScraperProvider.ExtractionResult> runSecondaryByPriority(List<ScraperProvider> selected,String type,String url,String correlation){List<ScraperProvider> ordered=selected.stream().sorted(Comparator.comparingInt(p->{int i=secondaryPriority.indexOf(p.providerId());return i<0?Integer.MAX_VALUE:i;})).toList();List<ScraperProvider.ExtractionResult> out=new ArrayList<>();for(ScraperProvider p:ordered){var r=p.extract(new ScraperProvider.DiscoveryRequest(url,type,correlation,Map.of()));out.add(r);if(r.successful())break;}return out;}
    private List<ScraperProvider.ExtractionResult> parallel(List<ScraperProvider> providers,String type,String url,String correlation){if(providers.isEmpty())return List.of(); ExecutorService executor=Executors.newFixedThreadPool(providers.size()); try{var futures=providers.stream().map(p->CompletableFuture.supplyAsync(()->p.extract(new ScraperProvider.DiscoveryRequest(url,type,correlation,Map.of())),executor)).toList(); return futures.stream().map(CompletableFuture::join).toList();}finally{executor.shutdown();}}
    private static List<ScraperProvider.ExtractedJob> jobs(List<ScraperProvider.ExtractionResult> results){return results.stream().filter(ScraperProvider.ExtractionResult::successful).flatMap(r->r.jobs().stream()).toList();}
    private Map<String,Object> metadata(ExtractionComparisonService.ComparisonResult cmp,List<ScraperProvider.ExtractedJob> jobs){if(cmp==null)return Map.of("status",jobs.isEmpty()?"NO_PRIMARY_RESULT":"INSUFFICIENT_PRIMARY_RESULTS"); return Map.of("status",cmp.agreement()?"AGREED":cmp.needsVerification()?"CONFLICT":"MINOR_DIFFERENCE","confidence",cmp.confidence(),"decision",cmp.decision());}
    private static JobDiscoveryService.IngestJobCommand toCommand(UUID sourceId,ScraperProvider.ExtractedJob job){String source=job.sourceUrl()==null?job.applicationUrl():job.sourceUrl(); String external=job.externalJobId()==null?JobDeduplicationService.normalizeUrl(source):job.externalJobId(); return new JobDiscoveryService.IngestJobCommand(sourceId,external,null,job.company(),job.title(),job.location(),null,null,job.remoteType(),job.employmentType(),null,null,null,null,job.description(),job.skills(),job.applicationUrl(),source);}
}
