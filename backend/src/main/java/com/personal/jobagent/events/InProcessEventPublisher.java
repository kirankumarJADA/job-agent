package com.personal.jobagent.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Phase 1 transport: synchronous in-process dispatch to registered
 * EventHandler beans, per architecture doc §A3.1. Idempotency is enforced
 * per (event id, consumer) via the consumed_events table — a handler that
 * already processed this exact event id is skipped, not re-invoked, so a
 * redelivered event (e.g. after a crash mid-dispatch) can't cause duplicate
 * side effects.
 *
 * If ANY handler throws, this method throws EventDeliveryException without
 * marking already-succeeded handlers as unconsumed — their consumed_events
 * rows stay, so a retry only re-attempts the handlers that actually failed.
 */
@Component
public class InProcessEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(InProcessEventPublisher.class);

    private final List<EventHandler> handlers;
    private final JdbcTemplate jdbcTemplate;

    public InProcessEventPublisher(List<EventHandler> handlers, JdbcTemplate jdbcTemplate) {
        this.handlers = handlers;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void publish(Envelope envelope) throws EventDeliveryException {
        for (EventHandler handler : handlers) {
            if (!handler.supports(envelope.type())) {
                continue;
            }
            if (alreadyConsumed(envelope.id(), handler.consumerName())) {
                log.debug("Skipping already-consumed event {} for consumer {}", envelope.id(), handler.consumerName());
                continue;
            }
            try {
                handler.handle(envelope);
                markConsumed(envelope.id(), handler.consumerName());
            } catch (Exception e) {
                throw new EventDeliveryException(
                        "Handler " + handler.consumerName() + " failed for event " + envelope.id(), e);
            }
        }
    }

    private boolean alreadyConsumed(java.util.UUID eventId, String consumer) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from consumed_events where event_id = ? and consumer = ?",
                Integer.class, eventId, consumer);
        return count != null && count > 0;
    }

    private void markConsumed(java.util.UUID eventId, String consumer) {
        jdbcTemplate.update(
                "insert into consumed_events (event_id, consumer, processed_at) values (?, ?, ?) "
                        + "on conflict (event_id, consumer) do nothing",
                eventId, consumer, Timestamp.from(Instant.now()));
    }
}
