package com.personal.jobagent.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.ats.AshbyAdapter;
import com.personal.jobagent.ats.JobFormQuestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 14: focused unit coverage for the Ashby plan builder, mirroring
 * {@code ZohoRecruitExecutionPlanServiceTest}'s mocked-{@code JdbcTemplate}
 * style (no real DB). The worker-side behaviour this plan feeds into (live
 * field classification, CAPTCHA detection, the guarded real submit click) is
 * covered separately and much more thoroughly in
 * {@code worker/test/ashby.test.js}; this test only proves the plan this
 * service hands to the worker is correctly shaped, and that it never appends
 * a submission-capable step itself.
 */
class AshbyExecutionPlanServiceTest {

    private static final UUID PROFILE = UUID.randomUUID();
    private static final UUID APPLICATION = UUID.randomUUID();
    private static final UUID JOB = UUID.randomUUID();
    private static final UUID PLAN_ID = UUID.randomUUID();
    private static final String ASHBY_URL = "https://jobs.ashbyhq.com/ramp/a85afad0-8051-4333-ae89-0f82321dea87";
    private static final String ASHBY_APPLY_URL = ASHBY_URL + "/application";

    private JdbcTemplate db;
    private AutomationPlanRepository plans;
    private AshbyAdapter ashbyAdapter;
    private ApplyDocumentSelector documentSelector;
    private AshbyExecutionPlanService service;

    private static final ApplyDocumentSelector.DocumentSelection NO_CV = new ApplyDocumentSelector.DocumentSelection(
            null, null, JobFormQuestionService.CoverLetterRequirement.UNKNOWN, List.of(), List.of());

