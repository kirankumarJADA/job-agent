package com.personal.jobagent.mailbox;

import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.mailbox.managed.MailAddresses;
import com.personal.jobagent.mailbox.managed.ManagedMailVerificationProvider;
import com.personal.jobagent.mailbox.managed.ManagedMailboxService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * Which e-mail address an employer account is registered with (Phase 8.5, Phase 20).
 *
 * <p><b>Robin-managed email is the default.</b> A new registration for an
 * employer domain gets the candidate's Robin-managed address, so the employer's
 * verification mail reaches Robin without the candidate's personal mailbox
 * being connected. The choice is recorded once per (profile, employer domain) in
 * {@code applicant_identities} and never silently changed afterwards: an
 * employer account that already exists keeps the address it was registered
 * with (switching would create a duplicate account), and the answer says which
 * mailbox can read its mail — managed, the connected Gmail, or neither
 * ("external", where verification pauses for the candidate).
 *
 * <p>With an account session, the session must be the owner's, for this
 * application and employer domain, and still open; the identity is bound to it
 * and a session that has not started moves to SIGNUP (new account) or LOGIN
 * (existing account).
 *
 * <p>When a new registration would need the managed address but it is not yet
 * usable (not configured, or receiving not yet proven), nothing is recorded and
 * {@link ManagedMailboxNotReadyException} is raised: the worker pauses instead
 * of registering with an address that cannot receive mail.
 */
@Service
public class RegistrationIdentityService {

    public static final String MAILBOX_MANAGED = "managed";
    public static final String MAILBOX_GMAIL = "gmail";
    public static final String MAILBOX_EXTERNAL = "external";

    public record RegistrationIdentity(UUID identityId, String email, String employerDomain, String mailbox,
                                       boolean existing) {
    }

    /**
     * Managed mail is needed for a new registration but is not usable yet.
     * {@link #reason()} is NOT_CONFIGURED, PENDING_RECEIVING_VERIFICATION or ADDRESS_DISABLED.
     */
    public static class ManagedMailboxNotReadyException extends RuntimeException {
        private final String reason;

