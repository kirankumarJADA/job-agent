package com.personal.jobagent.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * NOTE ON VERIFICATION STATUS: written against the same live-Postgres-proven
 * facts as the rest of P1-b (the purge cascade behavior was independently
 * verified via raw SQL — see PurgeService's javadoc), but this test class
 * itself, wired through Spring Security + MockMvc + Testcontainers, has NOT
 * been executed (no Maven Central access in the authoring environment).
 * Run via `mvn verify` before treating the auth flow as done.
 *
 * Uses the real seeded dev user (dev@example.local / DevPassword123!, per
 * V003's corrective migration) rather than creating a throwaway user, since
 * exercising the actual seed data end-to-end is itself part of what needs
 * proving.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuthControllerIT {

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

    private static final String LOGIN_BODY = """
            {"email":"dev@example.local","password":"DevPassword123!"}
            """;

    @Test
    void loginSuccess_returnsUserAndSetsSessionCookie() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LOGIN_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("dev@example.local"))
                .andExpect(jsonPath("$.displayName").value("Dev User"))
                .andExpect(request().sessionAttribute(
                        "SPRING_SECURITY_CONTEXT", org.hamcrest.Matchers.notNullValue()));
    }

    @Test
    void loginFailure_wrongPassword_returns401WithoutEnumeration() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"dev@example.local","password":"wrong-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Invalid credentials"));
    }

    @Test
    void loginFailure_unknownEmail_returnsIdenticalShapeToWrongPassword() throws Exception {
        // Same assertions as the wrong-password case on purpose — this test
        // existing at all is the point: if someone later changes the
        // unknown-user path to a different message/status, this fails and
        // flags the user-enumeration regression immediately.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"nobody@example.local","password":"whatever123"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Invalid credentials"));
    }

    @Test
    void me_withoutSession_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void me_withSession_returnsUser() throws Exception {
        MockHttpSession session = loginAndGetSession();

        mockMvc.perform(get("/api/v1/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("dev@example.local"));
    }

    @Test
    void logout_invalidatesSession() throws Exception {
        MockHttpSession session = loginAndGetSession();

        mockMvc.perform(post("/api/v1/auth/logout").session(session))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/auth/me").session(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void purge_wrongConfirmationPassword_returns403AndKeepsData() throws Exception {
        MockHttpSession session = loginAndGetSession();

        mockMvc.perform(post("/api/v1/auth/purge-my-data")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"confirmationPassword":"not-the-right-password"}
                                """))
                .andExpect(status().isForbidden());

        Integer profileCount = jdbcTemplate.queryForObject(
                "select count(*) from profiles p join users u on u.id = p.user_id where u.email = 'dev@example.local'",
                Integer.class);
        assertThat(profileCount).isEqualTo(1);
    }

    @Test
    void purge_correctConfirmation_deletesProfileButKeepsUser() throws Exception {
        MockHttpSession session = loginAndGetSession();

        mockMvc.perform(post("/api/v1/auth/purge-my-data")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"confirmationPassword":"DevPassword123!"}
                                """))
                .andExpect(status().isNoContent());

        Integer profileCount = jdbcTemplate.queryForObject(
                "select count(*) from profiles p join users u on u.id = p.user_id where u.email = 'dev@example.local'",
                Integer.class);
        Integer userCount = jdbcTemplate.queryForObject(
                "select count(*) from users where email = 'dev@example.local'", Integer.class);

        assertThat(profileCount).isEqualTo(0);
        assertThat(userCount).isEqualTo(1);

        Integer purgeAuditCount = jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where action = 'DATA_PURGE_REQUESTED'", Integer.class);
        assertThat(purgeAuditCount).isEqualTo(1);
    }

    private MockHttpSession loginAndGetSession() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LOGIN_BODY))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
