package com.personal.jobagent.ats;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AtsAdapterRegistryTest {

    private final List<AtsAdapter> allAdapters = List.of(
            new GreenhouseAdapter(),
            new AshbyAdapter(),
            new AtsAdapters.LeverAdapter(),
            new AtsAdapters.WorkdayAdapter(),
            new AtsAdapters.IcimsAdapter(),
            new AtsAdapters.BambooHrAdapter(),
            new AtsAdapters.WorkableAdapter(),
            new AtsAdapters.JobviteAdapter(),
            new AtsAdapters.BreezyHrAdapter(),
            new AtsAdapters.OracleCloudAdapter(),
            new AtsAdapters.PaylocityAdapter(),
            new AtsAdapters.UkgAdapter(),
            new AtsAdapters.AdpAdapter(),
            new AtsAdapters.DoverAdapter(),
            new AtsAdapters.GemAdapter(),
            new AtsAdapters.ZohoAdapter(),
            new AtsAdapters.RipplingAdapter()
    );

    private final AtsAdapterRegistry registry = new AtsAdapterRegistry(allAdapters);

    /**
     * Discovery's own JobIdentity recognises a Zoho Recruit posting by URL
     * path shape, independent of host — so it already finds postings on
     * .in tenants. APPLY's adapter previously only matched .com, so the two
     * disagreed on the exact same URL. Both the new .in recognition and the
     * host-anchoring (rejecting a lookalike domain a bare substring search
     * would have accepted) are covered here.
     */
    @Test
    void zohoAdapterRecognisesBothComAndInTenantsButNotALookalikeDomain() {
        AtsAdapter zoho = new AtsAdapters.ZohoAdapter();
        assertThat(zoho.matchesUrl("https://acme.zohorecruit.com/jobs/Careers/1234567890123")).isTrue();
        assertThat(zoho.matchesUrl("https://acme.zohorecruit.in/jobs/Careers/1234567890123")).isTrue();
        assertThat(zoho.matchesUrl("https://zohorecruit.in/jobs/Careers/1234567890123")).isTrue();
        assertThat(zoho.matchesUrl("https://notzohorecruit.com.attacker.example/jobs/Careers/1")).isFalse();
        assertThat(zoho.matchesUrl("http://acme.zohorecruit.in/jobs/Careers/1")).isFalse(); // http, not https
        assertThat(zoho.matchesUrl(null)).isFalse();

        assertThat(registry.findAdapterForUrl("https://acme.zohorecruit.in/jobs/Careers/1234567890123"))
                .isPresent().get().extracting(AtsAdapter::kind).isEqualTo(AtsKind.ZOHO);
    }

    @Test
    void matchesMajorAtsUrlsCorrectly() {
        assertThat(registry.findAdapterForUrl("https://boards.greenhouse.io/monzo/jobs/123"))
                .isPresent().get().extracting(AtsAdapter::kind).isEqualTo(AtsKind.GREENHOUSE);

        assertThat(registry.findAdapterForUrl("https://jobs.lever.co/deliveroo/456"))
                .isPresent().get().extracting(AtsAdapter::kind).isEqualTo(AtsKind.LEVER);

        assertThat(registry.findAdapterForUrl("https://jobs.ashbyhq.com/synthesia/789"))
                .isPresent().get().extracting(AtsAdapter::kind).isEqualTo(AtsKind.ASHBY);

        assertThat(registry.findAdapterForUrl("https://revolut.myworkdayjobs.com/en-US/Revolut/job/100"))
                .isPresent().get().extracting(AtsAdapter::kind).isEqualTo(AtsKind.WORKDAY);

        assertThat(registry.findAdapterForUrl("https://careers-bloomberg.icims.com/jobs/999/job"))
                .isPresent().get().extracting(AtsAdapter::kind).isEqualTo(AtsKind.ICIMS);

        assertThat(registry.findAdapterForUrl("https://wise.bamboohr.com/careers/55"))
                .isPresent().get().extracting(AtsAdapter::kind).isEqualTo(AtsKind.BAMBOOHR);

        assertThat(registry.findAdapterForUrl("https://apply.workable.com/snyk/j/ABCDEF/"))
                .isPresent().get().extracting(AtsAdapter::kind).isEqualTo(AtsKind.WORKABLE);
    }

    @Test
    void submitsApplicationWithValidationAndDryRun() {
        AtsAdapter workday = registry.findByKind(AtsKind.WORKDAY).orElseThrow();
        AtsAdapter.SubmissionPayload validPayload = new AtsAdapter.SubmissionPayload(
                "Ada Lovelace",
                "ada@example.com",
                "+447123456789",
                "London, UK",
                "JVBERi0xLjQKJcTl8uXr...", // base64 resume
                "Dear Hiring Team...",
                Map.of("years_experience", "5")
        );

        AtsAdapter.SubmissionResult dryRunResult = workday.submitApplication(
                "https://company.myworkdayjobs.com/job/1",
                validPayload,
                true
        );

        assertThat(dryRunResult.success()).isTrue();
        assertThat(dryRunResult.status()).isEqualTo("DRY_RUN_PASSED");
        assertThat(dryRunResult.confirmationId()).startsWith("MOCK-WORKDAY-");
    }

    @Test
    void rejectsInvalidPayloadNegativePaths() {
        AtsAdapter lever = registry.findByKind(AtsKind.LEVER).orElseThrow();

        AtsAdapter.SubmissionPayload invalidEmailPayload = new AtsAdapter.SubmissionPayload(
                "Test Candidate",
                "invalid-email",
                "12345",
                "London",
                "base64data",
                "Cover letter",
                Map.of()
        );

        AtsAdapter.SubmissionResult result = lever.submitApplication(
                "https://jobs.lever.co/company/1",
                invalidEmailPayload,
                false
        );

        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo("INVALID_PAYLOAD");
    }

    /**
     * Security regression (Robin application-execution hardening): a non-dry-run
     * call must never be reported as a genuine submission, for ANY adapter, in
     * ANY environment — there is no profile flag or configuration that can make
     * this true, because the behaviour does not branch on configuration at all.
     * Real execution exists only for Greenhouse's separate, reviewed
     * AutomationPlan/worker pipeline (automation.GreenhouseExecutionPlanService),
     * which this method never calls.
     */
    @Test
    void noAdapterEverReportsARealSubmissionForANonDryRunCall() {
        AtsAdapter.SubmissionPayload validPayload = new AtsAdapter.SubmissionPayload(
                "Ada Lovelace",
                "ada@example.com",
                "+447123456789",
                "London, UK",
                "JVBERi0xLjQKJcTl8uXr...", // base64 resume
                "Dear Hiring Team...",
                Map.of("years_experience", "5")
        );

        for (AtsAdapter adapter : allAdapters) {
            AtsAdapter.SubmissionResult result = adapter.submitApplication(
                    "https://example.test/careers/apply/1", validPayload, false);

            assertThat(result.success())
                    .as("%s must never report success for a non-dry-run call", adapter.kind())
                    .isFalse();
            assertThat(result.status())
                    .as("%s must never report SUBMITTED for a non-dry-run call", adapter.kind())
                    .isNotEqualTo("SUBMITTED");
            assertThat(result.confirmationId())
                    .as("%s must never mint a confirmation id for a non-dry-run call", adapter.kind())
                    .isNull();
        }
    }

    /** Pins the exact, unambiguous status every generic (non-Greenhouse-real,
     * non-Ashby) adapter reports for a non-dry-run call, so the message can
     * never silently drift back toward something that reads as success. */
    @Test
    void genericAdaptersReportRealSubmissionAsExplicitlyNotImplemented() {
        AtsAdapter workday = registry.findByKind(AtsKind.WORKDAY).orElseThrow();
        AtsAdapter.SubmissionPayload validPayload = new AtsAdapter.SubmissionPayload(
                "Ada Lovelace", "ada@example.com", "+447123456789", "London, UK",
                "JVBERi0xLjQKJcTl8uXr...", "Dear Hiring Team...", Map.of());

        AtsAdapter.SubmissionResult result = workday.submitApplication(
                "https://company.myworkdayjobs.com/job/1", validPayload, false);

        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo("REAL_SUBMIT_NOT_IMPLEMENTED");
        assertThat(result.details()).containsEntry("simulated", false);
    }

    /** Greenhouse is the one adapter with a REAL execution path — but not
     * through this generic interface. It must report the same safe refusal
     * as every other generic adapter, never a fabricated SUBMITTED. */
    @Test
    void greenhouseAdapterRefusesRealSubmissionThroughTheGenericInterfaceToo() {
        AtsAdapter greenhouse = registry.findByKind(AtsKind.GREENHOUSE).orElseThrow();
        AtsAdapter.SubmissionPayload validPayload = new AtsAdapter.SubmissionPayload(
                "Ada Lovelace", "ada@example.com", "+447123456789", "London, UK",
                "JVBERi0xLjQKJcTl8uXr...", "Dear Hiring Team...", Map.of());

        AtsAdapter.SubmissionResult result = greenhouse.submitApplication(
                "https://boards.greenhouse.io/monzo/jobs/123", validPayload, false);

        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo("REAL_SUBMIT_NOT_IMPLEMENTED");
    }
}