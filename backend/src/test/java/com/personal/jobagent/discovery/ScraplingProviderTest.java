package com.personal.jobagent.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contract tests for the optional Scrapling provider: policy (off unless
 * configured), the sidecar HTTP contract, error handling, conversion into the
 * existing {@code ExtractedJob} shape, and the orchestrator treating it like
 * any other PRIMARY provider. The sidecar itself is stubbed with an in-process
 * HTTP server — no public internet, no Python required for the unit suite.
 */
class ScraplingProviderTest {
    private HttpServer server;
    @AfterEach void stop() { if (server != null) server.stop(0); }

    private static final String JOB_HTML = """
            <html><head><link rel="canonical" href="https://boards.example.org/acme/jobs/42">
            <script type="application/ld+json">{"@type":"JobPosting","title":"Backend Engineer",
            "description":"Build Java services at scale","hiringOrganization":{"name":"Acme"},
            "jobLocation":{"address":{"addressLocality":"London"}},"employmentType":"FULL_TIME",
            "identifier":"42"}</script></head><body><h1>Backend Engineer</h1></body></html>
            """;

    @Test
    void providerIsOptionalAndUnconfiguredWithoutABaseUrl() {
        var provider = new ScraplingProvider(new ObjectMapper(), new MockEnvironment());
        assertThat(provider.providerId()).isEqualTo("scrapling");
        assertThat(provider.enabled()).isFalse();
        assertThat(provider.state()).isEqualTo(ScraperProvider.ProviderState.UNCONFIGURED);
        assertThat(provider.role()).isEqualTo(ScraperProvider.ProviderRole.PRIMARY);
        var result = provider.extract(new ScraperProvider.DiscoveryRequest("https://example.test/job", "CAREERS", "c", null));
        assertThat(result.error()).isEqualTo("UNCONFIGURED");
        assertThat(result.jobs()).isEmpty();
    }

