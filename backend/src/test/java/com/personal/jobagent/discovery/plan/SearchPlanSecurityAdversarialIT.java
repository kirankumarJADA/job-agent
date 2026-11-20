package com.personal.jobagent.discovery.plan;

import com.personal.jobagent.common.UuidV7;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.flyway.placeholders.remove_seed_dev_account=false",
        "app.discovery.scheduler-enabled=false",
        "app.security.operator-emails=dev@example.local"
})
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("Agent 4: Search Plan Security & Cross-Tenant Adversarial IT")
public class SearchPlanSecurityAdversarialIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SearchPlanRepository searchPlanRepository;

    @Test
    @DisplayName("Adversarial: SQL Injection payloads in search plan titles and facets are safely escaped")
    void testSqlInjectionResistance() {
        UUID profileId = createTestProfile("sqli-test@example.com");

        String sqlInjectionTitle = "Senior Engineer'; DROP TABLE search_plans CASCADE; --";
        String sqlInjectionFacetKey = "facet'; SELECT pg_sleep(5); --";

        SearchPlanRecord maliciousPlan = new SearchPlanRecord(
                UuidV7.generate(),
                profileId,
                "SQLi Resistance Test",
                List.of(sqlInjectionTitle),
                Map.of(sqlInjectionFacetKey, "injected_value' OR '1'='1"),
                List.of("ADZUNA"),
                "DAILY",
                50,
                true,
                null,
                null,
                null
        );

        SearchPlanRecord created = searchPlanRepository.create(maliciousPlan);
        assertThat(created).isNotNull();

        // Verify table still exists and data was safely parameterized
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM search_plans WHERE id = ?", Integer.class, created.id());
        assertThat(count).isEqualTo(1);

        SearchPlanRecord retrieved = searchPlanRepository.findByIdAndProfileId(created.id(), profileId).orElseThrow();
        assertThat(retrieved.targetTitles()).contains(sqlInjectionTitle);
        assertThat(retrieved.queryFacets()).containsKey(sqlInjectionFacetKey);
    }

    @Test
    @DisplayName("Cross-Tenant: Candidate B cannot access or modify Candidate A's search plan")
    void testCrossTenantIsolation() {
        UUID candidateA = createTestProfile("candidate-a@example.com");
        UUID candidateB = createTestProfile("candidate-b@example.com");

        SearchPlanRecord planA = searchPlanRepository.create(new SearchPlanRecord(
                UuidV7.generate(),
                candidateA,
                "Candidate A Private Search Plan",
                List.of("Staff Engineer"),
                Map.of("skills", List.of("Rust")),
                List.of("GREENHOUSE"),
                "DAILY",
                50,
                true,
                null,
                null,
                null
        ));

        // Direct repository verification
        assertThat(searchPlanRepository.findByIdAndProfileId(planA.id(), candidateA)).isPresent();
        assertThat(searchPlanRepository.findByIdAndProfileId(planA.id(), candidateB)).isEmpty();

        // Candidate B attempt to delete Candidate A's plan returns false
        boolean deletedByB = searchPlanRepository.delete(planA.id(), candidateB);
        assertThat(deletedByB).isFalse();

        // Candidate A's plan remains intact
        assertThat(searchPlanRepository.findByIdAndProfileId(planA.id(), candidateA)).isPresent();
    }

    private UUID createTestProfile(String email) {
        UUID userId = UuidV7.generate();
        UUID profileId = UuidV7.generate();

        jdbcTemplate.update("""
                INSERT INTO users (id, email, password_hash, display_name, created_at)
                VALUES (?, ?, 'hash', 'Test User', now())
                """, userId, email);

        jdbcTemplate.update("""
                INSERT INTO profiles (id, user_id, updated_at)
                VALUES (?, ?, now())
                """, profileId, userId);

        return profileId;
    }
}
