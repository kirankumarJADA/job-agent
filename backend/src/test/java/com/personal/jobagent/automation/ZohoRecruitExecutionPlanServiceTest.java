package com.personal.jobagent.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.ats.AtsAdapters;
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
 * Robin x Tsenta continuation: focused unit coverage for the Zoho Recruit
 * plan builder. Mocked JdbcTemplate/AutomationPlanRepository (no real DB) —
 * mirrors how {@code InspectionPlanBridgeTest} covers InspectionPlanService
 * without Testcontainers. The worker-side behaviour this plan feeds into
 * (live field classification, CAPTCHA/consent gating, never-submit) is
 * covered separately and much more thoroughly in
 * {@code worker/test/zoho_recruit.test.js}; this test only proves the plan
 * this service hands to the worker is correctly shaped.
 */
class ZohoRecruitExecutionPlanServiceTest {

    private static final UUID PROFILE = UUID.randomUUID();
    private static final UUID APPLICATION = UUID.randomUUID();
    private static final UUID JOB = UUID.randomUUID();
    private static final UUID PLAN_ID = UUID.randomUUID();
    private static final String ZOHO_URL = "https://acme.zohorecruit.com/jobs/Careers/123/Java-Engineer";

    private JdbcTemplate db;
    private AutomationPlanRepository plans;
    private AtsAdapters.ZohoAdapter zohoAdapter;
    private ApplyDocumentSelector documentSelector;
    private ZohoRecruitExecutionPlanService service;

    private static final ApplyDocumentSelector.DocumentSelection NO_CV = new ApplyDocumentSelector.DocumentSelection(
            null, null, JobFormQuestionService.CoverLetterRequirement.UNKNOWN, List.of(), List.of());

