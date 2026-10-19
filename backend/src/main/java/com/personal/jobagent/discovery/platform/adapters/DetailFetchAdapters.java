package com.personal.jobagent.discovery.platform.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import com.personal.jobagent.discovery.platform.BoardAdapter;
import com.personal.jobagent.discovery.platform.BoardConfigException;
import com.personal.jobagent.discovery.platform.NormalizedJob;
import com.personal.jobagent.discovery.platform.Normalize;
import com.personal.jobagent.discovery.platform.Parsing;
import com.personal.jobagent.discovery.platform.PlatformKind;
import com.personal.jobagent.discovery.platform.SafeHttpClient;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Platforms whose listing carries identity and summary fields but not the
 * full description, so each new posting needs one detail request. All verified
 * live 2026-10-10: SmartRecruiters [BoschGroup], Oracle Recruiting Cloud
 * [eeho.fa.us2.oraclecloud.com / CX_45001], UKG Pro (UltiPro)
 * [recruiting2.ultipro.com / HEL1006HELI], Phenom [careers.phenom.com],
 * BreezyHR [breezy], Paylocity [3876b9cb-3f4c-41a0-a88e-91ef0efab17e].
 */
public final class DetailFetchAdapters {

    private DetailFetchAdapters() {
    }

    // ==========================================================================================
    public static class SmartRecruiters implements BoardAdapter {
        static final int PAGE = 100;

        @Override public PlatformKind kind() { return PlatformKind.SMARTRECRUITERS; }

        @Override
        public Optional<BoardRef> recognize(URI url) {
            String host = Parsing.host(url);
            List<String> s = Parsing.pathSegments(url);
            if ((host.equals("jobs.smartrecruiters.com") || host.equals("careers.smartrecruiters.com")) && !s.isEmpty()
                    && !s.get(0).equalsIgnoreCase("oneclick-ui")) {
                return Optional.of(parseIdentifier(s.get(0)));
            }
            if (host.equals("api.smartrecruiters.com") && s.size() >= 3 && s.get(1).equals("companies")) {
                return Optional.of(parseIdentifier(s.get(2)));
            }
            return Optional.empty();
        }

        @Override
        public BoardRef parseIdentifier(String id) {
            return new BoardRef(kind(), Parsing.requireSlug(id, "SmartRecruiters company identifier", "[A-Za-z0-9_-]{1,100}"), Map.of());
        }

        @Override
        public ListingResult list(BoardRef board, CrawlContext context) {
            Crawl crawl = new Crawl(context);
            return crawl.run(() -> {
                int total = -1;
                for (int offset = 0; ; offset += PAGE) {
                    crawl.beforePage();
                    JsonNode page = Parsing.json(context.http().get(Parsing.uri("https://api.smartrecruiters.com/v1/companies/"
                            + Parsing.enc(board.identifier()) + "/postings?limit=" + PAGE + "&offset=" + offset), SafeHttpClient.Accept.JSON));
                    crawl.afterPage();
                    JsonNode content = page.get("content");
                    if (content == null || !content.isArray()) throw new Parsing.MalformedException("content missing");
                    if (total < 0) total = page.path("totalFound").asInt(-1);
                    for (JsonNode p : content) {
                        String id = Parsing.text(p, "id");
                        String title = Parsing.text(p, "name");
                        if (id == null || title == null) continue;
                        crawl.employerFromBoard(Parsing.textAt(p, "/company/name"));
                        Map<String, String> hints = new HashMap<>();
                        putIf(hints, "location", Parsing.textAt(p, "/location/fullLocation"));
                        putIf(hints, "city", Parsing.textAt(p, "/location/city"));
                        putIf(hints, "country", Parsing.textAt(p, "/location/country"));
                        putIf(hints, "remote", p.at("/location/remote").asBoolean(false) ? "REMOTE"
                                : p.at("/location/hybrid").asBoolean(false) ? "HYBRID" : null);
                        putIf(hints, "employmentType", Parsing.textAt(p, "/typeOfEmployment/label"));
                        putIf(hints, "postedAt", Parsing.text(p, "releasedDate"));
                        putIf(hints, "requisitionId", Parsing.text(p, "refNumber"));
                        crawl.add(ListingEntry.partial(id, null, title, hints));
                    }
                    if (content.isEmpty() || total < 0 || offset + PAGE >= total) return;
                }
            });
        }

