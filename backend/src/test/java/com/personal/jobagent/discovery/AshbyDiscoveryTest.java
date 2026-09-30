package com.personal.jobagent.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class AshbyDiscoveryTest {
    private HttpServer server;

    @AfterEach void stop() { if (server != null) server.stop(0); }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    @Test
    void sourceKindDispatchUsesLocalAshbyApiAndExistingIngestionTail() throws Exception {
        String payload = """
                {"jobs":[
                  {"id":"ashby-101","title":"Platform Engineer","location":"London","employmentType":"FullTime","isListed":true,"isRemote":false,"workplaceType":"Hybrid","publishedAt":"2025-03-04T05:06:07Z","jobUrl":"https://jobs.ashbyhq.com/fixture/ashby-101","applyUrl":"https://jobs.ashbyhq.com/fixture/ashby-101/application","descriptionPlain":"Build platform services."},
                  {"id":"hidden","title":"Hidden role","isListed":false,"jobUrl":"https://jobs.ashbyhq.com/fixture/hidden","descriptionPlain":"Do not ingest."}
                ]}
                """;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/posting-api/job-board/fixture", exchange -> respond(exchange, 200, payload));
        server.start();

        ObjectMapper mapper = new ObjectMapper();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JobDiscoveryService ingestion = mock(JobDiscoveryService.class);
        JobDeduplicationService dedup = mock(JobDeduplicationService.class);
        DiscoveryTelemetry telemetry = mock(DiscoveryTelemetry.class);
        var ingestResult = new JobDiscoveryService.IngestResult(UUID.randomUUID(), "INSERTED", "dedup", "hash");
        when(ingestion.ingestJob(any())).thenReturn(ingestResult);
        var orchestrator = new DiscoveryOrchestrator(List.of(), ingestion, dedup,
                new ExtractionComparisonService(), telemetry, .20, .60, "",
                new GreenhouseProvider(), new AshbyProvider("http://127.0.0.1:" + server.getAddress().getPort()));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
                "kind", "ASHBY", "org_identifier", "fixture", "enabled", true)));
        var controller = new DiscoveryController(ingestion, orchestrator, mock(LinkedInDiscoveryService.class), jdbc);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        UUID sourceId = UUID.randomUUID();

        var response = mvc.perform(post("/api/v1/discovery/run")
                        .param("sourceId", sourceId.toString())
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains("correlationId", "INSERTED");
        var commandCaptor = org.mockito.ArgumentCaptor.forClass(JobDiscoveryService.IngestJobCommand.class);
        verify(ingestion, times(1)).ingestJob(commandCaptor.capture());
        var command = commandCaptor.getValue();
        assertThat(command.sourceId()).isEqualTo(sourceId);
        assertThat(command.externalId()).isEqualTo("ashby-101");
        assertThat(command.title()).isEqualTo("Platform Engineer");
        assertThat(command.locationRaw()).isEqualTo("London");
        assertThat(command.remoteType()).isEqualTo("HYBRID");
        assertThat(command.employmentType()).isEqualTo("FullTime");
        assertThat(command.applicationUrl()).isEqualTo("https://jobs.ashbyhq.com/fixture/ashby-101/application");
        assertThat(command.canonicalUrl()).isEqualTo("https://jobs.ashbyhq.com/fixture/ashby-101");
        assertThat(command.postedAt()).isEqualTo(java.time.Instant.parse("2025-03-04T05:06:07Z"));
        verify(dedup).recordObservation(eq(ingestResult.jobId()), any(AshbyProvider.class), any(), eq(1.0));
    }

    @Test
    void hostedUrlMustMatchPersistedAshbySourceBoard() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ObjectMapper mapper = new ObjectMapper();
        JobDiscoveryService ingestion = mock(JobDiscoveryService.class);
        var provider = new AshbyProvider("http://127.0.0.1:1");
        var orchestrator = new DiscoveryOrchestrator(List.of(), ingestion, mock(JobDeduplicationService.class),
                new ExtractionComparisonService(), mock(DiscoveryTelemetry.class), .20, .60, "",
                new GreenhouseProvider(), provider);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
                "kind", "ASHBY", "org_identifier", "fixture", "enabled", true)));
        var controller = new DiscoveryController(ingestion, orchestrator, mock(LinkedInDiscoveryService.class), jdbc);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        int mismatch = mvc.perform(post("/api/v1/discovery/run")
                        .param("sourceId", UUID.randomUUID().toString())
                        .param("url", "https://jobs.ashbyhq.com/different-board"))
                .andReturn().getResponse().getStatus();
        assertThat(mismatch).isEqualTo(400);
    }
}
