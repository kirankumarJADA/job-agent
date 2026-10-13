package com.personal.jobagent.discovery.platform;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Replays captured responses for discovery tests. Nothing here touches the
 * network: every host "resolves" to a public documentation-range-free address
 * unless a test registers a private one, and unmatched requests fail loudly.
 */
public final class FixtureHttp implements SafeHttpClient.Transport {

    public record Request(String method, URI uri, Map<String, String> headers, String body) {}

    private record Rule(String method, Predicate<URI> match, Deque<SafeHttpClient.RawResponse> responses,
                        SafeHttpClient.RawResponse sticky) {}

    private final List<Rule> rules = new ArrayList<>();
    public final List<Request> requests = new ArrayList<>();
    private final Map<String, InetAddress[]> dns = new LinkedHashMap<>();

    public static String fixture(String name) {
        try (InputStream in = FixtureHttp.class.getResourceAsStream("/discovery/platforms/" + name)) {
            if (in == null) throw new IllegalArgumentException("missing fixture " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static SafeHttpClient.RawResponse ok(String contentType, String body) {
        return new SafeHttpClient.RawResponse(200, Map.of("Content-Type", contentType),
                body.getBytes(StandardCharsets.UTF_8), false);
    }

    public static SafeHttpClient.RawResponse json(String body) {
        return ok("application/json; charset=utf-8", body);
    }

    public static SafeHttpClient.RawResponse html(String body) {
        return ok("text/html; charset=utf-8", body);
    }

    public static SafeHttpClient.RawResponse status(int status) {
        return new SafeHttpClient.RawResponse(status, Map.of("Content-Type", "text/plain"), new byte[0], false);
    }

    public static SafeHttpClient.RawResponse redirect(String location) {
        return new SafeHttpClient.RawResponse(302, Map.of("Location", location), new byte[0], false);
    }

    /** Always answer requests matching {@code urlPart} with {@code response}. */
    public FixtureHttp on(String method, String urlPart, SafeHttpClient.RawResponse response) {
        rules.add(new Rule(method, uri -> uri.toString().contains(urlPart), new ArrayDeque<>(), response));
        return this;
    }

    public FixtureHttp on(String method, Predicate<URI> match, SafeHttpClient.RawResponse response) {
        rules.add(new Rule(method, match, new ArrayDeque<>(), response));
        return this;
    }

    /** Answer successive matching requests with these responses in order (last one repeats). */
    public FixtureHttp sequence(String method, String urlPart, SafeHttpClient.RawResponse... responses) {
        Deque<SafeHttpClient.RawResponse> queue = new ArrayDeque<>(List.of(responses));
        rules.add(new Rule(method, uri -> uri.toString().contains(urlPart), queue, responses[responses.length - 1]));
        return this;
    }

    /** Make a host resolve to the given address (e.g. a private one) for SSRF tests. */
    public FixtureHttp resolve(String host, String ip) {
        try {
            dns.put(host, new InetAddress[]{InetAddress.getByName(ip)});
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return this;
    }

    public AddressGuard guard() {
        return new AddressGuard(host -> {
            InetAddress[] mapped = dns.get(host);
            if (mapped != null) return mapped;
            return new InetAddress[]{InetAddress.getByAddress(host, new byte[]{(byte) 151, 101, 1, 69})};
        });
    }

    public SafeHttpClient client() {
        return client(SafeHttpClient.Settings.defaults().withMinInterval(0));
    }

    public SafeHttpClient client(SafeHttpClient.Settings settings) {
        return new SafeHttpClient(this, guard(), settings, ms -> { });
    }

    public BoardAdapter.CrawlContext context() {
        return new BoardAdapter.CrawlContext(client(), 50, java.time.Instant.parse("2026-10-10T12:00:00Z"), null);
    }

    public BoardAdapter.CrawlContext context(int maxPages) {
        return new BoardAdapter.CrawlContext(client(), maxPages, java.time.Instant.parse("2026-10-10T12:00:00Z"), null);
    }

    @Override
    public SafeHttpClient.RawResponse exchange(String method, URI uri, Map<String, String> headers, byte[] body,
                                               Duration timeout, int maxBytes) throws IOException {
        requests.add(new Request(method, uri, headers, body == null ? null : new String(body, StandardCharsets.UTF_8)));
        for (Rule rule : rules) {
            if (!rule.method().equals(method) || !rule.match().test(uri)) continue;
            SafeHttpClient.RawResponse response = rule.responses().isEmpty() ? rule.sticky() : rule.responses().poll();
            if (response == TIMEOUT) throw new HttpTimeoutException("fixture timeout");
            if (response.body().length > maxBytes) {
                return new SafeHttpClient.RawResponse(response.status(), response.headers(),
                        java.util.Arrays.copyOf(response.body(), maxBytes), true);
            }
            return response;
        }
        throw new IOException("no fixture for " + method + " " + uri);
    }

    /** Sentinel response that makes the transport throw a timeout. */
    public static final SafeHttpClient.RawResponse TIMEOUT = new SafeHttpClient.RawResponse(0, Map.of(), new byte[0], false);
}
