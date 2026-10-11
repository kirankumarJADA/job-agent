package com.personal.jobagent.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Source hiding (Phase 8.2.5): the candidate job DTO never reveals which
 * website, ATS platform or aggregator surfaced a posting — not as a field,
 * not inside another value — while the employer, the title and the employer's
 * genuine application link stay visible. Fixtures cover several discovery
 * sources, each with a provider-revealing source name, kind and canonical URL.
 */
class CandidateSourceVisibilityTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> PROVIDER_STRINGS = List.of("greenhouse", "workday", "myworkdayjobs-source", "lever.co/",
            "ashby", "tsenta", "smartrecruiters", "icims", "jobvite", "powered by", "found on", "via ", "source_name",
            "source_kind", "source_id", "canonical_url", "external_id");

    private static JobRepository.FeedItem item(String sourceName, String sourceKind, String canonical, String application,
                                               String employer, String title) {
        JobRecord job = new JobRecord(UUID.randomUUID(), UUID.randomUUID(), "ext-" + sourceKind, null, employer, title,
                "London", "London", "GB", "HYBRID", "FULL_TIME", null, null, null, null, "Build things.",
                List.of("Java"), application, canonical, Instant.parse("2026-10-01T00:00:00Z"), "DISCOVERED");
        return new JobRepository.FeedItem(job, Instant.parse("2026-10-02T00:00:00Z"), Instant.parse("2026-10-09T00:00:00Z"),
                false, false, sourceName, sourceKind, 77, "APPLY", null);
    }

    @ParameterizedTest
    @CsvSource({
            "Stripe (Greenhouse), GREENHOUSE, https://boards.greenhouse.io/stripe/jobs/1, https://stripe.com/jobs/listing/1/apply, Stripe, Backend Engineer",
            "NVIDIA (Workday), WORKDAY, https://nvidia.wd5.myworkdayjobs.com/x/job/y_JR1, https://careers.nvidia.com/apply/JR1, NVIDIA, GPU Engineer",
            "Tsenta (aggregator), TSENTA, https://tsenta.com/jobs/acme-analyst-8d7f73a6-5a94-4d7a-84c3-4db0ce114498, https://acme.example.com/careers/analyst, Acme, Analyst",
            "Bosch (SmartRecruiters), SMARTRECRUITERS, https://jobs.smartrecruiters.com/BoschGroup/7440, https://bosch.example.com/apply/7440, Bosch Group, Operador"
    })
    void theCandidateDtoCarriesNoProviderInformation(String sourceName, String kind, String canonical, String application,
                                                    String employer, String title) throws Exception {
        Map<String, Object> wire = JobsController.toWire(item(sourceName, kind, canonical, application, employer, title));
        String json = JSON.writeValueAsString(wire).toLowerCase();

        assertThat(wire).doesNotContainKeys("source_id", "source_name", "source_kind", "external_id", "canonical_url");
        for (String forbidden : PROVIDER_STRINGS) {
            assertThat(json).as("candidate JSON must not contain '%s'", forbidden).doesNotContain(forbidden);
        }
        // Employer identity and the genuine application link are still there, unaltered.
        assertThat(wire).containsEntry("company_name_raw", employer).containsEntry("title", title)
                .containsEntry("application_url", application);
    }

    @Test
    void anAtsApplicationUrlIsTheEmployersApplicationPageAndIsKept() {
        Map<String, Object> wire = JobsController.toWire(item("Monzo (Greenhouse)", "GREENHOUSE",
                "https://boards.greenhouse.io/monzo/jobs/8143930", "https://boards.greenhouse.io/monzo/jobs/8143930",
                "Monzo", "Analyst"));
        // Not disguised, not replaced: the employer's real form stays the apply link.
        assertThat(wire).containsEntry("application_url", "https://boards.greenhouse.io/monzo/jobs/8143930");
        assertThat(wire).doesNotContainKeys("source_name", "source_kind", "canonical_url");
    }

    @Test
    void anAggregatorUrlIsNeverOfferedAsTheApplicationLink() {
        Map<String, Object> wire = JobsController.toWire(item("Tsenta", "TSENTA",
                "https://tsenta.com/jobs/x-8d7f73a6-5a94-4d7a-84c3-4db0ce114498",
                "https://dashboard.tsenta.com/dashboard?apply=8d7f73a6-5a94-4d7a-84c3-4db0ce114498", "Acme", "Analyst"));
        assertThat(wire.get("application_url")).isNull();
        assertThat(wire).containsEntry("company_name_raw", "Acme");
    }
}