        @Override
        public NormalizedJob detail(BoardRef board, ListingEntry entry, CrawlContext context) {
            JsonNode p = Parsing.json(context.http().get(Parsing.uri("https://api.smartrecruiters.com/v1/companies/"
                    + Parsing.enc(board.identifier()) + "/postings/" + Parsing.enc(entry.externalId())), SafeHttpClient.Accept.JSON));
            return parseDetail(p, entry, context);
        }

        static NormalizedJob parseDetail(JsonNode p, ListingEntry entry, CrawlContext context) {
            JsonNode sections = p.at("/jobAd/sections");
            if (!sections.isObject()) throw new Parsing.MalformedException("jobAd sections missing");
            StringBuilder d = new StringBuilder();
            for (String key : List.of("companyDescription", "jobDescription", "qualifications", "additionalInformation")) {
                JsonNode s = sections.get(key);
                if (s == null) continue;
                String title = Parsing.text(s, "title");
                String text = Normalize.htmlToText(Parsing.text(s, "text"));
                if (text.isBlank()) continue;
                d.append(title == null ? "" : title + "\n").append(text).append("\n\n");
            }
            Map<String, String> h = entry.hints();
            return NormalizedJob.builder(entry.externalId(), Normalize.first(Parsing.text(p, "name"), entry.title()))
                    .employer(Normalize.first(context.employerName(), Parsing.textAt(p, "/company/name")))
                    .location(Normalize.first(Parsing.textAt(p, "/location/fullLocation"), h.get("location")))
                    .city(Normalize.first(Parsing.textAt(p, "/location/city"), h.get("city")))
                    .country(Normalize.first(Parsing.textAt(p, "/location/country"), h.get("country")))
                    .remote(p.at("/location/remote").asBoolean(false) ? "REMOTE"
                            : p.at("/location/hybrid").asBoolean(false) ? "HYBRID" : h.get("remote"))
                    .employmentType(Normalize.first(Parsing.textAt(p, "/typeOfEmployment/label"), h.get("employmentType")))
                    .description(d.toString().strip())
                    .applicationUrl(Normalize.first(Parsing.text(p, "applyUrl"), Parsing.text(p, "postingUrl")))
                    .sourceUrl(Parsing.text(p, "postingUrl"))
                    .postedAt(Normalize.postedAt(Normalize.first(Parsing.text(p, "releasedDate"), h.get("postedAt")), context.now()))
                    .requisitionId(Normalize.first(Parsing.text(p, "refNumber"), h.get("requisitionId")))
                    .active(p.has("active") && !p.path("active").asBoolean(true) ? Boolean.FALSE : null)
                    .build();
        }
    }

    // ==========================================================================================
    /** Oracle Recruiting Cloud (HCM Candidate Experience) public REST resources. */
    public static class OracleCloud implements BoardAdapter {
        static final int PAGE = 25;
        private static final Pattern HOST = Pattern.compile("^[a-z0-9-]{1,63}(\\.[a-z0-9-]{1,63}){0,4}\\.oraclecloud\\.com$");
        private static final Pattern IDENTIFIER = Pattern.compile("^([a-z0-9.-]+\\.oraclecloud\\.com)/([A-Za-z0-9_]{1,40})$");

        @Override public PlatformKind kind() { return PlatformKind.ORACLE_CLOUD; }

        @Override
        public Optional<BoardRef> recognize(URI url) {
            String host = Parsing.host(url);
            if (!HOST.matcher(host).matches()) return Optional.empty();
            List<String> s = Parsing.pathSegments(url);
            int i = s.indexOf("sites");
            if (i >= 0 && s.size() > i + 1) return Optional.of(parseIdentifier(host + "/" + s.get(i + 1)));
            Matcher m = Pattern.compile("siteNumber=([A-Za-z0-9_]+)").matcher(url.getRawQuery() == null ? "" : url.getRawQuery());
            return m.find() ? Optional.of(parseIdentifier(host + "/" + m.group(1))) : Optional.empty();
        }

