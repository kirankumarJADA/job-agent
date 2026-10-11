package com.personal.jobagent.discovery.platform.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import com.personal.jobagent.discovery.platform.BoardAdapter;
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
 * Lever public Postings API ({@code api.lever.co/v0/postings/{site}?mode=json},
 * EU: {@code api.eu.lever.co}) — verified live against "palantir". Paged with
 * {@code skip}/{@code limit}; a short page is the end of the list.
 */
public class LeverAdapter implements BoardAdapter {

    static final int PAGE_SIZE = 100;

    @Override
    public PlatformKind kind() {
        return PlatformKind.LEVER;
    }

    @Override
    public Optional<BoardRef> recognize(URI url) {
        String host = Parsing.host(url);
        List<String> s = Parsing.pathSegments(url);
        if ((host.equals("jobs.lever.co") || host.equals("jobs.eu.lever.co")) && !s.isEmpty()) {
            return Optional.of(parseIdentifier((host.contains(".eu.") ? "eu:" : "") + s.get(0)));
        }
        if ((host.equals("api.lever.co") || host.equals("api.eu.lever.co")) && s.size() >= 3 && s.get(1).equals("postings")) {
            return Optional.of(parseIdentifier((host.contains(".eu.") ? "eu:" : "") + s.get(2)));
        }
        return Optional.empty();
    }

    @Override
    public BoardRef parseIdentifier(String orgIdentifier) {
        String v = orgIdentifier == null ? "" : orgIdentifier.strip();
        boolean eu = v.startsWith("eu:");
        String site = Parsing.requireSlug(eu ? v.substring(3) : v, "Lever site name", "[A-Za-z0-9._-]{1,100}");
        return new BoardRef(kind(), (eu ? "eu:" : "") + site,
                Map.of("site", site, "api", eu ? "https://api.eu.lever.co" : "https://api.lever.co"));
    }

    @Override
    public ListingResult list(BoardRef board, CrawlContext context) {
        Crawl crawl = new Crawl(context);
        return crawl.run(() -> {
            for (int skip = 0; ; skip += PAGE_SIZE) {
                crawl.beforePage();
                URI uri = Parsing.uri(board.param("api") + "/v0/postings/" + Parsing.enc(board.param("site"))
                        + "?mode=json&skip=" + skip + "&limit=" + PAGE_SIZE);
                JsonNode page = Parsing.json(context.http().get(uri, SafeHttpClient.Accept.JSON));
                crawl.afterPage();
                if (!page.isArray()) throw new Parsing.MalformedException("expected a JSON array");
                for (JsonNode posting : page) {
                    NormalizedJob job = parse(posting, context);
                    if (job != null) crawl.add(ListingEntry.complete(job));
                }
                if (page.size() < PAGE_SIZE) return;
            }
        });
    }

    static NormalizedJob parse(JsonNode p, CrawlContext context) {
        String id = Parsing.text(p, "id");
        String title = Parsing.text(p, "text");
        if (id == null || title == null) return null;
        StringBuilder description = new StringBuilder(java.util.Objects.requireNonNullElse(Parsing.text(p, "descriptionPlain"), "") );
        for (JsonNode list : Parsing.iterable(p.get("lists"))) {
            description.append("\n\n").append(java.util.Objects.requireNonNullElse(Parsing.text(list, "text"), ""))
                    .append("\n").append(Normalize.htmlToText(Parsing.text(list, "content")));
        }
        String additional = Parsing.text(p, "additionalPlain");
        if (additional != null) description.append("\n\n").append(additional);
        List<String> locations = Parsing.textList(p.at("/categories/allLocations"));
        JsonNode salary = p.get("salaryRange");
        NormalizedJob.Builder b = NormalizedJob.builder(id, title)
                .employer(context.employerName())
                .location(locations.isEmpty() ? Parsing.textAt(p, "/categories/location") : Normalize.joinPlaces(locations))
                .country(Parsing.text(p, "country"))
                .remote(Normalize.remoteType(Parsing.text(p, "workplaceType")))
                .employmentType(Parsing.textAt(p, "/categories/commitment"))
                .description(description.toString().strip())
                .applicationUrl(Normalize.first(Parsing.text(p, "applyUrl"), Parsing.text(p, "hostedUrl")))
                .sourceUrl(Parsing.text(p, "hostedUrl"))
                .postedAt(Normalize.postedAt(p.path("createdAt").isNumber() ? p.path("createdAt").asLong() : null, context.now()));
        if (salary != null && salary.isObject()) {
            b.salary(Normalize.decimalOrNull(Parsing.text(salary, "min")), Normalize.decimalOrNull(Parsing.text(salary, "max")),
                    Parsing.text(salary, "currency"), Parsing.text(salary, "interval"));
        }
        return b.build();
    }

    @Override
    public NormalizedJob detail(BoardRef board, ListingEntry entry, CrawlContext context) {
        throw new UnsupportedOperationException("Lever listings are complete");
    }
}
