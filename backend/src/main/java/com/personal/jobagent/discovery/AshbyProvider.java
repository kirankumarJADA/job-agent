package com.personal.jobagent.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.util.HtmlUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Structured connector for Ashby's public Job Postings API. It is dispatched
 * explicitly by the persisted ASHBY source kind, not added to the generic URL
 * provider pool, so generic browser crawling remains available as a fallback.
 */
public class AshbyProvider implements ScraperProvider {
    public static final String SOURCE_KIND = "ASHBY";

    private static final String DEFAULT_API_BASE = "https://api.ashbyhq.com";
    private static final String BOARD_HOST = "jobs.ashbyhq.com";
    private static final String BOARD_NAME_PATTERN = "[A-Za-z0-9_-]{1,100}";

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final String apiBase;

    public AshbyProvider() {
        this.apiBase = DEFAULT_API_BASE;
    }

    /** Test seam for local stub servers; production always uses the official endpoint. */
    AshbyProvider(String apiBase) {
        this.apiBase = apiBase.trim().replaceAll("/$", "");
    }

    @Override public String providerId() { return "ashby"; }
    @Override public ProviderRole role() { return ProviderRole.PRIMARY; }
    @Override public boolean enabled() { return true; }
    @Override public ProviderState state() { return ProviderState.AVAILABLE; }

    /** Construct the public API URL only after validating the board slug. */
    public String jobBoardUrl(String boardName) {
        String safeBoard = validateBoardName(boardName);
        return apiBase + "/posting-api/job-board/" + safeBoard;
    }

    /** Extract a board slug from exactly the hosted Ashby board URL form. */
    public static String boardNameFromHostedUrl(String hostedUrl) {
        if (hostedUrl == null || hostedUrl.isBlank()) {
            throw new IllegalArgumentException("Ashby board URL is required");
        }
        try {
            URI uri = URI.create(hostedUrl.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || !BOARD_HOST.equalsIgnoreCase(uri.getHost())
                    || uri.getUserInfo() != null
                    || uri.getPort() != -1
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw new IllegalArgumentException("URL must be https://jobs.ashbyhq.com/{board}");
            }
            String path = uri.getPath();
            if (path == null || !path.matches("/[A-Za-z0-9_-]{1,100}/?")) {
                throw new IllegalArgumentException("URL must contain one valid Ashby board name");
            }
            return validateBoardName(path.substring(1).replaceAll("/$", ""));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid Ashby board URL", e);
        }
    }

