package com.personal.jobagent.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Event envelope shape per architecture doc §C4. Additive-only evolution:
 * a breaking change to an event's payload shape gets a new `type` (e.g.
 * `_v2`), never a mutation of what an existing type means.
 */
public record Envelope(
        UUID id,
        String type,
        int schemaVersion,
        Instant occurredAt,
        String aggregateType,
        UUID aggregateId,
        UUID correlationId,
        UUID causationId,
        Object payload
) {
}
