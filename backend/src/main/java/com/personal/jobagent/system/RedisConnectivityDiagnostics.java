package com.personal.jobagent.system;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.stereotype.Component;

/**
 * Safe startup diagnostic for the Redis health contributor. The actuator
 * indicator remains enabled and authoritative; this component only makes
 * configuration mismatches (especially missing Upstash authentication)
 * visible in logs without logging secrets or exception messages.
 */
@Component
public class RedisConnectivityDiagnostics {
    private static final Logger log = LoggerFactory.getLogger(RedisConnectivityDiagnostics.class);

    private final RedisProperties properties;
    private final RedisConnectionFactory connectionFactory;

    public RedisConnectivityDiagnostics(RedisProperties properties,
                                       RedisConnectionFactory connectionFactory) {
        this.properties = properties;
        this.connectionFactory = connectionFactory;
    }

    @PostConstruct
    void logStartupConnectivity() {
        Diagnostic diagnostic = diagnose();
        log.info("Redis connectivity diagnosis: client={}, host={}, port={}, sslEnabled={}, "
                        + "authenticationConfigured={}, healthProbe=INFO, result={}, errorCategory={}",
                diagnostic.client(), diagnostic.host(), diagnostic.port(), diagnostic.sslEnabled(),
                diagnostic.authenticationConfigured(), diagnostic.result(), diagnostic.errorCategory());
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
