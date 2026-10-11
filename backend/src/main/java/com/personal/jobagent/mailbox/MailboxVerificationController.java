package com.personal.jobagent.mailbox;

import com.personal.jobagent.common.ApiError;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.mailbox.managed.MailAddresses;
import com.personal.jobagent.security.OwnerContext;
import jakarta.servlet.http.HttpServletRequest;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
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
    private final RegistrationIdentityService registrationIdentities;
    private final OwnerContext ownerContext;
    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    private final int windowMinutes;

    public MailboxVerificationController(MailboxVerificationProvider provider,
                                         RegistrationIdentityService registrationIdentities,
                                         OwnerContext ownerContext,
                                         JdbcTemplate db,
                                         PlatformTransactionManager transactionManager,
                                         @Value("${app.mailbox-verification.window-minutes:30}") int windowMinutes) {
        this.provider = provider;
        this.registrationIdentities = registrationIdentities;
        this.ownerContext = ownerContext;
        this.db = db;
        this.transactions = new TransactionTemplate(transactionManager);
        this.windowMinutes = Math.max(1, Math.min(windowMinutes, 24 * 60));
    }

    public record ResolveRequest(UUID applicationId, UUID sessionId,
                                 String employerDomain, String mailboxAddress) {}

    public record IdentityRequest(UUID applicationId, UUID sessionId, String employerDomain) {}

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
        UUID ownerProfileId = ownerContext.ownerOfApplication(r.applicationId()).orElse(null);
        if (ownerProfileId == null) return error(404, "Not Found", "No such application", request);
        try {
            RegistrationIdentityService.RegistrationIdentity identity = registrationIdentities.resolve(
                    ownerProfileId, r.applicationId(), r.sessionId(), r.employerDomain());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("email", identity.email());
            body.put("mailbox", identity.mailbox());
            body.put("employerDomain", identity.employerDomain());
            body.put("identityId", identity.identityId().toString());
            body.put("existing", identity.existing());
            return ResponseEntity.ok(body);
        } catch (RegistrationIdentityService.ManagedMailboxNotReadyException notReady) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", 409);
            body.put("reason", "MANAGED_MAILBOX_NOT_READY");
            body.put("managedMailbox", notReady.reason());
            body.put("detail", "Robin-managed email is not ready, so no employer account was registered. "
                    + "The workflow is paused.");
            return ResponseEntity.status(409).body(body);
        } catch (RegistrationIdentityService.SessionMismatchException mismatch) {
            return error(409, "Conflict", mismatch.getMessage(), request);
        } catch (NoSuchElementException missing) {
            return error(404, "Not Found", "No such account session", request);
        }
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

        Instant now = Instant.now();
        Instant after = now.minusSeconds(windowMinutes * 60L);
        String mailboxAddress = r.mailboxAddress() == null || r.mailboxAddress().isBlank()
                ? null : r.mailboxAddress().trim().toLowerCase(Locale.ROOT);
        UUID accountSessionId = null;
        if (r.sessionId() != null) {
            List<Map<String, Object>> sessions = db.queryForList("""
                    select s.application_id, s.expected_domain, s.created_at, s.state,
                           i.applicant_email::text as registered_email
                    from account_sessions s
                    left join applicant_identities i on i.id = s.applicant_identity_id and i.profile_id = s.owner_profile_id
                    where s.id = ? and s.owner_profile_id = ?
                    """, r.sessionId(), ownerProfileId);
            if (sessions.isEmpty() || !r.applicationId().equals(sessions.get(0).get("application_id"))) {
                return error(404, "Not Found", "No such account session", request);
            }
            Map<String, Object> session = sessions.get(0);
            if (RegistrationIdentityService.CLOSED_SESSION_STATES.contains(String.valueOf(session.get("state")))) {
                // A hard-stopped, failed or completed session never receives another code.
                return error(409, "Conflict", "The account session is closed (" + session.get("state") + ")", request);
            }
            String expected = MailAddresses.employerDomain(String.valueOf(session.get("expected_domain"))).orElse("");
            if (!employerDomain.equals(expected)) {
                return error(409, "Conflict", "The account session is for another employer domain", request);
            }
            // Mail that arrived before this account session began cannot belong to it.
            Instant sessionStart = ((Timestamp) session.get("created_at")).toInstant();
            if (sessionStart.isAfter(after)) after = sessionStart;
            String registered = session.get("registered_email") == null
                    ? null : String.valueOf(session.get("registered_email")).toLowerCase(Locale.ROOT);
            if (registered != null) {
                if (mailboxAddress != null && !mailboxAddress.equals(registered)) {
                    return error(409, "Conflict", "The mailbox is not the one this account was registered with",
                            request);
                }
                mailboxAddress = registered;
            }
            accountSessionId = r.sessionId();
        }

        var hit = provider.findVerification(ownerProfileId, employerDomain, mailboxAddress, after);
        if (hit.isEmpty() || (!hit.get().hasOtp() && !hit.get().hasLink())) {
            return error(404, "Not Found", "No unambiguous verification message found in the registered mailbox. "
                    + "The workflow is paused for manual verification.", request);
        }
        var hitValue = hit.get();
        UUID sessionForAudit = accountSessionId;
        // Audit before releasing a one-time credential; never store OTPs or token-bearing links.
        // The audit row and the provider's retirement of the source message commit together.
        try {
            transactions.executeWithoutResult(status -> {
                db.update("""
                        insert into mailbox_verifications
                            (id, application_id, account_session_id, employer_domain, provider_id,
                             source_email_id, source_managed_message_id, created_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        UuidV7.generate(), r.applicationId(), sessionForAudit, employerDomain,
                        hitValue.providerId(), hitValue.sourceEmailId(), hitValue.sourceManagedMessageId(),
                        Timestamp.from(Instant.now()));
                provider.markResolved(ownerProfileId, hitValue);
                if (sessionForAudit != null) {
                    // The code or link is on its way to the employer form: the session is verifying.
                    db.update("""
                            update account_sessions set state = 'VERIFY_EMAIL', updated_at = now()
                            where id = ? and owner_profile_id = ?
                              and state in ('SIGNUP', 'WAITING_FOR_EMAIL', 'OTP_REQUIRED')
                            """, sessionForAudit, ownerProfileId);
                }
            });
        } catch (DuplicateKeyException alreadyResolved) {
            return error(404, "Not Found", "This verification message was already resolved; request a fresh code "
                    + "or pause for manual verification.", request);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("provider", hitValue.providerId());
        body.put("otp", hitValue.hasOtp() ? hitValue.otp() : "");
        body.put("link", hitValue.hasLink() ? hitValue.link() : "");
        body.put("receivedAt", hitValue.receivedAt() == null ? "" : hitValue.receivedAt().toString());
        return ResponseEntity.ok(body);
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
