package com.personal.jobagent.discovery.plan;

import com.personal.jobagent.jobs.SeniorityClassifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Agent 2: Search Plan Query Expander Unit Tests")
class SearchPlanQueryExpanderTest {

    private final SearchPlanQueryExpander expander = new SearchPlanQueryExpander();

    @Test
    @DisplayName("Expands target titles into synonyms and canonical tech skills")
    void expandsTitlesAndSkills() {
        SearchPlanRecord plan = new SearchPlanRecord(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Backend Plan",
                List.of("Backend Engineer", "Java Developer"),
                Map.of(
                        "skills", List.of("ReactJS", "SpringBoot", "Postgres"),
                        "seniority", List.of("SENIOR"),
                        "locations", List.of("London"),
                        "remoteTypes", List.of("HYBRID", "REMOTE"),
                        "exclude", List.of("Crypto", "Gambling")
                ),
                List.of("ADZUNA", "GREENHOUSE"),
                "DAILY",
                50,
                true,
                null,
                Instant.now(),
                Instant.now()
        );

        SearchPlanQueryExpander.ExpandedQueryPlan expanded = expander.expand(plan);

        assertThat(expanded.originalTitles()).containsExactly("Backend Engineer", "Java Developer");
        // Title expansion contains synonyms
        assertThat(expanded.expandedTitles()).contains("backend developer", "back end engineer");
        // Skill aliases are canonicalised
        assertThat(expanded.primaryKeywords()).contains("react", "spring boot");
        assertThat(expanded.seniorityLevels()).contains(SeniorityClassifier.Level.SENIOR);
        assertThat(expanded.negativeKeywords()).containsExactly("Crypto", "Gambling");
        assertThat(expanded.targetSources()).containsExactly("ADZUNA", "GREENHOUSE");
    }

    @Test
    @DisplayName("Applies sane defaults when facets are empty")
    void appliesDefaultsForEmptyFacets() {
        SearchPlanRecord plan = new SearchPlanRecord(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Minimal Plan",
                List.of("Software Engineer"),
                Map.of(),
                List.of(),
                "MANUAL",
                0,
                true,
                null,
                Instant.now(),
                Instant.now()
        );

        SearchPlanQueryExpander.ExpandedQueryPlan expanded = expander.expand(plan);

        assertThat(expanded.originalTitles()).containsExactly("Software Engineer");
        assertThat(expanded.targetSources()).contains("ADZUNA", "ARBEITNOW", "FREEHIRE", "TSENTA", "GREENHOUSE");
        assertThat(expanded.maxResults()).isEqualTo(50);
        assertThat(expanded.negativeKeywords()).isEmpty();
    }
}
