package com.personal.jobagent.discovery.platform.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import com.personal.jobagent.discovery.platform.BoardAdapter;
import com.personal.jobagent.discovery.platform.BoardConfigException;
import com.personal.jobagent.discovery.platform.NormalizedJob;
import com.personal.jobagent.discovery.platform.Normalize;
import com.personal.jobagent.discovery.platform.Parsing;
import com.personal.jobagent.discovery.platform.PlatformKind;
import com.personal.jobagent.discovery.platform.SafeHttpClient;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Platforms whose public endpoint returns the WHOLE board, descriptions
 * included, in one JSON response. A successful response is therefore a
 * complete listing. Each was verified live on 2026-10-10 (tenant in brackets):
 * Workable [huggingface], Gem [gem], Pinpoint [workwithus], Recruitee
 * [vandebron], Zoho Recruit [sehyog.zohorecruit.in].
 */
public final class SinglePayloadAdapters {

    private SinglePayloadAdapters() {
    }

    /** Base: one GET, one array, every element a complete posting. */
    abstract static class Single implements BoardAdapter {
        abstract URI endpoint(BoardRef board);

        abstract JsonNode postings(JsonNode root, Crawl crawl);

        abstract NormalizedJob parse(JsonNode posting, CrawlContext context, String employer);

        @Override
        public ListingResult list(BoardRef board, CrawlContext context) {
            Crawl crawl = new Crawl(context);
            return crawl.run(() -> {
                crawl.beforePage();
                JsonNode root = Parsing.json(context.http().get(endpoint(board), SafeHttpClient.Accept.JSON));
                crawl.afterPage();
                JsonNode postings = postings(root, crawl);
                if (postings == null || !postings.isArray()) throw new Parsing.MalformedException("postings array missing");
                for (JsonNode posting : postings) {
                    NormalizedJob job = parse(posting, context, crawl.employerName());
                    if (job != null && job.externalId() != null && job.title() != null) crawl.add(ListingEntry.complete(job));
                }
            });
        }

        @Override
        public NormalizedJob detail(BoardRef board, ListingEntry entry, CrawlContext context) {
            throw new UnsupportedOperationException(kind() + " listings are complete");
        }
    }

    static Optional<BoardAdapter.BoardRef> subdomainOf(URI url, String suffix, BoardAdapter adapter) {
        String host = Parsing.host(url);
        if (!host.endsWith(suffix)) return Optional.empty();
        String sub = host.substring(0, host.length() - suffix.length());
        if (sub.isEmpty() || sub.contains(".") || sub.equals("www") || sub.equals("api") || sub.equals("app")) {
            return Optional.empty();
        }
        return Optional.of(adapter.parseIdentifier(sub));
    }

    // ------------------------------------------------------------------------------------------
    public static class Workable extends Single {
        @Override public PlatformKind kind() { return PlatformKind.WORKABLE; }

        @Override
        public Optional<BoardRef> recognize(URI url) {
            String host = Parsing.host(url);
            List<String> s = Parsing.pathSegments(url);
            if (host.equals("apply.workable.com") && !s.isEmpty() && !s.get(0).equals("j") && !s.get(0).equals("api")) {
                return Optional.of(parseIdentifier(s.get(0)));
            }
            return subdomainOf(url, ".workable.com", this).filter(r -> !r.identifier().equals("apply")
                    && !r.identifier().equals("jobs"));
        }

        @Override
        public BoardRef parseIdentifier(String id) {
            return new BoardRef(kind(), Parsing.requireSlug(id, "Workable account", "[a-z0-9-]{1,100}"), Map.of());
        }

        @Override
        URI endpoint(BoardRef board) {
            return Parsing.uri("https://apply.workable.com/api/v1/widget/accounts/" + Parsing.enc(board.identifier()) + "?details=true");
        }

        @Override
        JsonNode postings(JsonNode root, Crawl crawl) {
            crawl.employerFromBoard(Parsing.text(root, "name"));
            return root.get("jobs");
        }

