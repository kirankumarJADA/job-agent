package com.personal.jobagent.system;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.actuate.data.redis.RedisHealthIndicator;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisServerCommands;
import org.springframework.data.redis.RedisConnectionFailureException;

import javax.net.ssl.SSLException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisConnectivityDiagnosticsTest {

    @Test
    void validUpstashStyleConfigurationReportsAuthenticatedTlsConnection() {
        RedisProperties properties = properties("upstash.example", 6379, true, "token-not-logged");
        RedisConnection connection = mock(RedisConnection.class);
        RedisServerCommands serverCommands = mock(RedisServerCommands.class);
        java.util.Properties info = new java.util.Properties();
        info.setProperty("redis_version", "7.2.0");
        when(connection.serverCommands()).thenReturn(serverCommands);
        when(serverCommands.info()).thenReturn(info);
        RedisConnectionFactory factory = factory(connection);

        var diagnostic = new RedisConnectivityDiagnostics(properties, factory).diagnose();

        assertThat(diagnostic.client()).isNotBlank();
        assertThat(diagnostic.host()).isEqualTo("upstash.example");
        assertThat(diagnostic.port()).isEqualTo(6379);
        assertThat(diagnostic.sslEnabled()).isTrue();
        assertThat(diagnostic.authenticationConfigured()).isTrue();
        assertThat(diagnostic.result()).isEqualTo("UP");
        assertThat(diagnostic.errorCategory()).isNull();
    }

    @Test
    void missingAuthenticationIsVisibleWithoutLoggingTheCredential() {
        RedisProperties properties = properties("upstash.example", 6379, true, "");
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(factory.getConnection()).thenThrow(new RuntimeException("authentication required"));

        var diagnostic = new RedisConnectivityDiagnostics(properties, factory).diagnose();

        assertThat(diagnostic.authenticationConfigured()).isFalse();
        assertThat(diagnostic.result()).isEqualTo("DOWN");
        assertThat(diagnostic.errorCategory()).isEqualTo("AUTHENTICATION");
    }

    @Test
    void unavailableRedisIsCategorizedAsConnectionFailure() {
        RedisProperties properties = properties("unreachable.example", 6379, true, "token");
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(factory.getConnection()).thenThrow(new RedisConnectionFailureException(
                "connection refused", new ConnectException("connection refused")));

        var diagnostic = new RedisConnectivityDiagnostics(properties, factory).diagnose();

        assertThat(diagnostic.result()).isEqualTo("DOWN");
        assertThat(diagnostic.errorCategory()).isEqualTo("CONNECTION");
    }

    @Test
    void timeoutAndTlsFailuresAreCategorizedSafely() {
        assertThat(RedisConnectivityDiagnostics.errorCategory(new SocketTimeoutException("timeout")))
                .isEqualTo("TIMEOUT");
        assertThat(RedisConnectivityDiagnostics.errorCategory(new SSLException("tls failure")))
                .isEqualTo("TLS");
    }

    @Test
    void applicationReadyListenerReturnsWithoutWaitingForRedisProbe() throws Exception {
        RedisProperties properties = properties("slow.example", 6379, true, "token");
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(factory.getConnection()).thenAnswer(invocation -> {
            entered.countDown();
            release.await(2, TimeUnit.SECONDS);
            return mock(RedisConnection.class);
        });

        RedisConnectivityDiagnostics diagnostics = new RedisConnectivityDiagnostics(properties, factory);
        long started = System.nanoTime();
        diagnostics.probeAfterApplicationReady();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        release.countDown();
    }

    @Test
    void startupProbeIsAsynchronousAndBoundedWhenRedisBlocks() throws Exception {
        RedisProperties properties = properties("slow.example", 6379, true, "token");
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        CountDownLatch entered = new CountDownLatch(1);
        when(factory.getConnection()).thenAnswer(invocation -> {
            entered.countDown();
            Thread.sleep(5_000);
            return mock(RedisConnection.class);
        });

        RedisConnectivityDiagnostics diagnostics = new RedisConnectivityDiagnostics(properties, factory);
        long started = System.nanoTime();
        CompletableFuture<?> result = diagnostics.probeAsync(Duration.ofMillis(100));
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(result.orTimeout(1, TimeUnit.SECONDS).handle((value, error) -> error))
                .succeedsWithin(2, TimeUnit.SECONDS)
                .isNotNull();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
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
        assertThat(new RedisConnectivityDiagnostics(properties("unreachable.example", 6379, true, "token"), downFactory)
                .diagnose().result()).isEqualTo("DOWN");
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
}
