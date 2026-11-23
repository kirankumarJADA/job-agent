package com.personal.jobagent.discovery.plan;

import com.personal.jobagent.audit.AuditEntry;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.common.ApiError;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.security.AppUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/v1/search-plans")
public class SearchPlansController implements DisposableBean {

    private final SearchPlanService service;
    private final SearchPlanExecutionCoordinator coordinator;
    private final JdbcTemplate jdbcTemplate;
    private final AuditLogWriter auditLogWriter;

    private final ThreadPoolExecutor manualRunExecutor = new ThreadPoolExecutor(
            2, 4, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(50),
            r -> {
                Thread t = new Thread(r, "search-plan-manual-run");
                t.setDaemon(true);
                return t;
            }
    );

    public SearchPlansController(SearchPlanService service,
                                 SearchPlanExecutionCoordinator coordinator,
                                 JdbcTemplate jdbcTemplate,
                                 AuditLogWriter auditLogWriter) {
        this.service = service;
        this.coordinator = coordinator;
        this.jdbcTemplate = jdbcTemplate;
        this.auditLogWriter = auditLogWriter;
    }

    @Override
    public void destroy() {
        manualRunExecutor.shutdownNow();
    }

    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest request) {
        UUID profileId = currentProfileId();
        if (profileId == null) {
            return unauthorized(request, "Authentication required");
        }
        List<SearchPlanResponse> plans = service.listPlans(profileId).stream()
                .map(SearchPlanResponse::fromRecord)
                .toList();
        return ResponseEntity.ok(plans);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable UUID id, HttpServletRequest request) {
        UUID profileId = currentProfileId();
        if (profileId == null) {
            return unauthorized(request, "Authentication required");
        }
        return service.getPlan(id, profileId)
                .map(SearchPlanResponse::fromRecord)
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
            return ResponseEntity.status(HttpStatus.CREATED).body(SearchPlanResponse.fromRecord(created));
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
                    .map(SearchPlanResponse::fromRecord)
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

    @PostMapping("/{id}/run")
    public ResponseEntity<?> runPlan(@PathVariable UUID id,
                                    @RequestParam(name = "sync", defaultValue = "false") boolean sync,
                                    HttpServletRequest request) {
        UUID profileId = currentProfileId();
        if (profileId == null) {
            return unauthorized(request, "Authentication required");
        }
        Optional<SearchPlanRecord> optPlan = service.getPlan(id, profileId);
        if (optPlan.isEmpty()) {
            return notFound(request, "Search plan not found: " + id);
        }
        SearchPlanRecord plan = optPlan.get();

        if (plan.executionOwnerToken() != null && plan.executionLeaseExpiresAt() != null
                && plan.executionLeaseExpiresAt().isAfter(java.time.Instant.now())
                && "RUNNING".equalsIgnoreCase(plan.lastExecutionStatus())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                    ApiError.of(409, "Conflict", "Search plan is already executing with an active lease", request.getRequestURI(), null));
        }

        if (sync) {
            SearchPlanExecutionCoordinator.ExecutionSummary summary = coordinator.executePlan(id, profileId, true);
            audit("SEARCH_PLAN_EXECUTED", id, Map.of(), Map.of("status", summary.status(), "jobsIngested", summary.jobsIngested(), "sync", true), request);
            return ResponseEntity.ok(summary);
        }

        try {
            manualRunExecutor.submit(() -> {
                try {
                    SearchPlanExecutionCoordinator.ExecutionSummary summary = coordinator.executePlan(id, profileId, true);
                    audit("SEARCH_PLAN_EXECUTED", id, Map.of(), Map.of("status", summary.status(), "jobsIngested", summary.jobsIngested(), "async", true), request);
                } catch (Exception e) {
                    // logged in coordinator
                }
            });
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(
                    SearchPlanExecutionCoordinator.ExecutionSummary.skipped(id, plan.name(), "Execution queued asynchronously"));
        } catch (RejectedExecutionException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(
                    ApiError.of(429, "Too Many Requests", "Manual run queue is full; please try again later", request.getRequestURI(), null));
        }
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