    /** Accept the source row's board slug or canonical hosted-board URL. */
    public static String boardNameFromIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("Ashby org_identifier must contain a board name or hosted-board URL");
        }
        String trimmed = identifier.trim();
        if (trimmed.startsWith("https://") || trimmed.startsWith("http://")) {
            return boardNameFromHostedUrl(trimmed);
        }
        return validateBoardName(trimmed);
    }

    public static boolean isHostedBoardUrl(String value) {
        if (value == null) return false;
        try {
            URI uri = URI.create(value.trim());
            return "https".equalsIgnoreCase(uri.getScheme())
                    && BOARD_HOST.equalsIgnoreCase(uri.getHost());
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Convenience entry point used by the source-kind-aware orchestrator. */
    public ExtractionResult extractBoard(String boardName, String correlationId) {
        return extract(new DiscoveryRequest(jobBoardUrl(boardName), SOURCE_KIND, correlationId, Map.of()));
    }

    @Override
    public ExtractionResult extract(DiscoveryRequest request) {
        Instant started = Instant.now();
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(request.sourceUrl()))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json")
                    .header("User-Agent", "PersonalJobAgent/1.0 (+permitted-discovery)")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) return result(request, started, List.of(), "QUOTA_EXHAUSTED");
            if (response.statusCode() >= 400) return result(request, started, List.of(), "HTTP_" + response.statusCode());

            JsonNode root;
            try {
                root = json.readTree(response.body());
            } catch (Exception e) {
                return result(request, started, List.of(), "MALFORMED_RESPONSE");
            }
            JsonNode postings = root.path("jobs");
            if (!postings.isArray()) return result(request, started, List.of(), "INCOMPLETE_EXTRACTION");

            List<ExtractedJob> jobs = new ArrayList<>();
            for (JsonNode posting : postings) {
                // Explicitly unlisted entries are not public board listings. If
                // the optional flag is absent, the public endpoint is authoritative.
                if (posting.has("isListed") && posting.path("isListed").isBoolean()
                        && !posting.path("isListed").asBoolean()) continue;
                ExtractedJob job = map(posting);
                if (job != null) jobs.add(job);
            }
            return result(request, started, jobs, null);
        } catch (Exception e) {
            return result(request, started, List.of(), e.getClass().getSimpleName());
        }
    }

    private ExtractedJob map(JsonNode posting) {
        String title = text(posting, "title");
        if (title == null) return null;

        String description = text(posting, "descriptionPlain");
        if (description == null) {
            String html = text(posting, "descriptionHtml");
            if (html != null) description = DiscoveryExtractionParser.clean(HtmlUtils.htmlUnescape(html));
        }
        String jobUrl = text(posting, "jobUrl");
        String applyUrl = text(posting, "applyUrl");
        String location = locations(posting);
        String remoteType = remoteType(posting);
        String postedAtValue = text(posting, "publishedAt");
        Instant postedAt = parseInstant(postedAtValue);
        if (description == null) description = "";

        return new ExtractedJob(
                title,
                null, // The public posting payload has no company/organization field.
                location,
                remoteType,
                null, // Salary is not part of the listed public posting fields.
                text(posting, "employmentType"),
                description,
                List.of(), // Skills are not separately exposed and are not inferred here.
                applyUrl != null ? applyUrl : jobUrl,
                jobUrl,
                postedAt,
                text(posting, "id"),
                DiscoveryExtractionParser.sha256(title + "\n" + (description == null ? "" : description)),
                1.0,
                Map.of("provider", providerId())
        );
    }

    private static String locations(JsonNode posting) {
        List<String> values = new ArrayList<>();
        addLocation(values, posting.path("location"));
        JsonNode secondary = posting.path("secondaryLocations");
        if (secondary.isArray()) {
            for (JsonNode location : secondary) addLocation(values, location);
        }
        return values.isEmpty() ? null : String.join("; ", values.stream().distinct().toList());
    }

    private static void addLocation(List<String> values, JsonNode node) {
        String value = node.isTextual() ? node.asText().trim() : text(node, "location");
        if (value == null) value = text(node, "name");
        if (value != null && !value.isBlank()) values.add(value);
    }

    private static String remoteType(JsonNode posting) {
        String workplace = text(posting, "workplaceType");
        if (workplace != null) {
            String normalized = workplace.trim().toLowerCase(Locale.ROOT);
            if (normalized.contains("remote")) return "REMOTE";
            if (normalized.contains("hybrid")) return "HYBRID";
            if (normalized.contains("on-site") || normalized.contains("onsite") || normalized.contains("office")) return "ONSITE";
        }
        return posting.path("isRemote").isBoolean() && posting.path("isRemote").asBoolean() ? "REMOTE" : null;
    }

    private static Instant parseInstant(String value) {
        if (value == null) return null;
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

    private static String validateBoardName(String boardName) {
        if (boardName == null || !boardName.trim().matches(BOARD_NAME_PATTERN)) {
            throw new IllegalArgumentException("Ashby board name must match " + BOARD_NAME_PATTERN);
        }
        return boardName.trim();
    }

    private static String text(JsonNode node, String field) {
        return textValue(node == null ? null : node.path(field));
    }

    private static String textValue(JsonNode node) {
        String value = node == null ? "" : node.asText("").trim();
        return value.isEmpty() ? null : value;
    }

    private ExtractionResult result(DiscoveryRequest request, Instant started, List<ExtractedJob> jobs, String error) {
        double confidence = jobs.isEmpty() ? 0 : jobs.stream().mapToDouble(ExtractedJob::confidence).average().orElse(0);
        return new ExtractionResult(providerId(), request.correlationId(), started, Instant.now(), jobs, confidence, error, 0);
    }
}
