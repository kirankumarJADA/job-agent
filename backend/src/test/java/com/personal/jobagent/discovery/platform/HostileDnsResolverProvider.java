package com.personal.jobagent.discovery.platform;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * A hostile DNS server for tests, installed JVM-wide through the JDK resolver SPI
 * ({@code META-INF/services/java.net.spi.InetAddressResolverProvider}).
 *
 * <p>Only names ending in {@value #SUFFIX} are answered here — always with the
 * "attacker" address {@link #ATTACKER} — and every lookup of such a name is
 * counted. Every other name is delegated unchanged to the JDK's built-in
 * resolver, so the rest of the test suite resolves exactly as it would without
 * this provider. This is what lets {@code SafeHttpClientTransportPinningTest}
 * test DNS rebinding at the transport boundary: if the transport ever performed
 * its own lookup after {@link AddressGuard} validated an address, the JDK would
 * get the attacker's answer from here and connect to it.
 */
public final class HostileDnsResolverProvider extends InetAddressResolverProvider {

    public static final String SUFFIX = ".robin-rebind.test";
    public static final byte[] ATTACKER = {127, 0, 0, 2};

    private static final Map<String, AtomicInteger> LOOKUPS = new ConcurrentHashMap<>();

    /** How many times the JVM asked this "DNS server" for {@code host}. */
    public static int lookups(String host) {
        AtomicInteger n = LOOKUPS.get(host.toLowerCase(Locale.ROOT));
        return n == null ? 0 : n.get();
    }

    @Override
    public InetAddressResolver get(Configuration configuration) {
        InetAddressResolver builtin = configuration.builtinResolver();
        return new InetAddressResolver() {
            @Override
            public Stream<InetAddress> lookupByName(String host, LookupPolicy lookupPolicy) throws UnknownHostException {
                String h = host == null ? "" : host.toLowerCase(Locale.ROOT);
                if (h.endsWith(SUFFIX)) {
                    LOOKUPS.computeIfAbsent(h, k -> new AtomicInteger()).incrementAndGet();
                    return Stream.of(InetAddress.getByAddress(host, ATTACKER.clone()));
                }
                return builtin.lookupByName(host, lookupPolicy);
            }

            @Override
            public String lookupByAddress(byte[] addr) throws UnknownHostException {
                return builtin.lookupByAddress(addr);
            }
        };
    }

    @Override
    public String name() {
        return "robin-test-hostile-dns";
    }
}
