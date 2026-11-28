package com.personal.jobagent.discovery.platform;

import javax.net.ssl.SSLContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /** Pinning-aware transport: production connects strictly to AddressGuard-validated IPs. */
    public interface PinnedTransport extends Transport {
        RawResponse exchangePinned(String method, URI uri, List<InetAddress> pinnedAddresses,
                                   Map<String, String> headers, byte[] body,
                                   Duration timeout, int maxBytes) throws IOException;
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
    private final Long deadlineNanos;
    private int requestsMade;
    private long lastRequestAt;

    public SafeHttpClient(Transport transport, AddressGuard guard, Settings settings, Sleeper sleeper) {
        this(transport, guard, settings, sleeper, null);
    }

    public SafeHttpClient(Transport transport, AddressGuard guard, Settings settings, Sleeper sleeper, Long deadlineNanos) {
        this.transport = transport;
        this.guard = guard;
        this.settings = settings;
        this.sleeper = sleeper;
        this.deadlineNanos = deadlineNanos;
    }

    public static SafeHttpClient live(Settings settings) {
        return new SafeHttpClient(new JdkTransport(), new AddressGuard(), settings, Thread::sleep);
    }

    /**
     * Constructs a live client with an explicit {@link AddressGuard} (e.g. permitting
     * local loopback test fixtures when {@code app.sandbox.allow-local-test-hosts=true}
     * while continuing to block private, metadata, and link-local addresses).
     */
    public static SafeHttpClient forGuard(AddressGuard guard, Settings settings) {
        return new SafeHttpClient(new JdkTransport(), guard, settings, Thread::sleep);
    }

    public int requestsMade() {
        return requestsMade;
    }

    public int remainingBudget() {
        return Math.max(0, settings.maxRequests() - requestsMade);
    }

    public Settings settings() {
        return settings;
    }

    public Long deadlineNanos() {
        return deadlineNanos;
    }

    public SafeHttpClient withTimeout(Duration timeout) {
        return new SafeHttpClient(this.transport, this.guard, this.settings.withTimeout(timeout), this.sleeper, this.deadlineNanos);
    }

    public SafeHttpClient withDeadline(Long deadlineNanos) {
        return new SafeHttpClient(this.transport, this.guard, this.settings, this.sleeper, deadlineNanos);
    }

    public String get(URI uri, Accept accept) {
        return exchange("GET", uri, null, accept);
    }

    public String postJson(URI uri, String jsonBody, Accept accept) {
        return exchange("POST", uri, jsonBody, accept);
    }

    private void checkDeadline(String phase) {
        if (deadlineNanos != null && System.nanoTime() >= deadlineNanos) {
            throw new FetchException(FailureCategory.TIMEOUT, "Execution deadline exceeded " + phase, 0);
        }
    }

    private String exchange(String method, URI uri, String body, Accept accept) {
        URI current = uri;
        for (int hop = 0; hop <= 3; hop++) {
            checkDeadline("before redirect hop " + hop);
            List<InetAddress> pinned;
            try {
                if (overridesLegacyCheck(guard.getClass())) {
                    guard.check(current);
                    pinned = resolveOverrideGuard(guard, current);
                } else {
                    pinned = guard.checkAndResolve(current);
                }
            } catch (AddressGuard.UnsafeAddressException e) {
                throw new FetchException(FailureCategory.UNSAFE_ADDRESS, e.getMessage(), 0);
            }
            RawResponse response = withRetries(method, current, pinned, body, accept);
            checkDeadline("after request attempt for " + current);
            int status = response.status();
            if (status >= 300 && status < 400 && response.header("Location").isPresent()) {
                checkDeadline("before redirect resolution");
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

    private static boolean overridesLegacyCheck(Class<?> guardClass) {
        for (Class<?> c = guardClass; c != null && c != AddressGuard.class; c = c.getSuperclass()) {
            try {
                c.getDeclaredMethod("check", URI.class);
                return true;
            } catch (NoSuchMethodException ignored) {
            }
        }
        return false;
    }

    private static List<InetAddress> resolveOverrideGuard(AddressGuard guard, URI uri) {
        try {
            List<InetAddress> viaMethod = guard.checkAndResolve(uri);
            if (viaMethod != null && !viaMethod.isEmpty()) {
                return viaMethod;
            }
        } catch (AddressGuard.UnsafeAddressException ignored) {
            // Custom subclass overrode check(URI) only (e.g., loopback test guard); do not fail closed on checkAndResolve.
        }
        return List.of();
    }

    private RawResponse withRetries(String method, URI uri, List<InetAddress> pinnedAddresses, String body, Accept accept) {
        FetchException last = null;
        for (int attempt = 1; attempt <= Math.max(1, settings.maxAttempts()); attempt++) {
            checkDeadline("before request attempt " + attempt);
            if (requestsMade >= settings.maxRequests()) {
                throw new FetchException(FailureCategory.BUDGET_EXHAUSTED,
                        "request budget of " + settings.maxRequests() + " exhausted", 0);
            }
            pace();
            checkDeadline("after pacing before attempt " + attempt);
            requestsMade++;
            RawResponse response;
            Duration attemptTimeout = settings.timeout();
            if (deadlineNanos != null) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    throw new FetchException(FailureCategory.TIMEOUT, "Execution deadline exceeded before transport exchange", 0);
                }
                Duration remaining = Duration.ofNanos(remainingNanos);
                if (attemptTimeout == null || remaining.compareTo(attemptTimeout) < 0) {
                    attemptTimeout = remaining;
                }
            }
            try {
                java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
                headers.put("User-Agent", USER_AGENT);
                headers.put("Accept", accept.header);
                if (body != null) headers.put("Content-Type", "application/json");
                byte[] bodyBytes = body == null ? null : body.getBytes(StandardCharsets.UTF_8);
                if (transport instanceof PinnedTransport pinnedTransport
                        && pinnedAddresses != null && !pinnedAddresses.isEmpty()) {
                    response = pinnedTransport.exchangePinned(method, uri, pinnedAddresses,
                            headers, bodyBytes, attemptTimeout, settings.maxBytes());
                } else {
                    response = transport.exchange(method, uri, headers, bodyBytes, attemptTimeout, settings.maxBytes());
                }
            } catch (HttpTimeoutException e) {
                last = new FetchException(FailureCategory.TIMEOUT, "timed out", 0);
                if (deadlineNanos != null && System.nanoTime() >= deadlineNanos) {
                    throw last;
                }
                if (attempt < settings.maxAttempts()) {
                    backoff(attempt, null);
                }
                continue;
            } catch (IOException e) {
                last = new FetchException(FailureCategory.NETWORK, "network error: " + e.getClass().getSimpleName(), 0);
                if (deadlineNanos != null && System.nanoTime() >= deadlineNanos) {
                    throw new FetchException(FailureCategory.TIMEOUT, "Execution deadline exceeded during network error", 0);
                }
                if (attempt < settings.maxAttempts()) {
                    backoff(attempt, null);
                }
                continue;
            }
            int status = response.status();
            if (status == 429 || status == 502 || status == 503 || status == 504) {
                last = new FetchException(status == 429 ? FailureCategory.RATE_LIMITED : FailureCategory.UPSTREAM_ERROR,
                        "HTTP " + status, status);
                if (attempt < settings.maxAttempts()) {
                    if (deadlineNanos != null && System.nanoTime() >= deadlineNanos) {
                        throw new FetchException(FailureCategory.TIMEOUT, "Execution deadline exceeded before retry attempt", 0);
                    }
                    backoff(attempt, response.header("Retry-After").orElse(null));
                }
                continue;
            }
            return response;
        }
        throw last == null ? new FetchException(FailureCategory.NETWORK, "request failed", 0) : last;
    }

    private void pace() {
        long now = System.currentTimeMillis();
        long wait = lastRequestAt + settings.minIntervalMs() - now;
        if (lastRequestAt > 0 && wait > 0) {
            if (deadlineNanos != null) {
                long remainingMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
                if (remainingMs <= 0) {
                    throw new FetchException(FailureCategory.TIMEOUT, "Execution deadline exceeded during pacing", 0);
                }
                wait = Math.min(wait, remainingMs);
            }
            sleepQuietly(wait);
        }
        lastRequestAt = System.currentTimeMillis();
    }

    private void backoff(int attempt, String retryAfter) {
        checkDeadline("before retry backoff for attempt " + attempt);
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
        long targetWait = Math.min(delay, settings.maxRetryAfterMs());
        if (deadlineNanos != null) {
            long remainingMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
            if (remainingMs <= 0) {
                throw new FetchException(FailureCategory.TIMEOUT, "Execution deadline exceeded before backoff sleep", 0);
            }
            targetWait = Math.min(targetWait, remainingMs);
        }
        sleepQuietly(targetWait);
        checkDeadline("after retry backoff for attempt " + attempt);
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

    /**
     * JDK transport: never follows redirects itself, stops reading at the size cap,
     * and when given AddressGuard-validated IPs, routes through an ephemeral loopback
     * tunnel so JDK HttpClient performs full TLS/SAN/SNI verification while connecting
     * strictly to the validated IP(s) with zero second DNS resolution.
     */
    static final class JdkTransport implements PinnedTransport {
        private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "safe-http-body-watchdog");
            t.setDaemon(true);
            return t;
        });

        private static final ThreadLocal<InetSocketAddress> PINNED_PROXY = new ThreadLocal<>();

        private final HttpClient directClient;
        private final HttpClient pinnedClient;

        JdkTransport() {
            this(defaultSslContext());
        }

        JdkTransport(SSLContext sslContext) {
            this.directClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .sslContext(sslContext)
                    .build();
            this.pinnedClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .sslContext(sslContext)
                    .proxy(new ProxySelector() {
                        @Override
                        public List<Proxy> select(URI uri) {
                            InetSocketAddress tunnel = PINNED_PROXY.get();
                            return tunnel == null ? List.of(Proxy.NO_PROXY)
                                    : List.of(new Proxy(Proxy.Type.HTTP, tunnel));
                        }

                        @Override
                        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                        }
                    })
                    .build();
        }

        private static SSLContext defaultSslContext() {
            try {
                return SSLContext.getDefault();
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("Default SSLContext unavailable", e);
            }
        }

        @Override
        public RawResponse exchange(String method, URI uri, Map<String, String> headers, byte[] body,
                                    Duration timeout, int maxBytes) throws IOException {
            return executeRequest(directClient, method, uri, headers, body, timeout, maxBytes);
        }

        @Override
        public RawResponse exchangePinned(String method, URI uri, List<InetAddress> pinnedAddresses,
                                          Map<String, String> headers, byte[] body,
                                          Duration timeout, int maxBytes) throws IOException {
            if (pinnedAddresses == null || pinnedAddresses.isEmpty()) {
                return exchange(method, uri, headers, body, timeout, maxBytes);
            }
            int targetPort = uri.getPort() != -1 ? uri.getPort()
                    : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
            try (PinnedTunnel tunnel = PinnedTunnel.open(uri.getHost(), targetPort, pinnedAddresses, timeout)) {
                PINNED_PROXY.set(tunnel.localAddress());
                try {
                    return executeRequest(pinnedClient, method, uri, headers, body, timeout, maxBytes);
                } finally {
                    PINNED_PROXY.remove();
                }
            }
        }

        private RawResponse executeRequest(HttpClient client, String method, URI uri, Map<String, String> headers,
                                           byte[] body, Duration timeout, int maxBytes) throws IOException {
            long deadlineNanos = (timeout != null)
                    ? System.nanoTime() + timeout.toNanos()
                    : Long.MAX_VALUE;

            HttpRequest.Builder builder = HttpRequest.newBuilder(uri);
            if (timeout != null && !timeout.isZero() && !timeout.isNegative()) {
                builder.timeout(timeout);
            }
            headers.forEach(builder::header);
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(body));

            HttpResponse<InputStream> response;
            try {
                response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (HttpTimeoutException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }

            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                try {
                    response.body().close();
                } catch (IOException ignored) {}
                throw new HttpTimeoutException("response body read timed out before reading started");
            }

            InputStream in = response.body();
            AtomicBoolean timedOut = new AtomicBoolean(false);
            ScheduledFuture<?> watchdogTask = null;
            if (timeout != null) {
                watchdogTask = WATCHDOG.schedule(() -> {
                    timedOut.set(true);
                    try {
                        in.close();
                    } catch (IOException ignored) {}
                }, remainingNanos, TimeUnit.NANOSECONDS);
            }

            byte[] bytes;
            boolean truncated;
            try {
                bytes = in.readNBytes(maxBytes + 1);
                if (timedOut.get() || System.nanoTime() >= deadlineNanos) {
                    throw new HttpTimeoutException("response body read timed out during body consumption");
                }
                truncated = bytes.length > maxBytes;
                if (truncated) {
                    bytes = java.util.Arrays.copyOf(bytes, maxBytes);
                }
            } catch (IOException e) {
                if (timedOut.get() || System.nanoTime() >= deadlineNanos) {
                    throw new HttpTimeoutException("response body read timed out during body consumption: " + e.getMessage());
                }
                throw e;
            } finally {
                if (watchdogTask != null) {
                    watchdogTask.cancel(false);
                }
                try {
                    in.close();
                } catch (IOException ignored) {}
            }

            Map<String, String> flat = new java.util.LinkedHashMap<>();
            response.headers().map().forEach((k, v) -> { if (!v.isEmpty()) flat.put(k, v.get(0)); });
            return new RawResponse(response.statusCode(), flat, bytes, truncated);
        }

        private static final class PinnedTunnel implements AutoCloseable {
            private final ServerSocket server;
            private final String expectedHost;
            private final int expectedPort;
            private final List<InetAddress> pinnedAddresses;
            private final int connectTimeoutMs;
            private final List<Socket> activeSockets = new CopyOnWriteArrayList<>();
            private final AtomicBoolean closed = new AtomicBoolean(false);

            private PinnedTunnel(ServerSocket server, String expectedHost, int expectedPort,
                                 List<InetAddress> pinnedAddresses, Duration timeout) {
                this.server = server;
                this.expectedHost = expectedHost == null ? "" : expectedHost.toLowerCase(Locale.ROOT);
                this.expectedPort = expectedPort;
                this.pinnedAddresses = pinnedAddresses;
                long ms = timeout == null ? 10_000L : Math.max(500L, Math.min(timeout.toMillis(), 10_000L));
                this.connectTimeoutMs = (int) ms;
            }

            static PinnedTunnel open(String expectedHost, int expectedPort,
                                     List<InetAddress> pinnedAddresses, Duration timeout) throws IOException {
                ServerSocket ss = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
                PinnedTunnel tunnel = new PinnedTunnel(ss, expectedHost, expectedPort, pinnedAddresses, timeout);
                Thread.ofVirtual().name("safe-http-pinned-tunnel").start(tunnel::acceptLoop);
                return tunnel;
            }

            InetSocketAddress localAddress() {
                return new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort());
            }

            private void acceptLoop() {
                while (!closed.get()) {
                    try {
                        Socket clientSocket = server.accept();
                        activeSockets.add(clientSocket);
                        Thread.ofVirtual().name("safe-http-pinned-conn").start(() -> handleClient(clientSocket));
                    } catch (IOException ignored) {
                        if (closed.get()) return;
                    }
                }
            }

            private void handleClient(Socket clientSocket) {
                Socket upstream = null;
                try {
                    clientSocket.setTcpNoDelay(true);
                    InputStream clientIn = clientSocket.getInputStream();
                    OutputStream clientOut = clientSocket.getOutputStream();
                    String headerBlock = readHeaders(clientIn);
                    if (headerBlock == null) return;
                    int eol = headerBlock.indexOf("\r\n");
                    String requestLine = eol >= 0 ? headerBlock.substring(0, eol) : headerBlock;
                    String[] parts = requestLine.split(" ");
                    if (parts.length < 3) return;
                    String method = parts[0];
                    String target = parts[1];

                    if ("CONNECT".equalsIgnoreCase(method)) {
                        if (!matchesAuthority(target)) {
                            clientOut.write("HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                            clientOut.flush();
                            return;
                        }
                        upstream = connectPinned();
                        activeSockets.add(upstream);
                        clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                        clientOut.flush();
                        relay(clientIn, upstream.getOutputStream(), upstream.getInputStream(), clientOut);
                    } else {
                        URI reqUri = URI.create(target);
                        int reqPort = reqUri.getPort() != -1 ? reqUri.getPort()
                                : ("https".equalsIgnoreCase(reqUri.getScheme()) ? 443 : 80);
                        String reqHost = reqUri.getHost() == null ? "" : reqUri.getHost().toLowerCase(Locale.ROOT);
                        if (!expectedHost.equals(reqHost) || reqPort != expectedPort) {
                            clientOut.write("HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                            clientOut.flush();
                            return;
                        }
                        upstream = connectPinned();
                        activeSockets.add(upstream);
                        String path = (reqUri.getRawPath() == null || reqUri.getRawPath().isEmpty() ? "/" : reqUri.getRawPath())
                                + (reqUri.getRawQuery() != null ? "?" + reqUri.getRawQuery() : "");
                        String rewritten = rewriteHttpProxyHeaders(method, path, eol >= 0 ? headerBlock.substring(eol + 2) : "");
                        OutputStream upOut = upstream.getOutputStream();
                        upOut.write(rewritten.getBytes(StandardCharsets.ISO_8859_1));
                        upOut.flush();
                        relay(clientIn, upOut, upstream.getInputStream(), clientOut);
                    }
                } catch (IOException ignored) {
                } finally {
                    closeQuietly(upstream);
                    closeQuietly(clientSocket);
                }
            }

            private boolean matchesAuthority(String authority) {
                int colon = authority.lastIndexOf(':');
                if (colon <= 0) return false;
                String h = authority.substring(0, colon).toLowerCase(Locale.ROOT);
                if (h.startsWith("[") && h.endsWith("]")) {
                    h = h.substring(1, h.length() - 1);
                }
                int p;
                try {
                    p = Integer.parseInt(authority.substring(colon + 1));
                } catch (NumberFormatException e) {
                    return false;
                }
                return expectedHost.equals(h) && expectedPort == p;
            }

            private String rewriteHttpProxyHeaders(String method, String path, String restHeaders) {
                StringBuilder sb = new StringBuilder();
                sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
                for (String line : restHeaders.split("\r\n")) {
                    if (line.isEmpty()) continue;
                    String lower = line.toLowerCase(Locale.ROOT);
                    if (lower.startsWith("proxy-")) continue;
                    sb.append(line).append("\r\n");
                }
                sb.append("Connection: close\r\n\r\n");
                return sb.toString();
            }

            private Socket connectPinned() throws IOException {
                IOException last = null;
                for (InetAddress addr : pinnedAddresses) {
                    Socket s = new Socket();
                    try {
                        s.setTcpNoDelay(true);
                        s.connect(new InetSocketAddress(addr, expectedPort), connectTimeoutMs);
                        return s;
                    } catch (IOException e) {
                        closeQuietly(s);
                        last = e;
                    }
                }
                throw last == null ? new IOException("No pinned addresses") : last;
            }

            private static String readHeaders(InputStream in) throws IOException {
                ByteArrayOutputStream buf = new ByteArrayOutputStream(512);
                int prev3 = -1, prev2 = -1, prev1 = -1;
                while (buf.size() < 16_384) {
                    int b = in.read();
                    if (b < 0) return null;
                    buf.write(b);
                    if (prev3 == '\r' && prev2 == '\n' && prev1 == '\r' && b == '\n') {
                        return buf.toString(StandardCharsets.ISO_8859_1);
                    }
                    prev3 = prev2;
                    prev2 = prev1;
                    prev1 = b;
                }
                return null;
            }

            private static void relay(InputStream cIn, OutputStream uOut, InputStream uIn, OutputStream cOut) {
                Thread up = Thread.ofVirtual().start(() -> pipe(cIn, uOut));
                pipe(uIn, cOut);
                try {
                    up.join(200L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }

            private static void pipe(InputStream in, OutputStream out) {
                byte[] buf = new byte[8192];
                try {
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        out.write(buf, 0, n);
                        out.flush();
                    }
                } catch (IOException ignored) {
                }
            }

            private static void closeQuietly(Socket s) {
                if (s != null) {
                    try {
                        s.close();
                    } catch (IOException ignored) {}
                }
            }

            @Override
            public void close() {
                if (!closed.compareAndSet(false, true)) return;
                try {
                    server.close();
                } catch (IOException ignored) {}
                for (Socket s : activeSockets) {
                    closeQuietly(s);
                }
            }
        }
    }
}
