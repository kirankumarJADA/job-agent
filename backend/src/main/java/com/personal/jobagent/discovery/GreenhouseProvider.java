package com.personal.jobagent.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.util.HtmlUtils;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * The first platform-specific discovery connector: Greenhouse public job boards.
 *
 * <p>Every Greenhouse board exposes a public, unauthenticated JSON API —
 * {@code https://boards-api.greenhouse.io/v1/boards/{token}/jobs} (verified
 * live against the real endpoint in this repository's integration work; the
 * historically assumed {@code api.greenhouse.io} host is wrong). With
 * {@code content=true} each entry carries the full posting HTML. This provider
 * calls that API for one board and maps every entry into the existing
 * {@link ScraperProvider.ExtractedJob} contract — one {@link ExtractionResult}
 * with one entry per posting, which the existing orchestrator, deduplication
 * and ingestion pipeline consume unchanged.
 *
 * <p><b>Not part of the generic provider pool.</b> Unlike Crawl4AI this
 * provider is not URL-agnostic: it fetches a board API derived from a
 * source's {@code org_identifier}, so it must never be invoked for arbitrary
 * URLs. It is therefore deliberately not a Spring component; the orchestrator
 * receives it as an explicit dependency and dispatches to it only for sources
 * whose {@code job_sources.kind} is {@code GREENHOUSE} (see
 * {@code DiscoveryOrchestrator.discoverGreenhouseBoard}). Generic URL
 * discovery is untouched.
 *
 * <p><b>Field mapping</b> (only fields the API actually provides; nothing
 * fabricated — absent values stay null per the existing conventions):
 * {@code id → externalJobId}, {@code title → title}, {@code company_name →
 * company}, {@code location.name → location}, {@code absolute_url →
 * applicationUrl/sourceUrl}, {@code first_published → postedAt} (falling back
 * to {@code updated_at}), {@code content} (entity-encoded HTML) → tag-stripped
 * {@code description}. Workplace type, salary and skills are not exposed by
 * the API and stay null.
 *
 * <p><b>Security.</b> The org token is validated against a strict pattern
 * before it is placed into the URL, so a crafted {@code org_identifier} cannot
 * change the host/path (SSRF via URL construction is not possible). The
 * endpoint is public and unauthenticated, so no credentials exist to leak;
 * logs carry status codes only, never posting content.
 */
public class GreenhouseProvider implements ScraperProvider {

    /** The {@code job_sources.kind} value this connector serves. */
    public static final String SOURCE_KIND = "GREENHOUSE";

    /** Greenhouse board tokens are short slugs; anything else must never reach the URL. */
    private static final String ORG_TOKEN_PATTERN = "[A-Za-z0-9_-]{1,100}";
    private static final String DEFAULT_BOARD_API_BASE = "https://boards-api.greenhouse.io";

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final String boardApiBase;

    public GreenhouseProvider() {
        // Resolution order: system property (used by integration tests to aim
        // the connector at a local stub), then the environment, then the
        // verified public API host.
        String prop = System.getProperty("greenhouse.board-api-base");
        String env = System.getenv("GREENHOUSE_API_BASE_URL");
        String resolved = (prop != null && !prop.isBlank()) ? prop
                : (env == null || env.isBlank()) ? DEFAULT_BOARD_API_BASE : env;
        this.boardApiBase = resolved.trim().replaceAll("/$", "");
    }

    GreenhouseProvider(String boardApiBase) {
        this.boardApiBase = boardApiBase.replaceAll("/$", "");
    }

    @Override public String providerId() { return "greenhouse"; }
    @Override public ProviderRole role() { return ProviderRole.PRIMARY; }
    @Override public boolean enabled() { return true; } // public API, no credentials
    @Override public ProviderState state() { return ProviderState.AVAILABLE; }

    /**
     * Builds the board-jobs API URL for one organisation token, rejecting
     * tokens that could alter the request target.
     *
     * @throws IllegalArgumentException when the token is not a plain slug
     */
    public String boardJobsUrl(String orgToken) {
        if (orgToken == null || !orgToken.trim().matches(ORG_TOKEN_PATTERN)) {
            throw new IllegalArgumentException("org_identifier must match " + ORG_TOKEN_PATTERN);
        }
        return boardApiBase + "/v1/boards/" + orgToken.trim() + "/jobs?content=true";
    }

