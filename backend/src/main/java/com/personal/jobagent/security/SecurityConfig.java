package com.personal.jobagent.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Production hardening (deployment pass):
 * - CSRF cookie gets SameSite/Secure from configuration (SameSite=None; Secure
 *   in prod so the cross-origin Vercel SPA can read it; Lax default locally).
 * - Worker -> backend event calls authenticate via WorkerEventTokenFilter
 *   (no-op when app.worker-event-token is unset).
 * - /api/v1/auth/login CSRF exemption: unchanged from the verified P1-b
 *   decision (single-user personal tool; a pre-login request cannot present
 *   a CSRF cookie anyway, so requiring one would only break login).
 */

/**
 * Real session auth + CSRF, replacing P1-a's permitAll placeholder. Built
 * against the P1-a CORS configuration verified working in a real browser —
 * that part is UNCHANGED from P1-a, only the authorization rules and CSRF
 * handling are new for P1-b.
 *
 * CSRF-for-SPA specifics (flagged clearly — this is the part most likely to
 * need adjustment during real browser verification, since it can't be
 * exercised in this sandbox):
 * - CookieCsrfTokenRepository.withHttpOnlyFalse() so the frontend's JS can
 *   read the XSRF-TOKEN cookie and echo it back in a header.
 * - /api/v1/auth/login is exempted from CSRF: there is no session yet to
 *   protect at that point (CSRF protects an EXISTING authenticated session
 *   from being abused; login is what creates that session). This is a
 *   standard, common pattern, not a gap — but "login CSRF" as a named
 *   attack class does exist for apps where creating a session matters even
 *   pre-authentication; judged acceptable here given this is a personal,
 *   single-user tool, not a multi-tenant app where tricking someone into
 *   authenticating as an attacker's account is meaningful.
 * - Every OTHER mutating endpoint (logout, purge, and P1-d's CRUD once it
 *   lands) requires the CSRF header — unverified in a real browser from
 *   this sandbox; part of what needs manual confirmation.
 */
@Configuration
public class SecurityConfig {

    @Value("${app.cors.allowed-origins:http://localhost:5173}")
    private List<String> allowedOrigins;

    @Value("${app.csrf.cookie-same-site:Lax}")
    private String csrfSameSite;

    @Value("${app.csrf.cookie-secure:false}")
    private boolean csrfSecure;

    @Value("${app.worker-event-token:}")
    private String workerEventToken;

    private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;

    public SecurityConfig(RestAuthenticationEntryPoint restAuthenticationEntryPoint) {
        this.restAuthenticationEntryPoint = restAuthenticationEntryPoint;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookieCustomizer(builder -> builder
                .sameSite(csrfSameSite)
                .secure(csrfSecure));
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> {
                    var customizer = csrf
                            .csrfTokenRepository(csrfRepository)
                            .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler());
                    if (workerEventToken == null || workerEventToken.isBlank()) {
                        customizer.ignoringRequestMatchers("/api/v1/auth/login");
                    } else {
                        // Token mode: /automation/events is bearer-authenticated
                        // (no cookies involved), so CSRF does not apply there.
                        customizer.ignoringRequestMatchers("/api/v1/auth/login", WorkerEventTokenFilter.EVENTS_PATH);
                    }
                })
                .addFilterBefore(new WorkerEventTokenFilter(workerEventToken), CsrfFilter.class)
                // Feature 8 live-verification fix: without this filter the
                // deferred CsrfToken is never materialized, so the XSRF-TOKEN
                // cookie is never written and EVERY mutating SPA call 403s
                // (reproduced via curl with a valid session). See
                // CsrfCookieFilter's javadoc.
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                .exceptionHandling(ex -> ex.authenticationEntryPoint(restAuthenticationEntryPoint))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/v1/system/health",
                                "/actuator/**",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/api/v1/auth/login"
                        ).permitAll()
                        .anyRequest().authenticated()
                );
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // Matches the parameters used for the V003 seed hash (m=19456,
        // t=2, p=1) — Argon2PasswordEncoder.matches() re-derives using
        // whatever parameters are ENCODED in the target hash string itself
        // (PHC format is self-describing), so this factory's own defaults
        // only govern hashes THIS instance creates going forward, not
        // verification of the seeded one. Both are Argon2id per RFC 9106,
        // so cross-implementation verification (this encoder checking a
        // hash generated by a different Argon2id implementation during
        // migration authoring) is expected to work — see V003's migration
        // comment for what was and wasn't independently verified.
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public org.springframework.security.web.context.SecurityContextRepository securityContextRepository() {
        // Explicit bean so AuthController can save the authenticated context
        // into the HTTP session after manual authentication. This matches
        // (is a member of) Spring Security 6's default delegating
        // repository used for loading on subsequent requests, so no
        // further .securityContext(...) override is needed on HttpSecurity
        // itself.
        return new org.springframework.security.web.context.HttpSessionSecurityContextRepository();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
