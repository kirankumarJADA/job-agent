package com.personal.jobagent.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression tests for the production 403 on cross-origin SPA POSTs
 * (e.g. `POST /api/v1/sources/{id}/health-check`) that authenticate with
 * `Authorization: Bearer <firebase id token>` and cannot carry the Render
 * domain's XSRF cookie.
 *
 * <p>Contract: a request that authenticates with an explicit Bearer header is
 * exempt from CSRF (stateless; an attacker can never force a browser to send
 * a custom Authorization header cross-origin), so it must reach the
 * authentication layer (401 for an unverifiable token — NOT a CSRF 403).
 * Cookie-session requests without the header keep FULL CSRF enforcement, and
 * worker authentication is untouched.
 */
@SpringBootTest(properties = {
        "spring.flyway.placeholders.remove_seed_dev_account=false",
        "app.worker-event-token=test-worker-token-0123456789abcdef",
        "app.cors.allowed-origins=https://job-agent-beige.vercel.app",
        "app.discovery.scheduler-enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
class CsrfBearerExemptionIT {

    private static final String HEALTH_CHECK_PATH = "/api/v1/sources/{id}/health-check";
    private static final String VERCEL_ORIGIN = "https://job-agent-beige.vercel.app";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private MockMvc mockMvc;

    private String healthCheckPath() {
        return HEALTH_CHECK_PATH.replace("{id}", UUID.randomUUID().toString());
    }

    @Test
    void aBearerAuthenticatedPostPassesCsrfAndReachesAuthentication() throws Exception {
        // An unverifiable Firebase token cannot authenticate, so the security
        // layer answers 401 — the decisive point is that this is NOT the CSRF
        // 403 that blocked production.
        mockMvc.perform(post(healthCheckPath())
                        .header("Authorization", "Bearer fake-firebase-id-token")
                        .header("Origin", VERCEL_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("Bearer requests must pass CsrfFilter and be answered by the auth layer")
                        .isEqualTo(401));
    }

    @Test
    void aCookieSessionPostWithoutCsrfTokenIsStillForbidden() throws Exception {
        // No Authorization header and no CSRF token: the ambient-cookie CSRF
        // protection must remain fully in force.
        mockMvc.perform(post(healthCheckPath())
                        .header("Origin", VERCEL_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aCookieSessionPostWithTheCsrfTokenStillExecutes() throws Exception {
        MockHttpSession session = login();
        // Legitimate same-session flow: session + CSRF token reaches the
        // controller (404 for an unknown source id - controller-level).
        mockMvc.perform(post(healthCheckPath())
                        .with(csrf())
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aCookieSessionPostWithoutTheCsrfTokenIsStillForbidden() throws Exception {
        // THE core CSRF protection: an ambient-cookie session POST without the
        // echoed token stays forbidden - the Bearer exemption must not have
        // weakened it.
        MockHttpSession session = login();
        mockMvc.perform(post(healthCheckPath())
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    private MockHttpSession login() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"dev@example.local\",\"password\":\"DevPassword123!\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) login.getRequest().getSession(false);
    }

    @Test
    void workerEventIngestWithTheWorkerTokenIsUnaffected() throws Exception {
        // The worker authentication fix (WORKER_AUTH_ATTRIBUTE CSRF exemption)
        // keeps working alongside the Bearer exemption.
        MvcResult result = mockMvc.perform(post("/api/v1/automation/events")
                        .header("Authorization", "Bearer test-worker-token-0123456789abcdef")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"evt-1\",\"planId\":\"" + UUID.randomUUID()
                                + "\",\"applicationId\":\"" + UUID.randomUUID()
                                + "\",\"jobId\":\"" + UUID.randomUUID()
                                + "\",\"type\":\"STEP_COMPLETED\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("worker events must not be CSRF-blocked")
                .isNotEqualTo(403);
    }
}
