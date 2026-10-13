package com.personal.jobagent.discovery.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
}
