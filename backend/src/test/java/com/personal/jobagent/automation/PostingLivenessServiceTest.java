package com.personal.jobagent.automation;

import com.personal.jobagent.discovery.platform.AddressGuard;
import com.personal.jobagent.discovery.platform.SafeHttpClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PostingLivenessServiceTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger statusCode = new AtomicInteger(200);
    private final AtomicReference<String> body = new AtomicReference<>("<html><body><h1>Backend Engineer</h1></body></html>");
    private final AtomicReference<String> locationHeader = new AtomicReference<>(null);
    private final AtomicInteger sleepMs = new AtomicInteger(0);

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            int delay = sleepMs.get();
            if (delay > 0) {
                try { Thread.sleep(delay); } catch (InterruptedException ignored) {}
            }
            if (locationHeader.get() != null) {
                exchange.getResponseHeaders().set("Location", locationHeader.get());
            }
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private PostingLivenessService localTestService(JdbcTemplate db, boolean enabled) {
        SafeHttpClient client = SafeHttpClient.forGuard(
                AddressGuard.allowingLoopbackForTesting(),
                new SafeHttpClient.Settings(10, 0, Duration.ofMillis(400), 256 * 1024, 1, 0, 0));
        return new PostingLivenessService(db, null, enabled, client);
    }

    @Test
    void distinguishesLiveClosedInconclusiveAndSsrfTargets() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        PostingLivenessService svc = localTestService(db, true);

        // 1. HTTP 200 live HTML -> LIVE
        assertThat(svc.probeUrl(baseUrl + "/jobs/1").status()).isEqualTo(PostingLivenessService.Status.LIVE);

        // 2. HTTP 404 / 410 -> CLOSED
        statusCode.set(404);
        var closed404 = svc.probeUrl(baseUrl + "/jobs/404");
        assertThat(closed404.status()).isEqualTo(PostingLivenessService.Status.CLOSED);
        assertThat(closed404.code()).isEqualTo("JOB_POSTING_CLOSED");

        statusCode.set(410);
        assertThat(svc.probeUrl(baseUrl + "/jobs/410").status()).isEqualTo(PostingLivenessService.Status.CLOSED);

        // 3. HTTP 200 explicit closed text -> CLOSED
        statusCode.set(200);
        body.set("<html><body><p>Sorry, this job is no longer accepting applications.</p></body></html>");
        assertThat(svc.probeUrl(baseUrl + "/jobs/banner-closed").status()).isEqualTo(PostingLivenessService.Status.CLOSED);

        // 4. Login wall -> INCONCLUSIVE (never closed!)
        body.set("<html><body><form action=\"/login\"><input type=\"password\" name=\"pw\"/></form></body></html>");
        assertThat(svc.probeUrl(baseUrl + "/jobs/login").status()).isEqualTo(PostingLivenessService.Status.INCONCLUSIVE);

        // 5. 401 / 403 / 429 / 503 -> INCONCLUSIVE
        body.set("<html><body>Blocked</body></html>");
        for (int code : List.of(401, 403, 429, 503)) {
            statusCode.set(code);
            var inc = svc.probeUrl(baseUrl + "/jobs/" + code);
            assertThat(inc.status()).isEqualTo(PostingLivenessService.Status.INCONCLUSIVE);
            assertThat(inc.blocksExecution()).isFalse();
        }

        // 6. Too many redirects -> INCONCLUSIVE
        statusCode.set(302);
        locationHeader.set(baseUrl + "/jobs/loop");
        assertThat(svc.probeUrl(baseUrl + "/jobs/loop").status()).isEqualTo(PostingLivenessService.Status.INCONCLUSIVE);
        locationHeader.set(null);

        // 7. Timeout -> INCONCLUSIVE
        statusCode.set(200);
        sleepMs.set(900);
        assertThat(svc.probeUrl(baseUrl + "/jobs/slow").status()).isEqualTo(PostingLivenessService.Status.INCONCLUSIVE);
        sleepMs.set(0);

        // 8. SSRF metadata & RFC-1918 blocked even when local test loopback allowed!
        assertThat(svc.probeUrl("https://169.254.169.254/latest/meta-data").status())
                .isEqualTo(PostingLivenessService.Status.UNSAFE_TARGET);
        assertThat(svc.probeUrl("https://10.0.0.1/internal").status())
                .isEqualTo(PostingLivenessService.Status.UNSAFE_TARGET);

        // 9. Strict production AddressGuard blocks loopback too
        PostingLivenessService prodSvc = new PostingLivenessService(db, null, true, false);
        assertThat(prodSvc.probeUrl(baseUrl + "/jobs/1").status()).isEqualTo(PostingLivenessService.Status.UNSAFE_TARGET);
    }

    @Test
    void evaluateForPlanAlwaysBlocksDeletedDbJobsEvenWhenHttpProbeDisabled() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        UUID planId = UUID.randomUUID();
        when(db.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
                "job_id", UUID.randomUUID(),
                "job_status", "ARCHIVED",
                "check_url", baseUrl + "/jobs/1")));

        PostingLivenessService disabledHttp = localTestService(db, false);
        var res = disabledHttp.evaluateForPlan(planId);
        assertThat(res.status()).isEqualTo(PostingLivenessService.Status.CLOSED);
        assertThat(res.blocksExecution()).isTrue();
    }
}
