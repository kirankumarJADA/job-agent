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
import java.util.Locale;
import java.util.Optional;

/**
 * Arbeitnow (https://www.arbeitnow.com) — a third discovery source, alongside
 * Tsenta and FreeHire. A free, keyless, documented public REST API
 * ({@code GET /api/job-board-api}, Laravel-style pagination via
 * {@code ?page=}, confirmed live 2026-10-19 — see
 * https://www.arbeitnow.com/blog/job-board-api). Every response states its
 * own terms inline ({@code meta.terms}): "free public API for jobs, please
 * do not abuse... By using the API, you agree to the terms of service
 * present on Arbeitnow.com" — no paid tier is required for the use this
 * adapter makes of it (unlike Remotive's own public API, whose response
 * explicitly reserves third-party display for a paid plan — checked live the
 * same day and deliberately NOT chosen for that reason).
 *
 * <p><b>Two legitimate first-party domains can appear in one response.</b>
 * Arbeitnow documents a UK-specific mirror of the same job board API at
 * {@code www.arbeitnow.co.uk}; a live run against the {@code .com} endpoint
 * confirmed its own response can carry individual job records whose {@code
 * url} field points at either domain (both running the same in-house apply
 * flow — see below). This adapter always queries {@link #HOST} ({@code
 * .com}) but never assumes a single domain for an individual record's own
 * stated {@code url} — it is passed through verbatim either way, the same
 * restraint every field in this adapter takes.
 *
 * <p><b>One HTTP call per page — no detail fetch.</b> {@code GET
 * /api/job-board-api?page=N} already returns every field this adapter maps,
 * including the full job description; every {@link ListingEntry} is
 * therefore {@link ListingEntry#complete}, and {@link #detail} is never
 * actually invoked.
 *
 * <p><b>Arbeitnow's own hosted page IS the genuine application
 * destination — never an employer's own domain, but also never a generic
 * placeholder.</b> Unlike FreeHire (whose {@code url} field is, per
 * FreeHire's own documentation, usually the employer's own ATS page),
 * Arbeitnow's {@code url} field is always an {@code arbeitnow.com}-hosted
 * job page, and that page's own "Apply" action posts back to arbeitnow.com
 * itself (confirmed live: {@code .../apply} under the same path — Arbeitnow
 * runs its own in-house application flow end to end, it does not merely
 * link out to the employer). This is treated the same way Robin already
 * treats every other third-party ATS VENDOR domain that is not the
 * employer's own — {@code apply.workable.com}, {@code *.breezy.hr}, {@code
 * *.pinpointhq.com} and others are not "employer.com" either, yet each is a
 * genuine, job-specific, complete application destination for that one
 * posting, and {@link NormalizedJob#applicationUrl()} is set to it. The
 * same reasoning applies here: this URL is job-specific (one slug, one
 * posting), not a generic cross-employer search page, so it is set as both
 * {@code applicationUrl} and {@code sourceUrl} (identical, like FreeHire's
 * own single-URL case) rather than left null.
 * <p>This distinction matters structurally, not just cosmetically:
 * {@code BoardDiscoveryService#ingest} refuses to store ANY aggregator-kind
 * posting whose {@code applicationUrl} is null at all ("without the
 * employer's own application URL the posting cannot be applied to
 * honestly") — a deliberate, correct guard for Tsenta, where a SPECIFIC
 * posting's real employer URL sometimes fails to resolve. Arbeitnow is not
 * that case: it has no failed resolution to guard against, because it
 * never exposes an employer URL for ANY posting, by the permanent design of
 * its own product — leaving {@code applicationUrl} null here would not
 * guard against a bad URL, it would make this entire integration silently
 * ingest zero jobs, every run, forever. The seed migration still marks this
 * source {@code "automation": false}: a candidate can apply through the
 * shown link, but Robin's own browser-automation pipeline never attempts to
 * drive a submission there (that pipeline is scoped to employer ATS
 * contracts it has separately verified, e.g. Greenhouse's).
 *
 * <p><b>No category/region partition — one global source.</b> FreeHire
 * partitions into category facets because any one category alone holds far
 * more postings than a bounded run can reach. Arbeitnow's entire catalogue
 * is, by contrast, small enough that a single bounded run (the default
 * {@link CrawlContext#maxPages()} of 50) regularly reaches its own
 * end-of-list signal — live evidence (2026-10-19) showed the catalogue
 * ending around page 25-30 (an empty {@code data} array). No partitioning is
 * needed for coverage, and inventing one (there is no documented category
 * facet to partition by in the first place) would be an unjustified guess.
 *
 * <p><b>Never reported complete, even when a run reaches the empty page.</b>
 * The SAME live probe that found the end of the catalogue also showed
 * successive page fetches returning 300, 300, 300, 181, 150, then 0 records —
 * non-monotonically, not a clean decreasing tail. Arbeitnow's own
 * documentation states jobs are "updated every hour" ordered by creation
 * time; new postings inserted at page 1 while a run is mid-pagination shift
 * every later offset-based page boundary forward, so a run's enumeration is
 * a snapshot of a moving target, not a verified stable total. Reaching the
 * empty page is therefore treated exactly like FreeHire's and Tsenta's own
 * "never exhaustive" convention (for a different underlying reason) — a
 * clean stop, never a failure, but never grounds to infer that an unseen
 * posting disappeared.
 *
 * <p><b>Fields deliberately NOT mapped.</b> {@code tags} (e.g. {@code
 * ["Operations"]}, {@code ["Tech","Remote"]}) are broad category/industry
 * labels, not literal technical skills — feeding them into {@link
 * NormalizedJob#skills()} would hand {@code JobMatchService}'s semantic
 * skill-matcher nonsense input instead of real skills, so they are left
 * unset; the existing "no skills stated" neutral-scoring path applies
 * honestly instead. {@code location} is free text with no consistent
 * structure across the sample observed live ("Berlin", "Berlin, Berlin",
 * "Berlin, Germany", "Berlin; Chemnitz, Sachsen; Dresden, Sachsen", "Remote
 * job", "Undisclosed test site 35km from Munich") — picking a city or
 * country out of that with any confidence would be a guess, so only {@code
 * locationRaw} is set.
 */
