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
 * Which e-mail address an employer account is registered with (Phase 8.5).
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
        boolean alreadyRegistered = existing.isPresent();
        if (existing.isEmpty()) {
            ManagedMailboxService.Readiness readiness = mailboxes.readiness();
            if (readiness != ManagedMailboxService.Readiness.ACTIVE) {
                throw new ManagedMailboxNotReadyException(readiness.name());
            }
            ManagedMailboxService.ManagedAddress address = mailboxes.ensureAddress(ownerProfileId);
            if (!address.active()) throw new ManagedMailboxNotReadyException("ADDRESS_DISABLED");
            db.update("""
                    insert into applicant_identities (id, profile_id, applicant_email, provider, domain)
                    values (?, ?, ?, 'ROBIN_MANAGED', ?)
                    on conflict (profile_id, domain) do nothing
                    """, UuidV7.generate(), ownerProfileId, address.address(), domain);
            existing = identity(ownerProfileId, domain);
        }
        Map<String, Object> row = existing.orElseThrow();
        if (!"ACTIVE".equals(row.get("account_status"))) {
            throw new IllegalStateException("The employer identity for this domain is disabled");
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

    private Optional<Map<String, Object>> identity(UUID ownerProfileId, String domain) {
        return db.queryForList("""
                select id, applicant_email::text as applicant_email, account_status from applicant_identities
                where profile_id = ? and domain = ?
                """, ownerProfileId, domain).stream().findFirst();
    }
}
