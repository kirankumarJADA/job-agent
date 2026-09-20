package com.personal.jobagent.system;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.stereotype.Component;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.Closeable;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Safe post-startup diagnostic for the Redis health contributor. The Actuator
 * indicator remains enabled and authoritative.
 *
 * <p>The probe runs asynchronously after the application is ready and walks the
 * exact network path a production client takes, attributing any failure to the
 * phase where it occurred:
 *
 * <pre>
 *   dns        -> resolve the configured host (A/AAAA counts)
 *   tcp        -> plain TCP connect against each resolved address (v4 first)
 *   tls        -> TLS handshake with SNI against the first reachable address
 *   pooledInfo -> INFO through the shared RedisConnectionFactory; the same
 *                 probe Spring Boot's RedisHealthIndicator performs, covering
 *                 DNS/TCP/TLS/auth through the real client
 * </pre>
 *
 * <p>Each phase has a strict timeout. The diagnostic never blocks startup and
 * never logs credentials, tokens, connection strings, or data contents.
 */
@Component
public class RedisConnectivityDiagnostics {
    private static final Logger log = LoggerFactory.getLogger(RedisConnectivityDiagnostics.class);

    /** Overall asynchronous safety cap; individual phases have their own budgets. */
    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(20);
    static final Duration DNS_TIMEOUT = Duration.ofSeconds(2);
    static final Duration TCP_TIMEOUT = Duration.ofSeconds(2);
    static final Duration TLS_TIMEOUT = Duration.ofSeconds(3);
    static final Duration POOLED_INFO_TIMEOUT = Duration.ofSeconds(3);

    private final RedisProperties properties;
    private final RedisConnectionFactory connectionFactory;

    /**
     * Dedicated daemon pool for probe work. Deliberately NOT the common ForkJoin
     * pool: bounded() submits nested work while diagnose() itself runs inside a
     * probe submission, and nested common-pool submissions can self-starve.
     */
    private static final ExecutorService PROBE_EXECUTOR =
            Executors.newCachedThreadPool(RedisConnectivityDiagnostics::daemonThread);

    private static Thread daemonThread(Runnable runnable) {
        Thread thread = new Thread(runnable, "redis-connectivity-probe");
        thread.setDaemon(true);
        return thread;
    }

    public RedisConnectivityDiagnostics(RedisProperties properties,
                                        RedisConnectionFactory connectionFactory) {
        this.properties = properties;
        this.connectionFactory = connectionFactory;
    }

    @EventListener(ApplicationReadyEvent.class)
    void probeAfterApplicationReady() {
        probeAsync(PROBE_TIMEOUT).whenComplete((diagnostic, failure) -> {
            if (failure != null) {
                // The overall cap fired. Do NOT claim a network TIMEOUT here:
                // the failing phase was not identified, so label it explicitly.
                log.info("Redis connectivity diagnosis: client={}, host={}, port={}, sslEnabled={}, "
                                + "authenticationConfigured={}, healthProbe=INFO, result=DOWN, "
                                + "errorCategory=DIAGNOSTIC_TIMEOUT, phases={}",
                        client(), host(), port(), sslEnabled(), authenticationConfigured(),
                        failureName(failure));
                return;
            }
            logDiagnostic(diagnostic);
        });
    }

