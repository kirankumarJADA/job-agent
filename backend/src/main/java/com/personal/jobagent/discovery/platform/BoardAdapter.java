package com.personal.jobagent.discovery.platform;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One public job board protocol. Implementations are stateless; all I/O goes
 * through the {@link SafeHttpClient} handed in with the {@link CrawlContext},
 * so every adapter inherits the same SSRF guard, timeouts, size limits,
 * retry/backoff and per-run request budget.
 *
 * <p>Discovery is two-phase. {@link #list} enumerates the board and reports
 * whether the enumeration is COMPLETE — only a complete listing may ever be
 * used to infer that a posting disappeared. {@link #detail} fills in a single
 * entry whose listing data is not enough to ingest (no description). Detail
 * failures never make a listing incomplete.
 */
public interface BoardAdapter {

    PlatformKind kind();

    /** Board reference for a public URL on this platform, or empty when the URL is not one of ours. */
    Optional<BoardRef> recognize(URI url);

    /**
     * Validates and canonicalises a persisted {@code org_identifier}.
     *
     * @throws BoardConfigException with a human-readable reason when the value is unusable
     */
    BoardRef parseIdentifier(String orgIdentifier);

    ListingResult list(BoardRef board, CrawlContext context);

    /** Completes one entry. Only called for entries with {@code needsDetail()}. */
    NormalizedJob detail(BoardRef board, ListingEntry entry, CrawlContext context);

    /** Validated board reference. {@code identifier} is the canonical org_identifier. */
    record BoardRef(PlatformKind kind, String identifier, Map<String, String> params) {
        public BoardRef {
            params = params == null ? Map.of() : Map.copyOf(params);
        }

        public String param(String name) {
            String value = params.get(name);
            if (value == null) throw new IllegalStateException("board parameter missing: " + name);
            return value;
        }
    }

    /**
     * A listing row. {@code job} is non-null when the listing already carries
     * everything ingestion needs; otherwise {@code detail} is fetched.
     */
    record ListingEntry(String externalId, String jobUrl, String title, NormalizedJob job, Map<String, String> hints) {
        public ListingEntry {
            hints = hints == null ? Map.of() : Map.copyOf(hints);
        }

        public static ListingEntry complete(NormalizedJob job) {
            return new ListingEntry(job.externalId(), job.sourceUrl(), job.title(), job, Map.of());
        }

        public static ListingEntry partial(String externalId, String jobUrl, String title, Map<String, String> hints) {
            return new ListingEntry(externalId, jobUrl, title, null, hints);
        }

        public boolean needsDetail() {
            return job == null;
        }
    }

    /**
     * Outcome of enumerating a board.
     *
     * @param complete true only when every page was fetched and parsed and the
     *                 enumeration ended on the platform's own end-of-list
     *                 signal (not on a page cap, budget, timeout or error)
     */
    record ListingResult(List<ListingEntry> entries, boolean complete, int pagesFetched,
                         FailureCategory failure, String failureDetail, String employerName) {
        public ListingResult {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }

        public static ListingResult complete(List<ListingEntry> entries, int pages, String employerName) {
            return new ListingResult(entries, true, pages, null, null, employerName);
        }

        public static ListingResult partial(List<ListingEntry> entries, int pages, FailureCategory failure,
                                            String detail, String employerName) {
            return new ListingResult(entries, false, pages, failure, detail, employerName);
        }

        public static ListingResult failed(FailureCategory failure, String detail) {
            return new ListingResult(List.of(), false, 0, failure, detail, null);
        }
    }

    /** Per-run knobs. {@code now} lets parsers reject implausible future dates deterministically. */
    record CrawlContext(SafeHttpClient http, int maxPages, Instant now, String employerName) {
        public CrawlContext {
            if (maxPages <= 0) maxPages = 50;
            if (now == null) now = Instant.now();
        }
    }
}
