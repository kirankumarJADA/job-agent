package com.personal.jobagent.automation;

import com.personal.jobagent.audit.AuditEntry;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.track.ApplicationReceiptService;
import com.personal.jobagent.track.receipt.SubmissionEvidenceRepository;
import com.personal.jobagent.track.receipt.SubmissionEvidenceRepository.NewEvidence;
import com.personal.jobagent.track.receipt.SubmissionEvidenceRepository.StoredEvidence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Sprint 2.2.1 Agent A: the only path from RUNNING to SUBMITTED. */
class SubmissionCompletionServiceTest {

    private final UUID planId = UUID.randomUUID();
    private final UUID applicationId = UUID.randomUUID();
    private final UUID profileId = UUID.randomUUID();
    private final String targetUrl = "https://acme.wd5.myworkdayjobs.com/x/apply";

    private AutomationPlanRepository plans;
    private SubmissionEvidenceRepository evidence;
    private AuditLogWriter audit;
    private PlatformTransactionManager txManager;
    private ApplicationReceiptService receipts;
    private SubmissionCompletionService service;
    private SimpleTransactionStatus lastTx;

    @BeforeEach
    void setUp() {
        plans = mock(AutomationPlanRepository.class);
        evidence = mock(SubmissionEvidenceRepository.class);
        audit = mock(AuditLogWriter.class);
        txManager = mock(PlatformTransactionManager.class);
        receipts = mock(ApplicationReceiptService.class);
        when(txManager.getTransaction(any())).thenAnswer(inv -> {
            lastTx = new SimpleTransactionStatus();
            return lastTx;
        });
        service = new SubmissionCompletionService(plans, evidence, audit, txManager, receipts);
        when(plans.ownerOfPlan(planId)).thenReturn(Optional.of(profileId));
        givenPlan("RUNNING", true, "WORKDAY_SUBMIT_CLICK");
        when(evidence.insert(any())).thenAnswer(inv -> stored(inv.getArgument(0)));
    }

    private void givenPlan(String status, boolean submitApproved, String lastStepType) {
        Map<String, Object> plan = Map.of("planType", "WORKDAY",
                "steps", List.of(Map.of("type", "WORKDAY_APPLY"), Map.of("type", lastStepType)));
        when(plans.findById(planId)).thenReturn(Optional.of(new AutomationPlanRepository.PlanRow(
                planId, applicationId, targetUrl, status, plan, List.of(), submitApproved, Instant.now(), Instant.now())));
    }

    private static StoredEvidence stored(NewEvidence e) {
        return new StoredEvidence(UUID.randomUUID(), e.planId(), e.applicationId(), e.profileId(), e.workerPrincipal(),
                SubmissionEvidenceRepository.RECORDED_VIA_WORKER_COMPLETE, e.atsPlatform(), e.targetUrl(),
                e.confirmationCategory(), e.employerConfirmationId(), e.observedSignals(), e.evidenceText(),
                e.rawEvidence() == null ? null : "c".repeat(64), e.rawEvidence() == null ? null : e.rawEvidence().length(),
                e.rawEvidence() == null ? "NOT_PROVIDED" : "STORED_ENCRYPTED", Instant.now(), null);
    }

