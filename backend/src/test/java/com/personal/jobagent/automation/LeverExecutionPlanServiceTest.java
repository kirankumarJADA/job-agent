package com.personal.jobagent.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.ats.AtsAdapters;
import com.personal.jobagent.ats.JobFormQuestionService;
import com.personal.jobagent.coverletter.CoverLetterRecord;
import com.personal.jobagent.coverletter.CoverLetterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 6: focused unit coverage for the Lever plan builder. Mocked
 * JdbcTemplate/AutomationPlanRepository/CoverLetterRepository (no real DB) —
 * mirrors {@code ZohoRecruitExecutionPlanServiceTest}. The worker-side
 * execution this plan feeds into (field fill, CAPTCHA/login-wall gating,
 * résumé upload, never-submit) is covered separately and much more
 * thoroughly in {@code worker/test/lever.test.js}; this test only proves the
 * plan this service hands to the worker is correctly shaped.
 */
class LeverExecutionPlanServiceTest {

    private static final UUID PROFILE = UUID.randomUUID();
    private static final UUID APPLICATION = UUID.randomUUID();
    private static final UUID JOB = UUID.randomUUID();
    private static final UUID PLAN_ID = UUID.randomUUID();
    private static final String LEVER_URL = "https://jobs.lever.co/acme/6ed76ce8-4156-4b60-b120-403538bd66cd/apply";

    private JdbcTemplate db;
    private AutomationPlanRepository plans;
    private AtsAdapters.LeverAdapter leverAdapter;
    private ApplyDocumentSelector documentSelector;
    private CoverLetterRepository letters;
    private LeverExecutionPlanService service;

    private static final ApplyDocumentSelector.DocumentSelection NO_DOCS = new ApplyDocumentSelector.DocumentSelection(
            null, null, JobFormQuestionService.CoverLetterRequirement.UNKNOWN, List.of(), List.of());

    private static final String FORM_HTML = """
            <form id="application-form"><input name="name" required><input name="email" type="email" required>
            <input name="phone"><input name="org"><input type="file" name="resume">
            <textarea name="comments"></textarea></form>
            """;

    @BeforeEach
    void setUp() {
        db = mock(JdbcTemplate.class);
        plans = mock(AutomationPlanRepository.class);
        leverAdapter = new AtsAdapters.LeverAdapter(url -> FORM_HTML);
        documentSelector = mock(ApplyDocumentSelector.class);
        letters = mock(CoverLetterRepository.class);
        when(documentSelector.select(any(), any(), any())).thenReturn(NO_DOCS);
        service = new LeverExecutionPlanService(plans, leverAdapter, documentSelector, letters, db, new ObjectMapper());
    }

    private void stubApplication(String applicationUrl) {
        when(db.queryForMap(contains("from applications a join jobs j"), eq(APPLICATION), eq(PROFILE), eq(JOB)))
                .thenReturn(Map.of("id", APPLICATION, "application_url", applicationUrl));
    }

    private void stubCandidate(String displayName, String email, String phone, String location) {
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("display_name", displayName);
        row.put("email", email);
        row.put("phone", phone);
        row.put("location", location);
        row.put("links", "{}");
        when(db.queryForMap(contains("from applications a join profiles p"), eq(APPLICATION), eq(PROFILE), eq(JOB)))
                .thenReturn(row);
    }

    private void stubNoAnswers() {
        when(db.query(contains("from application_answers"), org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<LeverFieldMapper.Answer>>any(),
                eq(APPLICATION), eq(PROFILE), eq(JOB))).thenReturn(List.of());
    }

    @Test
    void handlesDelegatesToTheLeverAdapter() {
        assertThat(service.handles(LEVER_URL)).isTrue();
        assertThat(service.handles("https://boards.greenhouse.io/acme/jobs/1")).isFalse();
    }

    @Test
    void skipsPlanCreationForANonLeverUrl() {
        stubApplication("https://boards.greenhouse.io/acme/jobs/1");

        var result = service.createExecutionPlan(PROFILE, APPLICATION, JOB);

        assertThat(result).isEmpty();
        verifyNoInteractions(plans);
    }

    @Test
    void createsALeverPlanWithBackendComputedFieldsAndNeverASubmitStep() {
        stubApplication(LEVER_URL);
        stubCandidate("Ada Lovelace", "ada@example.test", "+44 20 7946 0000", "London, UK");
        stubNoAnswers();
        when(plans.create(eq(PROFILE), eq(APPLICATION), eq(JOB), eq(LEVER_URL),
                eq("lever:" + APPLICATION), anyList())).thenReturn(PLAN_ID);
        when(plans.findById(PLAN_ID)).thenReturn(java.util.Optional.of(new AutomationPlanRepository.PlanRow(
                PLAN_ID, APPLICATION, LEVER_URL, "PREPARED", Map.of(), List.of(), false, Instant.now(), Instant.now())));
        when(db.update(contains("update automation_plans set plan"), anyString(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION)))
                .thenReturn(1);

        var result = service.createExecutionPlan(PROFILE, APPLICATION, JOB);

        assertThat(result).contains(PLAN_ID);
        var bodyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(db).update(contains("update automation_plans set plan"), bodyCaptor.capture(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION));
        String body = bodyCaptor.getValue();
        assertThat(body).contains("\"planType\":\"LEVER\"");
        assertThat(body).contains("LEVER_APPLY");
        assertThat(body).contains("\"ada@example.test\"");
        assertThat(body).contains("\"Ada Lovelace\"");
        // Never: a REAL_SUBMIT or MOCK_SUBMIT capability in a Lever plan.
        assertThat(body).doesNotContain("REAL_SUBMIT_CLICK").doesNotContain("MOCK_SUBMIT");
    }

