package com.personal.jobagent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.personal.jobagent.ats.AtsAdapterRegistry;
import com.personal.jobagent.coverletter.CoverLetterService;
import com.personal.jobagent.jobs.JobRepository;
import com.personal.jobagent.qa.ApplicationAnswerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * MCP get_application_timeline reads the same application_events.payload jsonb column as
 * GET /api/v1/applications/{id}/timeline; it used to hand the driver's PGobject to
 * ObjectMapper#writeValueAsString, which (after a silent toString fallback) produced a driver
 * wrapper instead of the event payload.
 */
class McpControllerTimelineJsonTest {

    private static final UUID APPLICATION_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final String PAYLOAD = "{\"event_key\":\"APPLICATION_SUBMITTED\",\"from\":\"APPLICATION_STARTED\"}";

    private ObjectMapper objectMapper;
    private McpController controller;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        controller = new McpController(Mockito.mock(JobRepository.class),
                Mockito.mock(CoverLetterService.class),
                Mockito.mock(ApplicationAnswerService.class),
                Mockito.mock(AtsAdapterRegistry.class),
                objectMapper);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getApplicationTimelineReturnsParsedPayload() throws Exception {
        JdbcTemplate db = Mockito.mock(JdbcTemplate.class);
        ResultSet rs = Mockito.mock(ResultSet.class);
        when(rs.getObject("id")).thenReturn(UUID.randomUUID());
        when(rs.getString("type")).thenReturn("STATUS_CHANGED");
        when(rs.getString("payload")).thenReturn(PAYLOAD);
        when(rs.getString("actor")).thenReturn("SYSTEM");
        when(rs.getObject("occurred_at")).thenReturn(Timestamp.from(Instant.parse("2026-09-23T09:00:00Z")));

        when(db.query(anyString(), Mockito.<RowMapper<Map<String, Object>>>any(), Mockito.<Object>any()))
                .thenAnswer(invocation -> {
                    RowMapper<Map<String, Object>> rowMapper = invocation.getArgument(1);
                    List<Map<String, Object>> mapped = new ArrayList<>();
                    mapped.add(rowMapper.mapRow(rs, 0));
                    return mapped;
                });
        ReflectionTestUtils.setField(controller, "db", db);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", "req-timeline");
        body.put("method", "tools/call");
        ObjectNode params = body.putObject("params");
        params.put("name", "get_application_timeline");
        params.putObject("arguments").put("application_id", APPLICATION_ID.toString());

        ResponseEntity<?> response = controller.dispatch(body, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String text = response.getBody().toString();
        assertThat(text).contains("payload");
        assertThat(text).contains("APPLICATION_SUBMITTED");
        assertThat(text).doesNotContain("jsonb");
    }
}
