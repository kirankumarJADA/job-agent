package com.personal.jobagent.automation;

import com.personal.jobagent.audit.AuditEntry;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.discovery.platform.AddressGuard;
import com.personal.jobagent.discovery.platform.SafeHttpClient;
import com.personal.jobagent.discovery.platform.SafeHttpClient.FetchException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Stage 3I Phase 8: SSRF-safe apply-time posting liveness verification.
 *
 * <p>Prevents wasting applications or submitting against withdrawn/closed jobs when
 * claiming a plan for browser execution:
 * <ul>
 *   <li>Always checks persisted {@code jobs.deleted_at} and {@code jobs.status='ARCHIVED'}.</li>
 *   <li>When {@code app.automation.liveness-check-enabled=true}, probes the employer posting
 *       URL through {@link SafeHttpClient} + {@link AddressGuard} pinning (3 redirect cap).</li>
 *   <li>HTTP 404/410 or explicit job-closed page copy (without a login gate) returns {@link Status#CLOSED}.</li>
 *   <li>SSRF refusal returns {@link Status#UNSAFE_TARGET}.</li>
 *   <li>401/403/login walls, 429 rate-limits, 5xx upstream errors, timeouts, and 3xx redirect caps
 *       never misclassify an application as closed: they return {@link Status#INCONCLUSIVE} and
 *       preserve audit evidence.</li>
 * </ul>
 */
@Service
public class PostingLivenessService {

    private static final Logger log = LoggerFactory.getLogger(PostingLivenessService.class);

    private static final Pattern EXPLICIT_CLOSED_PATTERN = Pattern.compile(
            "this job (?:has been closed|is no longer (?:available|open|accepting applications))|"
                    + "(?:position|role|vacancy|requisition) has been (?:closed|filled)|"
                    + "no longer accepting applications|"
                    + "posting has (?:closed|expired)|"
                    + "the job you are looking for is no longer available",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern LOGIN_WALL_PATTERN = Pattern.compile(
            "<input[^>]+type=[\"']?password|<form[^>]+(?:login|sign[-_]?in)|please sign in to continue",
            Pattern.CASE_INSENSITIVE);

    public enum Status {
        LIVE,
        CLOSED,
        INCONCLUSIVE,
        UNSAFE_TARGET,
        SKIPPED_DISABLED
    }

    public record LivenessResult(Status status, String code, String detail, Integer httpStatus) {
        public boolean blocksExecution() {
            return status == Status.CLOSED || status == Status.UNSAFE_TARGET;
        }
    }

    private final JdbcTemplate db;
    private final AuditLogWriter audit;
    private final boolean livenessCheckEnabled;
    private final SafeHttpClient httpClient;

    @Autowired
    public PostingLivenessService(
            JdbcTemplate db,
            AuditLogWriter audit,
            @Value("${app.automation.liveness-check-enabled:false}") boolean livenessCheckEnabled,
            @Value("${app.sandbox.allow-local-test-hosts:false}") boolean allowLocalTestHosts) {
        this(
                db,
                audit,
                livenessCheckEnabled,
                SafeHttpClient.forGuard(
                        allowLocalTestHosts ? AddressGuard.allowingLoopbackForTesting() : new AddressGuard(),
                        new SafeHttpClient.Settings(16, 0, Duration.ofSeconds(4), 512 * 1024, 1, 0, 0)));
    }

    public PostingLivenessService(
            JdbcTemplate db,
            AuditLogWriter audit,
            boolean livenessCheckEnabled,
            SafeHttpClient httpClient) {
        this.db = db;
        this.audit = audit;
        this.livenessCheckEnabled = livenessCheckEnabled;
        this.httpClient = httpClient;
    }

    public LivenessResult evaluateForPlan(UUID planId) {
        if (planId == null || db == null) {
            return new LivenessResult(Status.SKIPPED_DISABLED, "LIVENESS_SKIPPED", "no plan supplied", null);
        }
        List<Map<String, Object>> rows = db.queryForList("""
                select j.id as job_id, j.status as job_status, j.deleted_at,
                       coalesce(nullif(p.target_url, ''), j.application_url, j.canonical_url) as check_url
                from automation_plans p
                join applications a on a.id = p.application_id
                join jobs j on j.id = a.job_id
                where p.id = ?
                """, planId);
        if (rows.isEmpty()) {
            return new LivenessResult(Status.INCONCLUSIVE, "LIVENESS_NO_JOB",
                    "no linked job found for plan", null);
        }
        Map<String, Object> row = rows.get(0);
        if (row.get("deleted_at") != null || "ARCHIVED".equalsIgnoreCase(asString(row.get("job_status")))) {
            LivenessResult closed = new LivenessResult(
                    Status.CLOSED, "JOB_POSTING_CLOSED", "job posting is archived or deleted", null);
            writeAudit(planId, "AUTOMATION_LIVENESS_CLOSED", closed);
            return closed;
        }
        if (!livenessCheckEnabled) {
            return new LivenessResult(Status.SKIPPED_DISABLED, "LIVENESS_DISABLED",
                    "apply-time HTTP liveness probe is disabled in this environment", null);
        }
        LivenessResult probe = probeUrl(asString(row.get("check_url")));
        if (probe.status() == Status.CLOSED || probe.status() == Status.UNSAFE_TARGET) {
            writeAudit(planId, "AUTOMATION_LIVENESS_FAILED", probe);
        } else if (probe.status() == Status.INCONCLUSIVE) {
            writeAudit(planId, "AUTOMATION_LIVENESS_INCONCLUSIVE", probe);
        }
        return probe;
    }

    public LivenessResult probeUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return new LivenessResult(Status.INCONCLUSIVE, "LIVENESS_MISSING_URL",
                    "no application URL available to verify posting liveness", null);
        }
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException e) {
            return new LivenessResult(Status.UNSAFE_TARGET, "UNSAFE_TARGET",
                    "malformed employer application URL", null);
        }
        try {
            String body = httpClient.get(uri, SafeHttpClient.Accept.HTML);
            if (body != null && !body.isBlank()) {
                if (LOGIN_WALL_PATTERN.matcher(body).find()) {
                    return new LivenessResult(Status.INCONCLUSIVE, "LIVENESS_LOGIN_WALL",
                            "employer returned an authentication prompt; posting status inconclusive", 200);
                }
                if (EXPLICIT_CLOSED_PATTERN.matcher(body).find()) {
                    return new LivenessResult(Status.CLOSED, "JOB_POSTING_CLOSED",
                            "employer page explicitly states the job posting is closed", 200);
                }
            }
            return new LivenessResult(Status.LIVE, "JOB_POSTING_LIVE", "employer posting reachable", 200);
        } catch (FetchException e) {
            return switch (e.category()) {
                case NOT_FOUND -> new LivenessResult(Status.CLOSED, "JOB_POSTING_CLOSED",
                        "employer URL returned HTTP " + e.status() + " (" + e.category() + ")", e.status());
                case UNSAFE_ADDRESS, MALFORMED_RESPONSE -> new LivenessResult(Status.UNSAFE_TARGET, "UNSAFE_TARGET",
                        "blocked unsafe employer URL during liveness verification", null);
                default -> new LivenessResult(
                        Status.INCONCLUSIVE,
                        "LIVENESS_" + e.category().name(),
                        "posting liveness check inconclusive (" + e.category()
                                + (e.status() > 0 ? " HTTP " + e.status() : "") + "); proceeding cautiously",
                        e.status() > 0 ? e.status() : null);
            };
        } catch (RuntimeException e) {
            log.warn("Unexpected error during posting liveness check: {}", e.toString());
            return new LivenessResult(Status.INCONCLUSIVE, "LIVENESS_ERROR",
                    "unexpected error during posting liveness check", null);
        }
    }

    private void writeAudit(UUID planId, String action, LivenessResult result) {
        if (audit == null) return;
        Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("status", result.status().name());
        details.put("code", result.code());
        details.put("detail", result.detail());
        if (result.httpStatus() != null) {
            details.put("httpStatus", result.httpStatus());
        }
        audit.write(new AuditEntry("SYSTEM", action, "AUTOMATION_PLAN", planId, null, details, null, UuidV7.generate()));
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString().toLowerCase(Locale.ROOT).equals("null") ? null : value.toString();
    }
}
