package com.personal.jobagent.automation;

/**
 * Phase 10: how completely one dimension of {@link AtsCapabilities} is
 * actually implemented, verified against the real adapter/worker code — never
 * against a class name, a docstring, or a hostname match alone.
 */
public enum AtsCapabilityLevel {
    /** Not implemented at all for this platform. */
    NONE,
    /** Implemented for some cases only (see the capability's own note for which ones). */
    PARTIAL,
    /** Fully implemented and reachable through the real, reviewed pipeline. */
    FULL
}
