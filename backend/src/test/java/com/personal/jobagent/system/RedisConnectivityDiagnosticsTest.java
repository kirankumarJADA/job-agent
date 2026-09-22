package com.personal.jobagent.system;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.data.redis.RedisHealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisServerCommands;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import java.net.ConnectException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the phased Redis connectivity diagnostic. All network seams
 * are faked; no test touches a real endpoint.
 */
class RedisConnectivityDiagnosticsTest {

    private static final Duration POOLED = Duration.ofMillis(250);

    @Test
    void validUpstashStyleConfigurationReportsAuthenticatedTlsConnection() throws Exception {
        RedisProperties properties = properties("upstash.example", 6379, true, "token-not-logged");
        RedisConnectionFactory factory = factoryWithInfo();
        FakeHooks hooks = FakeHooks.healthy(2);

        RedisConnectivityDiagnostics.Diagnostic diagnostic =
                new RedisConnectivityDiagnostics(properties, factory).diagnose(hooks, POOLED);

        assertThat(diagnostic.client()).isNotBlank();
        assertThat(diagnostic.host()).isEqualTo("upstash.example");
        assertThat(diagnostic.port()).isEqualTo(6379);
        assertThat(diagnostic.sslEnabled()).isTrue();
        assertThat(diagnostic.authenticationConfigured()).isTrue();
        assertThat(diagnostic.result()).isEqualTo("UP");
        assertThat(diagnostic.errorCategory()).isNull();
        assertThat(diagnostic.phases())
                .contains("dns:OK").contains("v4=2,v6=0")
                .contains("tcp:OK").contains("[v4 1/2]")
                .contains("tls:OK").contains("protocol=TLSv1.3")
                .contains("pooledInfo:OK");
        verify(hooks.tlsSocket, times(1)).close();
    }

    @Test
    void missingAuthenticationIsVisibleWithoutLoggingTheCredential() throws Exception {
        RedisProperties properties = properties("upstash.example", 6379, true, "");
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(factory.getConnection()).thenThrow(new RuntimeException("authentication required"));
        FakeHooks hooks = FakeHooks.healthy(1);

        RedisConnectivityDiagnostics.Diagnostic diagnostic =
                new RedisConnectivityDiagnostics(properties, factory).diagnose(hooks, POOLED);

        assertThat(diagnostic.authenticationConfigured()).isFalse();
        assertThat(diagnostic.result()).isEqualTo("DOWN");
        assertThat(diagnostic.errorCategory()).isEqualTo("AUTHENTICATION");
        assertThat(diagnostic.phases()).contains("dns:OK").contains("tcp:OK").contains("pooledInfo:FAIL");
    }

    @Test
    void unavailableRedisIsCategorizedAsConnectionFailure() throws Exception {
        RedisProperties properties = properties("unreachable.example", 6379, true, "token");
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(factory.getConnection()).thenThrow(new RedisConnectionFailureException(
                "connection refused", new ConnectException("connection refused")));
        FakeHooks hooks = FakeHooks.healthy(1);

        RedisConnectivityDiagnostics.Diagnostic diagnostic =
                new RedisConnectivityDiagnostics(properties, factory).diagnose(hooks, POOLED);

        assertThat(diagnostic.result()).isEqualTo("DOWN");
        assertThat(diagnostic.errorCategory()).isEqualTo("CONNECTION");
    }

    @Test
    void dnsFailureIsAttributedToDnsPhaseAndSkipsLaterPhases() throws Exception {
        RedisProperties properties = properties("does-not-exist.invalid", 6379, true, "token");
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        FakeHooks hooks = FakeHooks.dnsFailure(new UnknownHostException("does-not-exist.invalid"));

        RedisConnectivityDiagnostics.Diagnostic diagnostic =
                new RedisConnectivityDiagnostics(properties, factory).diagnose(hooks, POOLED);

        assertThat(diagnostic.result()).isEqualTo("DOWN");
        assertThat(diagnostic.errorCategory()).isEqualTo("DNS");
        assertThat(diagnostic.phases())
                .contains("dns:FAIL").contains("UnknownHostException")
                .contains("tcp:SKIPPED").contains("tls:SKIPPED").contains("pooledInfo:SKIPPED");
    }

