package com.personal.jobagent.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.ats.AtsAdapters;
import com.personal.jobagent.identity.EmployerCredentialService;
import com.personal.jobagent.identity.IdentityService;
import com.personal.jobagent.mailbox.MailboxVerificationProvider;
import com.personal.jobagent.mailbox.RegistrationIdentityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 7: focused unit coverage for the Workday plan builder. Mocked
 * JdbcTemplate/AutomationPlanRepository/EmployerCredentialService (no real
 * DB) — mirrors {@code LeverExecutionPlanServiceTest}. The worker-side
 * execution this plan feeds into (sign-in field fill, CAPTCHA/MFA gating,
 * honeypot avoidance, never a form-fill or submit) is covered separately and
 * much more thoroughly in {@code worker/test/workday.test.js}; this test only
 * proves the plan this service hands to the worker is correctly shaped, and
 * that it honestly reflects whether a credential is saved before ever
 * including a login step.
 */
class WorkdayExecutionPlanServiceTest {

    private static final UUID PROFILE = UUID.randomUUID();
    private static final UUID APPLICATION = UUID.randomUUID();
    private static final UUID JOB = UUID.randomUUID();
    private static final UUID PLAN_ID = UUID.randomUUID();
    private static final String WORKDAY_URL =
            "https://nvidia.wd5.myworkdayjobs.com/en-US/NVIDIAExternalCareerSite/job/Taiwan-Taipei/Senior-HR-Operation-Manager---APAC_JR2024946";
    private static final String WORKDAY_APPLY_URL = WORKDAY_URL + "/apply";

    private JdbcTemplate db;
    private AutomationPlanRepository plans;
    private AtsAdapters.WorkdayAdapter workdayAdapter;
    private EmployerCredentialService credentials;
    private RegistrationIdentityService registrationIdentities;
    private IdentityService identityService;
    private MailboxVerificationProvider mailbox;
    private ApplyDocumentSelector documentSelector;
    private WorkdayExecutionPlanService service;

    @BeforeEach
    void setUp() {
        db = mock(JdbcTemplate.class);
        plans = mock(AutomationPlanRepository.class);
        workdayAdapter = new AtsAdapters.WorkdayAdapter();
        credentials = mock(EmployerCredentialService.class);
        registrationIdentities = mock(RegistrationIdentityService.class);
        identityService = mock(IdentityService.class);
        mailbox = mock(MailboxVerificationProvider.class);
        documentSelector = mock(ApplyDocumentSelector.class);
        service = new WorkdayExecutionPlanService(plans, workdayAdapter, credentials, registrationIdentities,
                identityService, mailbox, documentSelector, db, new ObjectMapper(), 30);
    }

    private void stubApplication(String applicationUrl) {
        when(db.queryForMap(contains("from applications a join jobs j"), eq(APPLICATION), eq(PROFILE), eq(JOB)))
                .thenReturn(Map.of("id", APPLICATION, "application_url", applicationUrl));
    }

    private void stubCandidateIdentity() {
        Map<String, Object> candidate = new java.util.LinkedHashMap<>();
        candidate.put("display_name", "Jane Doe");
        candidate.put("email", "jane@example.com");
        candidate.put("phone", "+44 7700 900123");
        candidate.put("links", "{\"linkedin\":\"https://linkedin.com/in/janedoe\"}");
        candidate.put("location", "London, UK");
        when(db.queryForMap(contains("from applications a join profiles p"), eq(APPLICATION), eq(PROFILE), eq(JOB)))
                .thenReturn(candidate);
    }