        public ManagedMailboxNotReadyException(String reason) {
            super("Robin-managed email is not ready for employer registration (" + reason + ")");
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    /** The account session exists but belongs to another application or employer domain, or is closed. */
    public static class SessionMismatchException extends RuntimeException {
        public SessionMismatchException(String message) {
            super(message);
        }
    }

    /** Account-session states after which nothing more may happen in the session. */
    public static final java.util.Set<String> CLOSED_SESSION_STATES = java.util.Set.of("HARD_STOP", "FAILED", "COMPLETED");

    private final JdbcTemplate db;
    private final ManagedMailboxService mailboxes;
    private final ManagedMailVerificationProvider managed;
    private final GmailMailboxVerificationProvider gmail;

    public RegistrationIdentityService(JdbcTemplate db, ManagedMailboxService mailboxes,
                                       ManagedMailVerificationProvider managed, GmailMailboxVerificationProvider gmail) {
        this.db = db;
        this.mailboxes = mailboxes;
        this.managed = managed;
        this.gmail = gmail;
    }

    /**
     * @param ownerProfileId the owner of {@code applicationId} (derived by the caller, never from a request)
     * @param sessionId      optional account session; when given it must be the owner's session for this
     *                       application and employer domain, and the identity is bound to it
     * @throws NoSuchElementException          when the session is not the owner's
     * @throws SessionMismatchException        when the session is for another application, domain or identity
     * @throws ManagedMailboxNotReadyException when a new registration cannot be given a working address
     * @throws IllegalArgumentException        when the employer domain is not a domain name
     */
    @Transactional
    public RegistrationIdentity resolve(UUID ownerProfileId, UUID applicationId, UUID sessionId, String employerDomain) {
        String domain = MailAddresses.employerDomain(employerDomain)
                .orElseThrow(() -> new IllegalArgumentException("employerDomain must be a domain name"));
        Map<String, Object> session = sessionId == null ? null : ownedSession(ownerProfileId, sessionId);
        if (session != null) {
            if (!applicationId.equals(session.get("application_id"))) {
                throw new SessionMismatchException("The account session belongs to another application");
            }
            if (CLOSED_SESSION_STATES.contains(String.valueOf(session.get("state")))) {
                throw new SessionMismatchException("The account session is closed (" + session.get("state") + ")");
            }
            String expected = MailAddresses.employerDomain(String.valueOf(session.get("expected_domain"))).orElse("");
            if (!domain.equals(expected)) {
                throw new SessionMismatchException("The account session is for another employer domain");
            }
        }

        Optional<Map<String, Object>> existing = identity(ownerProfileId, domain);
        boolean alreadyRegistered = existing.isPresent()
                && "ACTIVE".equals(existing.get().get("account_status"));
        if (existing.isEmpty()) {
            ManagedMailboxService.Readiness readiness = mailboxes.readiness();
            if (readiness != ManagedMailboxService.Readiness.ACTIVE) {
                throw new ManagedMailboxNotReadyException(readiness == null ? "NOT_CONFIGURED" : readiness.name());
            }
            ManagedMailboxService.ManagedAddress address = mailboxes.ensureAddress(ownerProfileId);
            if (address == null || !address.active()) throw new ManagedMailboxNotReadyException("ADDRESS_DISABLED");
            db.update("""
                    insert into applicant_identities (id, profile_id, applicant_email, provider, domain, account_status)
                    values (?, ?, ?, 'ROBIN_MANAGED', ?, 'PENDING')
                    on conflict (profile_id, domain) do nothing
                    """, UuidV7.generate(), ownerProfileId, address.address(), domain);
            existing = identity(ownerProfileId, domain);
            alreadyRegistered = existing.isPresent()
                    && "ACTIVE".equals(existing.get().get("account_status"));
        }
        Map<String, Object> row = existing.orElseThrow();
        String status = String.valueOf(row.get("account_status"));
        if (!"ACTIVE".equals(status) && !"PENDING".equals(status)) {
            throw new IllegalStateException("The employer identity for this domain is disabled");
        }
        alreadyRegistered = "ACTIVE".equals(status);
        if ("ROBIN_MANAGED".equals(row.get("provider"))) {
            ManagedMailboxService.Readiness currentReadiness = mailboxes.readiness();
            if (currentReadiness != null && currentReadiness != ManagedMailboxService.Readiness.ACTIVE) {
                throw new ManagedMailboxNotReadyException(currentReadiness.name());
            }
            Optional<ManagedMailboxService.ManagedAddress> currentAddress = mailboxes.findAddress(ownerProfileId);
            if (currentAddress.isPresent() && !currentAddress.get().active()) {
                throw new ManagedMailboxNotReadyException("ADDRESS_DISABLED");
            }
        }
        UUID identityId = (UUID) row.get("id");
        String email = String.valueOf(row.get("applicant_email")).toLowerCase(java.util.Locale.ROOT);

        if (session != null) {
            Object bound = session.get("applicant_identity_id");
            if (bound != null && !identityId.equals(bound)) {
                throw new SessionMismatchException("The account session is bound to another identity");
            }
            db.update("""
                    update account_sessions set applicant_identity_id = ?, updated_at = now()
                    where id = ? and owner_profile_id = ? and applicant_identity_id is null
                    """, identityId, sessionId, ownerProfileId);
            // A session that has not started yet moves on: to sign-up for a new
            // employer account, to login for one that already exists.
            db.update("""
                    update account_sessions set state = ?, updated_at = now()
                    where id = ? and owner_profile_id = ? and state in ('DISCOVER', 'ACCOUNT_REQUIRED')
                    """, alreadyRegistered ? "LOGIN" : "SIGNUP", sessionId, ownerProfileId);
        }
        return new RegistrationIdentity(identityId, email, domain, mailboxFor(ownerProfileId, email), alreadyRegistered);
    }

    private String mailboxFor(UUID ownerProfileId, String email) {
        if (managed.handles(ownerProfileId, email)) return MAILBOX_MANAGED;
        if (gmail.handles(ownerProfileId, email)) return MAILBOX_GMAIL;
        return MAILBOX_EXTERNAL;
    }

    private Map<String, Object> ownedSession(UUID ownerProfileId, UUID sessionId) {
        List<Map<String, Object>> rows = db.queryForList("""
                select application_id, expected_domain, applicant_identity_id, state
                from account_sessions where id = ? and owner_profile_id = ?
                """, sessionId, ownerProfileId);
        if (rows.isEmpty()) throw new NoSuchElementException("No such account session");
        return rows.get(0);
    }

    Optional<Map<String, Object>> identity(UUID ownerProfileId, String domain) {
        return db.queryForList("""
                select id, applicant_email::text as applicant_email, account_status, provider from applicant_identities
                where profile_id = ? and domain = ?
                """, ownerProfileId, domain).stream().findFirst();
    }

    /**
     * Resolves the candidate's contact email for an application form (Phase 20).
     *
     * <p>When Robin-managed email is active and usable on this deployment, returns
     * the candidate's separate Robin-managed application address (recorded in
     * {@code applicant_identities} for this employer domain). If managed email is
     * not configured or not yet verified for receiving, returns the candidate's
     * personal login email.
     *
     * <p>Fail-safe constraint: If managed email was explicitly selected or reserved
     * for this profile or employer domain, and managed email is not active,
     * {@link ManagedMailboxNotReadyException} is raised instead of silently leaking
     * the candidate's personal login email.
     */
    public String resolveApplicationContactEmail(UUID profileId, String applicationUrl, String personalEmail) {
        if (profileId == null) return personalEmail;
        String domain = domainOf(applicationUrl);
        if (domain != null) {
            Optional<Map<String, Object>> existing = identity(profileId, domain);
            if (existing.isPresent()) {
                Map<String, Object> idRow = existing.get();
                String provider = String.valueOf(idRow.get("provider"));
                String accountStatus = String.valueOf(idRow.get("account_status"));
                if ("ROBIN_MANAGED".equals(provider)) {
                    if (!"ACTIVE".equals(accountStatus) && !"PENDING".equals(accountStatus)) {
                        throw new ManagedMailboxNotReadyException("IDENTITY_DISABLED");
                    }
                    ManagedMailboxService.Readiness readiness = mailboxes.readiness();
                    if (readiness != ManagedMailboxService.Readiness.ACTIVE) {
                        throw new ManagedMailboxNotReadyException(readiness == null ? "NOT_CONFIGURED" : readiness.name());
                    }
                    Optional<ManagedMailboxService.ManagedAddress> currentAddress = mailboxes.findAddress(profileId);
                    if (currentAddress.isPresent() && !currentAddress.get().active()) {
                        throw new ManagedMailboxNotReadyException("ADDRESS_DISABLED");
                    }
                    Object registered = idRow.get("applicant_email");
                    if (registered != null && !String.valueOf(registered).isBlank()) {
                        return String.valueOf(registered).toLowerCase(java.util.Locale.ROOT).trim();
                    }
                    throw new ManagedMailboxNotReadyException("IDENTITY_EMAIL_MISSING");
                }
                if (!"ACTIVE".equals(accountStatus) && !"PENDING".equals(accountStatus)) {
                    throw new IllegalStateException("The employer identity for this domain is disabled");
                }
                Object registered = idRow.get("applicant_email");
                if (registered != null && !String.valueOf(registered).isBlank()) {
                    return String.valueOf(registered).toLowerCase(java.util.Locale.ROOT).trim();
                }
            }
        }

        // When the candidate has explicitly reserved a Robin-managed address, do not
        // silently leak their personal email if receiving is not ready.
        Optional<ManagedMailboxService.ManagedAddress> candidateAddress = mailboxes.findAddress(profileId);
        if (candidateAddress.isPresent()) {
            if (mailboxes.readiness() != ManagedMailboxService.Readiness.ACTIVE) {
                throw new ManagedMailboxNotReadyException(mailboxes.readiness().name());
            }
            ManagedMailboxService.ManagedAddress address = candidateAddress.get();
            if (!address.active()) {
                throw new ManagedMailboxNotReadyException("ADDRESS_DISABLED");
            }
            String managedEmail = address.address().toLowerCase(java.util.Locale.ROOT).trim();
            return managedEmail;
        }

        if (mailboxes.readiness() == ManagedMailboxService.Readiness.ACTIVE) {
            ManagedMailboxService.ManagedAddress address;
            try {
                address = mailboxes.ensureAddress(profileId);
            } catch (ManagedMailboxNotReadyException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new ManagedMailboxNotReadyException("ADDRESS_UNAVAILABLE");
            }
            if (address == null || !address.active()) {
                throw new ManagedMailboxNotReadyException("ADDRESS_DISABLED");
            }
            String managedEmail = address.address().toLowerCase(java.util.Locale.ROOT).trim();
            return managedEmail;
        }
        return personalEmail;
    }

    /**
     * Prepares an employer registration identity when authorizing account creation, without
     * marking an uncreated account as already registered. Fails with {@code existing = true}
     * only when a confirmed ACTIVE identity already exists for this profile and domain.
     */
    public RegistrationIdentity prepareForAccountCreation(UUID ownerProfileId, UUID applicationId,
                                                        UUID sessionId, String rawEmployerDomain) {
        return resolve(ownerProfileId, applicationId, sessionId, rawEmployerDomain);
    }

    /**
     * Promotes a PENDING employer registration identity to ACTIVE after real signup submission
     * or verification confirmation, without ever re-enabling a DISABLED identity.
     */
    public boolean confirmRegistration(UUID ownerProfileId, String rawEmployerDomain) {
        if (ownerProfileId == null || rawEmployerDomain == null || rawEmployerDomain.isBlank()) {
            return false;
        }
        String domain = MailAddresses.employerDomain(rawEmployerDomain).orElse(null);
        if (domain == null) {
            return false;
        }
        int updated = db.update("""
                update applicant_identities
                set account_status = 'ACTIVE',
                    updated_at = now()
                where profile_id = ? and domain = ? and account_status = 'PENDING'
                """, ownerProfileId, domain);
        return updated > 0;
    }

    public static String domainOf(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            String host = java.net.URI.create(url).getHost();
            if (host == null) return null;
            return MailAddresses.employerDomain(host).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}
