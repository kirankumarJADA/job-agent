package com.personal.jobagent.events;

/**
 * Implemented by consumers (one bean per logical consumer, per the §C4
 * event registry — e.g. "normalizer", "filter-engine"). consumerName()
 * must be stable across deploys — it's the key used in consumed_events for
 * idempotency, so renaming it silently loses the "already processed"
 * record for every event that consumer has seen.
 */
public interface EventHandler {

    /** Stable identifier for this consumer, e.g. "normalizer". */
    String consumerName();

    boolean supports(String eventType);

    void handle(Envelope envelope);
}