        @Override
        public BoardRef parseIdentifier(String id) {
            Matcher m = IDENTIFIER.matcher(id == null ? "" : id.strip());
            if (!m.matches() || !HOST.matcher(m.group(1)).matches()) {
                throw new BoardConfigException("Oracle Cloud board must be host/siteNumber, e.g. eeho.fa.us2.oraclecloud.com/CX_45001");
            }
            return new BoardRef(kind(), m.group(1) + "/" + m.group(2), Map.of("host", m.group(1), "site", m.group(2)));
        }

        @Override
        public ListingResult list(BoardRef board, CrawlContext context) {
            Crawl crawl = new Crawl(context);
            String base = "https://" + board.param("host") + "/hcmRestApi/resources/latest/recruitingCEJobRequisitions"
                    + "?onlyData=true&expand=requisitionList.secondaryLocations&finder=findReqs;siteNumber=" + board.param("site");
            return crawl.run(() -> {
                int total = -1;
                for (int offset = 0; ; offset += PAGE) {
                    crawl.beforePage();
                    JsonNode root = Parsing.json(context.http().get(Parsing.uri(base + ",limit=" + PAGE + ",offset=" + offset
                            + ",sortBy=POSTING_DATES_DESC"), SafeHttpClient.Accept.JSON));
                    crawl.afterPage();
                    JsonNode search = root.path("items").path(0);
                    JsonNode reqs = search.get("requisitionList");
                    if (reqs == null || !reqs.isArray()) throw new Parsing.MalformedException("requisitionList missing");
                    if (total < 0) total = search.path("TotalJobsCount").asInt(-1);
                    for (JsonNode r : reqs) {
                        String id = Parsing.text(r, "Id");
                        String title = Parsing.text(r, "Title");
                        if (id == null || title == null) continue;
                        Map<String, String> hints = new HashMap<>();
                        putIf(hints, "location", Parsing.text(r, "PrimaryLocation"));
                        putIf(hints, "country", Parsing.text(r, "PrimaryLocationCountry"));
                        putIf(hints, "postedAt", Parsing.text(r, "PostedDate"));
                        putIf(hints, "remote", Normalize.remoteType(Parsing.text(r, "WorkplaceType")));
                        crawl.add(ListingEntry.partial(id, jobUrl(board, id), title, hints));
                    }
                    if (reqs.isEmpty() || total < 0 || offset + PAGE >= total) return;
                }
            });
        }

        static String jobUrl(BoardRef board, String id) {
            return "https://" + board.param("host") + "/hcmUI/CandidateExperience/en/sites/" + board.param("site") + "/job/" + id;
        }

        @Override
        public NormalizedJob detail(BoardRef board, ListingEntry entry, CrawlContext context) {
            String url = "https://" + board.param("host") + "/hcmRestApi/resources/latest/recruitingCEJobRequisitionDetails"
                    + "?expand=all&onlyData=true&finder=ById;Id=%22" + Parsing.enc(entry.externalId()) + "%22,siteNumber=" + board.param("site");
            return parseDetail(Parsing.json(context.http().get(Parsing.uri(url), SafeHttpClient.Accept.JSON)), board, entry, context);
        }

        static NormalizedJob parseDetail(JsonNode root, BoardRef board, ListingEntry entry, CrawlContext context) {
            JsonNode d = root.path("items").path(0);
            if (!d.isObject()) throw new Parsing.MalformedException("requisition detail missing");
            StringBuilder text = new StringBuilder();
            for (String f : List.of("ExternalDescriptionStr", "ExternalResponsibilitiesStr", "ExternalQualificationsStr",
                    "CorporateDescriptionStr")) {
                String t = Normalize.htmlToText(Parsing.text(d, f));
                if (!t.isBlank()) text.append(t).append("\n\n");
            }
            Map<String, String> h = entry.hints();
            String url = jobUrl(board, entry.externalId());
            return NormalizedJob.builder(entry.externalId(), Normalize.first(Parsing.text(d, "Title"), entry.title()))
                    .employer(Normalize.first(context.employerName(), Parsing.text(d, "LegalEmployer")))
                    .location(Normalize.first(Parsing.text(d, "PrimaryLocation"), h.get("location")))
                    .country(Normalize.first(Parsing.text(d, "PrimaryLocationCountry"), h.get("country")))
                    .remote(Normalize.first(Normalize.remoteType(Parsing.text(d, "WorkplaceType")), h.get("remote")))
                    .employmentType(Normalize.first(Parsing.text(d, "JobSchedule"), Parsing.text(d, "WorkerType")))
                    .description(text.toString().strip())
                    .applicationUrl(url)
                    .sourceUrl(url)
                    .postedAt(Normalize.postedAt(Normalize.first(Parsing.text(d, "ExternalPostedStartDate"), h.get("postedAt")), context.now()))
                    .requisitionId(Parsing.text(d, "RequisitionId"))
                    .build();
        }
    }

