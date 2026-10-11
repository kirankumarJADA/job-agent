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
import org.jsoup.nodes.Document;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tsenta public job listings — the 31st, aggregator source. The project owner
 * has confirmed permission to collect Tsenta's PUBLIC job listings; this
 * adapter stays inside that scope:
 * <ul>
 *   <li>only the public, server-rendered pages {@code /jobs[/scope]?page=N}
 *       and {@code /jobs/{slug}-{uuid}} are requested — no dashboard, account,
 *       API or authenticated surface;</li>
 *   <li>{@code robots.txt} is fetched first on every run and the run stops
 *       if {@code /jobs} is disallowed or robots cannot be read;</li>
 *   <li>a run is bounded by a page cap, a request budget and a minimum
 *       interval between requests (default 1.5 s), all enforced by
 *       {@link SafeHttpClient}; a 401/403/CAPTCHA/challenge is reported as
 *       ACCESS_DENIED and never retried around.</li>
 * </ul>
 *
 * <p>Page structure (verified 2026-10-10): Next.js App Router pages, fully
 * server-rendered; 20 job cards per listing page, {@code ?page=N} paginates
 * server-side; each detail page embeds a schema.org JobPosting and a React
 * Server Component payload whose {@code job} object carries the posting's
 * structured fields, including {@code url}: the ORIGINAL EMPLOYER application
 * URL. That employer URL becomes the job's application URL. Tsenta's own
 * dashboard apply link is never used, and a posting without an employer URL
 * keeps no application URL at all (it is not replaced with a Tsenta URL).
 *
 * <p>Tsenta lists millions of postings; no run can enumerate them, so every
 * Tsenta listing is PARTIAL by definition and absence from a run never marks
 * anything removed. A posting is closed only when its own detail page says
 * {@code active:false} or its validThrough has passed.
 */
public class TsentaJobSource implements BoardAdapter {

    public static final String HOST = "tsenta.com";
    static final List<String> SCOPES = List.of("jobs", "jobs/remote", "jobs/internships", "jobs/entry-level");
    private static final Pattern CARD = Pattern.compile(
            "href=\"(/jobs/[a-z0-9-]{1,200}-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}))\"");
    private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final int maxDetailsPerRun;

    public TsentaJobSource() {
        this(40);
    }

    public TsentaJobSource(int maxDetailsPerRun) {
        this.maxDetailsPerRun = maxDetailsPerRun;
    }

    public int maxDetailsPerRun() {
        return maxDetailsPerRun;
    }

    @Override
    public PlatformKind kind() {
        return PlatformKind.TSENTA;
    }

    @Override
    public Optional<BoardRef> recognize(URI url) {
        String host = Parsing.host(url);
        if (!host.equals(HOST) && !host.equals("www." + HOST)) return Optional.empty();
        String path = String.join("/", Parsing.pathSegments(url));
        return SCOPES.contains(path) ? Optional.of(parseIdentifier(path)) : Optional.of(parseIdentifier("jobs"));
    }

    @Override
    public BoardRef parseIdentifier(String orgIdentifier) {
        String scope = orgIdentifier == null ? "" : orgIdentifier.strip().replaceAll("^/+|/+$", "").toLowerCase(Locale.ROOT);
        if (!SCOPES.contains(scope)) {
            throw new BoardConfigException("Tsenta scope must be one of " + SCOPES);
        }
        return new BoardRef(kind(), scope, Map.of());
    }