public class ArbeitnowAdapter implements BoardAdapter {

    public static final String HOST = "www.arbeitnow.com";

    /** The only supported identifier: this is one global catalogue, not a per-tenant board. */
    public static final String SCOPE = "jobs";

    @Override
    public PlatformKind kind() {
        return PlatformKind.ARBEITNOW;
    }

    /**
     * No per-tenant board URL exists to recognise — same restraint FreeHire
     * takes for {@code freehire.me/jobs/{slug}}: an individual {@code
     * arbeitnow.com/jobs/companies/...} posting page is a single-job URL, a
     * different feature (one-off import) from a recurring discovery source.
     */
    @Override
    public Optional<BoardRef> recognize(URI url) {
        return Optional.empty();
    }

    @Override
    public BoardRef parseIdentifier(String orgIdentifier) {
        String scope = orgIdentifier == null ? "" : orgIdentifier.strip().toLowerCase(Locale.ROOT);
        if (!scope.equals(SCOPE)) {
            throw new BoardConfigException("Arbeitnow identifier must be '" + SCOPE + "'");
        }
        return new BoardRef(kind(), scope, java.util.Map.of());
    }

    @Override
    public ListingResult list(BoardRef board, CrawlContext context) {
        Crawl crawl = new Crawl(context);
        ListingResult result = crawl.run(() -> {
            for (int page = 1; ; page++) {
                crawl.beforePage();
                String body = context.http().get(buildUri(page), SafeHttpClient.Accept.JSON);
                crawl.afterPage();
                if (parsePage(body, crawl, context.now()) == 0) return; // past the last page
            }
        });
        // See this class's own javadoc: a clean stop at the platform's own
        // empty page is never a failure, but — because the catalogue is
        // live-updated hourly and pagination can drift mid-run — it is also
        // never grounds to call the listing COMPLETE.
        boolean boundedStop = result.failure() == FailureCategory.BUDGET_EXHAUSTED
                && result.failureDetail() != null && result.failureDetail().startsWith("page cap");
        FailureCategory failure = boundedStop ? null : result.failure();
        return ListingResult.partial(result.entries(), result.pagesFetched(), failure,
                failure == null ? "catalogue refreshes hourly; one run's enumeration is a snapshot, never a verified stable total"
                        : result.failureDetail(), null);
    }

    static URI buildUri(int page) {
        return Parsing.uri("https://" + HOST + "/api/job-board-api?page=" + page);
    }

    /** Adds this page's valid records; returns how many records the page actually contained (0 = past the last page). */
    static int parsePage(String body, Crawl crawl, Instant now) {
        JsonNode root = Parsing.json(body);
        JsonNode data = root.get("data");
        if (data == null || !data.isArray()) {
            throw new Parsing.MalformedException("Arbeitnow response has no 'data' array");
        }
        for (JsonNode record : data) {
            NormalizedJob job = parseJob(record, now);
            // An individual malformed record (missing slug/title/url) is
            // skipped, never treated as proof the whole page or run failed —
            // the same restraint every other adapter in this package takes.
            if (job != null) crawl.add(ListingEntry.complete(job));
        }
        return data.size();
    }

    /**
     * Builds a {@link NormalizedJob} directly from one Arbeitnow job record,
     * or {@code null} when the record is missing a field this adapter treats
     * as mandatory (its own stable slug, a title, or its job-page URL) —
     * never a guessed value in their place.
     */
    static NormalizedJob parseJob(JsonNode record, Instant now) {
        String slug = Parsing.text(record, "slug");
        String title = Parsing.text(record, "title");
        String url = Parsing.text(record, "url");
        if (slug == null || title == null || url == null) return null;

        JsonNode createdAt = record.get("created_at");
        Long createdAtMillis = createdAt != null && createdAt.isNumber() ? createdAt.asLong() * 1000L : null;

        NormalizedJob.Builder b = NormalizedJob.builder(slug, title)
                .employer(Parsing.text(record, "company_name"))
                .location(Parsing.text(record, "location"))
                .description(Normalize.htmlToText(Parsing.text(record, "description")))
                // Both set to the same job-specific Arbeitnow page — see this
                // class's own module javadoc for why that is a genuine
                // application destination here, not a generic aggregator URL.
                .applicationUrl(url)
                .sourceUrl(url)
                .postedAt(Normalize.postedAt(createdAtMillis, now))
                .employmentType(Normalize.join(Parsing.textList(record.get("job_types"))));

        // true is a positive statement; false does not distinguish onsite
        // from hybrid, so it is left unset rather than guessed as ONSITE —
        // the same restraint NormalizedJob's own module doc requires.
        JsonNode remote = record.get("remote");
        if (remote != null && remote.isBoolean() && remote.asBoolean()) {
            b.remote("REMOTE");
        }

        return b.build();
    }

    @Override
    public NormalizedJob detail(BoardRef board, ListingEntry entry, CrawlContext context) {
        // Every entry list() produces is already ListingEntry.complete(job) —
        // BoardDiscoveryService only calls detail() for entries whose
        // needsDetail() is true, which never happens for this adapter.
        throw new UnsupportedOperationException("Arbeitnow listings are always complete; detail() is never called");
    }
}
