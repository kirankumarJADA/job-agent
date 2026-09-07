package com.personal.jobagent.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.common.ApiError;
import com.personal.jobagent.common.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Without this, Spring Security 6 defaults to 403 Forbidden for
 * unauthenticated access when neither formLogin nor httpBasic is
 * configured (Http403ForbiddenEntryPoint) — but the API contract
 * (docs/contracts/api.md) specifies 401 for "no active session" on
 * protected endpoints like GET /auth/me. This entry point makes that
 * explicit rather than relying on Spring Security's default, and returns
 * the same RFC 9457 shape every other error in the API uses.
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                          AuthenticationException authException) throws IOException {
        String correlationId = String.valueOf(org.slf4j.MDC.get(CorrelationIdFilter.MDC_KEY));
        ApiError error = ApiError.of(401, "Unauthorized",
                "Authentication is required to access this resource.",
                request.getRequestURI(), correlationId);

        response.setStatus(401);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(error));
    }
}