    @Test
    void fetchesThroughTheSidecarAndConvertsIntoTheExistingExtractedJobShape() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        AtomicReference<String> capturedAuth = new AtomicReference<>("absent");
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/fetch", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            capturedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"ok\":true,\"status\":200,\"url\":\"https://boards.example.org/acme/jobs/42\",\"content\":" +
                    new ObjectMapper().writeValueAsString(JOB_HTML) + "}");
        });
        server.start();
        MockEnvironment env = new MockEnvironment()
                .withProperty("SCRAPLING_BASE_URL", "http://localhost:" + server.getAddress().getPort())
                .withProperty("SCRAPLING_API_TOKEN", "sidecar-secret");
        var provider = new ScraplingProvider(new ObjectMapper(), env);

        var result = provider.extract(new ScraperProvider.DiscoveryRequest(
                "https://boards.example.org/acme/jobs/42", "CAREERS", "corr-1", null));

        assertThat(result.successful()).isTrue();
        assertThat(result.jobs()).singleElement().satisfies(job -> {
            assertThat(job.title()).isEqualTo("Backend Engineer");
            assertThat(job.company()).isEqualTo("Acme");
            assertThat(job.location()).isEqualTo("London");
            assertThat(job.description()).isEqualTo("Build Java services at scale");
            assertThat(job.employmentType()).isEqualTo("FULL_TIME");
            assertThat(job.externalJobId()).isEqualTo("42");
            assertThat(job.sourceUrl()).isEqualTo("https://boards.example.org/acme/jobs/42");
            assertThat(job.contentHash()).hasSize(64);
            assertThat(job.confidence()).isEqualTo(ScraplingProvider.CONFIDENCE);
        });
        // The sidecar contract: JSON body carrying the target URL and mode,
        // and the optional shared token as Bearer — never in the body/log.
        org.assertj.core.api.Assertions.assertThat(capturedBody.get()).contains("\"url\":\"https://boards.example.org/acme/jobs/42\"").contains("\"mode\":\"http\"");
        org.assertj.core.api.Assertions.assertThat(capturedAuth.get()).isEqualTo("Bearer sidecar-secret");
    }

    @Test
    void fetchModeComesFromConfigurationAndUnknownModesFallBackToHttp() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/fetch", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"ok\":false,\"error\":\"no fetch attempted\",\"status\":null}");
        });
        server.start();
        String base = "http://localhost:" + server.getAddress().getPort();

        var stealthy = new ScraplingProvider(new ObjectMapper(),
                new MockEnvironment().withProperty("SCRAPLING_BASE_URL", base).withProperty("SCRAPLING_FETCH_MODE", "stealthy"));
        stealthy.extract(new ScraperProvider.DiscoveryRequest("https://example.test/x", "CAREERS", "c", null));
        assertThat(capturedBody.get()).contains("\"mode\":\"stealthy\"");

        var invalid = new ScraplingProvider(new ObjectMapper(),
                new MockEnvironment().withProperty("SCRAPLING_BASE_URL", base).withProperty("SCRAPLING_FETCH_MODE", "nonsense"));
        invalid.extract(new ScraperProvider.DiscoveryRequest("https://example.test/x", "CAREERS", "c", null));
        assertThat(capturedBody.get()).contains("\"mode\":\"http\"");

        var plain = new ScraplingProvider(new ObjectMapper(),
                new MockEnvironment().withProperty("SCRAPLING_BASE_URL", base));
        plain.extract(new ScraperProvider.DiscoveryRequest("https://example.test/x", "CAREERS", "c", null));
        assertThat(capturedBody.get()).contains("\"mode\":\"http\"");
    }

    @Test
    void sidecarFailureReportIsSurfacedAsATerseErrorWithoutPageContent() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/fetch", exchange -> respond(exchange, 200,
                "{\"ok\":false,\"error\":\"upstream returned 403 after 3 stealth attempts\",\"status\":403}"));
        server.start();
        var provider = providerOn(server);
        var result = provider.extract(new ScraperProvider.DiscoveryRequest("https://example.test/job", "CAREERS", "c", null));
        assertThat(result.successful()).isFalse();
        assertThat(result.error()).isEqualTo("SCRAPLING_ERROR");
        assertThat(result.jobs()).isEmpty();
    }

    @Test
    void sidecarHttpErrorsAreSurfacedAsHttpCodes() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/fetch", exchange -> respond(exchange, 503, "sidecar unavailable"));
        server.start();
        var provider = providerOn(server);
        var result = provider.extract(new ScraperProvider.DiscoveryRequest("https://example.test/job", "CAREERS", "c", null));
        assertThat(result.error()).isEqualTo("HTTP_503");
        assertThat(result.jobs()).isEmpty();
    }

    @Test
    void malformedSidecarResponsesDoNotThrowOut() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/fetch", exchange -> respond(exchange, 200, "not-json-at-all"));
        server.start();
        var provider = providerOn(server);
        var result = provider.extract(new ScraperProvider.DiscoveryRequest("https://example.test/job", "CAREERS", "c", null));
        assertThat(result.successful()).isFalse();
        assertThat(result.jobs()).isEmpty();
        assertThat(result.error()).isNotBlank();
    }

    @Test
    void blankOrNonJobContentIsReportedAsIncomplete() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/fetch", exchange -> respond(exchange, 200,
                "{\"ok\":true,\"status\":200,\"url\":\"https://example.test/\",\"content\":\"   \"}"));
        server.start();
        var provider = providerOn(server);
        var result = provider.extract(new ScraperProvider.DiscoveryRequest("https://example.test/", "CAREERS", "c", null));
        assertThat(result.error()).isEqualTo("INCOMPLETE_CONTENT");
        assertThat(result.jobs()).isEmpty();
    }

    @Test
    void orchestratorTreatsScraplingLikeAnyOtherConfiguredPrimary() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/fetch", exchange -> respond(exchange, 200,
                "{\"ok\":true,\"status\":200,\"url\":\"https://boards.example.org/acme/jobs/42\",\"content\":" +
                        new ObjectMapper().writeValueAsString(JOB_HTML) + "}"));
        server.start();

        var provider = providerOn(server);
        var ingestion = mock(JobDiscoveryService.class);
        var dedup = mock(JobDeduplicationService.class);
        var telemetry = mock(DiscoveryTelemetry.class);
        UUID sourceId = java.util.UUID.randomUUID();
        when(ingestion.ingestJob(any())).thenReturn(new JobDiscoveryService.IngestResult(
                UUID.randomUUID(), "INSERTED", "dedup-key", "content-hash"));

        var orchestrator = new DiscoveryOrchestrator(
                List.of(provider), ingestion, dedup,
                new ExtractionComparisonService(), telemetry, 0.20, 0.60,
                "firecrawl,apify,browserless,scraperapi,scrapingbee");

        var run = orchestrator.discover(sourceId, "CAREERS", "https://boards.example.org/acme/jobs/42");

        assertThat(run.errors()).isEmpty();
        assertThat(run.ingested()).hasSize(1);
        verify(ingestion).ingestJob(any());
        verify(dedup).recordObservation(any(UUID.class), any(ScraperProvider.class),
                any(ScraperProvider.ExtractedJob.class), anyDouble());
    }

    @Test
    void unconfiguredScraplingIsSkippedByTheOrchestrator() {
        var provider = new ScraplingProvider(new ObjectMapper(), new MockEnvironment());
        var ingestion = mock(JobDiscoveryService.class);
        var orchestrator = new DiscoveryOrchestrator(
                List.of(provider), ingestion, mock(JobDeduplicationService.class),
                new ExtractionComparisonService(), mock(DiscoveryTelemetry.class), 0.20, 0.60,
                "firecrawl,apify,browserless,scraperapi,scrapingbee");

        var run = orchestrator.discover(UUID.randomUUID(), "CAREERS", "https://example.test/jobs");

        assertThat(run.ingested()).isEmpty();
        verify(ingestion, never()).ingestJob(any());
    }

    private ScraplingProvider providerOn(HttpServer server) {
        return new ScraplingProvider(new ObjectMapper(),
                new MockEnvironment().withProperty("SCRAPLING_BASE_URL", "http://localhost:" + server.getAddress().getPort()));
    }

    private static void respond(com.sun.net.httpserver.HttpExchange e, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        e.getResponseHeaders().set("Content-Type", "application/json");
        e.sendResponseHeaders(status, bytes.length);
        try (var out = e.getResponseBody()) { out.write(bytes); }
    }
}