    @BeforeEach
    void setUp() {
        db = mock(JdbcTemplate.class);
        plans = mock(AutomationPlanRepository.class);
        zohoAdapter = new AtsAdapters.ZohoAdapter();
        documentSelector = mock(ApplyDocumentSelector.class);
        when(documentSelector.select(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(NO_CV);
        service = new ZohoRecruitExecutionPlanService(plans, zohoAdapter, documentSelector, db, new ObjectMapper());
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

    @Test
    void handlesDelegatesToTheZohoAdapter() {
        assertThat(service.handles(ZOHO_URL)).isTrue();
        assertThat(service.handles("https://boards.greenhouse.io/acme/jobs/1")).isFalse();
    }

    @Test
    void skipsPlanCreationForANonZohoRecruitUrl() {
        stubApplication("https://boards.greenhouse.io/acme/jobs/1");

        var result = service.createExecutionPlan(PROFILE, APPLICATION, JOB);

        assertThat(result).isEmpty();
        verifyNoInteractions(plans);
    }

    @Test
    void createsAZohoRecruitPlanWithTheCandidatesSplitNameAndEmail() {
        stubApplication(ZOHO_URL);
        stubCandidate("Ada Lovelace", "ada@example.test");
        when(plans.create(eq(PROFILE), eq(APPLICATION), eq(JOB), eq(ZOHO_URL),
                eq("zoho-recruit:" + APPLICATION), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(PLAN_ID);
        when(plans.findById(PLAN_ID)).thenReturn(java.util.Optional.of(new AutomationPlanRepository.PlanRow(
                PLAN_ID, APPLICATION, ZOHO_URL, "PREPARED", Map.of(), java.util.List.of(), false, Instant.now(), Instant.now())));
        when(db.update(contains("update automation_plans set plan"), anyString(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION)))
                .thenReturn(1);

        var result = service.createExecutionPlan(PROFILE, APPLICATION, JOB);

        assertThat(result).contains(PLAN_ID);
        org.mockito.ArgumentCaptor<String> bodyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(db).update(contains("update automation_plans set plan"), bodyCaptor.capture(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION));
        String body = bodyCaptor.getValue();
        assertThat(body).contains("\"planType\":\"ZOHO_RECRUIT\"");
        assertThat(body).contains("\"firstName\":\"Ada\"");
        assertThat(body).contains("\"lastName\":\"Lovelace\"");
        assertThat(body).contains("\"email\":\"ada@example.test\"");
        assertThat(body).contains("ZOHO_RECRUIT_APPLY");
        // Never: a REAL_SUBMIT or MOCK_SUBMIT capability in a Zoho Recruit plan.
        assertThat(body).doesNotContain("REAL_SUBMIT_CLICK").doesNotContain("MOCK_SUBMIT");
    }

    @Test
    void createsAZohoRecruitPlanWithTheCandidatesPhoneAndLinkedInPhase13() {
        stubApplication(ZOHO_URL);
        stubCandidate("Ada Lovelace", "ada@example.test", "+44 7700 900123",
                "{\"linkedin\":\"https://linkedin.com/in/ada\",\"github\":\"https://github.com/ada\"}");
        when(plans.create(eq(PROFILE), eq(APPLICATION), eq(JOB), eq(ZOHO_URL),
                eq("zoho-recruit:" + APPLICATION), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(PLAN_ID);
        when(plans.findById(PLAN_ID)).thenReturn(java.util.Optional.of(new AutomationPlanRepository.PlanRow(
                PLAN_ID, APPLICATION, ZOHO_URL, "PREPARED", Map.of(), java.util.List.of(), false, Instant.now(), Instant.now())));
        when(db.update(contains("update automation_plans set plan"), anyString(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION)))
                .thenReturn(1);

        service.createExecutionPlan(PROFILE, APPLICATION, JOB);

        org.mockito.ArgumentCaptor<String> bodyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(db).update(contains("update automation_plans set plan"), bodyCaptor.capture(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION));
        String body = bodyCaptor.getValue();
        assertThat(body).contains("\"phone\":\"+44 7700 900123\"");
        assertThat(body).contains("\"linkedIn\":\"https://linkedin.com/in/ada\"");
        // github is a real profiles.links entry too, but Zoho's identity has
        // no github field — never pass through an unrequested key.
        assertThat(body).doesNotContain("github.com");
    }

    @Test
    void neverIncludesPhoneOrLinkedInWhenNeitherIsOnTheProfile() {
        stubApplication(ZOHO_URL);
        stubCandidate("Ada Lovelace", "ada@example.test");
        when(plans.create(eq(PROFILE), eq(APPLICATION), eq(JOB), eq(ZOHO_URL),
                eq("zoho-recruit:" + APPLICATION), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(PLAN_ID);
        when(plans.findById(PLAN_ID)).thenReturn(java.util.Optional.of(new AutomationPlanRepository.PlanRow(
                PLAN_ID, APPLICATION, ZOHO_URL, "PREPARED", Map.of(), java.util.List.of(), false, Instant.now(), Instant.now())));
        when(db.update(contains("update automation_plans set plan"), anyString(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION)))
                .thenReturn(1);

        service.createExecutionPlan(PROFILE, APPLICATION, JOB);

        org.mockito.ArgumentCaptor<String> bodyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(db).update(contains("update automation_plans set plan"), bodyCaptor.capture(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION));
        assertThat(bodyCaptor.getValue()).doesNotContain("\"phone\"").doesNotContain("\"linkedIn\"");
    }

    @Test
    void neverGuessesAFirstLastNameSplitThatIsNotExactlyTwoTokens() {
        stubApplication(ZOHO_URL);
        stubCandidate("Ada Katherine Lovelace", "ada@example.test");
        when(plans.create(eq(PROFILE), eq(APPLICATION), eq(JOB), eq(ZOHO_URL),
                eq("zoho-recruit:" + APPLICATION), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(PLAN_ID);
        when(plans.findById(PLAN_ID)).thenReturn(java.util.Optional.of(new AutomationPlanRepository.PlanRow(
                PLAN_ID, APPLICATION, ZOHO_URL, "PREPARED", Map.of(), java.util.List.of(), false, Instant.now(), Instant.now())));
        when(db.update(contains("update automation_plans set plan"), anyString(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION)))
                .thenReturn(1);

        service.createExecutionPlan(PROFILE, APPLICATION, JOB);

        org.mockito.ArgumentCaptor<String> bodyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(db).update(contains("update automation_plans set plan"), bodyCaptor.capture(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION));
        assertThat(bodyCaptor.getValue()).doesNotContain("\"firstName\"").doesNotContain("\"lastName\"");
        assertThat(bodyCaptor.getValue()).contains("\"email\":\"ada@example.test\"");
    }

    @Test
    void embedsTheSelectedResumeArtifactWhenAReviewedCvExists() {
        stubApplication(ZOHO_URL);
        stubCandidate("Ada Lovelace", "ada@example.test");
        UUID cvVersion = UUID.randomUUID();
        var selection = new ApplyDocumentSelector.DocumentSelection(
                new ApplyDocumentSelector.SelectedCv(cvVersion, "deadbeef", 1024L, "2026-01-01T00:00:00Z"),
                null, JobFormQuestionService.CoverLetterRequirement.UNKNOWN, List.of(), List.of());
        when(documentSelector.select(PROFILE, JOB, APPLICATION)).thenReturn(selection);
        when(plans.create(eq(PROFILE), eq(APPLICATION), eq(JOB), eq(ZOHO_URL),
                eq("zoho-recruit:" + APPLICATION), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(PLAN_ID);
        when(plans.findById(PLAN_ID)).thenReturn(java.util.Optional.of(new AutomationPlanRepository.PlanRow(
                PLAN_ID, APPLICATION, ZOHO_URL, "PREPARED", Map.of(), java.util.List.of(), false, Instant.now(), Instant.now())));
        when(db.update(contains("update automation_plans set plan"), anyString(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION)))
                .thenReturn(1);

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
    void neverEmbedsAResumeArtifactWhenDocumentSelectionIsBlocked() {
        stubApplication(ZOHO_URL);
        stubCandidate("Ada Lovelace", "ada@example.test");
        var blocked = new ApplyDocumentSelector.DocumentSelection(
                null, null, JobFormQuestionService.CoverLetterRequirement.UNKNOWN, List.of(),
                List.of(Map.of("area", "CV", "code", "CV_NOT_REVIEWED", "message", "no reviewed CV")));
        when(documentSelector.select(PROFILE, JOB, APPLICATION)).thenReturn(blocked);
        when(plans.create(eq(PROFILE), eq(APPLICATION), eq(JOB), eq(ZOHO_URL),
                eq("zoho-recruit:" + APPLICATION), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(PLAN_ID);
        when(plans.findById(PLAN_ID)).thenReturn(java.util.Optional.of(new AutomationPlanRepository.PlanRow(
                PLAN_ID, APPLICATION, ZOHO_URL, "PREPARED", Map.of(), java.util.List.of(), false, Instant.now(), Instant.now())));
        when(db.update(contains("update automation_plans set plan"), anyString(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION)))
                .thenReturn(1);

        // A blocked/missing CV selection never prevents the plan itself: the
        // worker's own résumé-upload step degrades to an honest gap instead.
        var result = service.createExecutionPlan(PROFILE, APPLICATION, JOB);

        assertThat(result).contains(PLAN_ID);
        org.mockito.ArgumentCaptor<String> bodyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(db).update(contains("update automation_plans set plan"), bodyCaptor.capture(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION));
        assertThat(bodyCaptor.getValue()).doesNotContain("\"package\"");
    }

    @Test
    void embedsTheSelectedCoverLetterArtifactWhenAnApprovedOneExists() {
        stubApplication(ZOHO_URL);
        stubCandidate("Ada Lovelace", "ada@example.test");
        UUID letterVersion = UUID.randomUUID();
        var selection = new ApplyDocumentSelector.DocumentSelection(
                null, new ApplyDocumentSelector.SelectedCoverLetter(letterVersion, 1, "GENERATED", "beefcafe", null, false),
                JobFormQuestionService.CoverLetterRequirement.UNKNOWN, List.of(), List.of());
        when(documentSelector.select(PROFILE, JOB, APPLICATION)).thenReturn(selection);
        when(plans.create(eq(PROFILE), eq(APPLICATION), eq(JOB), eq(ZOHO_URL),
                eq("zoho-recruit:" + APPLICATION), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(PLAN_ID);
        when(plans.findById(PLAN_ID)).thenReturn(java.util.Optional.of(new AutomationPlanRepository.PlanRow(
                PLAN_ID, APPLICATION, ZOHO_URL, "PREPARED", Map.of(), java.util.List.of(), false, Instant.now(), Instant.now())));
        when(db.update(contains("update automation_plans set plan"), anyString(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION)))
                .thenReturn(1);

        service.createExecutionPlan(PROFILE, APPLICATION, JOB);

        org.mockito.ArgumentCaptor<String> bodyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(db).update(contains("update automation_plans set plan"), bodyCaptor.capture(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION));
        String body = bodyCaptor.getValue();
        assertThat(body).contains("\"versionId\":\"" + letterVersion + "\"");
        assertThat(body).contains("\"sha256\":\"beefcafe\"");
        assertThat(body).contains("/artifacts/cover-letter?versionId=" + letterVersion);
        assertThat(body).contains("\"coverLetter\"");
        // The exact, already-reviewed Greenhouse artifact convention is reused
        // unchanged: plain-text body markdown, never an invented PDF rendition.
        assertThat(body).contains("cover-letter-" + letterVersion + ".txt");
    }

    @Test
    void neverEmbedsACoverLetterArtifactWhenNoneIsApproved() {
        stubApplication(ZOHO_URL);
        stubCandidate("Ada Lovelace", "ada@example.test");
        // NO_CV default stub already has coverLetter() == null.
        when(plans.create(eq(PROFILE), eq(APPLICATION), eq(JOB), eq(ZOHO_URL),
                eq("zoho-recruit:" + APPLICATION), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(PLAN_ID);
        when(plans.findById(PLAN_ID)).thenReturn(java.util.Optional.of(new AutomationPlanRepository.PlanRow(
                PLAN_ID, APPLICATION, ZOHO_URL, "PREPARED", Map.of(), java.util.List.of(), false, Instant.now(), Instant.now())));
        when(db.update(contains("update automation_plans set plan"), anyString(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION)))
                .thenReturn(1);

        service.createExecutionPlan(PROFILE, APPLICATION, JOB);

        org.mockito.ArgumentCaptor<String> bodyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(db).update(contains("update automation_plans set plan"), bodyCaptor.capture(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION));
        assertThat(bodyCaptor.getValue()).doesNotContain("\"coverLetter\"").doesNotContain("\"package\"");
    }

    @Test
    void embedsBothArtifactsIndependentlyWhenBothAreApproved() {
        stubApplication(ZOHO_URL);
        stubCandidate("Ada Lovelace", "ada@example.test");
        UUID cvVersion = UUID.randomUUID();
        UUID letterVersion = UUID.randomUUID();
        var selection = new ApplyDocumentSelector.DocumentSelection(
                new ApplyDocumentSelector.SelectedCv(cvVersion, "deadbeef", 1024L, "2026-01-01T00:00:00Z"),
                new ApplyDocumentSelector.SelectedCoverLetter(letterVersion, 1, "GENERATED", "beefcafe", null, false),
                JobFormQuestionService.CoverLetterRequirement.UNKNOWN, List.of(), List.of());
        when(documentSelector.select(PROFILE, JOB, APPLICATION)).thenReturn(selection);
        when(plans.create(eq(PROFILE), eq(APPLICATION), eq(JOB), eq(ZOHO_URL),
                eq("zoho-recruit:" + APPLICATION), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(PLAN_ID);
        when(plans.findById(PLAN_ID)).thenReturn(java.util.Optional.of(new AutomationPlanRepository.PlanRow(
                PLAN_ID, APPLICATION, ZOHO_URL, "PREPARED", Map.of(), java.util.List.of(), false, Instant.now(), Instant.now())));
        when(db.update(contains("update automation_plans set plan"), anyString(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION)))
                .thenReturn(1);

        service.createExecutionPlan(PROFILE, APPLICATION, JOB);

        org.mockito.ArgumentCaptor<String> bodyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(db).update(contains("update automation_plans set plan"), bodyCaptor.capture(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION));
        String body = bodyCaptor.getValue();
        assertThat(body).contains("\"cv\"").contains("\"coverLetter\"");
        assertThat(body).contains("\"versionId\":\"" + cvVersion + "\"");
        assertThat(body).contains("\"versionId\":\"" + letterVersion + "\"");
    }

    @Test
    void anIdempotentReplayReturnsTheExistingPlanWithoutRewritingIt() {
        stubApplication(ZOHO_URL);
        when(plans.create(eq(PROFILE), eq(APPLICATION), eq(JOB), eq(ZOHO_URL),
                eq("zoho-recruit:" + APPLICATION), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(PLAN_ID);
        when(plans.findById(PLAN_ID)).thenReturn(java.util.Optional.of(new AutomationPlanRepository.PlanRow(
                PLAN_ID, APPLICATION, ZOHO_URL, "RUNNING", Map.of(), java.util.List.of(), false, Instant.now(), Instant.now())));

        var result = service.createExecutionPlan(PROFILE, APPLICATION, JOB);

        assertThat(result).contains(PLAN_ID);
        verify(db, never()).update(contains("update automation_plans set plan"), anyString(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION));
    }

    @Test
    void aFailedPlanIsRequeuedAndRebuiltOnTheNextReprepareAttempt() {
        stubApplication(ZOHO_URL);
        stubCandidate("Ada Lovelace", "ada@example.test");
        when(plans.create(eq(PROFILE), eq(APPLICATION), eq(JOB), eq(ZOHO_URL),
                eq("zoho-recruit:" + APPLICATION), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(PLAN_ID);
        when(plans.findById(PLAN_ID)).thenReturn(java.util.Optional.of(new AutomationPlanRepository.PlanRow(
                PLAN_ID, APPLICATION, ZOHO_URL, "FAILED", Map.of(), java.util.List.of(), false, Instant.now(), Instant.now())));
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
        stubApplication(ZOHO_URL);
        stubCandidate("Ada Lovelace", "ada@example.test");
        when(plans.create(eq(PROFILE), eq(APPLICATION), eq(JOB), eq(ZOHO_URL),
                eq("zoho-recruit:" + APPLICATION), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(PLAN_ID);
        when(plans.findById(PLAN_ID)).thenReturn(java.util.Optional.of(new AutomationPlanRepository.PlanRow(
                PLAN_ID, APPLICATION, ZOHO_URL, "BLOCKED_ANTI_BOT", Map.of(), java.util.List.of(), false, Instant.now(), Instant.now())));
        when(plans.requeueFailedPlan(PLAN_ID)).thenReturn(true);
        when(db.update(contains("update automation_plans set plan"), anyString(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION)))
                .thenReturn(1);

        var result = service.createExecutionPlan(PROFILE, APPLICATION, JOB);

        assertThat(result).contains(PLAN_ID);
        verify(plans).requeueFailedPlan(PLAN_ID);
    }

    @Test
    void aRequeueRaceThatLosesLeavesTheExistingPlanUntouched() {
        stubApplication(ZOHO_URL);
        when(plans.create(eq(PROFILE), eq(APPLICATION), eq(JOB), eq(ZOHO_URL),
                eq("zoho-recruit:" + APPLICATION), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(PLAN_ID);
        when(plans.findById(PLAN_ID)).thenReturn(java.util.Optional.of(new AutomationPlanRepository.PlanRow(
                PLAN_ID, APPLICATION, ZOHO_URL, "FAILED", Map.of(), java.util.List.of(), false, Instant.now(), Instant.now())));
        // Another concurrent caller (e.g. a worker that just claimed it) won
        // the race — requeueFailedPlan's own atomic WHERE no longer matches.
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
        when(mockIdentities.resolveApplicationContactEmail(eq(PROFILE), eq(ZOHO_URL), eq("ada@example.test")))
                .thenReturn("ada.lovelace-fulcrum@mail.aplivenosi.com");

        org.springframework.beans.factory.ObjectProvider<com.personal.jobagent.mailbox.RegistrationIdentityService> provider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mockIdentities);

        ZohoRecruitExecutionPlanService serviceWithMail = new ZohoRecruitExecutionPlanService(
                plans, zohoAdapter, documentSelector, db, new ObjectMapper(), provider);

        stubApplication(ZOHO_URL);
        stubCandidate("Ada Lovelace", "ada@example.test", "+44 7700 900123", "{}");
        when(plans.create(eq(PROFILE), eq(APPLICATION), eq(JOB), eq(ZOHO_URL),
                eq("zoho-recruit:" + APPLICATION), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(PLAN_ID);
        when(plans.findById(PLAN_ID)).thenReturn(java.util.Optional.of(new AutomationPlanRepository.PlanRow(
                PLAN_ID, APPLICATION, ZOHO_URL, "PREPARED", Map.of(), java.util.List.of(), false, Instant.now(), Instant.now())));
        when(db.update(contains("update automation_plans set plan"), anyString(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION)))
                .thenReturn(1);

        serviceWithMail.createExecutionPlan(PROFILE, APPLICATION, JOB);

        org.mockito.ArgumentCaptor<String> planCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(db).update(contains("update automation_plans set plan"), planCaptor.capture(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION));
        assertThat(planCaptor.getValue()).contains("ada.lovelace-fulcrum@mail.aplivenosi.com");
        assertThat(planCaptor.getValue()).doesNotContain("ada@example.test");
    }
}
