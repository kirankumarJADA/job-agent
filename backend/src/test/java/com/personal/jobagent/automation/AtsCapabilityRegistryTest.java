package com.personal.jobagent.automation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 10: the honest ATS capability model is the single source of truth
 * every other part of this phase (the shared workflow, the frontend capability
 * labels) is required to agree with — these tests pin down the exact,
 * verified facts {@code AtsCapabilityRegistry}'s own class comment documents,
 * so a future change to any adapter's real capability must consciously update
 * this registry (and this test) rather than silently drifting from it.
 */
class AtsCapabilityRegistryTest {

    @Test
    void onlyGreenhouseAshbyAndWorkdayAreEverCreditedWithRealSubmission() {
        // Phase 14 added Ashby and Phase 19 added Workday as platforms with a genuine,
        // guarded final-submission mechanism — every other platform must still never
        // be credited with one.
        for (AtsCapabilities row : AtsCapabilityRegistry.all()) {
            boolean submits = row.finalSubmission() == AtsCapabilityLevel.FULL;
            boolean expected = "GREENHOUSE".equals(row.kind()) || "ASHBY".equals(row.kind()) || "WORKDAY".equals(row.kind());
            assertThat(submits).as(row.kind() + " finalSubmission").isEqualTo(expected);
        }
    }

    @Test
    void noRowIsEverCreditedWithSubmissionVerifiedNoLiveEmployerSubmissionHasEverHappened() {
        for (AtsCapabilities row : AtsCapabilityRegistry.all()) {
            assertThat(row.summary()).as(row.kind()).isNotEqualTo(AtsCapabilitySummary.SUBMISSION_VERIFIED);
        }
    }

    @Test
    void greenhouseSummarisesAsSubmissionAvailable() {
        assertThat(AtsCapabilityRegistry.byPlanType("GREENHOUSE").summary()).isEqualTo(AtsCapabilitySummary.SUBMISSION_AVAILABLE);
    }

    @Test
    void leverAndZohoRecruitSummariseAsFormFillAvailableNeverAsSubmissionCapable() {
        var lever = AtsCapabilityRegistry.byPlanType("LEVER");
        var zoho = AtsCapabilityRegistry.byPlanType("ZOHO_RECRUIT");
        assertThat(lever.summary()).isEqualTo(AtsCapabilitySummary.FORM_FILL_AVAILABLE);
        assertThat(zoho.summary()).isEqualTo(AtsCapabilitySummary.FORM_FILL_AVAILABLE);
        assertThat(lever.finalSubmission()).isEqualTo(AtsCapabilityLevel.NONE);
        assertThat(zoho.finalSubmission()).isEqualTo(AtsCapabilityLevel.NONE);
    }

    @Test
    void ashbySummarisesAsFormFillAvailableNotSubmissionAvailableUntilConfirmationIsLiveVerified() {
        var ashby = AtsCapabilityRegistry.byPlanType("ASHBY");
        // finalSubmission is genuinely FULL (the mechanism exists and is
        // approval-gated), but submissionConfirmation stays PARTIAL because
        // the live applicationForm.submit response contract was documented
        // and inferred, never directly observed — so the top-line summary
        // must stay at FORM_FILL_AVAILABLE, not SUBMISSION_AVAILABLE.
        assertThat(ashby.finalSubmission()).isEqualTo(AtsCapabilityLevel.FULL);
        assertThat(ashby.submissionConfirmation()).isEqualTo(AtsCapabilityLevel.PARTIAL);
        assertThat(ashby.summary()).isEqualTo(AtsCapabilitySummary.FORM_FILL_AVAILABLE);
    }

    @Test
    void workdaySummarisesAsFormFillAvailableWithGuardedFinalSubmission() {
        var workday = AtsCapabilityRegistry.byPlanType("WORKDAY");
        assertThat(workday.summary()).isEqualTo(AtsCapabilitySummary.FORM_FILL_AVAILABLE);
        assertThat(workday.formInspection()).isEqualTo(AtsCapabilityLevel.FULL);
        assertThat(workday.formFilling()).isEqualTo(AtsCapabilityLevel.FULL);
        assertThat(workday.signInReadiness()).isEqualTo(AtsCapabilityLevel.FULL);
        assertThat(workday.documentAttachment()).isEqualTo(AtsCapabilityLevel.FULL);
        assertThat(workday.finalSubmission()).isEqualTo(AtsCapabilityLevel.FULL);
        assertThat(workday.submissionConfirmation()).isEqualTo(AtsCapabilityLevel.PARTIAL);
    }

