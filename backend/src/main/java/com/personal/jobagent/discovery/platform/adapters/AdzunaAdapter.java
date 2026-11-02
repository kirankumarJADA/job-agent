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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Adzuna (https://www.adzuna.com) — Robin's fourth aggregator source and its
 * first genuinely REGION-AWARE one: one keyed, documented public REST API
 * ({@code GET /v1/api/jobs/{country}/search/{page}}, per Adzuna's own
 * published developer documentation, https://developer.adzuna.com/docs) whose
 * only per-region difference is the {@code {country}} path segment — a single
 * registered {@code app_id}/{@code app_key} pair covers every country Adzuna
 * supports. This is exactly the "architecture that accommodates other
 * regions" the task asks for: adding coverage for another country is one more
 * {@code job_sources} row (a different {@code org_identifier}/{@code region}),
 * never a new adapter.
 *
 * <p><b>Honesty about verification.</b> Unlike {@code ArbeitnowAdapter} and
 * {@code FreeHireAdapter} (both built and fixture-tested against a REAL live
 * response captured the same day), this adapter was built and fixture-tested
 * against Adzuna's OWN PUBLISHED documentation and schema samples only — this
 * environment has no registered Adzuna {@code app_id}/{@code app_key} to
 * exercise the real endpoint against. {@link #AdzunaAdapter(String, String)}
 * requires both to be configured (via {@code app.discovery.adzuna.app-id}/
 * {@code app-key}); when either is blank, {@link #parseIdentifier} refuses the
 * source with {@link BoardConfigException}, which {@code BoardDiscoveryService}
 * records as {@link FailureCategory#CONFIGURATION} — this source is correctly
 * reported as requiring configuration, never silently treated as working.
 * {@code AdzunaLiveIngestionIT} exists for the moment real credentials ARE
 * configured, mirroring the opt-in {@code -Dlive.discovery=true} convention
 * every other live-network test in this package already uses.
 *
 * <p><b>One HTTP call per page — no separate detail fetch.</b> Adzuna's
 * search endpoint already returns every field this adapter maps, including a
 * usable description; every {@link ListingEntry} is therefore
 * {@link ListingEntry#complete}.
 *
 * <p><b>Never reported complete.</b> Adzuna's own {@code count} field states
 * a total, but — exactly like {@code FreeHireAdapter}'s and {@code
 * ArbeitnowAdapter}'s own documented reasoning — a live job-search index is
 * continuously updated; without a live-verified update cadence to reason
 * about, this adapter makes the same conservative choice its aggregator
 * siblings make: a listing is always {@code partial}, so absence from one
 * run's slice is never grounds to infer a posting disappeared.
 *
 * <p><b>Currency is the query's own country, never guessed.</b> Adzuna states
 * {@code salary_min}/{@code salary_max} as plain numbers with no currency
 * field; the currency is implied by the searched country (documented fact,
 * not an inference — a GB search is priced in GBP). {@link #CURRENCY_BY_COUNTRY}
 * is scoped to exactly the countries {@link #SUPPORTED_COUNTRIES} this adapter
 * accepts, so a currency is only ever stated for a country this adapter
 * genuinely queries.
 *
 * <p><b>Application URL.</b> Adzuna's {@code redirect_url} is a job-specific
 * tracking redirect that forwards to the original posting (confirmed by
 * Adzuna's own documentation) — the same "genuine, job-specific, one-hop
 * application destination" reasoning {@code ArbeitnowAdapter}'s javadoc gives
 * for its own hosted apply page. It is used for both {@code applicationUrl}
 * and {@code sourceUrl}. This is a redirect, not a direct employer domain, so
 * (like Arbeitnow) the seed migration marks {@code "automation": false}:
 * Robin's browser-automation pipeline never attempts to drive a submission
 * through it.
 */
public class AdzunaAdapter implements BoardAdapter {

    public static final String HOST = "api.adzuna.com";
    static final int PAGE_SIZE = 50;

    /** Adzuna's own documented country coverage (https://developer.adzuna.com/docs) at the time this adapter was written. */
    static final Set<String> SUPPORTED_COUNTRIES = Set.of(
            "gb", "us", "at", "au", "br", "ca", "de", "fr", "in", "it", "mx", "nl", "nz", "pl", "sg", "za", "es");

    /** ISO-4217 currency implied by the searched country — a documented fact, never a guess. See this class's own javadoc. */
    static final Map<String, String> CURRENCY_BY_COUNTRY = Map.ofEntries(
            Map.entry("gb", "GBP"), Map.entry("us", "USD"), Map.entry("at", "EUR"), Map.entry("au", "AUD"),
            Map.entry("br", "BRL"), Map.entry("ca", "CAD"), Map.entry("de", "EUR"), Map.entry("fr", "EUR"),
            Map.entry("in", "INR"), Map.entry("it", "EUR"), Map.entry("mx", "MXN"), Map.entry("nl", "EUR"),
            Map.entry("nz", "NZD"), Map.entry("pl", "PLN"), Map.entry("sg", "SGD"), Map.entry("za", "ZAR"),
            Map.entry("es", "EUR"));

    private final String appId;
    private final String appKey;

    public AdzunaAdapter(String appId, String appKey) {
        this.appId = appId == null ? "" : appId.strip();
        this.appKey = appKey == null ? "" : appKey.strip();
    }

    @Override
    public PlatformKind kind() {
        return PlatformKind.ADZUNA;
    }

    /**
     * No per-tenant board URL exists to recognise: like FreeHire and
     * Arbeitnow, Adzuna is a global search API a candidate would never paste
     * a per-board link to.
     */
    @Override
    public Optional<BoardRef> recognize(URI url) {
        return Optional.empty();
    }

    @Override
    public BoardRef parseIdentifier(String orgIdentifier) {
        if (appId.isEmpty() || appKey.isEmpty()) {
            throw new BoardConfigException(
                    "Adzuna requires app_id and app_key to be configured (app.discovery.adzuna.app-id/app-key) "
                            + "before this source can be run");
        }
        String country = orgIdentifier == null ? "" : orgIdentifier.strip().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_COUNTRIES.contains(country)) {
            throw new BoardConfigException("Adzuna country must be one of " + SUPPORTED_COUNTRIES);
        }
        return new BoardRef(kind(), country, Map.of());
    }

    @Override
    public ListingResult list(BoardRef board, CrawlContext context) {
        String country = board.identifier();
        Crawl crawl = new Crawl(context);
        ListingResult result = crawl.run(() -> {
            for (int page = 1; ; page++) {
                crawl.beforePage();
                String body = context.http().get(buildUri(country, page), SafeHttpClient.Accept.JSON);
                crawl.afterPage();
                if (parsePage(body, crawl, context.now(), country) == 0) return; // past the last page
            }
        });
        // See this class's own javadoc: a live search index is continuously
        // updated, so a listing is always partial — the same convention
        // FreeHireAdapter and ArbeitnowAdapter use for the same reason.
        boolean boundedStop = result.failure() == FailureCategory.BUDGET_EXHAUSTED
                && result.failureDetail() != null && result.failureDetail().startsWith("page cap");
        FailureCategory failure = boundedStop ? null : result.failure();
        return ListingResult.partial(result.entries(), result.pagesFetched(), failure,
                failure == null ? "Adzuna's index updates continuously; one run's enumeration is a snapshot, never a verified stable total"
                        : result.failureDetail(), null);
    }

    URI buildUri(String country, int page) {
        return Parsing.uri("https://" + HOST + "/v1/api/jobs/" + Parsing.enc(country) + "/search/" + page
                + "?app_id=" + Parsing.enc(appId) + "&app_key=" + Parsing.enc(appKey)
                + "&results_per_page=" + PAGE_SIZE);
    }

    /** Adds this page's valid records; returns how many records the page actually contained (0 = past the last page). */
    static int parsePage(String body, Crawl crawl, Instant now, String country) {
        JsonNode root = Parsing.json(body);
        JsonNode results = root.get("results");
        if (results == null || !results.isArray()) {
            throw new Parsing.MalformedException("Adzuna response has no 'results' array");
        }
        for (JsonNode record : results) {
            NormalizedJob job = parseJob(record, now, country);
            // An individual malformed record (missing id/title/redirect_url)
            // is skipped, never treated as proof the whole page or run
            // failed — the same restraint every other adapter takes.
            if (job != null) crawl.add(ListingEntry.complete(job));
        }
        return results.size();
    }

    /**
     * Builds a {@link NormalizedJob} from one Adzuna result record, or
     * {@code null} when it is missing a field this adapter treats as
     * mandatory (its own stable id, a title, or its redirect URL) — never a
     * guessed value in their place.
     */
    static NormalizedJob parseJob(JsonNode record, Instant now, String country) {
        String id = Parsing.text(record, "id");
        String title = Parsing.text(record, "title");
        String redirectUrl = Parsing.text(record, "redirect_url");
        if (id == null || title == null || redirectUrl == null) return null;

        JsonNode company = record.get("company");
        JsonNode location = record.get("location");

        NormalizedJob.Builder b = NormalizedJob.builder(id, title)
                .employer(company == null ? null : Parsing.text(company, "display_name"))
                .location(location == null ? null : Parsing.text(location, "display_name"))
                .description(Parsing.text(record, "description"))
                // Both set to the same job-specific redirect — see this
                // class's own module javadoc for why that is a genuine
                // application destination here, not a generic search page.
                .applicationUrl(redirectUrl)
                .sourceUrl(redirectUrl)
                .postedAt(Normalize.postedAt(Parsing.text(record, "created"), now))
                .employmentType(Normalize.join(Parsing.text(record, "contract_time"), Parsing.text(record, "contract_type")))
                .country(country.toUpperCase(Locale.ROOT));

        String currency = CURRENCY_BY_COUNTRY.get(country);
        if (currency != null) {
            b.salary(Normalize.decimalOrNull(numberOrNull(record, "salary_min")),
                    Normalize.decimalOrNull(numberOrNull(record, "salary_max")), currency, null);
        }
        // category.label (e.g. "IT Jobs") is a broad industry category, not a
        // literal technical skill — the same restraint ArbeitnowAdapter's own
        // javadoc documents for its "tags" field — so it is deliberately left
        // out of NormalizedJob#skills().
        return b.build();
    }

    private static Object numberOrNull(JsonNode record, String field) {
        JsonNode v = record.get(field);
        return v == null || v.isNull() ? null : v.isValueNode() ? v.asText() : null;
    }

    @Override
    public NormalizedJob detail(BoardRef board, ListingEntry entry, CrawlContext context) {
        // Every entry list() produces is already ListingEntry.complete(job) —
        // BoardDiscoveryService only calls detail() for entries whose
        // needsDetail() is true, which never happens for this adapter.
        throw new UnsupportedOperationException("Adzuna listings are always complete; detail() is never called");
    }
}
