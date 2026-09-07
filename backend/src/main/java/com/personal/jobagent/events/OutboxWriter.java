package com.personal.jobagent.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.common.UuidV7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Writes an outbox_events row. Deliberately has NO @Transactional of its
 * own — per architecture doc §A3.1/§C4, the whole point of the outbox
 * pattern is that this write happens in the SAME transaction as the
 * business row it's describing, so callers must invoke this from within
 * their own @Transactional method. If this had its own transaction
 * boundary, the atomicity guarantee the outbox exists to provide would be
 * broken.
 */
@Component
public class OutboxWriter {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public OutboxWriter(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * @return the generated event id (== outbox_events.id), useful as the
     *         causationId for any event this one's handler goes on to emit.
     */
    public UUID append(String aggregateType, UUID aggregateId, String eventType,
                        Object payload, UUID correlationId, UUID causationId) {
        UUID eventId = UuidV7.generate();
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize event payload for type=" + eventType, e);
        }

        jdbcTemplate.update("""
                        insert into outbox_events
                            (sequence_id, id, aggregate_type, aggregate_id, event_type, schema_version,
                             payload, correlation_id, causation_id, created_at, published_at)
                        values (default, ?, ?, ?, ?, 1, ?::jsonb, ?, ?, now(), null)
                        """,
                eventId, aggregateType, aggregateId, eventType, payloadJson, correlationId, causationId);

        return eventId;
    }
}
