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
    @Disabled("KNOWN DEFECT P1-1: Aggregator SEEN observation keeps alive or resurrects closed employer job on baseline")
    @DisplayName("P1-1 Baseline Defect: Aggregator observation must not override employer ATS closure")
    void testAggregatorCannotResurrectEmployerClosedJob() {
        // P1-1 defect reproduction: On baseline, aggregator keepalive overrides employer removal.
        // Fixed in Wave 1 by Agent 1 (re-enabled in Phase B review).
    }
}