    /** Convenience entry point used by the orchestrator's kind-aware dispatch. */
    public ExtractionResult extractBoard(String orgToken, String correlationId) {
        return extract(new DiscoveryRequest(boardJobsUrl(orgToken), SOURCE_KIND, correlationId, Map.of()));
    }

    @Override
    public ExtractionResult extract(DiscoveryRequest request) {
        Instant started = Instant.now();
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(request.sourceUrl()))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .header("User-Agent", "PersonalJobAgent/1.0 (+permitted-discovery)")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) {
                return result(request, started, List.of(), "QUOTA_EXHAUSTED");
            }
            if (response.statusCode() >= 400) {
                return result(request, started, List.of(), "HTTP_" + response.statusCode());
            }

            JsonNode root;
            try {
                root = json.readTree(response.body());
            } catch (Exception e) {
                return result(request, started, List.of(), "MALFORMED_RESPONSE");
            }
            JsonNode jobs = root.path("jobs");
            if (!jobs.isArray()) {
                return result(request, started, List.of(), "INCOMPLETE_EXTRACTION");
            }

            List<ExtractedJob> extracted = new ArrayList<>();
            for (JsonNode entry : jobs) {
                ExtractedJob job = map(entry, request.sourceUrl());
                if (job != null) {
                    extracted.add(job);
                }
            }
            // An empty board is a valid outcome, not a failure: no error, no jobs.
            return result(request, started, extracted, null);
        } catch (Exception e) {
            return result(request, started, List.of(), e.getClass().getSimpleName());
        }
    }

    /**
     * Maps one Greenhouse posting entry. Returns null when the entry carries no
     * usable title (the one field every downstream consumer requires).
     */
    private ExtractedJob map(JsonNode entry, String boardUrl) {
        String title = text(entry, "title");
        if (title == null) {
            return null;
        }
        String descriptionHtml = text(entry, "content");
        String description = descriptionHtml == null ? null
                : DiscoveryExtractionParser.clean(HtmlUtils.htmlUnescape(descriptionHtml));
        String descriptionText = description == null || description.isBlank() ? null : description;
        String absoluteUrl = text(entry, "absolute_url");
        String contentHash = DiscoveryExtractionParser.sha256((title == null ? "" : title) + "\n" + (descriptionText == null ? "" : descriptionText));

        return new ExtractedJob(
                title,
                text(entry, "company_name"),
                text(entry.path("location"), "name"),
                null, // workplace type is not exposed by the board API
                null, // salary is not exposed by the board API
                text(entry, "employment_type"),
                descriptionText,
                null, // skills: the API exposes none and Robin does not infer them here
                absoluteUrl,
                absoluteUrl != null ? absoluteUrl : boardUrl,
                postedAt(entry),
                text(entry, "id"),
                contentHash,
                1.0, // structured official API data — no inference involved
                Map.of("provider", providerId())
        );
    }

    /** Posted date: {@code first_published} when present, otherwise {@code updated_at}. */
    private static Instant postedAt(JsonNode entry) {
        for (String field : List.of("first_published", "updated_at")) {
            String value = text(entry, field);
            if (value != null) {
                try {
                    return OffsetDateTime.parse(value).toInstant();
                } catch (DateTimeParseException ignored) {
                    try {
                        return Instant.parse(value);
                    } catch (DateTimeParseException alsoIgnored) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        return value.isEmpty() ? null : value;
    }

    private static ExtractionResult result(DiscoveryRequest request, Instant started, List<ExtractedJob> jobs, String error) {
        double confidence = jobs.isEmpty() ? 0 : jobs.stream().mapToDouble(ExtractedJob::confidence).average().orElse(0);
        return new ExtractionResult("greenhouse", request.correlationId(), started, Instant.now(), jobs, confidence, error, 0);
    }
}
