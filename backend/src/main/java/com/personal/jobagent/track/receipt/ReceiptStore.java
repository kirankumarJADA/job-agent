package com.personal.jobagent.track.receipt;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable storage for verified receipts ({@code submission_receipts}, V069).
 *
 * <p>{@link #insertIfAbsent} must insert the receipt row and its audit row in a
 * single transaction, and must be safe under concurrent duplicate captures:
 * the database unique constraints decide the winner, and the loser gets the
 * already-committed row back with {@code inserted=false}.
 */
public interface ReceiptStore {

    record NewReceipt(
            UUID profileId,
            UUID applicationId,
            UUID planId,
            int attemptNumber,
            UUID evidenceId,
            String evidenceSha256,
            String atsPlatform,
            String employerConfirmationId,
            List<String> observedSignals,
            UUID approvalAuditId,
            String approvalActor,
            Instant approvedAt,
            String workerPrincipal,
            UUID sandboxRegistrationId,
            String policyVersion
    ) {
        /** One receipt per plan attempt: the idempotency key is derived from the plan id alone. */
        public String idempotencyKey() {
            return idempotencyKeyFor(planId);
        }
    }

    record StoredReceipt(
            UUID receiptId,
            String idempotencyKey,
            UUID profileId,
            UUID applicationId,
            UUID planId,
            int attemptNumber,
            UUID evidenceId,
            String evidenceSha256,
            String atsPlatform,
            String employerConfirmationId,
            List<String> observedSignals,
            UUID approvalAuditId,
            String approvalActor,
            Instant approvedAt,
            String workerPrincipal,
            UUID sandboxRegistrationId,
            String policyVersion,
            Instant capturedAt
    ) {}

    record InsertResult(StoredReceipt receipt, boolean inserted) {}

    static String idempotencyKeyFor(UUID planId) {
        return "receipt:plan:" + planId;
    }

    InsertResult insertIfAbsent(NewReceipt receipt);

    Optional<StoredReceipt> findByPlan(UUID profileId, UUID planId);
}
