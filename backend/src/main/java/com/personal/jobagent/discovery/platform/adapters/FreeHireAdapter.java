package com.personal.jobagent.discovery.platform.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import com.personal.jobagent.discovery.platform.BoardAdapter;
import com.personal.jobagent.discovery.platform.BoardConfigException;
import com.personal.jobagent.discovery.platform.FailureCategory;
import com.personal.jobagent.discovery.platform.NormalizedJob;
import com.personal.jobagent.discovery.platform.Normalize;
import com.personal.jobagent.discovery.platform.Parsing;
import com.personal.jobagent.discovery.platform.PlatformKind;
import com.personal.jobagent.discovery.platform.SafeHttpClient;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * FreeHire (https://freehire.me) — the second aggregator source, after Tsenta.
 * MIT-licensed, 3.3M+ live postings pulled directly from company career pages
 * across 92 ATS platforms, exposed through a keyless public REST API
 * (https://freehire.me/docs/api, confirmed live against
 * https://freehire.me/openapi.yaml, version 1.1.0, 2026-10-19).
 *
 * <p><b>Why the API, not FreeHire's own Go crawler.</b> FreeHire already
 * crawls, normalizes and deduplicates these postings; re-crawling the same
 * ATS platforms a second time would duplicate work FreeHire has already done
 * and done well. This adapter reads FreeHire's finished catalogue through its
 * documented search endpoint instead.
 *
 * <p><b>One HTTP call per page — no separate detail fetch.</b> Unlike Tsenta
 * (whose listing pages carry only a card and a detail page carries the real
 * fields), {@code GET /api/v1/agent/jobs/search} already returns every field
 * this adapter maps, including the FULL job description
 * ({@code description_format=text}) — the sibling {@code /jobs/search}
 * endpoint only returns a truncated preview, which is why this adapter uses
 * {@code /agent/...} specifically. Every {@link ListingEntry} this adapter
 * produces is therefore {@link ListingEntry#complete}; {@link #detail} is
 * never actually invoked by {@code BoardDiscoveryService} and exists only to
 * satisfy the interface.
 *
 * <p><b>Search partitions (task's own "configurable search partitions"
 * ask).</b> FreeHire has no per-employer "board" to import — it is one global
 * catalogue — so {@code org_identifier} here names a {@code category} facet
 * value instead (validated against the full documented vocabulary,
 * {@link #CATEGORIES}). Each category is its own {@code job_sources} row,
 * scheduled and run independently through the exact same
 * {@code BoardDiscoveryService} path Tsenta uses. This keeps each run's own
 * page cap meaningful (a 10-page cap on ONE category reaches ten times
 * further than the same cap split across many categories in one call) and
 * lets an operator add or drop a category by adding or disabling one row —
 * no code change.
 *
 * <p><b>Pagination depth is a real, enforced API limit, not a style
 * choice.</b> {@code offset + limit} over 10,000 is refused by the API itself
 * with {@code 400 {"error":"pagination too deep"}} (confirmed live). This
 * adapter checks that ceiling itself before ever sending a request that
 * would cross it, stopping cleanly — the same non-failure shape as reaching
 * the page cap — rather than depending on the API to refuse it.
 *
 * <p><b>Never exhaustive, by design.</b> Even one category can hold well over
 * 10,000 postings; a bounded run can reach only a bounded slice of it. Every
 * listing this adapter produces is reported {@code partial} for that reason
 * alone (mirrors {@link TsentaJobSource}'s own "aggregator listing is never
 * exhaustive" convention exactly) — absence from one run's slice is never
 * treated as evidence a posting disappeared.
 *
 * <p><b>Application URL.</b> FreeHire's own {@code url} field is used
 * directly as both {@link NormalizedJob#applicationUrl()} and
 * {@link NormalizedJob#sourceUrl()} — FreeHire's own documentation states
 * every listing "links straight to the company's own careers page," and (for
 * the large majority of records, confirmed live: {@code source=workday} and
 * similar ATS-tagged records) that URL genuinely is the employer's own ATS
 * posting, just carrying a {@code utm_source=freehire.me} tracking parameter.
 * FreeHire's own {@code source} vocabulary is 225 values wide (confirmed
 * live via {@code /jobs/facets}) and includes, alongside direct ATS
 * platforms, secondary job-board aggregators (e.g. {@code whatjobs-ca},
 * {@code adzuna}) whose own {@code url} is one hop short of the employer's
 * own page. Classifying which of those 225 values are "direct" versus
 * "secondary" with any confidence was judged out of scope for this
 * integration — inventing that classification would itself be exactly the
 * kind of unverified guess the task warns against — so this adapter passes
 * FreeHire's own stated URL through unchanged for every record, the same way
 * every other adapter trusts its own source's stated URL. Tracking-parameter
 * stripping for identity purposes already happens centrally in
 * {@code JobIdentity}'s own URL normalization, unchanged by this adapter.
 */
public class FreeHireAdapter implements BoardAdapter {

    public static final String HOST = "freehire.me";
    static final int MAX_PAGE_SIZE = 100;
    /** The API's own enforced ceiling: {@code offset + limit <= 10000} (confirmed live, 2026-10-19). */
    static final int MAX_PAGINATION_DEPTH = 10_000;

    /**
     * The five tech-specialization categories seeded as {@code job_sources}
     * rows for this initial integration — a deliberately small, justified
     * subset of FreeHire's full category vocabulary chosen for direct overlap
     * with Robin's own existing skills/scoring vocabulary (Java, Python,
     * JavaScript/TypeScript, React, Spring, SQL, AWS, Docker, Kubernetes —
     * see {@code DiscoveryExtractionParser#skills}). FreeHire's facets
     * (confirmed live) show these as five DISTINCT, non-overlapping values —
     * not a parent/child hierarchy — so each genuinely widens coverage rather
     * than re-fetching the same postings.
     */
    public static final List<String> SEEDED_CATEGORIES =
            List.of("software_engineering", "backend", "frontend", "fullstack", "devops");

    /**
     * The FULL documented category vocabulary (confirmed live via
     * {@code /jobs/facets}) — {@link #parseIdentifier} validates against all
     * of these, not just the seeded five, so an operator can add another
     * category's {@code job_sources} row later with no code change.
     */
    static final Set<String> CATEGORIES = Set.of(
            "software_engineering", "backend", "frontend", "fullstack", "mobile", "devops", "sre",
            "data_engineering", "data_science", "ml_ai", "ai_engineering", "qa", "security", "design",
            "product", "project_management", "marketing", "sales", "support", "finance", "legal",
            "operations", "other");

    private final int pageSize;

    public FreeHireAdapter() {
        this(MAX_PAGE_SIZE);
    }

    public FreeHireAdapter(int pageSize) {
        this.pageSize = Math.min(MAX_PAGE_SIZE, Math.max(1, pageSize));
    }

    @Override
    public PlatformKind kind() {
        return PlatformKind.FREEHIRE;
    }

    /**
     * No per-tenant board URL exists to recognise: FreeHire is one global,
     * keyless search API, not a board a candidate would paste a link to.
     * Individual {@code freehire.me/jobs/{slug}} posting pages are
     * deliberately not treated as an importable "board" — that would be a
     * single-job import, a different feature from a recurring source.
     */
    @Override
    public Optional<BoardRef> recognize(URI url) {
        return Optional.empty();
    }

    @Override
    public BoardRef parseIdentifier(String orgIdentifier) {
        String category = orgIdentifier == null ? "" : orgIdentifier.strip().toLowerCase(Locale.ROOT);
        if (!CATEGORIES.contains(category)) {
            throw new BoardConfigException("FreeHire category must be one of " + CATEGORIES);
        }
        return new BoardRef(kind(), category, Map.of());
    }

    @Override
    public ListingResult list(BoardRef board, CrawlContext context) {
        Crawl crawl = new Crawl(context);
        ListingResult result = crawl.run(() -> {
            for (int page = 0; ; page++) {
                int offset = page * pageSize;
                if (offset + pageSize > MAX_PAGINATION_DEPTH) return; // the API's own depth ceiling — a clean stop, not a failure
                crawl.beforePage();
                String body = context.http().get(buildUri(board.identifier(), offset, pageSize), SafeHttpClient.Accept.JSON);
                crawl.afterPage();
                if (parsePage(body, crawl, context.now()) == 0) return; // past the last page
            }
        });
        // Even one category routinely holds far more postings than any bounded
        // run can reach — see this class's own javadoc. A listing is only ever
        // PARTIAL, the same convention TsentaJobSource uses for the same reason:
        // reaching the configured page cap (or the API's own pagination ceiling,
        // both represented here as BUDGET_EXHAUSTED by Crawl) is the design,
        // not a failure; a genuine fetch problem is still reported.
        boolean boundedStop = result.failure() == FailureCategory.BUDGET_EXHAUSTED
                && result.failureDetail() != null && result.failureDetail().startsWith("page cap");
        FailureCategory failure = boundedStop ? null : result.failure();
        return ListingResult.partial(result.entries(), result.pagesFetched(), failure,
                failure == null ? "aggregator listing is never exhaustive" : result.failureDetail(), null);
    }

    static URI buildUri(String category, int offset, int limit) {
        return Parsing.uri("https://" + HOST + "/api/v1/agent/jobs/search?category=" + Parsing.enc(category)
                + "&limit=" + limit + "&offset=" + offset + "&sort=posted_at&order=desc&description_format=text");
    }

    /** Adds this page's valid records; returns how many records the page actually contained (0 = past the last page). */
    static int parsePage(String body, Crawl crawl, Instant now) {
        JsonNode root = Parsing.json(body);
        JsonNode error = root.get("error");
        if (error != null) {
            throw new Parsing.MalformedException("FreeHire reported an error: " + Normalize.blankToNull(error.asText()));
        }
        JsonNode data = root.get("data");
        if (data == null || !data.isArray()) {
            throw new Parsing.MalformedException("FreeHire response has no 'data' array");
        }
        for (JsonNode record : data) {
            NormalizedJob job = parseJob(record, now);
            if (job != null) crawl.add(ListingEntry.complete(job));
            // An individual malformed record (missing slug/title/url) is skipped,
            // never treated as proof the whole page or run failed — Crawl's own
            // dedup/partial handling is for fetch-level problems, not one bad row.
        }
        return data.size();
    }

    /**
     * Builds a {@link NormalizedJob} directly from one FreeHire job record, or
     * {@code null} when the record is missing a field this adapter treats as
     * mandatory (its own stable identifier, a title, or a URL) — never a
     * guessed value in their place.
     */
    static NormalizedJob parseJob(JsonNode record, Instant now) {
        String slug = Parsing.text(record, "public_slug");
        String title = Parsing.text(record, "title");
        String url = Parsing.text(record, "url");
        if (slug == null || title == null || url == null) return null;

        NormalizedJob.Builder b = NormalizedJob.builder(slug, title)
                .employer(Parsing.text(record, "company"))
                .location(Parsing.text(record, "location"))
                .description(Parsing.text(record, "description"))
                .applicationUrl(url)
                .sourceUrl(url)
                .postedAt(Normalize.postedAt(Parsing.text(record, "posted_at"), now))
                .skills(Parsing.textList(record.get("skills")));

        String workMode = Parsing.text(record, "work_mode");
        if (workMode != null) b.remote(Normalize.remoteType(workMode));

        // A single resolved city/country is stated only when FreeHire's own
        // arrays name exactly one — several names is exactly the ambiguity
        // Normalize.joinPlaces-style callers elsewhere already decline to pick from.
        List<String> cities = Parsing.textList(record.get("cities"));
        if (cities.size() == 1) b.city(cities.get(0));
        List<String> countries = Parsing.textList(record.get("countries"));
        if (countries.size() == 1) b.country(countries.get(0));

        JsonNode enrichment = record.get("enrichment");
        if (enrichment != null && enrichment.isObject()) {
            String employmentType = Parsing.text(enrichment, "employment_type");
            if (employmentType != null) b.employmentType(employmentType);
            String currency = Normalize.currencyOrNull(Parsing.text(enrichment, "salary_currency"));
            if (currency != null) {
                b.salary(Normalize.decimalOrNull(Parsing.text(enrichment, "salary_min")),
                        Normalize.decimalOrNull(Parsing.text(enrichment, "salary_max")),
                        currency, Parsing.text(enrichment, "salary_period"));
            }
        }
        // closed_at is never present on /agent/jobs/search results — that
        // endpoint searches OPEN postings only (confirmed live and by the
        // documented contract); this adapter therefore never calls
        // `.active(false)` from a listing result, the same restraint every
        // other adapter uses: a close signal is only ever set when the
        // source states it explicitly, never inferred from an endpoint whose
        // documented scope already excludes closed postings.
        return b.build();
    }

    @Override
    public NormalizedJob detail(BoardRef board, ListingEntry entry, CrawlContext context) {
        // Every entry list() produces is already ListingEntry.complete(job) —
        // BoardDiscoveryService only calls detail() for entries whose
        // needsDetail() is true, which never happens for this adapter.
        throw new UnsupportedOperationException("FreeHire listings are always complete; detail() is never called");
    }

    /** Distinct, ordered view of the seeded categories — used by the seed migration's own documentation, never by runtime code. */
    public static Set<String> seededCategories() {
        return new LinkedHashSet<>(SEEDED_CATEGORIES);
    }
}
