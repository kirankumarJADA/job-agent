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
        var run = new DiscoveryOrchestrator(List.of(primary, secondary), ingestion, dedup, comparison, telemetry, .20, .60, "firecrawl,apify", new GreenhouseProvider(), new AshbyProvider(), 3, 30000).discover(UUID.randomUUID(), "CAREERS", "https://example.test/jobs");
        assertThat(run.verificationUsed()).isFalse(); verify(primary).extract(any()); verify(secondary, never()).extract(any());
    }
    @Test void conflictingPrimaryInvokesSecondaryVerification() {
        var ingestion = mock(JobDiscoveryService.class); var dedup = mock(JobDeduplicationService.class); var telemetry = mock(DiscoveryTelemetry.class); var comparison = new ExtractionComparisonService();
        var own = provider("own", ScraperProvider.ProviderRole.PRIMARY, result("own", "Engineer")); var crawl = provider("crawl4ai", ScraperProvider.ProviderRole.PRIMARY, result("crawl4ai", "Senior Engineer")); var secondary = provider("firecrawl", ScraperProvider.ProviderRole.SECONDARY, result("firecrawl", "Engineer"));
        when(ingestion.ingestJob(any())).thenReturn(new JobDiscoveryService.IngestResult(UUID.randomUUID(), "INSERTED", "k", "h"));
        var run = new DiscoveryOrchestrator(List.of(own, crawl, secondary), ingestion, dedup, comparison, telemetry, .20, .60, "firecrawl,apify", new GreenhouseProvider(), new AshbyProvider(), 3, 30000).discover(UUID.randomUUID(), "CAREERS", "https://example.test/jobs");
        assertThat(run.verificationUsed()).isTrue(); verify(own).extract(any()); verify(crawl).extract(any()); verify(secondary).extract(any());
    }
    @Test void secondaryProvidersRunInParallelNotSequential() {
        var ingestion = mock(JobDiscoveryService.class); var dedup = mock(JobDeduplicationService.class); var telemetry = mock(DiscoveryTelemetry.class); var comparison = new ExtractionComparisonService();
        // Primary with low confidence triggers secondary verification
        var primary = provider("own", ScraperProvider.ProviderRole.PRIMARY, result("own", "Engineer", 0.4));
        // Two secondaries — both should be called (parallel fan-out)
        var sec1 = provider("firecrawl", ScraperProvider.ProviderRole.SECONDARY, result("firecrawl", "Engineer"));
        var sec2 = provider("apify", ScraperProvider.ProviderRole.SECONDARY, result("apify", "Engineer"));
        when(ingestion.ingestJob(any())).thenReturn(new JobDiscoveryService.IngestResult(UUID.randomUUID(), "INSERTED", "k", "h"));
        var run = new DiscoveryOrchestrator(List.of(primary, sec1, sec2), ingestion, dedup, comparison, telemetry, .20, .60, "firecrawl,apify", new GreenhouseProvider(), new AshbyProvider(), 3, 30000)
                .discover(UUID.randomUUID(), "CAREERS", "https://example.test/jobs");
        assertThat(run.verificationUsed()).isTrue();
        // Both secondaries should have been invoked (parallel), not just the first
        verify(sec1).extract(any()); verify(sec2).extract(any());
    }

    @Test void concurrencyCapLimitsNumberOfSecondaryProviders() {
        var ingestion = mock(JobDiscoveryService.class); var dedup = mock(JobDeduplicationService.class); var telemetry = mock(DiscoveryTelemetry.class); var comparison = new ExtractionComparisonService();
        var primary = provider("own", ScraperProvider.ProviderRole.PRIMARY, result("own", "Engineer", 0.4));
        var sec1 = provider("firecrawl", ScraperProvider.ProviderRole.SECONDARY, result("firecrawl", "Engineer"));
        var sec2 = provider("apify", ScraperProvider.ProviderRole.SECONDARY, result("apify", "Engineer"));
        var sec3 = provider("browserless", ScraperProvider.ProviderRole.SECONDARY, result("browserless", "Engineer"));
        when(ingestion.ingestJob(any())).thenReturn(new JobDiscoveryService.IngestResult(UUID.randomUUID(), "INSERTED", "k", "h"));
        // Cap at 2 — only the top-2 by priority should run
        var run = new DiscoveryOrchestrator(List.of(primary, sec1, sec2, sec3), ingestion, dedup, comparison, telemetry, .20, .60, "firecrawl,apify,browserless", new GreenhouseProvider(), new AshbyProvider(), 2, 30000)
                .discover(UUID.randomUUID(), "CAREERS", "https://example.test/jobs");
        verify(sec1).extract(any()); verify(sec2).extract(any()); verify(sec3, never()).extract(any());
    }

    @Test void timeoutProducesErrorResultInsteadOfHanging() {
        var ingestion = mock(JobDiscoveryService.class); var dedup = mock(JobDeduplicationService.class); var telemetry = mock(DiscoveryTelemetry.class); var comparison = new ExtractionComparisonService();
        var primary = provider("own", ScraperProvider.ProviderRole.PRIMARY, result("own", "Engineer", 0.4));
        // Slow secondary that exceeds the timeout
        var slowSec = mock(ScraperProvider.class); when(slowSec.providerId()).thenReturn("slow"); when(slowSec.role()).thenReturn(ScraperProvider.ProviderRole.SECONDARY); when(slowSec.enabled()).thenReturn(true); when(slowSec.state()).thenReturn(ScraperProvider.ProviderState.AVAILABLE);
        when(slowSec.extract(any())).thenAnswer(inv -> { Thread.sleep(5000); return result("slow", "Engineer"); });
        when(ingestion.ingestJob(any())).thenReturn(new JobDiscoveryService.IngestResult(UUID.randomUUID(), "INSERTED", "k", "h"));
        // Timeout at 200ms so the slow provider times out quickly in tests
        var run = new DiscoveryOrchestrator(List.of(primary, slowSec), ingestion, dedup, comparison, telemetry, .20, .60, "slow", new GreenhouseProvider(), new AshbyProvider(), 3, 200)
                .discover(UUID.randomUUID(), "CAREERS", "https://example.test/jobs");
        assertThat(run.verificationUsed()).isTrue();
        assertThat(run.errors()).anyMatch(e -> e.contains("TIMEOUT"));
    }

    private static ScraperProvider provider(String id, ScraperProvider.ProviderRole role, ScraperProvider.ExtractionResult r) { var p=mock(ScraperProvider.class); when(p.providerId()).thenReturn(id); when(p.role()).thenReturn(role); when(p.enabled()).thenReturn(true); when(p.state()).thenReturn(ScraperProvider.ProviderState.AVAILABLE); when(p.extract(any())).thenReturn(r); return p; }
    private static ScraperProvider.ExtractionResult result(String provider, String title) { return result(provider, title, .8); }
    private static ScraperProvider.ExtractionResult result(String provider, String title, double confidence) { var now=Instant.now(); var j=new ScraperProvider.ExtractedJob(title,"Acme","London","HYBRID",null,"FULL_TIME","Build systems",List.of("Java"),"https://example.test/jobs/1","https://example.test/jobs/1",null,"req-1","hash-"+title,confidence,null); return new ScraperProvider.ExtractionResult(provider,"correlation",now,now,List.of(j),confidence,null,0); }
}