    // ==========================================================================================
    /** UKG Pro (UltiPro) Recruiting job boards. */
    public static class UltiPro implements BoardAdapter {
        static final int PAGE = 50;
        private static final Pattern HOST = Pattern.compile("^recruiting\\d{0,2}\\.ultipro\\.(com|ca)$");
        private static final Pattern GUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

        @Override public PlatformKind kind() { return PlatformKind.ULTIPRO; }

        @Override
        public Optional<BoardRef> recognize(URI url) {
            String host = Parsing.host(url);
            if (!HOST.matcher(host).matches()) return Optional.empty();
            List<String> s = Parsing.pathSegments(url);
            if (s.size() >= 3 && s.get(1).equalsIgnoreCase("JobBoard")) {
                return Optional.of(parseIdentifier(host + "/" + s.get(0) + "/" + s.get(2)));
            }
            return Optional.empty();
        }

        @Override
        public BoardRef parseIdentifier(String id) {
            String[] parts = (id == null ? "" : id.strip()).split("/");
            if (parts.length != 3 || !HOST.matcher(parts[0].toLowerCase(Locale.ROOT)).matches()
                    || !parts[1].matches("[A-Za-z0-9]{3,20}") || !GUID.matcher(parts[2]).matches()) {
                throw new BoardConfigException("UltiPro board must be host/tenant/boardGuid, e.g. recruiting2.ultipro.com/ACME1001/0000…");
            }
            String host = parts[0].toLowerCase(Locale.ROOT);
            String board = parts[2].toLowerCase(Locale.ROOT);
            return new BoardRef(kind(), host + "/" + parts[1] + "/" + board,
                    Map.of("host", host, "tenant", parts[1], "board", board));
        }

        private static String base(BoardRef b) {
            return "https://" + b.param("host") + "/" + b.param("tenant") + "/JobBoard/" + b.param("board");
        }

        @Override
        public ListingResult list(BoardRef board, CrawlContext context) {
            Crawl crawl = new Crawl(context);
            return crawl.run(() -> {
                int total = -1;
                for (int skip = 0; ; skip += PAGE) {
                    crawl.beforePage();
                    String body = "{\"opportunitySearch\":{\"Top\":" + PAGE + ",\"Skip\":" + skip + ",\"QueryString\":\"\","
                            + "\"OrderBy\":[{\"Value\":\"postedDateDesc\",\"PropertyName\":\"PostedDate\",\"Ascending\":false}],"
                            + "\"Filters\":[]},\"matchCriteria\":{\"PreferredJobs\":[],\"Educations\":[],\"LicenseAndCertifications\":[],"
                            + "\"Skills\":[],\"hasNoLicenses\":false,\"SkippedSkills\":[]}}";
                    JsonNode page = Parsing.json(context.http().postJson(Parsing.uri(base(board) + "/JobBoardView/LoadSearchResults"),
                            body, SafeHttpClient.Accept.JSON));
                    crawl.afterPage();
                    JsonNode opps = page.get("opportunities");
                    if (opps == null || !opps.isArray()) throw new Parsing.MalformedException("opportunities missing");
                    if (total < 0) total = page.path("totalCount").asInt(-1);
                    for (JsonNode o : opps) {
                        String id = Parsing.text(o, "Id");
                        String title = Parsing.text(o, "Title");
                        if (id == null || title == null) continue;
                        Map<String, String> hints = new HashMap<>();
                        putIf(hints, "location", locations(o.get("Locations")));
                        putIf(hints, "postedAt", Parsing.text(o, "PostedDate"));
                        putIf(hints, "requisitionId", Parsing.text(o, "RequisitionNumber"));
                        crawl.add(ListingEntry.partial(id, base(board) + "/OpportunityDetail?opportunityId=" + id, title, hints));
                    }
                    if (opps.isEmpty() || total < 0 || skip + PAGE >= total) return;
                }
            });
        }

