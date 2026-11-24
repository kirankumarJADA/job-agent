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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SearchPlansController.class);

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

        String requestUri = request.getRequestURI();
        String remoteAddr = request.getRemoteAddr();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String actor = authentication != null ? authentication.getName() : "UNKNOWN";
        UUID executionId = UuidV7.generate();

        // Synchronous atomic execution reservation via database-backed lease
        UUID ownerToken = UUID.randomUUID();
        boolean reserved = service.reserveExecutionLease(id, ownerToken);
        if (!reserved) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                    ApiError.of(409, "Conflict", "Search plan is already executing with an active lease", requestUri, null));
        }

        if (sync) {
            try {
                SearchPlanExecutionCoordinator.ExecutionSummary summary =
                        coordinator.executePlanWithReservedLease(id, profileId, ownerToken, true);
                auditLogWriter.write(new AuditEntry(
                        actor, "SEARCH_PLAN_EXECUTED", "SEARCH_PLAN", id,
                        Map.of(),
                        Map.of("status", summary.status(), "jobsIngested", summary.jobsIngested(), "sync", true,
                                "requestUri", requestUri, "correlationId", executionId.toString()),
                        remoteAddr,
                        executionId
                ));
                return ResponseEntity.ok(summary);
            } catch (Exception e) {
                service.releaseExecutionLease(id, ownerToken);
                throw e;
            }
        }

        java.util.concurrent.CountDownLatch admissionGate = new java.util.concurrent.CountDownLatch(1);
        try {
            manualRunExecutor.submit(() -> {
                try {
                    // Task-start gate: wait for caller thread to commit SEARCH_PLAN_RUN_QUEUED audit entry
                    boolean admitted = admissionGate.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    if (!admitted) {
                        log.warn("Search plan {} async execution aborted: admission gate timed out", id);
                        service.releaseExecutionLease(id, ownerToken);
                        return;
                    }
                    SearchPlanExecutionCoordinator.ExecutionSummary summary =
                            coordinator.executePlanWithReservedLease(id, profileId, ownerToken, true);
                    auditLogWriter.write(new AuditEntry(
                            actor, "SEARCH_PLAN_EXECUTED", "SEARCH_PLAN", id,
                            Map.of(),
                            Map.of("status", summary.status(), "jobsIngested", summary.jobsIngested(), "async", true,
                                    "requestUri", requestUri, "correlationId", executionId.toString(), "executionId", executionId.toString()),
                            remoteAddr,
                            executionId
                    ));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    service.releaseExecutionLease(id, ownerToken);
                } catch (Exception e) {
                    log.error("Async execution failed for search plan {}: {}", id, e.getMessage(), e);
                    auditLogWriter.write(new AuditEntry(
                            actor, "SEARCH_PLAN_EXECUTION_FAILED", "SEARCH_PLAN", id,
                            Map.of(),
                            Map.of("error", e.getMessage() != null ? e.getMessage() : "Execution exception", "async", true,
                                    "requestUri", requestUri, "correlationId", executionId.toString(), "executionId", executionId.toString()),
                            remoteAddr,
                            executionId
                    ));
                }
            });
        } catch (RejectedExecutionException e) {
            service.releaseExecutionLease(id, ownerToken);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(
                    ApiError.of(429, "Too Many Requests", "Manual run queue is full; please try again later", requestUri, null));
        }

        // Successfully admitted to executor: write QUEUED audit entry before opening gate
        auditLogWriter.write(new AuditEntry(
                actor, "SEARCH_PLAN_RUN_QUEUED", "SEARCH_PLAN", id,
                Map.of(),
                Map.of("correlationId", executionId.toString(), "executionId", executionId.toString(), "requestUri", requestUri, "async", true),
                remoteAddr,
                executionId
        ));

        // Open task gate for worker thread
        admissionGate.countDown();

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
                SearchPlanExecutionCoordinator.ExecutionSummary.queued(
                        id, plan.name(), executionId, "Execution queued asynchronously"));
    }

    private void audit(String action, UUID targetId, Map<String, Object> before, Map<String, Object> after, HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String actor = authentication != null ? authentication.getName() : "UNKNOWN";
        Map<String, Object> enrichedAfter = new java.util.LinkedHashMap<>(after);
        if (request != null) {
            enrichedAfter.put("requestUri", request.getRequestURI());
        }
        auditLogWriter.write(new AuditEntry(
                actor, action, "SEARCH_PLAN", targetId,
                before, enrichedAfter,
                request != null ? request.getRemoteAddr() : null,
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
