package com.personal.jobagent.discovery.plan;

/**
 * Thrown when an execution lease check fails at the database boundary,
 * indicating that the worker lost ownership, the lease expired, or
 * another worker took over execution.
 *
 * <p>Triggering this exception rolls back any ongoing transaction,
 * guaranteeing that no side-effects (job inserts, matches, or outbox events)
 * are committed by a stale worker.</p>
 */
public class ExecutionFencedException extends RuntimeException {

    public ExecutionFencedException(String message) {
        super(message);
    }

    public ExecutionFencedException(String message, Throwable cause) {
        super(message, cause);
    }
}
