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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Workday candidate-experience ("CXS") JSON used by every
 * {@code *.myworkdayjobs.com} career site — verified live 2026-10-10 against
 * nvidia.wd5/NVIDIAExternalCareerSite.
 *
 * <p>Listing: {@code POST /wday/cxs/{tenant}/{site}/jobs} with
 * {@code {limit, offset}}. Workday returns {@code total} on the FIRST page only
 * (later pages say 0), so the first total is kept for the end-of-list test.
 * Detail: {@code GET /wday/cxs/{tenant}/{site}{externalPath}} for the
 * description. {@code postedOn} is a relative phrase ("Posted Today") and is
 * deliberately not converted into a date.
 */
public class WorkdayAdapter implements BoardAdapter {

    private static final Pattern HOST = Pattern.compile("^([a-z0-9-]{1,64})\\.(wd\\d{1,3})\\.myworkdayjobs\\.com$");
    private static final Pattern IDENTIFIER = Pattern.compile("^([a-z0-9-]{1,64})\\.(wd\\d{1,3})/([A-Za-z0-9_-]{1,100})$");
    static final int PAGE_SIZE = 20;

    @Override
    public PlatformKind kind() {
        return PlatformKind.WORKDAY;
    }

    @Override
    public Optional<BoardRef> recognize(URI url) {
        Matcher host = HOST.matcher(Parsing.host(url));
        if (!host.matches()) return Optional.empty();
        List<String> segments = Parsing.pathSegments(url);
        String site = null;
        if (segments.size() >= 4 && segments.get(0).equals("wday") && segments.get(1).equals("cxs")) {
            site = segments.get(3);
        } else if (!segments.isEmpty()) {
            int i = segments.get(0).matches("[a-z]{2}-[A-Za-z]{2}") ? 1 : 0;
            if (segments.size() > i) site = segments.get(i);
        }
        if (site == null || !site.matches("[A-Za-z0-9_-]{1,100}")) return Optional.empty();
        return Optional.of(ref(host.group(1), host.group(2), site));
    }

    @Override
    public BoardRef parseIdentifier(String orgIdentifier) {
        Matcher m = IDENTIFIER.matcher(orgIdentifier == null ? "" : orgIdentifier.strip());
        if (!m.matches()) {
            throw new BoardConfigException("Workday board must look like tenant.wdN/site, e.g. nvidia.wd5/NVIDIAExternalCareerSite");
        }
        return ref(m.group(1), m.group(2), m.group(3));
    }

    private BoardRef ref(String tenant, String dc, String site) {
        return new BoardRef(kind(), tenant + "." + dc + "/" + site,
                Map.of("tenant", tenant, "dc", dc, "site", site, "host", tenant + "." + dc + ".myworkdayjobs.com"));
    }

    @Override
    public ListingResult list(BoardRef board, CrawlContext context) {
        String base = "https://" + board.param("host") + "/wday/cxs/" + board.param("tenant") + "/" + board.param("site");
        List<ListingEntry> entries = new ArrayList<>();
        Integer total = null;
        int pages = 0;
        int offset = 0;
        try {
            while (true) {
                if (pages >= context.maxPages()) {
                    return ListingResult.partial(entries, pages, FailureCategory.BUDGET_EXHAUSTED,
                            "page cap reached before the end of the board", context.employerName());
                }
                String body = "{\"appliedFacets\":{},\"limit\":" + PAGE_SIZE + ",\"offset\":" + offset + ",\"searchText\":\"\"}";
                JsonNode page = Parsing.json(context.http().postJson(URI.create(base + "/jobs"), body, SafeHttpClient.Accept.JSON));
                pages++;
                JsonNode postings = page.get("jobPostings");
                if (postings == null || !postings.isArray()) throw new Parsing.MalformedException("jobPostings missing");
                if (total == null) total = page.path("total").asInt(-1);
                for (JsonNode posting : postings) {
                    ListingEntry entry = entry(board, posting);
                    if (entry != null) entries.add(entry);
                }
                offset += PAGE_SIZE;
                if (postings.isEmpty() || total < 0 || offset >= total) break;
            }
            return ListingResult.complete(dedupe(entries), pages, context.employerName());
        } catch (SafeHttpClient.FetchException e) {
            return pages == 0 ? ListingResult.failed(e.category(), e.getMessage())
                    : ListingResult.partial(entries, pages, e.category(), e.getMessage(), context.employerName());
        } catch (Parsing.MalformedException e) {
            return ListingResult.partial(entries, pages, FailureCategory.MALFORMED_RESPONSE, e.getMessage(), context.employerName());
        }
    }

    static ListingEntry entry(BoardRef board, JsonNode posting) {
        String path = Parsing.text(posting, "externalPath");
        String title = Parsing.text(posting, "title");
        if (path == null || title == null || !path.startsWith("/")) return null;
        String url = "https://" + board.param("host") + "/" + board.param("site") + path;
        String req = posting.path("bulletFields").path(0).asText(null);
        return ListingEntry.partial(path, url, title, req == null ? Map.of() : Map.of("requisitionId", req));
    }

    private static List<ListingEntry> dedupe(List<ListingEntry> entries) {
        java.util.LinkedHashMap<String, ListingEntry> byId = new java.util.LinkedHashMap<>();
        entries.forEach(e -> byId.putIfAbsent(e.externalId(), e));
        return new ArrayList<>(byId.values());
    }

    @Override
    public NormalizedJob detail(BoardRef board, ListingEntry entry, CrawlContext context) {
        String url = "https://" + board.param("host") + "/wday/cxs/" + board.param("tenant") + "/"
                + board.param("site") + entry.externalId();
        return parseDetail(Parsing.json(context.http().get(Parsing.uri(url), SafeHttpClient.Accept.JSON)), entry, context);
    }

    static NormalizedJob parseDetail(JsonNode root, ListingEntry entry, CrawlContext context) {
        JsonNode info = root.get("jobPostingInfo");
        if (info == null || !info.isObject()) throw new Parsing.MalformedException("jobPostingInfo missing");
        List<String> places = new ArrayList<>();
        if (Parsing.text(info, "location") != null) places.add(Parsing.text(info, "location"));
        places.addAll(Parsing.textList(info.get("additionalLocations")));
        String requisition = Normalize.first(Parsing.text(info, "jobReqId"), entry.hints().get("requisitionId"));
        return NormalizedJob.builder(entry.externalId(), Normalize.first(Parsing.text(info, "title"), entry.title()))
                .employer(Normalize.first(context.employerName(), Parsing.textAt(root, "/hiringOrganization/name")))
                .location(Normalize.joinPlaces(places))
                .country(Parsing.textAt(info, "/country/descriptor"))
                .employmentType(Parsing.text(info, "timeType"))
                .description(Normalize.htmlToText(Parsing.text(info, "jobDescription")))
                .applicationUrl(Normalize.first(Parsing.text(info, "externalUrl"), entry.jobUrl()))
                .sourceUrl(entry.jobUrl())
                .requisitionId(requisition)
                .active(info.has("posted") && !info.path("posted").asBoolean(true) ? Boolean.FALSE : null)
                .build();
    }
}
