package com.personal.jobagent.system;

import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Safe post-startup diagnostic for the Redis health contributor. The Actuator
 * indicator remains enabled and authoritative. The probe is asynchronous so
 * an external Redis outage cannot prevent Tomcat from binding its port.
 */
@Component
public class RedisConnectivityDiagnostics {
    private static final Logger log = LoggerFactory.getLogger(RedisConnectivityDiagnostics.class);

    private final RedisProperties properties;
    private final RedisConnectionFactory connectionFactory;
    private final ExecutorService probeExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "redis-connectivity-diagnostic");
        thread.setDaemon(true);
        return thread;
    });

    public RedisConnectivityDiagnostics(RedisProperties properties,
                                       RedisConnectionFactory connectionFactory) {
        this.properties = properties;
        this.connectionFactory = connectionFactory;
    }

    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);

    @EventListener(ApplicationReadyEvent.class)
    void probeAfterApplicationReady() {
        probeAsync(PROBE_TIMEOUT)
                .whenComplete((diagnostic, failure) -> {
                    if (failure != null) {
                        ConnectionSettings settings = effectiveSettings();
                        log.info("Redis connectivity diagnosis: client={}, host={}, port={}, sslEnabled={}, "
                                        + "authenticationConfigured={}, healthProbe=INFO, result=DOWN, errorCategory=TIMEOUT",
                                settings.client(), settings.host(), settings.port(), settings.sslEnabled(),
                                settings.authenticationConfigured());
                        return;
                    }
                    log.info("Redis connectivity diagnosis: client={}, host={}, port={}, sslEnabled={}, "
                                    + "authenticationConfigured={}, healthProbe=INFO, result={}, errorCategory={}",
                            diagnostic.client(), diagnostic.host(), diagnostic.port(), diagnostic.sslEnabled(),
                            diagnostic.authenticationConfigured(), diagnostic.result(), diagnostic.errorCategory());
                });
    }

    CompletableFuture<Diagnostic> probeAsync(Duration timeout) {
        CompletableFuture<Diagnostic> probe = CompletableFuture
                .supplyAsync(this::diagnose, probeExecutor)
                .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
        // This is a one-shot startup diagnostic. Interrupt and release the
        // dedicated daemon executor on both success and timeout; a failed
        // external endpoint must not leave a connection task or executor
        // alive for the lifetime of the application.
        probe.whenComplete((ignored, failure) -> probeExecutor.shutdownNow());
        return probe;
    }

    @PreDestroy
    void stopProbeExecutor() {
        probeExecutor.shutdownNow();
    }

    Diagnostic diagnose() {
        ConnectionSettings settings = effectiveSettings();
        try (RedisConnection connection = connectionFactory.getConnection()) {
            // Spring Boot's RedisHealthIndicator uses the INFO command for a
            // standalone connection. Use the same probe so this diagnostic
            // cannot report PING success while Actuator reports DOWN.
            if (connection.serverCommands().info() == null) {
                throw new IllegalStateException("Redis INFO returned no response");
            }
            return new Diagnostic(settings.client(), settings.host(), settings.port(), settings.sslEnabled(),
                    settings.authenticationConfigured(), "UP", null);
        } catch (Throwable failure) {
            return new Diagnostic(settings.client(), settings.host(), settings.port(), settings.sslEnabled(),
                    settings.authenticationConfigured(), "DOWN", errorCategory(failure));
        }
    }

    private ConnectionSettings effectiveSettings() {
        String host = properties.getHost();
        int port = properties.getPort();
        boolean sslEnabled = properties.getSsl().isEnabled();
        boolean authenticationConfigured = hasText(properties.getPassword());
        String client = connectionFactory.getClass().getSimpleName();

        if (connectionFactory instanceof LettuceConnectionFactory lettuce) {
            host = lettuce.getHostName();
            port = lettuce.getPort();
            sslEnabled = lettuce.isUseSsl();
            authenticationConfigured = hasText(lettuce.getPassword());
            client = "Lettuce";
        }
        return new ConnectionSettings(client, host, port, sslEnabled, authenticationConfigured);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    static String errorCategory(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String type = current.getClass().getName().toLowerCase();
            String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase();
            if (type.contains("auth") || type.contains("noauth")
                    || type.contains("wrongpassword") || type.contains("invalidpassword")
                    || message.contains("noauth") || message.contains("wrongpass")
                    || message.contains("authentication required")
                    || message.contains("invalid username-password pair")) {
                return "AUTHENTICATION";
            }
            if (type.contains("ssl") || type.contains("tls") || type.contains("certificate")) {
                return "TLS";
            }
            if (type.contains("timeout") || type.contains("timedout")) {
                return "TIMEOUT";
            }
            if (type.contains("connect") || type.contains("refused")
                    || type.contains("unknownhost") || type.contains("unresolved")) {
                return "CONNECTION";
            }
        }
        return "CLIENT_OR_SERVER_ERROR";
    }

    record Diagnostic(String client, String host, int port, boolean sslEnabled,
                      boolean authenticationConfigured, String result,
                      String errorCategory) {
    }

    private record ConnectionSettings(String client, String host, int port,
                                      boolean sslEnabled, boolean authenticationConfigured) {
    }
}