        @Override
        NormalizedJob parse(JsonNode p, CrawlContext context, String employer) {
            List<String> places = new ArrayList<>();
            for (JsonNode loc : Parsing.iterable(p.get("locations"))) {
                if (loc.path("hidden").asBoolean(false)) continue;
                String joined = Normalize.join(Parsing.text(loc, "city"), Parsing.text(loc, "region"), Parsing.text(loc, "country"));
                if (joined != null) places.add(joined);
            }
            return NormalizedJob.builder(Parsing.text(p, "shortcode"), Parsing.text(p, "title"))
                    .employer(employer)
                    .location(places.isEmpty() ? Normalize.join(Parsing.text(p, "city"), Parsing.text(p, "state"), Parsing.text(p, "country"))
                            : Normalize.joinPlaces(places))
                    .city(Parsing.text(p, "city"))
                    .country(Parsing.text(p, "country"))
                    .remote(p.path("telecommuting").asBoolean(false) ? "REMOTE" : null)
                    .employmentType(Parsing.text(p, "employment_type"))
                    .description(Normalize.htmlToText(Parsing.text(p, "description")))
                    .applicationUrl(Normalize.first(Parsing.text(p, "application_url"), Parsing.text(p, "url")))
                    .sourceUrl(Parsing.text(p, "url"))
                    .postedAt(Normalize.postedAt(Parsing.text(p, "published_on"), context.now()))
                    .build();
        }
    }

    // ------------------------------------------------------------------------------------------
    public static class Gem extends Single {
        @Override public PlatformKind kind() { return PlatformKind.GEM; }

        @Override
        public Optional<BoardRef> recognize(URI url) {
            String host = Parsing.host(url);
            List<String> s = Parsing.pathSegments(url);
            if (host.equals("jobs.gem.com") && !s.isEmpty()) return Optional.of(parseIdentifier(s.get(0)));
            if (host.equals("api.gem.com") && s.size() >= 3 && s.get(0).equals("job_board")) {
                return Optional.of(parseIdentifier(s.get(2)));
            }
            return Optional.empty();
        }

        @Override
        public BoardRef parseIdentifier(String id) {
            return new BoardRef(kind(), Parsing.requireSlug(id, "Gem job board", "[A-Za-z0-9_-]{1,100}"), Map.of());
        }

        @Override
        URI endpoint(BoardRef board) {
            return Parsing.uri("https://api.gem.com/job_board/v0/" + Parsing.enc(board.identifier()) + "/job_posts/");
        }

        @Override
        JsonNode postings(JsonNode root, Crawl crawl) {
            return root;
        }

        @Override
        NormalizedJob parse(JsonNode p, CrawlContext context, String employer) {
            String plain = Parsing.text(p, "content_plain");
            return NormalizedJob.builder(Parsing.text(p, "id"), Parsing.text(p, "title"))
                    .employer(employer)
                    .location(Parsing.textAt(p, "/location/name"))
                    .remote(Normalize.remoteType(Parsing.text(p, "location_type")))
                    .employmentType(Parsing.text(p, "employment_type"))
                    .description(plain != null ? plain : Normalize.htmlToText(Parsing.text(p, "content")))
                    .applicationUrl(Parsing.text(p, "absolute_url"))
                    .sourceUrl(Parsing.text(p, "absolute_url"))
                    .postedAt(Normalize.postedAt(Parsing.text(p, "first_published_at"), context.now()))
                    .requisitionId(Parsing.text(p, "requisition_id"))
                    .build();
        }
    }

    // ------------------------------------------------------------------------------------------
    public static class Pinpoint extends Single {
        @Override public PlatformKind kind() { return PlatformKind.PINPOINT; }

        @Override
        public Optional<BoardRef> recognize(URI url) {
            return subdomainOf(url, ".pinpointhq.com", this);
        }

        @Override
        public BoardRef parseIdentifier(String id) {
            return new BoardRef(kind(), Parsing.requireSlug(id, "Pinpoint subdomain", "[a-z0-9-]{1,63}"), Map.of());
        }

