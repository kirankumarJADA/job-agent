package com.personal.jobagent.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Value("${app.cors.allowed-origins:http://localhost:5173}")
    private List<String> allowedOrigins;

    /**
     * When false, loopback origins are removed from the effective allowlist
     * even if they are present in {@code app.cors.allowed-origins}. Defaults
     * to true for local development; the prod profile sets it to false so a
     * leftover {@code http://localhost:5173} in APP_CORS_ALLOWED_ORIGINS can
     * never be trusted in production.
     */
    @Value("${app.cors.allow-localhost:true}")
    private boolean allowLocalhost;

    @Value("${app.csrf.cookie-same-site:Lax}")
    private String csrfSameSite;

    @Value("${app.csrf.cookie-secure:false}")
    private boolean csrfSecure;

    @Value("${app.worker-event-token:}")
    private String workerEventToken;

    private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;
    private final FirebaseTokenVerifier firebaseTokenVerifier;
    private final UserRepository userRepository;

    public SecurityConfig(RestAuthenticationEntryPoint restAuthenticationEntryPoint,
                          FirebaseTokenVerifier firebaseTokenVerifier,
                          UserRepository userRepository) {
        this.restAuthenticationEntryPoint = restAuthenticationEntryPoint;
        this.firebaseTokenVerifier = firebaseTokenVerifier;
        this.userRepository = userRepository;
    }

    /**
     * Endpoints that establish a session from a credential supplied in the
     * request body, and therefore cannot present a CSRF token by construction.
     *
     * <p>CSRF protects an <em>existing</em> authenticated session from being
     * abused by another origin; these two endpoints are what create that
     * session in the first place, and possession of the credential in the body
     * is itself the authentication. Only a pre-authentication request can reach
     * them, and neither mutates existing user data.
     */
    private static final String[] SESSION_ESTABLISHING_PATHS = {
            "/api/v1/auth/login",
            "/api/v1/auth/firebase/session"
    };

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
                        customizer.ignoringRequestMatchers(SESSION_ESTABLISHING_PATHS);
                    } else {
                        // Token mode: /automation/events is bearer-authenticated
                        // (no cookies involved), so CSRF does not apply there.
                        customizer.ignoringRequestMatchers(
                                "/api/v1/auth/login",
                                "/api/v1/auth/firebase/session",
                                WorkerEventTokenFilter.EVENTS_PATH);
                    }
                })
                .addFilterBefore(new WorkerEventTokenFilter(workerEventToken), CsrfFilter.class)
                // Firebase ID tokens supplied as `Authorization: Bearer <token>`
                // authenticate the request directly. Registered before the
                // username/password filter so the standard Authentication object
                // (and therefore the authorization rules below) is populated. It
                // only authenticates already-linked accounts; new accounts are
                // created exclusively by /auth/firebase/session, which enforces
                // the registration invite code. See FirebaseAuthenticationFilter.
                .addFilterBefore(
                        new FirebaseAuthenticationFilter(firebaseTokenVerifier, userRepository, securityContextRepository()),
                        org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
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
                                "/api/v1/auth/login",
                                "/api/v1/auth/firebase/session",
                                // Public, non-sensitive: whether an invite code is
                                // required and whether registration is open. Lets
                                // the sign-up form label itself honestly.
                                "/api/v1/auth/registration-policy"
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
        configuration.setAllowedOrigins(effectiveAllowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    /**
     * Resolves the origins Spring will actually trust. In production
     * ({@code app.cors.allow-localhost=false}) loopback origins are dropped,
     * so a dev origin accidentally left in {@code APP_CORS_ALLOWED_ORIGINS}
     * is ignored instead of being silently accepted with credentials.
     */
    List<String> effectiveAllowedOrigins() {
        if (allowLocalhost) {
            return List.copyOf(allowedOrigins);
        }
        return rejectLoopbackOrigins(allowedOrigins);
    }

    static List<String> rejectLoopbackOrigins(List<String> origins) {
        List<String> kept = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        for (String origin : origins) {
            if (isLoopbackOrigin(origin)) {
                dropped.add(origin);
            } else {
                kept.add(origin);
            }
        }
        if (!dropped.isEmpty()) {
            log.warn("app.cors.allow-localhost=false: ignoring {} loopback CORS origin(s) {}",
                    dropped.size(), dropped);
        }
        if (kept.isEmpty()) {
            log.warn("app.cors.allow-localhost=false left no trusted CORS origins; "
                    + "cross-origin browser requests will be rejected with 403");
        }
        return List.copyOf(kept);
    }

    /**
     * True only for genuine loopback origins (localhost, *.localhost, 127.0.0.0/8,
     * ::1). Hosts that merely contain the word "localhost" elsewhere —
     * e.g. {@code https://mylocalhost.example.com} — are NOT loopback.
     */
    static boolean isLoopbackOrigin(String origin) {
        if (origin == null) {
            return false;
        }
        String value = origin.trim();
        if (value.isEmpty()) {
            return false;
        }
        String host = null;
        try {
            host = URI.create(value).getHost();
        } catch (IllegalArgumentException ignored) {
            // Not a parseable URI; fall back to a bare host[:port] check below.
        }
        if (host == null) {
            host = value.contains("://") ? null : stripPort(value);
        }
        if (host == null || host.isEmpty()) {
            return false;
        }
        String normalised = host.toLowerCase(Locale.ROOT);
        if (normalised.startsWith("[") && normalised.endsWith("]")) {
            normalised = normalised.substring(1, normalised.length() - 1);
        }
        return normalised.equals("localhost")
                || normalised.endsWith(".localhost")
                || normalised.equals("::1")
                || normalised.equals("0:0:0:0:0:0:0:1")
                || isIpv4Loopback(normalised);
    }

    private static boolean isIpv4Loopback(String host) {
        String[] parts = host.split("\\.");
        if (parts.length != 4 || !parts[0].equals("127")) {
            return false;
        }
        for (String part : parts) {
            try {
                int octet = Integer.parseInt(part);
                if (octet < 0 || octet > 255) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    private static String stripPort(String value) {
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            return end < 0 ? value : value.substring(0, end + 1);
        }
        int colon = value.indexOf(':');
        return colon < 0 ? value : value.substring(0, colon);
    }
}
