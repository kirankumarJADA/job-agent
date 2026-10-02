package com.personal.jobagent.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The global advice converts the codebase-wide runtime contract into the
 * RFC 9457 error shape the frontend already parses — the profile-less 500s
 * become honest 409s without leaking stack traces.
 */
class ApiExceptionAdviceTest {

    private final ApiExceptionAdvice advice = new ApiExceptionAdvice();

    private final jakarta.servlet.http.HttpServletRequest request;

    {
        request = org.mockito.Mockito.mock(jakarta.servlet.http.HttpServletRequest.class);
        org.mockito.Mockito.when(request.getRequestURI()).thenReturn("/api/v1/test");
    }

    @Test
    void missingElementsMapToNotFound() {
        ResponseEntity<ApiError> response =
                advice.notFound(new NoSuchElementException("No application with that id"), request);
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().title()).isEqualTo("Not Found");
    }

    @Test
    void illegalArgumentsMapToBadRequestAndKeepTheMessage() {
        ResponseEntity<ApiError> response =
                advice.badRequest(new IllegalArgumentException("profile, application, and job correlation are required"), request);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().detail()).contains("correlation are required");
    }

    @Test
    void illegalStatesMapToConflict() {
        ResponseEntity<ApiError> response =
                advice.conflict(new IllegalStateException("No profile exists for the current user"), request);
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().detail()).contains("No profile exists");
    }
}
