package com.personal.jobagent.profile;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end persistence proof for the Master Profile evidence sections —
 * the exact flow the production bug report described: add a verified skill
 * ("Java", "Backend", years 5) → HTTP 201 → the skill is present with all
 * fields on the profile payload the frontend renders, and survives updates.
 *
 * <p>Also pins the GET /profile response CONTRACT the frontend consumes: the
 * profile record is nested under {@code profile}, while the evidence arrays
 * are TOP-LEVEL siblings — normalizing only the nested record was the root
 * cause of every section rendering empty.
 */
@SpringBootTest(properties = {
        "spring.flyway.placeholders.remove_seed_dev_account=false",
        "app.discovery.scheduler-enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProfileSkillsPersistenceIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private MockMvc mockMvc;

    private MockHttpSession session;

    private MockHttpSession login() throws Exception {
        if (session != null) return session;
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"dev@example.local\",\"password\":\"DevPassword123!\"}"))
                .andExpect(status().isOk())
                .andReturn();
        session = (MockHttpSession) login.getRequest().getSession(false);
        return session;
    }

    @Test
    @Order(1)
    void addedSkillPersistsAndRendersOnTheProfilePayload() throws Exception {
        // The exact record from the production bug report:
        mockMvc.perform(post("/api/v1/profile/skills").with(csrf()).session(login())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Java\",\"category\":\"Backend\",\"mastery\":4,\"years\":5}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty());

        // Persisted AND present on the payload the UI renders:
        mockMvc.perform(get("/api/v1/profile").session(login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skills[0].name").value("Java"))
                .andExpect(jsonPath("$.skills[0].category").value("Backend"))
                .andExpect(jsonPath("$.skills[0].mastery").value(4))
                .andExpect(jsonPath("$.skills[0].years").value(5))
                // The profile record itself carries no evidence arrays — the
                // frontend must read them from the top level (this is the
                // shape detail that made sections render empty):
                .andExpect(jsonPath("$.profile.id").isNotEmpty())
                .andExpect(jsonPath("$.profile.skills").doesNotExist());
    }

    @Test
    @Order(2)
    void skillUpdatePersists() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/profile/skills").with(csrf()).session(login())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Spring\",\"category\":\"Backend\",\"mastery\":3,\"years\":2}"))
                .andExpect(status().isCreated())
                .andReturn();
        String skillId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(put("/api/v1/profile/skills/" + skillId).with(csrf()).session(login())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Spring\",\"category\":\"Backend\",\"mastery\":5,\"years\":3}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/profile").session(login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skills[?(@.name=='Spring')].mastery").value(5))
                .andExpect(jsonPath("$.skills[?(@.name=='Spring')].years").value(3.0));
    }
}
