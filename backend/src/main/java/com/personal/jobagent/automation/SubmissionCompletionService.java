package com.personal.jobagent.automation;

import com.personal.jobagent.audit.AuditEntry;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.track.ApplicationReceiptService;
import com.personal.jobagent.track.receipt.SubmissionEvidenceRepository;
import com.personal.jobagent.track.receipt.SubmissionEvidenceRepository.NewEvidence;
import com.personal.jobagent.track.receipt.SubmissionEvidenceRepository.StoredEvidence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The one path by which a real-submit attempt reaches {@code SUBMITTED}.
 *
 * <p>Called by {@code AutomationController#complete} only for the authenticated
 * worker identity. It requires exactly one structured {@code SUBMISSION_EVIDENCE}
 * entry describing a confirmed outcome. Then, in one database transaction, it:
 * <ol>
 *   <li>records the worker's evidence in {@code submission_evidence} (server-set
 *       worker principal and {@code recorded_via}; raw evidence encrypted with a
 *       server-computed digest);</li>
 *   <li>moves the plan {@code RUNNING → SUBMITTED} and writes the sanitised
 *       result in the same guarded UPDATE
 *       ({@link AutomationPlanRepository#completeRealSubmissionWithResult});</li>
 *   <li>writes the {@code AUTOMATION_PLAN_SUBMITTED} audit row.</li>
 * </ol>
 * If any step fails, none of them happened.
 *
 * <p>A {@code SUBMITTED} report without valid structured evidence never reaches
 * {@code SUBMITTED}: the attempt is moved to {@code RECONCILIATION_REQUIRED}
 * instead (a real click may have reached the employer, so it must not be
 * retried automatically), with the reason recorded.
 *
 * <p>After commit, receipt capture is attempted. Its failure never undoes the
 * submission record; the receipt simply stays unverified.
 */
@Service
public class SubmissionCompletionService {

    private static final Logger log = LoggerFactory.getLogger(SubmissionCompletionService.class);

    public static final String EVIDENCE_TYPE = "SUBMISSION_EVIDENCE";
    public static final String MISSING_EVIDENCE_CATEGORY = "SUBMISSION_EVIDENCE_MISSING";
    static final int MAX_SIGNALS = 20;
    static final int MAX_SHORT_TEXT = 256;
    static final int MAX_EVIDENCE_TEXT = 4000;

    /** Keys of a reported evidence entry the server keeps; everything else (e.g. self-asserted provenance) is dropped. */
    private static final Set<String> RETAINED_KEYS = Set.of(
            "type", "planType", "category", "confirmed", "evidence", "confirmationNumber", "signals", "summary");

    public enum Outcome { SUBMITTED, RECONCILIATION_REQUIRED, ILLEGAL_TRANSITION }

    public record Result(Outcome outcome, String reason, UUID evidenceId, String rawEvidenceStatus) {
        static Result illegal(String reason) {
            return new Result(Outcome.ILLEGAL_TRANSITION, reason, null, null);
        }
    }

    private final AutomationPlanRepository plans;
    private final SubmissionEvidenceRepository evidence;
    private final AuditLogWriter audit;
    private final TransactionTemplate tx;
    private final ApplicationReceiptService receipts;

    public SubmissionCompletionService(AutomationPlanRepository plans,
                                       SubmissionEvidenceRepository evidence,
                                       AuditLogWriter audit,
                                       PlatformTransactionManager transactionManager,
                                       ApplicationReceiptService receipts) {
        this.plans = plans;
        this.evidence = evidence;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactionManager);
        this.receipts = receipts;
    }

    /**
     * @param workerPrincipal the authenticated principal name, taken from the
     *                        security context by the caller — never from the body
     */
    public Result completeWorkerSubmission(UUID planId, String workerPrincipal, String detail,
                                           List<Map<String, Object>> reported) {
        AutomationPlanRepository.PlanRow plan = plans.findById(planId).orElse(null);
        UUID profileId = plans.ownerOfPlan(planId).orElse(null);
        if (plan == null || profileId == null || plan.applicationId() == null) {
            return Result.illegal("plan not found");
        }
        if (!"RUNNING".equals(plan.status()) || !plan.submitApproved()) {
            return Result.illegal("only a RUNNING, human-approved plan can record a real submission");
        }
        String planType = planTypeOf(plan);
        if (!isRealSubmitAttempt(plan)) {
            return Result.illegal("this plan's last step is not a real submission click");
        }

        Validation v = validate(planType, reported);
        if (!v.valid()) {
            List<Object> reconciliation = List.of(Map.of(
                    "type", "SUBMISSION_RECONCILIATION",
                    "planType", planType,
                    "category", MISSING_EVIDENCE_CATEGORY,
                    "evidence", truncate("SUBMITTED was reported without valid structured evidence (" + v.reason()
                            + "); worker detail: " + (detail == null ? "" : detail), MAX_EVIDENCE_TEXT)));
            Boolean moved = tx.execute(status -> {
                boolean ok = plans.transitionWithResult(planId, "RUNNING", "RECONCILIATION_REQUIRED", reconciliation);
                if (ok) {
                    audit.write(new AuditEntry(workerPrincipal, "AUTOMATION_PLAN_RECONCILIATION_REQUIRED",
                            "AUTOMATION_PLAN", planId, null,
                            Map.of("reason", MISSING_EVIDENCE_CATEGORY, "detail", v.reason()), null, UuidV7.generate()));
                }
                return ok;
            });
            return Boolean.TRUE.equals(moved)
                    ? new Result(Outcome.RECONCILIATION_REQUIRED, v.reason(), null, null)
                    : Result.illegal("plan state changed");
        }

        Map<String, Object> entry = v.entry();
        Result committed;
        try {
            committed = tx.execute(status -> {
                StoredEvidence stored = evidence.insert(new NewEvidence(
                        planId,
                        plan.applicationId(),
                        profileId,
                        workerPrincipal,
                        planType,
                        plan.targetUrl(),
                        String.valueOf(entry.get("category")),
                        stringOrNull(entry.get("confirmationNumber")),
                        signalsOf(entry),
                        String.valueOf(entry.get("evidence")),
                        stringOrNull(entry.get("rawEvidence"))));

                Map<String, Object> sanitized = new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : entry.entrySet()) {
                    if (RETAINED_KEYS.contains(e.getKey()) && e.getValue() != null) {
                        sanitized.put(e.getKey(), e.getValue());
                    }
                }
                sanitized.put("evidenceId", stored.id().toString());
                sanitized.put("rawEvidenceStatus", stored.rawEvidenceStatus());
                if (stored.rawEvidenceSha256() != null) {
                    sanitized.put("rawEvidenceSha256", stored.rawEvidenceSha256());
                }
                sanitized.put("recordedVia", stored.recordedVia());

                List<Object> result = new ArrayList<>();
                result.add(sanitized);
                if (!plans.completeRealSubmissionWithResult(planId, result)) {
                    status.setRollbackOnly();
                    return Result.illegal("plan state changed");
                }
                Map<String, Object> after = new LinkedHashMap<>();
                after.put("detail", detail == null ? "" : detail);
                after.put("evidenceId", stored.id().toString());
                after.put("rawEvidenceStatus", stored.rawEvidenceStatus());
                if (stored.rawEvidenceSha256() != null) {
                    after.put("rawEvidenceSha256", stored.rawEvidenceSha256());
                }
                audit.write(new AuditEntry(workerPrincipal, "AUTOMATION_PLAN_SUBMITTED", "AUTOMATION_PLAN", planId,
                        null, after, null, UuidV7.generate()));
                return new Result(Outcome.SUBMITTED, null, stored.id(), stored.rawEvidenceStatus());
            });
        } catch (DataIntegrityViolationException e) {
            // A second completion for the same plan (submission_evidence.plan_id), or a second
            // SUBMITTED plan for the same application (V043): nothing was written.
            log.warn("Real submission for plan {} rejected by a database constraint: {}", planId,
                    e.getMostSpecificCause().getMessage());
            return Result.illegal("a submission is already recorded for this plan or application");
        }
        if (committed == null || committed.outcome() != Outcome.SUBMITTED) {
            return committed == null ? Result.illegal("plan state changed") : committed;
        }

        if (receipts != null) {
            boolean verified = receipts.tryCaptureForPlan(profileId, plan.applicationId(), planId);
            log.info("Plan {} recorded SUBMITTED (evidence {}, raw {}); verified receipt captured: {}",
                    planId, committed.evidenceId(), committed.rawEvidenceStatus(), verified);
        }
        return committed;
    }

    record Validation(boolean valid, String reason, Map<String, Object> entry) {
        static Validation invalid(String reason) {
            return new Validation(false, reason, null);
        }
    }

    @SuppressWarnings("unchecked")
    static Validation validate(String planType, List<Map<String, Object>> reported) {
        if (reported == null || reported.isEmpty()) {
            return Validation.invalid("no structured result was reported");
        }
        List<Map<String, Object>> entries = reported.stream()
                .filter(m -> m != null && EVIDENCE_TYPE.equals(m.get("type")))
                .toList();
        if (entries.size() != 1) {
            return Validation.invalid("exactly one " + EVIDENCE_TYPE + " entry is required, found " + entries.size());
        }
        Map<String, Object> e = entries.get(0);
        if (!planType.equals(e.get("planType"))) {
            return Validation.invalid("evidence planType does not match the plan");
        }
        if (!"CONFIRMED".equals(e.get("category")) || !Boolean.TRUE.equals(e.get("confirmed"))) {
            return Validation.invalid("evidence does not describe a confirmed outcome");
        }
        if (!(e.get("evidence") instanceof String text) || text.isBlank() || text.length() > MAX_EVIDENCE_TEXT) {
            return Validation.invalid("evidence text is missing or too long");
        }
        Object number = e.get("confirmationNumber");
        if (number != null && (!(number instanceof String s) || s.isBlank() || s.length() > MAX_SHORT_TEXT)) {
            return Validation.invalid("confirmationNumber must be a non-blank string");
        }
        Object signals = e.get("signals");
        if (signals != null) {
            if (!(signals instanceof List<?> list) || list.size() > MAX_SIGNALS) {
                return Validation.invalid("signals must be a list of at most " + MAX_SIGNALS + " strings");
            }
            for (Object s : list) {
                if (!(s instanceof String str) || str.isBlank() || str.length() > MAX_SHORT_TEXT) {
                    return Validation.invalid("every signal must be a non-blank string");
                }
            }
        }
        Object summary = e.get("summary");
        if (summary != null && (!(summary instanceof String s) || s.length() > MAX_EVIDENCE_TEXT)) {
            return Validation.invalid("summary must be a string of at most " + MAX_EVIDENCE_TEXT + " characters");
        }
        Object raw = e.get("rawEvidence");
        if (raw != null && !(raw instanceof String)) {
            return Validation.invalid("rawEvidence must be a string");
        }
        return new Validation(true, null, e);
    }

    private static List<String> signalsOf(Map<String, Object> entry) {
        if (entry.get("signals") instanceof List<?> list) {
            return list.stream().map(String::valueOf).map(String::trim).toList();
        }
        return List.of();
    }

    private static String stringOrNull(Object o) {
        return o instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String planTypeOf(AutomationPlanRepository.PlanRow plan) {
        Object planType = plan.plan() == null ? null : plan.plan().get("planType");
        return planType == null ? "GREENHOUSE" : String.valueOf(planType);
    }

    private static boolean isRealSubmitAttempt(AutomationPlanRepository.PlanRow plan) {
        if (plan.plan() == null || !(plan.plan().get("steps") instanceof List<?> steps) || steps.isEmpty()) {
            return false;
        }
        if (!(steps.get(steps.size() - 1) instanceof Map<?, ?> last)) {
            return false;
        }
        String type = String.valueOf(last.get("type"));
        return "REAL_SUBMIT_CLICK".equals(type)
                || "ASHBY_SUBMIT_CLICK".equals(type)
                || "WORKDAY_SUBMIT_CLICK".equals(type)
                || "UNIVERSAL_SUBMIT_CLICK".equals(type);
    }
}
