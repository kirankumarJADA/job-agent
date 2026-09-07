package com.personal.jobagent.common;

import java.net.URI;
import java.time.Instant;

/**
 * RFC 9457 "application/problem+json" error body. Every error response in
 * the API contract uses this shape (see docs/contracts/api.md conventions).
 */
public record ApiError(
        URI type,
        String title,
        int status,
        String detail,
        URI instance,
        String correlationId,
        Instant timestamp
) {

    public static ApiError of(int status, String title, String detail, String path, String correlationId) {
        return new ApiError(
                URI.create("about:blank"),
                title,
                status,
                detail,
                URI.create(path),
                correlationId,
                Instant.now()
        );
    }
}
