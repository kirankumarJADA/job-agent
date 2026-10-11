package com.personal.jobagent.discovery.platform.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import com.personal.jobagent.discovery.platform.BoardAdapter;
import com.personal.jobagent.discovery.platform.BoardConfigException;
import com.personal.jobagent.discovery.platform.NormalizedJob;
import com.personal.jobagent.discovery.platform.Normalize;
import com.personal.jobagent.discovery.platform.Parsing;
import com.personal.jobagent.discovery.platform.PlatformKind;
import com.personal.jobagent.discovery.platform.SafeHttpClient;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Platforms whose public listing is server-rendered HTML or an XML feed. All
 * verified live 2026-10-10: iCIMS [us-erac.icims.com], Jobvite [oneco],
 * JazzHR XML feed [labelmaster], Join [join], Teamtailor RSS
 * [career.teamtailor.com].
 */
public final class MarkupAdapters {

    private MarkupAdapters() {
    }

    // ==========================================================================================
    /**
     * iCIMS career portals: {@code /jobs/search?pr={page}&in_iframe=1} renders
     * job cards server-side and advertises the next page with
     * {@code <link rel="next">}; job pages carry schema.org JSON-LD.
     */
    public static class Icims implements BoardAdapter {
        private static final Pattern HOST = Pattern.compile("^[a-z0-9-]{1,63}\\.icims\\.com$");
        private static final Pattern JOB = Pattern.compile("^/jobs/(\\d+)/([^/]+)/job$");

        @Override public PlatformKind kind() { return PlatformKind.ICIMS; }

        @Override
        public Optional<BoardRef> recognize(URI url) {
            String host = Parsing.host(url);
            return HOST.matcher(host).matches() && !host.startsWith("www.") && !host.equals("community.icims.com")
                    && !host.equals("careers.icims.com") ? Optional.of(parseIdentifier(host)) : Optional.empty();
        }

        @Override
        public BoardRef parseIdentifier(String id) {
            String host = id == null ? "" : id.strip().toLowerCase(Locale.ROOT);
            if (!HOST.matcher(host).matches()) throw new BoardConfigException("iCIMS board must be the portal host, e.g. careers-acme.icims.com");
            return new BoardRef(kind(), host, Map.of());
        }

        @Override
        public ListingResult list(BoardRef board, CrawlContext context) {
            Crawl crawl = new Crawl(context);
            return crawl.run(() -> {
                Set<String> visited = new HashSet<>();
                String next = "https://" + board.identifier() + "/jobs/search?pr=0&in_iframe=1";
                while (next != null) {
                    if (!visited.add(next)) throw new Parsing.MalformedException("pagination loop");
                    crawl.beforePage();
                    String html = context.http().get(Parsing.uri(next), SafeHttpClient.Accept.HTML);
                    crawl.afterPage();
                    next = parsePage(html, board, crawl);
                }
            });
        }

        /** Adds the page's cards; returns the next page URL (same host only) or null at the end. */
        static String parsePage(String html, BoardRef board, Crawl crawl) {
            Document doc = Parsing.html(html, "https://" + board.identifier() + "/");
            Element table = doc.selectFirst(".iCIMS_JobsTable");
            if (table == null) {
                // A portal with no openings renders an explicit empty state, never a missing table.
                if (doc.text().toLowerCase(Locale.ROOT).contains("no jobs") || doc.selectFirst(".iCIMS_Message") != null) return null;
                throw new Parsing.MalformedException("job table missing");
            }
            for (Element a : table.select("a.iCIMS_Anchor[href]")) {
                URI href = Parsing.uri(a.absUrl("href"));
                if (!Parsing.host(href).equals(board.identifier())) continue;
                Matcher m = JOB.matcher(href.getRawPath());
                if (!m.matches()) continue;
                Element h3 = a.selectFirst("h3");
                String title = h3 != null ? h3.text() : a.attr("title").replaceFirst("^\\d+ - ", "");
                String url = "https://" + board.identifier() + "/jobs/" + m.group(1) + "/" + m.group(2) + "/job";
                crawl.add(ListingEntry.partial(m.group(1), url, title, Map.of()));
            }
            Element next = doc.selectFirst("link[rel=next]");
            if (next == null) return null;
            String target = next.absUrl("href");
            return Parsing.host(Parsing.uri(target)).equals(board.identifier()) ? target : null;
        }

