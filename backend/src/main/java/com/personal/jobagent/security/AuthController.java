package com.personal.jobagent.security;

import com.personal.jobagent.audit.AuditEntry;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.common.ApiError;
import com.personal.jobagent.common.CorrelationIdFilter;
import com.personal.jobagent.notifications.NotificationEvents;
import com.personal.jobagent.notifications.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * POST /auth/login, POST /auth/logout, GET /auth/me — per docs/contracts/api.md.
 * Login is intentionally NOT using Spring Security's formLogin filter chain
 * (which redirects and expects form-encoded bodies) — this is a JSON API
 * for a SPA, so authentication is performed manually against the
 * AuthenticationManager and the resulting context is saved into the
 * session explicitly.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final AuditLogWriter auditLogWriter;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    private final PurgeService purgeService;
    private final NotificationService notificationService;
    private final FirebaseTokenVerifier firebaseTokenVerifier;
    private final FirebaseUserService firebaseUserService;
    private final RegistrationGate registrationGate;

    public AuthController(AuthenticationManager authenticationManager,
                           SecurityContextRepository securityContextRepository,
                           AuditLogWriter auditLogWriter,
                           org.springframework.security.crypto.password.PasswordEncoder passwordEncoder,
                           PurgeService purgeService,
                           NotificationService notificationService,
                           FirebaseTokenVerifier firebaseTokenVerifier,
                           FirebaseUserService firebaseUserService,
                           RegistrationGate registrationGate) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.auditLogWriter = auditLogWriter;
        this.passwordEncoder = passwordEncoder;
        this.purgeService = purgeService;
        this.notificationService = notificationService;
        this.firebaseTokenVerifier = firebaseTokenVerifier;
        this.firebaseUserService = firebaseUserService;
        this.registrationGate = registrationGate;
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }

    public record UserResponse(UUID userId, String email, String displayName) {
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request,
                                    HttpServletRequest httpRequest,
                                    HttpServletResponse httpResponse) {
        UUID correlationId = correlationId();
        String ip = httpRequest.getRemoteAddr();

        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.email(), request.password()));

            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, httpRequest, httpResponse);

            AppUserDetails principal = (AppUserDetails) authentication.getPrincipal();

            // Feature 8: is this the FIRST successful session for the
            // account? Must be computed BEFORE this login's own audit row is
            // written (two statements down) — otherwise the lookup would
            // always find this very login and every session would look
            // non-first.
            boolean firstSessionForAccount;
            try {
                firstSessionForAccount = !auditLogWriter.existsActionForActor("LOGIN_SUCCESS", request.email());
            } catch (Exception lookupEx) {
                firstSessionForAccount = false; // fail closed to the per-day key
            }

            auditLogWriter.write(AuditEntry.simple(request.email(), "LOGIN_SUCCESS", ip, correlationId));

            // Feature 8: signup/verification-cycle notifications. Phase 1's
            // session flow has no separate signup vs login screens, so the
            // FIRST successful session for an account is SIGNUP_COMPLETED
            // (email-verification analogue: password proven), subsequent
            // ones are VERIFICATION_COMPLETED. Per-account dedup: exactly
            // one signup notification ever, one per calendar day per
            // account for verification.
            try {
                String verificationEvent = firstSessionForAccount
                        ? NotificationEvents.SIGNUP_COMPLETED
                        : NotificationEvents.VERIFICATION_COMPLETED;
                String dedup = firstSessionForAccount
                        ? "signup-completed:" + request.email().toLowerCase()
                        : "verification-completed:" + request.email().toLowerCase()
                                + ":" + java.time.LocalDate.now();
                notificationService.emit(new NotificationService.NotificationCommand(
                        verificationEvent,
                        "USER",
                        principal.getUserId(),
                        Map.of(
                                "message", firstSessionForAccount
                                        ? "Signup completed — welcome"
                                        : "Session verified",
                                "detail", firstSessionForAccount
                                        ? "Account created and identity verified for " + request.email()
                                        : "Identity re-verified for " + request.email(),
                                "dedup_key", dedup
                        ),
                        correlationId,
                        null));
            } catch (Exception notifyEx) {
                // notification must never fail the login itself
            }

            return ResponseEntity.ok(new UserResponse(
                    principal.getUserId(), principal.getUsername(), principal.getDisplayName()));

        } catch (BadCredentialsException | org.springframework.security.core.userdetails.UsernameNotFoundException e) {
            // Deliberately identical response/logging shape for "no such
            // user" vs "wrong password" — no user-enumeration signal, per
            // the API contract.
            auditLogWriter.write(AuditEntry.simple(request.email(), "LOGIN_FAILURE", ip, correlationId));
            ApiError error = ApiError.of(401, "Invalid credentials",
                    "The email or password is incorrect.", httpRequest.getRequestURI(), correlationId.toString());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
        }
    }

    /**
     * @param idToken    the Firebase ID token; the only source of identity
     * @param inviteCode the registration invite code, required only when a new
     *                   local account would be created
     * @param signup     true when the client just called Firebase's sign-up, as
     *                   opposed to signing an existing account in. It is not an
     *                   authorization signal — nothing is granted on the strength
     *                   of it — it only lets a refused registration clean up the
     *                   Firebase account that this very sign-up created. See
     *                   {@link FirebaseTokenVerifier#deleteJustCreatedAccount}.
     */
    public record FirebaseSessionRequest(@NotBlank String idToken, String inviteCode, Boolean signup) {
    }

    /**
     * Public, non-sensitive registration policy for the SPA's sign-up form:
     * whether an invite code is demanded, and whether registration is currently
     * possible at all. Exposes no credential material and no account data —
     * just enough for the form to label its fields honestly instead of showing
     * an invite-code box that the server will ignore, or a sign-up button that
     * cannot succeed.
     */
    @GetMapping("/registration-policy")
    public ResponseEntity<Map<String, Object>> registrationPolicy() {
        return ResponseEntity.ok(Map.of(
                "inviteCodeRequired", registrationGate.isInviteCodeRequired(),
                "registrationAvailable", registrationGate.registrationPossible()));
    }

    /**
     * Exchanges a Firebase ID token for an application session.
     *
     * <p>This is the ONLY place a local account can be created from a Firebase
     * identity, and it is where the registration invite code is enforced. The
     * identity used is derived exclusively from the verified token — the request
     * body carries no user id, and adding one would be ignored.
     *
     * <p>Status codes are deliberate and distinguishable, because the sign-up
     * UI has to tell the user something useful:
     * <ul>
     *   <li>401 — the ID token itself is not acceptable</li>
     *   <li>400 — token is valid but carries no email to link an account to</li>
     *   <li>403 — either the email is not verified yet (Firebase's
     *       verification email has not been followed, so no account may be
     *       claimed or created) or registration was refused by the invite
     *       gate; the body distinguishes the two</li>
     *   <li>503 — the server has no usable Firebase credentials</li>
     * </ul>
     */
    @PostMapping("/firebase/session")
    public ResponseEntity<?> firebaseSession(@Valid @RequestBody FirebaseSessionRequest request,
                                             HttpServletRequest httpRequest,
                                             HttpServletResponse httpResponse) {
        UUID correlationId = correlationId();
        String ip = httpRequest.getRemoteAddr();

        FirebaseTokenVerifier.VerifiedIdentity identity;
        try {
            identity = firebaseTokenVerifier.verifyIdToken(request.idToken());
        } catch (FirebaseTokenVerifier.InvalidToken e) {
            // Deliberately the same shape as a failed password login: a caller
            // learns only that the credential was not accepted.
            auditLogWriter.write(AuditEntry.simple("UNKNOWN", "FIREBASE_LOGIN_FAILURE", ip, correlationId));
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiError.of(401, "Invalid credentials",
                    "The authentication token is invalid or has expired.",
                    httpRequest.getRequestURI(), correlationId.toString()));
        } catch (FirebaseTokenVerifier.Unavailable e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ApiError.of(503,
                    "Authentication unavailable",
                    "Firebase Authentication is not configured on this server: " + e.getMessage(),
                    httpRequest.getRequestURI(), correlationId.toString()));
        }

        FirebaseUserService.ProvisionedUser provisioned;
        try {
            provisioned = firebaseUserService.signIn(identity, request.inviteCode());
        } catch (FirebaseUserService.RegistrationRefused e) {
            // A refused sign-up would otherwise leave an orphan Firebase account:
            // the credential exists, but nothing in this application refers to it,
            // and its email is then taken for any future attempt. Delete it, but
            // ONLY when the client says it just signed up AND Firebase reports the
            // account as seconds old — a pre-existing account that merely reached a
            // refusal (no local counterpart while registration is closed) must be
            // left intact. Never throws, never changes the 403.
            boolean removed = false;
            if (Boolean.TRUE.equals(request.signup())) {
                removed = firebaseTokenVerifier.deleteJustCreatedAccount(
                        identity.uid(), java.time.Duration.ofMinutes(5));
            }
            auditLogWriter.write(AuditEntry.simple(
                    identity.email() == null ? "UNKNOWN" : identity.email(),
                    removed ? "SIGNUP_REFUSED_ORPHAN_REMOVED" : "SIGNUP_REFUSED", ip, correlationId));
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiError.of(403,
                    "Registration not allowed", e.getMessage(),
                    httpRequest.getRequestURI(), correlationId.toString()));
        } catch (FirebaseUserService.IdentityIncomplete e) {
            return ResponseEntity.badRequest().body(ApiError.of(400, "Account cannot be linked",
                    e.getMessage(), httpRequest.getRequestURI(), correlationId.toString()));
        } catch (FirebaseUserService.UnverifiedEmail e) {
            // An unverified Firebase credential named an existing account's
            // address. Refuse without confirming whether such an account
            // exists (the message is deliberately account-agnostic), and
            // leave the Firebase account alone: deleting it would break the
            // legitimate verify-then-sign-in recovery, and it can no longer
            // reach any local account while its email stays unverified.
            auditLogWriter.write(AuditEntry.simple(
                    identity.email() == null ? "UNKNOWN" : identity.email(),
                    "FIREBASE_LINK_REFUSED_UNVERIFIED", ip, correlationId));
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiError.of(403,
                    "Email not verified", e.getMessage(),
                    httpRequest.getRequestURI(), correlationId.toString()));
        }

        UserRecord user = provisioned.user();
        AppUserDetails principal = new AppUserDetails(user);

        // Same session mechanism as POST /auth/login: the authenticated context
        // is saved into the HTTP session so the CSRF pair and every existing
        // endpoint behave identically no matter how the user signed in.
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

        auditLogWriter.write(AuditEntry.simple(
                user.email(), provisioned.created() ? "SIGNUP_COMPLETED" : "LOGIN_SUCCESS", ip, correlationId));

        try {
            notificationService.emit(new NotificationService.NotificationCommand(
                    provisioned.created()
                            ? NotificationEvents.SIGNUP_COMPLETED
                            : NotificationEvents.VERIFICATION_COMPLETED,
                    "USER",
                    user.id(),
                    Map.of(
                            "message", provisioned.created()
                                    ? "Signup completed — welcome"
                                    : "Session verified",
                            "detail", provisioned.created()
                                    ? "Account created and identity verified for " + user.email()
                                    : "Identity re-verified for " + user.email(),
                            "dedup_key", provisioned.created()
                                    ? "signup-completed:" + user.email().toLowerCase()
                                    : "verification-completed:" + user.email().toLowerCase()
                                            + ":" + java.time.LocalDate.now()
                    ),
                    correlationId,
                    null));
        } catch (Exception notifyEx) {
            // notification must never fail the sign-in itself
        }

        return ResponseEntity.ok(new UserResponse(user.id(), user.email(), user.displayName()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String actor = authentication != null ? authentication.getName() : "UNKNOWN";
        UUID correlationId = correlationId();

        httpRequest.getSession().invalidate();
        SecurityContextHolder.clearContext();

        auditLogWriter.write(AuditEntry.simple(actor, "LOGOUT", httpRequest.getRemoteAddr(), correlationId));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public ResponseEntity<UserResponse> me() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        AppUserDetails principal = (AppUserDetails) authentication.getPrincipal();
        return ResponseEntity.ok(new UserResponse(
                principal.getUserId(), principal.getUsername(), principal.getDisplayName()));
    }

    public record PurgeRequest(@NotBlank String confirmationPassword) {
    }

    /**
     * Synchronous 204 per the confirmed contract decision (Phase 1's
     * single-user, low-data-volume scope doesn't justify async/202
     * background-job infrastructure yet — revisit if that changes).
     */
    @PostMapping("/purge-my-data")
    public ResponseEntity<?> purgeMyData(@Valid @RequestBody PurgeRequest request, HttpServletRequest httpRequest) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        AppUserDetails principal = (AppUserDetails) authentication.getPrincipal();
        UUID correlationId = correlationId();
        String ip = httpRequest.getRemoteAddr();

        // Firebase-backed accounts have no locally-stored password (V020 makes
        // password_hash nullable), so there is nothing to compare against.
        // Refuse explicitly rather than letting a null hash reach the encoder.
        if (principal.getPassword() == null || principal.getPassword().isBlank()) {
            ApiError error = ApiError.of(403, "Confirmation failed",
                    "This account has no local password to confirm with.",
                    httpRequest.getRequestURI(), correlationId.toString());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
        }

        if (!passwordEncoder.matches(request.confirmationPassword(), principal.getPassword())) {
            ApiError error = ApiError.of(403, "Confirmation failed",
                    "The confirmation password did not match.", httpRequest.getRequestURI(), correlationId.toString());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
        }

        purgeService.purgeProfileData(principal.getUserId(), principal.getUsername(), correlationId, ip);
        return ResponseEntity.noContent().build();
    }

    private UUID correlationId() {
        // MDC already reflects X-Correlation-ID if the client supplied one
        // (CorrelationIdFilter doesn't validate it's a real UUID before
        // storing it — see that class). If it's not parseable, fall back to
        // a freshly generated one rather than letting the exception
        // propagate as an uncaught 500.
        Object mdcValue = org.slf4j.MDC.get(CorrelationIdFilter.MDC_KEY);
        if (mdcValue != null) {
            try {
                return UUID.fromString(mdcValue.toString());
            } catch (IllegalArgumentException ignored) {
                // fall through
            }
        }
        return com.personal.jobagent.common.UuidV7.generate();
    }
}