    CompletableFuture<Diagnostic> probeAsync(Duration overallTimeout) {
        return CompletableFuture
                .supplyAsync(this::diagnose, PROBE_EXECUTOR)
                .orTimeout(overallTimeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void logDiagnostic(Diagnostic diagnostic) {
        log.info("Redis connectivity diagnosis: client={}, host={}, port={}, sslEnabled={}, "
                        + "authenticationConfigured={}, healthProbe=INFO, result={}, errorCategory={}, phases={}",
                diagnostic.client(), diagnostic.host(), diagnostic.port(), diagnostic.sslEnabled(),
                diagnostic.authenticationConfigured(), diagnostic.result(),
                diagnostic.errorCategory(), diagnostic.phases());
    }

    Diagnostic diagnose() {
        return diagnose(hooks(), POOLED_INFO_TIMEOUT);
    }

    /** Overridable in tests so the asynchronous listener path stays fakeable. */
    Hooks hooks() {
        return RealHooks.INSTANCE;
    }

    /**
     * Package-private overloads let unit tests fake the network and bound the
     * pooled phase, so tests never touch real endpoints.
     */
    Diagnostic diagnose(Hooks hooks, Duration pooledInfoTimeout) {
        ConnectionSettings s = effectiveSettings();
        List<Phase> phases = new ArrayList<>();

        // ---- Phase 1: DNS -------------------------------------------------
        List<InetAddress> ordered = new ArrayList<>();
        long t0 = System.nanoTime();
        try {
            InetAddress[] all = bounded(DNS_TIMEOUT, () -> hooks.resolve(s.host()));
            int v4 = 0;
            int v6 = 0;
            List<InetAddress> ipv4 = new ArrayList<>();
            List<InetAddress> ipv6 = new ArrayList<>();
            for (InetAddress address : all) {
                if (address.getHostAddress() != null && address.getHostAddress().contains(":")) {
                    v6++;
                    ipv6.add(address);
                } else {
                    v4++;
                    ipv4.add(address);
                }
            }
            // Deterministic v4-first probing so an unreachable address family
            // can never shadow a reachable one.
            ordered.addAll(ipv4);
            ordered.addAll(ipv6);
            if (ordered.isEmpty()) {
                throw new UnknownHostException(s.host());
            }
            phases.add(Phase.ok("dns", elapsed(t0), "v4=" + v4 + ",v6=" + v6));
        } catch (Exception failure) {
            phases.add(Phase.fail("dns", elapsed(t0), simpleName(failure)));
            return down(s, phases, category(failure));
        }

        // ---- Phase 2: TCP per resolved address (v4 first) -----------------
        InetAddress reachable = null;
        long t1 = System.nanoTime();
        int attempted = 0;
        String lastError = null;
        String lastCategory = "CONNECTION";
        for (InetAddress address : ordered) {
            attempted++;
            try {
                Socket socket = bounded(TCP_TIMEOUT, () -> {
                    Socket opened = hooks.newSocket();
                    try {
                        opened.connect(new InetSocketAddress(address, s.port()),
                                (int) TCP_TIMEOUT.toMillis());
                        return opened;
                    } catch (Exception e) {
                        closeQuietly(opened);
                        throw e;
                    }
                });
                closeQuietly(socket);
                reachable = address;
                phases.add(Phase.ok("tcp", elapsed(t1),
                        family(address) + " " + attempted + "/" + ordered.size()));
                break;
            } catch (Exception failure) {
                lastError = simpleName(failure);
                lastCategory = category(failure);
            }
        }
        if (reachable == null) {
            phases.add(Phase.fail("tcp", elapsed(t1),
                    "0/" + ordered.size() + " last=" + lastError));
            return down(s, phases, lastCategory);
        }

        // ---- Phase 3: TLS handshake with SNI ------------------------------
        if (s.sslEnabled()) {
            final InetAddress tlsTarget = reachable;
            long t2 = System.nanoTime();
            try {
                String protocol = bounded(TLS_TIMEOUT, () -> {
                    SSLSocket tls = hooks.newTlsSocket();
                    try {
                        tls.connect(new InetSocketAddress(tlsTarget, s.port()),
                                (int) TLS_TIMEOUT.toMillis());
                        SSLParameters parameters = tls.getSSLParameters();
                        if (parameters != null) {
                            try {
                                parameters.setServerNames(List.of(new SNIHostName(s.host())));
                                tls.setSSLParameters(parameters);
                            } catch (IllegalArgumentException tolerated) {
                                // Literal IP or otherwise invalid SNI name:
                                // the handshake proceeds without SNI.
                            }
                            tls.setSSLParameters(parameters);
                        }
                        tls.setSoTimeout((int) TLS_TIMEOUT.toMillis());
                        tls.startHandshake();
                        return tls.getSession().getProtocol();
                    } finally {
                        closeQuietly(tls);
                    }
                });
                phases.add(Phase.ok("tls", elapsed(t2), "protocol=" + protocol));
            } catch (Exception failure) {
                phases.add(Phase.fail("tls", elapsed(t2), simpleName(failure)));
                return down(s, phases, tlsCategory(failure));
            }
        } else {
            phases.add(Phase.skipped("tls"));
        }

        // ---- Phase 4: pooled INFO (same probe as the Actuator indicator) --
        long t3 = System.nanoTime();
        try {
            bounded(pooledInfoTimeout, () -> {
                try (RedisConnection connection = connectionFactory.getConnection()) {
                    if (connection.serverCommands().info() == null) {
                        throw new IllegalStateException("Redis INFO returned no response");
                    }
                    return true;
                }
            });
            phases.add(Phase.ok("pooledInfo", elapsed(t3), null));
            return up(s, phases);
        } catch (Exception failure) {
            phases.add(Phase.fail("pooledInfo", elapsed(t3), simpleName(failure)));
            return down(s, phases, errorCategory(failure));
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

    private String host() { return effectiveSettings().host(); }
    private int port() { return effectiveSettings().port(); }
    private boolean sslEnabled() { return effectiveSettings().sslEnabled(); }
    private boolean authenticationConfigured() { return effectiveSettings().authenticationConfigured(); }
    private String client() { return effectiveSettings().client(); }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Phase-level category for the direct socket phases. The pooled phase uses
     * {@link #errorCategory(Throwable)} so its mapping stays aligned with the
     * historical diagnostic output.
     */
    static String category(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof TimeoutException || current instanceof SocketTimeoutException) {
                return "TIMEOUT";
            }
            if (current instanceof UnknownHostException) {
                return "DNS";
            }
            if (current instanceof ConnectException) {
                return "CONNECTION";
            }
        }
        return errorCategory(failure);
    }

    private static String tlsCategory(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof TimeoutException || current instanceof SocketTimeoutException) {
                return "TIMEOUT";
            }
        }
        return "TLS";
    }

    static String errorCategory(Throwable failure) {
        // Single pass over the whole chain, classifying each element. The most
        // specific signal found anywhere wins: outer wrappers like
        // RedisConnectionFailureException contain "connect" in their class name
        // and must not mask a NOAUTH root cause deeper in the chain.
        String fallback = "CLIENT_OR_SERVER_ERROR";
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
            if (current instanceof TimeoutException || current instanceof SocketTimeoutException
                    || type.contains("timeout") || message.contains("timed out")
                    || message.contains("timeout")) {
                return "TIMEOUT";
            }
            if (current instanceof UnknownHostException || type.contains("unknownhost")) {
                return "DNS";
            }
            if (type.contains("connect") || message.contains("refused")) {
                // Weakest signal: remember it but keep scanning for something
                // more specific deeper in the chain.
                if ("CLIENT_OR_SERVER_ERROR".equals(fallback)) {
                    fallback = "CONNECTION";
                }
            }
        }
        return fallback;
    }

