package com.personal.jobagent.mailbox.managed;

import com.personal.jobagent.common.UuidV7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Robin-managed receiving addresses (Phase 8.5, Phase 20).
 *
 * <p><b>Readiness is evidence-based.</b> An address is offered for employer
 * registration only when the deployment is fully configured AND Robin has
 * accepted at least one provider-signed delivery for the domain. That single
 * delivery proves the parts configuration cannot: the MX records point at the
 * provider, the provider route forwards to this backend, and the signing key
 * matches. Until then the address can be reserved and shown, but is reported
 * as not receiving and is never used for a registration.
 *
 * <p><b>Ownership.</b> One alias per profile per domain, unique per domain, both
 * enforced by the database. Aliases are random: 16 characters (about 80 bits)
 * from an alphabet without look-alike characters, starting with a letter.
 *
 * <p><b>Subaddressing (Cloudflare compatibility).</b> When Cloudflare Email Routing
 * is used, a single base rule on the subdomain (e.g. {@code robin@mail.aplivenosi.com})
 * is paired with candidate-specific plus-subaddressing (e.g. {@code robin+<token>@mail.aplivenosi.com}).
 */
@Service
public class ManagedMailboxService {

    /** Whether Robin-managed email can be used right now. */
    public enum Readiness {
        /** Domain, signing key or encryption key is missing. */
        NOT_CONFIGURED,
        /** Configured, but no signed delivery has been accepted for the domain yet. */
        PENDING_RECEIVING_VERIFICATION,
        /** Configured and proven by a signed delivery. */
        ACTIVE
    }

    public record ManagedAddress(UUID id, UUID profileId, String localPart, String domain, String status) {
        public String address() {
            return localPart + "@" + domain;
        }

        public boolean active() {
            return "ACTIVE".equals(status);
        }
    }

    public record DomainEvidence(Instant receivingVerifiedAt, Instant lastInboundAt) {
    }

    /** Owner-facing status. {@code address} is the owner's alias under the configured domain, if reserved. */
    public record Status(Readiness readiness, String domain, ManagedAddress address, DomainEvidence evidence,
                         List<String> missing) {

        /** True only when this owner's address may be given to an employer. */
        public boolean usableForRegistration() {
            return readiness == Readiness.ACTIVE && address != null && address.active();
        }
    }

    static final String FIRST_ALPHABET = "abcdefghijkmnpqrstuvwxyz";
    static final String ALPHABET = FIRST_ALPHABET + "23456789";
    static final int ALIAS_LENGTH = 16;
    private static final int MAX_ATTEMPTS = 8;

    private final JdbcTemplate db;
    private final ManagedMailSettings settings;
    private final SecureRandom random = new SecureRandom();

    public ManagedMailboxService(JdbcTemplate db, ManagedMailSettings settings) {
        this.db = db;
        this.settings = settings;
    }

    public ManagedMailSettings settings() {
        return settings;
    }

    /** Status for one owner. Never creates anything. */
    public Status status(UUID profileId) {
        List<String> missing = settings.missing();
        if (!missing.isEmpty()) {
            return new Status(Readiness.NOT_CONFIGURED, settings.domain(), null, null, missing);
        }
        Optional<DomainEvidence> evidence = domainEvidence(settings.domain());
        ManagedAddress address = profileId == null ? null : findAddress(profileId).orElse(null);
        Readiness readiness = evidence.isPresent() ? Readiness.ACTIVE : Readiness.PENDING_RECEIVING_VERIFICATION;
        return new Status(readiness, settings.domain(), address, evidence.orElse(null), List.of());
    }

    public Readiness readiness() {
        return status(null).readiness();
    }

    /** The owner's alias under the configured domain, if one was reserved. */
    public Optional<ManagedAddress> findAddress(UUID profileId) {
        if (settings.domain().isEmpty()) return Optional.empty();
        return db.query("""
                        select id, profile_id, local_part, domain, status from managed_mail_addresses
                        where profile_id = ? and domain = ?
                        """, (rs, n) -> new ManagedAddress((UUID) rs.getObject("id"), (UUID) rs.getObject("profile_id"),
                        rs.getString("local_part"), rs.getString("domain"), rs.getString("status")),
                profileId, settings.domain()).stream().findFirst();
    }

    /** The owner's address only when it may be used for registration and verification right now. */
    public Optional<ManagedAddress> usableAddress(UUID profileId) {
        Status status = status(profileId);
        return status.usableForRegistration() ? Optional.of(status.address()) : Optional.empty();
    }

