package com.personal.jobagent.discovery.platform;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The only HTTP client discovery adapters use. One instance per run: it
 * carries that run's request budget and politeness interval.
 *
 * <ul>
 *   <li>every URL and every redirect hop passes {@link AddressGuard} (SSRF);</li>
 *   <li>redirects are followed manually (max 3) so each hop is re-validated;</li>
 *   <li>connect/read timeouts, a response size cap and a content-type allowlist;</li>
 *   <li>bounded retries with exponential backoff + jitter on 429/5xx/timeouts,
 *       honouring Retry-After up to a ceiling;</li>
 *   <li>a hard per-run request budget and a minimum interval between requests.</li>
 * </ul>
 * Nothing that could be a credential is logged or echoed in exceptions:
 * messages carry the category and status only, never headers or bodies.
 */
public class SafeHttpClient {

    public static final String USER_AGENT = "RobinJobAgent/1.0 (+authorised-discovery)";

    public enum Accept {
        JSON("application/json", List.of("json")),
        HTML("text/html,application/xhtml+xml", List.of("html", "xhtml")),
        XML("application/rss+xml,application/xml,text/xml", List.of("xml", "rss")),
        /**
         * An XML document some providers label text/html (JazzHR's public feed does, verified live).
         * The caller's XML parser still demands the expected root element, so an HTML page is rejected
         * as malformed rather than parsed.
         */
        XML_LABELLED_HTML("application/xml,text/xml,text/html", List.of("xml", "html")),
        TEXT("text/plain", List.of("text/plain"));

        final String header;
        final List<String> allowedFragments;

        Accept(String header, List<String> allowedFragments) {
            this.header = header;
            this.allowedFragments = allowedFragments;
        }
    }

    /** Low-level exchange seam: production uses the JDK client; tests replay fixtures. */
    public interface Transport {
        RawResponse exchange(String method, URI uri, Map<String, String> headers, byte[] body,
                             Duration timeout, int maxBytes) throws IOException;
    }

