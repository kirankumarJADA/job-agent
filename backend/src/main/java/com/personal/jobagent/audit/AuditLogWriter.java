package com.personal.jobagent.audit;

/**
 * Port for writing to the append-only audit_logs table. Every module that
 * needs an audit trail (auth in P1-b, profile/preferences in P1-d, the
 * purge endpoint) depends on this interface, not on the JDBC implementation
 * directly — matches the architecture's port/adapter convention so this can
 * be swapped later (e.g. if audit writes ever need to go through the
 * outbox instead of a direct write — see docs/planning/architectural-conflict-review.md
 * for why direct writes were chosen for Phase 1).
 */
public interface AuditLogWriter {

    void write(AuditEntry entry);

    /**
     * Read helper: has this actor ever performed this action? Feature 8's
     * auth notification path uses it to distinguish a first successful
     * session (signup analogue) from subsequent ones. Implemented on the
     * JDBC adapter (it already owns the template); must never be treated
     * as part of the append-only contract — reads only.
     */
    boolean existsActionForActor(String action, String actor);
}
