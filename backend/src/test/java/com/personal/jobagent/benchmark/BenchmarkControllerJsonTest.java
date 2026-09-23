package com.personal.jobagent.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.common.JdbcConversions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.postgresql.util.PGobject;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * model_benchmark_runs.config and benchmark_results.metrics are jsonb. Returning them from
 * queryForList handed Jackson pgjdbc's PGobject, which renders as
 * {"type":"jsonb","value":"…"} instead of the actual object — a wrong-shape bug (not a 500,
 * since PGobject has plain getters) that the "config" / "metrics" fields exposed.
 */
class BenchmarkControllerJsonTest {

    private static final UUID RUN_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID MODEL_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final String RUN_CONFIG = "{\"modelKeys\":[\"simulated\",\"nim\"]}";
    private static final String CASE_METRICS = "{\"latency_ms\":120,\"json_valid\":true,\"retries\":0}";

    private ObjectMapper mapper;
    private JdbcTemplate jdbc;
    private BenchmarkController controller;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        jdbc = Mockito.mock(JdbcTemplate.class);
        controller = new BenchmarkController(Mockito.mock(BenchmarkRunner.class),
                Mockito.mock(PromotionService.class), jdbc, mapper);
    }

    @Test
    void listRunsReturnsConfigAsAJsonObject() throws Exception {
        stubMappedQueries();

        mockMvc().perform(get("/api/v1/benchmarks/runs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].id").value(RUN_ID.toString()))
                .andExpect(jsonPath("$[0].suite").value("job_classification@v1"))
                .andExpect(jsonPath("$[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$[0].config.modelKeys[0]").value("simulated"))
                .andExpect(jsonPath("$[0].config.modelKeys[1]").value("nim"))
                .andExpect(jsonPath("$[0].git_sha").value("abc1234"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("\"type\":\"jsonb\""))));
    }

    @Test
    void getRunReturnsConfigAndResultMetricsAsJsonObjects() throws Exception {
        stubMappedQueries();

        mockMvc().perform(get("/api/v1/benchmarks/runs/" + RUN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.config.modelKeys[0]").value("simulated"))
                .andExpect(jsonPath("$.results[0].metrics.latency_ms").value(120))
                .andExpect(jsonPath("$.results[0].metrics.json_valid").value(true))
                .andExpect(jsonPath("$.results[0].passed").value(false))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("\"type\":\"jsonb\""))));
    }

    @Test
    void jsonbColumnsAreParsedNotDriverWrapped() throws Exception {
        ResultSet rs = Mockito.mock(ResultSet.class);
        when(rs.getString("config")).thenReturn(RUN_CONFIG);
        when(rs.getString("metrics")).thenReturn(CASE_METRICS);

        assertThat(JdbcConversions.readJson(rs, "config", mapper))
                .isEqualTo(Map.of("modelKeys", List.of("simulated", "nim")));
        assertThat(JdbcConversions.readJson(rs, "metrics", mapper))
                .isEqualTo(Map.of("latency_ms", 120, "json_valid", true, "retries", 0));
    }

    @Test
    void rawDriverJsonbValuesRenderAsDriverWrappedJson() throws Exception {
        // Pin: this is the shape the endpoints used to emit for a jsonb column.
        PGobject rawDriverValue = new PGobject();
        rawDriverValue.setType("jsonb");
        rawDriverValue.setValue(RUN_CONFIG);

        String json = mapper.writeValueAsString(Map.of("config", rawDriverValue));

        assertThat(json).contains("\"type\":\"jsonb\"");
        assertThat(json).contains("modelKeys");
    }

    // ---------------------------------------------------------------- helpers

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    /**
     * Routes the controller's mapped queries to a run row or a result row by table name. Both the
     * two-argument ({@code query(sql, mapper)}) and varargs ({@code query(sql, mapper, id)})
     * overloads are stubbed because listRuns and getRun use one each.
     */
    @SuppressWarnings("unchecked")
    private void stubMappedQueries() {
        when(jdbc.query(anyString(), Mockito.<RowMapper<Map<String, Object>>>any()))
                .thenAnswer(this::mapSingleRow);
        when(jdbc.query(anyString(), Mockito.<RowMapper<Map<String, Object>>>any(), Mockito.<Object>any()))
                .thenAnswer(this::mapSingleRow);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mapSingleRow(org.mockito.invocation.InvocationOnMock invocation) throws Throwable {
        String sql = invocation.getArgument(0);
        RowMapper<Map<String, Object>> rowMapper = invocation.getArgument(1);
        ResultSet row = sql.contains("benchmark_results") ? resultRow() : runRow();
        return List.of(rowMapper.mapRow(row, 0));
    }

    private ResultSet runRow() throws SQLException {
        ResultSet rs = Mockito.mock(ResultSet.class);
        when(rs.getObject("id")).thenReturn(RUN_ID);
        when(rs.getString("suite")).thenReturn("job_classification@v1");
        when(rs.getString("task_type")).thenReturn("JOB_CLASSIFICATION");
        when(rs.getString("config")).thenReturn(RUN_CONFIG);
        when(rs.getString("status")).thenReturn("COMPLETED");
        when(rs.getObject("started_at")).thenReturn(Timestamp.from(Instant.parse("2026-09-23T10:00:00Z")));
        when(rs.getObject("finished_at")).thenReturn(Timestamp.from(Instant.parse("2026-09-23T10:05:00Z")));
        when(rs.getString("git_sha")).thenReturn("abc1234");
        when(rs.getString("notes")).thenReturn(null);
        return rs;
    }

    private ResultSet resultRow() throws SQLException {
        ResultSet rs = Mockito.mock(ResultSet.class);
        when(rs.getObject("id")).thenReturn(UUID.randomUUID());
        when(rs.getObject("run_id")).thenReturn(RUN_ID);
        when(rs.getObject("model_id")).thenReturn(MODEL_ID);
        when(rs.getObject("case_index")).thenReturn(0);
        when(rs.getObject("passed")).thenReturn(false);
        when(rs.getBigDecimal("score")).thenReturn(BigDecimal.ZERO);
        when(rs.getString("metrics")).thenReturn(CASE_METRICS);
        return rs;
    }
}
