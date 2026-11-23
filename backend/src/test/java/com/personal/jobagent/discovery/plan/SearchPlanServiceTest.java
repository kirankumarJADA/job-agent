package com.personal.jobagent.discovery.plan;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("Agent 2: SearchPlanService Unit Tests")
class SearchPlanServiceTest {

    private SearchPlanRepository repository;
    private SearchPlanQueryExpander queryExpander;
    private SearchPlanService service;

    private final UUID profileId = UUID.randomUUID();
    private final UUID planId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(SearchPlanRepository.class);
        queryExpander = new SearchPlanQueryExpander();
        service = new SearchPlanService(repository, queryExpander);
    }

    @Test
    @DisplayName("Creates search plan successfully when valid")
    void createPlan_valid() {
        SearchPlanRecord input = new SearchPlanRecord(
                null, null, "Primary Backend Plan",
                List.of("Backend Engineer"),
                Map.of("skills", List.of("Java", "Spring Boot")),
                List.of("ADZUNA", "ARBEITNOW"),
                "DAILY", 50, true, null, null, null
        );

        when(repository.create(any(SearchPlanRecord.class))).thenAnswer(inv -> {
            SearchPlanRecord rec = inv.getArgument(0);
            return new SearchPlanRecord(
                    planId, profileId, rec.name(), rec.targetTitles(), rec.queryFacets(),
                    rec.targetSources(), rec.searchFrequency(), rec.maxResultsPerRun(),
                    rec.active(), null, Instant.now(), Instant.now()
            );
        });

        SearchPlanRecord result = service.createPlan(profileId, input);

        assertThat(result.id()).isEqualTo(planId);
        assertThat(result.profileId()).isEqualTo(profileId);
        assertThat(result.name()).isEqualTo("Primary Backend Plan");
        assertThat(result.searchFrequency()).isEqualTo("DAILY");
        verify(repository).create(any(SearchPlanRecord.class));
    }

    @Test
    @DisplayName("Rejects plan with blank name")
    void createPlan_blankName() {
        SearchPlanRecord input = new SearchPlanRecord(
                null, null, "   ",
                List.of("Engineer"), Map.of(), List.of(), "DAILY", 50, true, null, null, null
        );

        assertThatThrownBy(() -> service.createPlan(profileId, input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name cannot be blank");
    }

    @Test
    @DisplayName("Rejects plan with invalid maxResultsPerRun")
    void createPlan_invalidMaxResults() {
        SearchPlanRecord input = new SearchPlanRecord(
                null, null, "Plan",
                List.of("Engineer"), Map.of(), List.of(), "DAILY", 999, true, null, null, null
        );

        assertThatThrownBy(() -> service.createPlan(profileId, input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxResultsPerRun must be between 1 and 500");
    }

    @Test
    @DisplayName("Enforces tenant isolation on plan retrieval")
    void getPlan_tenantIsolation() {
        UUID otherProfileId = UUID.randomUUID();
        when(repository.findByIdAndProfileId(planId, profileId)).thenReturn(Optional.of(
                new SearchPlanRecord(planId, profileId, "Plan", List.of(), Map.of(), List.of(), "DAILY", 50, true, null, Instant.now(), Instant.now())
        ));
        when(repository.findByIdAndProfileId(planId, otherProfileId)).thenReturn(Optional.empty());

        assertThat(service.getPlan(planId, profileId)).isPresent();
        assertThat(service.getPlan(planId, otherProfileId)).isEmpty();
    }

    @Test
    @DisplayName("Expands search plan using Title and Skill synomyms")
    void expandPlan_success() {
        SearchPlanRecord plan = new SearchPlanRecord(
                planId, profileId, "Expandable Plan",
                List.of("Frontend Engineer"),
                Map.of("skills", List.of("ReactJS")),
                List.of("ADZUNA"),
                "DAILY", 50, true, null, Instant.now(), Instant.now()
        );
        when(repository.findByIdAndProfileId(planId, profileId)).thenReturn(Optional.of(plan));

        var expanded = service.expandPlan(planId, profileId);

        assertThat(expanded).isPresent();
        assertThat(expanded.get().expandedTitles()).contains("frontend developer", "ui engineer");
        assertThat(expanded.get().primaryKeywords()).contains("react");
    }
}