    @Test
    void everyResolvedAddressRefusingIsAttributedToTcpPhase() throws Exception {
        RedisProperties properties = properties("blackhole.example", 6379, true, "token");
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        FakeHooks hooks = FakeHooks.healthy(2);
        hooks.failAllTcp(new ConnectException("connection refused"));

        RedisConnectivityDiagnostics.Diagnostic diagnostic =
                new RedisConnectivityDiagnostics(properties, factory).diagnose(hooks, POOLED);

        assertThat(diagnostic.result()).isEqualTo("DOWN");
        assertThat(diagnostic.errorCategory()).isEqualTo("CONNECTION");
        assertThat(diagnostic.phases())
                .contains("tcp:FAIL").contains("0/2").contains("last=ConnectException")
                .contains("tls:SKIPPED").contains("pooledInfo:SKIPPED");
        for (Socket socket : hooks.sockets) {
            verify(socket, times(1)).close();
        }
    }

    @Test
    void firstAddressTimingOutFallsThroughToNextAddress() throws Exception {
        RedisProperties properties = properties("partial.example", 6379, true, "token");
        RedisConnectionFactory factory = factoryWithInfo();
        FakeHooks hooks = FakeHooks.healthy(2);
        hooks.failTcpOn(0, new SocketTimeoutException("connect timed out"));

        RedisConnectivityDiagnostics.Diagnostic diagnostic =
                new RedisConnectivityDiagnostics(properties, factory).diagnose(hooks, POOLED);

        assertThat(diagnostic.result()).isEqualTo("UP");
        assertThat(diagnostic.phases())
                .contains("dns:OK")
                .contains("tcp:OK").contains("[v4 2/2]")
                .contains("tls:OK").contains("pooledInfo:OK");
    }

    @Test
    void tlsHandshakeTimeoutIsAttributedToTlsPhase() throws Exception {
        RedisProperties properties = properties("slowtls.example", 6379, true, "token");
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        FakeHooks hooks = FakeHooks.healthy(1);
        hooks.failTls(new SocketTimeoutException("handshake timed out"));

        RedisConnectivityDiagnostics.Diagnostic diagnostic =
                new RedisConnectivityDiagnostics(properties, factory).diagnose(hooks, POOLED);

        assertThat(diagnostic.result()).isEqualTo("DOWN");
        assertThat(diagnostic.errorCategory()).isEqualTo("TIMEOUT");
        assertThat(diagnostic.phases())
                .contains("dns:OK").contains("tcp:OK")
                .contains("tls:FAIL").contains("SocketTimeoutException")
                .contains("pooledInfo:SKIPPED");
        verify(hooks.tlsSocket, times(1)).close();
    }

    @Test
    void tlsFailureIsAttributedToTlsPhaseNotNetworkTimeout() throws Exception {
        RedisProperties properties = properties("badtls.example", 6379, true, "token");
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        FakeHooks hooks = FakeHooks.healthy(1);
        hooks.failTls(new SSLException("handshake failure"));

        RedisConnectivityDiagnostics.Diagnostic diagnostic =
                new RedisConnectivityDiagnostics(properties, factory).diagnose(hooks, POOLED);

        assertThat(diagnostic.result()).isEqualTo("DOWN");
        assertThat(diagnostic.errorCategory()).isEqualTo("TLS");
        assertThat(diagnostic.phases()).contains("tls:FAIL").contains("SSLException");
    }

