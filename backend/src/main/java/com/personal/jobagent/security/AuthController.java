package com.personal.jobagent.security;

import com.personal.jobagent.audit.AuditEntry;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.common.ApiError;
import com.personal.jobagent.common.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
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

    public AuthController(AuthenticationManager authenticationManager,
                           SecurityContextRepository securityContextRepository,
                           AuditLogWriter auditLogWriter,
                           org.springframework.security.crypto.password.PasswordEncoder passwordEncoder,
                           PurgeService purgeService) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.auditLogWriter = auditLogWriter;
        this.passwordEncoder = passwordEncoder;
        this.purgeService = purgeService;
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

            auditLogWriter.write(AuditEntry.simple(request.email(), "LOGIN_SUCCESS", ip, correlationId));

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
