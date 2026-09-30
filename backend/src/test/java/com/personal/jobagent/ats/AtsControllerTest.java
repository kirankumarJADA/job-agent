package com.personal.jobagent.ats;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** /detect contract: inspection failures are a deterministic 503, never a mock fallback. */
class AtsControllerTest {

    @Test
    void missingUrlIsABadRequest() {
        AtsController controller = new AtsController(mock(AtsAdapterRegistry.class));
        var response = controller.detectAdapter(new AtsController.DetectRequest(null), request());
        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void unknownUrlIsNotFound() {
        AtsAdapterRegistry registry = mock(AtsAdapterRegistry.class);
        when(registry.findAdapterForUrl("https://example.com/apply")).thenReturn(Optional.empty());
        AtsController controller = new AtsController(registry);
        var response = controller.detectAdapter(new AtsController.DetectRequest("https://example.com/apply"), request());
        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void inspectionFailureMapsToServiceUnavailableWithTheStableCode() {
        AtsAdapter greenhouse = mock(AtsAdapter.class);
        when(greenhouse.matchesUrl("https://job-boards.greenhouse.io/monzo/jobs/1")).thenReturn(true);
        when(greenhouse.inspectForm("https://job-boards.greenhouse.io/monzo/jobs/1"))
                .thenThrow(new IllegalStateException("GREENHOUSE_INSPECTION_UNAVAILABLE: FETCH_FAILED"));
        AtsAdapterRegistry registry = mock(AtsAdapterRegistry.class);
        when(registry.findAdapterForUrl("https://job-boards.greenhouse.io/monzo/jobs/1"))
                .thenReturn(Optional.of(greenhouse));

        AtsController controller = new AtsController(registry);
        var response = controller.detectAdapter(
                new AtsController.DetectRequest("https://job-boards.greenhouse.io/monzo/jobs/1"), request());

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(String.valueOf(response.getBody())).contains("GREENHOUSE_INSPECTION_UNAVAILABLE");
    }

    private jakarta.servlet.http.HttpServletRequest request() {
        jakarta.servlet.http.HttpServletRequest request = Mockito.mock(jakarta.servlet.http.HttpServletRequest.class);
        Mockito.when(request.getRequestURI()).thenReturn("/api/v1/ats/detect");
        return request;
    }
}
