package com.personal.jobagent.discovery;

import com.personal.jobagent.discovery.platform.BoardAdapter;
import com.personal.jobagent.discovery.platform.BoardConfigException;
import com.personal.jobagent.discovery.platform.FailureCategory;
import com.personal.jobagent.discovery.platform.FixtureHttp;
import com.personal.jobagent.discovery.platform.NormalizedJob;
import com.personal.jobagent.discovery.platform.adapters.ProviderBackedAdapter;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Greenhouse and Ashby keep their pre-8.2.5 providers (and those providers'
 * own tests); these adapters only put them behind the common contract. This
 * test pins that contract: complete listings, normalised fields, recognition
 * and failure categories.
 */
class ProviderBackedAdapterTest {

    private HttpServer server;
    private final AtomicInteger status = new AtomicInteger(200);

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private String serve(String path, String classpathFixture) throws Exception {
        String body = new String(new ClassPathResource(classpathFixture).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            int code = status.get();
            exchange.sendResponseHeaders(code, code == 200 ? bytes.length : -1);
            if (code == 200) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void greenhouseBoardIsACompleteListingOfNormalisedJobs() throws Exception {
        String base = serve("/v1/boards/monzo/jobs", "discovery/greenhouse-board-fixture.json");
        var adapter = new ProviderBackedAdapter.Greenhouse(new GreenhouseProvider(base));
        var result = adapter.list(adapter.parseIdentifier("monzo"), new FixtureHttp().context());
        assertThat(result.complete()).isTrue();
        assertThat(result.entries()).hasSize(3).allMatch(e -> !e.needsDetail());
        NormalizedJob job = result.entries().stream().map(BoardAdapter.ListingEntry::job)
                .filter(j -> "8143930".equals(j.externalId())).findFirst().orElseThrow();
        assertThat(job.title()).isEqualTo("Anaplan Support Analyst");
        assertThat(job.employerName()).isEqualTo("Monzo");
        assertThat(job.applicationUrl()).contains("8143930");
        assertThat(job.salaryMin()).isNull();
    }

    @Test
    void greenhouseFailuresAreCategorisedAndNeverComplete() throws Exception {
        String base = serve("/v1/boards/monzo/jobs", "discovery/greenhouse-board-fixture.json");
        var adapter = new ProviderBackedAdapter.Greenhouse(new GreenhouseProvider(base));
        status.set(404);
        var notFound = adapter.list(adapter.parseIdentifier("monzo"), new FixtureHttp().context());
        assertThat(notFound.complete()).isFalse();
        assertThat(notFound.failure()).isEqualTo(FailureCategory.NOT_FOUND);
        status.set(429);
        assertThat(adapter.list(adapter.parseIdentifier("monzo"), new FixtureHttp().context()).failure())
                .isEqualTo(FailureCategory.RATE_LIMITED);
        status.set(503);
        assertThat(adapter.list(adapter.parseIdentifier("monzo"), new FixtureHttp().context()).failure())
                .isEqualTo(FailureCategory.UPSTREAM_ERROR);
    }

    @Test
    void greenhouseRecognitionCoversHostedEmbeddedAndApiUrls() {
        var adapter = new ProviderBackedAdapter.Greenhouse(new GreenhouseProvider("http://unused"));
        assertThat(adapter.recognize(URI.create("https://boards.greenhouse.io/stripe")).map(BoardAdapter.BoardRef::identifier)).contains("stripe");
        assertThat(adapter.recognize(URI.create("https://job-boards.eu.greenhouse.io/monzo/jobs/1")).map(BoardAdapter.BoardRef::identifier)).contains("monzo");
        assertThat(adapter.recognize(URI.create("https://boards.greenhouse.io/embed/job_board?for=acme")).map(BoardAdapter.BoardRef::identifier)).contains("acme");
        assertThat(adapter.recognize(URI.create("https://jobs.lever.co/acme"))).isEmpty();
        assertThatThrownBy(() -> adapter.parseIdentifier("bad token!")).isInstanceOf(BoardConfigException.class);
    }

    @Test
    void ashbyBoardIsACompleteListingAndKeepsJobAndApplyUrlsDistinct() throws Exception {
        String base = serve("/posting-api/job-board/linear", "discovery/ashby-linear-job-board-fixture.json");
        var adapter = new ProviderBackedAdapter.Ashby(new AshbyProvider(base));
        var result = adapter.list(adapter.parseIdentifier("linear"), new FixtureHttp().context());
        assertThat(result.complete()).isTrue();
        assertThat(result.entries()).isNotEmpty();
        NormalizedJob job = result.entries().get(0).job();
        assertThat(job.sourceUrl()).startsWith("https://jobs.ashbyhq.com/");
        assertThat(job.applicationUrl()).startsWith("https://jobs.ashbyhq.com/");
        assertThat(adapter.recognize(URI.create("https://jobs.ashbyhq.com/linear/abc")).map(BoardAdapter.BoardRef::identifier)).contains("linear");
        assertThatThrownBy(() -> adapter.parseIdentifier("")).isInstanceOf(BoardConfigException.class);
    }
}
