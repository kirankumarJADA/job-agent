package com.personal.jobagent.discovery.platform;

import com.personal.jobagent.common.ApiError;
import com.personal.jobagent.security.AppUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Phase 9: the candidate-facing counterpart to the ops-only
 * {@code DiscoveryController} ({@code POST /discovery/run?sourceId=...}, which
 * requires knowing a specific source id — not something a candidate should
 * ever need to know, per the task's own "users should not need to understand
 * individual scrapers or manually pick a provider" requirement).
 *
 * <p>{@code POST /api/v1/discovery/run-for-me} runs discovery across every
 * source applicable to the signed-in candidate's own configured region(s),
 * concurrently, through {@link PreferredSourcesDiscoveryService} — and
 * returns only an aggregate summary. Consistent with {@code Job}'s own
 * documented contract ("the candidate feed never reveals which site, ATS or
 * aggregator surfaced a posting"), this endpoint never returns a source id,
 * kind or name; {@link PreferredSourcesDiscoveryService.RunSummary} is
 * already shaped that way by construction.
 */
@RestController
@RequestMapping("/api/v1/discovery")
public class PreferredDiscoveryController {

    private final PreferredSourcesDiscoveryService discovery;
    private final JdbcTemplate jdbcTemplate;

    public PreferredDiscoveryController(PreferredSourcesDiscoveryService discovery, JdbcTemplate jdbcTemplate) {
        this.discovery = discovery;
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostMapping("/run-for-me")
    public ResponseEntity<?> runForMe(HttpServletRequest request) {
        UUID profileId = currentProfileId();
        if (profileId == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of(404, "Not found",
                    "No profile exists yet for this account.", request.getRequestURI(), correlationId()));
        }
        return ResponseEntity.ok(discovery.runForProfile(profileId));
    }

    private UUID currentProfileId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        AppUserDetails principal = (AppUserDetails) authentication.getPrincipal();
        return jdbcTemplate.query("select id from profiles where user_id = ?",
                        (rs, rowNum) -> (UUID) rs.getObject("id"), principal.getUserId())
                .stream().findFirst().orElse(null);
    }

    private String correlationId() {
        return String.valueOf(org.slf4j.MDC.get("correlation_id"));
    }
}
