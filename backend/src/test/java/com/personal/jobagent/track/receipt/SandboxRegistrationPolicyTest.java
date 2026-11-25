package com.personal.jobagent.track.receipt;

import com.personal.jobagent.discovery.platform.AddressGuard;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SandboxRegistrationPolicyTest {

    private static final AddressGuard PUBLIC_RESOLVER_GUARD = new AddressGuard(host -> {
        if ("boards.greenhouse.io".equalsIgnoreCase(host) || "job-boards.greenhouse.io".equalsIgnoreCase(host)) {
            return new InetAddress[] { InetAddress.getByName("151.101.1.69") };
        }
        throw new UnknownHostException(host);
    });

    @Test
    void emptyAllowlistFailsClosed() {
        SandboxRegistrationPolicy unconfigured = new SandboxRegistrationPolicy(
                List.of(), List.of("/robin-sandbox/"), true, false, PUBLIC_RESOLVER_GUARD);
        assertThat(unconfigured.isConfigured()).isFalse();
        assertThatThrownBy(() -> unconfigured.validateAndCanonicalize(
                "GREENHOUSE", "https://boards.greenhouse.io/robin-sandbox/jobs/42"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no allowed sandbox domains are configured");
    }

    @Test
    void validatesCanonicalGreenhouseSandboxUrlAndDerivesPermittedDomain() {
        SandboxRegistrationPolicy policy = new SandboxRegistrationPolicy(
                List.of("boards.greenhouse.io", "job-boards.greenhouse.io"),
                List.of("/robin-sandbox/", "/sandbox/"),
                true,
                false,
                PUBLIC_RESOLVER_GUARD);

        var validated = policy.validateAndCanonicalize(
                "greenhouse", "https://BOARDS.GREENHOUSE.IO:443/robin-sandbox/jobs/42/");
        assertThat(validated.atsPlatform()).isEqualTo("GREENHOUSE");
        assertThat(validated.canonicalPostingUrl()).isEqualTo("https://boards.greenhouse.io/robin-sandbox/jobs/42");
        assertThat(validated.permittedDomain()).isEqualTo("boards.greenhouse.io");
    }

    @Test
    void rejectsArbitraryEmployerDomainsAndNonSandboxBoardPaths() {
        SandboxRegistrationPolicy policy = new SandboxRegistrationPolicy(
                List.of("boards.greenhouse.io"),
                List.of("/robin-sandbox/"),
                true,
                false,
                PUBLIC_RESOLVER_GUARD);

        // Arbitrary production employer on Greenhouse outside sandbox prefix
        assertThatThrownBy(() -> policy.validateAndCanonicalize(
                "GREENHOUSE", "https://boards.greenhouse.io/stripe/jobs/999"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path prefixes");

        // Arbitrary employer custom domain
        assertThatThrownBy(() -> policy.validateAndCanonicalize(
                "GREENHOUSE", "https://careers.evil-employer.com/robin-sandbox/jobs/999"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowlisted");

        // Lookalike suffix attack
        assertThatThrownBy(() -> policy.validateAndCanonicalize(
                "GREENHOUSE", "https://boards.greenhouse.io.evil.com/robin-sandbox/jobs/999"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowlisted");
    }

    @Test
    void rejectsHttpSsrfLoopbackPrivateMetadataAndCredentials() {
        AddressGuard mixedResolver = new AddressGuard(host -> {
            if ("metadata-rebind.greenhouse.io".equals(host)) {
                return new InetAddress[] { InetAddress.getByName("169.254.169.254") };
            }
            if ("private-rebind.greenhouse.io".equals(host)) {
                return new InetAddress[] { InetAddress.getByName("10.0.0.12") };
            }
            return new InetAddress[] { InetAddress.getByName("151.101.1.69") };
        });

        SandboxRegistrationPolicy policy = new SandboxRegistrationPolicy(
                List.of("boards.greenhouse.io", "greenhouse.io", "localhost", "127.0.0.1", "169.254.169.254", "10.0.0.5"),
                List.of("/robin-sandbox/"),
                true,
                false,
                mixedResolver);

        for (String dangerous : List.of(
                "http://boards.greenhouse.io/robin-sandbox/jobs/42",
                "https://boards.greenhouse.io:8443/robin-sandbox/jobs/42",
                "https://user:pass@boards.greenhouse.io/robin-sandbox/jobs/42",
                "https://boards.greenhouse.io/robin-sandbox/jobs/42?token=abc",
                "https://boards.greenhouse.io/robin-sandbox/jobs/42#top",
                "https://localhost/robin-sandbox/jobs/42",
                "https://127.0.0.1/robin-sandbox/jobs/42",
                "https://169.254.169.254/robin-sandbox/jobs/42",
                "https://10.0.0.5/robin-sandbox/jobs/42",
                "https://metadata-rebind.greenhouse.io/robin-sandbox/jobs/42",
                "https://private-rebind.greenhouse.io/robin-sandbox/jobs/42")) {
            assertThatThrownBy(() -> policy.validateAndCanonicalize("GREENHOUSE", dangerous))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void syntheticFixtureMarkersAndPrefixesAreRejectedByReceiptPolicy() {
        ReceiptVerificationPolicy policy = new ReceiptVerificationPolicy();
        assertThat(ReceiptVerificationPolicy.isMockOrSynthetic("FIXTURE-123")).isTrue();
        assertThat(ReceiptVerificationPolicy.isMockOrSynthetic("SYNTHETIC-123")).isTrue();
        assertThat(ReceiptVerificationPolicy.isSyntheticFixtureEvidence(
                "{\"fixture\":true,\"text\":\"Confirmation: GH-1\"}")).isTrue();
        assertThat(ReceiptVerificationPolicy.isSyntheticFixtureEvidence(
                "{\"source\":\"FIXTURE\",\"text\":\"Confirmation: GH-1\"}")).isTrue();
        assertThat(ReceiptVerificationPolicy.isSyntheticFixtureEvidence(
                "{\"atsPlatform\":\"GREENHOUSE\",\"confirmationNumber\":\"GH-9988\"}")).isFalse();
        assertThat(policy).isNotNull();
    }

    @Test
    void sharedMultiTenantHostsWithoutPathPrefixesFailClosed() {
        for (String host : List.of("boards.greenhouse.io", "job-boards.greenhouse.io", "jobs.ashbyhq.com")) {
            SandboxRegistrationPolicy missingPrefixes = new SandboxRegistrationPolicy(
                    List.of(host),
                    List.of(),
                    true,
                    false,
                    PUBLIC_RESOLVER_GUARD);
            assertThat(missingPrefixes.isConfigured()).isFalse();
            assertThatThrownBy(() -> missingPrefixes.validateAndCanonicalize(
                    "GREENHOUSE", "https://" + host + "/stripe/jobs/999"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("disabled");
        }
    }

    @Test
    void rejectsDotSegmentsEncodedTraversalUnicodeHostsTrailingDotIpv6AndNormalizesLocalPorts() {
        SandboxRegistrationPolicy policy = new SandboxRegistrationPolicy(
                List.of("boards.greenhouse.io", "greenhouse.io", "localhost", "[::1]", "[::ffff:127.0.0.1]"),
                List.of("/robin-sandbox/"),
                true,
                false,
                PUBLIC_RESOLVER_GUARD);

        for (String dangerous : List.of(
                "https://boards.greenhouse.io/robin-sandbox/jobs/..",
                "https://boards.greenhouse.io/robin-sandbox/jobs/.",
                "https://boards.greenhouse.io/../jobs/42",
                "https://boards.greenhouse.io/robin-sandbox/jobs/%2e%2e",
                "https://boards.greenhouse.io./robin-sandbox/jobs/42",
                "https://b\u00f6ards.greenhouse.io/robin-sandbox/jobs/42",
                "https://[::1]/robin-sandbox/jobs/42",
                "https://[::ffff:127.0.0.1]/robin-sandbox/jobs/42")) {
            assertThatThrownBy(() -> policy.validateAndCanonicalize("GREENHOUSE", dangerous))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        // Isolated local test mode strips default ports (:443 / :80) matching canonicalizePostingUrl()
        SandboxRegistrationPolicy localPolicy = new SandboxRegistrationPolicy(
                List.of("localhost"),
                List.of("/robin-sandbox/"),
                false,
                true,
                PUBLIC_RESOLVER_GUARD);
        var defaultHttpPort = localPolicy.validateAndCanonicalize(
                "GREENHOUSE", "http://localhost:80/robin-sandbox/jobs/42/");
        assertThat(defaultHttpPort.canonicalPostingUrl()).isEqualTo("http://localhost/robin-sandbox/jobs/42");
        assertThat(defaultHttpPort.canonicalPostingUrl())
                .isEqualTo(SandboxRegistrationPolicy.canonicalizePostingUrl("http://localhost:80/robin-sandbox/jobs/42/"));

        var defaultHttpsPort = localPolicy.validateAndCanonicalize(
                "GREENHOUSE", "https://localhost:443/robin-sandbox/jobs/42");
        assertThat(defaultHttpsPort.canonicalPostingUrl()).isEqualTo("https://localhost/robin-sandbox/jobs/42");
    }

    @Test
    void trackingQueryParametersReconcileWithoutWeakeningPostingIdentityMatching() {
        String registered = "https://boards.greenhouse.io/robin-sandbox/jobs/42";
        String executedWithTracking = "https://BOARDS.GREENHOUSE.IO:443/robin-sandbox/jobs/42/?gh_src=linkedin&utm_medium=social&utm_campaign=q4";
        String executedWithDifferentPostingParam = "https://boards.greenhouse.io/robin-sandbox/jobs/42?gh_jid=9999";

        assertThat(SandboxRegistrationPolicy.canonicalizePostingUrlForMatch(executedWithTracking))
                .isEqualTo(registered);
        assertThat(ReceiptCaptureService.canonicalUrlsMatch(executedWithTracking, registered)).isTrue();
        assertThat(ReceiptCaptureService.canonicalUrlsMatch(executedWithDifferentPostingParam, registered)).isFalse();
    }

    @Test
    void localHttpDomainCheckRequiresExplicitLocalTestModeAndLoopbackHost() {
        // Production default rejects http even for localhost
        assertThat(ReceiptCaptureService.hostWithinDomain("http://localhost:8080/robin-sandbox/jobs/42", "localhost", true, false))
                .isFalse();
        assertThat(ReceiptCaptureService.hostWithinDomain("http://localhost:8080/robin-sandbox/jobs/42", "localhost", false, false))
                .isFalse();
        // Remote host never permitted over http even if local test mode is enabled
        assertThat(ReceiptCaptureService.hostWithinDomain("http://boards.greenhouse.io/robin-sandbox/jobs/42", "boards.greenhouse.io", false, true))
                .isFalse();
        // Loopback permitted over http ONLY when !requireHttps && allowLocalTestHosts
        assertThat(ReceiptCaptureService.hostWithinDomain("http://localhost:8080/robin-sandbox/jobs/42", "localhost", false, true))
                .isTrue();
        assertThat(ReceiptCaptureService.hostWithinDomain("http://127.0.0.1:8080/robin-sandbox/jobs/42", "127.0.0.1", false, true))
                .isTrue();
    }
}
