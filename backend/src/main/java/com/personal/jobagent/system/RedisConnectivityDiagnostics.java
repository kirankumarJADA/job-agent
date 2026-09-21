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

import io.lettuce.core.ClientOptions;
import io.lettuce.core.protocol.ProtocolVersion;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.event.Event;
import io.lettuce.core.event.command.CommandBaseEvent;
import io.lettuce.core.resource.DefaultClientResources;
import io.lettuce.core.resource.DnsResolvers;
import io.lettuce.core.resource.NettyCustomizer;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.ssl.SslHandler;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

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
 *   pooledInfo -> pooled round-trip through the shared RedisConnectionFactory
 *                 (the same probe Spring Boot's RedisHealthIndicator uses),
 *                 with sub-phases acquire (pool wait + connect + AUTH), info
 *                 (command round-trip), and release (connection close)
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
    static final Duration RAW_PHASE_TIMEOUT = Duration.ofSeconds(2);
    static final Duration RAW_TOTAL_TIMEOUT = Duration.ofSeconds(7);

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
                                + "authenticationConfigured={}, passwordLength={}, passwordSha256Prefix={}, "
                                + "healthProbe=INFO, effectiveClient={}, result=DOWN, "
                                + "errorCategory=DIAGNOSTIC_TIMEOUT, phases={}",
                        client(), host(), port(), sslEnabled(), authenticationConfigured(),
                        passwordLength(), passwordSha256Prefix(), effectiveClientDetails(), failureName(failure));
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
                        + "authenticationConfigured={}, passwordLength={}, passwordSha256Prefix={}, "
                        + "healthProbe=INFO, effectiveClient={}, result={}, errorCategory={}, phases={}",
                diagnostic.client(), diagnostic.host(), diagnostic.port(), diagnostic.sslEnabled(),
                diagnostic.authenticationConfigured(), passwordLength(), passwordSha256Prefix(),
                effectiveClientDetails(), diagnostic.result(), diagnostic.errorCategory(), diagnostic.phases());
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

        // ---- Phase 4: pooled INFO through the real client ----------------
        // Sub-phases make the historical 3000ms TimeoutException attributable:
        // acquire covers pool wait + connection establishment + AUTH (Lettuce
        // authenticates while establishing the native connection), info is the
        // command round-trip, release is the connection close. The whole
        // section stays bounded by pooledInfoTimeout; on timeout the abandoned
        // probe closes its own connection in its finally.
        long t3 = System.nanoTime();
        List<Phase> pooled = Collections.synchronizedList(new ArrayList<>());
        try {
            bounded(pooledInfoTimeout, () -> pooledProbe(pooled));
            phases.add(Phase.ok("pooledInfo", elapsed(t3), renderSubPhases(pooled)));
            return up(s, phases);
        } catch (Exception failure) {
            boolean probeTimedOut = failure instanceof TimeoutException
                    || failure instanceof SocketTimeoutException;
            if (probeTimedOut) {
                if (pooled.isEmpty()) {
                    pooled.add(Phase.fail("acquire", elapsed(t3), "timeout"));
                } else if (!hasPhaseNamed(pooled, "info")) {
                    pooled.add(Phase.fail("info", elapsed(t3), "timeout"));
                }
            }
            phases.add(Phase.fail("pooledInfo", elapsed(t3), renderSubPhases(pooled)));
            long rawStart = System.nanoTime();
            try {
                String rawDetail = bounded(RAW_TOTAL_TIMEOUT, () -> rawLettuceProbe(s));
                phases.add(Phase.ok("rawLettuce", elapsed(rawStart), rawDetail));
            } catch (Exception rawFailure) {
                phases.add(Phase.fail("rawLettuce", elapsed(rawStart),
                        rawFailureDetail(rawFailure)));
            }
            return down(s, phases, errorCategory(failure));
        }
    }

    /**
     * Direct Lettuce comparison probe. It uses the effective endpoint, TLS
     * setting and password from the same factory as the Spring probe, but does
     * not use Spring Data's shared-connection lock/future.
     */
    private String rawLettuceProbe(ConnectionSettings settings) throws Exception {
        String password = effectivePassword();
        if (!hasText(password)) {
            return rawLettuceAuthMode(settings, "password-only", null, null);
        }
        // Test both representations used by Redis clients. Spring's standalone
        // configuration commonly emits password-only AUTH; an explicit default
        // user exercises the ACL form used by Redis 6+/Upstash.
        List<String> results = new ArrayList<>();
        RawPhaseException firstFailure = null;
        try {
            results.add(rawLettuceAuthMode(settings, "explicit-default", "default", password));
        } catch (RawPhaseException failure) {
            firstFailure = failure;
            results.add("explicit-default.FAIL[" + rawFailureDetail(failure) + "]");
        }
        try {
            results.add(rawLettuceAuthMode(settings, "password-only", null, password));
        } catch (RawPhaseException failure) {
            if (firstFailure == null) {
                firstFailure = failure;
            }
            results.add("password-only.FAIL[" + rawFailureDetail(failure) + "]");
        }
        if (firstFailure != null && results.stream().noneMatch(value -> value.contains(":OK"))) {
            throw firstFailure;
        }
        return String.join(";", results);
    }

    private String rawLettuceAuthMode(ConnectionSettings settings, String mode,
                                      String username, String password) throws Exception {
        RedisURI uri = RedisURI.builder()
                .withHost(settings.host())
                .withPort(settings.port())
                .withSsl(settings.sslEnabled())
                .withVerifyPeer(settings.sslEnabled())
                // AUTH is deliberately issued as a separate command below so
                // connect, AUTH, and PING can be distinguished.
                .build();
        StatefulRedisConnection<String, String> connection = null;
        List<String> phases = new ArrayList<>();
        List<String> lifecycleEvents = new CopyOnWriteArrayList<>();
        long lifecycleStarted = System.nanoTime();
        // Match the application's effective resolver. Using Lettuce's default
        // unresolved Netty resolver here would test a different path than the
        // Spring factory and could falsely attribute a resolver failure to the
        // protocol handshake.
        DefaultClientResources resources = DefaultClientResources.builder()
                .dnsResolver(DnsResolvers.JVM_DEFAULT)
                .nettyCustomizer(lifecycleNettyCustomizer(lifecycleEvents))
                .build();
        RedisClient client = RedisClient.create(resources, uri);
        client.setOptions(ClientOptions.builder().protocolVersion(ProtocolVersion.RESP2).build());
        var eventSubscription = resources.eventBus().get()
                .subscribe(event -> lifecycleEvents.add(renderLifecycleEvent(event,
                        elapsed(lifecycleStarted))));
        try {
            long started = System.nanoTime();
            try {
                connection = bounded(RAW_PHASE_TIMEOUT, client::connect);
                phases.add(mode + ".connect:OK/" + elapsed(started) + "ms");
            } catch (Exception failure) {
                throw new RawPhaseException(mode + ".connect", failure, lifecycleEvents);
            }

            StatefulRedisConnection<String, String> rawConnection = connection;
            if (hasText(password)) {
                started = System.nanoTime();
                try {
                    String authResponse = bounded(RAW_PHASE_TIMEOUT, () -> username == null
                            ? rawConnection.sync().auth(password)
                            : rawConnection.sync().auth(username, password));
                    phases.add(mode + ".auth:OK/" + elapsed(started) + "ms[response="
                            + (authResponse == null ? "empty" : "received") + "]");
                } catch (Exception failure) {
                    throw new RawPhaseException(mode + ".auth", failure, lifecycleEvents);
                }
            } else {
                phases.add(mode + ".auth:SKIPPED");
            }

            started = System.nanoTime();
            try {
                String pingResponse = bounded(RAW_PHASE_TIMEOUT, () -> rawConnection.sync().ping());
                phases.add(mode + ".ping:OK/" + elapsed(started) + "ms[response="
                        + (pingResponse == null ? "empty" : "received") + "]");
            } catch (Exception failure) {
                throw new RawPhaseException(mode + ".ping", failure, lifecycleEvents);
            }
            return String.join(",", phases);
        } catch (RawPhaseException failure) {
            throw failure;
        } finally {
            if (connection != null) {
                connection.close();
            }
            eventSubscription.dispose();
            client.shutdown();
            resources.shutdown();
        }
    }

    /**
     * One full pooled round-trip. Runs on a probe-executor thread under the
     * caller's overall budget; records each sub-phase into {@code pooled} as
     * it completes so a timeout reveals exactly where the probe was stuck.
     */
    private boolean pooledProbe(List<Phase> pooled) throws Exception {
        long acquireStart = System.nanoTime();
        RedisConnection connection = null;
        try {
            connection = connectionFactory.getConnection();
            pooled.add(Phase.ok("acquire", elapsed(acquireStart), null));
        } catch (Exception failure) {
            pooled.add(Phase.fail("acquire", elapsed(acquireStart), simpleName(failure)));
            throw failure;
        }
        long infoStart = System.nanoTime();
        try {
            if (connection.serverCommands().info() == null) {
                pooled.add(Phase.fail("info", elapsed(infoStart), "empty-response"));
                throw new IllegalStateException("Redis INFO returned no response");
            }
            pooled.add(Phase.ok("info", elapsed(infoStart), null));
        } catch (Exception failure) {
            if (hasPhaseNamed(pooled, "acquire") && !hasPhaseNamed(pooled, "info")) {
                pooled.add(Phase.fail("info", elapsed(infoStart), simpleName(failure)));
            }
            throw failure;
        } finally {
            if (connection != null) {
                long releaseStart = System.nanoTime();
                closeConnectionQuietly(connection);
                pooled.add(Phase.ok("release", elapsed(releaseStart), null));
            }
        }
        return true;
    }

    private static boolean hasPhaseNamed(List<Phase> phases, String name) {
        synchronized (phases) {
            for (Phase phase : phases) {
                if (name.equals(phase.name())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String renderSubPhases(List<Phase> phases) {
        synchronized (phases) {
            StringBuilder rendered = new StringBuilder();
            for (Phase phase : phases) {
                if (rendered.length() > 0) {
                    rendered.append(',');
                }
                rendered.append(phase.render());
            }
            return rendered.toString();
        }
    }

    private static void closeConnectionQuietly(RedisConnection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (Exception ignored) {
            // Best-effort release of a probe connection.
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

    private String effectiveClientDetails() {
        if (!(connectionFactory instanceof LettuceConnectionFactory lettuce)) {
            return "factory=" + connectionFactory.getClass().getSimpleName();
        }
        var options = lettuce.getClientConfiguration().getClientOptions();
        String optionsSummary = options.map(value -> "commandTimeout=" + lettuce.getTimeout()
                        + "ms,connectTimeout=" + value.getSocketOptions().getConnectTimeout().toMillis()
                        + "ms,applyConnectionTimeout=" + value.getTimeoutOptions().isApplyConnectionTimeout())
                .orElse("options=unavailable");
        String resolver = lettuce.getClientConfiguration().getClientResources()
                .map(resources -> {
                    if (resources.dnsResolver() instanceof DnsResolvers dnsResolver) {
                        return dnsResolver.name();
                    }
                    return resources.dnsResolver().getClass().getName();
                })
                .orElse("resources=unavailable");
        return "shareNativeConnection=" + lettuce.getShareNativeConnection()
                + "," + optionsSummary + ",dnsResolver=" + resolver;
    }

    private String effectivePassword() {
        if (connectionFactory instanceof LettuceConnectionFactory lettuce) {
            return lettuce.getPassword();
        }
        return properties.getPassword();
    }

    private int passwordLength() {
        String password = effectivePassword();
        return password == null ? 0 : password.length();
    }

    private String passwordSha256Prefix() {
        String password = effectivePassword();
        if (!hasText(password)) {
            return "none";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            return "unavailable";
        }
    }

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

    private static final List<String> PHASE_ORDER = List.of("dns", "tcp", "tls", "pooledInfo", "rawLettuce");

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

    private static final class RawPhaseException extends Exception {
        private final String phase;
        private final List<String> lifecycleEvents;

        private RawPhaseException(String phase, Throwable cause, List<String> lifecycleEvents) {
            super(cause);
            this.phase = phase;
            this.lifecycleEvents = List.copyOf(lifecycleEvents);
        }
    }

    private static NettyCustomizer lifecycleNettyCustomizer(List<String> lifecycleEvents) {
        return new NettyCustomizer() {
            @Override
            public void afterChannelInitialized(io.netty.channel.Channel channel) {
                channel.pipeline().addFirst("redisDiagnosticLifecycle", new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelActive(ChannelHandlerContext context) throws Exception {
                        lifecycleEvents.add("channelActive");
                        context.fireChannelActive();
                    }

                    @Override
                    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) throws Exception {
                        lifecycleEvents.add("nettyException:" + errorCategory(cause));
                        context.fireExceptionCaught(cause);
                    }
                });
                SslHandler sslHandler = channel.pipeline().get(SslHandler.class);
                if (sslHandler != null) {
                    sslHandler.handshakeFuture().addListener(future -> lifecycleEvents.add(
                            future.isSuccess() ? "sslHandshakeComplete" : "sslHandshakeFailed:" + errorCategory(future.cause())));
                }
            }
        };
    }

    private static String renderLifecycleEvent(Event event, long elapsedMillis) {
        String name = event.getClass().getSimpleName();
        if (event instanceof CommandBaseEvent commandEvent && commandEvent.getCommand() != null
                && commandEvent.getCommand().getType() != null) {
            name += ":" + commandEvent.getCommand().getType().name();
        }
        return name + "/" + elapsedMillis + "ms";
    }

    private static String rawFailureDetail(Throwable failure) {
        if (failure instanceof RawPhaseException phaseFailure) {
            Throwable cause = phaseFailure.getCause() == null ? phaseFailure : phaseFailure.getCause();
            String events = phaseFailure.lifecycleEvents.isEmpty()
                    ? "none" : String.join(",", phaseFailure.lifecycleEvents);
            return "phase=" + phaseFailure.phase + ":" + errorCategory(cause) + ":" + simpleName(cause)
                    + "[lifecycleEvents=" + events + "]";
        }
        return errorCategory(failure) + ":" + simpleName(failure);
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