    @Override
    public ListingResult list(BoardRef board, CrawlContext context) {
        Crawl crawl = new Crawl(context);
        try {
            String robots = context.http().get(Parsing.uri("https://" + HOST + "/robots.txt"), SafeHttpClient.Accept.TEXT);
            if (!robotsAllow(robots, "/" + board.identifier())) {
                return ListingResult.failed(FailureCategory.ROBOTS_DISALLOWED, "robots.txt disallows /" + board.identifier());
            }
        } catch (SafeHttpClient.FetchException e) {
            return ListingResult.failed(e.category() == FailureCategory.NOT_FOUND ? FailureCategory.ROBOTS_DISALLOWED : e.category(),
                    "robots.txt could not be read: " + e.getMessage());
        }
        ListingResult result = crawl.run(() -> {
            for (int page = 1; ; page++) {
                crawl.beforePage();
                String html = context.http().get(Parsing.uri("https://" + HOST + "/" + board.identifier() + "?page=" + page),
                        SafeHttpClient.Accept.HTML);
                crawl.afterPage();
                if (parseListing(html, crawl) == 0) return;
            }
        });
        // Never complete: the aggregate is far larger than any run, so absence proves nothing.
        // Stopping at the configured page cap is the design, not a failure; running out of
        // the REQUEST budget (or any fetch problem) is reported.
        boolean pageCap = result.failure() == FailureCategory.BUDGET_EXHAUSTED
                && result.failureDetail() != null && result.failureDetail().startsWith("page cap");
        FailureCategory failure = pageCap ? null : result.failure();
        return ListingResult.partial(result.entries(), result.pagesFetched(), failure,
                failure == null ? "aggregator listing is never exhaustive" : result.failureDetail(), null);
    }

    /** Adds this page's cards; returns how many distinct cards it had (0 = past the last page). */
    static int parseListing(String html, Crawl crawl) {
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = CARD.matcher(html);
        while (m.find()) {
            if (seen.add(m.group(2))) {
                crawl.add(ListingEntry.partial(m.group(2), "https://" + HOST + m.group(1), null, Map.of()));
            }
        }
        if (seen.isEmpty() && !html.contains("__next_f")) throw new Parsing.MalformedException("not a Tsenta listing page");
        return seen.size();
    }

    /** Minimal robots.txt evaluation for the "*" group: longest matching Allow/Disallow wins. */
    static boolean robotsAllow(String robots, String path) {
        boolean inStar = false, groupHasRules = false;
        int bestLen = -1;
        boolean allowed = true;
        for (String raw : robots.split("\\r?\\n")) {
            String line = raw.replaceAll("#.*$", "").strip();
            if (line.isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).strip();
            if (key.equals("user-agent")) {
                if (groupHasRules) { inStar = false; groupHasRules = false; }
                if (value.equals("*")) inStar = true;
                continue;
            }
            if (!key.equals("allow") && !key.equals("disallow")) continue;
            groupHasRules = true;
            if (!inStar || value.isEmpty()) continue;
            if (path.startsWith(value) && value.length() > bestLen) {
                bestLen = value.length();
                allowed = key.equals("allow");
            } else if (path.startsWith(value) && value.length() == bestLen && key.equals("allow")) {
                allowed = true;
            }
        }
        return allowed;
    }

    @Override
    public NormalizedJob detail(BoardRef board, ListingEntry entry, CrawlContext context) {
        String html = context.http().get(Parsing.uri(entry.jobUrl()), SafeHttpClient.Accept.HTML);
        return parseDetail(html, entry.externalId(), entry.jobUrl(), context);
    }

