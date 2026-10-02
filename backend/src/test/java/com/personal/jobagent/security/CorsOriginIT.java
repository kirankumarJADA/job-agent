package com.personal.jobagent.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression test for the production CORS failure of the deployed Vercel
 * frontend (https://job-agent-beige.vercel.app) against the Render backend.
 *
 * <p>Root cause of the incident: the frontend's VITE_API_BASE_URL was set
 * without the {@code /api/v1} suffix, so the Firebase session exchange hit
 * {@code /auth/firebase/session} — a path that matches no controller — and
 * the CORS source only covered {@code /api/**}, so the 404 (and its
 * preflight) carried no CORS headers at all. The browser therefore reported
 * the opaque "No Access-Control-Allow-Origin" error instead of a clear 404.
 *
 * <p>Locked in here: the configured production origin receives CORS headers
 * on the REAL session endpoint (preflight + actual), on the misrouted path
 * (so the failure is legible), and a non-configured origin is still rejected;
 * loopback origins stay excluded when {@code app.cors.allow-localhost=false}.
 * No wildcard is ever emitted.
 */
@SpringBootTest(properties = {
        "app.cors.allowed-origins=https://job-agent-beige.vercel.app",
        "app.cors.allow-localhost=false"
})
@AutoConfigureMockMvc
@Testcontainers
class CorsOriginIT {

    private static final String FRONTEND = "https://job-agent-beige.vercel.app";
    private static final String SESSION_PATH = "/api/v1/auth/firebase/session";
    private static final String MISROUTED_PATH = "/auth/firebase/session";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private MockMvc mockMvc;

    @Test
    void preflightForTheFirebaseSessionEndpointEchoesTheConfiguredOrigin() throws Exception {
        mockMvc.perform(options(SESSION_PATH)
                        .header("Origin", FRONTEND)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", FRONTEND))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void anActualPostToTheSessionEndpointCarriesTheCorsHeaders() throws Exception {
        // Body is irrelevant here (an invalid token is a 400) — the contract
        // under test is that the CORS headers reach the browser regardless.
        MvcResult result = mockMvc.perform(post(SESSION_PATH)
                        .header("Origin", FRONTEND)
                        .contentType("application/json")
                        .content("{\"idToken\":\"not-a-real-token\"}"))
                .andReturn();
        assertThat(result.getResponse().getHeader("Access-Control-Allow-Origin")).isEqualTo(FRONTEND);
    }

    @Test
    void aMisroutedPathWithoutTheApiPrefixIsLegibleInsteadOfOpaque() throws Exception {
        // The exact request the incident produced: /auth/firebase/session
        // (missing /api/v1). It still does not exist — but the browser now
        // sees the honest 404 WITH CORS headers instead of a silent CORS block.
        mockMvc.perform(options(MISROUTED_PATH)
                        .header("Origin", FRONTEND)
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", FRONTEND));

        MvcResult post = mockMvc.perform(post(MISROUTED_PATH)
                        .header("Origin", FRONTEND)
                        .contentType("application/json")
                        .content("{}"))
                .andReturn();
        // An unauthenticated caller sees the security layer's 403; with a
        // session it would be the dispatcher's 404. Either way the failure is
        // now legible: a real HTTP status WITH CORS headers, not a silent block.
        assertThat(post.getResponse().getStatus()).isBetween(400, 499);
        assertThat(post.getResponse().getHeader("Access-Control-Allow-Origin")).isEqualTo(FRONTEND);
    }

    @Test
    void aNonConfiguredOriginIsStillRejected() throws Exception {
        mockMvc.perform(options(SESSION_PATH)
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

    @Test
    void loopbackOriginsStayExcludedInProduction() throws Exception {
        mockMvc.perform(options(SESSION_PATH)
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }
}