        @Override
        URI endpoint(BoardRef board) {
            return Parsing.uri("https://" + board.identifier() + ".pinpointhq.com/postings.json");
        }

        @Override
        JsonNode postings(JsonNode root, Crawl crawl) {
            return root.get("data");
        }

        @Override
        NormalizedJob parse(JsonNode p, CrawlContext context, String employer) {
            StringBuilder d = new StringBuilder(Normalize.htmlToText(Parsing.text(p, "description")));
            for (String section : List.of("key_responsibilities", "skills_knowledge_expertise", "benefits")) {
                String html = Parsing.text(p, section);
                if (html == null) continue;
                String header = Parsing.text(p, section + "_header");
                d.append("\n\n").append(header == null ? "" : header + "\n").append(Normalize.htmlToText(html));
            }
            NormalizedJob.Builder b = NormalizedJob.builder(Parsing.text(p, "id"), Parsing.text(p, "title"))
                    .employer(employer)
                    .location(Normalize.join(Parsing.textAt(p, "/location/city"), Parsing.textAt(p, "/location/province"),
                            Parsing.textAt(p, "/location/name")))
                    .city(Parsing.textAt(p, "/location/city"))
                    .remote(Normalize.remoteType(Parsing.text(p, "workplace_type")))
                    .employmentType(Parsing.text(p, "employment_type_text"))
                    .description(d.toString().strip())
                    .applicationUrl(Parsing.text(p, "url"))
                    .sourceUrl(Parsing.text(p, "url"))
                    .requisitionId(Parsing.textAt(p, "/job/requisition_id"));
            if (p.path("compensation_visible").asBoolean(false)) {
                b.salary(Normalize.decimalOrNull(Parsing.text(p, "compensation_minimum")),
                        Normalize.decimalOrNull(Parsing.text(p, "compensation_maximum")),
                        Parsing.text(p, "compensation_currency"), Parsing.text(p, "compensation_frequency"));
            }
            return b.build();
        }
    }

    // ------------------------------------------------------------------------------------------
    public static class Recruitee extends Single {
        @Override public PlatformKind kind() { return PlatformKind.RECRUITEE; }

        @Override
        public Optional<BoardRef> recognize(URI url) {
            return subdomainOf(url, ".recruitee.com", this);
        }

        @Override
        public BoardRef parseIdentifier(String id) {
            return new BoardRef(kind(), Parsing.requireSlug(id, "Recruitee subdomain", "[a-z0-9-]{1,63}"), Map.of());
        }

        @Override
        URI endpoint(BoardRef board) {
            return Parsing.uri("https://" + board.identifier() + ".recruitee.com/api/offers/");
        }

        @Override
        JsonNode postings(JsonNode root, Crawl crawl) {
            return root.get("offers");
        }

        @Override
        NormalizedJob parse(JsonNode p, CrawlContext context, String employer) {
            String remote = p.path("remote").asBoolean(false) ? "REMOTE"
                    : p.path("hybrid").asBoolean(false) ? "HYBRID"
                    : p.path("on_site").asBoolean(false) ? "ONSITE" : null;
            List<String> places = new ArrayList<>();
            for (JsonNode loc : Parsing.iterable(p.get("locations"))) {
                String joined = Normalize.join(Parsing.text(loc, "city"), Parsing.text(loc, "state"), Parsing.text(loc, "country"));
                if (joined != null) places.add(joined);
            }
            String description = (Normalize.htmlToText(Parsing.text(p, "description")) + "\n\n"
                    + Normalize.htmlToText(Parsing.text(p, "requirements"))).strip();
            NormalizedJob.Builder b = NormalizedJob.builder(Parsing.text(p, "id"), Parsing.text(p, "title"))
                    .employer(Normalize.first(employer, Parsing.text(p, "company_name")))
                    .location(places.isEmpty() ? Parsing.text(p, "location") : Normalize.joinPlaces(places))
                    .city(Parsing.text(p, "city"))
                    .country(Parsing.text(p, "country_code"))
                    .remote(remote)
                    .employmentType(Parsing.text(p, "employment_type_code"))
                    .description(description)
                    .applicationUrl(Normalize.first(Parsing.text(p, "careers_apply_url"), Parsing.text(p, "careers_url")))
                    .sourceUrl(Parsing.text(p, "careers_url"))
                    .postedAt(Normalize.postedAt(Parsing.text(p, "published_at"), context.now()));
            JsonNode salary = p.get("salary");
            if (salary != null && salary.isObject()) {
                b.salary(Normalize.decimalOrNull(Parsing.text(salary, "min")), Normalize.decimalOrNull(Parsing.text(salary, "max")),
                        Parsing.text(salary, "currency"), Parsing.text(salary, "period"));
            }
            return b.build();
        }
    }

