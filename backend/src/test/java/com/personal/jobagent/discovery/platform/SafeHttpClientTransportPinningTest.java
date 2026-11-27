package com.personal.jobagent.discovery.platform;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIMatcher;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.StandardConstants;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Stage 3I Phase 6, A5: backend SSRF transport pinning proved at the socket level.
 *
 * <p>Two real HTTPS servers listen on the same port on two loopback addresses:
 * the "validated" server on 127.0.0.1 and the "attacker" (standing in for a
 * private or metadata address) on 127.0.0.2. A hostile JVM-wide DNS resolver
 * ({@link HostileDnsResolverProvider}) answers every lookup of a
 * {@code *.robin-rebind.test} name with the attacker address — so if the
 * transport resolved the name again after the guard validated 127.0.0.1, the
 * JDK would connect to the attacker. The assertions check which socket was
 * actually connected, how many DNS lookups the JVM made, the SNI the server
 * received, and that certificate hostname verification still runs through the
 * pinned tunnel. A control test shows the same hostile DNS does redirect the
 * unpinned JDK path, so the adversary is real.
 *
 * <p>Certificates are generated per run with the JDK's own {@code keytool};
 * nothing is committed.
 */
class SafeHttpClientTransportPinningTest {

    private static final char[] PASSWORD = "robin-test-only".toCharArray();

    @TempDir
    static Path keys;