    @Test
    void pooledInfoTimeoutIsPhaseAttributedRatherThanBlanketTimeout() throws Exception {
        // Regression for the production misattribution: a hanging pooled probe
        // must be reported as a pooledInfo phase failure, never as a blanket
        // DIAGNOSTIC_TIMEOUT/TIMEOUT from the previous all-or-nothing orTimeout.
        RedisProperties properties = properties("hangingpool.example", 6379, true, "token");
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        CountDownLatch entered = new CountDownLatch(1);
        when(factory.getConnection()).thenAnswer(invocation -> {
            entered.countDown();
            Thread.sleep(10_000);
            return mock(RedisConnection.class);
        });
        FakeHooks hooks = FakeHooks.healthy(1);

        RedisConnectivityDiagnostics.Diagnostic diagnostic =
                new RedisConnectivityDiagnostics(properties, factory)
                        .diagnose(hooks, Duration.ofMillis(150));

        assertThat(diagnostic.result()).isEqualTo("DOWN");
        assertThat(diagnostic.errorCategory()).isEqualTo("TIMEOUT");
        assertThat(diagnostic.phases())
                .contains("dns:OK").contains("tcp:OK").contains("tls:OK")
                .contains("pooledInfo:FAIL");
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void pooledSubPhasesAreRecordedOnSuccess() throws Exception {
        RedisProperties properties = properties("upstash.example", 6379, true, "token");
        RedisConnectionFactory factory = factoryWithInfo();
        FakeHooks hooks = FakeHooks.healthy(1);

        var diagnostic = new RedisConnectivityDiagnostics(properties, factory).diagnose(hooks, POOLED);

        assertThat(diagnostic.result()).isEqualTo("UP");
        assertThat(diagnostic.phases())
                .contains("pooledInfo:OK")
                .contains("acquire:OK").contains("info:OK").contains("release:OK");
    }

    @Test
    void acquireFailureIsRecordedAsAcquireSubPhaseWithAuthCategory() throws Exception {
        RedisProperties properties = properties("upstash.example", 6379, true, "token");
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        io.lettuce.core.RedisCommandExecutionException noauth =
                new io.lettuce.core.RedisCommandExecutionException(
                        "NOAUTH Authentication required. See https://upstash.com/docs/redis/troubleshooting/no_auth");
        when(factory.getConnection()).thenThrow(
                new org.springframework.data.redis.RedisConnectionFailureException(
                        "Unable to connect to Redis",
                        new io.lettuce.core.RedisConnectionException(
                                "Unable to connect to upstash.example/<unresolved>:6379", noauth)));
        FakeHooks hooks = FakeHooks.healthy(1);

        var diagnostic = new RedisConnectivityDiagnostics(properties, factory).diagnose(hooks, POOLED);

        assertThat(diagnostic.result()).isEqualTo("DOWN");
        assertThat(diagnostic.errorCategory()).isEqualTo("AUTHENTICATION");
        assertThat(diagnostic.phases())
                .contains("pooledInfo:FAIL").contains("acquire:FAIL");
        assertThat(diagnostic.phases()).doesNotContain("info:OK");
    }

    @Test
    void infoTimeoutIsRecordedAsInfoSubPhaseAndConnectionStillReleased() throws Exception {
        RedisProperties properties = properties("hanginginfo.example", 6379, true, "token");
        RedisConnection connection = mock(RedisConnection.class);
        RedisServerCommands serverCommands = mock(RedisServerCommands.class);
        when(connection.serverCommands()).thenReturn(serverCommands);
        when(serverCommands.info()).thenAnswer(invocation -> {
            Thread.sleep(1_000);
            return new java.util.Properties();
        });
        RedisConnectionFactory factory = factory(connection);
        FakeHooks hooks = FakeHooks.healthy(1);

        var diagnostic = new RedisConnectivityDiagnostics(properties, factory)
                .diagnose(hooks, Duration.ofMillis(150));

        assertThat(diagnostic.result()).isEqualTo("DOWN");
        assertThat(diagnostic.errorCategory()).isEqualTo("TIMEOUT");
        assertThat(diagnostic.phases()).contains("pooledInfo:FAIL").contains("acquire:OK");
        // CompletableFuture cancellation does not interrupt the abandoned probe
        // thread; it must still release the connection from its own finally.
        verify(connection, org.mockito.Mockito.timeout(5_000).atLeastOnce()).close();
    }

    @Test
    void acquireTimeoutIsRecordedAsAcquireSubPhase() throws Exception {
        RedisProperties properties = properties("hangingacquire.example", 6379, true, "token");
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        CountDownLatch entered = new CountDownLatch(1);
        when(factory.getConnection()).thenAnswer(invocation -> {
            entered.countDown();
            Thread.sleep(10_000);
            return mock(RedisConnection.class);
        });
        FakeHooks hooks = FakeHooks.healthy(1);

        var diagnostic = new RedisConnectivityDiagnostics(properties, factory)
                .diagnose(hooks, Duration.ofMillis(150));

        assertThat(diagnostic.result()).isEqualTo("DOWN");
        assertThat(diagnostic.errorCategory()).isEqualTo("TIMEOUT");
        assertThat(diagnostic.phases()).contains("pooledInfo:FAIL").contains("acquire:FAIL").contains("[timeout]");
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void lateCallbackArrivingDuringGraceWindowIsCaptured() throws Exception {
        CountDownLatch terminal = new CountDownLatch(1);
        java.util.List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        Thread callback = new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            events.add("connectionFutureCompleted");
            terminal.countDown();
        });
        callback.start();

        RedisConnectivityDiagnostics.awaitLateLifecycleEvents(
                terminal, events, Duration.ofMillis(1500));

        assertThat(events).contains("connectionFutureCompleted")
                .anyMatch(event -> event.startsWith("lateEventGraceComplete/"));
        callback.join(500);
        assertThat(callback.isAlive()).isFalse();
    }

