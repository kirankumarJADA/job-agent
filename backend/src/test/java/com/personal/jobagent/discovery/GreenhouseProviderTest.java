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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract tests for the Greenhouse board connector, against a captured real
 * API response ({@code greenhouse-board-fixture.json} — three genuine postings
 * from a live public board, content trimmed) plus synthetic error cases.
 * No test touches the public internet.
 */
class GreenhouseProviderTest {
    private HttpServer server;
    @AfterEach void stop() { if (server != null) server.stop(0); }

    private GreenhouseProvider providerOn(int port) {
        return new GreenhouseProvider("http://localhost:" + port);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange e, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        e.getResponseHeaders().set("Content-Type", "application/json");
        e.sendResponseHeaders(status, bytes.length);
        try (var out = e.getResponseBody()) { out.write(bytes); }
    }

    @Test
    void mapsRealCapturedBoardResponseIntoTheExistingJobContract() throws Exception {
        String fixture = new String(new ClassPathResource("discovery/greenhouse-board-fixture.json")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/boards/monzo/jobs", exchange -> respond(exchange, 200, fixture));
        server.start();

        var result = providerOn(server.getAddress().getPort()).extractBoard("monzo", "corr-1");

        assertThat(result.successful()).isTrue();
        assertThat(result.jobs()).hasSize(3);
        var first = result.jobs().get(0);
        // Fields asserted against the captured live payload:
        assertThat(first.title()).isEqualTo("Anaplan Support Analyst");
        assertThat(first.company()).isEqualTo("Monzo");
        assertThat(first.location()).isEqualTo("Cardiff, London or Remote (UK)");
        assertThat(first.externalJobId()).isEqualTo("8143930");
        assertThat(first.applicationUrl()).isEqualTo("https://job-boards.greenhouse.io/monzo/jobs/8143930");
        assertThat(first.sourceUrl()).isEqualTo("https://job-boards.greenhouse.io/monzo/jobs/8143930");
        assertThat(first.postedAt()).isNotNull();
        // content arrives entity-encoded HTML; the mapping unescapes and strips tags
        assertThat(first.description()).doesNotContain("&lt;").doesNotContain("<div").isNotBlank();
        // Not exposed by the API — never fabricated:
        assertThat(first.remoteType()).isNull();
        assertThat(first.salary()).isNull();
        assertThat(first.skills()).isEmpty();
        assertThat(first.confidence()).isEqualTo(1.0);
        assertThat(first.contentHash()).hasSize(64);
        assertThat(result.error()).isNull();
    }

    @Test
    void anEmptyBoardIsAValidOutcomeWithNoError() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/boards/emptyorg/jobs", exchange -> respond(exchange, 200,
                "{\"meta\":{\"total\":0},\"jobs\":[]}"));
        server.start();

        var result = providerOn(server.getAddress().getPort()).extractBoard("emptyorg", "corr-2");

        assertThat(result.error()).isNull();
        assertThat(result.jobs()).isEmpty();
    }

    @Test
    void malformedJsonIsReportedNotThrown() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/boards/x/jobs", exchange -> respond(exchange, 200, "<html>not json</html>"));
        server.start();

        var result = providerOn(server.getAddress().getPort()).extractBoard("x", "corr-3");
        assertThat(result.error()).isEqualTo("MALFORMED_RESPONSE");
        assertThat(result.jobs()).isEmpty();
    }

    @Test
    void aResponseWithoutAJobsArrayFailsAsIncomplete() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/boards/x/jobs", exchange -> respond(exchange, 200, "{\"unexpected\":true}"));
        server.start();

        var result = providerOn(server.getAddress().getPort()).extractBoard("x", "corr-4");
        assertThat(result.error()).isEqualTo("INCOMPLETE_EXTRACTION");
    }

    @Test
    void http404IsSurfaced() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/boards/missing/jobs", exchange -> respond(exchange, 404, "{\"error\":\"not found\"}"));
        server.start();

        assertThat(providerOn(server.getAddress().getPort()).extractBoard("missing", "c").error()).isEqualTo("HTTP_404");
    }

    @Test
    void http429IsSurfacedAsQuotaExhausted() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/boards/x/jobs", exchange -> respond(exchange, 429, "slow down"));
        server.start();

        assertThat(providerOn(server.getAddress().getPort()).extractBoard("x", "c").error()).isEqualTo("QUOTA_EXHAUSTED");
    }

    @Test
    void http5xxIsSurfaced() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/boards/x/jobs", exchange -> respond(exchange, 502, "bad gateway"));
        server.start();

        assertThat(providerOn(server.getAddress().getPort()).extractBoard("x", "c").error()).isEqualTo("HTTP_502");
    }

    @Test
    void networkFailureIsSurfacedAsTheExceptionClass() {
        // Port 1 on localhost: nothing listens there — a deterministic connection failure.
        var provider = new GreenhouseProvider("http://127.0.0.1:1");
        var result = provider.extractBoard("monzo", "c");
        assertThat(result.successful()).isFalse();
        assertThat(result.error()).isEqualTo("ConnectException");
        assertThat(result.jobs()).isEmpty();
    }

    @Test
    void entriesWithoutATitleAreSkippedButUsableOnesAreKept() throws Exception {
        String payload = """
                {"meta":{"total":2},"jobs":[
                 {"id":1,"title":"","absolute_url":"https://job-boards.example.org/a/1","location":{"name":"London"},"content":"x"},
                 {"id":2,"title":"Real Engineer","absolute_url":"https://job-boards.example.org/a/2","location":{"name":"Leeds"},"content":"&lt;p&gt;Role body&lt;/p&gt;"}
                ]}
                """;
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/boards/x/jobs", exchange -> respond(exchange, 200, payload));
        server.start();

        var result = providerOn(server.getAddress().getPort()).extractBoard("x", "c");
        assertThat(result.jobs()).hasSize(1);
        assertThat(result.jobs().get(0).title()).isEqualTo("Real Engineer");
        assertThat(result.jobs().get(0).externalJobId()).isEqualTo("2");
        assertThat(result.jobs().get(0).description()).isEqualTo("Role body");
    }

    @Test
    void invalidOrgTokensNeverReachUrlConstruction() {
        var provider = new GreenhouseProvider("http://localhost:1");
        for (String bad : new String[]{null, "", "../etc", "org/jobs", "org?x=1", "org name", "a".repeat(101)}) {
            assertThatThrownBy(() -> provider.boardJobsUrl(bad))
                    .as("org token %s must be rejected", bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        // A valid slug builds the documented public API URL with content inline.
        assertThat(provider.boardJobsUrl("monzo"))
                .isEqualTo("http://localhost:1/v1/boards/monzo/jobs?content=true");
        // The production default base is the verified public API host.
        assertThat(new GreenhouseProvider().boardJobsUrl("monzo"))
                .isEqualTo("https://boards-api.greenhouse.io/v1/boards/monzo/jobs?content=true");
    }

    @Test
    void providerIdentifiesAsPrimaryUnderTheExistingContract() {
        var provider = new GreenhouseProvider();
        assertThat(provider.providerId()).isEqualTo("greenhouse");
        assertThat(provider.role()).isEqualTo(ScraperProvider.ProviderRole.PRIMARY);
        assertThat(provider.enabled()).isTrue();
        assertThat(provider.state()).isEqualTo(ScraperProvider.ProviderState.AVAILABLE);
    }
}
