package com.personal.jobagent.system;

import com.personal.jobagent.config.RedisLettuceConfiguration;
import com.personal.jobagent.config.RedisLettuceConfiguration.InitializationTimeoutLettuceConnectionFactory;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.async.RedisAsyncCommands;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.DirectFieldAccessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Drives the application's own Lettuce connection factory against a disposable
 * Redis 7 container (plain and TLS listeners, password required). A loopback
 * proxy can delay, stall or reject replies, reproducing the slow cold-start
 * connection seen on the CPU-throttled production host without touching the
 * production Redis provider.
 */
class RedisLettuceConnectionLifecycleIT {

    private static final String PASSWORD = "it-" + UUID.randomUUID();
    private static final String[] TRUST_PROPERTIES = {
            "javax.net.ssl.trustStore", "javax.net.ssl.trustStoreType", "javax.net.ssl.trustStorePassword"};
    private static final Map<String, String> savedTrustProperties = new HashMap<>();

    private static Path tlsDirectory;
    private static GenericContainer<?> redis;

    @BeforeAll
    static void startRedis() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
        tlsDirectory = Files.createTempDirectory("redis-it-tls");
        String truststorePassword = UUID.randomUUID().toString();
        Path truststore = generateTlsMaterial(tlsDirectory, truststorePassword);

