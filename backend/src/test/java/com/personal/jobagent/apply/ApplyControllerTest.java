package com.personal.jobagent.apply;

import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.automation.AshbyExecutionPlanService;
import com.personal.jobagent.automation.GreenhouseExecutionPlanService;
import com.personal.jobagent.automation.InspectionPlanService;
import com.personal.jobagent.automation.LeverExecutionPlanService;
import com.personal.jobagent.automation.WorkdayExecutionPlanService;
import com.personal.jobagent.automation.ZohoRecruitExecutionPlanService;
import com.personal.jobagent.jobs.JobRepository;
import com.personal.jobagent.mailbox.RegistrationIdentityService;
import com.personal.jobagent.security.OwnerContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 11: focused unit coverage for {@link ApplyController#createWorkdayAccount},
 * mirroring {@code EmployerCredentialControllerTest}'s style — pure Mockito
 * mocks, no Spring context. The pre-existing readiness/createPackage
 * endpoints already have end-to-end coverage via {@code ApplyWorkflowIT};
 * this class covers only the new account-creation entry point.
 */
class ApplyControllerTest {

    private static final UUID PROFILE = UUID.randomUUID();
    private static final UUID APPLICATION = UUID.randomUUID();
    private static final UUID JOB = UUID.randomUUID();

    private final OwnerContext ownerContext = mock(OwnerContext.class);
    private final ApplyReadinessService readiness = mock(ApplyReadinessService.class);
    private final GreenhouseExecutionPlanService greenhousePlans = mock(GreenhouseExecutionPlanService.class);
    private final ZohoRecruitExecutionPlanService zohoRecruitPlans = mock(ZohoRecruitExecutionPlanService.class);
    private final LeverExecutionPlanService leverPlans = mock(LeverExecutionPlanService.class);
    private final WorkdayExecutionPlanService workdayPlans = mock(WorkdayExecutionPlanService.class);
    private final AshbyExecutionPlanService ashbyPlans = mock(AshbyExecutionPlanService.class);
    private final InspectionPlanService inspectionPlans = mock(InspectionPlanService.class);
    private final JobRepository jobs = mock(JobRepository.class);
    private final AuditLogWriter audit = mock(AuditLogWriter.class);
    private final ApplyController controller = new ApplyController(ownerContext, readiness, greenhousePlans,
            zohoRecruitPlans, leverPlans, workdayPlans, ashbyPlans, inspectionPlans, jobs, audit);

    private Map<String, Object> readinessState() {
        Map<String, Object> application = new LinkedHashMap<>();
        application.put("jobId", JOB.toString());
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("application", application);
        return state;
    }

    @BeforeEach
    void setUp() {
        when(ownerContext.profileIdOrNull()).thenReturn(PROFILE);
        when(ownerContext.actorOr(any())).thenReturn("user");
        when(readiness.evaluate(PROFILE, APPLICATION)).thenReturn(readinessState());
    }

    @Test
    void withoutAProfileIsReportedAsBadRequest() {
        when(ownerContext.profileIdOrNull()).thenReturn(null);

        ResponseEntity<?> response = controller.createWorkdayAccount(APPLICATION, null);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verifyNoWorkdayCall();
    }

    @Test
    void aForeignOrMissingApplicationIs404() {
        when(readiness.evaluate(PROFILE, APPLICATION)).thenThrow(new NoSuchElementException());

        ResponseEntity<?> response = controller.createWorkdayAccount(APPLICATION, null);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verifyNoWorkdayCall();
    }

    @Test
    void anUnavailableReadinessCheckIs503NeverAFalseAttempt() {
        when(readiness.evaluate(PROFILE, APPLICATION))
                .thenThrow(new ApplyReadinessService.ReadinessUnavailableException("db down"));

        ResponseEntity<?> response = controller.createWorkdayAccount(APPLICATION, null);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        verifyNoWorkdayCall();
    }

    @Test
    void aMissingRequestBodyIsTreatedAsTermsNotAcceptedNeverADefaultAuthorization() {
        when(workdayPlans.authorizeAccountCreation(PROFILE, APPLICATION, JOB, false))
                .thenThrow(new WorkdayExecutionPlanService.AccountCreationRefusedException(
                        "TERMS_NOT_ACCEPTED", "Creating a Workday account requires explicit acceptance"));

        ResponseEntity<?> response = controller.createWorkdayAccount(APPLICATION, null);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(((Map<?, ?>) response.getBody()).get("code")).isEqualTo("TERMS_NOT_ACCEPTED");
    }

    @Test
    void anExplicitAcceptanceAuthorizesAccountCreationAndAudits() {
        UUID planId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(workdayPlans.authorizeAccountCreation(PROFILE, APPLICATION, JOB, true))
                .thenReturn(new WorkdayExecutionPlanService.AccountCreationResult(planId, sessionId, false));

        ResponseEntity<?> response = controller.createWorkdayAccount(APPLICATION,
                new ApplyController.WorkdayAccountRequest(true));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertThat(body.get("planId")).isEqualTo(planId.toString());
        assertThat(body.get("sessionId")).isEqualTo(sessionId.toString());
        assertThat(body.get("reusedExisting")).isEqualTo(false);
        verify(audit).write(argThatActionIs("WORKDAY_ACCOUNT_CREATION_AUTHORIZED"));
    }

    @Test
    void aRefusalIsReportedAsConflictWithItsOwnReasonCodeAndIsAudited() {
        when(workdayPlans.authorizeAccountCreation(PROFILE, APPLICATION, JOB, true))
                .thenThrow(new WorkdayExecutionPlanService.AccountCreationRefusedException(
                        "CREDENTIAL_ALREADY_EXISTS", "A Workday credential is already saved for this employer domain"));

        ResponseEntity<?> response = controller.createWorkdayAccount(APPLICATION,
                new ApplyController.WorkdayAccountRequest(true));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(((Map<?, ?>) response.getBody()).get("code")).isEqualTo("CREDENTIAL_ALREADY_EXISTS");
        verify(audit).write(argThatActionIs("WORKDAY_ACCOUNT_CREATION_REFUSED"));
    }

    @Test
    void aNotReadyManagedMailboxIsReportedAsConflictNeverAsSuccess() {
        when(workdayPlans.authorizeAccountCreation(PROFILE, APPLICATION, JOB, true))
                .thenThrow(new RegistrationIdentityService.ManagedMailboxNotReadyException("NOT_CONFIGURED"));

        ResponseEntity<?> response = controller.createWorkdayAccount(APPLICATION,
                new ApplyController.WorkdayAccountRequest(true));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(((Map<?, ?>) response.getBody()).get("code")).isEqualTo("MANAGED_MAILBOX_NOT_READY");
    }

    @Test
    void aSessionMismatchIsReportedAsConflict() {
        when(workdayPlans.authorizeAccountCreation(PROFILE, APPLICATION, JOB, true))
                .thenThrow(new RegistrationIdentityService.SessionMismatchException("The account session is closed"));

        ResponseEntity<?> response = controller.createWorkdayAccount(APPLICATION,
                new ApplyController.WorkdayAccountRequest(true));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(((Map<?, ?>) response.getBody()).get("code")).isEqualTo("SESSION_MISMATCH");
    }

    // ── Phase 11A: workdayAccountVerificationStatus ──────────────────────

    @Test
    void verificationStatusWithoutAProfileIsReportedAsBadRequest() {
        when(ownerContext.profileIdOrNull()).thenReturn(null);

        ResponseEntity<?> response = controller.workdayAccountVerificationStatus(APPLICATION);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(workdayPlans);
    }

    @Test
    void verificationStatusForAForeignOrMissingApplicationIs404() {
        when(workdayPlans.checkAccountVerificationStatus(PROFILE, APPLICATION))
                .thenThrow(new NoSuchElementException());

        ResponseEntity<?> response = controller.workdayAccountVerificationStatus(APPLICATION);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void verificationStatusReturnsTheCoarseStatusProviderAndTimestampNeverAnOtpOrLink() {
        java.time.Instant receivedAt = java.time.Instant.now();
        when(workdayPlans.checkAccountVerificationStatus(PROFILE, APPLICATION)).thenReturn(
                new WorkdayExecutionPlanService.VerificationStatus(
                        "VERIFICATION_MESSAGE_MATCHED", "robin-managed", receivedAt, null));

        ResponseEntity<?> response = controller.workdayAccountVerificationStatus(APPLICATION);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertThat(body.get("status")).isEqualTo("VERIFICATION_MESSAGE_MATCHED");
        assertThat(body.get("provider")).isEqualTo("robin-managed");
        assertThat(body.get("receivedAt")).isEqualTo(receivedAt.toString());
        assertThat(body.containsKey("otp")).isFalse();
        assertThat(body.containsKey("link")).isFalse();
    }

    private void verifyNoWorkdayCall() {
        verify(workdayPlans, never()).authorizeAccountCreation(any(), any(), any(), eq(true));
        verify(workdayPlans, never()).authorizeAccountCreation(any(), any(), any(), eq(false));
    }

    private com.personal.jobagent.audit.AuditEntry argThatActionIs(String action) {
        return org.mockito.ArgumentMatchers.argThat(entry -> entry != null && action.equals(entry.action()));
    }
}