        static String locations(JsonNode locations) {
            List<String> out = new ArrayList<>();
            for (JsonNode l : Parsing.iterable(locations)) {
                String joined = Normalize.join(Parsing.textAt(l, "/Address/City"), Parsing.textAt(l, "/Address/State/Code"),
                        Parsing.textAt(l, "/Address/Country/Name"));
                out.add(joined == null ? Parsing.text(l, "LocalizedName") : joined);
            }
            return Normalize.joinPlaces(out);
        }

        @Override
        public NormalizedJob detail(BoardRef board, ListingEntry entry, CrawlContext context) {
            String html = context.http().get(Parsing.uri(entry.jobUrl()), SafeHttpClient.Accept.HTML);
            return parseDetail(html, entry, context);
        }

        static NormalizedJob parseDetail(String html, ListingEntry entry, CrawlContext context) {
            int at = html.indexOf("CandidateOpportunityDetail(");
            if (at < 0) throw new Parsing.MalformedException("opportunity detail object missing");
            JsonNode o = Parsing.json(Parsing.balancedObject(html, at).orElseThrow(() ->
                    new Parsing.MalformedException("opportunity detail object unterminated")));
            Map<String, String> h = entry.hints();
            NormalizedJob.Builder b = NormalizedJob.builder(entry.externalId(), Normalize.first(Parsing.text(o, "Title"), entry.title()))
                    .employer(context.employerName())
                    .location(Normalize.first(locations(o.get("Locations")), h.get("location")))
                    .employmentType(o.path("FullTime").isBoolean() ? (o.path("FullTime").asBoolean() ? "Full-time" : "Part-time") : null)
                    .description(Normalize.htmlToText(Parsing.text(o, "Description")))
                    .applicationUrl(entry.jobUrl())
                    .sourceUrl(entry.jobUrl())
                    .postedAt(Normalize.postedAt(Normalize.first(Parsing.text(o, "PostedDate"), h.get("postedAt")), context.now()))
                    .requisitionId(Normalize.first(Parsing.text(o, "RequisitionNumber"), h.get("requisitionId")))
                    .active(o.path("OpportunityIsClosed").asBoolean(false) ? Boolean.FALSE : null);
            if (o.path("PayRangeVisible").asBoolean(false)) {
                b.salary(Normalize.decimalOrNull(Parsing.textAt(o, "/PayRange/PayRangeMinimum")),
                        Normalize.decimalOrNull(Parsing.textAt(o, "/PayRange/PayRangeMaximum")),
                        Parsing.text(o, "PayRangeCurrencyCode"), null);
            }
            return b.build();
        }
    }

    // ==========================================================================================
    /**
     * Phenom career sites. Phenom customers serve them from their OWN domains,
     * so the host is only accepted after the import flow has confirmed the
     * site answers Phenom's widgets protocol, and every fetch still passes the
     * SSRF guard.
     */
    public static class Phenom implements BoardAdapter {
        static final int PAGE = 10;
        private static final Pattern IDENTIFIER = Pattern.compile("^([a-z0-9-]{1,63}(\\.[a-z0-9-]{1,63}){1,5})/([a-z]{2})/([a-z]{2})$");

        @Override public PlatformKind kind() { return PlatformKind.PHENOM; }

        @Override
        public Optional<BoardRef> recognize(URI url) {
            String host = Parsing.host(url);
            if (!host.endsWith(".phenom.com") && !host.endsWith(".phenompeople.com")) return Optional.empty();
            return fromPath(host, url);
        }

