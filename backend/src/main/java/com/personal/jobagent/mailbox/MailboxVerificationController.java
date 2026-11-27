package com.personal.jobagent.mailbox;

import com.personal.jobagent.audit.AuditEntry;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.common.ApiError;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.identity.EmployerCredentialService;
import com.personal.jobagent.mailbox.managed.MailAddresses;
import com.personal.jobagent.mailbox.managed.ManagedMailVerificationProvider;
import com.personal.jobagent.security.OwnerContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * Worker-facing mailbox-verification boundary (Phase 8.5).
 *
 * <p>The worker authenticates with WORKER_EVENT_TOKEN and correlates every
 * request to one application it is executing; the owner is always derived from
 * that application, never taken from the request. Two calls:
 * <ul>
 *   <li>{@code registration-identity} — the address to register the employer
 *       account with: the candidate's Robin-managed address by default (see
 *       {@link RegistrationIdentityService}), or the address an existing account
 *       already uses. 409 {@code MANAGED_MAILBOX_NOT_READY} pauses the worker.</li>
 *   <li>{@code resolve} — the fresh code or link for that account, read from the
 *       mailbox it was registered with ({@link RoutingMailboxVerificationProvider}).
 *       With an account session, the session must be open (never HARD_STOP,
 *       FAILED or COMPLETED), its employer domain must match, its start bounds
 *       how old a message may be, and its registered address selects the
 *       mailbox; a released code moves it to VERIFY_EMAIL. A resolution is
 *       recorded (never the code or link) and a source message can be resolved
 *       only once.</li>
 * </ul>
 * Anything missing or ambiguous answers 404 and the worker pauses for the human.
 */
@RestController
@RequestMapping("/api/v1/mailbox-verification")
public class MailboxVerificationController {

    private final MailboxVerificationProvider provider;
    private final ManagedMailVerificationProvider managedProvider;
    private final RegistrationIdentityService registrationIdentities;
    private final OwnerContext ownerContext;
    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    private final EmployerCredentialService credentials;
    private final AuditLogWriter auditLog;
    private final int windowMinutes;
    private final int leaseSeconds;
    private final SecureRandom random = new SecureRandom();

    public MailboxVerificationController(MailboxVerificationProvider provider,
                                         RegistrationIdentityService registrationIdentities,
                                         OwnerContext ownerContext,
                                         JdbcTemplate db,
                                         PlatformTransactionManager transactionManager,
                                         @Value("${app.mailbox-verification.window-minutes:30}") int windowMinutes) {
        this(provider, registrationIdentities, ownerContext, db, transactionManager,
                provider instanceof ManagedMailVerificationProvider m ? m : null, null, null, windowMinutes, 180);
    }

    public MailboxVerificationController(MailboxVerificationProvider provider,
                                         RegistrationIdentityService registrationIdentities,
                                         OwnerContext ownerContext,
                                         JdbcTemplate db,
                                         PlatformTransactionManager transactionManager,
                                         ManagedMailVerificationProvider managedProvider,
                                         int windowMinutes,
                                         int leaseSeconds) {
        this(provider, registrationIdentities, ownerContext, db, transactionManager,
                managedProvider, null, null, windowMinutes, leaseSeconds);
    }

    @Autowired
    public MailboxVerificationController(MailboxVerificationProvider provider,
                                         RegistrationIdentityService registrationIdentities,
                                         OwnerContext ownerContext,
                                         JdbcTemplate db,
                                         PlatformTransactionManager transactionManager,
                                         @Autowired(required = false) ManagedMailVerificationProvider managedProvider,
                                         @Autowired(required = false) EmployerCredentialService credentials,
                                         @Autowired(required = false) AuditLogWriter auditLog,
                                         @Value("${app.mailbox-verification.window-minutes:30}") int windowMinutes,
                                         @Value("${app.mailbox-verification.lease-seconds:180}") int leaseSeconds) {
        this.provider = provider;
        this.managedProvider = managedProvider != null ? managedProvider
                : (provider instanceof ManagedMailVerificationProvider m ? m : null);
        this.registrationIdentities = registrationIdentities;
        this.ownerContext = ownerContext;
        this.db = db;
        this.transactions = new TransactionTemplate(transactionManager);
        this.credentials = credentials;
        this.auditLog = auditLog;
        this.windowMinutes = Math.max(1, Math.min(windowMinutes, 24 * 60));
        this.leaseSeconds = Math.max(30, Math.min(leaseSeconds, 900));
    }

    public record ResolveRequest(UUID applicationId, UUID sessionId,
                                 String employerDomain, String mailboxAddress) {}

    public record IdentityRequest(UUID applicationId, UUID sessionId, String employerDomain) {}

    public record LeaseActionRequest(UUID applicationId, UUID sessionId,
                                     UUID verificationId, String leaseToken, String reason) {}

