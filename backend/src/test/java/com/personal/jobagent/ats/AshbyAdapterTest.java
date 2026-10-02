package com.personal.jobagent.ats;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ashby's apply forms are client-rendered (verified live in the Phase 8
 * audit: a 340KB SPA shell with zero form elements, and a posting API with no
 * form schema). The adapter therefore detects URLs precisely and is HONEST
 * about its boundary — inspection fails with a stable code, and submission is
 * refused outright. It must never return the fabricated field list the old
 * mock stub produced.
 */
class AshbyAdapterTest {

    private final AshbyAdapter adapter = new AshbyAdapter();

    @Test
    void matchesOnlyHttpsAshbyBoardUrls() {
        assertThat(adapter.matchesUrl("https://jobs.ashbyhq.com/openai/abc")).isTrue();
        assertThat(adapter.matchesUrl("https://jobs.ashbyhq.com/openai")).isTrue();
        assertThat(adapter.matchesUrl("http://jobs.ashbyhq.com/openai")).isFalse();
        assertThat(adapter.matchesUrl("https://jobs.ashbyhq.com.evil.test/openai")).isFalse();
        assertThat(adapter.matchesUrl("https://evil.test/jobs.ashbyhq.com")).isFalse();
        assertThat(adapter.matchesUrl("https://boards.greenhouse.io/acme/jobs/1")).isFalse();
        assertThat(adapter.matchesUrl(null)).isFalse();
    }

    @Test
    void nonAshbyUrlsAreRejectedWithIllegalArgument() {
        assertThatThrownBy(() -> adapter.inspectForm("https://jobs.lever.co/acme/1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not an Ashby board URL");
    }

    @Test
    void inspectionFailsHonestlyWithTheStableUnavailableCode() {
        // The controller maps IllegalStateException → deterministic 503;
        // this is the contract that keeps a silent mock from ever returning.
        assertThatThrownBy(() -> adapter.inspectForm("https://jobs.ashbyhq.com/openai/abc"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ASHBY_INSPECTION_UNAVAILABLE");
    }

    @Test
    void submissionIsRefusedWithoutAnyNetworkCall() {
        AtsAdapter.SubmissionPayload payload = new AtsAdapter.SubmissionPayload(
                "Test Candidate", "test@example.com", "+441234567890", "London",
                "base64cv", "cover letter", java.util.Map.of());

        AtsAdapter.SubmissionResult result =
                adapter.submitApplication("https://jobs.ashbyhq.com/openai/abc", payload, true);

        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo("REAL_SUBMIT_DISABLED");
        assertThat(result.confirmationId()).isNull();
    }

    @Test
    void theAdapterDeclaresNoLiveInspectionCapability() {
        assertThat(adapter.supportsLiveInspection()).isFalse();
    }
}
