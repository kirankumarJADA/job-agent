package com.personal.jobagent.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Reads X-Correlation-ID from the incoming request if present (so a caller
 * can thread a correlation id through), otherwise mints a new UUIDv7. Puts
 * it in MDC so every log line for this request carries correlation_id
 * (see logback-spring.xml), and echoes it back as a response header so the
 * frontend/dashboard can surface it for support/debugging.
 *
 * This is the single seam every module's logging depends on — do not bypass
 * it by writing to MDC directly elsewhere.
 */
@Component
@Order(Integer.MIN_VALUE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Correlation-ID";
    public static final String MDC_KEY = "correlation_id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String correlationId = request.getHeader(HEADER_NAME);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UuidV7.generate().toString();
        }

        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER_NAME, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