        /** Phenom path shape /{cc}/{lang}/... on a host already confirmed to be Phenom. */
        public Optional<BoardRef> fromPath(String host, URI url) {
            List<String> s = Parsing.pathSegments(url);
            if (s.size() >= 2 && s.get(0).matches("[a-z]{2}") && s.get(1).matches("[a-z]{2}")) {
                return Optional.of(parseIdentifier(host + "/" + s.get(0) + "/" + s.get(1)));
            }
            return Optional.empty();
        }

        @Override
        public BoardRef parseIdentifier(String id) {
            Matcher m = IDENTIFIER.matcher(id == null ? "" : id.strip().toLowerCase(Locale.ROOT));
            if (!m.matches()) throw new BoardConfigException("Phenom board must be host/country/language, e.g. careers.acme.com/us/en");
            return new BoardRef(kind(), m.group(1) + "/" + m.group(3) + "/" + m.group(4),
                    Map.of("host", m.group(1), "country", m.group(3), "lang", m.group(4)));
        }

        @Override
        public ListingResult list(BoardRef board, CrawlContext context) {
            Crawl crawl = new Crawl(context);
            return crawl.run(() -> {
                int total = -1;
                for (int from = 0; ; from += PAGE) {
                    crawl.beforePage();
                    String body = "{\"lang\":\"" + board.param("lang") + "_" + board.param("country") + "\",\"deviceType\":\"desktop\","
                            + "\"country\":\"" + board.param("country") + "\",\"pageName\":\"search-results\",\"ddoKey\":\"refineSearch\","
                            + "\"sortBy\":\"\",\"subsearch\":\"\",\"from\":" + from + ",\"jobs\":true,\"counts\":true,\"all_fields\":[],"
                            + "\"size\":" + PAGE + ",\"clearAll\":false,\"jdsource\":\"facets\",\"isSliderEnable\":false,"
                            + "\"pageId\":\"page1\",\"siteType\":\"external\",\"keywords\":\"\",\"global\":true,"
                            + "\"selected_fields\":{},\"locationData\":{}}";
                    JsonNode root = Parsing.json(context.http().postJson(Parsing.uri("https://" + board.param("host") + "/widgets"),
                            body, SafeHttpClient.Accept.JSON));
                    crawl.afterPage();
                    JsonNode search = root.get("refineSearch");
                    JsonNode jobs = search == null ? null : search.at("/data/jobs");
                    if (jobs == null || !jobs.isArray()) throw new Parsing.MalformedException("refineSearch jobs missing");
                    if (total < 0) total = search.path("totalHits").asInt(-1);
                    for (JsonNode j : jobs) {
                        String id = Parsing.text(j, "jobId");
                        String title = Parsing.text(j, "title");
                        if (id == null || title == null) continue;
                        Map<String, String> hints = new HashMap<>();
                        putIf(hints, "location", Normalize.first(Parsing.text(j, "location"),
                                Normalize.join(Parsing.text(j, "city"), Parsing.text(j, "state"), Parsing.text(j, "country"))));
                        putIf(hints, "city", Parsing.text(j, "city"));
                        putIf(hints, "country", Parsing.text(j, "country"));
                        putIf(hints, "employmentType", Parsing.text(j, "type"));
                        putIf(hints, "postedAt", Parsing.text(j, "postedDate"));
                        putIf(hints, "requisitionId", Parsing.text(j, "reqId"));
                        String url = "https://" + board.param("host") + "/" + board.param("country") + "/" + board.param("lang")
                                + "/job/" + Parsing.enc(id);
                        crawl.add(ListingEntry.partial(id, url, title, hints));
                    }
                    if (jobs.isEmpty() || total < 0 || from + PAGE >= total) return;
                }
            });
        }

        @Override
        public NormalizedJob detail(BoardRef board, ListingEntry entry, CrawlContext context) {
            String html = context.http().get(Parsing.uri(entry.jobUrl()), SafeHttpClient.Accept.HTML);
            return jsonLdDetail(html, entry, context);
        }
    }

    // ==========================================================================================
    public static class BreezyHr implements BoardAdapter {
        @Override public PlatformKind kind() { return PlatformKind.BREEZYHR; }

