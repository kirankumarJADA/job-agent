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
 * <p>Known limitation, documented in PHASE8_FIND_WORKFLOW.md: the JDK HTTP
 * client re-resolves the name when it connects, so a hostile DNS server could
 * still answer differently between check and connect (rebinding). Adapter
 * hosts are additionally pinned to the platform's own domains wherever the
 * platform has one, which narrows that window to custom-domain platforms.
 */
public class AddressGuard {

    /** Name resolution seam for tests. */
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final Resolver resolver;

    public AddressGuard() {
        this(InetAddress::getAllByName);
    }

    public AddressGuard(Resolver resolver) {
        this.resolver = resolver;
    }

    /** @throws UnsafeAddressException when the URI must not be fetched */
    public void check(URI uri) {
        if (uri == null) throw new UnsafeAddressException("missing URL");
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https")) throw new UnsafeAddressException("only https URLs are fetched");
        if (uri.getRawUserInfo() != null) throw new UnsafeAddressException("URLs with credentials are refused");
        if (uri.getPort() != -1 && uri.getPort() != 443) throw new UnsafeAddressException("non-standard ports are refused");
        String host = uri.getHost();
        if (host == null || host.isBlank()) throw new UnsafeAddressException("URL has no host");
        String h = host.toLowerCase(Locale.ROOT);
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
            return true;
        }
        return false;
    }

    public static class UnsafeAddressException extends RuntimeException {
        public UnsafeAddressException(String message) {
            super(message);
        }
    }
}