    private static Map<String, Object> validEntry() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "SUBMISSION_EVIDENCE");
        m.put("planType", "WORKDAY");
        m.put("category", "CONFIRMED");
        m.put("confirmed", true);
        m.put("evidence", "confirmation page rendered");
        m.put("confirmationNumber", "WD-1");
        m.put("signals", List.of("PLATFORM_SUBMISSION_CONFIRMED"));
        m.put("rawEvidence", "<div>WD-1</div>");
        return m;
    }

    @Test
    void validEvidencePersistsEvidenceTransitionAndAuditInOneTransaction() {
        when(plans.completeRealSubmissionWithResult(eq(planId), anyList())).thenReturn(true);

        var result = service.completeWorkerSubmission(planId, "worker", "confirmed", List.of(validEntry()));

        assertThat(result.outcome()).isEqualTo(SubmissionCompletionService.Outcome.SUBMITTED);
        ArgumentCaptor<NewEvidence> ev = ArgumentCaptor.forClass(NewEvidence.class);
        verify(evidence).insert(ev.capture());
        assertThat(ev.getValue().workerPrincipal()).isEqualTo("worker");
        assertThat(ev.getValue().targetUrl()).isEqualTo(targetUrl);
        assertThat(ev.getValue().rawEvidence()).isEqualTo("<div>WD-1</div>");
        verify(audit).write(any(AuditEntry.class));
        verify(txManager).commit(any(TransactionStatus.class));
        verify(receipts).tryCaptureForPlan(profileId, applicationId, planId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void storedResultDropsRawEvidenceAndEverySelfAssertedTrustField() {
        when(plans.completeRealSubmissionWithResult(eq(planId), anyList())).thenReturn(true);
        Map<String, Object> entry = validEntry();
        entry.put("evidenceProvenance", "AUTHENTICATED_BROWSER_WORKER");
        entry.put("sandboxPosting", true);
        entry.put("candidateAuthorizedSandbox", true);
        entry.put("receiptSha256", "f".repeat(64));
        entry.put("recordedVia", "FORGED");

        service.completeWorkerSubmission(planId, "worker", "confirmed", List.of(entry));

        ArgumentCaptor<List<Object>> captured = ArgumentCaptor.forClass(List.class);
        verify(plans).completeRealSubmissionWithResult(eq(planId), captured.capture());
        Map<String, Object> stored = (Map<String, Object>) captured.getValue().get(0);
        assertThat(stored).doesNotContainKeys("rawEvidence", "evidenceProvenance", "sandboxPosting",
                "candidateAuthorizedSandbox", "receiptSha256");
        assertThat(stored).containsEntry("recordedVia", SubmissionEvidenceRepository.RECORDED_VIA_WORKER_COMPLETE);
        assertThat(stored).containsKeys("evidenceId", "rawEvidenceSha256", "rawEvidenceStatus");
    }

    @Test
    void missingEvidenceMovesTheAttemptToReconciliationNotSubmitted() {
        when(plans.transitionWithResult(eq(planId), eq("RUNNING"), eq("RECONCILIATION_REQUIRED"), anyList())).thenReturn(true);

        var result = service.completeWorkerSubmission(planId, "worker", "confirmed", null);

        assertThat(result.outcome()).isEqualTo(SubmissionCompletionService.Outcome.RECONCILIATION_REQUIRED);
        verify(evidence, never()).insert(any());
        verify(plans, never()).completeRealSubmissionWithResult(any(), anyList());
        verify(receipts, never()).tryCaptureForPlan(any(), any(), any());
    }

    @Test
    void guardFailureRollsBackTheEvidenceInsert() {
        when(plans.completeRealSubmissionWithResult(eq(planId), anyList())).thenReturn(false);

        var result = service.completeWorkerSubmission(planId, "worker", "confirmed", List.of(validEntry()));

        assertThat(result.outcome()).isEqualTo(SubmissionCompletionService.Outcome.ILLEGAL_TRANSITION);
        // TransactionTemplate hands a rollback-only status to commit(); the real
        // (AbstractPlatformTransactionManager) implementation rolls it back. The
        // database-level proof that nothing persists is in ApplicationReceiptIT.
        assertThat(lastTx.isRollbackOnly()).isTrue();
        verify(audit, never()).write(any());
        verify(receipts, never()).tryCaptureForPlan(any(), any(), any());
    }

    @Test
    void duplicateCompletionRejectedByTheDatabaseRecordsNothing() {
        doThrow(new DataIntegrityViolationException("submission_evidence_plan_id_key")).when(evidence).insert(any());

        var result = service.completeWorkerSubmission(planId, "worker", "confirmed", List.of(validEntry()));

        assertThat(result.outcome()).isEqualTo(SubmissionCompletionService.Outcome.ILLEGAL_TRANSITION);
        verify(plans, never()).completeRealSubmissionWithResult(any(), anyList());
        verify(receipts, never()).tryCaptureForPlan(any(), any(), any());
    }

    @Test
    void onlyARunningApprovedRealSubmitAttemptCanBeCompleted() {
        givenPlan("PREPARED", true, "WORKDAY_SUBMIT_CLICK");
        assertThat(service.completeWorkerSubmission(planId, "worker", "d", List.of(validEntry())).outcome())
                .isEqualTo(SubmissionCompletionService.Outcome.ILLEGAL_TRANSITION);
        givenPlan("RUNNING", false, "WORKDAY_SUBMIT_CLICK");
        assertThat(service.completeWorkerSubmission(planId, "worker", "d", List.of(validEntry())).outcome())
                .isEqualTo(SubmissionCompletionService.Outcome.ILLEGAL_TRANSITION);
        givenPlan("RUNNING", true, "WORKDAY_APPLY");
        assertThat(service.completeWorkerSubmission(planId, "worker", "d", List.of(validEntry())).outcome())
                .isEqualTo(SubmissionCompletionService.Outcome.ILLEGAL_TRANSITION);
        verify(evidence, never()).insert(any());
        verify(plans, never()).transitionWithResult(any(), any(), any(), anyList());
    }

    @Test
    void validationRejectsEveryMalformedOrUnconfirmedShape() {
        assertInvalid(null);
        assertInvalid(List.of());
        assertInvalid(List.of(Map.of("type", "WORKDAY_APPLICATION_OUTCOME", "submitted", true)));
        assertInvalid(List.of(validEntry(), validEntry()));
        assertInvalid(List.of(with("planType", "GREENHOUSE")));
        assertInvalid(List.of(with("category", "AMBIGUOUS_NO_CONFIRMATION_CONFIGURED")));
        assertInvalid(List.of(with("confirmed", "true")));
        assertInvalid(List.of(with("evidence", " ")));
        assertInvalid(List.of(with("evidence", "x".repeat(SubmissionCompletionService.MAX_EVIDENCE_TEXT + 1))));
        assertInvalid(List.of(with("confirmationNumber", 12345)));
        assertInvalid(List.of(with("signals", "PLATFORM_SUBMISSION_CONFIRMED")));
        assertInvalid(List.of(with("signals", List.of(" "))));
        assertInvalid(List.of(with("summary", 12345)));
        assertInvalid(List.of(with("summary", "x".repeat(SubmissionCompletionService.MAX_EVIDENCE_TEXT + 1))));
        assertInvalid(List.of(with("rawEvidence", Map.of("dom", "<div/>"))));
        assertThat(SubmissionCompletionService.validate("WORKDAY",
                List.of(with("summary", "x".repeat(SubmissionCompletionService.MAX_EVIDENCE_TEXT)))).valid()).isTrue();
        assertThat(SubmissionCompletionService.validate("WORKDAY", List.of(validEntry())).valid()).isTrue();
        Map<String, Object> minimal = validEntry();
        minimal.remove("confirmationNumber");
        minimal.remove("signals");
        minimal.remove("rawEvidence");
        assertThat(SubmissionCompletionService.validate("WORKDAY", List.of(minimal)).valid()).isTrue();
    }

    private static Map<String, Object> with(String key, Object value) {
        Map<String, Object> m = new HashMap<>(validEntry());
        m.put(key, value);
        return m;
    }

    private static void assertInvalid(List<Map<String, Object>> reported) {
        assertThat(SubmissionCompletionService.validate("WORKDAY", reported).valid()).as(String.valueOf(reported)).isFalse();
    }
}