    public record RawResponse(int status, Map<String, String> headers, byte[] body, boolean truncated) {
        public Optional<String> header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(Map.Entry::getValue).findFirst();
        }
    }

    /** Sleep seam so backoff tests run instantly. */
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    public record Settings(int maxRequests, long minIntervalMs, Duration timeout, int maxBytes,
                           int maxAttempts, long baseBackoffMs, long maxRetryAfterMs) {
        public static Settings defaults() {
            return new Settings(400, 250, Duration.ofSeconds(20), 8 * 1024 * 1024, 3, 500, 10_000);
        }

        public Settings withMaxRequests(int n) {
            return new Settings(n, minIntervalMs, timeout, maxBytes, maxAttempts, baseBackoffMs, maxRetryAfterMs);
        }

        public Settings withMinInterval(long ms) {
            return new Settings(maxRequests, ms, timeout, maxBytes, maxAttempts, baseBackoffMs, maxRetryAfterMs);
        }

        public Settings withMaxBytes(int bytes) {
            return new Settings(maxRequests, minIntervalMs, timeout, bytes, maxAttempts, baseBackoffMs, maxRetryAfterMs);
        }

        public Settings withTimeout(Duration t) {
            return new Settings(maxRequests, minIntervalMs, t, maxBytes, maxAttempts, baseBackoffMs, maxRetryAfterMs);
        }
    }

    private final Transport transport;
    private final AddressGuard guard;
    private final Settings settings;
    private final Sleeper sleeper;
    private int requestsMade;
    private long lastRequestAt;

    public SafeHttpClient(Transport transport, AddressGuard guard, Settings settings, Sleeper sleeper) {
        this.transport = transport;
        this.guard = guard;
        this.settings = settings;
        this.sleeper = sleeper;
    }

    public static SafeHttpClient live(Settings settings) {
        return new SafeHttpClient(new JdkTransport(), new AddressGuard(), settings, Thread::sleep);
    }

    public int requestsMade() {
        return requestsMade;
    }

    public int remainingBudget() {
        return Math.max(0, settings.maxRequests() - requestsMade);
    }

    public String get(URI uri, Accept accept) {
        return exchange("GET", uri, null, accept);
    }

    public String postJson(URI uri, String jsonBody, Accept accept) {
        return exchange("POST", uri, jsonBody, accept);
    }

    private String exchange(String method, URI uri, String body, Accept accept) {
        URI current = uri;
        for (int hop = 0; hop <= 3; hop++) {
            try {
                guard.check(current);
            } catch (AddressGuard.UnsafeAddressException e) {
                throw new FetchException(FailureCategory.UNSAFE_ADDRESS, e.getMessage(), 0);
            }
            RawResponse response = withRetries(method, current, body, accept);
            int status = response.status();
            if (status >= 300 && status < 400 && response.header("Location").isPresent()) {
                URI next;
                try {
                    next = current.resolve(response.header("Location").get().strip());
                } catch (IllegalArgumentException e) {
                    throw new FetchException(FailureCategory.MALFORMED_RESPONSE, "invalid redirect target", status);
                }
                if (hop == 3) throw new FetchException(FailureCategory.RESPONSE_REJECTED, "too many redirects", status);
                current = next;
                if (status != 307 && status != 308) {
                    method = "GET";
                    body = null;
                }
                continue;
            }
            return decode(response, accept, status);
        }
        throw new FetchException(FailureCategory.RESPONSE_REJECTED, "too many redirects", 0);
    }

    private String decode(RawResponse response, Accept accept, int status) {
        if (status == 401 || status == 403) {
            throw new FetchException(FailureCategory.ACCESS_DENIED, "HTTP " + status, status);
        }
        if (status == 404 || status == 410) throw new FetchException(FailureCategory.NOT_FOUND, "HTTP " + status, status);
        if (status == 429) throw new FetchException(FailureCategory.RATE_LIMITED, "HTTP 429", status);
        if (status >= 500) throw new FetchException(FailureCategory.UPSTREAM_ERROR, "HTTP " + status, status);
        if (status < 200 || status >= 300) {
            throw new FetchException(FailureCategory.CONFIGURATION, "HTTP " + status, status);
        }
        if (response.truncated()) {
            throw new FetchException(FailureCategory.RESPONSE_REJECTED, "response exceeded " + settings.maxBytes() + " bytes", status);
        }
        String contentType = response.header("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        boolean allowed = accept.allowedFragments.stream().anyMatch(contentType::contains);
        if (!allowed) {
            throw new FetchException(FailureCategory.RESPONSE_REJECTED,
                    "unexpected content type " + contentType.replaceAll(";.*", ""), status);
        }
        Charset charset = StandardCharsets.UTF_8;
        int idx = contentType.indexOf("charset=");
        if (idx >= 0) {
            try {
                charset = Charset.forName(contentType.substring(idx + 8).replaceAll("[\";].*", "").strip());
            } catch (RuntimeException ignored) {
                // keep UTF-8
            }
        }
        return new String(response.body(), charset);
    }

    private RawResponse withRetries(String method, URI uri, String body, Accept accept) {
        FetchException last = null;
        for (int attempt = 1; attempt <= Math.max(1, settings.maxAttempts()); attempt++) {
            if (requestsMade >= settings.maxRequests()) {
                throw new FetchException(FailureCategory.BUDGET_EXHAUSTED,
                        "request budget of " + settings.maxRequests() + " exhausted", 0);
            }
            pace();
            requestsMade++;
            RawResponse response;
            try {
                java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
                headers.put("User-Agent", USER_AGENT);
                headers.put("Accept", accept.header);
                if (body != null) headers.put("Content-Type", "application/json");
                response = transport.exchange(method, uri, headers,
                        body == null ? null : body.getBytes(StandardCharsets.UTF_8), settings.timeout(), settings.maxBytes());
            } catch (HttpTimeoutException e) {
                last = new FetchException(FailureCategory.TIMEOUT, "timed out", 0);
                backoff(attempt, null);
                continue;
            } catch (IOException e) {
                last = new FetchException(FailureCategory.NETWORK, "network error: " + e.getClass().getSimpleName(), 0);
                backoff(attempt, null);
                continue;
            }
            int status = response.status();
            if (status == 429 || status == 502 || status == 503 || status == 504) {
                last = new FetchException(status == 429 ? FailureCategory.RATE_LIMITED : FailureCategory.UPSTREAM_ERROR,
                        "HTTP " + status, status);
                if (attempt < settings.maxAttempts()) backoff(attempt, response.header("Retry-After").orElse(null));
                continue;
            }
            return response;
        }
        throw last == null ? new FetchException(FailureCategory.NETWORK, "request failed", 0) : last;
    }

    private void pace() {
        long now = System.currentTimeMillis();
        long wait = lastRequestAt + settings.minIntervalMs() - now;
        if (lastRequestAt > 0 && wait > 0) sleepQuietly(wait);
        lastRequestAt = System.currentTimeMillis();
    }

    private void backoff(int attempt, String retryAfter) {
        long delay = settings.baseBackoffMs() * (1L << Math.min(attempt - 1, 6));
        delay += ThreadLocalRandom.current().nextLong(Math.max(1, settings.baseBackoffMs() / 2));
        if (retryAfter != null) {
            try {
                long seconds = Long.parseLong(retryAfter.strip());
                delay = Math.max(delay, seconds * 1000);
            } catch (NumberFormatException ignored) {
                // HTTP-date form: keep exponential delay
            }
        }
        sleepQuietly(Math.min(delay, settings.maxRetryAfterMs()));
    }

    private void sleepQuietly(long ms) {
        try {
            sleeper.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FetchException(FailureCategory.TIMEOUT, "interrupted", 0);
        }
    }

    /** A failed fetch with its category. The message never contains headers or bodies. */
    public static class FetchException extends RuntimeException {
        private final FailureCategory category;
        private final int status;

        public FetchException(FailureCategory category, String message, int status) {
            super(message);
            this.category = category;
            this.status = status;
        }

        public FailureCategory category() {
            return category;
        }

        public int status() {
            return status;
        }
    }

    /** JDK transport: never follows redirects itself and stops reading at the size cap. */
    static final class JdkTransport implements Transport {
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        @Override
        public RawResponse exchange(String method, URI uri, Map<String, String> headers, byte[] body,
                                    Duration timeout, int maxBytes) throws IOException {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout);
            headers.forEach(builder::header);
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(body));
            try {
                HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
                byte[] bytes;
                boolean truncated;
                try (InputStream in = response.body()) {
                    bytes = in.readNBytes(maxBytes + 1);
                    truncated = bytes.length > maxBytes;
                    if (truncated) bytes = java.util.Arrays.copyOf(bytes, maxBytes);
                }
                Map<String, String> flat = new java.util.LinkedHashMap<>();
                response.headers().map().forEach((k, v) -> { if (!v.isEmpty()) flat.put(k, v.get(0)); });
                return new RawResponse(response.statusCode(), flat, bytes, truncated);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        }
    }
}