    static NormalizedJob parseDetail(String html, String uuid, String tsentaUrl, CrawlContext context) {
        if (!UUID.matcher(uuid).matches()) throw new Parsing.MalformedException("invalid posting id");
        JsonNode job = rscJob(html, uuid);
        Document doc = Parsing.html(html, tsentaUrl);
        List<JsonNode> ld = Parsing.jsonLdJobPostings(doc);
        JsonNode posting = ld.isEmpty() ? null : ld.get(0);
        if (job == null && posting == null) throw new Parsing.MalformedException("no structured job data on the page");

        NormalizedJob.Builder b = posting != null
                ? Parsing.fromJsonLd(posting, uuid, context.now())
                : NormalizedJob.builder(uuid, null).description("");
        if (job != null) {
            b.title(Parsing.text(job, "title"));
            b.employer(Normalize.first(Parsing.text(job, "companyName"), posting == null ? null : Parsing.textAt(posting, "/hiringOrganization/name")));
            List<String> locations = Parsing.textList(job.get("locations"));
            if (!locations.isEmpty()) b.location(Normalize.joinPlaces(locations));
            List<String> cities = Parsing.textList(job.get("cities"));
            List<String> countries = Parsing.textList(job.get("countries"));
            boolean single = locations.size() <= 1;
            b.city(single && cities.size() == 1 ? cities.get(0) : null);
            b.country(single && countries.size() == 1 ? countries.get(0) : null);
            String workplace = Parsing.text(job, "workplaceType");
            if (workplace != null) b.remote(Normalize.remoteType(workplace.replace("_", "")));
            String employment = Parsing.text(job, "employmentType");
            if (employment != null) b.employmentType(employment);
            String currency = Normalize.currencyOrNull(Parsing.text(job, "salaryCurrency"));
            if (currency != null) {
                b.salary(Normalize.decimalOrNull(Parsing.text(job, "salaryMin")), Normalize.decimalOrNull(Parsing.text(job, "salaryMax")),
                        currency, Parsing.text(job, "salaryPeriod"));
            }
            String posted = Parsing.text(job, "datePosted");
            if (posted != null) b.postedAt(Normalize.postedAt(posted, context.now()));
            if (job.has("active") && !job.path("active").asBoolean(true)) b.active(false);
            b.applicationUrl(employerUrl(Parsing.text(job, "url")));
        } else {
            b.applicationUrl(null);
        }
        return b.sourceUrl(tsentaUrl).build();
    }

    /**
     * The original employer URL, or null. Anything on a Tsenta host (including
     * the dashboard "apply" link) is rejected: it is Tsenta's flow, not the
     * employer's application page.
     */
    static String employerUrl(String value) {
        String url = Normalize.httpUrlOrNull(value);
        if (url == null) return null;
        String host = Parsing.host(URI.create(url));
        if (host.equals(HOST) || host.endsWith("." + HOST)) return null;
        return url;
    }

    /**
     * Concatenates the string chunks of every {@code self.__next_f.push([n,"..."])}
     * call. Scanned linearly (not with a regex — chunks are hundreds of KB and a
     * backtracking pattern overflows the stack); each literal is decoded with a
     * JSON parser, never evaluated.
     */
    static String flightPayload(String html) {
        StringBuilder flight = new StringBuilder();
        String marker = "self.__next_f.push([";
        int at = html.indexOf(marker);
        while (at >= 0) {
            int i = at + marker.length();
            while (i < html.length() && Character.isDigit(html.charAt(i))) i++;
            if (i + 1 < html.length() && html.charAt(i) == ',' && html.charAt(i + 1) == '"') {
                int start = i + 1;
                int j = start + 1;
                boolean escaped = false;
                for (; j < html.length(); j++) {
                    char c = html.charAt(j);
                    if (escaped) escaped = false;
                    else if (c == '\\') escaped = true;
                    else if (c == '"') break;
                }
                if (j < html.length()) {
                    try {
                        JsonNode literal = Parsing.json(html.substring(start, j + 1));
                        if (literal.isTextual()) flight.append(literal.asText());
                    } catch (Parsing.MalformedException ignored) {
                        // a chunk we cannot decode does not invalidate the rest
                    }
                }
                at = html.indexOf(marker, j);
            } else {
                at = html.indexOf(marker, i);
            }
        }
        return flight.toString();
    }

    /** The {@code job} object for this posting from the decoded RSC flight payload. */
    static JsonNode rscJob(String html, String uuid) {
        String payload = flightPayload(html);
        int at = payload.indexOf("\"job\":{\"id\":\"" + uuid + "\"");
        if (at < 0) return null;
        Optional<String> object = Parsing.balancedObject(payload, at + 6);
        if (object.isEmpty()) return null;
        try {
            return Parsing.json(object.get());
        } catch (Parsing.MalformedException e) {
            return null;
        }
    }
}
