package com.personal.jobagent.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the Scrapling integration end-to-end against REAL job-page HTML:
 * the fixture is the head of an actual public Greenhouse job page
 * (boards.greenhouse.io / job-boards.greenhouse.io template, fetched live
 * through the sidecar during integration verification). Greenhouse's current
 * template carries the job title in &lt;title&gt; and og:title but no JSON-LD
 * JobPosting, so this pins the parser's fallback behavior on real content —
 * the case a synthetic fixture would hide.
 *
 * The sidecar is stubbed with an in-process HTTP server returning exactly the
 * bytes Scrapling returned for that page; everything downstream (provider →
 * parser → ExtractedJob) is the real production code.
 */
class ScraplingProviderFixtureTest {
    private HttpServer server;
    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test
    void realGreenhouseJobPageNormalizesIntoTheExistingExtractedJob() throws Exception {
        String fixture = new String(new ClassPathResource("discovery/greenhouse-monzo-job.html")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/fetch", exchange -> {
            byte[] body = ("{\"ok\":true,\"status\":200,\"url\":\"https://job-boards.greenhouse.io/monzo/jobs/8200681\",\"content\":"
                    + new ObjectMapper().writeValueAsString(fixture) + "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();

        var provider = new ScraplingProvider(new ObjectMapper(),
                new MockEnvironment().withProperty("SCRAPLING_BASE_URL", "http://localhost:" + server.getAddress().getPort()));
        var result = provider.extract(new ScraperProvider.DiscoveryRequest(
                "https://job-boards.greenhouse.io/monzo/jobs/8200681", "CAREERS", "fixture-corr", null));

        assertThat(result.successful()).isTrue();
        assertThat(result.jobs()).hasSize(1);
        var job = result.jobs().get(0);
        assertThat(job.title()).isEqualTo("Operational Tax, Senior Manager");
        assertThat(job.company()).isNull(); // Greenhouse's template exposes no company meta
        // The real page declares its own canonical (with http://) and the
        // parser prefers it over the requested URL — verified live behavior.
        assertThat(job.sourceUrl()).isEqualTo("http://job-boards.greenhouse.io/monzo/jobs/8200681");
        assertThat(job.applicationUrl()).isEqualTo("http://job-boards.greenhouse.io/monzo/jobs/8200681");
        assertThat(job.contentHash()).hasSize(64);
        assertThat(job.confidence()).isEqualTo(ScraplingProvider.CONFIDENCE);
    }
}