        @Override
        public Optional<BoardRef> recognize(URI url) {
            return SinglePayloadAdapters.subdomainOf(url, ".breezy.hr", this);
        }

        @Override
        public BoardRef parseIdentifier(String id) {
            return new BoardRef(kind(), Parsing.requireSlug(id, "BreezyHR portal subdomain", "[a-z0-9-]{1,63}"), Map.of());
        }

        @Override
        public ListingResult list(BoardRef board, CrawlContext context) {
            Crawl crawl = new Crawl(context);
            return crawl.run(() -> {
                crawl.beforePage();
                JsonNode jobs = Parsing.json(context.http().get(Parsing.uri("https://" + board.identifier() + ".breezy.hr/json"),
                        SafeHttpClient.Accept.JSON));
                crawl.afterPage();
                if (!jobs.isArray()) throw new Parsing.MalformedException("expected a JSON array");
                for (JsonNode j : jobs) {
                    String id = Parsing.text(j, "id");
                    String title = Parsing.text(j, "name");
                    if (id == null || title == null) continue;
                    crawl.employerFromBoard(Parsing.textAt(j, "/company/name"));
                    Map<String, String> hints = new HashMap<>();
                    putIf(hints, "location", Parsing.textAt(j, "/location/name"));
                    putIf(hints, "city", Parsing.textAt(j, "/location/city"));
                    putIf(hints, "country", Parsing.textAt(j, "/location/country/name"));
                    putIf(hints, "remote", j.at("/location/is_remote").asBoolean(false) ? "REMOTE" : null);
                    putIf(hints, "employmentType", Parsing.textAt(j, "/type/name"));
                    putIf(hints, "postedAt", Parsing.text(j, "published_date"));
                    crawl.add(ListingEntry.partial(id, Parsing.text(j, "url"), title, hints));
                }
            });
        }

        @Override
        public NormalizedJob detail(BoardRef board, ListingEntry entry, CrawlContext context) {
            if (entry.jobUrl() == null) throw new Parsing.MalformedException("posting URL missing");
            return parseDetail(context.http().get(Parsing.uri(entry.jobUrl()), SafeHttpClient.Accept.HTML), entry, context);
        }

        static NormalizedJob parseDetail(String html, ListingEntry entry, CrawlContext context) {
            Document doc = Parsing.html(html, entry.jobUrl());
            Element description = doc.selectFirst("div.description");
            if (description == null) throw new Parsing.MalformedException("description block missing");
            Map<String, String> h = entry.hints();
            return NormalizedJob.builder(entry.externalId(), entry.title())
                    .employer(context.employerName())
                    .location(h.get("location")).city(h.get("city")).country(h.get("country"))
                    .remote(h.get("remote"))
                    .employmentType(h.get("employmentType"))
                    .description(Normalize.htmlToText(description.html()))
                    .applicationUrl(entry.jobUrl())
                    .sourceUrl(entry.jobUrl())
                    .postedAt(Normalize.postedAt(h.get("postedAt"), context.now()))
                    .build();
        }
    }

    // ==========================================================================================
    /** Paylocity Recruiting public job list (window.pageData) + JSON-LD job pages. */
    public static class Paylocity implements BoardAdapter {
        private static final Pattern GUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

        @Override public PlatformKind kind() { return PlatformKind.PAYLOCITY; }

        @Override
        public Optional<BoardRef> recognize(URI url) {
            if (!Parsing.host(url).equals("recruiting.paylocity.com")) return Optional.empty();
            List<String> s = Parsing.pathSegments(url).stream().map(x -> x.toLowerCase(Locale.ROOT)).toList();
            int i = s.indexOf("all");
            if (s.size() > i + 1 && i >= 0 && GUID.matcher(s.get(i + 1)).matches()) return Optional.of(parseIdentifier(s.get(i + 1)));
            return Optional.empty();
        }

        @Override
        public BoardRef parseIdentifier(String id) {
            String v = id == null ? "" : id.strip().toLowerCase(Locale.ROOT);
            if (!GUID.matcher(v).matches()) throw new BoardConfigException("Paylocity board must be the company GUID from /Recruiting/Jobs/All/{guid}");
            return new BoardRef(kind(), v, Map.of());
        }

