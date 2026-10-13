package com.personal.jobagent.discovery.platform;

/** Why a fetch or a run did not complete. Persisted in source health; never contains secrets. */
public enum FailureCategory {
    /** The board identifier / URL is invalid or names a board that does not exist. */
    CONFIGURATION,
    /** The address resolved to a private, loopback, link-local or metadata network. */
    UNSAFE_ADDRESS,
    /** HTTP 429 or an explicit throttling signal after the retry budget. */
    RATE_LIMITED,
    /** 5xx after retries. */
    UPSTREAM_ERROR,
    /** Connect/read timeout after retries. */
    TIMEOUT,
    /** Other network failure (DNS, reset). */
    NETWORK,
    /** 401/403 — the page is not publicly accessible within our permission. */
    ACCESS_DENIED,
    /** 404/410 for the board itself. */
    NOT_FOUND,
    /** Body was not the expected shape. */
    MALFORMED_RESPONSE,
    /** Response exceeded the size limit or had a disallowed content type. */
    RESPONSE_REJECTED,
    /** The per-run request budget or page cap ran out before the end of the listing. */
    BUDGET_EXHAUSTED,
    /** robots.txt disallows the path. */
    ROBOTS_DISALLOWED
}
