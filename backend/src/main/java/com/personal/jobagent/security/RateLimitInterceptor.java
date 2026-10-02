package com.personal.jobagent.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.common.ApiError;
import com.personal.jobagent.common.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Applies {@link RateLimitService} buckets to the routes where abuse is
 * expensive or dangerous. Returns RFC 9457 problem-detail 429s with a
 * Retry-After hint. Requests are keyed by authenticated principal when one
 * exists and by client IP otherwise (the login bucket, where callers are by
 * definition unauthenticated).
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private final RateLimitService rateLimiter;
    private final ObjectMapper objectMapper;

    public RateLimitInterceptor(RateLimitService rateLimiter, ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String bucket = rateLimiter.bucketFor(request.getMethod(), request.getRequestURI());
        if (bucket == null) return true;

        String key = clientKey(request);
        long retryAfterSeconds = rateLimiter.checkAndRecord(bucket, key);
        if (retryAfterSeconds < 0) return true;

        log.warn("Rate limit exceeded for bucket={} key={} on {} {}", bucket, key, request.getMethod(),
                request.getRequestURI());
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setContentType("application/problem+json");
        ApiError error = ApiError.of(429, "Too Many Requests",
                "Too many requests - retry after " + retryAfterSeconds + " seconds",
                request.getRequestURI(), String.valueOf(request.getHeader(CorrelationIdFilter.HEADER_NAME)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", error.type());
        body.put("title", error.title());
        body.put("status", error.status());
        body.put("detail", error.detail());
        body.put("instance", error.instance());
        body.put("correlationId", error.correlationId());
        objectMapper.writeValue(response.getWriter(), body);
        return false;
    }

    private String clientKey(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(String.valueOf(authentication.getPrincipal()))) {
            return "u:" + authentication.getName();
        }
        return "ip:" + request.getRemoteAddr();
    }
}
