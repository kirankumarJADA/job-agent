package com.personal.jobagent.application;

import com.personal.jobagent.audit.AuditEntry;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.security.OwnerContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * /api/v1/approval-rules — the owner's configurable
 * auto-approval rule (Phase 7).
 *
 * <p>GET returns the current rule (or nulls when the
 * Phase 5 defaults are in effect). PUT upserts it.
 * The rule can toggle automatic application creation
 * and set a minimum score — nothing more: it can
 * never bypass a hard stop, a required-field failure,
 * an artifact-integrity failure, the daily quota,
 * or any other safety gate; those live downstream
 * of this table and are unaffected by it.
 */
@RestController
@RequestMapping("/api/v1/approval-rules")
public class ApprovalRulesController {

    private final ApplicationDecisionService decisions;
    private final AuditLogWriter audit;
    private final OwnerContext ownerContext;

    public ApprovalRulesController(ApplicationDecisionService decisions,
                                   AuditLogWriter audit,
                                   OwnerContext ownerContext) {
        this.decisions = decisions;
        this.audit = audit;
        this.ownerContext = ownerContext;
    }

    public record RuleRequest(Boolean autoApproveEnabled, Integer minScore) {}

    @GetMapping
    public ResponseEntity<?> get() {
        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "a profile is required"));
        }
        ApplicationDecisionService.UserApprovalRule rule = decisions.ruleFor(profileId);
        return ResponseEntity.ok(Map.of(
                "autoApproveEnabled", rule != null && rule.autoApproveEnabled(),
                "minScore", rule != null ? rule.minScore() : ApplicationDecisionService.HIGH_CONFIDENCE_THRESHOLD,
                "configured", rule != null));
    }

    @PutMapping
    public ResponseEntity<?> put(@RequestBody RuleRequest request, HttpServletRequest httpRequest) {
        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "a profile is required"));
        }
        try {
            ApplicationDecisionService.UserApprovalRule saved =
                    decisions.saveRule(profileId, request.autoApproveEnabled(), request.minScore());
            audit.write(new AuditEntry(ownerContext.actorOr("user"), "APPROVAL_RULE_UPDATED", "PROFILE",
                    profileId, null,
                    Map.of("autoApproveEnabled", saved.autoApproveEnabled(), "minScore", saved.minScore()),
                    httpRequest.getRemoteAddr(), UuidV7.generate()));
            return ResponseEntity.ok(Map.of(
                    "autoApproveEnabled", saved.autoApproveEnabled(),
                    "minScore", saved.minScore()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
