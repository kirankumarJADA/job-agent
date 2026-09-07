package com.personal.jobagent.events;

/**
 * The swappable transport seam per architecture doc §A3.1: Phase 1 =
 * InProcessEventPublisher, Phase 2+ = a Redis Streams-backed
 * implementation, Kafka stays a drop-in replacement beyond that. Callers
 * (the OutboxDispatcher) depend on this interface only — swapping the
 * implementation later requires no change to dispatch/reconciliation logic.
 */
public interface EventPublisher {

    /**
     * Delivers the envelope to every registered handler that supports its
     * type. Must be idempotent per (event id, consumer) — implementations
     * check consumed_events before invoking a handler.
     *
     * @throws EventDeliveryException if any handler fails, so the caller
     *         (OutboxDispatcher) knows not to mark the outbox row published.
     */
    void publish(Envelope envelope) throws EventDeliveryException;
}
