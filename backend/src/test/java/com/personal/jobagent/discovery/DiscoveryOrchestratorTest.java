package com.personal.jobagent.discovery;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DiscoveryOrchestratorTest {
    @Test void confidentPrimaryDoesNotInvokeSecondary() {
        var ingestion = mock(JobDiscoveryService.class); var dedup = mock(JobDeduplicationService.class); var telemetry = mock(DiscoveryTelemetry.class); var comparison = new ExtractionComparisonService();
        var primary = provider("own", ScraperProvider.ProviderRole.PRIMARY, result("own", "Engineer")); var secondary = provider("firecrawl", ScraperProvider.ProviderRole.SECONDARY, result("firecrawl", "Engineer"));
        when(ingestion.ingestJob(any())).thenReturn(new JobDiscoveryService.IngestResult(UUID.randomUUID(), "INSERTED", "k", "h"));
        var run = new DiscoveryOrchestrator(List.of(primary, secondary), ingestion, dedup, comparison, telemetry, .20, .60, "firecrawl,apify").discover(UUID.randomUUID(), "CAREERS", "https://example.test/jobs");
        assertThat(run.verificationUsed()).isFalse(); verify(primary).extract(any()); verify(secondary, never()).extract(any());
    }
    @Test void conflictingPrimaryInvokesSecondaryVerification() {
        var ingestion = mock(JobDiscoveryService.class); var dedup = mock(JobDeduplicationService.class); var telemetry = mock(DiscoveryTelemetry.class); var comparison = new ExtractionComparisonService();
        var own = provider("own", ScraperProvider.ProviderRole.PRIMARY, result("own", "Engineer")); var crawl = provider("crawl4ai", ScraperProvider.ProviderRole.PRIMARY, result("crawl4ai", "Senior Engineer")); var secondary = provider("firecrawl", ScraperProvider.ProviderRole.SECONDARY, result("firecrawl", "Engineer"));
        when(ingestion.ingestJob(any())).thenReturn(new JobDiscoveryService.IngestResult(UUID.randomUUID(), "INSERTED", "k", "h"));
        var run = new DiscoveryOrchestrator(List.of(own, crawl, secondary), ingestion, dedup, comparison, telemetry, .20, .60, "firecrawl,apify").discover(UUID.randomUUID(), "CAREERS", "https://example.test/jobs");
        assertThat(run.verificationUsed()).isTrue(); verify(own).extract(any()); verify(crawl).extract(any()); verify(secondary).extract(any());
    }
    private static ScraperProvider provider(String id, ScraperProvider.ProviderRole role, ScraperProvider.ExtractionResult r) { var p=mock(ScraperProvider.class); when(p.providerId()).thenReturn(id); when(p.role()).thenReturn(role); when(p.enabled()).thenReturn(true); when(p.state()).thenReturn(ScraperProvider.ProviderState.AVAILABLE); when(p.extract(any())).thenReturn(r); return p; }
    private static ScraperProvider.ExtractionResult result(String provider, String title) { var now=Instant.now(); var j=new ScraperProvider.ExtractedJob(title,"Acme","London","HYBRID",null,"FULL_TIME","Build systems",List.of("Java"),"https://example.test/jobs/1","https://example.test/jobs/1",null,"req-1","hash-"+title,.8,null); return new ScraperProvider.ExtractionResult(provider,"correlation",now,now,List.of(j),.8,null,0); }
}
