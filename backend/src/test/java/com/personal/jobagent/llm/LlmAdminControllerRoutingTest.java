package com.personal.jobagent.llm;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.common.JdbcConversions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.TypeInfo;
import org.postgresql.jdbc.PgArray;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * routing_policies.fallback_model_ids is uuid[]. Both endpoints that return a routing_policies
 * row (GET /api/v1/routing and the PUT /api/v1/routing/{taskType} response) used raw
 * queryForList/queryForMap, so pgjdbc's PgArray went straight to Jackson — the same driver-object
 * serialization that 500'd /api/v1/models/nim/registry.
 */
class LlmAdminControllerRoutingTest {

    private static final UUID PRIMARY = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID FALLBACK = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private ObjectMapper mapper;
    private JdbcTemplate jdbc;
    private LlmAdminController controller;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        jdbc = Mockito.mock(JdbcTemplate.class);
        controller = new LlmAdminController(jdbc, Mockito.mock(NimModelRegistry.class));
    }

    @Test
    void listRoutingReturnsUuidArraysAsJsonArrays() throws Exception {
        Array fallbackIds = sqlArray(PRIMARY, FALLBACK);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(routingRow(fallbackIds)));

        mockMvc().perform(get("/api/v1/routing"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].task_type").value("JOB_CLASSIFICATION"))
                .andExpect(jsonPath("$[0].primary_model_id").value(PRIMARY.toString()))
                .andExpect(jsonPath("$[0].fallback_model_ids[0]").value(PRIMARY.toString()))
                .andExpect(jsonPath("$[0].fallback_model_ids[1]").value(FALLBACK.toString()))
                .andExpect(jsonPath("$[0].basis").value("MANUAL"));
    }

    @Test
    void routingUpdateResponseReturnsUuidArraysAsJsonArrays() throws Exception {
        Array fallbackIds = sqlArray(FALLBACK);
        when(jdbc.queryForMap(anyString(), any())).thenReturn(routingRow(fallbackIds));

        mockMvc().perform(put("/api/v1/routing/JOB_CLASSIFICATION")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"primaryModelId":"11111111-1111-1111-1111-111111111111",
                                 "fallbackModelIds":["22222222-2222-2222-2222-222222222222"],
                                 "rationale":"manual pin"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task_type").value("JOB_CLASSIFICATION"))
                .andExpect(jsonPath("$.fallback_model_ids[0]").value(FALLBACK.toString()));
    }

    @Test
    void emptyUuidArrayBecomesAnEmptyJsonArray() throws Exception {
        Array emptyIds = sqlArray();
        when(jdbc.queryForList(anyString())).thenReturn(List.of(routingRow(emptyIds)));

        mockMvc().perform(get("/api/v1/routing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fallback_model_ids").isArray())
                .andExpect(jsonPath("$[0].fallback_model_ids").isEmpty());
    }

    @Test
    void rawDriverArrayRowsStillCannotBeRenderedAsJson() throws Exception {
        // Pin: this is exactly what the endpoints used to return, and it is why they failed.
        Map<String, Object> rawRow = routingRow(pgArray("{11111111-1111-1111-1111-111111111111}"));

        assertThatThrownBy(() -> mapper.writeValueAsString(rawRow))
                .isInstanceOf(JsonMappingException.class);

        String json = mapper.writeValueAsString(JdbcConversions.jsonSafeRows(List.of(rawRow)));
        assertThat(json).contains("\"fallback_model_ids\":[");
    }

    // ---------------------------------------------------------------- helpers

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static Map<String, Object> routingRow(Array fallbackModelIds) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("task_type", "JOB_CLASSIFICATION");
        row.put("primary_model_id", PRIMARY);
        row.put("fallback_model_ids", fallbackModelIds);
        row.put("basis", "MANUAL");
        row.put("based_on_run_id", null);
        row.put("rationale", "manual pin");
        row.put("updated_at", Timestamp.from(Instant.parse("2026-09-23T12:00:00Z")));
        return row;
    }

    /** The value the driver hands back for a uuid[] column. */
    private static Array sqlArray(UUID... ids) throws SQLException {
        Array array = Mockito.mock(Array.class);
        when(array.getArray()).thenReturn(ids);
        return array;
    }

    /** A real pgjdbc PgArray — the class that used to reach Jackson. */
    private static PgArray pgArray(String literal) throws SQLException {
        BaseConnection connection = Mockito.mock(BaseConnection.class, Mockito.RETURNS_DEEP_STUBS);
        TypeInfo typeInfo = connection.getTypeInfo();
        when(typeInfo.getPGArrayElement(anyInt())).thenReturn(2950);
        when(typeInfo.getPGType(2950)).thenReturn("uuid");
        when(typeInfo.getSQLType("uuid")).thenReturn(Types.OTHER);
        return new PgArray(connection, 2951, literal);
    }
}
