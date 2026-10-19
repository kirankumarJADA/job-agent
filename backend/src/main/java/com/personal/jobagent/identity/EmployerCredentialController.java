package com.personal.jobagent.identity;

import com.personal.jobagent.common.ApiError;
import com.personal.jobagent.mailbox.managed.MailAddresses;
import com.personal.jobagent.security.OwnerContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Candidate-owned employer-portal credentials (Phase 8.5 continuation).
 *
 * <p>Two audiences, exactly like {@link com.personal.jobagent.mailbox.MailboxVerificationController}:
 * the candidate manages their own saved credentials below (list/save/disable
 * — password accepted but never echoed back, even to the account that just
 * supplied it); the automation worker resolves exactly one credential at the
 * execution boundary to actually fill a login form, authenticated by the
 * worker bearer token and scoped to the application it is executing — never
 * to a profile id it supplies itself.
 */
@RestController
@RequestMapping("/api/v1/employer-credentials")
public class EmployerCredentialController {

    private final EmployerCredentialService credentials;
    private final OwnerContext ownerContext;

    public EmployerCredentialController(EmployerCredentialService credentials, OwnerContext ownerContext) {
        this.credentials = credentials;
        this.ownerContext = ownerContext;
    }

    public record SaveRequest(String employerDomain, String username, String password) {}

    public record ResolveRequest(UUID applicationId, String employerDomain) {}

    /** The candidate's own saved credentials. Usernames are shown; passwords never are. */
    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest request) {
        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null) return notFound(request, "No profile exists for this account.");
        List<Map<String, Object>> body = credentials.list(profileId).stream()
                .map(c -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("employerDomain", c.employerDomain());
                    row.put("username", c.username());
                    row.put("status", c.status());
                    row.put("updatedAt", c.updatedAt() == null ? null : c.updatedAt().toInstant().toString());
                    return row;
                }).toList();
        return ResponseEntity.ok(Map.of("credentials", body));
    }

    /** Saves or replaces the credential for one employer domain. Write-only: never returned. */
    @PostMapping
    public ResponseEntity<?> save(@RequestBody SaveRequest r, HttpServletRequest request) {
        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null) return notFound(request, "No profile exists for this account.");
        String domain = r == null ? null : MailAddresses.employerDomain(r.employerDomain()).orElse(null);
        if (domain == null) return badRequest(request, "employerDomain must be a valid domain name");
        if (r.username() == null || r.username().isBlank()) return badRequest(request, "username is required");
        if (r.password() == null || r.password().isBlank()) return badRequest(request, "password is required");
        credentials.save(profileId, domain, r.username().trim(), r.password());
        return ResponseEntity.ok(Map.of("employerDomain", domain, "saved", true));
    }

    /** Disables (does not delete) the credential for one employer domain. */
    @DeleteMapping("/{employerDomain}")
    public ResponseEntity<?> disable(@PathVariable String employerDomain, HttpServletRequest request) {
        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null) return notFound(request, "No profile exists for this account.");
        String domain = MailAddresses.employerDomain(employerDomain).orElse(null);
        if (domain == null) return badRequest(request, "employerDomain must be a valid domain name");
        credentials.disable(profileId, domain);
        return ResponseEntity.ok(Map.of("employerDomain", domain, "disabled", true));
    }

    /**
     * Worker call immediately before filling an employer portal's login form.
     * Owner is derived from the application the worker is executing, never
     * supplied by the request body — the same rule {@code MailboxVerificationController}
     * follows for the mailbox-verification boundary. 404 (missing/foreign
     * application, or no active credential for that domain) is the worker's
     * cue to pause for a human; this endpoint never fabricates or reuses a
     * credential for the wrong employer domain, and the decrypted password
     * leaves the database only in this one response, to the worker only.
     */
    @PostMapping("/resolve")
    public ResponseEntity<?> resolve(@RequestBody ResolveRequest r, HttpServletRequest request) {
        if (!ownerContext.isWorkerRequest()) return forbidden(request);
        if (r == null || r.applicationId() == null || r.employerDomain() == null || r.employerDomain().isBlank()) {
            return badRequest(request, "applicationId and employerDomain are required");
        }
        String domain = MailAddresses.employerDomain(r.employerDomain()).orElse(null);
        if (domain == null) return badRequest(request, "employerDomain must be a domain name");
        UUID ownerProfileId = ownerContext.ownerOfApplication(r.applicationId()).orElse(null);
        if (ownerProfileId == null) return notFound(request, "No such application");
        return credentials.find(ownerProfileId, domain)
                .<ResponseEntity<?>>map(stored -> ResponseEntity.ok(
                        Map.of("username", stored.username(), "password", stored.password())))
                .orElseGet(() -> notFound(request, "No active credential stored for this employer domain"));
    }

    private ResponseEntity<?> forbidden(HttpServletRequest request) {
        return error(403, "Forbidden", "Only the automation worker may resolve employer credentials.", request);
    }

    private ResponseEntity<?> notFound(HttpServletRequest request, String detail) {
        return error(404, "Not Found", detail, request);
    }

    private ResponseEntity<?> badRequest(HttpServletRequest request, String detail) {
        return error(400, "Bad Request", detail, request);
    }

    private ResponseEntity<?> error(int status, String title, String detail, HttpServletRequest request) {
        return ResponseEntity.status(status).body(ApiError.of(status, title, detail, request.getRequestURI(),
                correlationId()));
    }

    private String correlationId() {
        return String.valueOf(org.slf4j.MDC.get("correlation_id"));
    }
}