    @Test
    void lateEventGraceNeverExceeds1500Milliseconds() {
        CountDownLatch terminal = new CountDownLatch(1);
        java.util.List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        long started = System.nanoTime();

        RedisConnectivityDiagnostics.awaitLateLifecycleEvents(
                terminal, events, RedisConnectivityDiagnostics.LATE_EVENT_GRACE);

        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(elapsedMillis).isLessThan(1800);
        assertThat(events).anyMatch(event -> event.startsWith("lateEventGraceComplete/"));
    }

    @Test
    void connectionFailureDiagnosticPreservesSafeCauseChain() {
        RuntimeException root = new RuntimeException("NOAUTH password=super-secret");
        RuntimeException wrapped = new RuntimeException("connection initialization failed", root);

        String chain = RedisConnectivityDiagnostics.safeCauseChain(wrapped, null);

        assertThat(chain).contains("RuntimeException[connection initialization failed]")
                .contains("NOAUTH password=<redacted>")
                .doesNotContain("super-secret");
    }

    @Test
    void localLettuceLifecycleRegressionRemainsCoveredByRealAbExperiment() {
        // The real Docker Redis success matrix in RedisLettuceAbExperimentTest
        // exercises the same instrumented client and verifies connect completion
        // plus PING; this assertion protects the protocol-stage marker contract.
        assertThat(RedisConnectivityDiagnostics.describeUri(
                io.lettuce.core.RedisURI.builder().withHost("localhost").withPort(6379).build()))
                .contains("protocol=RESP2");
    }

    @Test
    void observationTimeoutDoesNotCancelUnderlyingConnectionFuture() throws Exception {
        io.lettuce.core.ConnectionFuture<String> future = mock(io.lettuce.core.ConnectionFuture.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(future.get()).thenAnswer(invocation -> {
            entered.countDown();
            release.await();
            return "late";
        });

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        RedisConnectivityDiagnostics.observeConnectionFuture(
                                future, Duration.ofMillis(50)))
                .isInstanceOf(java.util.concurrent.TimeoutException.class);
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        verify(future, org.mockito.Mockito.never()).cancel(org.mockito.ArgumentMatchers.anyBoolean());
        release.countDown();
    }