        @Override
        public NormalizedJob detail(BoardRef board, ListingEntry entry, CrawlContext context) {
            String html = context.http().get(Parsing.uri(entry.jobUrl() + "?in_iframe=1"), SafeHttpClient.Accept.HTML);
            NormalizedJob job = DetailFetchAdapters.jsonLdDetail(html, entry, context);
            // The JSON-LD url points at the iframe variant; the candidate applies on the plain job page.
            return job.toBuilder().applicationUrl(entry.jobUrl()).build();
        }
    }

    // ==========================================================================================
    /** Jobvite hosted career pages (jobs.jobvite.com/{slug}/jobs). */
    public static class Jobvite implements BoardAdapter {
        @Override public PlatformKind kind() { return PlatformKind.JOBVITE; }

        @Override
        public Optional<BoardRef> recognize(URI url) {
            String host = Parsing.host(url);
            List<String> s = Parsing.pathSegments(url);
            if ((host.equals("jobs.jobvite.com") || host.equals("careers.jobvite.com")) && !s.isEmpty()
                    && !s.get(0).equals("careers") && !s.get(0).equals("__assets__")) {
                return Optional.of(parseIdentifier(s.get(0)));
            }
            return Optional.empty();
        }

        @Override
        public BoardRef parseIdentifier(String id) {
            return new BoardRef(kind(), Parsing.requireSlug(id, "Jobvite company slug", "[A-Za-z0-9_-]{1,100}"), Map.of());
        }

        @Override
        public ListingResult list(BoardRef board, CrawlContext context) {
            Crawl crawl = new Crawl(context);
            return crawl.run(() -> {
                Set<String> visited = new HashSet<>();
                String next = "https://jobs.jobvite.com/" + board.identifier() + "/jobs";
                while (next != null) {
                    if (!visited.add(next)) throw new Parsing.MalformedException("pagination loop");
                    crawl.beforePage();
                    String html = context.http().get(Parsing.uri(next), SafeHttpClient.Accept.HTML);
                    crawl.afterPage();
                    next = parsePage(html, board, crawl);
                }
            });
        }

        static String parsePage(String html, BoardRef board, Crawl crawl) {
            Document doc = Parsing.html(html, "https://jobs.jobvite.com/");
            String prefix = "/" + board.identifier() + "/job/";
            boolean sawListing = doc.selectFirst("table.jv-job-list, .jv-featured-jobs, .jv-job-list") != null;
            for (Element row : doc.select("table.jv-job-list tr")) {
                Element a = row.selectFirst("td.jv-job-list-name a[href]");
                if (a == null) continue;
                String path = URI.create(a.absUrl("href")).getPath();
                if (path == null || !path.startsWith(prefix)) continue;
                String id = path.substring(prefix.length()).replaceAll("/.*$", "");
                Element loc = row.selectFirst("td.jv-job-list-location");
                Map<String, String> hints = new HashMap<>();
                if (loc != null && !loc.text().isBlank()) hints.put("location", loc.text().replaceAll("\\s+", " ").strip());
                crawl.add(ListingEntry.partial(id, "https://jobs.jobvite.com" + prefix + id, a.text(), hints));
            }
            for (Element a : doc.select(".jv-featured-job-title a[href]")) {
                String path = URI.create(a.absUrl("href")).getPath();
                if (path == null || !path.startsWith(prefix)) continue;
                String id = path.substring(prefix.length()).replaceAll("/.*$", "");
                crawl.add(ListingEntry.partial(id, "https://jobs.jobvite.com" + prefix + id, a.text(), Map.of()));
            }
            if (!sawListing && doc.selectFirst(".jv-page-body, .jv-wrapper") == null) {
                throw new Parsing.MalformedException("not a Jobvite listing page");
            }
            Element next = doc.selectFirst("a.jv-pagination-next[href], .jv-pagination a[rel=next][href]");
            if (next == null) {
                // Pagination we do not understand must not look like the end of the list.
                if (doc.selectFirst("[class*=pagination], a[href*=\"jobs?p=\"]") != null) {
                    throw new Parsing.MalformedException("unrecognised pagination markup");
                }
                return null;
            }
            String target = next.absUrl("href");
            return target.startsWith("https://jobs.jobvite.com/" + board.identifier() + "/") ? target : null;
        }

