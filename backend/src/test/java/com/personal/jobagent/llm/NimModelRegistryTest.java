package com.personal.jobagent.llm;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.TypeInfo;
import org.postgresql.jdbc.PgArray;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Production regression: GET /api/v1/models/nim/registry returned HTTP 500 while
 * GET /api/v1/models returned 200. The old implementation used
 * JdbcTemplate#queryForList, so the {@code capabilities text[]} column reached Jackson as a
 * pgjdbc {@link PgArray} (a {@code java.sql.Array}) and {@code notes jsonb} as a
 * {@code PGobject}. Jackson serialises those as beans and calls their getters — including
 * {@code PgArray#getResultSet()}, which builds a live driver ResultSet out of driver internals
 * — so response rendering failed with a JsonMappingException. {@code /models} escaped it only
 * because it selects no array column and casts {@code notes::text}.
 *
 * <p>These tests pin both halves: driver array values really are unserialisable, and the
 * registry now maps every column to plain JSON values before the message converter sees them.
 */
class NimModelRegistryTest {

    private static final String NOTES_JSON =
            "{\"availability\":\"UNKNOWN\",\"free_endpoint\":false,\"deprecated\":false,\"source\":\"NVIDIA_CATALOGUE\"}";

    private ObjectMapper mapper;
    private JdbcTemplate db;
    private NimModelRegistry registry;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        db = Mockito.mock(JdbcTemplate.class);
        registry = new NimModelRegistry(mapper, db, "https://integrate.api.nvidia.com", "");
    }

    // ---------------------------------------------------------------- root-cause pins

    @Test
    void jacksonTreatsTheDriverArrayTypeAsABeanNotAsAJsonArray() {
        List<String> properties = mapper.getSerializationConfig()
                .introspect(mapper.getTypeFactory().constructType(PgArray.class))
                .findProperties().stream()
                .map(BeanPropertyDefinition::getName)
                .toList();

        // 'resultSet' is the killer: Jackson calls PgArray#getResultSet(), which hands out a
        // live JDBC ResultSet built from driver internals instead of a plain JSON value.
        assertThat(properties).contains("array", "resultSet", "baseType", "baseTypeName");
    }

    @Test
    void rawDriverArrayValuesCannotBeRenderedAsJson() throws Exception {
        PgArray rawDriverValue = pgArray("{reasoning}");

        Map<String, Object> legacyRow = new LinkedHashMap<>();
        legacyRow.put("provider_id", "nim");
        legacyRow.put("capabilities", rawDriverValue);

        assertThatThrownBy(() -> mapper.writeValueAsString(legacyRow))
                .as("a raw java.sql.Array must never reach the HTTP message converter")
                .isInstanceOf(JsonMappingException.class)
                .hasMessageContaining("capabilities");
    }

    // ---------------------------------------------------------------- registry mapping

    @Test
    void registryRowsMapsDriverTypesToPlainJsonValues() throws Exception {
        givenRows(nimRow("nvidia/nemotron-3.5-lightning-30b-a3b", false, 128000,
                sqlArray("reasoning", "tool_use"), NOTES_JSON));

        List<Map<String, Object>> rows = registry.registryRows();

        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row).containsOnlyKeys("provider_id", "model_key", "display_name",
                "context_window", "capabilities", "enabled", "notes");
        assertThat(row.get("provider_id")).isEqualTo("nim");
        assertThat(row.get("model_key")).isEqualTo("nvidia/nemotron-3.5-lightning-30b-a3b");
        assertThat(row.get("context_window")).isEqualTo(128000);
        assertThat(row.get("enabled")).isEqualTo(false);
        assertThat(row.get("capabilities")).isEqualTo(List.of("reasoning", "tool_use"));
        assertThat(row.get("notes")).isEqualTo(Map.of(
                "availability", "UNKNOWN",
                "free_endpoint", false,
                "deprecated", false,
                "source", "NVIDIA_CATALOGUE"));

        assertThat(row.values())
                .as("no JDBC driver types may leak into the response payload")
                .noneMatch(value -> value instanceof Array
                        || value instanceof org.postgresql.util.PGobject);

        String json = mapper.writeValueAsString(rows);
        assertThat(json).contains("\"capabilities\":[\"reasoning\",\"tool_use\"]");
        assertThat(json).contains("\"enabled\":false");
    }

    @Test
    void registryRowsConvertsARealDriverArrayIntoJsonSafeStrings() throws Exception {
        givenRows(nimRow("z-ai/glm-5-3", false, null, pgArray("{reasoning}"), "{}"));

        Object capabilities = registry.registryRows().get(0).get("capabilities");

        assertThat(capabilities).isInstanceOf(List.class);
        assertThat((List<?>) capabilities).allMatch(String.class::isInstance);
        assertThat(mapper.writeValueAsString(capabilities)).isEqualTo("[\"reasoning\"]");
    }

    @Test
    void registryRowsOnlySelectsNimModels() {
        givenRows();

        registry.registryRows();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        Mockito.verify(db).query(sql.capture(), Mockito.<RowMapper<Map<String, Object>>>any());
        assertThat(sql.getValue())
                .contains("from llm_models")
                .contains("provider_id='nim'")
                .contains("order by model_key");
    }

    @Test
    void nullCapabilitiesAndNotesColumnsBecomeEmptyContainers() throws Exception {
        givenRows(nimRow("z-ai/glm-5-3", true, null, null, null));

        Map<String, Object> row = registry.registryRows().get(0);

        assertThat(row.get("capabilities")).isEqualTo(List.of());
        assertThat(row.get("notes")).isEqualTo(Map.of());
        assertThat(row.get("context_window")).isNull();
        assertThat(row.get("enabled")).isEqualTo(true);

        String json = mapper.writeValueAsString(row);
        assertThat(json).contains("\"capabilities\":[]").contains("\"notes\":{}");
    }

    // ---------------------------------------------------------------- endpoint output

    @Test
    void registryEndpointReturnsMappedRowsAsJson() throws Exception {
        givenRows(nimRow("nvidia/nemotron-3.5-lightning-30b-a3b", false, 128000,
                sqlArray("reasoning", "tool_use"), NOTES_JSON));

        mockMvc().perform(get("/api/v1/models/nim/registry"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].provider_id").value("nim"))
                .andExpect(jsonPath("$[0].model_key").value("nvidia/nemotron-3.5-lightning-30b-a3b"))
                .andExpect(jsonPath("$[0].display_name").value("Display nvidia/nemotron-3.5-lightning-30b-a3b"))
                .andExpect(jsonPath("$[0].context_window").value(128000))
                .andExpect(jsonPath("$[0].capabilities[0]").value("reasoning"))
                .andExpect(jsonPath("$[0].capabilities[1]").value("tool_use"))
                .andExpect(jsonPath("$[0].enabled").value(false))
                .andExpect(jsonPath("$[0].notes.availability").value("UNKNOWN"))
                .andExpect(jsonPath("$[0].notes.free_endpoint").value(false));
    }

    @Test
    void registryEndpointReturnsEmptyArrayWhenNoNimModelsAreSeeded() throws Exception {
        givenRows();

        assertThat(registry.registryRows()).isEmpty();
        mockMvc().perform(get("/api/v1/models/nim/registry"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    // ---------------------------------------------------------------- helpers

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(new LlmAdminController(db, registry)).build();
    }

    /** Stubs {@code JdbcTemplate#query(String, RowMapper)} to run the mapper over {@code rows}. */
    private void givenRows(ResultSet... rows) {
        when(db.query(anyString(), Mockito.<RowMapper<Map<String, Object>>>any())).thenAnswer(invocation -> {
            RowMapper<Map<String, Object>> rowMapper = invocation.getArgument(1);
            List<Map<String, Object>> mapped = new ArrayList<>();
            for (int i = 0; i < rows.length; i++) {
                mapped.add(rowMapper.mapRow(rows[i], i));
            }
            return mapped;
        });
    }

    private ResultSet nimRow(String modelKey, boolean enabled, Integer contextWindow,
                             Array capabilities, String notes) throws SQLException {
        ResultSet rs = Mockito.mock(ResultSet.class);
        when(rs.getString("provider_id")).thenReturn("nim");
        when(rs.getString("model_key")).thenReturn(modelKey);
        when(rs.getString("display_name")).thenReturn("Display " + modelKey);
        when(rs.getObject("context_window")).thenReturn(contextWindow);
        when(rs.getArray("capabilities")).thenReturn(capabilities);
        when(rs.getBoolean("enabled")).thenReturn(enabled);
        when(rs.getString("notes")).thenReturn(notes);
        return rs;
    }

    /** A {@code java.sql.Array} standing in for what the driver returns for a text[] column. */
    private static Array sqlArray(String... elements) throws SQLException {
        Array array = Mockito.mock(Array.class);
        when(array.getArray()).thenReturn(elements);
        return array;
    }

    /**
     * A real pgjdbc {@link PgArray}, stubbed only enough for the driver's own decoding to resolve
     * the element type to {@code text} (oid 25) — i.e. the exact class that used to be handed to
     * Jackson by {@code queryForList}. (A mocked connection cannot reproduce the server-side
     * literal parsing byte-for-byte, so assertions on its contents stay structural.)
     */
    private static PgArray pgArray(String literal) throws SQLException {
        BaseConnection connection = Mockito.mock(BaseConnection.class, Mockito.RETURNS_DEEP_STUBS);
        TypeInfo typeInfo = connection.getTypeInfo();
        when(typeInfo.getPGArrayElement(anyInt())).thenReturn(25);
        when(typeInfo.getPGType(25)).thenReturn("text");
        when(typeInfo.getSQLType("text")).thenReturn(Types.VARCHAR);
        return new PgArray(connection, 1009, literal);
    }
}