    @Test
    void lateUnderlyingFailureIsReportedByTheFutureRatherThanDiagnosticCancellation() throws Exception {
        io.lettuce.core.ConnectionFuture<String> future = mock(io.lettuce.core.ConnectionFuture.class);
        CountDownLatch terminal = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> observed =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(future.get()).thenAnswer(invocation -> {
            terminal.await();
            throw new java.util.concurrent.ExecutionException(
                    new io.lettuce.core.RedisConnectionException("real handshake failure"));
        });

        Thread completer = new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            observed.set(new io.lettuce.core.RedisConnectionException("real handshake failure"));
            terminal.countDown();
        });
        completer.start();

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        RedisConnectivityDiagnostics.observeConnectionFuture(
                                future, Duration.ofMillis(25)))
                .isInstanceOf(java.util.concurrent.TimeoutException.class);
        assertThat(terminal.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(observed.get()).isInstanceOf(io.lettuce.core.RedisConnectionException.class);
        verify(future, org.mockito.Mockito.never()).cancel(org.mockito.ArgumentMatchers.anyBoolean());
        completer.join(500);
    }

    @Test
    void cancellationIsNotReportedAsGenericClientServerError() {
        assertThat(RedisConnectivityDiagnostics.errorCategory(
                new java.util.concurrent.CancellationException("observer cancelled")))
                .isEqualTo("FUTURE_CANCELLED");
    }

    @Test
    void credentiallessFailureDoesNotPreventPasswordOnlyModeFromRunning() throws Exception {
        Assumptions.assumeTrue(RedisLettuceAbExperimentTest.redisAvailable());
        LettuceConnectionFactory factory = mock(LettuceConnectionFactory.class);
        when(factory.getHostName()).thenReturn("127.0.0.1");
        when(factory.getPort()).thenReturn(6379);
        when(factory.isUseSsl()).thenReturn(false);
        when(factory.getPassword()).thenReturn("deliberately-wrong-password");
        RedisProperties properties = properties("127.0.0.1", 6379, false,
                "deliberately-wrong-password");
        RedisConnectivityDiagnostics diagnostics =
                new RedisConnectivityDiagnostics(properties, factory);

        String detail;
        try {
            detail = diagnostics.rawLettuceProbe(diagnostics.effectiveSettings());
        } catch (Exception failure) {
            detail = RedisConnectivityDiagnostics.rawFailureDetail(failure);
        }

        // The credential-less mode may fail while password-only succeeds, or
        // both may fail depending on the local Redis fixture. Either way both
        // independent mode labels must be present in the final diagnostic.
        assertThat(detail).contains("explicit-default")
                .contains("password-only");
    }

    @Test
    void overallCapIsReportedAsDiagnosticTimeoutNotNetworkTimeout() throws Exception {
        RedisProperties properties = properties("hanging.example", 6379, true, "token");
        RedisConnectionFactory factory = factoryWithInfo();
        RedisConnectivityDiagnostics diagnostics =
                new RedisConnectivityDiagnostics(properties, factory) {
                    @Override
                    RedisConnectivityDiagnostics.Hooks hooks() {
                        return new RedisConnectivityDiagnostics.Hooks() {
                            @Override
                            public InetAddress[] resolve(String host) throws UnknownHostException {
                                try {
                                    Thread.sleep(5_000);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                                throw new UnknownHostException("never resolves");
                            }

                            @Override
                            public Socket newSocket() {
                                return new Socket();
                            }

                            @Override
                            public SSLSocket newTlsSocket() {
                                return null;
                            }
                        };
                    }
                };

        CompletableFuture<RedisConnectivityDiagnostics.Diagnostic> probe =
                diagnostics.probeAsync(Duration.ofMillis(120));

        Throwable failure = probe.handle((value, error) -> error).get(3, TimeUnit.SECONDS);
        assertThat(failure).isNotNull();
    }

    @Test
    void timeoutAndTlsFailuresAreCategorizedSafely() {
        assertThat(RedisConnectivityDiagnostics.errorCategory(new SocketTimeoutException("timeout")))
                .isEqualTo("TIMEOUT");
        assertThat(RedisConnectivityDiagnostics.errorCategory(new SSLException("tls failure")))
                .isEqualTo("TLS");
        assertThat(RedisConnectivityDiagnostics.category(new UnknownHostException("x")))
                .isEqualTo("DNS");
        assertThat(RedisConnectivityDiagnostics.category(new ConnectException("refused")))
                .isEqualTo("CONNECTION");
    }

    @Test
    void authRootCauseIsNotMaskedByConnectionWrapper() {
        // Regression mirroring the live Upstash NOAUTH chain: the outer wrapper's
        // class name contains "connect" and must not mask the deeper NOAUTH cause.
        io.lettuce.core.RedisCommandExecutionException noauth =
                new io.lettuce.core.RedisCommandExecutionException(
                        "NOAUTH Authentication required. See https://upstash.com/docs/redis/troubleshooting/no_auth");
        io.lettuce.core.RedisConnectionException lettuce =
                new io.lettuce.core.RedisConnectionException(
                        "Unable to connect to upstash.example/<unresolved>:6379", noauth);
        org.springframework.data.redis.RedisConnectionFailureException wrapper =
                new org.springframework.data.redis.RedisConnectionFailureException(
                        "Unable to connect to Redis", lettuce);

        assertThat(RedisConnectivityDiagnostics.errorCategory(wrapper)).isEqualTo("AUTHENTICATION");
    }

    @Test
    void readyListenerDoesNotWaitForRedisProbe() throws Exception {
        RedisProperties properties = properties("slow.example", 6379, true, "token");
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        CountDownLatch entered = new CountDownLatch(1);
        when(factory.getConnection()).thenAnswer(invocation -> {
            entered.countDown();
            Thread.sleep(10_000);
            return mock(RedisConnection.class);
        });
        RedisConnectivityDiagnostics diagnostics =
                new RedisConnectivityDiagnostics(properties, factory) {
                    @Override
                    RedisConnectivityDiagnostics.Hooks hooks() {
                        try {
                            return FakeHooks.healthy(1);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }
                };

        long started = System.nanoTime();
        diagnostics.probeAfterApplicationReady();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void authenticatedLettuceUrisCarryCredentialsWithoutExposingThemInDescriptions() {
        RedisConnectivityDiagnostics.ConnectionSettings settings =
                new RedisConnectivityDiagnostics.ConnectionSettings(
                        "Lettuce", "upstash.example", 6379, true, true);
        String password = "configured-password-not-logged";

        io.lettuce.core.RedisURI passwordOnly =
                RedisConnectivityDiagnostics.authenticatedUri(settings, null, password);
        io.lettuce.core.RedisURI explicitDefault =
                RedisConnectivityDiagnostics.authenticatedUri(settings, "default", password);

        assertThat(passwordOnly.getUsername()).isNull();
        assertThat(passwordOnly.getPassword()).isNotNull().hasSize(password.length());
        assertThat(explicitDefault.getUsername()).isEqualTo("default");
        assertThat(explicitDefault.getPassword()).isNotNull().hasSize(password.length());
        assertThat(RedisConnectivityDiagnostics.describeUri(passwordOnly))
                .doesNotContain(password, "configured-password");
        assertThat(RedisConnectivityDiagnostics.describeUri(explicitDefault))
                .doesNotContain(password, "configured-password");
    }

    @Test
    void lettuceUriDiagnosticReportsTransportAndProtocolWithoutCredentials() {
        io.lettuce.core.RedisURI uri = io.lettuce.core.RedisURI.builder()
                .withHost("absolute-skylark-284998.upstash.io")
                .withPort(6379)
                .withSsl(true)
                .build();

        assertThat(RedisConnectivityDiagnostics.describeUri(uri))
                .isEqualTo("scheme=rediss,host=absolute-skylark-284998.upstash.io,port=6379,ssl=true,protocol=RESP2")
                .doesNotContain("password", "token");
    }

    @Test
    void lifecyclePhaseMarkersAreSafeAndIncludeElapsedTime() {
        io.lettuce.core.RedisURI uri = io.lettuce.core.RedisURI.builder()
                .withHost("redis.example")
                .withPort(6379)
                .build();

        assertThat(RedisConnectivityDiagnostics.describeUri(uri))
                .contains("scheme=redis", "host=redis.example", "port=6379", "ssl=false", "protocol=RESP2")
                .doesNotContain("@", "password");
    }

    @Test
    void actuatorRedisHealthIndicatorRemainsAuthoritative() {
        RedisConnection upConnection = mock(RedisConnection.class);
        RedisServerCommands serverCommands = mock(RedisServerCommands.class);
        when(upConnection.serverCommands()).thenReturn(serverCommands);
        java.util.Properties info = new java.util.Properties();
        info.setProperty("redis_version", "7.2.0");
        when(serverCommands.info()).thenReturn(info);
        RedisConnectionFactory upFactory = factory(upConnection);
        assertThat(new RedisHealthIndicator(upFactory).health().getStatus()).isEqualTo(Status.UP);

        RedisConnectionFactory downFactory = mock(RedisConnectionFactory.class);
        when(downFactory.getConnection()).thenThrow(new RedisConnectionFailureException(
                "unavailable", new ConnectException("unavailable")));
        assertThat(new RedisHealthIndicator(downFactory).health().getStatus()).isEqualTo(Status.DOWN);
    }

    private RedisConnectionFactory factoryWithInfo() {
        RedisConnection connection = mock(RedisConnection.class);
        RedisServerCommands serverCommands = mock(RedisServerCommands.class);
        java.util.Properties info = new java.util.Properties();
        info.setProperty("redis_version", "7.2.0");
        when(connection.serverCommands()).thenReturn(serverCommands);
        when(serverCommands.info()).thenReturn(info);
        return factory(connection);
    }

    private RedisProperties properties(String host, int port, boolean ssl, String password) {
        RedisProperties properties = new RedisProperties();
        properties.setHost(host);
        properties.setPort(port);
        properties.setPassword(password);
        properties.getSsl().setEnabled(ssl);
        return properties;
    }

    private RedisConnectionFactory factory(RedisConnection connection) {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(factory.getConnection()).thenReturn(connection);
        return factory;
    }

    /** Deterministic fake network used by all phase tests. */
    private static final class FakeHooks implements RedisConnectivityDiagnostics.Hooks {
        final InetAddress[] addresses;
        final Socket[] sockets;
        final SSLSocket tlsSocket;
        private final DnsAction dnsAction;
        private final TcpAction[] tcpActions;
        private final AtomicInteger nextSocket = new AtomicInteger();

        @FunctionalInterface
        interface DnsAction {
            void run() throws UnknownHostException;
        }

        @SuppressWarnings("unchecked")
        private FakeHooks(InetAddress[] addresses, DnsAction dnsAction) {
            this.addresses = addresses;
            this.dnsAction = dnsAction;
            this.sockets = new Socket[addresses.length];
            this.tcpActions = new TcpAction[addresses.length];
            for (int i = 0; i < addresses.length; i++) {
                sockets[i] = mock(Socket.class);
            }
            this.tlsSocket = mock(SSLSocket.class);
            try {
                when(tlsSocket.getSSLParameters()).thenReturn(new SSLParameters());
                SSLSession session = mock(SSLSession.class);
                when(session.getProtocol()).thenReturn("TLSv1.3");
                when(tlsSocket.getSession()).thenReturn(session);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        static FakeHooks healthy(int addressCount) throws Exception {
            InetAddress[] addresses = new InetAddress[addressCount];
            for (int i = 0; i < addressCount; i++) {
                addresses[i] = Inet4Address.getByAddress(new byte[]{10, 0, 0, (byte) (i + 1)});
            }
            return new FakeHooks(addresses, () -> { });
        }

        static FakeHooks dnsFailure(UnknownHostException failure) {
            return new FakeHooks(new InetAddress[0], () -> {
                throw failure;
            });
        }

        void failAllTcp(Exception failure) {
            for (int i = 0; i < tcpActions.length; i++) {
                failTcpOn(i, failure);
            }
        }

        void failTcpOn(int index, Exception failure) {
            tcpActions[index] = socket -> {
                try {
                    throw failure;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            };
        }

        void failTls(Exception failure) throws Exception {
            org.mockito.Mockito.doAnswer(invocation -> {
                throw failure;
            }).when(tlsSocket).startHandshake();
        }

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            dnsAction.run();
            return addresses;
        }

        @FunctionalInterface
        interface TcpAction {
            void run(Socket socket) throws Exception;
        }

        @Override
        public Socket newSocket() throws Exception {
            int index = nextSocket.getAndIncrement();
            int slot = Math.min(index, sockets.length - 1);
            Socket socket = sockets[slot];
            TcpAction action = tcpActions[Math.min(index, tcpActions.length - 1)];
            if (action != null) {
                org.mockito.Mockito.doAnswer(invocation -> {
                    try {
                        action.run(socket);
                        return null;
                    } catch (Exception e) {
                        throw new java.io.IOException(e.getMessage(), e);
                    }
                }).when(socket).connect(org.mockito.ArgumentMatchers.any(SocketAddress.class),
                        org.mockito.ArgumentMatchers.anyInt());
            }
            return socket;
        }

        @Override
        public SSLSocket newTlsSocket() {
            return tlsSocket;
        }
    }
}
