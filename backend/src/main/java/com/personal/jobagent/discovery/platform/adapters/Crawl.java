package com.personal.jobagent.discovery.platform.adapters;

import com.personal.jobagent.discovery.platform.BoardAdapter.CrawlContext;
import com.personal.jobagent.discovery.platform.BoardAdapter.ListingEntry;
import com.personal.jobagent.discovery.platform.BoardAdapter.ListingResult;
import com.personal.jobagent.discovery.platform.FailureCategory;
import com.personal.jobagent.discovery.platform.Parsing;
import com.personal.jobagent.discovery.platform.SafeHttpClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared listing loop bookkeeping. The completeness rule lives here, once:
 * a listing is complete only when the adapter's body returns normally — any
 * page cap, budget exhaustion, fetch failure or malformed page yields a
 * PARTIAL result that keeps what was parsed but can never drive removals.
 */
final class Crawl {

    private final CrawlContext context;
    private final List<ListingEntry> entries = new ArrayList<>();
    private int pages;
    private String employerName;

    Crawl(CrawlContext context) {
        this.context = context;
        this.employerName = context.employerName();
    }

    /** Call before fetching each page. */
    void beforePage() {
        if (pages >= context.maxPages()) throw new CapReached();
    }

    void afterPage() {
        pages++;
    }

    void add(ListingEntry entry) {
        if (entry != null) entries.add(entry);
    }

    /** Employer name stated by the board itself, used only when none was configured. */
    void employerFromBoard(String name) {
        if (employerName == null && name != null && !name.isBlank()) employerName = name.strip();
    }

    String employerName() {
        return employerName;
    }

    ListingResult run(Runnable body) {
        try {
            body.run();
            return ListingResult.complete(dedupe(), pages, employerName);
        } catch (CapReached e) {
            return ListingResult.partial(dedupe(), pages, FailureCategory.BUDGET_EXHAUSTED,
                    "page cap of " + context.maxPages() + " reached before the end of the board", employerName);
        } catch (SafeHttpClient.FetchException e) {
            return pages == 0 && entries.isEmpty() ? ListingResult.failed(e.category(), e.getMessage())
                    : ListingResult.partial(dedupe(), pages, e.category(), e.getMessage(), employerName);
        } catch (Parsing.MalformedException e) {
            return pages == 0 && entries.isEmpty()
                    ? ListingResult.failed(FailureCategory.MALFORMED_RESPONSE, e.getMessage())
                    : ListingResult.partial(dedupe(), pages, FailureCategory.MALFORMED_RESPONSE, e.getMessage(), employerName);
        }
    }

    /** The same posting listed twice (featured + regular, overlapping pages) is kept once. */
    private List<ListingEntry> dedupe() {
        Map<String, ListingEntry> byId = new LinkedHashMap<>();
        for (ListingEntry e : entries) byId.putIfAbsent(e.externalId(), e);
        return new ArrayList<>(byId.values());
    }

    static final class CapReached extends RuntimeException {
        CapReached() {
            super(null, null, false, false);
        }
    }
}
