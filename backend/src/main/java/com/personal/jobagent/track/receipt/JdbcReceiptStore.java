package com.personal.jobagent.track.receipt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.audit.AuditEntry;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.common.UuidV7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC implementation of {@link ReceiptStore}.
 *
 * <p>The receipt row and its {@code SUBMISSION_RECEIPT_CAPTURED} audit row are
 * written in one transaction: if the audit write fails, the receipt is rolled
 * back and the caller is told persistence failed. A concurrent duplicate insert
 * blocks on the unique index until the winner commits, then does nothing, and
 * reads the winner's row back.
 */
@Component
public class JdbcReceiptStore implements ReceiptStore {

    public static final String AUDIT_ACTION = "SUBMISSION_RECEIPT_CAPTURED";

    private static final String COLUMNS = """
            id, idempotency_key, profile_id, application_id, plan_id, attempt_number, evidence_id, evidence_sha256,
            ats_platform, employer_confirmation_id, observed_signals::text as observed_signals, approval_audit_id,
            approval_actor, approved_at, worker_principal, sandbox_registration_id, policy_version, captured_at
            """;

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final AuditLogWriter audit;
    private final TransactionTemplate tx;

    public JdbcReceiptStore(JdbcTemplate db, ObjectMapper json, AuditLogWriter audit,
                            PlatformTransactionManager transactionManager) {
        this.db = db;
        this.json = json;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Override
    public InsertResult insertIfAbsent(NewReceipt r) {
        return tx.execute(status -> {
            UUID id = UuidV7.generate();
            String signals;
            try {
                signals = json.writeValueAsString(r.observedSignals() == null ? List.of() : r.observedSignals());
            } catch (Exception e) {
                throw new IllegalStateException("Unable to serialise observed signals", e);
            }
            List<UUID> inserted = db.query("""
                    insert into submission_receipts(id, idempotency_key, plan_id, application_id, profile_id,
                        attempt_number, evidence_id, evidence_sha256, ats_platform, employer_confirmation_id,
                        observed_signals, approval_audit_id, approval_actor, approved_at, worker_principal,
                        sandbox_registration_id, policy_version)
                    values (?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?)
                    on conflict do nothing
                    returning id
                    """, (rs, n) -> (UUID) rs.getObject(1),
                    id, r.idempotencyKey(), r.planId(), r.applicationId(), r.profileId(), r.attemptNumber(),
                    r.evidenceId(), r.evidenceSha256(), r.atsPlatform(), r.employerConfirmationId(), signals,
                    r.approvalAuditId(), r.approvalActor(), Timestamp.from(r.approvedAt()), r.workerPrincipal(),
                    r.sandboxRegistrationId(), r.policyVersion());
            if (inserted.isEmpty()) {
                StoredReceipt existing = findByPlanUnscoped(r.planId())
                        .orElseThrow(() -> new IllegalStateException(
                                "receipt insert conflicted but no receipt exists for plan " + r.planId()));
                return new InsertResult(existing, false);
            }
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("receiptId", id.toString());
            after.put("planId", r.planId().toString());
            after.put("applicationId", r.applicationId().toString());
            after.put("idempotencyKey", r.idempotencyKey());
            after.put("evidenceId", r.evidenceId().toString());
            after.put("evidenceSha256", r.evidenceSha256());
            after.put("atsPlatform", r.atsPlatform());
            after.put("approvalAuditId", r.approvalAuditId().toString());
            after.put("sandboxRegistrationId", r.sandboxRegistrationId().toString());
            after.put("policyVersion", r.policyVersion());
            audit.write(new AuditEntry("system", AUDIT_ACTION, "APPLICATION_RECEIPT", r.applicationId(),
                    null, after, null, UuidV7.generate()));
            StoredReceipt stored = findByPlanUnscoped(r.planId())
                    .orElseThrow(() -> new IllegalStateException("receipt vanished after insert"));
            return new InsertResult(stored, true);
        });
    }

    @Override
    public Optional<StoredReceipt> findByPlan(UUID profileId, UUID planId) {
        if (profileId == null || planId == null) {
            return Optional.empty();
        }
        return db.query("select " + COLUMNS + " from submission_receipts where plan_id = ? and profile_id = ?",
                (rs, n) -> map(rs), planId, profileId).stream().findFirst();
    }

    private Optional<StoredReceipt> findByPlanUnscoped(UUID planId) {
        return db.query("select " + COLUMNS + " from submission_receipts where plan_id = ?",
                (rs, n) -> map(rs), planId).stream().findFirst();
    }

    private StoredReceipt map(ResultSet rs) throws SQLException {
        List<String> signals;
        try {
            signals = json.readValue(rs.getString("observed_signals"), new TypeReference<List<String>>() {});
        } catch (Exception e) {
            signals = List.of();
        }
        return new StoredReceipt(
                (UUID) rs.getObject("id"),
                rs.getString("idempotency_key"),
                (UUID) rs.getObject("profile_id"),
                (UUID) rs.getObject("application_id"),
                (UUID) rs.getObject("plan_id"),
                rs.getInt("attempt_number"),
                (UUID) rs.getObject("evidence_id"),
                rs.getString("evidence_sha256").trim(),
                rs.getString("ats_platform"),
                rs.getString("employer_confirmation_id"),
                signals,
                (UUID) rs.getObject("approval_audit_id"),
                rs.getString("approval_actor"),
                rs.getTimestamp("approved_at").toInstant(),
                rs.getString("worker_principal"),
                (UUID) rs.getObject("sandbox_registration_id"),
                rs.getString("policy_version"),
                rs.getTimestamp("captured_at").toInstant());
    }
}