    // ------------------------------------------------------------------------------------------
    /**
     * Zoho Recruit public career-site API. {@code Date_Opened} is rendered in
     * the tenant's locale (observed "02/06/2026") and is therefore NOT parsed:
     * the posting date stays unknown rather than possibly wrong by months.
     *
     * <p>The validated tenant had 8 openings, so whether this public endpoint
     * paginates larger boards has not been observed. The listing is therefore
     * reported PARTIAL (never complete) and can never drive removals.
     */
    public static class ZohoRecruit extends Single {
        @Override
        public ListingResult list(BoardRef board, CrawlContext context) {
            ListingResult result = super.list(board, context);
            if (!result.complete()) return result;
            return ListingResult.partial(result.entries(), result.pagesFetched(), null,
                    "pagination of the public endpoint is unverified; absence is not treated as removal", result.employerName());
        }

        private static final java.util.regex.Pattern HOST =
                java.util.regex.Pattern.compile("^[a-z0-9-]{1,63}\\.zohorecruit\\.(com|in|eu|com\\.au|jp|com\\.cn|ca|sa|uk)$");

        @Override public PlatformKind kind() { return PlatformKind.ZOHO_RECRUIT; }

        @Override
        public Optional<BoardRef> recognize(URI url) {
            String host = Parsing.host(url);
            return HOST.matcher(host).matches() ? Optional.of(parseIdentifier(host)) : Optional.empty();
        }

        @Override
        public BoardRef parseIdentifier(String id) {
            String host = id == null ? "" : id.strip().toLowerCase(java.util.Locale.ROOT);
            if (!HOST.matcher(host).matches()) {
                throw new BoardConfigException("Zoho Recruit board must be the career-site host, e.g. acme.zohorecruit.com");
            }
            return new BoardRef(kind(), host, Map.of());
        }

        @Override
        URI endpoint(BoardRef board) {
            return Parsing.uri("https://" + board.identifier() + "/recruit/v2/public/Job_Openings?pagename=Careers&source=CareerSite");
        }

        @Override
        JsonNode postings(JsonNode root, Crawl crawl) {
            if (!"success".equalsIgnoreCase(Parsing.text(root, "code"))) throw new Parsing.MalformedException("Zoho response not successful");
            return root.get("data");
        }

        @Override
        NormalizedJob parse(JsonNode p, CrawlContext context, String employer) {
            if (p.has("Publish") && !p.path("Publish").asBoolean(true)) return null;
            String url = Parsing.text(p, "$url");
            return NormalizedJob.builder(Parsing.text(p, "id"), Normalize.first(Parsing.text(p, "Posting_Title"), Parsing.text(p, "Job_Opening_Name")))
                    .employer(employer)
                    .location(Normalize.join(Parsing.text(p, "City"), Parsing.text(p, "State"), Parsing.text(p, "Country")))
                    .city(Parsing.text(p, "City"))
                    .country(Parsing.text(p, "Country"))
                    .remote("yes".equalsIgnoreCase(Parsing.text(p, "Remote_Job")) || "true".equalsIgnoreCase(Parsing.text(p, "Remote_Job")) ? "REMOTE" : null)
                    .employmentType(Parsing.text(p, "Job_Type"))
                    .description(Normalize.htmlToText(Parsing.text(p, "Job_Description")))
                    .applicationUrl(url)
                    .sourceUrl(url)
                    .build();
        }
    }
}