    @Test
    void aFailedPlanIsRequeuedAndRebuiltOnTheCandidatesExplicitRetry() {
        stubApplication(LEVER_URL);
        stubCandidate("Ada Lovelace", "ada@example.test", null, null);
        stubNoAnswers();
        when(plans.create(any(), any(), any(), any(), any(), anyList())).thenReturn(PLAN_ID);
        when(plans.findById(PLAN_ID)).thenReturn(java.util.Optional.of(new AutomationPlanRepository.PlanRow(
                PLAN_ID, APPLICATION, LEVER_URL, "FAILED", Map.of(), List.of(), false, Instant.now(), Instant.now())));
        when(plans.requeueFailedPlan(PLAN_ID)).thenReturn(true);
        when(db.update(contains("update automation_plans set plan"), anyString(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION)))
                .thenReturn(1);

        var result = service.createExecutionPlan(PROFILE, APPLICATION, JOB);

        assertThat(result).contains(PLAN_ID);
        verify(plans).requeueFailedPlan(PLAN_ID);
    }

    @Test
    void aPlanNotInPreparedStateIsLeftAloneAndNotRebuilt() {
        stubApplication(LEVER_URL);
        when(plans.create(any(), any(), any(), any(), any(), anyList())).thenReturn(PLAN_ID);
        when(plans.findById(PLAN_ID)).thenReturn(java.util.Optional.of(new AutomationPlanRepository.PlanRow(
                PLAN_ID, APPLICATION, LEVER_URL, "RUNNING", Map.of(), List.of(), false, Instant.now(), Instant.now())));

        var result = service.createExecutionPlan(PROFILE, APPLICATION, JOB);

        assertThat(result).contains(PLAN_ID);
        verify(db, never()).update(contains("update automation_plans set plan"), anyString(), any(), any(), any());
    }

    @Test
    void theApprovedCoverLetterBodyIsFetchedAndFilledIntoTheCommentsField() {
        stubApplication(LEVER_URL);
        stubCandidate("Ada Lovelace", "ada@example.test", null, null);
        stubNoAnswers();
        UUID cvVersion = UUID.randomUUID();
        UUID letterVersion = UUID.randomUUID();
        var selection = new ApplyDocumentSelector.DocumentSelection(
                new ApplyDocumentSelector.SelectedCv(cvVersion, "cvsha", 1024, null),
                new ApplyDocumentSelector.SelectedCoverLetter(letterVersion, 1, "GENERATED", "lettersha", null, false),
                JobFormQuestionService.CoverLetterRequirement.UNKNOWN, List.of(), List.of());
        when(documentSelector.select(PROFILE, JOB, APPLICATION)).thenReturn(selection);
        when(letters.findByIdForProfile(letterVersion, PROFILE)).thenReturn(java.util.Optional.of(
                new CoverLetterRecord(letterVersion, PROFILE, JOB, APPLICATION, 1, "Cover letter",
                        "Dear hiring team, I am excited to apply.", Map.of(), true, Instant.now(), Instant.now())));
        when(plans.create(any(), any(), any(), any(), any(), anyList())).thenReturn(PLAN_ID);
        when(plans.findById(PLAN_ID)).thenReturn(java.util.Optional.of(new AutomationPlanRepository.PlanRow(
                PLAN_ID, APPLICATION, LEVER_URL, "PREPARED", Map.of(), List.of(), false, Instant.now(), Instant.now())));
        when(db.update(contains("update automation_plans set plan"), anyString(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION)))
                .thenReturn(1);

        service.createExecutionPlan(PROFILE, APPLICATION, JOB);

        var bodyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(db).update(contains("update automation_plans set plan"), bodyCaptor.capture(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION));
        assertThat(bodyCaptor.getValue()).contains("Dear hiring team, I am excited to apply.");
    }

    @Test
    @SuppressWarnings("unchecked")
    void usesManagedEmailWhenConfiguredWithoutModifyingLoginEmail() {
        com.personal.jobagent.mailbox.RegistrationIdentityService mockIdentities =
                mock(com.personal.jobagent.mailbox.RegistrationIdentityService.class);
        when(mockIdentities.resolveApplicationContactEmail(eq(PROFILE), eq(LEVER_URL), eq("ada@example.test")))
                .thenReturn("ada.lovelace-acme@mail.aplivenosi.com");

        org.springframework.beans.factory.ObjectProvider<com.personal.jobagent.mailbox.RegistrationIdentityService> provider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mockIdentities);

        LeverExecutionPlanService serviceWithMail = new LeverExecutionPlanService(
                plans, leverAdapter, documentSelector, letters, db, new ObjectMapper(), provider);

        stubApplication(LEVER_URL);
        stubCandidate("Ada Lovelace", "ada@example.test", "+44 7700 900123", "London");
        stubNoAnswers();
        when(plans.create(any(), any(), any(), any(), any(), anyList())).thenReturn(PLAN_ID);
        when(plans.findById(PLAN_ID)).thenReturn(java.util.Optional.of(new AutomationPlanRepository.PlanRow(
                PLAN_ID, APPLICATION, LEVER_URL, "PREPARED", Map.of(), List.of(), false, Instant.now(), Instant.now())));
        when(db.update(contains("update automation_plans set plan"), anyString(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION)))
                .thenReturn(1);

        serviceWithMail.createExecutionPlan(PROFILE, APPLICATION, JOB);

        var bodyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(db).update(contains("update automation_plans set plan"), bodyCaptor.capture(), eq(PLAN_ID), eq(PROFILE), eq(APPLICATION));
        assertThat(bodyCaptor.getValue()).contains("ada.lovelace-acme@mail.aplivenosi.com");
        assertThat(bodyCaptor.getValue()).doesNotContain("ada@example.test");
    }
}