    @Test
    void workdayAccountCreationIsPartialNeverFullBecauseThePostCreationVerificationMechanismIsUnestablished() {
        var workday = AtsCapabilityRegistry.byPlanType("WORKDAY");
        // Phase 11 implemented account creation itself; Phase 11A established that what
        // happens after it (OTP? link? neither?) is unknown — PARTIAL says both facts honestly.
        assertThat(workday.accountCreationReadiness()).isEqualTo(AtsCapabilityLevel.PARTIAL);
        assertThat(workday.summary()).isEqualTo(AtsCapabilitySummary.FORM_FILL_AVAILABLE);
    }

    @Test
    void everyOtherRecognisedAtsFallsBackToGenericAndRequiresManualApplication() {
        var generic = AtsCapabilityRegistry.byPlanType("ICIMS");
        assertThat(generic.kind()).isEqualTo("GENERIC");
        assertThat(generic.summary()).isEqualTo(AtsCapabilitySummary.MANUAL_APPLICATION_REQUIRED);
        assertThat(AtsCapabilityRegistry.byPlanType(null).kind()).isEqualTo("GENERIC");
        assertThat(AtsCapabilityRegistry.byPlanType("nonsense")).isEqualTo(generic);
    }

    @Test
    void byApplicationUrlClassifiesARealUrlForEachWiredPlatform() {
        assertThat(AtsCapabilityRegistry.byApplicationUrl("https://boards.greenhouse.io/acme/jobs/123").kind()).isEqualTo("GREENHOUSE");
        assertThat(AtsCapabilityRegistry.byApplicationUrl("https://jobs.lever.co/acme/x/apply").kind()).isEqualTo("LEVER");
        assertThat(AtsCapabilityRegistry.byApplicationUrl("https://jobs.eu.lever.co/acme/x/apply").kind()).isEqualTo("LEVER");
        assertThat(AtsCapabilityRegistry.byApplicationUrl("https://sehyog.zohorecruit.in/jobs/Careers").kind()).isEqualTo("ZOHO_RECRUIT");
        assertThat(AtsCapabilityRegistry.byApplicationUrl("https://nvidia.wd5.myworkdayjobs.com/en-US/Site/job/x/apply").kind()).isEqualTo("WORKDAY");
        assertThat(AtsCapabilityRegistry.byApplicationUrl("https://jobs.ashbyhq.com/ramp/a85afad0-8051-4333-ae89-0f82321dea87").kind()).isEqualTo("ASHBY");
        assertThat(AtsCapabilityRegistry.byApplicationUrl("https://careers.acme.com/jobs/123").kind()).isEqualTo("GENERIC");
        assertThat(AtsCapabilityRegistry.byApplicationUrl(null).kind()).isEqualTo("GENERIC");
        assertThat(AtsCapabilityRegistry.byApplicationUrl("").kind()).isEqualTo("GENERIC");
    }

    @Test
    void everyRowDeclaresANonBlankVerificationNoteDistinguishingLiveFromFixtureEvidence() {
        for (AtsCapabilities row : AtsCapabilityRegistry.all()) {
            assertThat(row.verificationNote()).as(row.kind()).isNotBlank();
        }
    }

    @Test
    void recoveryAndDuplicatePreventionIsFullEverywhereTheSharedPipelineAlreadyGuaranteesIt() {
        // Dedup/duplicate-application prevention is a property of the shared
        // pipeline (JobIdentity, the applications table, AutomationPlanRepository's
        // guards), not of any one ATS adapter — so it is uniformly FULL.
        for (AtsCapabilities row : AtsCapabilityRegistry.all()) {
            assertThat(row.recoveryAndDuplicatePrevention()).as(row.kind()).isEqualTo(AtsCapabilityLevel.FULL);
        }
    }
}