        @Override
        public NormalizedJob detail(BoardRef board, ListingEntry entry, CrawlContext context) {
            return parseDetail(context.http().get(Parsing.uri(entry.jobUrl()), SafeHttpClient.Accept.HTML), entry, context);
        }

        static NormalizedJob parseDetail(String html, ListingEntry entry, CrawlContext context) {
            Document doc = Parsing.html(html, entry.jobUrl());
            // A real Jobvite posting can have no description at all (the employer left it empty); the page
            // then has no description block. That is a valid posting with an unknown description, never
            // something to fill in. A page without the job header is not a job page and is rejected.
            Element description = doc.selectFirst("div.jv-job-detail-description");
            Element header = doc.selectFirst("h2.jv-header");
            if (header == null && description == null) throw new Parsing.MalformedException("job detail page not recognised");
            String location = entry.hints().get("location");
            Element meta = doc.selectFirst("p.jv-job-detail-meta");
            if (location == null && meta != null) {
                List<String> parts = new ArrayList<>(List.of(meta.html().split("<span class=\"jv-inline-separator\"></span>")));
                if (parts.size() > 1) {
                    parts.remove(0); // first segment is the job category
                    location = Normalize.join(parts.stream().map(p -> Jsoup.parse(p).text().replaceAll("\\s+", " ").strip()).toList());
                }
            }
            return NormalizedJob.builder(entry.externalId(), Normalize.first(header == null ? null : header.text(), entry.title()))
                    .employer(context.employerName())
                    .location(location)
                    .description(description == null ? null
                            : Normalize.blankToNull(Normalize.htmlToText(description.html()).replaceFirst("^Description\\s*", "")))
                    .applicationUrl(entry.jobUrl())
                    .sourceUrl(entry.jobUrl())
                    .build();
        }
    }

    // ==========================================================================================
    /** JazzHR public XML job feed (app.jazz.co/feeds/export/jobs/{subdomain}): whole board, descriptions included. */
    public static class JazzHr implements BoardAdapter {
        @Override public PlatformKind kind() { return PlatformKind.JAZZHR; }

        @Override
        public Optional<BoardRef> recognize(URI url) {
            String host = Parsing.host(url);
            if (host.endsWith(".applytojob.com")) return SinglePayloadAdapters.subdomainOf(url, ".applytojob.com", this);
            List<String> s = Parsing.pathSegments(url);
            if (host.equals("app.jazz.co") && s.size() >= 4 && s.get(0).equals("feeds")) return Optional.of(parseIdentifier(s.get(3)));
            return Optional.empty();
        }

        @Override
        public BoardRef parseIdentifier(String id) {
            return new BoardRef(kind(), Parsing.requireSlug(id, "JazzHR subdomain", "[a-z0-9-]{1,63}"), Map.of());
        }

        @Override
        public ListingResult list(BoardRef board, CrawlContext context) {
            Crawl crawl = new Crawl(context);
            return crawl.run(() -> {
                crawl.beforePage();
                String xml = context.http().get(Parsing.uri("https://app.jazz.co/feeds/export/jobs/" + board.identifier()),
                        SafeHttpClient.Accept.XML_LABELLED_HTML);
                crawl.afterPage();
                parseFeed(xml, crawl, context);
            });
        }

