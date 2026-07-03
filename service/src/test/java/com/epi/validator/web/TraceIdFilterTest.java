package com.epi.validator.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    private String resolvedTraceId(String xTraceId, String traceparent) throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getHeader(TraceIdFilter.HEADER)).thenReturn(xTraceId);
        when(request.getHeader("traceparent")).thenReturn(traceparent);
        filter.doFilterInternal(request, response, mock(FilterChain.class));
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(response).setHeader(eq(TraceIdFilter.HEADER), captor.capture());
        return captor.getValue();
    }

    @Test
    void validTraceIdIsPassedThrough() throws Exception {
        assertThat(resolvedTraceId("pipeline-run_42.1", null)).isEqualTo("pipeline-run_42.1");
    }

    @Test
    void crlfInjectionIsRejectedAndReplacedWithGeneratedId() throws Exception {
        String traceId = resolvedTraceId("abc\r\nInjected-Header: evil", null);
        // A header value containing CRLF (or spaces/colons) fails the safe-id regex -> generated id.
        assertThat(traceId).doesNotContain("\r").doesNotContain("\n").doesNotContain("Injected");
        assertThat(traceId).matches("[0-9a-f]{32}");
    }

    @Test
    void oversizedTraceIdIsRejected() throws Exception {
        String traceId = resolvedTraceId("x".repeat(200), null);
        assertThat(traceId).matches("[0-9a-f]{32}"); // >128 chars fails the regex
    }

    @Test
    void w3cTraceparentTraceIdIsExtracted() throws Exception {
        String traceId = resolvedTraceId(null, "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        assertThat(traceId).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    }
}