    /** A server SSLContext whose certificate is for {@code host} (plus any extra subject alternative names). */
    private static SSLContext serverFor(String host, String... alsoValidFor) throws Exception {
        Path store = keys.resolve(host + ".p12");
        if (!Files.exists(store)) {
            String keytool = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool").toString();
            Process p = new ProcessBuilder(keytool, "-genkeypair", "-alias", "tls", "-keyalg", "EC", "-groupname", "secp256r1",
                    "-dname", "CN=" + host, "-ext", "SAN=dns:" + host + sans(alsoValidFor), "-validity", "2", "-storetype", "PKCS12",
                    "-keystore", store.toString(), "-storepass", new String(PASSWORD), "-keypass", new String(PASSWORD))
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(p.waitFor(60, TimeUnit.SECONDS)).as(out).isTrue();
            assertThat(p.exitValue()).as(out).isZero();
        }
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(store)) {
            ks.load(in, PASSWORD);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, PASSWORD);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }

    private static String sans(String... names) {
        StringBuilder sb = new StringBuilder();
        for (String n : names) sb.append(",dns:").append(n);
        return sb.toString();
    }

    /** A client SSLContext that trusts exactly the given servers' self-signed certificates. */
    private static SSLContext clientTrusting(String... hosts) throws Exception {
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        for (String host : hosts) {
            serverFor(host);
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(keys.resolve(host + ".p12"))) {
                ks.load(in, PASSWORD);
            }
            Certificate cert = ks.getCertificate("tls");
            trust.setCertificateEntry(host, cert);
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }

    /** A real HTTPS server that records the SNI of every handshake and counts requests. */
    private static final class Server implements AutoCloseable {
        final HttpsServer https;
        final List<String> sni = new CopyOnWriteArrayList<>();
        final AtomicInteger requests = new AtomicInteger();

        Server(InetAddress bind, int port, String certHost, String body, String... alsoValidFor) throws Exception {
            https = HttpsServer.create(new InetSocketAddress(bind, port), 0);
            SSLContext ctx = serverFor(certHost, alsoValidFor);
            https.setHttpsConfigurator(new HttpsConfigurator(ctx) {
                @Override
                public void configure(HttpsParameters params) {
                    SSLParameters sp = ctx.getDefaultSSLParameters();
                    sp.setSNIMatchers(List.of(new SNIMatcher(StandardConstants.SNI_HOST_NAME) {
                        @Override
                        public boolean matches(SNIServerName name) {
                            sni.add(((SNIHostName) name).getAsciiName());
                            return true;
                        }
                    }));
                    params.setSSLParameters(sp);
                }
            });
            https.createContext("/", exchange -> {
                requests.incrementAndGet();
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            });
            https.start();
        }

        int port() {
            return https.getAddress().getPort();
        }

        @Override
        public void close() {
            https.stop(0);
        }
    }

    /** A bare TCP listener on the attacker address that only counts connection attempts. */
    private static final class Sentinel implements AutoCloseable {
        final ServerSocket socket;
        final AtomicInteger accepts = new AtomicInteger();

        Sentinel(int port) throws IOException {
            socket = new ServerSocket(port, 50, InetAddress.getByAddress(HostileDnsResolverProvider.ATTACKER));
            Thread.ofVirtual().start(() -> {
                while (!socket.isClosed()) {
                    try (Socket s = socket.accept()) {
                        accepts.incrementAndGet();
                    } catch (IOException ignored) {
                        // closed
                    }
                }
            });
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private final List<AutoCloseable> open = new ArrayList<>();

    @AfterEach
    void closeAll() throws Exception {
        for (AutoCloseable c : open) c.close();
        open.clear();
    }

    private static String host(String label) {
        return label + "-" + UUID.randomUUID().toString().substring(0, 8) + HostileDnsResolverProvider.SUFFIX;
    }

    private static final InetAddress VALIDATED;

    static {
        try {
            VALIDATED = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @BeforeAll
    static void hostileDnsIsInstalled() throws Exception {
        String probe = host("probe");
        assertThat(InetAddress.getByName(probe).getAddress()).isEqualTo(HostileDnsResolverProvider.ATTACKER);
        assertThat(HostileDnsResolverProvider.lookups(probe)).isEqualTo(1);
    }

    @Test
    void pinnedHttpsConnectsOnlyToTheValidatedAddressWhileDnsWouldRebindToTheAttacker() throws Exception {
        String h = host("pinned");
        Server legit = new Server(VALIDATED, 0, h, "{\"server\":\"validated\"}");
        open.add(legit);
        Sentinel attacker = new Sentinel(legit.port());
        open.add(attacker);
        SafeHttpClient.JdkTransport transport = new SafeHttpClient.JdkTransport(clientTrusting(h));

        SafeHttpClient.RawResponse response = transport.exchangePinned("GET", URI.create("https://" + h + ":" + legit.port() + "/x"),
                List.of(VALIDATED), Map.of("Accept", "application/json"), null, Duration.ofSeconds(10), 4096);

        assertThat(response.status()).isEqualTo(200);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).contains("validated");
        assertThat(legit.requests.get()).isEqualTo(1);
        // The attacker address received no TCP connection at all, and the JVM never asked DNS for the name.
        assertThat(attacker.accepts.get()).isZero();
        assertThat(HostileDnsResolverProvider.lookups(h)).isZero();
        // TLS ran end to end with the logical host name: SNI carried it, and the certificate for it verified.
        assertThat(legit.sni).containsOnly(h);
    }

    @Test
    void controlTheSameHostileDnsDoesRedirectTheUnpinnedJdkPath() throws Exception {
        String h = host("control");
        Server legit = new Server(VALIDATED, 0, h, "{\"server\":\"validated\"}");
        open.add(legit);
        Server attacker = new Server(InetAddress.getByAddress(HostileDnsResolverProvider.ATTACKER), legit.port(), h,
                "{\"server\":\"attacker\"}");
        open.add(attacker);
        SafeHttpClient.JdkTransport transport = new SafeHttpClient.JdkTransport(clientTrusting(h));

        SafeHttpClient.RawResponse response = transport.exchange("GET", URI.create("https://" + h + ":" + legit.port() + "/x"),
                Map.of("Accept", "application/json"), null, Duration.ofSeconds(10), 4096);

        // Without pinning, the JDK resolves the name itself and the rebinding attack succeeds.
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).contains("attacker");
        assertThat(attacker.requests.get()).isEqualTo(1);
        assertThat(legit.requests.get()).isZero();
        assertThat(HostileDnsResolverProvider.lookups(h)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void certificateHostnameVerificationStillAppliesThroughThePinnedTunnel() throws Exception {
        String h = host("wrong-cert");
        String other = host("other");
        // The validated address answers with a certificate for a different name.
        Server legit = new Server(VALIDATED, 0, other, "{\"server\":\"validated\"}");
        open.add(legit);
        SafeHttpClient.JdkTransport transport = new SafeHttpClient.JdkTransport(clientTrusting(h, other));

        assertThatThrownBy(() -> transport.exchangePinned("GET", URI.create("https://" + h + ":" + legit.port() + "/x"),
                List.of(VALIDATED), Map.of("Accept", "application/json"), null, Duration.ofSeconds(10), 4096))
                .isInstanceOf(IOException.class);
        assertThat(legit.requests.get()).isZero();
        assertThat(legit.sni).containsOnly(h);
        assertThat(HostileDnsResolverProvider.lookups(h)).isZero();
    }

    @Test
    void safeHttpClientValidatesAndPinsEachRedirectHopAndNeverConnectsToARefusedOne() throws Exception {
        String first = host("hop1");
        String second = host("hop2");
        String refused = host("hop3");
        // One server whose certificate covers both names: 302 from the first hop to the second, then to a refused one.
        Server legit = new Server(VALIDATED, 0, first, "{}", second);
        open.add(legit);
        legit.https.removeContext("/");
        int port = legit.port();
        legit.https.createContext("/", exchange -> {
            legit.requests.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            String location = path.equals("/start") ? "https://" + second + ":" + port + "/next"
                    : "https://" + refused + ":" + port + "/metadata";
            exchange.getResponseHeaders().set("Location", location);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        Sentinel attacker = new Sentinel(port);
        open.add(attacker);

        List<String> guardCalls = new CopyOnWriteArrayList<>();
        AddressGuard guard = new AddressGuard() {
            @Override
            public List<InetAddress> checkAndResolve(URI uri) {
                guardCalls.add(uri.getHost());
                if (uri.getHost().equals(refused)) {
                    throw new UnsafeAddressException("host resolves to a non-public address");
                }
                return List.of(VALIDATED);
            }
        };
        SafeHttpClient client = new SafeHttpClient(new SafeHttpClient.JdkTransport(clientTrusting(first)), guard,
                SafeHttpClient.Settings.defaults().withTimeout(Duration.ofSeconds(10)).withMinInterval(0), ms -> {});

        assertThatThrownBy(() -> client.get(URI.create("https://" + first + ":" + port + "/start"), SafeHttpClient.Accept.JSON))
                .isInstanceOf(SafeHttpClient.FetchException.class)
                .satisfies(e -> assertThat(((SafeHttpClient.FetchException) e).category())
                        .isEqualTo(FailureCategory.UNSAFE_ADDRESS));
        // Each hop was validated exactly once, in order; the refused hop was never connected to.
        assertThat(guardCalls).containsExactly(first, second, refused);
        assertThat(attacker.accepts.get()).isZero();
        assertThat(HostileDnsResolverProvider.lookups(first)).isZero();
        assertThat(HostileDnsResolverProvider.lookups(second)).isZero();
        assertThat(HostileDnsResolverProvider.lookups(refused)).isZero();
        assertThat(legit.sni).contains(first, second);
    }
}
