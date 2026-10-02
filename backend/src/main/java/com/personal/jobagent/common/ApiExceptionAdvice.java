package com.personal.jobagent.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.NoSuchElementException;

/**
 * Global fallback error mapping. Several controllers resolve the caller's
 * profile themselves and throw {@code IllegalStateException} when the account
 * has no profile — previously that surfaced as a raw 500 with a stack trace.
 * The advice maps the framework-wide runtime contract to the same RFC 9457
 * {@link ApiError} shape the controllers already return by hand:
 *
 * <ul>
 *   <li>{@link NoSuchElementException} → 404 (an id the caller does not own
 *       or that does not exist is indistinguishable by design);</li>
 *   <li>{@link IllegalArgumentException} → 400;</li>
 *   <li>{@link IllegalStateException} → 409 (state preconditions, e.g. no
 *       profile yet);</li>
 * </ul>
 *
 * <p>Anything else keeps Spring Boot's default handling — this advice only
 * standardises the shapes the codebase already commits to. Handlers that map
 * exceptions locally (ResumeAtsController) are unaffected: local handlers win.
 */
@RestControllerAdvice
public class ApiExceptionAdvice {

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiError> notFound(NoSuchElementException e, HttpServletRequest request) {
        return ResponseEntity.status(404).body(ApiError.of(404, "Not Found",
                "The referenced resource does not exist", request.getRequestURI(), correlationId()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> badRequest(IllegalArgumentException e, HttpServletRequest request) {
        return ResponseEntity.status(400).body(ApiError.of(400, "Bad Request",
                safeDetail(e), request.getRequestURI(), correlationId()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> conflict(IllegalStateException e, HttpServletRequest request) {
        return ResponseEntity.status(409).body(ApiError.of(409, "Conflict",
                safeDetail(e), request.getRequestURI(), correlationId()));
    }

    private String safeDetail(RuntimeException e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? "Request could not be completed" : message;
    }

    private String correlationId() {
        return String.valueOf(MDC.get(CorrelationIdFilter.MDC_KEY));
    }
}