    /** Like {@link #stubApplication}, but the row also carries an existing {@code account_session_id}. */
    private void stubApplicationWithSession(String applicationUrl, UUID sessionId) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("id", APPLICATION);
        row.put("application_url", applicationUrl);
        row.put("account_session_id", sessionId);
        when(db.queryForMap(contains("from applications a join jobs j"), eq(APPLICATION), eq(PROFILE), eq(JOB)))
                .thenReturn(row);
    }

    private void stubNoExistingAccountCreationPlan() {
        when(db.queryForList(contains("from automation_plans where profile_id"), eq(UUID.class), eq(PROFILE), anyString()))
                .thenReturn(List.of());
    }

    @Test
    void handlesDelegatesToTheWorkdayAdapter() {
        assertThat(service.handles(WORKDAY_URL)).isTrue();
        assertThat(service.handles("https://boards.greenhouse.io/acme/jobs/1")).isFalse();
    }

    @Test
    void skipsPlanCreationForANonWorkdayUrl() {
        stubApplication("https://boards.greenhouse.io/acme/jobs/1");

        var result = service.createExecutionPlan(PROFILE, APPLICATION, JOB);

        assertThat(result).isEmpty();
        verifyNoInteractions(plans);
    }

    @Test
    void withoutASavedCredentialThePlanIsWorkdayApplyAndHonestlyMarkedUnsigned() {
        stubApplication(WORKDAY_URL);
        stubCandidateIdentity();
        when(credentials.exists(PROFILE, "nvidia.wd5.myworkdayjobs.com")).thenReturn(false);
        when(plans.create(eq(PROFILE), eq(APPLICATION), eq(JOB), eq(WORKDAY_APPLY_URL),
                eq("workday:" + APPLICATION), anyList())).thenReturn(PLAN_ID);
        when(plans.findById(PLAN_ID)).thenReturn(java.util.Optional.of(new AutomationPlanRepository.PlanRow(
                PLAN_ID, APPLICATION, WORKDAY_APPLY_URL, "PREPARED", Map.of(), List.of(), false, Instant.now(), Instant.now())));
        when(db.update(contains("update automation_plans set plan"), anyString(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION)))
                .thenReturn(1);

        var result = service.createExecutionPlan(PROFILE, APPLICATION, JOB);

        assertThat(result).contains(PLAN_ID);
        var bodyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(db).update(contains("update automation_plans set plan"), bodyCaptor.capture(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION));
        String body = bodyCaptor.getValue();
        assertThat(body).contains("\"planType\":\"WORKDAY\"");
        assertThat(body).contains("\"credentialSaved\":false");
        assertThat(body).contains("WORKDAY_APPLY");
        // Never signs in with nothing: no credential-resolve step.
        assertThat(body).doesNotContain("ACCOUNT_RESOLVE_CREDENTIAL");
        // Never a submission click step until candidate approval.
        assertThat(body).doesNotContain("REAL_SUBMIT_CLICK").doesNotContain("MOCK_SUBMIT").doesNotContain("WORKDAY_SUBMIT_CLICK");
    }

    @Test
    void withASavedCredentialThePlanAddsCredentialResolutionThenWorkdayApplyAndNeverSubmits() {
        stubApplication(WORKDAY_URL);
        stubCandidateIdentity();
        when(credentials.exists(PROFILE, "nvidia.wd5.myworkdayjobs.com")).thenReturn(true);
        when(plans.create(eq(PROFILE), eq(APPLICATION), eq(JOB), eq(WORKDAY_APPLY_URL),
                eq("workday:" + APPLICATION), anyList())).thenReturn(PLAN_ID);
        when(plans.findById(PLAN_ID)).thenReturn(java.util.Optional.of(new AutomationPlanRepository.PlanRow(
                PLAN_ID, APPLICATION, WORKDAY_APPLY_URL, "PREPARED", Map.of(), List.of(), false, Instant.now(), Instant.now())));
        when(db.update(contains("update automation_plans set plan"), anyString(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION)))
                .thenReturn(1);

        var result = service.createExecutionPlan(PROFILE, APPLICATION, JOB);

        assertThat(result).contains(PLAN_ID);
        var bodyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(db).update(contains("update automation_plans set plan"), bodyCaptor.capture(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION));
        String body = bodyCaptor.getValue();
        assertThat(body).contains("\"credentialSaved\":true");
        assertThat(body).contains("ACCOUNT_RESOLVE_CREDENTIAL");
        assertThat(body).contains("WORKDAY_APPLY");
        assertThat(body).doesNotContain("REAL_SUBMIT_CLICK").doesNotContain("MOCK_SUBMIT").doesNotContain("WORKDAY_SUBMIT_CLICK");
    }

    @Test
    void aFailedPlanIsRequeuedAndRebuiltOnTheCandidatesExplicitRetry() {
        stubApplication(WORKDAY_URL);
        stubCandidateIdentity();
        when(credentials.exists(PROFILE, "nvidia.wd5.myworkdayjobs.com")).thenReturn(true);
        when(plans.create(any(), any(), any(), any(), any(), anyList())).thenReturn(PLAN_ID);
        when(plans.findById(PLAN_ID)).thenReturn(java.util.Optional.of(new AutomationPlanRepository.PlanRow(
                PLAN_ID, APPLICATION, WORKDAY_APPLY_URL, "FAILED", Map.of(), List.of(), false, Instant.now(), Instant.now())));
        when(plans.requeueFailedPlan(PLAN_ID)).thenReturn(true);
        when(db.update(contains("update automation_plans set plan"), anyString(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION)))
                .thenReturn(1);

        var result = service.createExecutionPlan(PROFILE, APPLICATION, JOB);

        assertThat(result).contains(PLAN_ID);
        verify(plans).requeueFailedPlan(PLAN_ID);
    }

    @Test
    void createRetryExecutionPlanCreatesAnIndependentRetryPlanForWorkday() {
        UUID supersededPlanId = UUID.randomUUID();
        stubApplication(WORKDAY_URL);
        stubCandidateIdentity();
        when(credentials.exists(PROFILE, "nvidia.wd5.myworkdayjobs.com")).thenReturn(false);
        when(plans.create(eq(PROFILE), eq(APPLICATION), eq(JOB), eq(WORKDAY_APPLY_URL),
                eq("workday-retry:" + supersededPlanId), anyList())).thenReturn(PLAN_ID);
        when(plans.findById(PLAN_ID)).thenReturn(java.util.Optional.of(new AutomationPlanRepository.PlanRow(
                PLAN_ID, APPLICATION, WORKDAY_APPLY_URL, "PREPARED", Map.of(), List.of(), false, Instant.now(), Instant.now())));
        when(db.update(contains("update automation_plans set plan"), anyString(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION)))
                .thenReturn(1);

        var result = service.createRetryExecutionPlan(PROFILE, APPLICATION, JOB, supersededPlanId);

        assertThat(result).contains(PLAN_ID);
        verify(plans).create(eq(PROFILE), eq(APPLICATION), eq(JOB), eq(WORKDAY_APPLY_URL),
                eq("workday-retry:" + supersededPlanId), anyList());
    }

    @Test
    void aPlanNotInPreparedStateIsLeftAloneAndNotRebuilt() {
        stubApplication(WORKDAY_URL);
        when(plans.create(any(), any(), any(), any(), any(), anyList())).thenReturn(PLAN_ID);
        when(plans.findById(PLAN_ID)).thenReturn(java.util.Optional.of(new AutomationPlanRepository.PlanRow(
                PLAN_ID, APPLICATION, WORKDAY_APPLY_URL, "RUNNING", Map.of(), List.of(), false, Instant.now(), Instant.now())));

        var result = service.createExecutionPlan(PROFILE, APPLICATION, JOB);

        assertThat(result).contains(PLAN_ID);
        verify(db, never()).update(contains("update automation_plans set plan"), anyString(), any(), any(), any());
        verifyNoInteractions(credentials);
    }

    @Test
    void theApplyUrlIsDerivedByAppendingApplyAndIsIdempotentIfAlreadyPresent() {
        assertThat(WorkdayExecutionPlanService.applyUrlOf(WORKDAY_URL)).isEqualTo(WORKDAY_APPLY_URL);
        assertThat(WorkdayExecutionPlanService.applyUrlOf(WORKDAY_APPLY_URL)).isEqualTo(WORKDAY_APPLY_URL);
        assertThat(WorkdayExecutionPlanService.applyUrlOf(WORKDAY_URL + "/")).isEqualTo(WORKDAY_APPLY_URL);
    }

    @Test
    void theEmployerDomainIsTheLowercasedHostOfTheApplicationUrl() {
        assertThat(WorkdayExecutionPlanService.domainOf(WORKDAY_URL)).isEqualTo("nvidia.wd5.myworkdayjobs.com");
        assertThat(WorkdayExecutionPlanService.domainOf("not a url")).isNull();
    }

    // ── Phase 11: authorizeAccountCreation ──────────────────────────────

    @Test
    void authorizeAccountCreationRefusesWithoutExplicitTermsAcceptance() {
        stubNoExistingAccountCreationPlan();

        assertThatThrownBy(() -> service.authorizeAccountCreation(PROFILE, APPLICATION, JOB, false))
                .isInstanceOf(WorkdayExecutionPlanService.AccountCreationRefusedException.class)
                .extracting(e -> ((WorkdayExecutionPlanService.AccountCreationRefusedException) e).reason())
                .isEqualTo("TERMS_NOT_ACCEPTED");
        verifyNoInteractions(credentials, registrationIdentities, identityService);
    }

    @Test
    void authorizeAccountCreationRefusesForANonWorkdayApplication() {
        stubNoExistingAccountCreationPlan();
        stubApplication("https://boards.greenhouse.io/acme/jobs/1");

        assertThatThrownBy(() -> service.authorizeAccountCreation(PROFILE, APPLICATION, JOB, true))
                .isInstanceOf(WorkdayExecutionPlanService.AccountCreationRefusedException.class)
                .extracting(e -> ((WorkdayExecutionPlanService.AccountCreationRefusedException) e).reason())
                .isEqualTo("NOT_A_WORKDAY_APPLICATION");
        verifyNoInteractions(credentials, registrationIdentities, identityService);
    }

    @Test
    void authorizeAccountCreationRefusesWhenACredentialAlreadyExists() {
        stubNoExistingAccountCreationPlan();
        stubApplication(WORKDAY_URL);
        when(credentials.exists(PROFILE, "nvidia.wd5.myworkdayjobs.com")).thenReturn(true);

        assertThatThrownBy(() -> service.authorizeAccountCreation(PROFILE, APPLICATION, JOB, true))
                .isInstanceOf(WorkdayExecutionPlanService.AccountCreationRefusedException.class)
                .extracting(e -> ((WorkdayExecutionPlanService.AccountCreationRefusedException) e).reason())
                .isEqualTo("CREDENTIAL_ALREADY_EXISTS");
        verifyNoInteractions(registrationIdentities, identityService);
        verify(credentials, never()).save(any(), any(), any(), any());
    }

    @Test
    void authorizeAccountCreationRefusesWhenTheIdentityIsAlreadyRegistered() {
        UUID sessionId = UUID.randomUUID();
        stubNoExistingAccountCreationPlan();
        stubApplication(WORKDAY_URL);
        when(credentials.exists(PROFILE, "nvidia.wd5.myworkdayjobs.com")).thenReturn(false);
        when(identityService.startSession(APPLICATION, "nvidia.wd5.myworkdayjobs.com", PROFILE))
                .thenReturn(Optional.of(sessionId));
        when(registrationIdentities.resolve(PROFILE, APPLICATION, sessionId, "nvidia.wd5.myworkdayjobs.com"))
                .thenReturn(new RegistrationIdentityService.RegistrationIdentity(
                        UUID.randomUUID(), "already@registered.example", "nvidia.wd5.myworkdayjobs.com",
                        "managed", true));

        assertThatThrownBy(() -> service.authorizeAccountCreation(PROFILE, APPLICATION, JOB, true))
                .isInstanceOf(WorkdayExecutionPlanService.AccountCreationRefusedException.class)
                .extracting(e -> ((WorkdayExecutionPlanService.AccountCreationRefusedException) e).reason())
                .isEqualTo("IDENTITY_ALREADY_REGISTERED");
        verify(credentials, never()).save(any(), any(), any(), any());
    }

    @Test
    void authorizeAccountCreationStartsASessionGeneratesAFreshPasswordAndBuildsTheCreateAccountPlan() {
        UUID sessionId = UUID.randomUUID();
        stubNoExistingAccountCreationPlan();
        stubApplication(WORKDAY_URL);
        when(credentials.exists(PROFILE, "nvidia.wd5.myworkdayjobs.com")).thenReturn(false);
        when(identityService.startSession(APPLICATION, "nvidia.wd5.myworkdayjobs.com", PROFILE))
                .thenReturn(Optional.of(sessionId));
        when(registrationIdentities.resolve(PROFILE, APPLICATION, sessionId, "nvidia.wd5.myworkdayjobs.com"))
                .thenReturn(new RegistrationIdentityService.RegistrationIdentity(
                        UUID.randomUUID(), "candidate@managed.robinapp.example", "nvidia.wd5.myworkdayjobs.com",
                        "managed", false));
        when(plans.create(eq(PROFILE), eq(APPLICATION), eq(JOB), eq(WORKDAY_APPLY_URL),
                eq("workday-create-account:" + APPLICATION), anyList())).thenReturn(PLAN_ID);
        when(db.update(contains("update automation_plans set plan"), anyString(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION)))
                .thenReturn(1);

        var result = service.authorizeAccountCreation(PROFILE, APPLICATION, JOB, true);

        assertThat(result.planId()).isEqualTo(PLAN_ID);
        assertThat(result.sessionId()).isEqualTo(sessionId);
        assertThat(result.reusedExisting()).isFalse();

        var passwordCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(credentials).save(eq(PROFILE), eq("nvidia.wd5.myworkdayjobs.com"),
                eq("candidate@managed.robinapp.example"), passwordCaptor.capture());
        // A fresh, sufficiently random, non-blank password — never invented or empty.
        assertThat(passwordCaptor.getValue()).isNotBlank().hasSizeGreaterThan(16);

        var bodyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(db).update(contains("update automation_plans set plan"), bodyCaptor.capture(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION));
        String body = bodyCaptor.getValue();
        assertThat(body).contains("\"accountCreationAttempted\":true");
        assertThat(body).contains("\"credentialSaved\":false");
        assertThat(body).contains("ACCOUNT_RESOLVE_IDENTITY");
        assertThat(body).contains("ACCOUNT_RESOLVE_CREDENTIAL");
        assertThat(body).contains("WORKDAY_CREATE_ACCOUNT");
        assertThat(body).contains("\"termsAuthorized\":true");
        assertThat(body).contains(sessionId.toString());
        // Never a submission capability, and never the sign-in-only step in this plan.
        assertThat(body).doesNotContain("REAL_SUBMIT_CLICK").doesNotContain("MOCK_SUBMIT")
                .doesNotContain("WORKDAY_LOGIN");
        int createAccountIndex = body.indexOf("WORKDAY_CREATE_ACCOUNT");
        int lastStepsBracket = body.lastIndexOf("]");
        assertThat(createAccountIndex).isLessThan(lastStepsBracket);
    }

    @Test
    void authorizeAccountCreationReusesAnAlreadyOpenAccountSessionInsteadOfStartingANewOne() {
        UUID sessionId = UUID.randomUUID();
        stubNoExistingAccountCreationPlan();
        stubApplicationWithSession(WORKDAY_URL, sessionId);
        when(credentials.exists(PROFILE, "nvidia.wd5.myworkdayjobs.com")).thenReturn(false);
        when(db.queryForList(contains("from account_sessions where id"), eq(String.class), eq(sessionId), eq(PROFILE)))
                .thenReturn(List.of("SIGNUP"));
        when(registrationIdentities.resolve(PROFILE, APPLICATION, sessionId, "nvidia.wd5.myworkdayjobs.com"))
                .thenReturn(new RegistrationIdentityService.RegistrationIdentity(
                        UUID.randomUUID(), "candidate@managed.robinapp.example", "nvidia.wd5.myworkdayjobs.com",
                        "managed", false));
        when(plans.create(eq(PROFILE), eq(APPLICATION), eq(JOB), eq(WORKDAY_APPLY_URL),
                eq("workday-create-account:" + APPLICATION), anyList())).thenReturn(PLAN_ID);
        when(db.update(contains("update automation_plans set plan"), anyString(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION)))
                .thenReturn(1);

        var result = service.authorizeAccountCreation(PROFILE, APPLICATION, JOB, true);

        assertThat(result.sessionId()).isEqualTo(sessionId);
        verify(identityService, never()).startSession(any(), any(), any());
    }

    @Test
    void authorizeAccountCreationIsIdempotentOnRetryAndNeverTouchesCredentialsOrIdentityAgain() {
        UUID existingSessionId = UUID.randomUUID();
        when(db.queryForList(contains("from automation_plans where profile_id"), eq(UUID.class), eq(PROFILE),
                eq("workday-create-account:" + APPLICATION))).thenReturn(List.of(PLAN_ID));
        when(db.queryForObject(contains("select account_session_id from applications"), eq(UUID.class),
                eq(APPLICATION), eq(PROFILE))).thenReturn(existingSessionId);

        var result = service.authorizeAccountCreation(PROFILE, APPLICATION, JOB, true);

        assertThat(result.planId()).isEqualTo(PLAN_ID);
        assertThat(result.sessionId()).isEqualTo(existingSessionId);
        assertThat(result.reusedExisting()).isTrue();
        verifyNoInteractions(credentials, registrationIdentities, identityService, plans);
    }

    // ── Phase 11A: checkAccountVerificationStatus ───────────────────────

    private void stubApplicationSession(UUID sessionId) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("account_session_id", sessionId);
        when(db.queryForList(contains("select account_session_id from applications"), eq(APPLICATION), eq(PROFILE)))
                .thenReturn(List.of(row));
    }

    private void stubAccountCreationPlanId(UUID planId) {
        when(db.queryForList(contains("from automation_plans where profile_id"), eq(UUID.class), eq(PROFILE),
                eq("workday-create-account:" + APPLICATION))).thenReturn(List.of(planId));
    }

    private AutomationPlanRepository.PlanRow planRowWithResult(UUID planId, List<Object> result) {
        return new AutomationPlanRepository.PlanRow(planId, APPLICATION, WORKDAY_APPLY_URL, "COMPLETED",
                Map.of(), result, false, Instant.now(), Instant.now());
    }

    @Test
    void checkAccountVerificationStatusReportsAccountCreationRequiredWhenNoPlanExists() {
        stubApplicationSession(null);
        when(db.queryForList(contains("from automation_plans where profile_id"), eq(UUID.class), eq(PROFILE),
                eq("workday-create-account:" + APPLICATION))).thenReturn(List.of());

        var status = service.checkAccountVerificationStatus(PROFILE, APPLICATION);

        assertThat(status.status()).isEqualTo("ACCOUNT_CREATION_REQUIRED");
        verifyNoInteractions(mailbox);
    }

    @Test
    void checkAccountVerificationStatusThrowsForAForeignOrMissingApplication() {
        when(db.queryForList(contains("select account_session_id from applications"), eq(APPLICATION), eq(PROFILE)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.checkAccountVerificationStatus(PROFILE, APPLICATION))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void checkAccountVerificationStatusReportsInProgressWhenTheWorkerHasNotReportedYet() {
        UUID planId = UUID.randomUUID();
        stubApplicationSession(null);
        stubAccountCreationPlanId(planId);
        when(plans.find(PROFILE, planId)).thenReturn(Optional.of(planRowWithResult(planId, List.of())));

        var status = service.checkAccountVerificationStatus(PROFILE, APPLICATION);

        assertThat(status.status()).isEqualTo("ACCOUNT_CREATION_IN_PROGRESS");
        verifyNoInteractions(mailbox);
    }

    @Test
    void checkAccountVerificationStatusPassesThroughANonCreatedOutcomeHonestlyAndNeverProbesTheMailbox() {
        UUID planId = UUID.randomUUID();
        stubApplicationSession(null);
        stubAccountCreationPlanId(planId);
        when(plans.find(PROFILE, planId)).thenReturn(Optional.of(planRowWithResult(planId, List.of(
                Map.of("type", "WORKDAY_OUTCOME", "loginStatus", "ACCOUNT_CREATE_FAILED",
                        "reason", "the employer's site reported that an account already exists for this email")))));

        var status = service.checkAccountVerificationStatus(PROFILE, APPLICATION);

        assertThat(status.status()).isEqualTo("ACCOUNT_CREATE_FAILED");
        assertThat(status.detail()).contains("already exists");
        verifyNoInteractions(mailbox);
    }

    @Test
    void checkAccountVerificationStatusReportsPendingWhenNoMessageHasArrivedYet() {
        UUID planId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        stubApplicationSession(sessionId);
        stubAccountCreationPlanId(planId);
        when(plans.find(PROFILE, planId)).thenReturn(Optional.of(planRowWithResult(planId, List.of(
                Map.of("type", "WORKDAY_OUTCOME", "loginStatus", "ACCOUNT_CREATED")))));
        when(db.queryForList(contains("from account_sessions s"), eq(sessionId), eq(PROFILE))).thenReturn(List.of(
                new java.util.LinkedHashMap<>(Map.of("state", "SIGNUP", "created_at",
                        java.sql.Timestamp.from(Instant.now().minusSeconds(60)),
                        "registered_email", "candidate@managed.robinapp.example"))));
        when(mailbox.findVerification(eq(PROFILE), eq("nvidia.wd5.myworkdayjobs.com"),
                eq("candidate@managed.robinapp.example"), any())).thenReturn(Optional.empty());

        var status = service.checkAccountVerificationStatus(PROFILE, APPLICATION);

        assertThat(status.status()).isEqualTo("VERIFICATION_MESSAGE_PENDING");
        verify(mailbox, never()).markResolved(any(), any());
    }

    @Test
    void checkAccountVerificationStatusReportsMatchedWithProviderAndTimestampNeverOtpOrLink() {
        UUID planId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        stubApplicationSession(sessionId);
        stubAccountCreationPlanId(planId);
        when(plans.find(PROFILE, planId)).thenReturn(Optional.of(planRowWithResult(planId, List.of(
                Map.of("type", "WORKDAY_OUTCOME", "loginStatus", "ACCOUNT_CREATED")))));
        Instant receivedAt = Instant.now();
        when(db.queryForList(contains("from account_sessions s"), eq(sessionId), eq(PROFILE))).thenReturn(List.of(
                new java.util.LinkedHashMap<>(Map.of("state", "SIGNUP", "created_at",
                        java.sql.Timestamp.from(Instant.now().minusSeconds(60)),
                        "registered_email", "candidate@managed.robinapp.example"))));
        when(mailbox.findVerification(eq(PROFILE), eq("nvidia.wd5.myworkdayjobs.com"),
                eq("candidate@managed.robinapp.example"), any())).thenReturn(Optional.of(
                new MailboxVerificationProvider.VerificationHit("robin-managed", "msg-1", null,
                        "123456", null, receivedAt)));

        var status = service.checkAccountVerificationStatus(PROFILE, APPLICATION);

        assertThat(status.status()).isEqualTo("VERIFICATION_MESSAGE_MATCHED");
        assertThat(status.provider()).isEqualTo("robin-managed");
        assertThat(status.receivedAt()).isEqualTo(receivedAt);
        // The record itself has no otp/link accessor at all — structurally impossible to leak one.
        verify(mailbox, never()).markResolved(any(), any());
    }

    @Test
    void checkAccountVerificationStatusReportsHumanAssistanceRequiredWhenTheSessionWasHardStopped() {
        UUID planId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        stubApplicationSession(sessionId);
        stubAccountCreationPlanId(planId);
        when(plans.find(PROFILE, planId)).thenReturn(Optional.of(planRowWithResult(planId, List.of(
                Map.of("type", "WORKDAY_OUTCOME", "loginStatus", "ACCOUNT_CREATED")))));
        when(db.queryForList(contains("from account_sessions s"), eq(sessionId), eq(PROFILE))).thenReturn(List.of(
                new java.util.LinkedHashMap<>(Map.of("state", "HARD_STOP", "hard_stop_reason", "CAPTCHA_PRESENT",
                        "created_at", java.sql.Timestamp.from(Instant.now()),
                        "registered_email", "candidate@managed.robinapp.example"))));

        var status = service.checkAccountVerificationStatus(PROFILE, APPLICATION);

        assertThat(status.status()).isEqualTo("HUMAN_ASSISTANCE_REQUIRED");
        assertThat(status.detail()).isEqualTo("CAPTCHA_PRESENT");
        verifyNoInteractions(mailbox);
    }

    @Test
    void checkAccountVerificationStatusReportsFailedWhenTheSessionFailed() {
        UUID planId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        stubApplicationSession(sessionId);
        stubAccountCreationPlanId(planId);
        when(plans.find(PROFILE, planId)).thenReturn(Optional.of(planRowWithResult(planId, List.of(
                Map.of("type", "WORKDAY_OUTCOME", "loginStatus", "ACCOUNT_CREATED")))));
        when(db.queryForList(contains("from account_sessions s"), eq(sessionId), eq(PROFILE))).thenReturn(List.of(
                new java.util.LinkedHashMap<>(Map.of("state", "FAILED",
                        "created_at", java.sql.Timestamp.from(Instant.now()),
                        "registered_email", "candidate@managed.robinapp.example"))));

        var status = service.checkAccountVerificationStatus(PROFILE, APPLICATION);

        assertThat(status.status()).isEqualTo("VERIFICATION_FAILED_OR_EXPIRED");
        verifyNoInteractions(mailbox);
    }
}
