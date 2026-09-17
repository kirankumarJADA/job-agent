package com.personal.jobagent.ats;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AtsAdapterRegistryTest {

    private final List<AtsAdapter> allAdapters = List.of(
            new AtsAdapters.GreenhouseAdapter(),
            new AtsAdapters.LeverAdapter(),
            new AtsAdapters.AshbyAdapter(),
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
}