    @BeforeEach
    void setUp() {
        db = mock(JdbcTemplate.class);
        plans = mock(AutomationPlanRepository.class);
        ashbyAdapter = new AshbyAdapter();
        documentSelector = mock(ApplyDocumentSelector.class);
        when(documentSelector.select(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(NO_CV);
        service = new AshbyExecutionPlanService(plans, ashbyAdapter, documentSelector, db, new ObjectMapper());
    }

    private void stubApplication(String applicationUrl) {
        when(db.queryForMap(contains("from applications a join jobs j"), eq(APPLICATION), eq(PROFILE), eq(JOB)))
                .thenReturn(Map.of("id", APPLICATION, "application_url", applicationUrl));
    }

    private void stubCandidate(String displayName, String email) {
        when(db.queryForMap(contains("from applications a join profiles p"), eq(APPLICATION), eq(PROFILE), eq(JOB)))
                .thenReturn(Map.of("display_name", displayName, "email", email));
    }

    private void stubCandidate(String displayName, String email, String phone, String linksJson) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("display_name", displayName);
        row.put("email", email);
        row.put("phone", phone);
        row.put("links", linksJson);
        when(db.queryForMap(contains("from applications a join profiles p"), eq(APPLICATION), eq(PROFILE), eq(JOB)))
                .thenReturn(row);
    }

    private void stubPlanLifecycle() {
        when(plans.create(eq(PROFILE), eq(APPLICATION), eq(JOB), eq(ASHBY_APPLY_URL),
                eq("ashby:" + APPLICATION), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(PLAN_ID);
        when(plans.findById(PLAN_ID)).thenReturn(java.util.Optional.of(new AutomationPlanRepository.PlanRow(
                PLAN_ID, APPLICATION, ASHBY_APPLY_URL, "PREPARED", Map.of(), java.util.List.of(), false, Instant.now(), Instant.now())));
        when(db.update(contains("update automation_plans set plan"), anyString(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION)))
                .thenReturn(1);
    }

    @Test
    void handlesDelegatesToTheAshbyAdapter() {
        assertThat(service.handles(ASHBY_URL)).isTrue();
        assertThat(service.handles("https://boards.greenhouse.io/acme/jobs/1")).isFalse();
    }

    @Test
    void skipsPlanCreationForANonAshbyUrl() {
        stubApplication("https://boards.greenhouse.io/acme/jobs/1");

        var result = service.createExecutionPlan(PROFILE, APPLICATION, JOB);

        assertThat(result).isEmpty();
        verifyNoInteractions(plans);
    }

    @Test
    void navigatesToTheApplicationSubRouteNeverTheBareJobPostingUrl() {
        stubApplication(ASHBY_URL);
        stubCandidate("Ada Lovelace", "ada@example.test");
        stubPlanLifecycle();

        service.createExecutionPlan(PROFILE, APPLICATION, JOB);

        verify(plans).create(eq(PROFILE), eq(APPLICATION), eq(JOB), eq(ASHBY_APPLY_URL),
                eq("ashby:" + APPLICATION), org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void neverDoublesTheApplicationSuffixWhenTheStoredUrlAlreadyHasIt() {
        stubApplication(ASHBY_APPLY_URL);
        stubCandidate("Ada Lovelace", "ada@example.test");
        stubPlanLifecycle();

        service.createExecutionPlan(PROFILE, APPLICATION, JOB);

        verify(plans).create(eq(PROFILE), eq(APPLICATION), eq(JOB), eq(ASHBY_APPLY_URL),
                eq("ashby:" + APPLICATION), org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void createsAnAshbyPlanWithTheCandidatesSingleUnsplitNameAndEmail() {
        stubApplication(ASHBY_URL);
        stubCandidate("Ada Lovelace", "ada@example.test");
        stubPlanLifecycle();

        var result = service.createExecutionPlan(PROFILE, APPLICATION, JOB);

        assertThat(result).contains(PLAN_ID);
        org.mockito.ArgumentCaptor<String> bodyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(db).update(contains("update automation_plans set plan"), bodyCaptor.capture(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION));
        String body = bodyCaptor.getValue();
        assertThat(body).contains("\"planType\":\"ASHBY\"");
        // Ashby's one stable name field is a SINGLE full-name control — never
        // split first/last, unlike every other adapter.
        assertThat(body).contains("\"name\":\"Ada Lovelace\"");
        assertThat(body).doesNotContain("\"firstName\"").doesNotContain("\"lastName\"");
        assertThat(body).contains("\"email\":\"ada@example.test\"");
        assertThat(body).contains("ASHBY_APPLY");
        // Never: a submission-capable step in a freshly-built Ashby plan —
        // only AutomationPlanRepository#prepareAshbySubmission can ever add one.
        assertThat(body).doesNotContain("ASHBY_SUBMIT_CLICK").doesNotContain("REAL_SUBMIT_CLICK").doesNotContain("MOCK_SUBMIT");
    }

    @Test
    void createsAnAshbyPlanWithTheCandidatesPhoneAndLinkedIn() {
        stubApplication(ASHBY_URL);
        stubCandidate("Ada Lovelace", "ada@example.test", "+44 7700 900123",
                "{\"linkedin\":\"https://linkedin.com/in/ada\",\"github\":\"https://github.com/ada\"}");
        stubPlanLifecycle();

        service.createExecutionPlan(PROFILE, APPLICATION, JOB);

        org.mockito.ArgumentCaptor<String> bodyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(db).update(contains("update automation_plans set plan"), bodyCaptor.capture(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION));
        String body = bodyCaptor.getValue();
        assertThat(body).contains("\"phone\":\"+44 7700 900123\"");
        assertThat(body).contains("\"linkedIn\":\"https://linkedin.com/in/ada\"");
        // github is a real profiles.links entry too, but Ashby's identity has
        // no github field — never pass through an unrequested key.
        assertThat(body).doesNotContain("github.com");
    }

    @Test
    void neverIncludesPhoneOrLinkedInWhenNeitherIsOnTheProfile() {
        stubApplication(ASHBY_URL);
        stubCandidate("Ada Lovelace", "ada@example.test");
        stubPlanLifecycle();

        service.createExecutionPlan(PROFILE, APPLICATION, JOB);

        org.mockito.ArgumentCaptor<String> bodyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(db).update(contains("update automation_plans set plan"), bodyCaptor.capture(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION));
        assertThat(bodyCaptor.getValue()).doesNotContain("\"phone\"").doesNotContain("\"linkedIn\"");
    }

    @Test
    void embedsTheSelectedResumeArtifactWhenAReviewedCvExists() {
        stubApplication(ASHBY_URL);
        stubCandidate("Ada Lovelace", "ada@example.test");
        UUID cvVersion = UUID.randomUUID();
        var selection = new ApplyDocumentSelector.DocumentSelection(
                new ApplyDocumentSelector.SelectedCv(cvVersion, "deadbeef", 1024L, "2026-01-01T00:00:00Z"),
                null, JobFormQuestionService.CoverLetterRequirement.UNKNOWN, List.of(), List.of());
        when(documentSelector.select(PROFILE, JOB, APPLICATION)).thenReturn(selection);
        stubPlanLifecycle();

        service.createExecutionPlan(PROFILE, APPLICATION, JOB);

        org.mockito.ArgumentCaptor<String> bodyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(db).update(contains("update automation_plans set plan"), bodyCaptor.capture(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION));
        String body = bodyCaptor.getValue();
        assertThat(body).contains("\"versionId\":\"" + cvVersion + "\"");
        assertThat(body).contains("\"sha256\":\"deadbeef\"");
        assertThat(body).contains("/artifacts/cv?versionId=" + cvVersion);
        assertThat(body).contains("\"artifacts\"");
    }

    @Test
    void neverEmbedsACoverLetterArtifactEvenWhenOneIsApproved() {
        // Ashby's one live-sampled posting has no cover-letter field at all
        // (see this class's own module comment) — unlike Zoho, this adapter
        // never selects or embeds one, regardless of what documentSelector
        // returns, since there is no matching field on the form to attach it to.
        stubApplication(ASHBY_URL);
        stubCandidate("Ada Lovelace", "ada@example.test");
        var selection = new ApplyDocumentSelector.DocumentSelection(
                null, new ApplyDocumentSelector.SelectedCoverLetter(UUID.randomUUID(), 1, "GENERATED", "beefcafe", null, false),
                JobFormQuestionService.CoverLetterRequirement.UNKNOWN, List.of(), List.of());
        when(documentSelector.select(PROFILE, JOB, APPLICATION)).thenReturn(selection);
        stubPlanLifecycle();

        service.createExecutionPlan(PROFILE, APPLICATION, JOB);

        org.mockito.ArgumentCaptor<String> bodyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(db).update(contains("update automation_plans set plan"), bodyCaptor.capture(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION));
        assertThat(bodyCaptor.getValue()).doesNotContain("\"coverLetter\"");
    }

    @Test
    void neverEmbedsAResumeArtifactWhenDocumentSelectionIsBlocked() {
        stubApplication(ASHBY_URL);
        stubCandidate("Ada Lovelace", "ada@example.test");
        var blocked = new ApplyDocumentSelector.DocumentSelection(
                null, null, JobFormQuestionService.CoverLetterRequirement.UNKNOWN, List.of(),
                List.of(Map.of("area", "CV", "code", "CV_NOT_REVIEWED", "message", "no reviewed CV")));
        when(documentSelector.select(PROFILE, JOB, APPLICATION)).thenReturn(blocked);
        stubPlanLifecycle();

        // A blocked/missing CV selection never prevents the plan itself: the
        // worker's own résumé-upload step degrades to an honest gap instead.
        var result = service.createExecutionPlan(PROFILE, APPLICATION, JOB);

        assertThat(result).contains(PLAN_ID);
        org.mockito.ArgumentCaptor<String> bodyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(db).update(contains("update automation_plans set plan"), bodyCaptor.capture(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION));
        assertThat(bodyCaptor.getValue()).doesNotContain("\"package\"");
    }

    @Test
    void anIdempotentReplayReturnsTheExistingPlanWithoutRewritingIt() {
        stubApplication(ASHBY_URL);
        when(plans.create(eq(PROFILE), eq(APPLICATION), eq(JOB), eq(ASHBY_APPLY_URL),
                eq("ashby:" + APPLICATION), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(PLAN_ID);
        when(plans.findById(PLAN_ID)).thenReturn(java.util.Optional.of(new AutomationPlanRepository.PlanRow(
                PLAN_ID, APPLICATION, ASHBY_APPLY_URL, "RUNNING", Map.of(), java.util.List.of(), false, Instant.now(), Instant.now())));

        var result = service.createExecutionPlan(PROFILE, APPLICATION, JOB);

        assertThat(result).contains(PLAN_ID);
        verify(db, never()).update(contains("update automation_plans set plan"), anyString(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION));
    }

    @Test
    void aFailedPlanIsRequeuedAndRebuiltOnTheNextReprepareAttempt() {
        stubApplication(ASHBY_URL);
        stubCandidate("Ada Lovelace", "ada@example.test");
        when(plans.create(eq(PROFILE), eq(APPLICATION), eq(JOB), eq(ASHBY_APPLY_URL),
                eq("ashby:" + APPLICATION), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(PLAN_ID);
        when(plans.findById(PLAN_ID)).thenReturn(java.util.Optional.of(new AutomationPlanRepository.PlanRow(
                PLAN_ID, APPLICATION, ASHBY_APPLY_URL, "FAILED", Map.of(), java.util.List.of(), false, Instant.now(), Instant.now())));
        when(plans.requeueFailedPlan(PLAN_ID)).thenReturn(true);
        when(db.update(contains("update automation_plans set plan"), anyString(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION)))
                .thenReturn(1);

        var result = service.createExecutionPlan(PROFILE, APPLICATION, JOB);

        assertThat(result).contains(PLAN_ID);
        verify(plans).requeueFailedPlan(PLAN_ID);
        // The plan is genuinely rebuilt — not just left as the dead FAILED row.
        verify(db).update(contains("update automation_plans set plan"), anyString(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION));
    }

    @Test
    void aBlockedAntiBotPlanIsRequeuedTheSameWayAFailedPlanIs() {
        stubApplication(ASHBY_URL);
        stubCandidate("Ada Lovelace", "ada@example.test");
        when(plans.create(eq(PROFILE), eq(APPLICATION), eq(JOB), eq(ASHBY_APPLY_URL),
                eq("ashby:" + APPLICATION), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(PLAN_ID);
        when(plans.findById(PLAN_ID)).thenReturn(java.util.Optional.of(new AutomationPlanRepository.PlanRow(
                PLAN_ID, APPLICATION, ASHBY_APPLY_URL, "BLOCKED_ANTI_BOT", Map.of(), java.util.List.of(), false, Instant.now(), Instant.now())));
        when(plans.requeueFailedPlan(PLAN_ID)).thenReturn(true);
        when(db.update(contains("update automation_plans set plan"), anyString(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION)))
                .thenReturn(1);

        var result = service.createExecutionPlan(PROFILE, APPLICATION, JOB);

        assertThat(result).contains(PLAN_ID);
        verify(plans).requeueFailedPlan(PLAN_ID);
    }

    @Test
    void aRequeueRaceThatLosesLeavesTheExistingPlanUntouched() {
        stubApplication(ASHBY_URL);
        when(plans.create(eq(PROFILE), eq(APPLICATION), eq(JOB), eq(ASHBY_APPLY_URL),
                eq("ashby:" + APPLICATION), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(PLAN_ID);
        when(plans.findById(PLAN_ID)).thenReturn(java.util.Optional.of(new AutomationPlanRepository.PlanRow(
                PLAN_ID, APPLICATION, ASHBY_APPLY_URL, "FAILED", Map.of(), java.util.List.of(), false, Instant.now(), Instant.now())));
        when(plans.requeueFailedPlan(PLAN_ID)).thenReturn(false);

        var result = service.createExecutionPlan(PROFILE, APPLICATION, JOB);

        assertThat(result).contains(PLAN_ID);
        verify(db, never()).update(contains("update automation_plans set plan"), anyString(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION));
    }

    @Test
    @SuppressWarnings("unchecked")
    void usesManagedEmailWhenConfiguredWithoutModifyingLoginEmail() {
        com.personal.jobagent.mailbox.RegistrationIdentityService mockIdentities =
                mock(com.personal.jobagent.mailbox.RegistrationIdentityService.class);
        when(mockIdentities.resolveApplicationContactEmail(eq(PROFILE), eq(ASHBY_APPLY_URL), eq("ada@example.test")))
                .thenReturn("ada.lovelace-ramp@mail.aplivenosi.com");

        org.springframework.beans.factory.ObjectProvider<com.personal.jobagent.mailbox.RegistrationIdentityService> provider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mockIdentities);

        AshbyExecutionPlanService serviceWithMail = new AshbyExecutionPlanService(
                plans, ashbyAdapter, documentSelector, db, new ObjectMapper(), provider);

        stubApplication(ASHBY_URL);
        stubCandidate("Ada Lovelace", "ada@example.test", "+44 7700 900123", "{}");
        stubPlanLifecycle();

        serviceWithMail.createExecutionPlan(PROFILE, APPLICATION, JOB);

        org.mockito.ArgumentCaptor<String> planCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(db).update(contains("update automation_plans set plan"), planCaptor.capture(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION));
        assertThat(planCaptor.getValue()).contains("ada.lovelace-ramp@mail.aplivenosi.com");
        assertThat(planCaptor.getValue()).doesNotContain("ada@example.test");
    }
}