        static void parseFeed(String xml, Crawl crawl, CrawlContext context) {
            Document doc = Jsoup.parse(xml, "", Parser.xmlParser());
            if (doc.selectFirst("jobs") == null) throw new Parsing.MalformedException("jobs root missing");
            Element company = doc.selectFirst("jobs > company");
            if (company != null) crawl.employerFromBoard(company.text());
            for (Element job : doc.select("jobs > job")) {
                String status = text(job, "status");
                if (status != null && !status.equalsIgnoreCase("open")) continue;
                String id = text(job, "id");
                String title = text(job, "title");
                if (id == null || title == null) continue;
                String url = text(job, "url");
                crawl.add(ListingEntry.complete(NormalizedJob.builder(id, title)
                        .employer(crawl.employerName())
                        .location(Normalize.join(text(job, "city"), text(job, "state"), text(job, "country")))
                        .city(text(job, "city"))
                        .country(text(job, "country"))
                        .employmentType(text(job, "type"))
                        .description(Normalize.htmlToText(text(job, "description")))
                        .applicationUrl(url)
                        .sourceUrl(url)
                        .build()));
            }
        }

        @Override
        public NormalizedJob detail(BoardRef board, ListingEntry entry, CrawlContext context) {
            throw new UnsupportedOperationException("JazzHR feed entries are complete");
        }
    }

    // ==========================================================================================
    /** JOIN company pages: Next.js state carries the paginated job list; job pages carry JSON-LD. */
    public static class Join implements BoardAdapter {
        @Override public PlatformKind kind() { return PlatformKind.JOIN; }

        @Override
        public Optional<BoardRef> recognize(URI url) {
            List<String> s = Parsing.pathSegments(url);
            if (Parsing.host(url).equals("join.com") && s.size() >= 2 && s.get(0).equals("companies")) {
                return Optional.of(parseIdentifier(s.get(1)));
            }
            return Optional.empty();
        }

        @Override
        public BoardRef parseIdentifier(String id) {
            return new BoardRef(kind(), Parsing.requireSlug(id, "JOIN company slug", "[a-z0-9-]{1,100}"), Map.of());
        }

        @Override
        public ListingResult list(BoardRef board, CrawlContext context) {
            Crawl crawl = new Crawl(context);
            return crawl.run(() -> {
                for (int page = 1; ; page++) {
                    crawl.beforePage();
                    String html = context.http().get(Parsing.uri("https://join.com/companies/" + board.identifier()
                            + (page == 1 ? "" : "?page=" + page)), SafeHttpClient.Accept.HTML);
                    crawl.afterPage();
                    int pageCount = parsePage(html, board, crawl, context);
                    if (page >= pageCount) return;
                }
            });
        }

        /** Adds this page's jobs; returns the platform's reported page count. */
        static int parsePage(String html, BoardRef board, Crawl crawl, CrawlContext context) {
            Element script = Parsing.html(html, "https://join.com/").selectFirst("script#__NEXT_DATA__");
            if (script == null) throw new Parsing.MalformedException("__NEXT_DATA__ missing");
            JsonNode state = Parsing.json(script.data()).at("/props/pageProps/initialState");
            JsonNode jobs = state.at("/jobs/items");
            if (!jobs.isArray()) throw new Parsing.MalformedException("jobs.items missing");
            crawl.employerFromBoard(Parsing.textAt(state, "/company/name"));
            for (JsonNode j : jobs) {
                String id = Parsing.text(j, "id");
                String title = Parsing.text(j, "title");
                String idParam = Parsing.text(j, "idParam");
                if (id == null || title == null || idParam == null) continue;
                Map<String, String> hints = new HashMap<>();
                DetailFetchAdapters.putIf(hints, "location", Normalize.join(Parsing.textAt(j, "/city/cityName"), Parsing.textAt(j, "/city/countryName")));
                DetailFetchAdapters.putIf(hints, "city", Parsing.textAt(j, "/city/cityName"));
                DetailFetchAdapters.putIf(hints, "country", Parsing.textAt(j, "/city/countryName"));
                DetailFetchAdapters.putIf(hints, "remote", Normalize.remoteType(Parsing.text(j, "workplaceType")));
                DetailFetchAdapters.putIf(hints, "employmentType", Parsing.textAt(j, "/employmentType/name"));
                DetailFetchAdapters.putIf(hints, "postedAt", Parsing.text(j, "createdAt"));
                crawl.add(ListingEntry.partial(id, "https://join.com/companies/" + board.identifier() + "/" + idParam, title, hints));
            }
            return state.at("/jobs/pagination/pageCount").asInt(1);
        }

