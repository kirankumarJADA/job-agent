package com.personal.jobagent.adversarial;

import com.personal.jobagent.discovery.platform.JobIdentity;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Adversarial QA Suite: Discovery Integrity & Defect Reproductions")
class DiscoveryAdversarialTest {

    @Test
    @DisplayName("P1-6 Reproduction: Generic URL queries like gh_jid differentiate distinct vacancies")
    void testGenericUrlQueriesDifferentiateDistinctVacancies() {
        UUID sourceId = UUID.randomUUID();
        List<JobIdentity.Key> keysA = JobIdentity.keys(sourceId, null, null, "https://company.com/apply?gh_jid=1001", "Company", null);
        List<JobIdentity.Key> keysB = JobIdentity.keys(sourceId, null, null, "https://company.com/apply?gh_jid=1002", "Company", null);

        assertThat(keysA).isNotEmpty();
        assertThat(keysB).isNotEmpty();
        assertThat(keysA).doesNotContainAnyElementsOf(keysB);
    }

    @Test
    @DisplayName("P1-1 Fixed: Aggregator ranking and identification prevent aggregator override of employer ATS authority")
    void testAggregatorCannotResurrectEmployerClosedJob() {
        // Fixed in Wave 1 by Agent 1: Aggregator URLs are identified and ranked strictly below employer/ATS URLs
        assertThat(JobIdentity.isAggregatorUrl("https://tsenta.com/jobs/456")).isTrue();
        assertThat(JobIdentity.applicationUrlRank("https://boards.greenhouse.io/acme/jobs/123"))
                .isGreaterThan(JobIdentity.applicationUrlRank("https://tsenta.com/jobs/456"));
    }
}
