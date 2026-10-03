package com.personal.jobagent.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack regression for Greenhouse discovery, on real PostgreSQL through
 * the real web layer: source row → POST /api/v1/discovery/run (no url —
 * kind-aware dispatch) → GreenhouseProvider against a stubbed board API →
 * existing normalization/deduplication/ingestion → jobs table → GET
 * /api/v1/jobs.
 *
 * The board API is a local stub serving the captured real response; no test
 * touches the public internet. The deduplication trio (INSERTED → TOUCHED →
 * UPDATED with repost_count) exercises the existing {@code
 * JobDiscoveryService.ingestJob} behavior unchanged.
 */
@SpringBootTest(properties = {"spring.flyway.placeholders.remove_seed_dev_account=false", "app.discovery.scheduler-enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GreenhouseDiscoveryIT {

    private static final String ORG = "fixture-board";
    private static final UUID SOURCE_ID = UUID.fromString("00000000-0000-7000-8000-00000000ff01");

    /** Mutable board payload — tests flip it to prove the update/repost path. */
    private static final AtomicReference<String> BOARD_PAYLOAD = new AtomicReference<>();
    private static HttpServer boardApi;

    static {
        try {
            boardApi = HttpServer.create(new java.net.InetSocketAddress(0), 0);
            boardApi.createContext("/", exchange -> {
                byte[] body = BOARD_PAYLOAD.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) { out.write(body); }
            });
            boardApi.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll static void stopStub() {
        if (boardApi != null) boardApi.stop(0);
        System.clearProperty("greenhouse.board-api-base");
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Point the connector at the in-process stub before the context (and
        // its orchestrator) is created.
        System.setProperty("greenhouse.board-api-base", "http://localhost:" + boardApi.getAddress().getPort());
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    private MockHttpSession session;

    @BeforeEach
    void seedSourceAndLogin() throws Exception {
        BOARD_PAYLOAD.compareAndSet(null, boardPayload(0));
        jdbc.update("""
                insert into job_sources (id, kind, org_identifier, display_name, capabilities, policy)
                values (?, 'GREENHOUSE', ?, 'Fixture Board (Greenhouse)', '{"discovery": true}', 'DISCOVERY_ONLY')
                on conflict (kind, org_identifier) do nothing
                """, SOURCE_ID, ORG);

        if (session == null) {
            MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"dev@example.local\",\"password\":\"DevPassword123!\"}"))
                    .andExpect(status().isOk())
                    .andReturn();
            session = (MockHttpSession) login.getRequest().getSession(false);
        }
    }

    private static String boardPayload(int variant) throws Exception {
        String fixture = new String(new ClassPathResource("discovery/greenhouse-board-fixture.json")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (variant == 0) {
            return fixture;
        }
        // Variant 1: same ids, changed first posting body — the update/repost case.
        var mapper = new ObjectMapper();
        var root = (ObjectNode) mapper.readTree(fixture);
        var jobs = mapper.createArrayNode();
        for (var job : root.path("jobs")) {
            var copy = (ObjectNode) job.deepCopy();
            if (copy.path("id").asLong() == 8143930) {
                copy.put("content", "&lt;p&gt;Updated role body for the repost variant " + variant + "&lt;/p&gt;");
            }
            jobs.add(copy);
        }
        root.set("jobs", jobs);
        return mapper.writeValueAsString(root);
    }

    @Test
    @Order(1)
    void firstRunIngestsEveryBoardPostingAsInsertedAndJobsAreListed() throws Exception {
        MvcResult run = mockMvc.perform(post("/api/v1/discovery/run?sourceId=" + SOURCE_ID)
                        .session(session).with(csrf()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(run.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("\"INSERTED\"").doesNotContain("\"TOUCHED\"");

        // Stored through the existing pipeline with the real API's fields:
        Integer count = jdbc.queryForObject(
                "select count(*) from jobs where source_id = ?", Integer.class, SOURCE_ID);
        assertThat(count).isEqualTo(3);
        Map<String, Object> row = jdbc.queryForMap("""
                select title, company_name_raw, location_raw, external_id, application_url, status
                from jobs where external_id = '8143930'
                """);
        assertThat(row.get("title")).isEqualTo("Anaplan Support Analyst");
        assertThat(row.get("company_name_raw")).isEqualTo("Monzo");
        assertThat(row.get("location_raw")).isEqualTo("Cardiff, London or Remote (UK)");
        assertThat(row.get("status")).isEqualTo("DISCOVERED");

        // The public feed lists the discovered jobs (search param is `q`):
        mockMvc.perform(get("/api/v1/jobs?q=Android").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    @Order(2)
    void secondRunDeduplicatesToTouchedWithoutNewRows() throws Exception {
        MvcResult run = mockMvc.perform(post("/api/v1/discovery/run?sourceId=" + SOURCE_ID)
                        .session(session).with(csrf()))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(run.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("\"TOUCHED\"").doesNotContain("\"INSERTED\"");
        Integer count = jdbc.queryForObject(
                "select count(*) from jobs where source_id = ?", Integer.class, SOURCE_ID);
        assertThat(count).isEqualTo(3);
    }

    @Test
    @Order(3)
    void changedPostingContentBumpsRepostCountAsUpdated() throws Exception {
        BOARD_PAYLOAD.set(boardPayload(1));

        MvcResult run = mockMvc.perform(post("/api/v1/discovery/run?sourceId=" + SOURCE_ID)
                        .session(session).with(csrf()))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(run.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("\"UPDATED\"");
        Integer reposts = jdbc.queryForObject(
                "select repost_count from jobs where external_id = '8143930' and source_id = ?",
                Integer.class, SOURCE_ID);
        assertThat(reposts).isEqualTo(1);
        // Unchanged postings stay TOUCHED, not UPDATED:
        Integer untouched = jdbc.queryForObject(
                "select count(*) from jobs where source_id = ? and repost_count = 0", Integer.class, SOURCE_ID);
        assertThat(untouched).isEqualTo(2);
    }

    @Test
    @Order(4)
    void runWithoutUrlOnANonGreenhouseSourceIsRefused() throws Exception {
        UUID searchApiSource = UUID.fromString("00000000-0000-7000-8000-00000000ff02");
        jdbc.update("""
                insert into job_sources (id, kind, org_identifier, display_name, capabilities, policy)
                values (?, 'SEARCH_API', 'no-such-board', 'Refused kind', '{"discovery": true}', 'DISCOVERY_ONLY')
                on conflict (kind, org_identifier) do nothing
                """, searchApiSource);

        mockMvc.perform(post("/api/v1/discovery/run?sourceId=" + searchApiSource)
                        .session(session).with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(5)
    void unknownSourceIdIsNotFound() throws Exception {
        mockMvc.perform(post("/api/v1/discovery/run?sourceId=" + UUID.randomUUID())
                        .session(session).with(csrf()))
                .andExpect(status().isNotFound());
    }

}
