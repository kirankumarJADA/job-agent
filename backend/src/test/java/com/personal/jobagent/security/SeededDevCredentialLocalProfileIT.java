package com.personal.jobagent.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Local development keeps working, verified through the profile that actually
 * runs locally rather than through a hand-set property.
 *
 * <p>V024 removes the seeded development credential everywhere except an
 * explicitly local environment, and {@code application-local.yml} is the only
 * configuration that opts out. This class loads that real profile — the one
 * {@code infra/docker-compose.yml} activates with
 * {@code SPRING_PROFILES_ACTIVE=local} — against a fresh database, and signs in
 * with the seeded account exactly as local inspection mode does
 * (frontend/src/localInspection.ts). If someone ever deletes that opt-out, local
 * development stops being able to sign in and this test says so, instead of the
 * first symptom being a developer staring at a 401.
 */
@SpringBootTest
@ActiveProfiles("local")
@AutoConfigureMockMvc
@Testcontainers
class SeededDevCredentialLocalProfileIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void theSeededDevelopmentLoginStillWorksUnderTheLocalProfile() throws Exception {
        assertThat(jdbcTemplate.queryForObject(
                "select password_hash from users where email = ?", String.class, "dev@example.local"))
                .as("the local profile must keep the seeded credential")
                .isNotNull();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"dev@example.local","password":"DevPassword123!"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("dev@example.local"))
                .andExpect(jsonPath("$.displayName").value("Dev User"));
    }
}
