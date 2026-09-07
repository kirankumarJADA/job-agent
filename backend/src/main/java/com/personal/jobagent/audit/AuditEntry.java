package com.personal.jobagent.audit;

import java.util.UUID;

/**
 * One row's worth of data for the append-only audit_logs table (V001).
 * before/afterState are arbitrary serializable objects (typically a Map or
 * a DTO) — the writer serializes them to JSON itself, callers don't deal
 * with JSON strings directly.
 *
 * entityType/entityId/beforeState/afterState/ip are all nullable: a
 * LOGIN_SUCCESS/LOGIN_FAILURE/LOGOUT row has none of these (there's no
 * "entity" being changed), while PROFILE_UPDATED or DATA_PURGE_REQUESTED
 * rows populate them.
 */
public record AuditEntry(
        String actor,
        String action,
        String entityType,
        UUID entityId,
        Object beforeState,
        Object afterState,
        String ip,
        UUID correlationId
) {

    /** Convenience for the common case: no entity, no before/after state (login/logout). */
    public static AuditEntry simple(String actor, String action, String ip, UUID correlationId) {
        return new AuditEntry(actor, action, null, null, null, null, ip, correlationId);
    }
}
