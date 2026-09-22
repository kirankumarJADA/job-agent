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
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.ConnectionFuture;
import io.lettuce.core.protocol.ProtocolVersion;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.event.Event;
import io.lettuce.core.event.command.CommandBaseEvent;
import io.lettuce.core.resource.DefaultClientResources;
import io.lettuce.core.resource.DnsResolvers;
import io.lettuce.core.resource.NettyCustomizer;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelDuplexHandler;
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
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
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
 *   springSharedNativeInfo -> Spring shared-native round-trip through the
 *                 configured RedisConnectionFactory
 *                 (the same connection path Spring Boot's RedisHealthIndicator uses),
 *                 with sub-phases acquire (shared connection initialization + AUTH),
 *                 info (command round-trip), and release (connection close)
 * </pre>
 *
 * <p>Each phase has a strict timeout. The diagnostic never blocks startup and
 * never logs credentials, tokens, connection strings, or data contents.
 */
@Component
public class RedisConnectivityDiagnostics {
    private static final Logger log = LoggerFactory.getLogger(RedisConnectivityDiagnostics.class);

    /** Overall asynchronous safety cap retained for diagnostic compatibility; phases have their own budgets. */
    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(20);
    static final Duration DNS_TIMEOUT = Duration.ofSeconds(2);
    static final Duration TCP_TIMEOUT = Duration.ofSeconds(2);
    static final Duration TLS_TIMEOUT = Duration.ofSeconds(3);
    static final Duration SPRING_FACTORY_INFO_TIMEOUT = Duration.ofSeconds(3);
    static final Duration RAW_PHASE_TIMEOUT = Duration.ofSeconds(2);
    static final Duration RAW_TOTAL_TIMEOUT = Duration.ofSeconds(10);
    static final Duration LATE_EVENT_GRACE = Duration.ofMillis(1500);

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
        // Every external phase has its own bound. Do not apply CompletableFuture
        // orTimeout() to the whole diagnosis: that only times out observation
        // and can leave a shared Lettuce operation running without a retained
        // completion path. The argument is retained for source compatibility;
        // phase-specific bounds are authoritative.
        return CompletableFuture.supplyAsync(this::diagnose, PROBE_EXECUTOR);
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
        return diagnose(hooks(), SPRING_FACTORY_INFO_TIMEOUT);
    }

    /** Overridable in tests so the asynchronous listener path stays fakeable. */
    Hooks hooks() {
        return RealHooks.INSTANCE;
    }

    /**
     * Package-private overloads let unit tests fake the network and bound the
     * Spring factory phase, so tests never touch real endpoints.
     */
    Diagnostic diagnose(Hooks hooks, Duration springSharedNativeInfoTimeout) {
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

        // ---- Phase 4: Spring's shared-native connection ------------------
        // This is deliberately named springSharedNativeInfo: Spring Boot does
        // not enable Commons Pool here. With shareNativeConnection=true,
        // getConnection() initializes one native connection under
        // LettuceConnectionFactory's internal lock. The diagnostic observes
        // this same path with a bounded wait, never cancels the operation, and
        // retains a completion callback that logs any late terminal result.
        SpringFactoryObservation spring = observeSpringFactoryInfo(springSharedNativeInfoTimeout);
        phases.add(spring.phase());

        long rawStart = System.nanoTime();
        try {
            String rawDetail = rawLettuceProbe(s);
            phases.add(Phase.ok("protocolComparison", elapsed(rawStart), rawDetail));
        } catch (Exception rawFailure) {
            phases.add(Phase.fail("protocolComparison", elapsed(rawStart),
                    rawFailureDetail(rawFailure)));
        }
        return spring.failure() == null
                ? up(s, phases)
                : down(s, phases, errorCategory(spring.failure()));
    }

    /**
     * Executes the real Spring Data path in a retained observation future.
     * The caller waits only for the diagnostic observation budget and never
     * cancels the shared-native operation. If it runs late, the retained
     * completion callback records its eventual terminal result, so no operation
     * is abandoned without a recorded outcome.
     */
    private SpringFactoryObservation observeSpringFactoryInfo(Duration timeout) {
        List<Phase> phases = Collections.synchronizedList(new ArrayList<>());
        long started = System.nanoTime();
        CompletableFuture<SpringFactoryOutcome> operation = CompletableFuture.supplyAsync(() -> {
            try {
                springFactoryProbe(phases);
                return new SpringFactoryOutcome(null);
            } catch (Throwable failure) {
                return new SpringFactoryOutcome(failure);
            }
        }, PROBE_EXECUTOR);
        operation.whenComplete((outcome, callbackFailure) -> {
            Throwable failure = callbackFailure != null
                    ? callbackFailure
                    : outcome == null ? null : outcome.failure();
            log.info("Redis Spring shared-native comparison completed: elapsedMs={}, result={}, "
                            + "errorCategory={}, phases={}",
                    elapsed(started), failure == null ? "UP" : "DOWN",
                    failure == null ? "none" : errorCategory(failure), renderSubPhases(phases));
        });
        try {
            SpringFactoryOutcome outcome = operation.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            Throwable failure = outcome.failure();
            if (failure == null) {
                return new SpringFactoryObservation(
                        Phase.ok("springSharedNativeInfo", elapsed(started), renderSubPhases(phases)), null);
            }
            if (phases.isEmpty()) {
                phases.add(Phase.fail("factoryAcquire", elapsed(started), simpleName(failure)));
            }
            return new SpringFactoryObservation(
                    Phase.fail("springSharedNativeInfo", elapsed(started), renderSubPhases(phases)), failure);
        } catch (TimeoutException timeoutFailure) {
            phases.add(Phase.fail("factoryAcquire", elapsed(started), "observationTimeout,inFlight=true,completionTracked=true"));
            return new SpringFactoryObservation(
                    Phase.fail("springSharedNativeInfo", elapsed(started), renderSubPhases(phases)), timeoutFailure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            phases.add(Phase.fail("factoryAcquire", elapsed(started), "interrupted"));
            return new SpringFactoryObservation(
                    Phase.fail("springSharedNativeInfo", elapsed(started), renderSubPhases(phases)), interrupted);
        } catch (ExecutionException execution) {
            Throwable failure = execution.getCause() == null ? execution : execution.getCause();
            phases.add(Phase.fail("factoryAcquire", elapsed(started), simpleName(failure)));
            return new SpringFactoryObservation(
                    Phase.fail("springSharedNativeInfo", elapsed(started), renderSubPhases(phases)), failure);
        }
    }

    private boolean springFactoryProbe(List<Phase> phases) throws Exception {
        long acquireStart = System.nanoTime();
        RedisConnection connection = null;
        try {
            connection = connectionFactory.getConnection();
            phases.add(Phase.ok("factoryAcquire", elapsed(acquireStart), null));
        } catch (Exception failure) {
            phases.add(Phase.fail("factoryAcquire", elapsed(acquireStart),
                    simpleName(failure) + ",causeChain=" + safeCauseChain(failure, null)));
            throw failure;
        }
        long infoStart = System.nanoTime();
        try {
            if (connection.serverCommands().info() == null) {
                phases.add(Phase.fail("factoryInfo", elapsed(infoStart), "empty-response"));
                throw new IllegalStateException("Redis INFO returned no response");
            }
            phases.add(Phase.ok("factoryInfo", elapsed(infoStart), null));
        } catch (Exception failure) {
            if (!hasPhaseNamed(phases, "factoryInfo")) {
                phases.add(Phase.fail("factoryInfo", elapsed(infoStart),
                        simpleName(failure) + ",causeChain=" + safeCauseChain(failure, null)));
            }
            throw failure;
        } finally {
            if (connection != null) {
                long releaseStart = System.nanoTime();
                closeConnectionQuietly(connection);
                phases.add(Phase.ok("factoryRelease", elapsed(releaseStart), null));
            }
        }
        return true;
    }

    /**
     * Runs three independent authenticated comparisons. The first is the
     * Spring-equivalent Lettuce construction (automatic protocol plus the same
     * two-second URI initialization timeout), followed by RESP2 and automatic
     * protocol controls. Each result is retained independently.
     */
    String rawLettuceProbe(ConnectionSettings settings) throws Exception {
        String password = effectivePassword();
        if (!hasText(password)) {
            return "protocolComparison.SKIPPED[authentication-not-configured]";
        }
        List<String> results = new ArrayList<>();
        results.add(runProtocolComparison(settings, "spring-equivalent", null, password, Duration.ofSeconds(2)));
        results.add(runProtocolComparison(settings, "raw-resp2", ProtocolVersion.RESP2, password, null));
        results.add(runProtocolComparison(settings, "raw-auto-2s", null, password, Duration.ofSeconds(2)));
        return String.join(";", results);
    }

    private String runProtocolComparison(ConnectionSettings settings, String mode,
                                         ProtocolVersion protocol, String password,
                                         Duration uriTimeout) {
        try {
            return rawLettuceAuthMode(settings, mode, null, password, false, protocol, uriTimeout);
        } catch (Exception failure) {
            return mode + ".FAIL[" + rawFailureDetail(failure) + "]";
        }
    }

    private String rawLettuceAuthMode(ConnectionSettings settings, String mode,
                                      String username, String password, boolean plain,
                                      ProtocolVersion protocol, Duration uriTimeout) throws Exception {
        // Credentials must be part of Lettuce's URI so its connection
        // initializer performs AUTH before the connection future completes.
        // This is the same placement used by Spring's standalone factory.
        RedisURI uri = authenticatedUri(settings, username, password, uriTimeout);
        StatefulRedisConnection<String, String> connection = null;
        List<String> phases = new ArrayList<>();
        List<String> lifecycleEvents = new CopyOnWriteArrayList<>();
        AtomicReference<Channel> lifecycleChannel = new AtomicReference<>();
        long lifecycleStarted = System.nanoTime();
        // Match the application's effective resolver. Using Lettuce's default
        // unresolved Netty resolver here would test a different path than the
        // Spring factory and could falsely attribute a resolver failure to the
        // protocol handshake.
        DefaultClientResources resources = probeClientResources(!plain, lifecycleEvents, lifecycleStarted, lifecycleChannel);
        RedisClient client = RedisClient.create(resources, uri);
        ClientOptions.Builder clientOptions = ClientOptions.builder()
                .socketOptions(SocketOptions.builder().connectTimeout(RAW_PHASE_TIMEOUT).build())
                .timeoutOptions(TimeoutOptions.enabled());
        if (protocol != null) {
            clientOptions.protocolVersion(protocol);
        }
        client.setOptions(clientOptions.build());
        lifecycleEvents.add("uri=" + describeUri(uri, protocol == null ? "AUTO" : protocol.name())
                + ",resolver=JVM_DEFAULT,instrumented=" + !plain);
        var eventSubscription = resources.eventBus().get()
                .subscribe(event -> lifecycleEvents.add(renderLifecycleEvent(event,
                        elapsed(lifecycleStarted))));
        try {
            long started = System.nanoTime();
            ConnectionFuture<StatefulRedisConnection<String, String>> connectionFuture = null;
            CountDownLatch connectionTerminal = new CountDownLatch(1);
            AtomicReference<Throwable> terminalFailure = new AtomicReference<>();
            AtomicReference<String> terminalResult = new AtomicReference<>("pending");
            try {
                connectionFuture = client.connectAsync(StringCodec.UTF8, uri);
                lifecycleEvents.add("connectionFutureCreated/" + elapsed(lifecycleStarted) + "ms");
                connectionFuture.whenComplete((value, failure) -> {
                    if (failure == null) {
                        terminalResult.set("completed");
                        lifecycleEvents.add("connectionFutureCompleted/" + elapsed(lifecycleStarted) + "ms");
                    } else {
                        terminalFailure.set(failure);
                        terminalResult.set("failed");
                        lifecycleEvents.add("connectionFutureFailed/" + elapsed(lifecycleStarted) + "ms["
                                + errorCategory(failure) + "]");
                        lifecycleEvents.add("connectionFutureFailureChain="
                                + safeCauseChain(failure, lifecycleChannel.get()));
                    }
                    connectionTerminal.countDown();
                });
                ConnectionFuture<StatefulRedisConnection<String, String>> future = connectionFuture;
                connection = observeConnectionFuture(future, RAW_PHASE_TIMEOUT);
                phases.add(mode + ".connect:OK/" + elapsed(started) + "ms");
                // Credentials are attached to the URI. Lettuce therefore
                // performs AUTH during its connection initializer, before the
                // ConnectionFuture completes; a second manual AUTH would not
                // be equivalent to Spring and can produce false failures.
                phases.add(mode + ".auth:OK/" + elapsed(started) + "ms[initializer]");
            } catch (Exception failure) {
                if (connectionFuture != null) {
                    // Never cancel Lettuce's future here. This timeout belongs
                    // only to the diagnostic observer; cancelling the underlying
                    // future would turn a real late handshake result into our
                    // own CancellationException.
                    awaitLateLifecycleEvents(connectionTerminal, lifecycleEvents, LATE_EVENT_GRACE);
                }
                Throwable eventualFailure = terminalFailure.get();
                Throwable root = eventualFailure == null ? failure : eventualFailure;
                String authStage = errorCategory(root).equals("AUTHENTICATION")
                        ? mode + ".auth:FAIL[" + safeCauseChain(root, lifecycleChannel.get()) + "]"
                        : mode + ".auth:NOT_REACHED";
                throw new RawPhaseException(mode + ".connect",
                        root, lifecycleEvents, terminalResult.get(), authStage);
            }

            StatefulRedisConnection<String, String> rawConnection = connection;
            started = System.nanoTime();
            try {
                String pingResponse = bounded(RAW_PHASE_TIMEOUT, () -> rawConnection.sync().ping());
                phases.add(mode + ".ping:OK/" + elapsed(started) + "ms[response="
                        + (pingResponse == null ? "empty" : "received") + "]");
            } catch (Exception failure) {
                throw new RawPhaseException(mode + ".ping", failure, lifecycleEvents,
                        "not-applicable", String.join(",", phases));
            }
            String protocolLabel = protocol == null ? "AUTO" : protocol.name();
            return mode + "{protocol=" + protocolLabel
                    + ",uriTimeout=" + uri.getTimeout().toMillis() + "ms,phases="
                    + String.join(",", phases) + "}";
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

    ConnectionSettings effectiveSettings() {
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
     * Phase-level category for the direct socket phases. The shared-native phase uses
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
                    || message.contains("invalid username-password pair")
                    || message.contains("err auth")
                    || message.contains(" auth ")) {
                return "AUTHENTICATION";
            }
            if (type.contains("ssl") || type.contains("tls") || type.contains("certificate")) {
                return "TLS";
            }
            if (current instanceof CancellationException) {
                return "FUTURE_CANCELLED";
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

    private static final List<String> PHASE_ORDER = List.of("dns", "tcp", "tls", "springSharedNativeInfo", "protocolComparison");

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

    static <T> T observeConnectionFuture(ConnectionFuture<T> future, Duration timeout) throws Exception {
        // bounded() may cancel its private waiting task on timeout, but this
        // task only calls Future.get(); it never owns or cancels the Lettuce
        // ConnectionFuture itself.
        return bounded(timeout, future::get);
    }

    static void awaitLateLifecycleEvents(CountDownLatch terminal,
                                          List<String> lifecycleEvents,
                                          Duration grace) {
        long started = System.nanoTime();
        try {
            terminal.await(grace.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            lifecycleEvents.add("lateEventGraceInterrupted/" + elapsed(started) + "ms");
            return;
        }
        lifecycleEvents.add("lateEventGraceComplete/" + elapsed(started) + "ms");
    }

    private static final class RawPhaseException extends Exception {
        private final String phase;
        /** Deliberately live until the bounded late-event grace has completed. */
        private final List<String> lifecycleEvents;
        private final String eventualResult;
        private final String phaseTrail;

        private RawPhaseException(String phase, Throwable cause, List<String> lifecycleEvents,
                                  String eventualResult) {
            this(phase, cause, lifecycleEvents, eventualResult, "");
        }

        private RawPhaseException(String phase, Throwable cause, List<String> lifecycleEvents,
                                  String eventualResult, String phaseTrail) {
            super(cause);
            this.phase = phase;
            this.lifecycleEvents = lifecycleEvents;
            this.eventualResult = eventualResult;
            this.phaseTrail = phaseTrail;
        }

        private RawPhaseException(String phase, Throwable cause, List<String> lifecycleEvents) {
            this(phase, cause, lifecycleEvents, "not-applicable");
        }
    }

    private record SpringFactoryOutcome(Throwable failure) {
    }

    private record SpringFactoryObservation(Phase phase, Throwable failure) {
    }

    private static final class MultiModeFailure extends Exception {
        private final String modeResults;

        private MultiModeFailure(Throwable cause, String modeResults) {
            super(cause);
            this.modeResults = modeResults;
        }
    }

    private static NettyCustomizer lifecycleNettyCustomizer(List<String> lifecycleEvents,
                                                              long lifecycleStarted,
                                                              AtomicReference<Channel> lifecycleChannel) {
        return new NettyCustomizer() {
            @Override
            public void afterBootstrapInitialized(Bootstrap bootstrap) {
                markLifecycle(lifecycleEvents, lifecycleStarted, "bootstrapPrepared/" + safeAddress(bootstrap.config().remoteAddress())
                        + ",options=" + bootstrap.config().options().keySet());
                // Deliberately NO channelFactory decoration here: Lettuce's
                // ConnectionBuilder.configureBootstrap has already set the
                // channel class, and Bootstrap.channelFactory(...) in Netty
                // 4.1.113 throws IllegalStateException("channelFactory set
                // already") for a re-set on BOTH overloads. An A/B experiment
                // against a real Redis proved the decoration broke every
                // instrumented connect attempt while plain Lettuce succeeded.
                // Channel creation is still observable via
                // afterChannelInitialized below (it fires immediately after
                // Lettuce's ChannelInitializer.initChannel).
            }

            @Override
            public void afterChannelInitialized(Channel channel) {
                lifecycleChannel.set(channel);
                markLifecycle(lifecycleEvents, lifecycleStarted, "channelInitialized/" + addressSummary(channel));
                channel.pipeline().addFirst("redisDiagnosticLifecycle", new ChannelDuplexHandler() {
                    @Override
                    public void channelRegistered(ChannelHandlerContext context) throws Exception {
                        markLifecycle(lifecycleEvents, lifecycleStarted, "channelRegistered/" + addressSummary(context.channel()));
                        context.fireChannelRegistered();
                    }

                    @Override
                    public void channelActive(ChannelHandlerContext context) throws Exception {
                        markLifecycle(lifecycleEvents, lifecycleStarted, "channelActive/" + addressSummary(context.channel()));
                        attachSslObservation(context.channel(), lifecycleEvents, lifecycleStarted);
                        context.fireChannelActive();
                    }

                    @Override
                    public void channelInactive(ChannelHandlerContext context) throws Exception {
                        markLifecycle(lifecycleEvents, lifecycleStarted, "channelInactive/" + addressSummary(context.channel()));
                        context.fireChannelInactive();
                    }

                    @Override
                    public void write(ChannelHandlerContext context, Object message,
                                      io.netty.channel.ChannelPromise promise) throws Exception {
                        if (message instanceof io.lettuce.core.protocol.RedisCommand<?, ?, ?> command
                                && command.getType() != null) {
                            markLifecycle(lifecycleEvents, lifecycleStarted,
                                    "protocolCommandSent:" + command.getType().name());
                        }
                        context.write(message, promise);
                    }

                    @Override
                    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) throws Exception {
                        markLifecycle(lifecycleEvents, lifecycleStarted, "nettyException:" + errorCategory(cause));
                        markLifecycle(lifecycleEvents, lifecycleStarted,
                                "nettyExceptionChain=" + safeCauseChain(cause, context.channel()));
                        context.fireExceptionCaught(cause);
                    }
                });
                channel.eventLoop().execute(() -> {
                    if (channel.pipeline().get(SslHandler.class) != null) {
                        markLifecycle(lifecycleEvents, lifecycleStarted, "sslHandlerInstalled");
                        attachSslObservation(channel, lifecycleEvents, lifecycleStarted);
                    }
                    if (channel.pipeline().get(io.lettuce.core.protocol.RedisHandshakeHandler.class) != null) {
                        markLifecycle(lifecycleEvents, lifecycleStarted, "redisHandshakeHandlerInstalled");
                    }
                });
            }
        };
    }

    private static void attachSslObservation(Channel channel, List<String> events, long lifecycleStarted) {
        SslHandler sslHandler = channel.pipeline().get(SslHandler.class);
        if (sslHandler == null) {
            return;
        }
        markLifecycle(events, lifecycleStarted, "sslHandshakeStarted");
        sslHandler.handshakeFuture().addListener(future -> markLifecycle(events, lifecycleStarted,
                future.isSuccess()
                        ? "sslHandshakeComplete"
                        : "sslHandshakeFailed:" + errorCategory(future.cause())));
    }

    /**
     * Builds the exact {@link ClientResources} used by the instrumented raw
     * probe. Exposed for A/B regression tests: {@code instrument=false}
     * produces resolver-only resources with no lifecycle NettyCustomizer, so
     * tests can construct the identical client minus instrumentation and
     * prove the customizer cannot alter channel initialization.
     */
    static DefaultClientResources probeClientResources(boolean instrument,
                                                       List<String> lifecycleEvents,
                                                       long lifecycleStartedNanos) {
        return probeClientResources(instrument, lifecycleEvents, lifecycleStartedNanos,
                new AtomicReference<>());
    }

    static DefaultClientResources probeClientResources(boolean instrument,
                                                       List<String> lifecycleEvents,
                                                       long lifecycleStartedNanos,
                                                       AtomicReference<Channel> lifecycleChannel) {
        DefaultClientResources.Builder builder = DefaultClientResources.builder()
                .dnsResolver(DnsResolvers.JVM_DEFAULT);
        if (instrument) {
            builder.nettyCustomizer(lifecycleNettyCustomizer(lifecycleEvents, lifecycleStartedNanos,
                    lifecycleChannel));
        }
        return builder.build();
    }

    static RedisURI authenticatedUri(ConnectionSettings settings, String username, String password) {
        return authenticatedUri(settings, username, password, null);
    }

    static RedisURI authenticatedUri(ConnectionSettings settings, String username, String password,
                                     Duration uriTimeout) {
        RedisURI.Builder builder = RedisURI.builder()
                .withHost(settings.host())
                .withPort(settings.port())
                .withSsl(settings.sslEnabled())
                .withVerifyPeer(settings.sslEnabled());
        if (uriTimeout != null) {
            builder.withTimeout(uriTimeout);
        }
        if (hasText(password)) {
            if (username == null) {
                builder.withPassword(password.toCharArray());
            } else {
                builder.withAuthentication(username, password.toCharArray());
            }
        }
        return builder.build();
    }

    static String describeUri(RedisURI uri) {
        return describeUri(uri, "RESP2");
    }

    static String describeUri(RedisURI uri, String protocol) {
        String scheme = uri.isSsl() ? "rediss" : "redis";
        return "scheme=" + scheme + ",host=" + uri.getHost() + ",port=" + uri.getPort()
                + ",ssl=" + uri.isSsl() + ",protocol=" + protocol
                + ",uriTimeout=" + uri.getTimeout().toMillis() + "ms";
    }

    private static void markLifecycle(List<String> events, long started, String event) {
        events.add(event + "/" + elapsed(started) + "ms");
    }

    private static String addressSummary(Channel channel) {
        return "remote=" + safeAddress(channel.remoteAddress()) + ",local=" + safeAddress(channel.localAddress());
    }

    private static String safeAddress(SocketAddress address) {
        if (address == null) {
            return "none";
        }
        if (address instanceof InetSocketAddress inet) {
            return inet.getHostString() + ":" + inet.getPort();
        }
        return address.getClass().getSimpleName();
    }

    private static String renderLifecycleEvent(Event event, long elapsedMillis) {
        String name = event.getClass().getSimpleName();
        if (event instanceof CommandBaseEvent commandEvent && commandEvent.getCommand() != null
                && commandEvent.getCommand().getType() != null) {
            name += ":" + commandEvent.getCommand().getType().name();
        }
        return name + "/" + elapsedMillis + "ms";
    }

    static String safeCauseChain(Throwable failure, Channel channel) {
        StringBuilder chain = new StringBuilder();
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth++ < 8) {
            if (chain.length() > 0) {
                chain.append("<-");
            }
            chain.append(current.getClass().getSimpleName());
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                chain.append('[').append(sanitizeDiagnosticMessage(message)).append(']');
            }
            current = current.getCause();
        }
        if (channel != null) {
            Throwable initFailure = channel.attr(io.lettuce.core.ConnectionBuilder.INIT_FAILURE).get();
            if (initFailure != null) {
                chain.append(",INIT_FAILURE=").append(initFailure.getClass().getSimpleName())
                        .append('[').append(sanitizeDiagnosticMessage(initFailure.getMessage())).append(']');
            }
            Object handshakeHandler = channel.pipeline().get(io.lettuce.core.protocol.RedisHandshakeHandler.class);
            chain.append(",handshakeHandler=").append(handshakeHandler == null ? "absent" : "present");
        }
        return chain.toString();
    }

    private static String sanitizeDiagnosticMessage(String message) {
        String sanitized = message.replaceAll("(?i)(password|token|secret|authorization)\\s*[=:]\\s*[^,;\\s]+",
                "$1=<redacted>");
        return sanitized.length() > 240 ? sanitized.substring(0, 240) + "..." : sanitized;
    }

    static String rawFailureDetail(Throwable failure) {
        if (failure instanceof MultiModeFailure modeFailure) {
            return "modeResults=" + modeFailure.modeResults;
        }
        if (failure instanceof RawPhaseException phaseFailure) {
            Throwable cause = phaseFailure.getCause() == null ? phaseFailure : phaseFailure.getCause();
            String events = phaseFailure.lifecycleEvents.isEmpty()
                    ? "none" : String.join(",", phaseFailure.lifecycleEvents);
            return "phase=" + phaseFailure.phase + ":" + errorCategory(cause) + ":" + simpleName(cause)
                    + "[causeChain=" + safeCauseChain(cause, null)
                    + ",eventualResult=" + phaseFailure.eventualResult
                    + (phaseFailure.phaseTrail.isBlank() ? "" : ",completed=" + phaseFailure.phaseTrail)
                    + ",lifecycleEvents=" + events + "]";
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

    record ConnectionSettings(String client, String host, int port,
                                      boolean sslEnabled, boolean authenticationConfigured) {
    }
}
