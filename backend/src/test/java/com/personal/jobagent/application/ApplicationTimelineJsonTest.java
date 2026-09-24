package com.personal.jobagent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.notifications.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

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
 * application_events.payload is jsonb. The timeline rows used to go straight from queryForList to
 * Jackson, so the payload surfaced as the driver's {"type":"jsonb","value":"…"} wrapper instead of
 * the event payload itself. Exercised through the service both GET
 * /api/v1/applications/{id}/timeline and the MCP get_application_timeline tool read from.
 */
class ApplicationTimelineJsonTest {

    private static final UUID APPLICATION_ID = UUID.fromString("88888888-8888-8888-8888-888888888888");
    private static final UUID PROFILE_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final String PAYLOAD = "{\"event_key\":\"APPLICATION_SUBMITTED\",\"from\":\"APPLICATION_STARTED\"}";

    private ObjectMapper mapper;
    private JdbcTemplate jdbc;
    private ApplicationStatusService service;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        jdbc = Mockito.mock(JdbcTemplate.class);
        service = new ApplicationStatusService(jdbc, mapper,
                Mockito.mock(AuditLogWriter.class), Mockito.mock(NotificationService.class));
        // timeline() is ownership-gated before it reads events (the events table has
        // no owner column of its own), so the ownership probe has to answer yes.
        when(jdbc.queryForObject(anyString(), Mockito.eq(Integer.class),
                org.mockito.ArgumentMatchers.<Object>any(), org.mockito.ArgumentMatchers.<Object>any()))
                .thenReturn(1);
    }

    @Test
    void timelineReturnsEventPayloadAsAJsonObject() throws Exception {
        whenMappedQueryReturns(timelineRow());

        List<Map<String, Object>> rows = service.timeline(PROFILE_ID, APPLICATION_ID);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("payload")).isEqualTo(Map.of(
                "event_key", "APPLICATION_SUBMITTED",
                "from", "APPLICATION_STARTED"));
        assertThat(rows.get(0).get("actor")).isEqualTo("SYSTEM");

        String json = mapper.writeValueAsString(rows);
        assertThat(json).contains("\"payload\":{\"event_key\":\"APPLICATION_SUBMITTED\"");
        assertThat(json).doesNotContain("\"type\":\"jsonb\"");
    }

    @Test
    void timelineReadsPayloadAsTextRatherThanTheDriverJsonbObject() throws Exception {
        whenMappedQueryReturns(timelineRow());

        service.timeline(PROFILE_ID, APPLICATION_ID);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        Mockito.verify(jdbc).query(sql.capture(), Mockito.<RowMapper<Map<String, Object>>>any(),
                org.mockito.ArgumentMatchers.<Object>any());
        assertThat(sql.getValue()).contains("payload::text as payload");
    }

    // ---------------------------------------------------------------- helpers

    @SuppressWarnings("unchecked")
    private void whenMappedQueryReturns(ResultSet... rows) {
        when(jdbc.query(anyString(), Mockito.<RowMapper<Map<String, Object>>>any(),
                org.mockito.ArgumentMatchers.<Object>any()))
                .thenAnswer(invocation -> {
                    RowMapper<Map<String, Object>> rowMapper = invocation.getArgument(1);
                    List<Map<String, Object>> mapped = new ArrayList<>();
                    for (int i = 0; i < rows.length; i++) {
                        mapped.add(rowMapper.mapRow(rows[i], i));
                    }
                    return mapped;
                });
    }

    private ResultSet timelineRow() throws SQLException {
        ResultSet rs = Mockito.mock(ResultSet.class);
        when(rs.getObject("id")).thenReturn(UUID.randomUUID());
        when(rs.getString("type")).thenReturn("STATUS_CHANGED");
        when(rs.getString("payload")).thenReturn(PAYLOAD);
        when(rs.getString("actor")).thenReturn("SYSTEM");
        when(rs.getObject("occurred_at")).thenReturn(Timestamp.from(Instant.parse("2026-09-23T09:00:00Z")));
        return rs;
    }

}
