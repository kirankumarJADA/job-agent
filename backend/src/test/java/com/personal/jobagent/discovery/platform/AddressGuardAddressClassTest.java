package com.personal.jobagent.discovery.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Stage 3I Phase 6, A5: which addresses the backend SSRF guard refuses, including IPv6 forms that embed IPv4. */
class AddressGuardAddressClassTest {

    @ParameterizedTest
    @ValueSource(strings = {
            // IPv4: loopback, unspecified, private, CGNAT, link-local / cloud metadata, benchmarking, documentation, multicast, reserved
            "127.0.0.1", "127.255.0.9", "0.0.0.0", "10.0.0.1", "172.16.5.4", "172.31.255.255", "192.168.1.1", "100.64.0.1",
            "169.254.169.254", "169.254.0.1", "198.18.0.1", "192.0.0.8", "192.0.2.10", "198.51.100.7", "203.0.113.9",
            "224.0.0.1", "240.0.0.1", "255.255.255.255",
            // IPv6: loopback, unspecified, unique-local (incl. fd00:ec2::254 metadata), link-local, multicast, documentation
            "::1", "::", "fc00::1", "fd00:ec2::254", "fe80::1", "ff02::1", "2001:db8::1",
            // IPv6 forms embedding a private / metadata IPv4
            "::ffff:127.0.0.1", "::ffff:169.254.169.254", "::10.0.0.1",
            "64:ff9b::a9fe:a9fe", "64:ff9b::7f00:1", "64:ff9b:1::a00:1",
            "2002:a9fe:a9fe::1", "2002:0a00:0001::1", "2002:7f00:0001::",
            // Teredo and discard
            "2001:0:4136:e378:8000:63bf:3fff:fdd2", "100::1"
    })
    void refusesNonPublicAddresses(String literal) throws Exception {
        assertThat(AddressGuard.isPublic(InetAddress.getByName(literal))).as(literal).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"93.184.216.34", "8.8.8.8", "1.1.1.1", "2606:4700:4700::1111", "2a00:1450:4009:81f::200e",
            "64:ff9b::5db8:d822", "2002:5db8:d822::1", "::ffff:93.184.216.34"})
    void acceptsPublicUnicastIncludingTranslatedPublicIpv4(String literal) throws Exception {
        assertThat(AddressGuard.isPublic(InetAddress.getByName(literal))).as(literal).isTrue();
    }

    @Test
    void anyNonPublicAnswerAmongSeveralRefusesTheHostAndTheResolverIsAskedOnce() throws Exception {
        int[] calls = {0};
        AddressGuard guard = new AddressGuard(host -> {
            calls[0]++;
            return new InetAddress[]{InetAddress.getByName("93.184.216.34"), InetAddress.getByName("64:ff9b::a9fe:a9fe")};
        });
        assertThatThrownBy(() -> guard.checkAndResolve(URI.create("https://jobs.example.org/x")))
                .isInstanceOf(AddressGuard.UnsafeAddressException.class);
        assertThat(calls[0]).isEqualTo(1);

        AddressGuard publicOnly = new AddressGuard(host -> {
            calls[0]++;
            return new InetAddress[]{InetAddress.getByName("93.184.216.34")};
        });
        List<InetAddress> pinned = publicOnly.checkAndResolve(URI.create("https://jobs.example.org/x"));
        assertThat(pinned).extracting(InetAddress::getHostAddress).containsExactly("93.184.216.34");
        assertThat(calls[0]).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://jobs.example.org/", "https://user:pw@jobs.example.org/", "https://jobs.example.org:8443/",
            "https://127.0.0.1/", "https://[::1]/", "https://localhost/", "https://svc.internal/", "https://intranet/"})
    void refusesUnsafeUrlShapesBeforeAnyLookup(String url) {
        AddressGuard guard = new AddressGuard(host -> {
            throw new AssertionError("must not resolve " + host);
        });
        assertThatThrownBy(() -> guard.checkAndResolve(URI.create(url))).isInstanceOf(AddressGuard.UnsafeAddressException.class);
    }
}
