package com.personal.jobagent.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Production hardening pass: production must never trust a loopback origin.
 *
 * <p>Motivation (observed in the hosted deployment): Render's
 * {@code APP_CORS_ALLOWED_ORIGINS} still contained {@code http://localhost:5173}
 * and the backend answered `OPTIONS /api/v1/jobs` with
 * {@code Access-Control-Allow-Origin: http://localhost:5173} and
 * {@code Access-Control-Allow-Credentials: true}. Configuration alone cannot be
 * trusted to stay clean, so the prod profile now sets
 * {@code app.cors.allow-localhost=false} and this class pins both the profile
 * setting and the actual CorsFilter outcome.
 *
 * <p>These tests exercise the real {@link CorsFilter} over the real
 * {@link SecurityConfig#corsConfigurationSource()} where the assertion is about
 * HTTP behaviour, not just the parsed config object.
 */
class ProductionCorsOriginsTest {

    private static final String DEV_ORIGIN = "http://localhost:5173";
    private static final String PROD_ORIGIN = "https://job-agent.example.com";

    @Test
    void productionDropsLoopbackOriginsFromTheEffectiveAllowlist() {
        SecurityConfig config = config(List.of(DEV_ORIGIN, PROD_ORIGIN), false);

        assertThat(config.effectiveAllowedOrigins())
                .as("production must keep the real frontend origin")
                .containsExactly(PROD_ORIGIN);
    }

    @Test
    void productionWithOnlyLoopbackConfiguredTrustsNoOrigins() {
        SecurityConfig config = config(List.of(DEV_ORIGIN), false);

        assertThat(config.effectiveAllowedOrigins())
                .as("a leftover dev origin alone must not be trusted in production")
                .isEmpty();
    }

    @Test
    void localDevelopmentStillTrustsTheLoopbackDevServer() {
        SecurityConfig config = config(List.of(DEV_ORIGIN, PROD_ORIGIN), true);

        assertThat(config.effectiveAllowedOrigins())
                .as("local dev needs the Vite dev server origin")
                .containsExactly(DEV_ORIGIN, PROD_ORIGIN);
    }

    @Test
    void loopbackPreflightIsRejectedInProductionButAcceptedInDevelopment() throws Exception {
        MockMvc production = mockMvc(config(List.of(DEV_ORIGIN, PROD_ORIGIN), false));
        MockMvc development = mockMvc(config(List.of(DEV_ORIGIN, PROD_ORIGIN), true));

        preflight(production, DEV_ORIGIN).andExpect(status().isForbidden());
        preflight(development, DEV_ORIGIN)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, DEV_ORIGIN));

        preflight(production, PROD_ORIGIN)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, PROD_ORIGIN));
    }

    @Test
    void simpleCrossOriginRequestFromLoopbackIsRejectedInProduction() throws Exception {
        MockMvc production = mockMvc(config(List.of(DEV_ORIGIN, PROD_ORIGIN), false));

        MvcResult rejected = production.perform(get("/api/v1/probe").header(HttpHeaders.ORIGIN, DEV_ORIGIN))
                .andReturn();
        assertThat(rejected.getResponse().getStatus()).isEqualTo(403);
        assertThat(rejected.getResponse().getContentAsString()).contains("Invalid CORS request");

        MvcResult accepted = production.perform(get("/api/v1/probe").header(HttpHeaders.ORIGIN, PROD_ORIGIN))
                .andReturn();
        assertThat(accepted.getResponse().getStatus()).isEqualTo(200);
        assertThat(accepted.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isEqualTo(PROD_ORIGIN);
    }

    @Test
    void loopbackDetectionCoversLoopbackFormsOnly() {
        assertThat(List.of(
                "http://localhost",
                "http://localhost:5173",
                "https://localhost:8082",
                "localhost:5173",
                "http://127.0.0.1:5173",
                "https://127.0.0.2",
                "http://[::1]:5173"
        )).allSatisfy(origin -> assertThat(SecurityConfig.isLoopbackOrigin(origin))
                .as("expected loopback: " + origin)
                .isTrue());

        assertThat(List.of(
                "https://job-agent.example.com",
                "https://mylocalhost.example.com",
                "https://127.0.0.1.evil.example.com",
                "http://192.168.1.10:5173",
                "https://localhost.example.com"
        )).allSatisfy(origin -> assertThat(SecurityConfig.isLoopbackOrigin(origin))
                .as("must NOT be treated as loopback: " + origin)
                .isFalse());

        assertThat(SecurityConfig.isLoopbackOrigin(null)).isFalse();
        assertThat(SecurityConfig.isLoopbackOrigin("  ")).isFalse();
    }

    @Test
    void prodProfilePinsLoopbackOffWhileBaseProfileDefaultsItOn() throws IOException {
        Object prodValue = yaml("application-prod.yml", "app.cors.allow-localhost");
        Object prodOrigins = yaml("application-prod.yml", "app.cors.allowed-origins");
        Object devValue = yaml("application.yml", "app.cors.allow-localhost");

        assertThat(String.valueOf(prodValue))
                .as("prod must default allow-localhost to false (env-overridable only for the local prod-like stack)")
                .isEqualTo("${APP_CORS_ALLOW_LOCALHOST:false}");
        assertThat(String.valueOf(prodOrigins))
                .as("prod origins stay environment-driven; no origin is hardcoded")
                .isEqualTo("${APP_CORS_ALLOWED_ORIGINS}");
        assertThat(String.valueOf(devValue))
                .as("local development keeps trusting the dev server by default")
                .isEqualTo("${APP_CORS_ALLOW_LOCALHOST:true}");
    }

    private static Object yaml(String resource, String key) throws IOException {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load(resource, new ClassPathResource(resource));
        assertThat(sources).as(resource + " must be on the classpath").isNotEmpty();
        return sources.get(0).getProperty(key);
    }

    private static SecurityConfig config(List<String> allowedOrigins, boolean allowLocalhost) {
        SecurityConfig config = new SecurityConfig(new RestAuthenticationEntryPoint(new ObjectMapper()));
        ReflectionTestUtils.setField(config, "allowedOrigins", allowedOrigins);
        ReflectionTestUtils.setField(config, "allowLocalhost", allowLocalhost);
        return config;
    }

    private static MockMvc mockMvc(SecurityConfig config) {
        CorsConfigurationSource source = config.corsConfigurationSource();
        return MockMvcBuilders.standaloneSetup(new ProbeController())
                .addFilters(new CorsFilter(source))
                .build();
    }

    private static org.springframework.test.web.servlet.ResultActions preflight(MockMvc mvc, String origin)
            throws Exception {
        return mvc.perform(options("/api/v1/probe")
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"));
    }

    @RestController
    static class ProbeController {

        @GetMapping("/api/v1/probe")
        Map<String, String> probe() {
            return Map.of("status", "ok");
        }
    }
}
