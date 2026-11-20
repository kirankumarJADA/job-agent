package com.personal.jobagent.discovery.plan;

import com.personal.jobagent.audit.AuditEntry;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.common.ApiError;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.security.AppUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/search-plans")
public class SearchPlansController {

    private final SearchPlanService service;
    private final JdbcTemplate jdbcTemplate;
    private final AuditLogWriter auditLogWriter;

    public SearchPlansController(SearchPlanService service,
                                 JdbcTemplate jdbcTemplate,
                                 AuditLogWriter auditLogWriter) {
        this.service = service;
        this.jdbcTemplate = jdbcTemplate;
        this.auditLogWriter = auditLogWriter;
    }

    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest request) {
        UUID profileId = currentProfileId();
        if (profileId == null) {
            return unauthorized(request, "Authentication required");
        }
        List<SearchPlanRecord> plans = service.listPlans(profileId);
        return ResponseEntity.ok(plans);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable UUID id, HttpServletRequest request) {
        UUID profileId = currentProfileId();
        if (profileId == null) {
            return unauthorized(request, "Authentication required");
        }
        return service.getPlan(id, profileId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> notFound(request, "Search plan not found: " + id));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody SearchPlanRecord body, HttpServletRequest request) {
        UUID profileId = currentProfileId();
        if (profileId == null) {
            return unauthorized(request, "Authentication required");
        }
        try {
            SearchPlanRecord created = service.createPlan(profileId, body);
            audit("SEARCH_PLAN_CREATED", created.id(), Map.of(), Map.of("name", created.name()), request);
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (IllegalArgumentException e) {
            return badRequest(request, e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable UUID id, @RequestBody SearchPlanRecord body, HttpServletRequest request) {
        UUID profileId = currentProfileId();
        if (profileId == null) {
            return unauthorized(request, "Authentication required");
        }
        try {
            return service.updatePlan(id, profileId, body)
                    .<ResponseEntity<?>>map(updated -> {
                        audit("SEARCH_PLAN_UPDATED", id, Map.of(), Map.of("name", updated.name()), request);
                        return ResponseEntity.ok(updated);
                    })
                    .orElseGet(() -> notFound(request, "Search plan not found: " + id));
        } catch (IllegalArgumentException e) {
            return badRequest(request, e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable UUID id, HttpServletRequest request) {
        UUID profileId = currentProfileId();
        if (profileId == null) {
            return unauthorized(request, "Authentication required");
        }
        boolean deleted = service.deletePlan(id, profileId);
        if (deleted) {
            audit("SEARCH_PLAN_DELETED", id, Map.of(), Map.of(), request);
            return ResponseEntity.noContent().build();
        }
        return notFound(request, "Search plan not found: " + id);
    }

    @GetMapping("/{id}/expand")
    public ResponseEntity<?> expand(@PathVariable UUID id, HttpServletRequest request) {
        UUID profileId = currentProfileId();
        if (profileId == null) {
            return unauthorized(request, "Authentication required");
        }
        return service.expandPlan(id, profileId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> notFound(request, "Search plan not found: " + id));
    }

    private void audit(String action, UUID targetId, Map<String, Object> before, Map<String, Object> after, HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String actor = authentication != null ? authentication.getName() : "UNKNOWN";
        auditLogWriter.write(new AuditEntry(
                actor, action, "SEARCH_PLAN", targetId,
                before, after,
                request.getRemoteAddr(),
                UuidV7.generate()
        ));
    }

    private ResponseEntity<?> unauthorized(HttpServletRequest request, String detail) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                ApiError.of(401, "Unauthorized", detail, request.getRequestURI(), null));
    }

    private ResponseEntity<?> notFound(HttpServletRequest request, String detail) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiError.of(404, "Not Found", detail, request.getRequestURI(), null));
    }

    private ResponseEntity<?> badRequest(HttpServletRequest request, String detail) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                ApiError.of(400, "Bad Request", detail, request.getRequestURI(), null));
    }

    private UUID currentProfileId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AppUserDetails principal)) {
            return null;
        }
        return jdbcTemplate.query(
                        "select id from profiles where user_id = ?",
                        (rs, rowNum) -> (UUID) rs.getObject("id"), principal.getUserId())
                .stream().findFirst().orElse(null);
    }
}