        // Testcontainers' file copy (commons-compress) needs a newer commons-lang3
        // than Spring Boot 3.3 manages, so the PEM files are written in-container.
        redis = new GenericContainer<>("redis:7-alpine")
                .withExposedPorts(6379, 6380)
                .withEnv("TLS_CERT", Files.readString(tlsDirectory.resolve("redis.crt")))
                .withEnv("TLS_KEY", Files.readString(tlsDirectory.resolve("redis.key")))
                .withEnv("REDIS_PASSWORD", PASSWORD)
                .withCommand("sh", "-c", "mkdir -p /tls"
                        + " && printf '%s\\n' \"$TLS_CERT\" > /tls/redis.crt"
                        + " && printf '%s\\n' \"$TLS_KEY\" > /tls/redis.key"
                        + " && chmod 0755 /tls && chmod 0644 /tls/redis.crt /tls/redis.key"
                        + " && exec docker-entrypoint.sh redis-server --port 6379 --tls-port 6380"
                        + " --tls-cert-file /tls/redis.crt --tls-key-file /tls/redis.key"
                        + " --tls-ca-cert-file /tls/redis.crt --tls-auth-clients no"
                        + " --requirepass \"$REDIS_PASSWORD\"")
                .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));
        redis.start();

        for (String key : TRUST_PROPERTIES) {
            savedTrustProperties.put(key, System.getProperty(key));
        }
        // Lettuce builds a JDK SslContext per connection without an explicit
        // trust manager, so the JVM trust store decides; scoped to this class.
        System.setProperty("javax.net.ssl.trustStore", truststore.toString());
        System.setProperty("javax.net.ssl.trustStoreType", "PKCS12");
        System.setProperty("javax.net.ssl.trustStorePassword", truststorePassword);
    }

    @AfterAll
    static void stopRedis() throws IOException {
        savedTrustProperties.forEach((key, value) -> {
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        });
        if (redis != null) {
            redis.stop();
        }
        if (tlsDirectory != null) {
            try (Stream<Path> files = Files.walk(tlsDirectory)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    @Test
    void tlsFactoryInitializesAuthenticatesAndPings() {
        Assumptions.assumeTrue(Set.of("localhost", "127.0.0.1").contains(redis.getHost()),
                "the test certificate only covers loopback hosts");
        applicationRedis("localhost", redis.getMappedPort(6380), true, PASSWORD).run(context -> {
            assertThat(context).hasNotFailed();
            LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);
            assertThat(factory).isInstanceOf(InitializationTimeoutLettuceConnectionFactory.class);
            assertThat(factory.isUseSsl()).isTrue();
            assertThat(factory.getShareNativeConnection()).isTrue();
            assertThat(uriTimeout(factory)).isEqualTo(Duration.ofSeconds(10));
            assertThat(factory.getTimeout()).isEqualTo(2_000L);

            try (RedisConnection connection = factory.getConnection()) {
                assertThat(connection.ping()).isEqualTo("PONG");
                assertThat(connection.serverCommands().info("server").getProperty("redis_version"))
                        .startsWith("7.");
            }
        });
    }

    @Test
    void wrongOrMissingCredentialsAreReportedAsAuthenticationFailures() {
        applicationRedis(redis.getHost(), redis.getMappedPort(6379), false, "not-the-password").run(context -> {
            Throwable failure = catchThrowable(() -> context.getBean(LettuceConnectionFactory.class).getConnection());
            assertThat(failure).isNotNull();
            assertThat(RedisConnectivityDiagnostics.errorCategory(failure)).isEqualTo("AUTHENTICATION");
        });
        applicationRedis(redis.getHost(), redis.getMappedPort(6379), false, "").run(context -> {
            Throwable failure = catchThrowable(() -> context.getBean(LettuceConnectionFactory.class).getConnection());
            assertThat(failure).isNotNull();
            assertThat(RedisConnectivityDiagnostics.errorCategory(failure)).isEqualTo("AUTHENTICATION");
        });
    }

    @Test
    void sharedNativeConnectionIsReleasedAndReusedAcrossRepeatedAcquisitions() {
        applicationRedis(redis.getHost(), redis.getMappedPort(6379), false, PASSWORD).run(context -> {
            LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);
            Object shared;
            int clientsBefore;
            try (RedisConnection connection = factory.getConnection()) {
                shared = ((LettuceConnection) connection).getNativeConnection();
                clientsBefore = connectedClients(connection);
            }
            for (int attempt = 0; attempt < 25; attempt++) {
                try (RedisConnection connection = factory.getConnection()) {
                    assertThat(connection.ping()).isEqualTo("PONG");
                    assertThat(((LettuceConnection) connection).getNativeConnection()).isSameAs(shared);
                }
            }
            assertThat(((RedisAsyncCommands<?, ?>) shared).getStatefulConnection().isOpen()).isTrue();
            try (RedisConnection connection = factory.getConnection()) {
                assertThat(connectedClients(connection)).isLessThanOrEqualTo(clientsBefore);
            }
        });
    }

    @Test
    void sharedConnectionRecoversAfterFailedInitialAttempts() throws Exception {
        try (DelayingTcpProxy proxy = new DelayingTcpProxy(redis.getHost(), redis.getMappedPort(6379))) {
            proxy.reject(true);
            applicationRedis("127.0.0.1", proxy.port(), false, PASSWORD).run(context -> {
                LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);
                assertThat(catchThrowable(factory::getConnection)).isNotNull();
                assertThat(catchThrowable(factory::getConnection)).isNotNull();

                proxy.reject(false);
                try (RedisConnection connection = factory.getConnection()) {
                    assertThat(connection.ping()).isEqualTo("PONG");
                }
            });
        }
    }

    @Test
    void factoryRestartAndApplicationRestartReconnect() {
        ApplicationContextRunner runner =
                applicationRedis(redis.getHost(), redis.getMappedPort(6379), false, PASSWORD);
        runner.run(context -> {
            LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);
            try (RedisConnection connection = factory.getConnection()) {
                assertThat(connection.ping()).isEqualTo("PONG");
            }
            factory.stop();
            assertThat(factory.isRunning()).isFalse();
            factory.start();
            assertThat(uriTimeout(factory)).isEqualTo(Duration.ofSeconds(10));
            try (RedisConnection connection = factory.getConnection()) {
                assertThat(connection.ping()).isEqualTo("PONG");
            }
        });
        // Closing the context destroys the factory and its ClientResources; a
        // fresh application context must connect cleanly afterwards.
        runner.run(context -> {
            try (RedisConnection connection = context.getBean(LettuceConnectionFactory.class).getConnection()) {
                assertThat(connection.ping()).isEqualTo("PONG");
            }
        });
    }

    @Test
    void slowHandshakeInsideTheInitializationBudgetSucceeds() throws Exception {
        try (DelayingTcpProxy proxy = new DelayingTcpProxy(redis.getHost(), redis.getMappedPort(6379))
                .delayFirstReply(Duration.ofSeconds(4))) {
            applicationRedis("127.0.0.1", proxy.port(), false, PASSWORD).run(context -> {
                long started = System.nanoTime();
                try (RedisConnection connection = context.getBean(LettuceConnectionFactory.class).getConnection()) {
                    assertThat(connection.ping()).isEqualTo("PONG");
                }
                // Slower than the 2s command timeout, inside the 10s initialization budget.
                assertThat(Duration.ofNanos(System.nanoTime() - started))
                        .isBetween(Duration.ofSeconds(4), Duration.ofSeconds(10));
            });
        }
    }

    @Test
    void commandTimeoutStillAppliesAndTheSharedConnectionSurvivesIt() throws Exception {
        try (DelayingTcpProxy proxy = new DelayingTcpProxy(redis.getHost(), redis.getMappedPort(6379))) {
            applicationRedis("127.0.0.1", proxy.port(), false, PASSWORD).run(context -> {
                LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);
                try (RedisConnection connection = factory.getConnection()) {
                    assertThat(connection.ping()).isEqualTo("PONG");
                }

                proxy.stallRepliesFor(Duration.ofMillis(3_500));
                long started = System.nanoTime();
                Throwable timeout;
                try (RedisConnection connection = factory.getConnection()) {
                    timeout = catchThrowable(connection::ping);
                }
                Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
                assertThat(timeout).isNotNull();
                assertThat(RedisConnectivityDiagnostics.errorCategory(timeout)).isEqualTo("TIMEOUT");
                assertThat(elapsed).isBetween(Duration.ofMillis(1_500), Duration.ofMillis(3_400));

                Thread.sleep(2_500);
                try (RedisConnection connection = factory.getConnection()) {
                    assertThat(connection.ping()).isEqualTo("PONG");
                }
            });
        }
    }

    @Test
    void diagnosticReportsASlowButHealthySharedConnectionAsUp() throws Exception {
        try (DelayingTcpProxy proxy = new DelayingTcpProxy(redis.getHost(), redis.getMappedPort(6379))
                .delayFirstReply(Duration.ofSeconds(4))) {
            applicationRedis("127.0.0.1", proxy.port(), false, PASSWORD).run(context -> {
                RedisConnectivityDiagnostics diagnostics = new RedisConnectivityDiagnostics(
                        context.getBean(RedisProperties.class),
                        context.getBean(LettuceConnectionFactory.class),
                        "10s");

                RedisConnectivityDiagnostics.Diagnostic diagnostic = diagnostics.diagnose();

                assertThat(diagnostic.result()).as(diagnostic.phases()).isEqualTo("UP");
                assertThat(diagnostic.errorCategory()).isNull();
                assertThat(diagnostic.phases())
                        .contains("springSharedNativeInfo:OK")
                        .contains("protocolComparison:SKIPPED")
                        .doesNotContain("Connection closed prematurely");
            });
        }
    }

    @Test
    void protocolComparisonReportsEachModesRealOutcome() throws Exception {
        try (DelayingTcpProxy proxy = new DelayingTcpProxy(redis.getHost(), redis.getMappedPort(6379))
                .delayFirstReply(Duration.ofSeconds(4))) {
            applicationRedis("127.0.0.1", proxy.port(), false, PASSWORD).run(context -> {
                RedisConnectivityDiagnostics diagnostics = new RedisConnectivityDiagnostics(
                        context.getBean(RedisProperties.class),
                        context.getBean(LettuceConnectionFactory.class),
                        "10s");

                String detail = diagnostics.rawLettuceProbe(diagnostics.effectiveSettings());

                assertThat(detail)
                        .doesNotContain("Connection closed prematurely")
                        .contains("spring-equivalent{protocol=AUTO")
                        .contains("spring-equivalent.ping:OK")
                        .contains("raw-resp2{protocol=RESP2")
                        .contains("raw-resp2.ping:OK")
                        .contains("raw-auto-2s.FAIL")
                        .contains("Connection initialization timed out after 2 second(s)");
            });
        }
    }

    private static ApplicationContextRunner applicationRedis(String host, int port, boolean ssl, String password) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
                .withUserConfiguration(RedisLettuceConfiguration.class)
                .withPropertyValues(
                        "spring.data.redis.repositories.enabled=false",
                        "spring.data.redis.host=" + host,
                        "spring.data.redis.port=" + port,
                        "spring.data.redis.password=" + password,
                        "spring.data.redis.ssl.enabled=" + ssl,
                        // Production values from application-prod.yml.
                        "spring.data.redis.timeout=2000ms",
                        "spring.data.redis.connect-timeout=2s",
                        "app.redis.connection-initialization-timeout=10s");
    }

    private static Duration uriTimeout(LettuceConnectionFactory factory) {
        return ((RedisURI) new DirectFieldAccessor(factory.getNativeClient()).getPropertyValue("redisURI"))
                .getTimeout();
    }

    private static int connectedClients(RedisConnection connection) {
        return Integer.parseInt(connection.serverCommands().info("clients").getProperty("connected_clients"));
    }

    private static Path generateTlsMaterial(Path directory, String truststorePassword) throws Exception {
        String keystorePassword = UUID.randomUUID().toString();
        Path keystore = directory.resolve("redis.p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows")
                        ? "keytool.exe" : "keytool").toString();
        Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "redis",
                "-keyalg", "EC", "-groupname", "secp256r1",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-validity", "2", "-storetype", "PKCS12", "-keystore", keystore.toString(),
                "-storepass", keystorePassword, "-keypass", keystorePassword)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).as(output).isTrue();
        assertThat(process.exitValue()).as(output).isZero();

        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            keys.load(in, keystorePassword.toCharArray());
        }
        PrivateKey key = (PrivateKey) keys.getKey("redis", keystorePassword.toCharArray());
        Certificate certificate = keys.getCertificate("redis");
        Files.writeString(directory.resolve("redis.key"), pem("PRIVATE KEY", key.getEncoded()));
        Files.writeString(directory.resolve("redis.crt"), pem("CERTIFICATE", certificate.getEncoded()));

        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("redis-it", certificate);
        Path truststore = directory.resolve("truststore.p12");
        try (OutputStream out = Files.newOutputStream(truststore)) {
            trust.store(out, truststorePassword.toCharArray());
        }
        return truststore;
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    /** Loopback TCP proxy that can delay the first reply, stall replies, or reject connections. */
    static final class DelayingTcpProxy implements AutoCloseable {
        private final ServerSocket server;
        private final String upstreamHost;
        private final int upstreamPort;
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();
        private final ExecutorService threads = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "redis-it-proxy");
            thread.setDaemon(true);
            return thread;
        });
        private volatile Duration firstReplyDelay = Duration.ZERO;
        private volatile boolean rejecting;
        private volatile long stallUntilMillis;

        DelayingTcpProxy(String upstreamHost, int upstreamPort) throws IOException {
            this.upstreamHost = upstreamHost;
            this.upstreamPort = upstreamPort;
            this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            threads.execute(this::acceptLoop);
        }

        DelayingTcpProxy delayFirstReply(Duration delay) {
            this.firstReplyDelay = delay;
            return this;
        }

        void reject(boolean reject) {
            this.rejecting = reject;
        }

        void stallRepliesFor(Duration duration) {
            this.stallUntilMillis = System.currentTimeMillis() + duration.toMillis();
        }

        int port() {
            return server.getLocalPort();
        }

        private void acceptLoop() {
            while (!server.isClosed()) {
                Socket client;
                try {
                    client = server.accept();
                } catch (IOException closed) {
                    return;
                }
                sockets.add(client);
                if (rejecting) {
                    closeQuietly(client);
                    continue;
                }
                try {
                    Socket upstream = new Socket(upstreamHost, upstreamPort);
                    sockets.add(upstream);
                    Duration delay = firstReplyDelay;
                    threads.execute(() -> pump(client, upstream, Duration.ZERO, false));
                    threads.execute(() -> pump(upstream, client, delay, true));
                } catch (IOException upstreamUnavailable) {
                    closeQuietly(client);
                }
            }
        }

        private void pump(Socket from, Socket to, Duration firstChunkDelay, boolean replies) {
            byte[] buffer = new byte[8192];
            boolean first = true;
            try {
                InputStream in = from.getInputStream();
                OutputStream out = to.getOutputStream();
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (first && !firstChunkDelay.isZero()) {
                        Thread.sleep(firstChunkDelay.toMillis());
                    }
                    first = false;
                    while (replies && System.currentTimeMillis() < stallUntilMillis) {
                        Thread.sleep(10);
                    }
                    out.write(buffer, 0, read);
                    out.flush();
                }
            } catch (Exception closed) {
                // Either side closed; tear the pair down below.
            } finally {
                closeQuietly(from);
                closeQuietly(to);
            }
        }

        @Override
        public void close() {
            closeQuietly(server);
            sockets.forEach(DelayingTcpProxy::closeQuietly);
            threads.shutdownNow();
        }

        private static void closeQuietly(Closeable closeable) {
            try {
                closeable.close();
            } catch (IOException ignored) {
                // Test proxy cleanup only.
            }
        }
    }
}
