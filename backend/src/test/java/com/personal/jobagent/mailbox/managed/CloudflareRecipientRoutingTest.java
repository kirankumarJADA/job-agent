package com.personal.jobagent.mailbox.managed;

import com.personal.jobagent.security.SecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CloudflareRecipientRoutingTest {

    private static final String DOMAIN = "mail.aplivenosi.com";
    private static final SecretCipher CIPHER = new SecretCipher("6M5mXo2C0eP1Ztq0QzZ0n7cYk1o0r8sB3m6Gq9v2T8U=");
    private static final UUID PROFILE_1 = UUID.randomUUID();
    private static final UUID PROFILE_2 = UUID.randomUUID();

    private JdbcTemplate db;
    private ManagedMailSettings cloudflareSettings;
    private ManagedMailboxService service;
    private Map<String, ManagedMailboxService.ManagedAddress> addressesByLocalPart;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        db = mock(JdbcTemplate.class);
        cloudflareSettings = new ManagedMailSettings(DOMAIN, "cloudflare", "", 900, "cf-secret", 900, "robin+", 120, 30, CIPHER);
        service = new ManagedMailboxService(db, cloudflareSettings);
        addressesByLocalPart = new HashMap<>();

        when(db.query(anyString(), any(RowMapper.class), anyString(), eq(DOMAIN))).thenAnswer(invocation -> {
            String localPart = invocation.getArgument(2);
            ManagedMailboxService.ManagedAddress addr = addressesByLocalPart.get(localPart);
            return addr != null && addr.active() ? List.of(addr) : Collections.emptyList();
        });
    }

    @Test
    @DisplayName("newLocalPart generates alias with configured prefix and valid token format")
    void newLocalPartGeneratesPrefixedAlias() {
        Set<String> generated = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            String localPart = service.newLocalPart();
            assertThat(localPart).startsWith("robin+");
            String token = localPart.substring("robin+".length());
            assertThat(token).matches("^[a-km-np-z][a-km-np-z2-9]{15}$");
            generated.add(localPart);
        }
        assertThat(generated).hasSize(500);
    }

    @Test
    @DisplayName("Routes recipient matching exact candidate address with plus prefix")
    void routesExactPrefixedRecipient() {
        UUID addressId = UUID.randomUUID();
        String localPart = "robin+ab23cd45ef67gh89";
        ManagedMailboxService.ManagedAddress candidateAddress =
                new ManagedMailboxService.ManagedAddress(addressId, PROFILE_1, localPart, DOMAIN, "ACTIVE");
        addressesByLocalPart.put(localPart, candidateAddress);

        Optional<ManagedMailboxService.ManagedAddress> routed =
                service.routeEnvelopeRecipient("robin+ab23cd45ef67gh89@mail.aplivenosi.com");

        assertThat(routed).isPresent();
        assertThat(routed.get().id()).isEqualTo(addressId);
        assertThat(routed.get().profileId()).isEqualTo(PROFILE_1);
        assertThat(routed.get().address()).isEqualTo("robin+ab23cd45ef67gh89@mail.aplivenosi.com");
    }

    @Test
    @DisplayName("Routes case-insensitively and handles RFC display name format")
    void routesCaseInsensitiveAndDisplayName() {
        UUID addressId = UUID.randomUUID();
        String localPart = "robin+cand123456789012";
        ManagedMailboxService.ManagedAddress candidateAddress =
                new ManagedMailboxService.ManagedAddress(addressId, PROFILE_1, localPart, DOMAIN, "ACTIVE");
        addressesByLocalPart.put(localPart, candidateAddress);

        Optional<ManagedMailboxService.ManagedAddress> upperCase =
                service.routeEnvelopeRecipient("ROBIN+CAND123456789012@MAIL.APLIVENOSI.COM");
        assertThat(upperCase).isPresent();
        assertThat(upperCase.get().profileId()).isEqualTo(PROFILE_1);

        Optional<ManagedMailboxService.ManagedAddress> withDisplayName =
                service.routeEnvelopeRecipient("\"Robin Candidate\" <robin+cand123456789012@mail.aplivenosi.com>");
        assertThat(withDisplayName).isPresent();
        assertThat(withDisplayName.get().profileId()).isEqualTo(PROFILE_1);
    }

    @Test
    @DisplayName("Subaddress fallback routes incoming robin+token to legacy token-only registered address")
    void routesSubaddressFallbackForLegacyRegisteredAlias() {
        UUID addressId = UUID.randomUUID();
        String rawToken = "cand9876543210ab";
        // Address registered without robin+ in DB (e.g. from prior Mailgun registration)
        ManagedMailboxService.ManagedAddress legacyAddress =
                new ManagedMailboxService.ManagedAddress(addressId, PROFILE_2, rawToken, DOMAIN, "ACTIVE");
        addressesByLocalPart.put(rawToken, legacyAddress);

        // Inbound recipient forwarded from Cloudflare Email Routing rule for robin+<token>
        Optional<ManagedMailboxService.ManagedAddress> routed =
                service.routeEnvelopeRecipient("robin+cand9876543210ab@mail.aplivenosi.com");

        assertThat(routed).isPresent();
        assertThat(routed.get().id()).isEqualTo(addressId);
        assertThat(routed.get().profileId()).isEqualTo(PROFILE_2);
    }

    @Test
    @DisplayName("Base routing address without unique subaddress token rejects delivery")
    void rejectsBaseAddressWithoutSubaddressToken() {
        // The literal base address robin@mail.aplivenosi.com configured in Cloudflare Email Routing
        // must not match any candidate mailbox.
        Optional<ManagedMailboxService.ManagedAddress> routed =
                service.routeEnvelopeRecipient("robin@mail.aplivenosi.com");

        assertThat(routed).isEmpty();
    }

    @Test
    @DisplayName("Rejects unknown subaddress token, wrong domain, or malformed address")
    void rejectsUnknownOrInvalidRecipients() {
        // Unknown token
        assertThat(service.routeEnvelopeRecipient("robin+unknown12345678@mail.aplivenosi.com")).isEmpty();

        // Wrong domain
        UUID addressId = UUID.randomUUID();
        String localPart = "robin+cand123456789012";
        addressesByLocalPart.put(localPart,
                new ManagedMailboxService.ManagedAddress(addressId, PROFILE_1, localPart, DOMAIN, "ACTIVE"));
        assertThat(service.routeEnvelopeRecipient("robin+cand123456789012@otherdomain.com")).isEmpty();

        // Empty / null / malformed
        assertThat(service.routeEnvelopeRecipient(null)).isEmpty();
        assertThat(service.routeEnvelopeRecipient("")).isEmpty();
        assertThat(service.routeEnvelopeRecipient("not-an-email")).isEmpty();
        assertThat(service.routeEnvelopeRecipient("@mail.aplivenosi.com")).isEmpty();
    }

    @Test
    @DisplayName("Rejects inactive address even if local_part matches")
    void rejectsInactiveAddress() {
        UUID addressId = UUID.randomUUID();
        String localPart = "robin+inactive123456";
        // Status is DISABLED
        addressesByLocalPart.put(localPart,
                new ManagedMailboxService.ManagedAddress(addressId, PROFILE_1, localPart, DOMAIN, "DISABLED"));

        assertThat(service.routeEnvelopeRecipient("robin+inactive123456@mail.aplivenosi.com")).isEmpty();
    }
}
