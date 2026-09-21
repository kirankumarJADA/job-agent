package com.personal.jobagent.system;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.ConnectionFuture;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.protocol.ProtocolVersion;
import io.lettuce.core.resource.DefaultClientResources;
import io.lettuce.core.resource.DnsResolvers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A/B experiment: plain Lettuce vs the diagnostic's instrumented Lettuce
 * against a REAL Redis (local Docker, localhost:6379), not mocks.
 *
 * <p>Purpose: determine whether the diagnostic's NettyCustomizer (bootstrap
 * wrapping + pipeline handler insertion) causes the production
 * IllegalStateException signature, or whether plain Lettuce fails
 * identically. Variants separate resolver effects from instrumentation
 * effects; failure controls cover unresolvable-host and connection-refused
 * targets.
 */
@EnabledIf(value = "com.personal.jobagent.system.RedisLettuceAbExperimentTest#redisAvailable",
        disabledReason = "local Docker Redis on localhost:6379 is not reachable")
class RedisLettuceAbExperimentTest {

    private static final int TIMEOUT_SECONDS = 10;

    @BeforeAll
    static void checkRedisAvailable() {
        assertTrue(redisAvailable(), "localhost:6379 must be reachable for the A/B experiment");
    }

    static boolean redisAvailable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 6379), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** One A/B run: full lifecycle record for a single client construction. */
    private record AbRun(String label,
                         String connectStatus,
                         String rootException,
                         String pingResult,
                         List<String> lifecycleEvents) {

        String render() {
            return String.format("%-30s connect=%-5s ping=%-24s root=%s%n    lifecycle=%s",
                    label, connectStatus, pingResult, rootException, lifecycleEvents);
        }
    }


    @Test
    void abMatrixAgainstRealRedis() throws Exception {
        List<AbRun> runs = new ArrayList<>();

        // A1: Plain Lettuce - default resources, default (Netty) resolver.
        runs.add(run("A1 plain", "127.0.0.1", 6379, false, false));
        // A2: Plain + JVM_DEFAULT resolver (resolver effect isolated).
        runs.add(run("A2 plain+jvm-resolver", "127.0.0.1", 6379, false, true));
        // B: Instrumented - byte-for-byte the diagnostic's construction.
        runs.add(run("B instrumented", "127.0.0.1", 6379, true, true));

        for (AbRun r : runs) {
            System.out.println(r.render());
        }

        // All variants must fully succeed against the real Redis: neither the
        // JVM resolver nor the lifecycle customizer may break connect or PING.
        for (AbRun r : runs) {
            assertEquals("OK", r.connectStatus(), r.label + " connect");
            assertTrue(r.pingResult().startsWith("OK"), r.label + " ping");
        }
        // Only the instrumented variant observes channelActive: the lifecycle
        // customizer is the observer. Plain variants have no observer by design.
        AbRun instrumented = runs.get(2);
        assertTrue(instrumented.lifecycleEvents().stream().anyMatch(e -> e.startsWith("channelActive")),
                "instrumented run must observe channelActive");
        assertTrue(instrumented.lifecycleEvents().stream().anyMatch(e -> e.startsWith("bootstrapPrepared")),
                "instrumented run must observe bootstrapPrepared");
        assertTrue(instrumented.lifecycleEvents().stream().anyMatch(e -> e.startsWith("channelInitialized")),
                "instrumented run must observe channelInitialized (implies channel creation)");
    }

    @Test
    void failureControlsOnUnreachableTargets() throws Exception {
        List<AbRun> runs = new ArrayList<>();
        // Control 1: guaranteed unresolvable host.
        runs.add(run("C1 plain unresolvable", "no-such-host.localhost.invalid", 6379, false, false));
        runs.add(run("C2 instrument unresolvable", "no-such-host.localhost.invalid", 6379, true, true));
        // Control 2: connection refused (resolvable address, closed port).
        runs.add(run("C3 plain refused", "127.0.0.1", 6390, false, false));
        runs.add(run("C4 instrument refused", "127.0.0.1", 6390, true, true));

        for (AbRun r : runs) {
            System.out.println(r.render());
        }

        for (AbRun r : runs) {
            assertEquals("FAIL", r.connectStatus(), r.label + " must fail");
        }
        // Plain vs instrumented failure shapes must agree on root category:
        // instrumentation must not change WHY a connection fails.
        assertEquals(categoryOf(runs.get(0)), categoryOf(runs.get(1)),
                "C1 vs C2 root categories must match");
        assertEquals(categoryOf(runs.get(2)), categoryOf(runs.get(3)),
                "C3 vs C4 root categories must match");
        for (AbRun r : runs) {
            assertTrue(!r.rootException().contains("IllegalStateException"),
                    r.label + " must not fail with the instrumentation artifact: " + r.rootException());
        }
    }

    private static String categoryOf(AbRun run) {
        String root = run.rootException();
        int colon = root.indexOf(':');
        return colon < 0 ? root : root.substring(0, colon);
    }


    /**
     * Executes one A/B variant end-to-end. {@code instrumented=true} selects
     * the diagnostic's exact construction via
     * {@link RedisConnectivityDiagnostics#probeClientResources}; otherwise
     * plain {@link DefaultClientResources} with an optional explicit JVM
     * resolver.
     */
    private AbRun run(String label, String host, int port, boolean instrumented, boolean jvmResolver)
            throws Exception {
        List<String> lifecycle = new ArrayList<>();
        long started = System.nanoTime();

        RedisURI uri = RedisURI.builder()
                .withHost(host)
                .withPort(port)
                .withTimeout(Duration.ofSeconds(5))
                .build();

        DefaultClientResources resources;
        if (instrumented) {
            resources = RedisConnectivityDiagnostics.probeClientResources(true, lifecycle, started);
        } else if (jvmResolver) {
            resources = DefaultClientResources.builder()
                    .dnsResolver(DnsResolvers.JVM_DEFAULT)
                    .build();
        } else {
            resources = DefaultClientResources.builder().build();
        }

        RedisClient client = RedisClient.create(resources, uri);
        client.setOptions(ClientOptions.builder().protocolVersion(ProtocolVersion.RESP2).build());

        String connectStatus;
        String rootException = "none";
        String pingResult = "SKIPPED";
        StatefulRedisConnection<String, String> connection = null;
        try {
            long connectStart = System.nanoTime();
            ConnectionFuture<StatefulRedisConnection<String, String>> future =
                    client.connectAsync(StringCodec.UTF8, uri);
            lifecycle.add("connectInvoked/" + ms(connectStart) + "ms");
            lifecycle.add("connectionFutureCreated/" + ms(connectStart) + "ms");
            future.whenComplete((value, failure) -> lifecycle.add(failure == null
                    ? "connectionFutureCompleted/" + ms(connectStart) + "ms"
                    : "connectionFutureFailed/" + ms(connectStart) + "ms"));

            connection = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            connectStatus = "OK";

            long pingStart = System.nanoTime();
            String pong = connection.sync().ping();
            pingResult = "OK[" + (pong == null ? "no-response" : pong) + "]/" + ms(pingStart) + "ms";
        } catch (Exception e) {
            connectStatus = "FAIL";
            rootException = safeRootChain(e);
        } finally {
            if (connection != null) {
                connection.close();
            }
            client.shutdown();
            resources.shutdown(0, 2, TimeUnit.SECONDS).get(5, TimeUnit.SECONDS);
        }

        return new AbRun(label, connectStatus, rootException, pingResult, lifecycle);
    }

    /** Renders the full exception chain safely (class simple names + sanitized messages). */
    private static String safeRootChain(Throwable failure) {
        StringBuilder chain = new StringBuilder();
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth < 8) {
            String message = current.getMessage() == null ? "" : current.getMessage();
            // Defensive scrub: never echo anything credential-shaped.
            message = message.replaceAll("(?i)(password|token|auth)[a-zA-Z0-9_-]*", "$1<redacted>");
            if (depth > 0) {
                chain.append(" <- ");
            }
            chain.append(current.getClass().getSimpleName());
            if (!message.isBlank()) {
                String truncated = message.length() > 140 ? message.substring(0, 140) + "..." : message;
                chain.append('[').append(truncated).append(']');
            }
            current = current.getCause();
            depth++;
        }
        return chain.toString();
    }

    private static long ms(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
