package com.personal.jobagent.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.assertThat;

class DiscoveryProviderHttpTest {
    private HttpServer server;
    @AfterEach void stop(){if(server!=null)server.stop(0);}

    @Test void ownScraperReadsRobotsCanonicalJsonLdAndHashesContent() throws Exception {
        server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/robots.txt", exchange -> respond(exchange,200,"User-agent: *\nAllow: /"));
        server.createContext("/jobs", exchange -> respond(exchange,200,"""
                <html><head><link rel="canonical" href="http://localhost:%d/jobs/42">
                <script type="application/ld+json">{"@type":"JobPosting","title":"Platform Engineer","description":"Build Java services","hiringOrganization":{"name":"Acme"},"jobLocation":{"address":{"addressLocality":"London"}},"identifier":"42"}</script></head><body><h1>Platform Engineer</h1></body></html>
                """.formatted(server.getAddress().getPort())));
        server.start();
        MockEnvironment env=new MockEnvironment().withProperty("DISCOVERY_HTTP_RETRIES","0").withProperty("DISCOVERY_ROBOTS_REQUIRED","true");
        var result=new OurOwnWebScraper(env).extract(new ScraperProvider.DiscoveryRequest("http://localhost:"+server.getAddress().getPort()+"/jobs","CAREERS","c",null));
        assertThat(result.successful()).isTrue(); assertThat(result.jobs()).singleElement().satisfies(j->{assertThat(j.title()).isEqualTo("Platform Engineer");assertThat(j.company()).isEqualTo("Acme");assertThat(j.externalJobId()).isEqualTo("42");assertThat(j.contentHash()).hasSize(64);});
    }

    @Test void firecrawlUsesV2ScrapeContractAndMarksRateLimitExhausted() throws Exception {
        server=HttpServer.create(new InetSocketAddress(0),0); server.createContext("/v2/scrape", exchange -> {String response="{\"data\":{\"markdown\":\"# Software Engineer\\nJava platform role\",\"title\":\"Software Engineer\"}}";respond(exchange,200,response);});server.start();
        MockEnvironment env=new MockEnvironment().withProperty("FIRECRAWL_API_KEY","test-key").withProperty("FIRECRAWL_BASE_URL","http://localhost:"+server.getAddress().getPort()+"/v2").withProperty("DISCOVERY_HTTP_RETRIES","0");
        var provider=new FirecrawlProvider(env); var result=provider.extract(new ScraperProvider.DiscoveryRequest("http://example.test/job","CAREERS","c",null));
        assertThat(result.successful()).isTrue(); assertThat(result.jobs()).singleElement().extracting(ScraperProvider.ExtractedJob::title).isEqualTo("Software Engineer");
    }

    @Test void configuredProviderStopsOnRateLimitWithoutRetrying() throws Exception {
        server=HttpServer.create(new InetSocketAddress(0),0); server.createContext("/v2/scrape", exchange -> respond(exchange,429,"quota"));server.start();
        MockEnvironment env=new MockEnvironment().withProperty("FIRECRAWL_API_KEY","test-key").withProperty("FIRECRAWL_BASE_URL","http://localhost:"+server.getAddress().getPort()+"/v2").withProperty("DISCOVERY_HTTP_RETRIES","2");
        var provider=new FirecrawlProvider(env); var result=provider.extract(new ScraperProvider.DiscoveryRequest("http://example.test/job","CAREERS","c",null));
        assertThat(result.error()).isEqualTo("QUOTA_EXHAUSTED"); assertThat(provider.state()).isEqualTo(ScraperProvider.ProviderState.EXHAUSTED);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange e,int status,String body)throws java.io.IOException{byte[] bytes=body.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type", "text/html");e.sendResponseHeaders(status,bytes.length);try(var out=e.getResponseBody()){out.write(bytes);}}
}
