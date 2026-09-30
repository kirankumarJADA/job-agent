package com.personal.jobagent.discovery;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.core.io.ClassPathResource;
import java.io.IOException;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AshbyProviderTest {
    private HttpServer server;
    private String fixture;

    @BeforeEach
    void loadFixture() throws IOException {
        fixture = new String(new ClassPathResource("discovery/ashby-linear-job-board-fixture.json")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private AshbyProvider providerOn(int port) {
        return new AshbyProvider("http://127.0.0.1:" + port);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    @Test
    void mapsPublishedListingFieldsIntoExtractedJob() throws Exception {
        String payload = """
                {"jobs":[{
                  "id":"job-123","title":"Senior Engineer","department":"Product","team":"Platform",
                  "employmentType":"FullTime","location":"Europe","secondaryLocations":[{"location":"London"}],
                  "publishedAt":"2025-01-02T03:04:05.123+00:00","isListed":true,"isRemote":true,
                  "workplaceType":"Remote","descriptionHtml":"<p>Build &amp; ship</p>",
                  "descriptionPlain":"Build and ship dependable systems",
                  "jobUrl":"https://jobs.ashbyhq.com/acme/job-123",
                  "applyUrl":"https://jobs.ashbyhq.com/acme/job-123/application"
                }]}
                """;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/posting-api/job-board/acme", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            assertThat(exchange.getRequestHeaders().getFirst("Accept")).contains("application/json");
            respond(exchange, 200, payload);
        });
        server.start();

        var result = providerOn(server.getAddress().getPort()).extractBoard("acme", "correlation-1");

        assertThat(result.provider()).isEqualTo("ashby");
        assertThat(result.successful()).isTrue();
        assertThat(result.jobs()).hasSize(1);
        var job = result.jobs().getFirst();
        assertThat(job.title()).isEqualTo("Senior Engineer");
        assertThat(job.company()).isNull();
        assertThat(job.location()).isEqualTo("Europe; London");
        assertThat(job.remoteType()).isEqualTo("REMOTE");
        assertThat(job.salary()).isNull();
        assertThat(job.employmentType()).isEqualTo("FullTime");
        assertThat(job.description()).isEqualTo("Build and ship dependable systems");
        assertThat(job.applicationUrl()).isEqualTo("https://jobs.ashbyhq.com/acme/job-123/application");
        assertThat(job.sourceUrl()).isEqualTo("https://jobs.ashbyhq.com/acme/job-123");
        assertThat(job.postedAt()).isEqualTo(java.time.Instant.parse("2025-01-02T03:04:05.123Z"));
        assertThat(job.externalJobId()).isEqualTo("job-123");
        assertThat(job.contentHash()).hasSize(64);
        assertThat(job.confidence()).isEqualTo(1.0);
    }

    @Test
    void parsesTheTrimmedPublicLinearApiFixture() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/posting-api/job-board/linear", exchange -> respond(exchange, 200, fixture));
        server.start();

        var result = providerOn(server.getAddress().getPort()).extractBoard("linear", "linear-fixture");

        assertThat(result.successful()).isTrue();
        assertThat(result.jobs()).hasSize(1);
        var job = result.jobs().getFirst();
        assertThat(job.title()).isEqualTo("Senior / Staff Fullstack Engineer");
        assertThat(job.location()).isEqualTo("Europe");
        assertThat(job.remoteType()).isEqualTo("REMOTE");
        assertThat(job.sourceUrl()).isEqualTo("https://jobs.ashbyhq.com/linear/d3bc1ced-3ce4-4086-a050-555055dbb1ff");
    }

    @Test
    void fallsBackToHtmlDescriptionAndAcceptsMissingOptionalFields() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/posting-api/job-board/acme", exchange -> respond(exchange, 200,
                "{\"jobs\":[{\"title\":\"Support Engineer\",\"descriptionHtml\":\"<p>Help &amp; support</p>\",\"jobUrl\":\"https://jobs.ashbyhq.com/acme/support\"}]}"));
        server.start();

        var result = providerOn(server.getAddress().getPort()).extractBoard("acme", "c");
        var job = result.jobs().getFirst();
        assertThat(job.title()).isEqualTo("Support Engineer");
        assertThat(job.description()).isEqualTo("Help & support");
        assertThat(job.location()).isNull();
        assertThat(job.remoteType()).isNull();
        assertThat(job.employmentType()).isNull();
        assertThat(job.postedAt()).isNull();
        assertThat(job.applicationUrl()).isEqualTo(job.sourceUrl());
    }

    @Test
    void skipsExplicitlyUnlistedAndTitlelessJobs() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/posting-api/job-board/acme", exchange -> respond(exchange, 200, """
                {"jobs":[
                  {"id":"hidden","title":"Hidden","isListed":false},
                  {"id":"no-title","isListed":true},
                  {"id":"visible","title":"Visible Engineer","isListed":true,"descriptionPlain":"Role"}
                ]}
                """));
        server.start();

        var result = providerOn(server.getAddress().getPort()).extractBoard("acme", "c");
        assertThat(result.jobs()).singleElement().extracting(ScraperProvider.ExtractedJob::title).isEqualTo("Visible Engineer");
    }

    @Test
    void emptyJobsArrayIsSuccessfulEmptyResult() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/posting-api/job-board/empty", exchange -> respond(exchange, 200, "{\"jobs\":[]}"));
        server.start();
        var result = providerOn(server.getAddress().getPort()).extractBoard("empty", "c");
        assertThat(result.jobs()).isEmpty();
        assertThat(result.error()).isNull();
        assertThat(result.successful()).isFalse(); // Existing SPI defines success as non-empty extraction.
    }

    @Test
    void malformedAndUnexpectedPayloadsAreReportedWithoutThrowing() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/posting-api/job-board/bad", exchange -> respond(exchange, 200, "<html>not json</html>"));
        server.createContext("/posting-api/job-board/missing", exchange -> respond(exchange, 200, "{\"data\":[]}"));
        server.start();

        assertThat(providerOn(server.getAddress().getPort()).extractBoard("bad", "c").error()).isEqualTo("MALFORMED_RESPONSE");
        assertThat(providerOn(server.getAddress().getPort()).extractBoard("missing", "c").error()).isEqualTo("INCOMPLETE_EXTRACTION");
    }

    @Test
    void hostedBoardUrlAndSlugAreStrictlyValidated() {
        assertThat(AshbyProvider.boardNameFromHostedUrl("https://jobs.ashbyhq.com/linear" )).isEqualTo("linear");
        assertThat(AshbyProvider.boardNameFromIdentifier("linear")).isEqualTo("linear");
        assertThat(AshbyProvider.boardNameFromIdentifier("https://jobs.ashbyhq.com/linear")).isEqualTo("linear");
        assertThat(new AshbyProvider("https://api.ashbyhq.com").jobBoardUrl("linear"))
                .isEqualTo("https://api.ashbyhq.com/posting-api/job-board/linear");

        for (String invalid : new String[]{null, "", "../other", "board/jobs", "board?x=1", "board name", "a".repeat(101)}) {
            assertThatThrownBy(() -> new AshbyProvider("http://localhost").jobBoardUrl(invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String invalid : new String[]{"http://jobs.ashbyhq.com/linear", "https://evil.test/linear",
                "https://jobs.ashbyhq.com/a/b", "https://jobs.ashbyhq.com/linear?x=1",
                "https://jobs.ashbyhq.com/linear#fragment", "https://user@jobs.ashbyhq.com/linear"}) {
            assertThatThrownBy(() -> AshbyProvider.boardNameFromHostedUrl(invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void providerIsPrimaryPublicAndCredentialFree() {
        var provider = new AshbyProvider("https://api.ashbyhq.com");
        assertThat(provider.providerId()).isEqualTo("ashby");
        assertThat(provider.role()).isEqualTo(ScraperProvider.ProviderRole.PRIMARY);
        assertThat(provider.enabled()).isTrue();
        assertThat(provider.state()).isEqualTo(ScraperProvider.ProviderState.AVAILABLE);
    }
}
