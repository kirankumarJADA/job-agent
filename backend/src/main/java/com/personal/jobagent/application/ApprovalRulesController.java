package com.personal.jobagent.application;

import com.personal.jobagent.audit.AuditEntry;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.security.OwnerContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * /api/v1/approval-rules — the owner's configurable
 * auto-approval rule (Phase 7).
 *
 * <p>GET reports the rule's <b>availability</b>, which is the distinction
 * Phase 7.3 exists to make:
 *
 * <ul>
 *   <li>{@code CONFIGURED} — a valid saved rule was loaded;</li>
 *   <li>{@code ABSENT} — no custom rule exists;</li>
 *   <li>{@code UNREADABLE} — the rule could not be reliably retrieved or
 *       validated, so the decision engine is failing closed. This is returned
 *       as <b>503</b>, never as a successful response describing an
 *       unconfigured rule: a database or validation error reported as "no rule
 *       configured" would tell the owner their settings are simply absent when
 *       in fact they exist and are being ignored.</li>
 * </ul>
 *
 * <p>PUT upserts the rule. The rule can toggle automatic application creation
 * and set a minimum score — nothing more: it can never bypass a hard stop, a
 * required-field failure, an artifact-integrity failure, the daily quota, or
 * any other safety gate; those live downstream of this table and are
 * unaffected by it.
 *
 * <p>Both operations are owner-scoped from the authenticated identity. No id
 * in a request body is ever treated as an identifier. The {@code applicationMode}
 * field mirrors what {@link ApplicationDecisionService} will actually do, so a
 * client can describe the real default behaviour for a user without a rule.
 */
@RestController
@RequestMapping("/api/v1/approval-rules")
public class ApprovalRulesController {

    /**
     * Score applied in ASSISTED mode when no rule is configured. Sourced from
     * the decision engine so the API can never disagree with it.
     */
    private static final int ASSISTED_FLOOR = ApplicationDecisionService.HIGH_CONFIDENCE_THRESHOLD;

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

        ApplicationDecisionService.RuleState state = decisions.ruleState(profileId);
        String applicationMode = decisions.effectiveApplicationMode(profileId);

        if (state.availability() == ApplicationDecisionService.RuleAvailability.UNREADABLE) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("availability", state.availability().name());
            body.put("reason", state.unreadableCause() != null ? state.unreadableCause().name() : null);
            body.put("message", unreadableMessage(state.unreadableCause()));
            body.put("applicationMode", applicationMode);
            body.put("assistedFloor", ASSISTED_FLOOR);
            // Deliberately no autoApproveEnabled/minScore: there are no settings
            // we can honestly report, and echoing stale defaults would invite a
            // client to treat them as loaded state.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }

        return ResponseEntity.ok(readableBody(state, applicationMode));
    }

    /**
     * A readable rule. {@code configured} is retained for existing consumers;
     * it is exactly {@code availability == CONFIGURED}.
     */
    private static Map<String, Object> readableBody(ApplicationDecisionService.RuleState state,
                                                    String applicationMode) {
        boolean configured = state.availability() == ApplicationDecisionService.RuleAvailability.CONFIGURED;
        ApplicationDecisionService.UserApprovalRule rule = state.rule();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("availability", state.availability().name());
        body.put("configured", configured);
        body.put("autoApproveEnabled", configured && rule.autoApproveEnabled());
        body.put("minScore", configured ? rule.minScore() : ASSISTED_FLOOR);
        body.put("applicationMode", applicationMode);
        body.put("assistedFloor", ASSISTED_FLOOR);
        return body;
    }

    /** Bounded, user-safe explanation — never the raw database error. */
    private static String unreadableMessage(ApplicationDecisionService.RuleUnavailableCause cause) {
        if (cause == ApplicationDecisionService.RuleUnavailableCause.INVALID_THRESHOLD) {
            return "Your saved approval rule has an invalid minimum score, so automatic approval is "
                    + "paused for safety and matches wait for your review.";
        }
        return "Your approval rule could not be read, so automatic approval is paused for safety "
                + "and matches wait for your review.";
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
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("availability", ApplicationDecisionService.RuleAvailability.CONFIGURED.name());
            body.put("configured", true);
            body.put("autoApproveEnabled", saved.autoApproveEnabled());
            body.put("minScore", saved.minScore());
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