        @Override
        public ListingResult list(BoardRef board, CrawlContext context) {
            Crawl crawl = new Crawl(context);
            return crawl.run(() -> {
                crawl.beforePage();
                String html = context.http().get(Parsing.uri("https://recruiting.paylocity.com/Recruiting/Jobs/All/" + board.identifier()),
                        SafeHttpClient.Accept.HTML);
                crawl.afterPage();
                parseList(html, crawl);
            });
        }

        static void parseList(String html, Crawl crawl) {
            int at = html.indexOf("window.pageData");
            if (at < 0) throw new Parsing.MalformedException("pageData missing");
            JsonNode data = Parsing.json(Parsing.balancedObject(html, at).orElseThrow(() -> new Parsing.MalformedException("pageData unterminated")));
            JsonNode jobs = data.get("Jobs");
            if (jobs == null || !jobs.isArray()) throw new Parsing.MalformedException("Jobs missing");
            crawl.employerFromBoard(Parsing.text(data, "ModuleTitle"));
            for (JsonNode j : jobs) {
                if (j.path("IsInternal").asBoolean(false)) continue;
                String id = Parsing.text(j, "JobId");
                String title = Parsing.text(j, "JobTitle");
                if (id == null || title == null) continue;
                Map<String, String> hints = new HashMap<>();
                putIf(hints, "location", Normalize.first(Parsing.text(j, "LocationName"),
                        Normalize.join(Parsing.textAt(j, "/JobLocation/City"), Parsing.textAt(j, "/JobLocation/State"))));
                putIf(hints, "city", Parsing.textAt(j, "/JobLocation/City"));
                putIf(hints, "country", Parsing.textAt(j, "/JobLocation/Country"));
                putIf(hints, "remote", j.path("IsRemote").asBoolean(false) ? "REMOTE" : null);
                putIf(hints, "postedAt", Parsing.text(j, "PublishedDate"));
                crawl.add(ListingEntry.partial(id, "https://recruiting.paylocity.com/Recruiting/Jobs/Details/" + id, title, hints));
            }
        }

        @Override
        public NormalizedJob detail(BoardRef board, ListingEntry entry, CrawlContext context) {
            return jsonLdDetail(context.http().get(Parsing.uri(entry.jobUrl()), SafeHttpClient.Accept.HTML), entry, context);
        }
    }

    // ==========================================================================================
    /** Shared: a detail page whose schema.org JobPosting carries the posting. */
    static NormalizedJob jsonLdDetail(String html, BoardAdapter.ListingEntry entry, BoardAdapter.CrawlContext context) {
        Document doc = Parsing.html(html, entry.jobUrl());
        List<JsonNode> postings = Parsing.jsonLdJobPostings(doc);
        if (postings.isEmpty()) throw new Parsing.MalformedException("JobPosting JSON-LD missing");
        JsonNode posting = postings.get(0);
        NormalizedJob ld = Parsing.fromJsonLd(posting, entry.externalId(), context.now()).build();
        Map<String, String> h = entry.hints();
        return ld.toBuilder()
                .title(Normalize.first(ld.title(), entry.title()))
                .employer(Normalize.first(context.employerName(), ld.employerName()))
                .location(Normalize.first(ld.locationRaw(), h.get("location")))
                .city(Normalize.first(ld.city(), h.get("city")))
                .country(Normalize.first(ld.country(), h.get("country")))
                .remote(ld.remoteType() != null ? ld.remoteType() : h.get("remote"))
                .employmentType(Normalize.first(ld.employmentType(), h.get("employmentType")))
                .applicationUrl(Normalize.first(Parsing.text(posting, "url"), entry.jobUrl()))
                .sourceUrl(entry.jobUrl())
                // The listing's structured date beats SEO markup: JSON-LD datePosted was
                // observed set to the fetch date (Phenom) or shifted by hours (Paylocity).
                .postedAt(h.get("postedAt") != null ? Normalize.postedAt(h.get("postedAt"), context.now()) : ld.postedAt())
                .requisitionId(h.get("requisitionId"))
                .build();
    }

    static void putIf(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) map.put(key, value);
    }
}
