package com.personal.jobagent.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Optional PRIMARY provider backed by a local Scrapling sidecar service.
 *
 * <p>Scrapling (https://github.com/D4Vinci/Scrapling) is a Python fetching
 * library, so it cannot run inside this JVM. The integration therefore follows
 * the same shape as {@link Crawl4AiProvider}: a self-hosted HTTP service (see
 * {@code sidecar/scrapling/}) that exposes a single {@code POST /fetch}
 * endpoint, with this provider translating {@link DiscoveryRequest}s into
 * sidecar calls and the response body into the existing
 * {@link DiscoveryExtractionParser} path. Nothing about the other providers,
 * the orchestrator, or the ingestion contract changes.
 *
 * <p><b>Optional by construction.</b> The provider is enabled only when
 * {@code SCRAPLING_BASE_URL} is set; without it the provider reports
 * {@link ProviderState#UNCONFIGURED} and the orchestrator's
 * {@code available(ProviderRole)} filter skips it, exactly like Crawl4AI. A
 * deployment that never configures the sidecar behaves identically to one
 * before this class existed.
 *
 * <p><b>Fetch modes</b> (chosen with {@code SCRAPLING_FETCH_MODE}, default
 * {@code http}): {@code http} uses Scrapling's plain fetcher for ordinary
 * pages; {@code dynamic} and {@code stealthy} ask the sidecar to use its
 * browser-based fetchers for JavaScript-heavy or protected targets.
 * {@code auto} (Robin x Tsenta hardening) asks the sidecar to run its own
 * bounded, evidence-based escalation instead — http first, escalating to
 * dynamic/stealthy only when the actual fetched result gives a concrete
 * reason to (see {@code sidecar/scrapling/extraction.py#escalate_fetch}),
 * never an open-ended retry loop. The mode applies to every request through
 * this provider — per-request escalation is {@code auto}'s whole point, so
 * an operator who wants it everywhere simply sets the mode once.
 *
 * <p><b>Adaptive field extraction (Robin x Tsenta hardening).</b> The sidecar
 * now also returns a {@code fields} map — job-field values Scrapling's own
 * adaptive CSS/JSON-LD/OpenGraph extraction found, each tagged with its
 * source and a confidence score (see the sidecar's own {@code extraction.py}
 * for exactly how {@code json_ld} &gt; {@code opengraph} &gt;
 * {@code css_selector} ranks, and why a {@code css_selector} value — which
 * may have come from Scrapling's adaptive relocation, not a fresh match — is
 * never treated as equal-strength evidence to a structured JSON-LD claim).
 * This provider never lets that overlay WORSEN what the existing
 * {@link DiscoveryExtractionParser} regex/JSON-LD parse already found: a
 * high-confidence sidecar field only fills a BLANK base field, or replaces a
 * present one when the sidecar's own confidence is at the JSON-LD tier
 * ({@link #HIGH_CONFIDENCE_OVERRIDE}). An older sidecar that never sends
 * {@code fields} at all behaves identically to before this change.
 *
 * <p><b>Security posture.</b> The sidecar is a local, operator-run service: it
 * must never be exposed publicly (the provided compose service binds to
 * 127.0.0.1 only). URLs come from the operator-triggered discovery endpoints,
 * which are authenticated like the rest of the API. An optional
 * {@code SCRAPLING_API_TOKEN} is sent as a Bearer header and is never logged.
 * Scraped page content is untrusted data: it flows only into
 * {@code ExtractedJob} fields as stored evidence and is never interpreted as
 * instructions, and it is never written to logs — log/telemetry surface stays
 * at error codes, matching the other providers.
 */
@Component
public class ScraplingProvider implements ScraperProvider {
    /** Confidence used for parsed pages — same tier as Crawl4AI (self-hosted service). */
    static final double CONFIDENCE = 0.80;
    /** Minimum sidecar field confidence required to REPLACE an already-present base value (json_ld tier only). */
    static final double HIGH_CONFIDENCE_OVERRIDE = 0.85;

    private final ObjectMapper json;
    private final HttpClient client;
    private final String baseUrl;
    private final String token;
    private final String mode;
    private final Duration timeout;

    public ScraplingProvider(ObjectMapper json, Environment env) {
        this.json = json;
        this.baseUrl = env.getProperty("SCRAPLING_BASE_URL", "").trim();
        this.token = env.getProperty("SCRAPLING_API_TOKEN", "").trim();
        String requestedMode = env.getProperty("SCRAPLING_FETCH_MODE", "http").trim().toLowerCase(Locale.ROOT);
        this.mode = switch (requestedMode) {
            case "dynamic", "stealthy", "http", "auto" -> requestedMode;
            default -> "http";
        };
        this.timeout = Duration.ofMillis(env.getProperty("SCRAPLING_TIMEOUT_MS", Integer.class, 60000));
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override public String providerId() { return "scrapling"; }
    @Override public ProviderRole role() { return ProviderRole.PRIMARY; }
    @Override public boolean enabled() { return !baseUrl.isBlank(); }
    @Override public ProviderState state() { return enabled() ? ProviderState.AVAILABLE : ProviderState.UNCONFIGURED; }

    @Override
    public ExtractionResult extract(DiscoveryRequest request) {
        Instant started = Instant.now();
        if (!enabled()) {
            return new ExtractionResult(providerId(), request.correlationId(), started, Instant.now(),
                    List.of(), 0, "UNCONFIGURED", 0);
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("url", request.sourceUrl());
            payload.put("mode", mode);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl.replaceAll("/$", "") + "/fetch"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)));
            if (!token.isBlank()) {
                builder.header("Authorization", "Bearer " + token);
            }
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                return new ExtractionResult(providerId(), request.correlationId(), started, Instant.now(),
                        List.of(), 0, "HTTP_" + response.statusCode(), 0);
            }
            JsonNode root = json.readTree(response.body());
            if (!root.path("ok").asBoolean(false)) {
                // The sidecar reached its own decision that the fetch failed
                // (upstream status, blocked, timeout inside Scrapling). The
                // code stays terse; the detail never includes page content.
                return new ExtractionResult(providerId(), request.correlationId(), started, Instant.now(),
                        List.of(), 0, "SCRAPLING_ERROR", 0);
            }
            String content = root.path("content").asText("").trim();
            if (content.isEmpty()) {
                return new ExtractionResult(providerId(), request.correlationId(), started, Instant.now(),
                        List.of(), 0, "INCOMPLETE_CONTENT", 0);
            }
            ExtractedJob job = content.startsWith("<")
                    ? DiscoveryExtractionParser.fromHtml(content, request.sourceUrl(), providerId(), CONFIDENCE)
                    : DiscoveryExtractionParser.fromMarkdown(content, request.sourceUrl(), providerId(), CONFIDENCE, root.path("data"));
            if (job != null && root.path("fields").isObject()) {
                job = overlayAdaptiveFields(job, root.path("fields"));
            }
            if (job == null) {
                return new ExtractionResult(providerId(), request.correlationId(), started, Instant.now(),
                        List.of(), 0, "INCOMPLETE_CONTENT", 0);
            }
            return new ExtractionResult(providerId(), request.correlationId(), started, Instant.now(),
                    List.of(job), job.confidence(), null, 0);
        } catch (Exception e) {
            return new ExtractionResult(providerId(), request.correlationId(), started, Instant.now(),
                    List.of(), 0, e.getClass().getSimpleName(), 0);
        }
    }

    /**
     * Merges the sidecar's adaptive {@code fields} onto the already-parsed
     * job — see this class's own javadoc for the merge rule (fill a blank
     * base field at any confidence; replace a present one only at the
     * JSON-LD confidence tier). Never widens which fields exist on
     * {@link ExtractedJob} itself — every value still flows through the
     * record's own existing shape, just assembled from two sources instead
     * of one.
     */
    private ExtractedJob overlayAdaptiveFields(ExtractedJob base, JsonNode fields) {
        String title = overlay(fields, "title", base.title());
        String company = overlay(fields, "company", base.company());
        String location = overlay(fields, "location", base.location());
        String description = overlay(fields, "description", base.description());
        String employmentType = overlay(fields, "employment_type", base.employmentType());
        String salary = overlay(fields, "salary_text", base.salary());
        String applicationUrl = overlay(fields, "application_url", base.applicationUrl());
        String sourceUrl = overlay(fields, "canonical_url", base.sourceUrl());
        Instant postedAt = parsePostedAt(fields.path("posted_at").path("value").asText(null), base.postedAt());

        // The provider's own contentHash backs observation-level change
        // detection (JobDeduplicationService), so it must reflect whatever
        // title/description actually ended up on this job, not what the
        // regex-only parse originally produced before the overlay ran.
        boolean contentChanged = !Objects.equals(title, base.title()) || !Objects.equals(description, base.description());
        String contentHash = contentChanged
                ? DiscoveryExtractionParser.sha256(DiscoveryExtractionParser.clean(title) + "\n" + DiscoveryExtractionParser.clean(description))
                : base.contentHash();

        Map<String, Object> raw = new LinkedHashMap<>(base.raw());
        raw.put("adaptiveFields", fieldProvenance(fields));

        return new ExtractedJob(title, company, location, base.remoteType(), salary, employmentType, description,
                base.skills(), applicationUrl, sourceUrl, postedAt, base.externalJobId(), contentHash,
                base.confidence(), raw);
    }

    /** A sidecar field only fills a blank base value, or replaces a present one at the JSON-LD confidence tier. */
    private String overlay(JsonNode fields, String key, String baseValue) {
        JsonNode field = fields.path(key);
        if (!field.isObject()) return baseValue;
        String value = field.path("value").asText(null);
        if (value == null || value.isBlank()) return baseValue;
        if (baseValue == null || baseValue.isBlank()) return value;
        double confidence = field.path("confidence").asDouble(0);
        return confidence >= HIGH_CONFIDENCE_OVERRIDE ? value : baseValue;
    }

    private Instant parsePostedAt(String value, Instant fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }

    /** Retains per-field source/confidence for observability — never used as extraction logic itself. */
    private Map<String, Object> fieldProvenance(JsonNode fields) {
        Map<String, Object> provenance = new LinkedHashMap<>();
        fields.fields().forEachRemaining(entry -> provenance.put(entry.getKey(), Map.of(
                "source", entry.getValue().path("source").asText(""),
                "confidence", entry.getValue().path("confidence").asDouble(0))));
        return provenance;
    }
}
