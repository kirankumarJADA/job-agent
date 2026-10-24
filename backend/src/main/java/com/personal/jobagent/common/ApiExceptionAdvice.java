package com.personal.jobagent.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import java.util.Map;
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

    /** The only multipart endpoint (MailgunInboundController.PATH; kept literal so common stays a leaf). */
    private static final String MAILGUN_INBOUND_PATH = "/api/v1/managed-mail/inbound/mailgun";

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

    /**
     * A multipart body that could not be read (over the size limits, or
     * malformed). Only the Mailgun inbound webhook accepts multipart; for it the
     * answer is 406, which tells Mailgun not to retry a delivery that can never
     * be accepted (for example one carrying a large attachment). Anything else
     * keeps the conventional 413 / 400.
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<?> unreadableMultipart(MultipartException e, HttpServletRequest request) {
        if (MAILGUN_INBOUND_PATH.equals(request.getRequestURI())) {
            return ResponseEntity.status(406).body(Map.of("status", "unprocessable"));
        }
        int status = e instanceof MaxUploadSizeExceededException ? 413 : 400;
        return ResponseEntity.status(status).body(ApiError.of(status,
                status == 413 ? "Payload Too Large" : "Bad Request",
                "The uploaded content could not be read", request.getRequestURI(), correlationId()));
    }

    private String safeDetail(RuntimeException e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? "Request could not be completed" : message;
    }

    private String correlationId() {
        return String.valueOf(MDC.get(CorrelationIdFilter.MDC_KEY));
    }
}