    /**
     * Reserves the owner's alias under the configured domain, or returns the one
     * already reserved. Requires configuration (an alias needs a domain), but not
     * receiving evidence: reserving is safe and lets the operator send the first
     * test message to a real alias. Concurrent calls converge on one alias.
     *
     * @throws IllegalStateException when managed mail is not configured
     */
    public ManagedAddress ensureAddress(UUID profileId) {
        if (profileId == null) throw new IllegalArgumentException("A profile is required");
        if (!settings.configured()) {
            throw new IllegalStateException("Robin-managed email is not configured on this deployment");
        }
        Optional<ManagedAddress> existing = findAddress(profileId);
        if (existing.isPresent()) return existing.get();
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            // A conflict is either an alias collision (retry with a new alias) or
            // a concurrent reservation for this owner (which the re-read returns).
            db.update("""
                    insert into managed_mail_addresses (id, profile_id, local_part, domain)
                    values (?, ?, ?, ?)
                    on conflict do nothing
                    """, UuidV7.generate(), profileId, newLocalPart(), settings.domain());
            Optional<ManagedAddress> now = findAddress(profileId);
            if (now.isPresent()) return now.get();
        }
        throw new IllegalStateException("Could not reserve a unique Robin address; try again");
    }

    /**
     * The active alias an envelope recipient routes to, or empty. Matching supports
     * exact local parts as well as Cloudflare plus-subaddressing (e.g. robin+token),
     * so a message reaches exactly one owner or none.
     */
    public Optional<ManagedAddress> routeEnvelopeRecipient(String envelopeRecipient) {
        Optional<String> address = MailAddresses.bareAddress(envelopeRecipient);
        if (address.isEmpty()) {
            address = MailAddresses.singleMailbox(envelopeRecipient);
        }
        if (address.isEmpty() || settings.domain().isEmpty()) return Optional.empty();
        String domain = MailAddresses.domainOf(address.get());
        if (!settings.domain().equals(domain)) return Optional.empty();
        String fullLocalPart = MailAddresses.localPartOf(address.get());

        // 1. Exact match on local_part (handles exact alias or robin+alias)
        Optional<ManagedAddress> direct = findActiveByLocalPart(fullLocalPart, domain);
        if (direct.isPresent()) return direct;

        // 2. Subaddress fallback: if recipient has plus-addressing (e.g. robin+token),
        // check if token matches an alias registered without the prefix
        int plus = fullLocalPart.indexOf('+');
        if (plus > 0 && plus < fullLocalPart.length() - 1) {
            String token = fullLocalPart.substring(plus + 1);
            Optional<ManagedAddress> byToken = findActiveByLocalPart(token, domain);
            if (byToken.isPresent()) return byToken;
        }

        return Optional.empty();
    }

    private Optional<ManagedAddress> findActiveByLocalPart(String localPart, String domain) {
        return db.query("""
                        select id, profile_id, local_part, domain, status from managed_mail_addresses
                        where local_part = ? and domain = ? and status = 'ACTIVE'
                        """, (rs, n) -> new ManagedAddress((UUID) rs.getObject("id"), (UUID) rs.getObject("profile_id"),
                        rs.getString("local_part"), rs.getString("domain"), rs.getString("status")),
                localPart, domain).stream().findFirst();
    }

    /**
     * Records that a provider-signed delivery for {@code domain} was accepted:
     * the receiving-verification evidence {@link #status} relies on.
     */
    public void recordSignedDelivery(String domain, String providerId, Instant at) {
        Timestamp time = Timestamp.from(at);
        db.update("""
                insert into managed_mail_domains (domain, provider_id, receiving_verified_at, last_inbound_at)
                values (?, ?, ?, ?)
                on conflict (domain) do update set last_inbound_at = greatest(managed_mail_domains.last_inbound_at,
                                                                             excluded.last_inbound_at)
                """, domain, providerId, time, time);
    }

    public Optional<DomainEvidence> domainEvidence(String domain) {
        if (domain == null || domain.isEmpty()) return Optional.empty();
        List<Map<String, Object>> rows = db.queryForList(
                "select receiving_verified_at, last_inbound_at from managed_mail_domains where domain = ?", domain);
        if (rows.isEmpty()) return Optional.empty();
        Map<String, Object> row = rows.get(0);
        return Optional.of(new DomainEvidence(((Timestamp) row.get("receiving_verified_at")).toInstant(),
                ((Timestamp) row.get("last_inbound_at")).toInstant()));
    }

    public String newLocalPart() {
        return settings.addressPrefix() + newAlias();
    }

    String newAlias() {
        StringBuilder alias = new StringBuilder(ALIAS_LENGTH);
        alias.append(FIRST_ALPHABET.charAt(random.nextInt(FIRST_ALPHABET.length())));
        for (int i = 1; i < ALIAS_LENGTH; i++) alias.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        return alias.toString();
    }
}
