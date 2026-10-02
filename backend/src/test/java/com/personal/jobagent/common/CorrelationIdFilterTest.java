package com.personal.jobagent.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Caller-supplied correlation ids land in every structured log line, so they
 * must be constrained: control characters (log forging) and oversized values
 * are dropped for a freshly minted id.
 */
class CorrelationIdFilterTest {

    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final HttpServletResponse response = mock(HttpServletResponse.class);
    private final FilterChain chain = mock(FilterChain.class);
    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    private String echoedHeader() throws Exception {
        // The filter clears the MDC in its finally block, so capture the value
        // at the moment the chain runs.
        final String[] captured = new String[1];
        org.mockito.Mockito.doAnswer(inv -> {
            captured[0] = MDC.get(CorrelationIdFilter.MDC_KEY);
            return null;
        }).when(chain).doFilter(request, response);
        filter.doFilterInternal(request, response, chain);
        return captured[0];
    }

    @Test
    void aWellFormedIdIsAcceptedAndEchoed() throws Exception {
        when(request.getHeader(CorrelationIdFilter.HEADER_NAME)).thenReturn("0190ab12-3456-7abc-8def-0123456789ab");
        String value = echoedHeader();
        assertThat(value).isEqualTo("0190ab12-3456-7abc-8def-0123456789ab");
        verify(response).setHeader(anyString(), anyString());
    }

    @Test
    void newlinesAndControlCharactersAreRejectedNotEchoed() throws Exception {
        when(request.getHeader(CorrelationIdFilter.HEADER_NAME)).thenReturn("abc\nEVIL: injected=true");
        String value = echoedHeader();
        // A minted UUIDv7 replaces the forged value (36 chars, safe alphabet).
        assertThat(value).doesNotContain("EVIL").doesNotContain("\n").hasSize(36);
    }

    @Test
    void oversizedValuesAreRejected() throws Exception {
        when(request.getHeader(CorrelationIdFilter.HEADER_NAME)).thenReturn("x".repeat(200));
        String value = echoedHeader();
        assertThat(value).hasSize(36).doesNotContain("xxx");
    }

    @Test
    void aMissingHeaderMintsAVersion7Uuid() throws Exception {
        when(request.getHeader(CorrelationIdFilter.HEADER_NAME)).thenReturn(null);
        String value = echoedHeader();
        assertThat(value).hasSize(36);
        assertThat(UuidV7.generate().toString().charAt(14)).isEqualTo(value.charAt(14));
    }
}
