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
import java.util.regex.Pattern;

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

    public static final String EVENTS_PATH = "/api/v1/automation/events";
    public static final String WORKER_AUTH_ATTRIBUTE =
            WorkerEventTokenFilter.class.getName() + ".authenticated";

    private static final String CLAIM_NEXT_PATH = "/api/v1/automation/plans/claim-next";
    private static final String RECOVER_STALE_PATH = "/api/v1/automation/plans/recover-stale";

    private static final Pattern WORKER_PLAN_PATH = Pattern.compile(
            "^/api/v1/automation/plans/[0-9a-fA-F-]{36}/"
                    + "(claim|heartbeat|steps|complete|package|artifacts/[^/]+)$"
    );

    private final String expectedToken;

    public WorkerEventTokenFilter(@Value("${app.worker-event-token:}") String expectedToken) {
        this.expectedToken = expectedToken == null ? "" : expectedToken;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean workerOnly = isWorkerOnlyPath(path);
        boolean workerCapable = workerOnly || WORKER_PLAN_PATH.matcher(path).matches();

        if (!workerCapable) {
            filterChain.doFilter(request, response);
            return;
        }

        String header = request.getHeader("Authorization");
        boolean bearer = header != null && header.startsWith("Bearer ");
        boolean validWorker = bearer
                && !expectedToken.isBlank()
                && constantTimeEquals(expectedToken, header.substring(7).trim());

        if (validWorker) {
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            "worker", null, List.of(new SimpleGrantedAuthority("ROLE_WORKER")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            request.setAttribute(WORKER_AUTH_ATTRIBUTE, Boolean.TRUE);
            filterChain.doFilter(request, response);
            return;
        }

        if (expectedToken.isBlank() && !bearer) {
            // Local development: keep the existing verified-session behavior.
            filterChain.doFilter(request, response);
            return;
        }

        if (workerOnly) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"invalid or missing worker token\"}");
            return;
        }

        // Dual-use plan endpoints may still be accessed by a normal
        // authenticated user session when a worker token is not valid/present.
        filterChain.doFilter(request, response);
    }

    private boolean isWorkerOnlyPath(String path) {
        return EVENTS_PATH.equals(path)
                || CLAIM_NEXT_PATH.equals(path)
                || RECOVER_STALE_PATH.equals(path);
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
}
