package com.personal.jobagent.discovery.platform;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * SSRF guard. Every outbound discovery request — including each redirect hop —
 * is checked here first: https only, default port only, no user-info, and the
 * host must resolve exclusively to public unicast addresses. Loopback,
 * unspecified, private (RFC 1918), carrier-grade NAT, link-local (which
 * includes the 169.254.169.254 cloud metadata service), multicast, IPv6
 * unique-local and IPv4-mapped/compatible forms of any of those are refused.
 *
 * <p>Rebinding: {@link #checkAndResolve} resolves the name exactly once and
 * returns the validated addresses; {@link SafeHttpClient} connects only to
 * those addresses (through its pinned tunnel), so a second DNS answer is never
 * consulted (proved at the socket level by {@code SafeHttpClientTransportPinningTest}).
 * IPv6 forms that embed an IPv4 address (IPv4-mapped/compatible, NAT64
 * {@code 64:ff9b::/96} and {@code 64:ff9b:1::/48}, 6to4 {@code 2002::/16}) are
 * judged by the embedded address; Teredo, the discard prefix and documentation
 * ranges are refused outright.
 */
public class AddressGuard {

    /** Name resolution seam for tests. */
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final Resolver resolver;
    private final boolean allowLoopbackForTesting;

    public AddressGuard() {
        this(InetAddress::getAllByName, false);
    }

    public AddressGuard(Resolver resolver) {
        this(resolver, false);
    }

    private AddressGuard(Resolver resolver, boolean allowLoopbackForTesting) {
        this.resolver = resolver;
        this.allowLoopbackForTesting = allowLoopbackForTesting;
    }

    /**
     * Test-only factory that permits explicit loopback HTTP/HTTPS URIs
     * ({@code 127.0.0.1} / {@code localhost} resolving strictly to loopback)
     * while preserving full SSRF refusal for RFC 1918, metadata, and link-local
     * addresses.
     */
    public static AddressGuard allowingLoopbackForTesting() {
        return new AddressGuard(InetAddress::getAllByName, true);
    }

    public static AddressGuard allowingLoopbackForTesting(Resolver resolver) {
        return new AddressGuard(resolver, true);
    }

    /** @throws UnsafeAddressException when the URI must not be fetched */
    public void check(URI uri) {
        checkAndResolve(uri);
    }

    /**
     * Validates URL safety, resolves DNS once, checks every returned IP is
     * public, and returns the validated addresses for transport-level pinning.
     */
    public java.util.List<InetAddress> checkAndResolve(URI uri) {
        if (uri == null) throw new UnsafeAddressException("missing URL");
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (host == null || host.isBlank()) throw new UnsafeAddressException("URL has no host");
        String h = host.toLowerCase(Locale.ROOT);
        if (uri.getRawUserInfo() != null) throw new UnsafeAddressException("URLs with credentials are refused");

        boolean candidateLoopback = allowLoopbackForTesting
                && ("http".equals(scheme) || "https".equals(scheme))
                && ("localhost".equals(h) || h.endsWith(".localhost") || "127.0.0.1".equals(h) || "[::1]".equals(h) || "::1".equals(h));
        if (candidateLoopback) {
            InetAddress[] addresses;
            try {
                String normalizedHost = h.startsWith("[") && h.endsWith("]") ? h.substring(1, h.length() - 1) : h;
                addresses = resolver.resolve(normalizedHost);
            } catch (UnknownHostException e) {
                throw new UnsafeAddressException("host does not resolve");
            }
            if (addresses == null || addresses.length == 0) throw new UnsafeAddressException("host does not resolve");
            for (InetAddress address : addresses) {
                if (!address.isLoopbackAddress()) {
                    throw new UnsafeAddressException("test loopback host resolved outside loopback");
                }
            }
            return java.util.List.of(addresses);
        }

        if (!scheme.equals("https")) throw new UnsafeAddressException("only https URLs are fetched");
        if (uri.getPort() != -1 && uri.getPort() != 443) throw new UnsafeAddressException("non-standard ports are refused");
        if (h.startsWith("[") || h.matches("[0-9.]+") || h.contains(":")) {
            throw new UnsafeAddressException("IP-literal hosts are refused");
        }
        if (h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal")
                || h.endsWith(".lan") || h.endsWith(".home.arpa") || !h.contains(".")) {
            throw new UnsafeAddressException("internal host names are refused");
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(h);
        } catch (UnknownHostException e) {
            throw new UnsafeAddressException("host does not resolve");
        }
        if (addresses == null || addresses.length == 0) throw new UnsafeAddressException("host does not resolve");
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                throw new UnsafeAddressException("host resolves to a non-public address");
            }
        }
        return java.util.List.of(addresses);
    }

    static boolean isPublic(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            int a0 = b[0] & 0xff, a1 = b[1] & 0xff;
            if (a0 == 0 || a0 == 10 || a0 == 127) return false;
            if (a0 == 100 && a1 >= 64 && a1 <= 127) return false;     // 100.64.0.0/10 CGNAT
            if (a0 == 169 && a1 == 254) return false;                  // link-local / metadata
            if (a0 == 172 && a1 >= 16 && a1 <= 31) return false;
            if (a0 == 192 && a1 == 168) return false;
            if (a0 == 192 && a1 == 0 && (b[2] & 0xff) == 0) return false; // 192.0.0.0/24
            if (a0 == 198 && (a1 == 18 || a1 == 19)) return false;     // benchmarking
            if (a0 == 192 && a1 == 0 && (b[2] & 0xff) == 2) return false;   // 192.0.2.0/24 documentation
            if (a0 == 198 && a1 == 51 && (b[2] & 0xff) == 100) return false; // 198.51.100.0/24 documentation
            if (a0 == 203 && a1 == 0 && (b[2] & 0xff) == 113) return false;  // 203.0.113.0/24 documentation
            if (a0 >= 224) return false;                               // multicast + reserved
            return true;
        }
        if (address instanceof Inet6Address) {
            if ((b[0] & 0xfe) == 0xfc) return false;                   // fc00::/7 unique-local
            if ((b[0] & 0xff) == 0xfe && (b[1] & 0xc0) == 0x80) return false; // fe80::/10
            boolean mappedOrCompat = true;
            for (int i = 0; i < 10; i++) if (b[i] != 0) { mappedOrCompat = false; break; }
            if (mappedOrCompat && ((b[10] == (byte) 0xff && b[11] == (byte) 0xff) || (b[10] == 0 && b[11] == 0))) {
                try {
                    return isPublic(InetAddress.getByAddress(new byte[]{b[12], b[13], b[14], b[15]}));
                } catch (UnknownHostException e) {
                    return false;
                }
            }
            if ((b[0] & 0xff) == 0x20 && (b[1] & 0xff) == 0x01 && (b[2] & 0xff) == 0x0d && (b[3] & 0xff) == 0xb8) {
                return false;                                          // 2001:db8::/32 documentation
            }
            if ((b[0] & 0xff) == 0x20 && (b[1] & 0xff) == 0x01 && b[2] == 0 && b[3] == 0) {
                return false;                                          // 2001::/32 Teredo (tunnels to an arbitrary IPv4)
            }
            if (b[0] == 0x01 && b[1] == 0 && b[2] == 0 && b[3] == 0 && b[4] == 0 && b[5] == 0 && b[6] == 0 && b[7] == 0) {
                return false;                                          // 100::/64 discard
            }
            boolean nat64WellKnown = (b[0] & 0xff) == 0x00 && (b[1] & 0xff) == 0x64 && (b[2] & 0xff) == 0xff
                    && (b[3] & 0xff) == 0x9b && allZero(b, 4, 12);     // 64:ff9b::/96
            boolean nat64Local = (b[0] & 0xff) == 0x00 && (b[1] & 0xff) == 0x64 && (b[2] & 0xff) == 0xff
                    && (b[3] & 0xff) == 0x9b && b[4] == 0 && (b[5] & 0xff) == 0x01;   // 64:ff9b:1::/48
            if (nat64WellKnown || nat64Local) {
                return embeddedIsPublic(b[12], b[13], b[14], b[15]);
            }
            if ((b[0] & 0xff) == 0x20 && (b[1] & 0xff) == 0x02) {   // 2002::/16 6to4: bytes 2-5 are the IPv4
                return embeddedIsPublic(b[2], b[3], b[4], b[5]);
            }
            return true;
        }
        return false;
    }

    private static boolean allZero(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) if (b[i] != 0) return false;
        return true;
    }

    private static boolean embeddedIsPublic(byte b0, byte b1, byte b2, byte b3) {
        try {
            return isPublic(InetAddress.getByAddress(new byte[]{b0, b1, b2, b3}));
        } catch (UnknownHostException e) {
            return false;
        }
    }

    public static class UnsafeAddressException extends RuntimeException {
        public UnsafeAddressException(String message) {
            super(message);
        }
    }
}
