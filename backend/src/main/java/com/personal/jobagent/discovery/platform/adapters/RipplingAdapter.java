package com.personal.jobagent.discovery.platform.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import com.personal.jobagent.discovery.platform.BoardAdapter;
import com.personal.jobagent.discovery.platform.NormalizedJob;
import com.personal.jobagent.discovery.platform.Normalize;
import com.personal.jobagent.discovery.platform.Parsing;
import com.personal.jobagent.discovery.platform.PlatformKind;
import com.personal.jobagent.discovery.platform.SafeHttpClient;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Rippling ATS public board API — verified live against "rippling":
 * {@code GET api.rippling.com/platform/api/ats/v1/board/{slug}/jobs} returns
 * the whole board as one array (no description); {@code .../jobs/{uuid}}
 * returns the posting with company and role HTML.
 */
public class RipplingAdapter implements BoardAdapter {

    static final String API = "https://api.rippling.com/platform/api/ats/v1/board/";

    @Override
    public PlatformKind kind() {
        return PlatformKind.RIPPLING;
    }

    @Override
    public Optional<BoardRef> recognize(URI url) {
        String host = Parsing.host(url);
        List<String> s = Parsing.pathSegments(url);
        if (host.equals("ats.rippling.com") && !s.isEmpty()) {
            int i = s.get(0).matches("[a-z]{2}-[A-Z]{2}") ? 1 : 0;
            if (s.size() > i) return Optional.of(parseIdentifier(s.get(i)));
        }
        if (host.equals("api.rippling.com") && s.size() >= 6 && s.get(4).equals("board")) {
            return Optional.of(parseIdentifier(s.get(5)));
        }
        return Optional.empty();
    }

    @Override
    public BoardRef parseIdentifier(String orgIdentifier) {
        return new BoardRef(kind(), Parsing.requireSlug(orgIdentifier, "Rippling board slug", "[A-Za-z0-9_-]{1,100}"), Map.of());
    }

    @Override
    public ListingResult list(BoardRef board, CrawlContext context) {
        Crawl crawl = new Crawl(context);
        return crawl.run(() -> {
            crawl.beforePage();
            JsonNode jobs = Parsing.json(context.http().get(Parsing.uri(API + Parsing.enc(board.identifier()) + "/jobs"),
                    SafeHttpClient.Accept.JSON));
            crawl.afterPage();
            if (!jobs.isArray()) throw new Parsing.MalformedException("expected a JSON array");
            for (JsonNode job : jobs) {
                String id = Parsing.text(job, "uuid");
                String title = Parsing.text(job, "name");
                if (id == null || title == null) continue;
                crawl.add(ListingEntry.partial(id, Parsing.text(job, "url"), title,
                        Map.of("location", java.util.Objects.requireNonNullElse(Parsing.textAt(job, "/workLocation/label"), ""))));
            }
        });
    }

    @Override
    public NormalizedJob detail(BoardRef board, ListingEntry entry, CrawlContext context) {
        JsonNode job = Parsing.json(context.http().get(
                Parsing.uri(API + Parsing.enc(board.identifier()) + "/jobs/" + Parsing.enc(entry.externalId())),
                SafeHttpClient.Accept.JSON));
        return parseDetail(job, entry, context);
    }

    static NormalizedJob parseDetail(JsonNode job, ListingEntry entry, CrawlContext context) {
        if (!job.isObject()) throw new Parsing.MalformedException("expected a JSON object");
        String description = (Normalize.htmlToText(Parsing.textAt(job, "/description/company")) + "\n\n"
                + Normalize.htmlToText(Parsing.textAt(job, "/description/role"))).strip();
        List<String> places = Parsing.textList(job.get("workLocations"));
        String url = Normalize.first(Parsing.text(job, "url"), entry.jobUrl());
        return NormalizedJob.builder(entry.externalId(), Normalize.first(Parsing.text(job, "name"), entry.title()))
                .employer(Normalize.first(context.employerName(), Parsing.text(job, "companyName")))
                .location(places.isEmpty() ? entry.hints().get("location") : Normalize.joinPlaces(places))
                .employmentType(Parsing.textAt(job, "/employmentType/id"))
                .description(description)
                .applicationUrl(url)
                .sourceUrl(url)
                .postedAt(Normalize.postedAt(Parsing.text(job, "createdOn"), context.now()))
                .build();
    }
}
