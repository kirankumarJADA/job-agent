package com.personal.jobagent.discovery.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import com.sun.net.httpserver.HttpServer;

import static com.personal.jobagent.discovery.platform.FixtureHttp.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SSRF guard, redirects, limits, retries and budget of the one discovery HTTP client. */
class SafeHttpClientTest {

    private static SafeHttpClient.FetchException fails(Runnable call) {
        try {
            call.run();
        } catch (SafeHttpClient.FetchException e) {
            return e;
        }
        throw new AssertionError("expected a FetchException");
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "10.0.0.5", "172.16.3.4", "192.168.1.1", "169.254.169.254", "100.64.0.1",
            "0.0.0.0", "::1", "fd00::1", "fe80::1", "::ffff:127.0.0.1", "224.0.0.1"})
    void hostsResolvingToNonPublicAddressesAreRefusedBeforeAnyRequest(String ip) {
        FixtureHttp f = new FixtureHttp().resolve("jobs.example.com", ip).on("GET", "example.com", json("{}"));
        var e = fails(() -> f.client().get(URI.create("https://jobs.example.com/x"), SafeHttpClient.Accept.JSON));
        assertThat(e.category()).isEqualTo(FailureCategory.UNSAFE_ADDRESS);
        assertThat(f.requests).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://jobs.example.com/x", "ftp://jobs.example.com/x", "file:///etc/passwd",
            "https://user:pw@jobs.example.com/x", "https://jobs.example.com:8443/x", "https://127.0.0.1/x",
            "https://[::1]/x", "https://localhost/x", "https://metadata.google.internal/x", "https://intranet/x"})
    void unsafeUrlShapesAreRefused(String url) {
        FixtureHttp f = new FixtureHttp().on("GET", "x", json("{}"));
        var e = fails(() -> f.client().get(URI.create(url), SafeHttpClient.Accept.JSON));
        assertThat(e.category()).isEqualTo(FailureCategory.UNSAFE_ADDRESS);
        assertThat(f.requests).isEmpty();
    }

    @Test
    void everyRedirectHopIsRevalidated() {
        FixtureHttp f = new FixtureHttp().resolve("internal.example.com", "10.1.2.3")
                .on("GET", "public.example.com", redirect("https://internal.example.com/secret"))
                .on("GET", "internal.example.com", json("{\"leak\":true}"));
        var e = fails(() -> f.client().get(URI.create("https://public.example.com/start"), SafeHttpClient.Accept.JSON));
        assertThat(e.category()).isEqualTo(FailureCategory.UNSAFE_ADDRESS);
        assertThat(f.requests).hasSize(1);
    }

    @Test
    void redirectsToHttpAreRefusedAndChainsAreBounded() {
        FixtureHttp toHttp = new FixtureHttp().on("GET", "a.example.com", redirect("http://a.example.com/plain"));
        assertThat(fails(() -> toHttp.client().get(URI.create("https://a.example.com/"), SafeHttpClient.Accept.JSON)).category())
                .isEqualTo(FailureCategory.UNSAFE_ADDRESS);
        FixtureHttp loop = new FixtureHttp().on("GET", "b.example.com", redirect("https://b.example.com/again"));
        assertThat(fails(() -> loop.client().get(URI.create("https://b.example.com/"), SafeHttpClient.Accept.JSON)).category())
                .isEqualTo(FailureCategory.RESPONSE_REJECTED);
        assertThat(loop.requests).hasSize(4);
    }

    @Test
    void oversizedResponsesAndUnexpectedContentTypesAreRejected() {
        FixtureHttp big = new FixtureHttp().on("GET", "x", json("x".repeat(5000)));
        assertThat(fails(() -> big.client(SafeHttpClient.Settings.defaults().withMaxBytes(1000).withMinInterval(0))
                .get(URI.create("https://big.example.com/x"), SafeHttpClient.Accept.JSON)).category())
                .isEqualTo(FailureCategory.RESPONSE_REJECTED);
        FixtureHttp pdf = new FixtureHttp().on("GET", "x", ok("application/pdf", "%PDF"));
        assertThat(fails(() -> pdf.client().get(URI.create("https://pdf.example.com/x"), SafeHttpClient.Accept.HTML)).category())
                .isEqualTo(FailureCategory.RESPONSE_REJECTED);
    }

    @Test
    void transientFailuresAreRetriedWithBoundedBackoffHonouringRetryAfter() {
        List<Long> sleeps = new ArrayList<>();
        FixtureHttp f = new FixtureHttp().sequence("GET", "x",
                new SafeHttpClient.RawResponse(429, Map.of("Retry-After", "3"), new byte[0], false),
                status(503), json("{\"ok\":true}"));
        SafeHttpClient client = new SafeHttpClient(f, f.guard(), SafeHttpClient.Settings.defaults().withMinInterval(0), sleeps::add);
        assertThat(client.get(URI.create("https://r.example.com/x"), SafeHttpClient.Accept.JSON)).contains("ok");
        assertThat(f.requests).hasSize(3);
        assertThat(sleeps.get(0)).isGreaterThanOrEqualTo(3000L).isLessThanOrEqualTo(10_000L);
        assertThat(sleeps.get(1)).isGreaterThanOrEqualTo(1000L);
    }

    @Test
    void retriesStopAfterMaxAttempts() {
        FixtureHttp f = new FixtureHttp().on("GET", "x", status(503));
        assertThat(fails(() -> f.client().get(URI.create("https://down.example.com/x"), SafeHttpClient.Accept.JSON)).category())
                .isEqualTo(FailureCategory.UPSTREAM_ERROR);
        assertThat(f.requests).hasSize(3);
    }

    @Test
    void clientErrorsAreNotRetried() {
        FixtureHttp f = new FixtureHttp().on("GET", "x", status(403));
        assertThat(fails(() -> f.client().get(URI.create("https://deny.example.com/x"), SafeHttpClient.Accept.JSON)).category())
                .isEqualTo(FailureCategory.ACCESS_DENIED);
        assertThat(f.requests).hasSize(1);
    }

    @Test
    void theRequestBudgetIsAHardLimit() {
        FixtureHttp f = new FixtureHttp().on("GET", "x", json("{}"));
        SafeHttpClient client = f.client(SafeHttpClient.Settings.defaults().withMaxRequests(2).withMinInterval(0));
        client.get(URI.create("https://b.example.com/x"), SafeHttpClient.Accept.JSON);
        client.get(URI.create("https://b.example.com/x"), SafeHttpClient.Accept.JSON);
        assertThat(fails(() -> client.get(URI.create("https://b.example.com/x"), SafeHttpClient.Accept.JSON)).category())
                .isEqualTo(FailureCategory.BUDGET_EXHAUSTED);
        assertThat(f.requests).hasSize(2);
    }

    @Test
    void requestsArePacedByTheMinimumInterval() {
        List<Long> sleeps = new ArrayList<>();
        FixtureHttp f = new FixtureHttp().on("GET", "x", json("{}"));
        SafeHttpClient client = new SafeHttpClient(f, f.guard(), SafeHttpClient.Settings.defaults().withMinInterval(1500), sleeps::add);
        client.get(URI.create("https://p.example.com/x"), SafeHttpClient.Accept.JSON);
        client.get(URI.create("https://p.example.com/x"), SafeHttpClient.Accept.JSON);
        assertThat(sleeps).hasSize(1);
        assertThat(sleeps.get(0)).isBetween(1000L, 1500L);
    }

    @Test
    void timeoutsAreCategorisedAndTheUserAgentIsSent() {
        FixtureHttp f = new FixtureHttp().on("GET", "x", TIMEOUT);
        assertThat(fails(() -> f.client().get(URI.create("https://slow.example.com/x"), SafeHttpClient.Accept.JSON)).category())
                .isEqualTo(FailureCategory.TIMEOUT);
        assertThat(f.requests.get(0).headers()).containsEntry("User-Agent", SafeHttpClient.USER_AGENT);
    }

    @Test
    void fetchErrorsNeverEchoBodiesOrHeaders() {
        FixtureHttp f = new FixtureHttp().on("GET", "x",
                new SafeHttpClient.RawResponse(500, Map.of("Set-Cookie", "session=secret"), "token=abc123".getBytes(), false));
        var e = fails(() -> f.client().get(URI.create("https://e.example.com/x"), SafeHttpClient.Accept.JSON));
        assertThat(e.getMessage()).doesNotContain("secret", "abc123");
    }

    @Test
    void addressGuardClassifiesAddresses() throws Exception {
        assertThat(AddressGuard.isPublic(InetAddress.getByName("151.101.1.69"))).isTrue();
        assertThat(AddressGuard.isPublic(InetAddress.getByName("2606:4700::6810:84e5"))).isTrue();
        assertThat(AddressGuard.isPublic(InetAddress.getByName("169.254.169.254"))).isFalse();
        assertThat(AddressGuard.isPublic(InetAddress.getByName("::ffff:10.0.0.1"))).isFalse();
        assertThatThrownBy(() -> new AddressGuard(h -> { throw new java.net.UnknownHostException(h); })
                .check(URI.create("https://nowhere.example.com/"))).isInstanceOf(AddressGuard.UnsafeAddressException.class);
    }

    @Test
    void settingsDefaultsAreBounded() {
        SafeHttpClient.Settings s = SafeHttpClient.Settings.defaults();
        assertThat(s.timeout()).isLessThanOrEqualTo(Duration.ofSeconds(30));
        assertThat(s.maxAttempts()).isBetween(1, 5);
        assertThat(s.maxRetryAfterMs()).isLessThanOrEqualTo(30_000L);
    }

    @Test
    void remainingDeadlineClampsTransportTimeout() {
        FixtureHttp f = new FixtureHttp().on("GET", "x", json("{}"));
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(50);
        SafeHttpClient client = f.client(SafeHttpClient.Settings.defaults().withTimeout(Duration.ofSeconds(20)).withMinInterval(0))
                .withDeadline(deadline);

        client.get(URI.create("https://deadline.example.com/x"), SafeHttpClient.Accept.JSON);

        assertThat(f.capturedTimeouts).hasSize(1);
        assertThat(f.capturedTimeouts.get(0)).isLessThanOrEqualTo(Duration.ofMillis(55));
    }

    @Test
    void zeroRemainingTimePreventsTransportCall() {
        FixtureHttp f = new FixtureHttp().on("GET", "x", json("{}"));
        long pastDeadline = System.nanoTime() - java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(10);
        SafeHttpClient client = f.client().withDeadline(pastDeadline);

        var e = fails(() -> client.get(URI.create("https://expired.example.com/x"), SafeHttpClient.Accept.JSON));

        assertThat(e.category()).isEqualTo(FailureCategory.TIMEOUT);
        assertThat(f.requests).isEmpty();
        assertThat(f.capturedTimeouts).isEmpty();
    }

    @Test
    void timeSpentOnFirstRequestReducesTimeoutForNextRequest() {
        FixtureHttp f = new FixtureHttp().on("GET", "x", json("{}"));
        long start = System.nanoTime();
        long deadline = start + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(200);
        SafeHttpClient client = f.client(SafeHttpClient.Settings.defaults().withTimeout(Duration.ofSeconds(10)).withMinInterval(0))
                .withDeadline(deadline);

        client.get(URI.create("https://multi.example.com/x"), SafeHttpClient.Accept.JSON);
        try { Thread.sleep(30); } catch (InterruptedException ignored) {}
        client.get(URI.create("https://multi.example.com/x"), SafeHttpClient.Accept.JSON);

        assertThat(f.capturedTimeouts).hasSize(2);
        assertThat(f.capturedTimeouts.get(1)).isLessThan(f.capturedTimeouts.get(0));
    }

    @Test
    void retryAbortsImmediatelyWhenDeadlineExpires() {
        FixtureHttp f = new FixtureHttp().on("GET", "x", status(503));
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(30);
        SafeHttpClient client = new SafeHttpClient(f, f.guard(),
                new SafeHttpClient.Settings(100, 0, Duration.ofSeconds(5), 1024 * 1024, 5, 200, 5000),
                ms -> { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} },
                deadline);

        var e = fails(() -> client.get(URI.create("https://retry.example.com/x"), SafeHttpClient.Accept.JSON));

        assertThat(e.category()).isEqualTo(FailureCategory.TIMEOUT);
        // Did not burn all 5 attempts because deadline terminated retries
        assertThat(f.requests.size()).isLessThan(5);
    }

    @Test
    void redirectChainRespectsAbsoluteDeadline() {
        FixtureHttp f = new FixtureHttp()
                .on("GET", "step1", redirect("https://r.example.com/step2"))
                .on("GET", "step2", redirect("https://r.example.com/step3"))
                .on("GET", "step3", json("{}"));
        long pastDeadline = System.nanoTime() - 1000L;
        SafeHttpClient client = f.client().withDeadline(pastDeadline);

        var e = fails(() -> client.get(URI.create("https://r.example.com/step1"), SafeHttpClient.Accept.JSON));
        assertThat(e.category()).isEqualTo(FailureCategory.TIMEOUT);
        assertThat(f.requests).isEmpty();
    }

    @Test
    void slowHttpServerResponseBodyEnforcesStrictDeadlineInJdkTransport() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow-body", exchange -> {
            exchange.sendResponseHeaders(200, 0); // chunked encoding
            OutputStream os = exchange.getResponseBody();
            os.write("initial-header-chunk\n".getBytes());
            os.flush();
            try {
                Thread.sleep(3000); // stall for 3 seconds
            } catch (InterruptedException ignored) {}
            try {
                os.write("final-chunk\n".getBytes());
                os.close();
            } catch (Exception ignored) {}
        });
        server.start();
        int port = server.getAddress().getPort();
        try {
            SafeHttpClient.JdkTransport transport = new SafeHttpClient.JdkTransport();
            long t0 = System.nanoTime();
            assertThatThrownBy(() -> transport.exchange(
                    "GET",
                    URI.create("http://127.0.0.1:" + port + "/slow-body"),
                    Map.of(),
                    null,
                    Duration.ofMillis(250),
                    1024 * 1024
            )).isInstanceOf(HttpTimeoutException.class);

            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            // Must abort strictly near 250ms and well before the 3,000ms server sleep completes
            assertThat(elapsedMs).isLessThan(1500L);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void slowHttpServerResponseBodyEnforcesOverallDeadlineThroughSafeHttpClient() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow-body", exchange -> {
            byte[] preamble = "{\"key\": \"".getBytes();
            exchange.sendResponseHeaders(200, 0);
            OutputStream os = exchange.getResponseBody();
            os.write(preamble);
            os.flush();
            try {
                Thread.sleep(3000); // stall for 3 seconds
            } catch (InterruptedException ignored) {}
            try {
                os.write("value\"}".getBytes());
                os.close();
            } catch (Exception ignored) {}
        });
        server.start();
        int port = server.getAddress().getPort();
        try {
            AddressGuard permissiveGuard = new AddressGuard() {
                @Override
                public void check(URI uri) {
                    // allow local test server
                }
            };
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300);
            SafeHttpClient client = new SafeHttpClient(
                    new SafeHttpClient.JdkTransport(),
                    permissiveGuard,
                    SafeHttpClient.Settings.defaults().withTimeout(Duration.ofSeconds(5)).withMinInterval(0),
                    Thread::sleep,
                    deadline
            );

            long t0 = System.nanoTime();
            var e = fails(() -> client.get(URI.create("http://127.0.0.1:" + port + "/slow-body"), SafeHttpClient.Accept.JSON));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

            assertThat(e.category()).isEqualTo(FailureCategory.TIMEOUT);
            // Proves strict deadline terminates consumption far before the 3000ms delay
            assertThat(elapsedMs).isLessThan(1500L);
        } finally {
            server.stop(0);
        }
    }
}