        @Override
        public NormalizedJob detail(BoardRef board, ListingEntry entry, CrawlContext context) {
            String html = context.http().get(Parsing.uri(entry.jobUrl()), SafeHttpClient.Accept.HTML);
            return DetailFetchAdapters.jsonLdDetail(html, entry, context).toBuilder().applicationUrl(entry.jobUrl()).build();
        }
    }

    // ==========================================================================================
    /**
     * Teamtailor career sites publish an RSS feed of open jobs with full
     * descriptions. The feed is not documented as exhaustive, so a Teamtailor
     * listing is reported as PARTIAL and never used to infer removals; jobs
     * age out through the normal staleness rules instead.
     */
    public static class Teamtailor implements BoardAdapter {
        private static final Pattern HOST = Pattern.compile("^[a-z0-9-]{1,63}\\.teamtailor\\.com$");

        @Override public PlatformKind kind() { return PlatformKind.TEAMTAILOR; }

        @Override
        public Optional<BoardRef> recognize(URI url) {
            String host = Parsing.host(url);
            return HOST.matcher(host).matches() && !host.equals("www.teamtailor.com") && !host.equals("app.teamtailor.com")
                    ? Optional.of(parseIdentifier(host)) : Optional.empty();
        }

        @Override
        public BoardRef parseIdentifier(String id) {
            String host = id == null ? "" : id.strip().toLowerCase(Locale.ROOT);
            if (!HOST.matcher(host).matches()) throw new BoardConfigException("Teamtailor board must be the career-site host, e.g. acme.teamtailor.com");
            return new BoardRef(kind(), host, Map.of());
        }

        @Override
        public ListingResult list(BoardRef board, CrawlContext context) {
            Crawl crawl = new Crawl(context);
            ListingResult result = crawl.run(() -> {
                crawl.beforePage();
                String xml = context.http().get(Parsing.uri("https://" + board.identifier() + "/jobs.rss"), SafeHttpClient.Accept.XML);
                crawl.afterPage();
                parseFeed(xml, crawl, context);
            });
            if (!result.complete()) return result;
            return ListingResult.partial(result.entries(), result.pagesFetched(), null,
                    "RSS feed is not documented as exhaustive; absence is not treated as removal", result.employerName());
        }

        static void parseFeed(String xml, Crawl crawl, CrawlContext context) {
            Document doc = Jsoup.parse(xml, "", Parser.xmlParser());
            if (doc.selectFirst("rss > channel") == null) throw new Parsing.MalformedException("RSS channel missing");
            for (Element item : doc.select("channel > item")) {
                String link = text(item, "link");
                String title = text(item, "title");
                if (link == null || title == null) continue;
                Matcher m = Pattern.compile("/jobs/(\\d+)").matcher(link);
                String id = m.find() ? m.group(1) : text(item, "guid");
                if (id == null) continue;
                List<String> places = new ArrayList<>();
                String city = null, country = null;
                for (Element loc : item.select("tt|location")) {
                    String c = text(loc, "tt|city");
                    String co = text(loc, "tt|country");
                    String joined = Normalize.join(c, co);
                    places.add(joined != null ? joined : text(loc, "tt|name"));
                    if (city == null) city = c;
                    if (country == null) country = co;
                }
                crawl.add(ListingEntry.complete(NormalizedJob.builder(id, title)
                        .employer(crawl.employerName())
                        .location(Normalize.joinPlaces(places))
                        .city(city)
                        .country(country)
                        .remote(Normalize.remoteType(text(item, "remoteStatus")))
                        .description(Normalize.htmlToText(text(item, "description")))
                        .applicationUrl(link)
                        .sourceUrl(link)
                        .postedAt(Normalize.postedAt(text(item, "pubDate"), context.now()))
                        .build()));
            }
        }

        @Override
        public NormalizedJob detail(BoardRef board, ListingEntry entry, CrawlContext context) {
            throw new UnsupportedOperationException("Teamtailor feed entries are complete");
        }
    }

    static String text(Element parent, String cssQuery) {
        Element e = parent.selectFirst(cssQuery);
        return e == null ? null : Normalize.blankToNull(e.text());
    }
}