    /** Owner probe: can Robin read a verification mailbox for this profile (managed or Gmail)? */
    @GetMapping("/connected")
    public ResponseEntity<?> connected() {
        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "a profile is required"));
        }
        return ResponseEntity.ok(Map.of("connected", provider.connected(profileId),
                "provider", provider.providerId()));
    }

    /** Worker call before sign-up: which address to register the employer account with. */
    @PostMapping("/registration-identity")
    public ResponseEntity<?> registrationIdentity(@RequestBody IdentityRequest r, HttpServletRequest request) {
        if (!ownerContext.isWorkerRequest()) return forbidden(request);
        if (r == null || r.applicationId() == null || r.employerDomain() == null || r.employerDomain().isBlank()) {
            return error(400, "Bad Request", "applicationId and employerDomain are required", request);
        }
        String employerDomain = MailAddresses.employerDomain(r.employerDomain()).orElse(null);
        if (employerDomain == null) return error(400, "Bad Request", "employerDomain must be a domain name", request);
        UUID ownerProfileId = ownerContext.ownerOfApplication(r.applicationId()).orElse(null);
        if (ownerProfileId == null) return error(404, "Not Found", "No such application", request);

        ResponseEntity<?> bindingViolation = enforceApplicationBinding(
                ownerProfileId, r.applicationId(), employerDomain, "REGISTRATION_IDENTITY", request);
        if (bindingViolation != null) return bindingViolation;

        UUID effectiveSessionId;
        try {
            effectiveSessionId = resolveEffectiveSessionId(ownerProfileId, r.applicationId(), r.sessionId());
        } catch (SessionConflictException e) {
            auditVerification("REGISTRATION_IDENTITY_REJECTED", ownerProfileId, r.applicationId(), r.sessionId(),
                    employerDomain, e.getMessage());
            return error(409, "Conflict", e.getMessage(), request);
        } catch (NoSuchElementException missing) {
            return error(404, "Not Found", "No such account session", request);
        }

        try {
            RegistrationIdentityService.RegistrationIdentity identity = registrationIdentities.resolve(
                    ownerProfileId, r.applicationId(), effectiveSessionId, employerDomain);
            auditVerification("REGISTRATION_IDENTITY_RESOLVED", ownerProfileId, r.applicationId(), effectiveSessionId,
                    employerDomain, identity.existing() ? "existing" : "pending");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("email", identity.email());
            body.put("mailbox", identity.mailbox());
            body.put("employerDomain", identity.employerDomain());
            body.put("identityId", identity.identityId().toString());
            body.put("existing", identity.existing());
            return ResponseEntity.ok(body);
        } catch (RegistrationIdentityService.ManagedMailboxNotReadyException notReady) {
            auditVerification("REGISTRATION_IDENTITY_REJECTED", ownerProfileId, r.applicationId(), effectiveSessionId,
                    employerDomain, notReady.reason());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", 409);
            body.put("reason", "MANAGED_MAILBOX_NOT_READY");
            body.put("managedMailbox", notReady.reason());
            body.put("detail", "Robin-managed email is not ready, so no employer account was registered. "
                    + "The workflow is paused.");
            return ResponseEntity.status(409).body(body);
        } catch (RegistrationIdentityService.SessionMismatchException mismatch) {
            auditVerification("REGISTRATION_IDENTITY_REJECTED", ownerProfileId, r.applicationId(), effectiveSessionId,
                    employerDomain, mismatch.getMessage());
            return error(409, "Conflict", mismatch.getMessage(), request);
        } catch (NoSuchElementException missing) {
            return error(404, "Not Found", "No such account session", request);
        } catch (IllegalStateException disabled) {
            auditVerification("REGISTRATION_IDENTITY_REJECTED", ownerProfileId, r.applicationId(), effectiveSessionId,
                    employerDomain, "IDENTITY_DISABLED");
            return error(409, "Conflict", disabled.getMessage(), request);
        }
    }

    /** Worker call right after employer signup form succeeds: promotes PENDING identity to ACTIVE. */
    @PostMapping("/registration-identity/confirm")
    public ResponseEntity<?> confirmRegistration(@RequestBody IdentityRequest r, HttpServletRequest request) {
        if (!ownerContext.isWorkerRequest()) return forbidden(request);
        if (r == null || r.applicationId() == null || r.employerDomain() == null || r.employerDomain().isBlank()) {
            return error(400, "Bad Request", "applicationId and employerDomain are required", request);
        }
        String employerDomain = MailAddresses.employerDomain(r.employerDomain()).orElse(null);
        if (employerDomain == null) return error(400, "Bad Request", "employerDomain must be a domain name", request);
        UUID ownerProfileId = ownerContext.ownerOfApplication(r.applicationId()).orElse(null);
        if (ownerProfileId == null) return error(404, "Not Found", "No such application", request);

        ResponseEntity<?> bindingViolation = enforceApplicationBinding(
                ownerProfileId, r.applicationId(), employerDomain, "REGISTRATION_IDENTITY_CONFIRM", request);
        if (bindingViolation != null) return bindingViolation;

        UUID effectiveSessionId;
        try {
            effectiveSessionId = resolveEffectiveSessionId(ownerProfileId, r.applicationId(), r.sessionId());
            if (effectiveSessionId != null) {
                validateOpenSessionForAction(ownerProfileId, r.applicationId(), effectiveSessionId, employerDomain);
            }
        } catch (SessionConflictException e) {
            auditVerification("REGISTRATION_IDENTITY_CONFIRM_REJECTED", ownerProfileId, r.applicationId(), r.sessionId(),
                    employerDomain, e.getMessage());
            return error(409, "Conflict", e.getMessage(), request);
        } catch (NoSuchElementException missing) {
            return error(404, "Not Found", "No such account session", request);
        }

        boolean promoted = registrationIdentities.confirmRegistration(ownerProfileId, employerDomain);
        auditVerification("REGISTRATION_IDENTITY_CONFIRMED", ownerProfileId, r.applicationId(), effectiveSessionId,
                employerDomain, promoted ? "promoted" : "already_active");
        return ResponseEntity.ok(Map.of("confirmed", true, "promoted", promoted));
    }

    /**
     * Worker call during ACCOUNT_VERIFY_OTP: resolves the freshest code or link
     * for this application's employer domain. 404 when nothing unambiguous is
     * found (the worker then pauses for the human).
     */
    @PostMapping("/resolve")
    public ResponseEntity<?> resolve(@RequestBody ResolveRequest r, HttpServletRequest request) {
        if (!ownerContext.isWorkerRequest()) return forbidden(request);
        if (r == null || r.applicationId() == null || r.employerDomain() == null || r.employerDomain().isBlank()) {
            return error(400, "Bad Request", "applicationId and employerDomain are required", request);
        }
        String employerDomain = MailAddresses.employerDomain(r.employerDomain()).orElse(null);
        if (employerDomain == null) return error(400, "Bad Request", "employerDomain must be a domain name", request);
        // The application must belong to the profile whose mailbox is queried:
        // the provider is scoped to that profile, so cross-user mail is unreachable.
        UUID ownerProfileId = ownerContext.ownerOfApplication(r.applicationId()).orElse(null);
        if (ownerProfileId == null) return error(404, "Not Found", "No such application", request);

        ResponseEntity<?> bindingViolation = enforceApplicationBinding(
                ownerProfileId, r.applicationId(), employerDomain, "MAILBOX_VERIFICATION_RESOLVE", request);
        if (bindingViolation != null) return bindingViolation;

        Instant now = dbNow();
        Instant after = now.minusSeconds(windowMinutes * 60L);
        String mailboxAddress = r.mailboxAddress() == null || r.mailboxAddress().isBlank()
                ? null : r.mailboxAddress().trim().toLowerCase(Locale.ROOT);
        UUID accountSessionId;
        try {
            accountSessionId = resolveEffectiveSessionId(ownerProfileId, r.applicationId(), r.sessionId());
        } catch (SessionConflictException e) {
            auditVerification("MAILBOX_VERIFICATION_RESOLVE_REJECTED", ownerProfileId, r.applicationId(),
                    r.sessionId(), employerDomain, e.getMessage());
            return error(409, "Conflict", e.getMessage(), request);
        } catch (NoSuchElementException missing) {
            return error(404, "Not Found", "No such account session", request);
        }
        if (accountSessionId != null) {
            List<Map<String, Object>> sessions = db.queryForList("""
                    select s.application_id, s.expected_domain, s.created_at, s.state,
                           i.applicant_email::text as registered_email
                    from account_sessions s
                    left join applicant_identities i on i.id = s.applicant_identity_id and i.profile_id = s.owner_profile_id
                    where s.id = ? and s.owner_profile_id = ?
                    """, accountSessionId, ownerProfileId);
            if (sessions.isEmpty() || !r.applicationId().equals(sessions.get(0).get("application_id"))) {
                return error(404, "Not Found", "No such account session", request);
            }
            Map<String, Object> session = sessions.get(0);
            if (RegistrationIdentityService.CLOSED_SESSION_STATES.contains(String.valueOf(session.get("state")))) {
                auditVerification("MAILBOX_VERIFICATION_RESOLVE_REJECTED", ownerProfileId, r.applicationId(),
                        accountSessionId, employerDomain, "SESSION_CLOSED:" + session.get("state"));
                return error(409, "Conflict", "The account session is closed (" + session.get("state") + ")", request);
            }
            String expected = MailAddresses.employerDomain(String.valueOf(session.get("expected_domain"))).orElse("");
            if (!employerDomain.equals(expected)) {
                auditVerification("MAILBOX_VERIFICATION_RESOLVE_REJECTED", ownerProfileId, r.applicationId(),
                        accountSessionId, employerDomain, "SESSION_DOMAIN_MISMATCH");
                return error(409, "Conflict", "The account session is for another employer domain", request);
            }
            Instant sessionStart = ((Timestamp) session.get("created_at")).toInstant();
            if (sessionStart.isAfter(after)) after = sessionStart;
            String registered = session.get("registered_email") == null
                    ? null : String.valueOf(session.get("registered_email")).toLowerCase(Locale.ROOT);
            if (registered != null) {
                if (mailboxAddress != null && !mailboxAddress.equals(registered)) {
                    auditVerification("MAILBOX_VERIFICATION_RESOLVE_REJECTED", ownerProfileId, r.applicationId(),
                            accountSessionId, employerDomain, "MAILBOX_MISMATCH");
                    return error(409, "Conflict", "The mailbox is not the one this account was registered with",
                            request);
                }
                mailboxAddress = registered;
            }
        }

        final UUID finalSessionId = accountSessionId;
        final String finalMailboxAddress = mailboxAddress;
        final Instant finalAfter = after;

        // Path A: idempotent crash recovery of an active lease held by this application/session.
        ResponseEntity<?> recovered = tryRecoverActiveLease(
                ownerProfileId, r.applicationId(), finalSessionId, employerDomain, finalMailboxAddress, finalAfter, request);
        if (recovered != null) {
            return recovered;
        }

        // Path B: look for a fresh unconsumed, unleased message outside DB transactions.
        var hit = provider.findVerification(ownerProfileId, employerDomain, finalMailboxAddress, finalAfter);
        if (hit.isEmpty() || (!hit.get().hasOtp() && !hit.get().hasLink())) {
            return error(404, "Not Found", "No unambiguous verification message found in the registered mailbox. "
                    + "The workflow is paused for manual verification.", request);
        }
        var hitValue = hit.get();
        String leaseToken = generateLeaseToken();
        String tokenSha = sha256Hex(leaseToken);
        UUID verificationId = UuidV7.generate();
        Instant expiresAt = dbNow().plusSeconds(leaseSeconds);

        try {
            Boolean acquired = transactions.execute(status -> {
                Instant tsInstant = dbNow();
                Timestamp tsNow = Timestamp.from(tsInstant);
                expireOverdueLeases(tsNow);
                if (hitValue.sourceEmailId() != null) {
                    Integer activeOrUsed = db.queryForObject("""
                            select count(*) from mailbox_verifications
                            where source_email_id = ? and status in ('RESERVED', 'CONSUMED')
                            """, Integer.class, hitValue.sourceEmailId());
                    if (activeOrUsed != null && activeOrUsed > 0) {
                        return Boolean.FALSE;
                    }
                }
                if (hitValue.sourceManagedMessageId() != null && managedProvider != null) {
                    if (!managedProvider.tryReserveManagedMessage(ownerProfileId, hitValue.sourceManagedMessageId(),
                            r.applicationId(), finalSessionId, expiresAt, tokenSha)) {
                        return Boolean.FALSE;
                    }
                }
                db.update("""
                        insert into mailbox_verifications
                            (id, application_id, account_session_id, employer_domain, provider_id,
                             source_email_id, source_managed_message_id, status, reserved_until,
                             reservation_token_sha256, created_at)
                        values (?, ?, ?, ?, ?, ?, ?, 'RESERVED', ?, ?, ?)
                        """,
                        verificationId, r.applicationId(), finalSessionId, employerDomain,
                        hitValue.providerId(), hitValue.sourceEmailId(), hitValue.sourceManagedMessageId(),
                        Timestamp.from(expiresAt), tokenSha, tsNow);
                if (finalSessionId != null) {
                    db.update("""
                            update account_sessions set state = 'VERIFY_EMAIL', updated_at = now()
                            where id = ? and owner_profile_id = ?
                              and state in ('SIGNUP', 'WAITING_FOR_EMAIL', 'OTP_REQUIRED')
                            """, finalSessionId, ownerProfileId);
                }
                return Boolean.TRUE;
            });
            if (!Boolean.TRUE.equals(acquired)) {
                // Stage 3I Phase 6: a concurrent /resolve for this same application and session may have
                // reserved the message between Path A and Path B (its reservation, not a key collision,
                // makes ours fail). Converge on that lease instead of reporting nothing found; a lease
                // held for any other application or session is never returned here.
                ResponseEntity<?> racedRecovery = tryRecoverActiveLease(
                        ownerProfileId, r.applicationId(), finalSessionId, employerDomain, finalMailboxAddress, finalAfter, request);
                if (racedRecovery != null) {
                    return racedRecovery;
                }
                return error(404, "Not Found", "This verification message is currently leased or resolved; "
                        + "request a fresh code or pause for manual verification.", request);
            }
        } catch (DuplicateKeyException conflict) {
            ResponseEntity<?> racedRecovery = tryRecoverActiveLease(
                    ownerProfileId, r.applicationId(), finalSessionId, employerDomain, finalMailboxAddress, finalAfter, request);
            if (racedRecovery != null) {
                return racedRecovery;
            }
            return error(404, "Not Found", "This verification message was already resolved; request a fresh code "
                    + "or pause for manual verification.", request);
        }
        auditVerification("MAILBOX_VERIFICATION_LEASE_ACQUIRED", ownerProfileId, r.applicationId(), finalSessionId,
                employerDomain, verificationId.toString());
        return ResponseEntity.ok(leaseResponseBody(hitValue, verificationId, leaseToken, expiresAt));
    }

    /**
     * Worker confirmation after the verification code or link has genuinely succeeded in the browser:
     * transitions the bounded lease to CONSUMED and erases managed encrypted content permanently.
     */
    @PostMapping("/confirm")
    public ResponseEntity<?> confirm(@RequestBody LeaseActionRequest r, HttpServletRequest request) {
        if (!ownerContext.isWorkerRequest()) return forbidden(request);
        if (r == null || r.applicationId() == null || r.verificationId() == null
                || r.leaseToken() == null || r.leaseToken().isBlank()) {
            return error(400, "Bad Request", "applicationId, verificationId and leaseToken are required", request);
        }
        UUID ownerProfileId = ownerContext.ownerOfApplication(r.applicationId()).orElse(null);
        if (ownerProfileId == null) return error(404, "Not Found", "No such application", request);

        final UUID effectiveSessionId;
        try {
            effectiveSessionId = resolveEffectiveSessionId(ownerProfileId, r.applicationId(), r.sessionId());
        } catch (SessionConflictException e) {
            auditVerification("MAILBOX_VERIFICATION_CONFIRM_REJECTED", ownerProfileId, r.applicationId(),
                    r.sessionId(), null, e.getMessage());
            return error(409, "Conflict", e.getMessage(), request);
        } catch (NoSuchElementException missing) {
            return error(404, "Not Found", "No such account session", request);
        }

        String suppliedSha = sha256Hex(r.leaseToken().trim());

        ResponseEntity<?> result = transactions.execute(status -> {
            List<Map<String, Object>> rows = db.queryForList("""
                    select id, account_session_id, employer_domain, provider_id, source_email_id,
                           source_managed_message_id, status, reserved_until, reservation_token_sha256
                    from mailbox_verifications
                    where id = ? and application_id = ?
                    for update
                    """, r.verificationId(), r.applicationId());
            if (rows.isEmpty()) {
                return error(404, "Not Found", "No such verification lease", request);
            }
            Map<String, Object> row = rows.get(0);
            String employerDomain = MailAddresses.employerDomain(String.valueOf(row.get("employer_domain"))).orElse("");
            ResponseEntity<?> bindingViolation = enforceApplicationBinding(
                    ownerProfileId, r.applicationId(), employerDomain, "MAILBOX_VERIFICATION_CONFIRM", request);
            if (bindingViolation != null) return bindingViolation;

            UUID sessionBound = (UUID) row.get("account_session_id");
            if (effectiveSessionId != null) {
                if (sessionBound != null && !effectiveSessionId.equals(sessionBound)) {
                    return error(409, "Conflict", "The verification lease belongs to another session", request);
                }
                try {
                    validateOpenSessionForAction(ownerProfileId, r.applicationId(), effectiveSessionId, employerDomain);
                } catch (SessionConflictException e) {
                    return error(409, "Conflict", e.getMessage(), request);
                } catch (NoSuchElementException missing) {
                    return error(404, "Not Found", "No such account session", request);
                }
            }
            String expectedSha = row.get("reservation_token_sha256") == null
                    ? null : String.valueOf(row.get("reservation_token_sha256"));
            if (!constantTimeHexEquals(expectedSha, suppliedSha)) {
                auditVerification("MAILBOX_VERIFICATION_CONFIRM_REJECTED", ownerProfileId, r.applicationId(),
                        effectiveSessionId, employerDomain, "INVALID_LEASE_TOKEN");
                return error(409, "Conflict", "Invalid verification lease token", request);
            }
            String currentStatus = String.valueOf(row.get("status"));
            if ("CONSUMED".equals(currentStatus)) {
                return ResponseEntity.ok(Map.of(
                        "status", "CONSUMED",
                        "verificationId", r.verificationId().toString(),
                        "alreadyConfirmed", true));
            }
            Timestamp reservedUntilTs = (Timestamp) row.get("reserved_until");
            Instant now = dbNow();
            if (!"RESERVED".equals(currentStatus)
                    || (reservedUntilTs != null && !reservedUntilTs.toInstant().isAfter(now))) {
                UUID sourceManagedId = (UUID) row.get("source_managed_message_id");
                if ("RESERVED".equals(currentStatus)) {
                    db.update("update mailbox_verifications set status = 'EXPIRED', reserved_until = null where id = ?",
                            r.verificationId());
                    if (sourceManagedId != null && managedProvider != null) {
                        managedProvider.releaseManagedMessage(ownerProfileId, sourceManagedId, r.applicationId());
                    }
                }
                auditVerification("MAILBOX_VERIFICATION_CONFIRM_REJECTED", ownerProfileId, r.applicationId(),
                        effectiveSessionId, employerDomain, "LEASE_EXPIRED");
                return error(409, "Conflict", "Verification lease is expired or no longer active", request);
            }

            UUID sourceManagedId = (UUID) row.get("source_managed_message_id");
            UUID sourceEmailId = (UUID) row.get("source_email_id");
            String providerId = String.valueOf(row.get("provider_id"));
            db.update("""
                    update mailbox_verifications
                    set status = 'CONSUMED', consumed_at = now(), reserved_until = null
                    where id = ? and application_id = ? and status = 'RESERVED'
                    """, r.verificationId(), r.applicationId());
            provider.markResolved(ownerProfileId, new MailboxVerificationProvider.VerificationHit(
                    providerId,
                    sourceManagedId != null ? sourceManagedId.toString() : String.valueOf(sourceEmailId),
                    sourceEmailId, null, null, null, sourceManagedId));
            if (registrationIdentities != null && !employerDomain.isBlank()) {
                registrationIdentities.confirmRegistration(ownerProfileId, employerDomain);
            }
            auditVerification("MAILBOX_VERIFICATION_CONFIRMED", ownerProfileId, r.applicationId(),
                    effectiveSessionId, employerDomain, r.verificationId().toString());
            return ResponseEntity.ok(Map.of(
                    "status", "CONSUMED",
                    "verificationId", r.verificationId().toString()));
        });
        return result != null ? result : error(500, "Internal Server Error", "Confirmation failed", request);
    }

    /**
     * Releases an unused verification lease (e.g. OTP rejected or navigation aborted before use)
     * without burning or deleting the still-encrypted inbound message.
     */
    @PostMapping("/release")
    public ResponseEntity<?> release(@RequestBody LeaseActionRequest r, HttpServletRequest request) {
        if (!ownerContext.isWorkerRequest()) return forbidden(request);
        if (r == null || r.applicationId() == null || r.verificationId() == null
                || r.leaseToken() == null || r.leaseToken().isBlank()) {
            return error(400, "Bad Request", "applicationId, verificationId and leaseToken are required", request);
        }
        UUID ownerProfileId = ownerContext.ownerOfApplication(r.applicationId()).orElse(null);
        if (ownerProfileId == null) return error(404, "Not Found", "No such application", request);

        final UUID effectiveSessionId;
        try {
            effectiveSessionId = resolveEffectiveSessionId(ownerProfileId, r.applicationId(), r.sessionId());
        } catch (SessionConflictException e) {
            auditVerification("MAILBOX_VERIFICATION_RELEASE_REJECTED", ownerProfileId, r.applicationId(),
                    r.sessionId(), null, e.getMessage());
            return error(409, "Conflict", e.getMessage(), request);
        } catch (NoSuchElementException missing) {
            return error(404, "Not Found", "No such account session", request);
        }

        String suppliedSha = sha256Hex(r.leaseToken().trim());

        ResponseEntity<?> result = transactions.execute(status -> {
            List<Map<String, Object>> rows = db.queryForList("""
                    select id, account_session_id, employer_domain, source_managed_message_id,
                           status, reserved_until, reservation_token_sha256
                    from mailbox_verifications
                    where id = ? and application_id = ?
                    for update
                    """, r.verificationId(), r.applicationId());
            if (rows.isEmpty()) {
                return error(404, "Not Found", "No such verification lease", request);
            }
            Map<String, Object> row = rows.get(0);
            String employerDomain = MailAddresses.employerDomain(String.valueOf(row.get("employer_domain"))).orElse("");
            ResponseEntity<?> bindingViolation = enforceApplicationBinding(
                    ownerProfileId, r.applicationId(), employerDomain, "MAILBOX_VERIFICATION_RELEASE", request);
            if (bindingViolation != null) return bindingViolation;

            UUID sessionBound = (UUID) row.get("account_session_id");
            if (effectiveSessionId != null) {
                if (sessionBound != null && !effectiveSessionId.equals(sessionBound)) {
                    return error(409, "Conflict", "The verification lease belongs to another session", request);
                }
                try {
                    validateOpenSessionForAction(ownerProfileId, r.applicationId(), effectiveSessionId, employerDomain);
                } catch (SessionConflictException e) {
                    return error(409, "Conflict", e.getMessage(), request);
                } catch (NoSuchElementException missing) {
                    return error(404, "Not Found", "No such account session", request);
                }
            }
            String expectedSha = row.get("reservation_token_sha256") == null
                    ? null : String.valueOf(row.get("reservation_token_sha256"));
            if (!constantTimeHexEquals(expectedSha, suppliedSha)) {
                auditVerification("MAILBOX_VERIFICATION_RELEASE_REJECTED", ownerProfileId, r.applicationId(),
                        effectiveSessionId, employerDomain, "INVALID_LEASE_TOKEN");
                return error(409, "Conflict", "Invalid verification lease token", request);
            }
            String currentStatus = String.valueOf(row.get("status"));
            if ("RELEASED".equals(currentStatus)) {
                return ResponseEntity.ok(Map.of("status", "RELEASED", "verificationId", r.verificationId().toString()));
            }
            Timestamp reservedUntilTs = (Timestamp) row.get("reserved_until");
            Instant now = dbNow();
            UUID sourceManagedId = (UUID) row.get("source_managed_message_id");
            if (!"RESERVED".equals(currentStatus)
                    || (reservedUntilTs != null && !reservedUntilTs.toInstant().isAfter(now))) {
                if ("RESERVED".equals(currentStatus)) {
                    db.update("update mailbox_verifications set status = 'EXPIRED', reserved_until = null where id = ?",
                            r.verificationId());
                    if (sourceManagedId != null && managedProvider != null) {
                        managedProvider.releaseManagedMessage(ownerProfileId, sourceManagedId, r.applicationId());
                    }
                }
                auditVerification("MAILBOX_VERIFICATION_RELEASE_REJECTED", ownerProfileId, r.applicationId(),
                        effectiveSessionId, employerDomain, "LEASE_EXPIRED");
                return error(409, "Conflict", "Verification lease can no longer be released (" + currentStatus + ")", request);
            }
            db.update("""
                    update mailbox_verifications
                    set status = 'RELEASED', reserved_until = null
                    where id = ? and application_id = ? and status = 'RESERVED'
                    """, r.verificationId(), r.applicationId());
            if (sourceManagedId != null && managedProvider != null) {
                managedProvider.releaseManagedMessage(ownerProfileId, sourceManagedId, r.applicationId());
            }
            auditVerification("MAILBOX_VERIFICATION_RELEASED", ownerProfileId, r.applicationId(),
                    effectiveSessionId, employerDomain, r.verificationId().toString());
            return ResponseEntity.ok(Map.of(
                    "status", "RELEASED",
                    "verificationId", r.verificationId().toString()));
        });
        return result != null ? result : error(500, "Internal Server Error", "Release failed", request);
    }

    private ResponseEntity<?> tryRecoverActiveLease(UUID ownerProfileId,
                                                    UUID applicationId,
                                                    UUID sessionId,
                                                    String employerDomain,
                                                    String mailboxAddress,
                                                    Instant after,
                                                    HttpServletRequest request) {
        record ActiveLease(UUID verificationId, String domain, UUID sourceManagedId, UUID sourceEmailId) {}
        ActiveLease existing = transactions.execute(status -> {
            Timestamp tsNow = Timestamp.from(dbNow());
            expireOverdueLeases(tsNow);
            List<Map<String, Object>> rows = db.queryForList("""
                    select id, employer_domain, source_managed_message_id, source_email_id
                    from mailbox_verifications
                    where application_id = ?
                      and account_session_id is not distinct from ?::uuid
                      and status = 'RESERVED'
                      and reserved_until > ?
                    order by created_at desc
                    limit 1
                    for update
                    """, applicationId, sessionId, tsNow);
            if (rows.isEmpty()) return null;
            Map<String, Object> row = rows.get(0);
            return new ActiveLease(
                    (UUID) row.get("id"),
                    String.valueOf(row.get("employer_domain")),
                    (UUID) row.get("source_managed_message_id"),
                    (UUID) row.get("source_email_id"));
        });
        if (existing == null) return null;
        if (!employerDomain.equals(existing.domain())) {
            auditVerification("MAILBOX_VERIFICATION_RESOLVE_REJECTED", ownerProfileId, applicationId,
                    sessionId, employerDomain, "LEASE_DOMAIN_MISMATCH");
            return error(409, "Conflict", "The verification lease is for another employer domain", request);
        }

        Optional<MailboxVerificationProvider.VerificationHit> hit = Optional.empty();
        if (existing.sourceManagedId() != null && managedProvider != null) {
            hit = managedProvider.readReservedVerification(
                    ownerProfileId, employerDomain, mailboxAddress, existing.sourceManagedId());
        } else {
            Optional<MailboxVerificationProvider.VerificationHit> found = provider.findVerification(
                    ownerProfileId, employerDomain, mailboxAddress, after);
            if (found.isPresent() && (existing.sourceEmailId() == null || existing.sourceEmailId().equals(found.get().sourceEmailId()))) {
                hit = found;
            }
        }
        if (hit.isEmpty() || (!hit.get().hasOtp() && !hit.get().hasLink())) {
            transactions.executeWithoutResult(status -> {
                db.update("update mailbox_verifications set status = 'EXPIRED', reserved_until = null where id = ? and status = 'RESERVED'",
                        existing.verificationId());
                if (existing.sourceManagedId() != null && managedProvider != null) {
                    managedProvider.releaseManagedMessage(ownerProfileId, existing.sourceManagedId(), applicationId);
                }
            });
            return null;
        }

        String leaseToken = generateLeaseToken();
        Instant expiresAt = dbNow().plusSeconds(leaseSeconds);
        String tokenSha = sha256Hex(leaseToken);
        Boolean refreshed = transactions.execute(status -> {
            if (existing.sourceManagedId() != null && managedProvider != null) {
                if (!managedProvider.refreshManagedLease(ownerProfileId, existing.sourceManagedId(), applicationId,
                        expiresAt, tokenSha)) {
                    return Boolean.FALSE;
                }
            }
            int updated = db.update("""
                    update mailbox_verifications
                    set reserved_until = ?, reservation_token_sha256 = ?
                    where id = ? and application_id = ? and status = 'RESERVED'
                    """, Timestamp.from(expiresAt), tokenSha, existing.verificationId(), applicationId);
            return updated == 1;
        });
        if (!Boolean.TRUE.equals(refreshed)) {
            return null;
        }
        auditVerification("MAILBOX_VERIFICATION_LEASE_RECOVERED", ownerProfileId, applicationId, sessionId,
                employerDomain, existing.verificationId().toString());
        return ResponseEntity.ok(leaseResponseBody(hit.get(), existing.verificationId(), leaseToken, expiresAt));
    }

    private Instant dbNow() {
        Timestamp ts = db.queryForObject("select now()", Timestamp.class);
        return ts != null ? ts.toInstant() : Instant.now();
    }

    private void expireOverdueLeases(Timestamp tsNow) {
        db.update("""
                update mailbox_verifications
                set status = 'EXPIRED', reserved_until = null
                where status = 'RESERVED' and reserved_until <= ?
                """, tsNow);
        db.update("""
                update managed_inbound_messages
                set reserved_for_application_id = null,
                    reserved_for_session_id = null,
                    reserved_at = null,
                    reservation_expires_at = null,
                    reservation_token_sha256 = null
                where consumed_at is null and reserved_at is not null and reservation_expires_at <= ?
                """, tsNow);
    }

    private Map<String, Object> leaseResponseBody(MailboxVerificationProvider.VerificationHit hitValue,
                                                    UUID verificationId, String leaseToken, Instant expiresAt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("provider", hitValue.providerId());
        body.put("otp", hitValue.hasOtp() ? hitValue.otp() : "");
        body.put("link", hitValue.hasLink() ? hitValue.link() : "");
        body.put("receivedAt", hitValue.receivedAt() == null ? "" : hitValue.receivedAt().toString());
        body.put("verificationId", verificationId.toString());
        body.put("leaseToken", leaseToken);
        body.put("leaseExpiresAt", expiresAt.toString());
        return body;
    }

    private String generateLeaseToken() {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static boolean constantTimeHexEquals(String expected, String actual) {
        if (expected == null || actual == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private ResponseEntity<?> enforceApplicationBinding(UUID ownerProfileId, UUID applicationId,
                                                      String employerDomain, String action, HttpServletRequest request) {
        // Stage 3I Phase 6: fail closed. Without the binding (running plan + permitted domains) nothing
        // may be resolved, confirmed or released for an application.
        EmployerCredentialService.ApplicationBinding binding = credentials == null ? null
                : credentials.bindingFor(ownerProfileId, applicationId);
        if (binding == null) {
            auditVerification(action + "_REJECTED", ownerProfileId, applicationId, null, employerDomain, "BINDING_UNAVAILABLE");
            return error(503, "Service Unavailable", "The application's automation binding could not be verified.", request);
        }
        if (!binding.running()) {
            auditVerification(action + "_REJECTED", ownerProfileId, applicationId, null, employerDomain, "NO_RUNNING_PLAN");
            return error(409, "Conflict", "No automation run is currently executing for this application.", request);
        }
        boolean domainMatch = binding.permittedDomains().stream()
                .map(d -> MailAddresses.employerDomain(d).orElse(d))
                .anyMatch(d -> d.equalsIgnoreCase(employerDomain));
        if (!domainMatch) {
            auditVerification(action + "_REJECTED", ownerProfileId, applicationId, null, employerDomain, "DOMAIN_NOT_PERMITTED");
            return error(403, "Forbidden", "Employer domain is not bound to this application.", request);
        }
        return null;
    }

    private UUID resolveEffectiveSessionId(UUID ownerProfileId, UUID applicationId, UUID requestedSessionId) {
        List<Map<String, Object>> appRows = db.queryForList(
                "select account_session_id from applications where id = ? and profile_id = ?",
                applicationId, ownerProfileId);
        if (appRows.isEmpty()) {
            throw new NoSuchElementException("No such application");
        }
        UUID appSessionId = (UUID) appRows.get(0).get("account_session_id");
        if (requestedSessionId != null) {
            Integer owned = db.queryForObject(
                    "select count(*) from account_sessions where id = ? and owner_profile_id = ? and application_id = ?",
                    Integer.class, requestedSessionId, ownerProfileId, applicationId);
            if (owned == null || owned == 0) {
                throw new NoSuchElementException("No such account session");
            }
            if (appSessionId != null && !requestedSessionId.equals(appSessionId)) {
                throw new SessionConflictException("Session does not match this application's active account session");
            }
            return requestedSessionId;
        }
        return appSessionId;
    }

    private void validateOpenSessionForAction(UUID ownerProfileId, UUID applicationId, UUID sessionId, String employerDomain) {
        List<Map<String, Object>> rows = db.queryForList("""
                select state, expected_domain from account_sessions
                where id = ? and owner_profile_id = ? and application_id = ?
                """, sessionId, ownerProfileId, applicationId);
        if (rows.isEmpty()) {
            throw new NoSuchElementException("No such account session");
        }
        Map<String, Object> session = rows.get(0);
        String state = String.valueOf(session.get("state"));
        if ("COMPLETED".equals(state) || "FAILED".equals(state) || "HARD_STOP".equals(state)) {
            throw new SessionConflictException("The account session is already closed (" + state + ")");
        }
        String expected = MailAddresses.employerDomain(String.valueOf(session.get("expected_domain"))).orElse(null);
        if (expected == null || !expected.equals(employerDomain)) {
            throw new SessionConflictException("Employer domain does not match the account session");
        }
    }

    private void auditVerification(String action, UUID profileId, UUID applicationId, UUID sessionId,
                                   String employerDomain, String detail) {
        if (auditLog == null) return;
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (profileId != null) metadata.put("profileId", profileId.toString());
        if (applicationId != null) metadata.put("applicationId", applicationId.toString());
        if (sessionId != null) metadata.put("sessionId", sessionId.toString());
        if (employerDomain != null) metadata.put("employerDomain", employerDomain);
        if (detail != null) metadata.put("detail", detail);
        auditLog.write(new AuditEntry(
                "worker", action, "APPLICATION",
                applicationId,
                null, metadata, null, parseCorrelationUuid()));
    }

    private UUID parseCorrelationUuid() {
        String raw = org.slf4j.MDC.get("correlation_id");
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static final class SessionConflictException extends RuntimeException {
        SessionConflictException(String message) { super(message); }
    }

    private ResponseEntity<?> forbidden(HttpServletRequest request) {
        return error(403, "Forbidden", "Only the automation worker may use mailbox verification.", request);
    }

    private ResponseEntity<?> error(int status, String title, String detail, HttpServletRequest request) {
        return ResponseEntity.status(status).body(ApiError.of(status, title, detail, request.getRequestURI(),
                correlationId()));
    }

    private String correlationId() {
        return String.valueOf(org.slf4j.MDC.get("correlation_id"));
    }
}
