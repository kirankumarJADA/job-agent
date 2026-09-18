package com.personal.jobagent.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Machine-to-machine authentication for worker -> backend calls
 * (POST /api/v1/automation/events).
 *
 * Fail-closed semantics:
 * - When {@code app.worker-event-token} is blank (local development), this
 *   filter is a no-op and the endpoint keeps requiring an authenticated
 *   browser session (+CSRF) exactly as before.
 * - When the token IS configured (production), the events endpoint requires
 *   a matching `Authorization: Bearer <token>`: requests without it, or with
 *   a wrong one, are rejected 401 here and never fall through to session
 *   authentication — this also removes the cookie-CSRF surface on that path
 *   (the CSRF exemption for it is only activated together with the token in
 *   SecurityConfig).
 * - The comparison is constant-time; the token value is never logged.
 */
public class WorkerEventTokenFilter extends OncePerRequestFilter {

    static final String EVENTS_PATH = "/api/v1/automation/events";

    private final String expectedToken;

    public WorkerEventTokenFilter(@Value("${app.worker-event-token:}") String expectedToken) {
        this.expectedToken = expectedToken == null ? "" : expectedToken;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!EVENTS_PATH.equals(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }
        String header = request.getHeader("Authorization");
        boolean bearer = header != null && header.startsWith("Bearer ");
        if (bearer && !expectedToken.isBlank() && constantTimeEquals(expectedToken, header.substring(7).trim())) {
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    "worker", null, List.of(new SimpleGrantedAuthority("ROLE_WORKER")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
            return;
        }
        if (expectedToken.isBlank() && !bearer) {
            // Local development: keep the verified session + CSRF behavior.
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"invalid or missing worker token\"}");
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
}
