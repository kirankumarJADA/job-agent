package com.personal.jobagent.automation;

import java.util.List;
import java.util.Map;

/**
 * The execution package could not be built because the exact document
 * versions for this application cannot be selected fail-closed (missing
 * review, integrity failure, legacy/stale validation, digest mismatch).
 * Carries the actionable blocker list; never carries a substitute document.
 */
public class ExecutionPackageBlockedException extends RuntimeException {

    private final List<Map<String, Object>> blockers;

    public ExecutionPackageBlockedException(String message, List<Map<String, Object>> blockers) {
        super(message);
        this.blockers = List.copyOf(blockers);
    }

    public List<Map<String, Object>> blockers() {
        return blockers;
    }
}