    private Diagnostic up(ConnectionSettings s, List<Phase> phases) {
        return new Diagnostic(s.client(), s.host(), s.port(), s.sslEnabled(),
                s.authenticationConfigured(), "UP", null, render(phases));
    }

    private Diagnostic down(ConnectionSettings s, List<Phase> phases, String category) {
        markRemainingSkipped(phases);
        return new Diagnostic(s.client(), s.host(), s.port(), s.sslEnabled(),
                s.authenticationConfigured(), "DOWN", category, render(phases));
    }

    private static final List<String> PHASE_ORDER = List.of("dns", "tcp", "tls", "pooledInfo");

    private static void markRemainingSkipped(List<Phase> phases) {
        String last = phases.isEmpty() ? null : phases.get(phases.size() - 1).name();
        int from = last == null ? 0 : PHASE_ORDER.indexOf(last) + 1;
        for (int i = from; i < PHASE_ORDER.size(); i++) {
            phases.add(Phase.skipped(PHASE_ORDER.get(i)));
        }
    }

    private static String render(List<Phase> phases) {
        StringBuilder rendered = new StringBuilder();
        for (Phase phase : phases) {
            if (rendered.length() > 0) {
                rendered.append(';');
            }
            rendered.append(phase.render());
        }
        return rendered.toString();
    }

    private static <T> T bounded(Duration timeout, ThrowingSupplier<T> operation) throws Exception {
        CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> {
            try {
                return operation.get();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, PROBE_EXECUTOR);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            // Unwrap ExecutionException + CompletionException so callers see the
            // original failure and phase categorization stays accurate.
            Throwable cause = e.getCause() == null ? e : e.getCause();
            while (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw e;
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        }
    }

    private static long elapsed(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    private static String simpleName(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName();
    }

    private static String failureName(Throwable failure) {
        return simpleName(failure);
    }

    private static String family(InetAddress address) {
        return address.getHostAddress() != null && address.getHostAddress().contains(":")
                ? "v6" : "v4";
    }

    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Probe socket cleanup only; nothing to recover.
        }
    }

    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    /** Network seams swapped for fakes in unit tests. */
    interface Hooks {
        InetAddress[] resolve(String host) throws UnknownHostException;

        Socket newSocket() throws Exception;

        SSLSocket newTlsSocket() throws Exception;
    }

    private enum RealHooks implements Hooks {
        INSTANCE;

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            return InetAddress.getAllByName(host);
        }

        @Override
        public Socket newSocket() {
            return new Socket();
        }

        @Override
        public SSLSocket newTlsSocket() throws Exception {
            return (SSLSocket) SSLSocketFactory.getDefault().createSocket();
        }
    }

    record Phase(String name, String status, long durationMs, String detail) {

        static Phase ok(String name, long durationMs, String detail) {
            return new Phase(name, "OK", durationMs, detail);
        }

        static Phase fail(String name, long durationMs, String detail) {
            return new Phase(name, "FAIL", durationMs, detail);
        }

        static Phase skipped(String name) {
            return new Phase(name, "SKIPPED", 0, null);
        }

        String render() {
            StringBuilder rendered = new StringBuilder(name).append(':').append(status);
            if (!"SKIPPED".equals(status)) {
                rendered.append('/').append(durationMs).append("ms");
                if (detail != null && !detail.isBlank()) {
                    rendered.append('[').append(detail).append(']');
                }
            }
            return rendered.toString();
        }
    }

    record Diagnostic(String client, String host, int port, boolean sslEnabled,
                      boolean authenticationConfigured, String result,
                      String errorCategory, String phases) {
    }

    private record ConnectionSettings(String client, String host, int port,
                                      boolean sslEnabled, boolean authenticationConfigured) {
    }
}
