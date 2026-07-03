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

    private String resolvedTraceId(String xTraceId) throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getHeader(TraceIdFilter.HEADER)).thenReturn(xTraceId);
        filter.doFilterInternal(request, response, mock(FilterChain.class));
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(response).setHeader(eq(TraceIdFilter.HEADER), captor.capture());
        return captor.getValue();
    }

    @Test
    void validTraceIdIsPassedThrough() throws Exception {
        assertThat(resolvedTraceId("pipeline-run_42.1")).isEqualTo("pipeline-run_42.1");
    }

    @Test
    void crlfInjectionIsRejectedAndReplacedWithGeneratedId() throws Exception {
        String traceId = resolvedTraceId("abc\r\nInjected-Header: evil");
        // A header value containing CRLF (or spaces/colons) fails the safe-id regex -> generated id.
        assertThat(traceId).doesNotContain("\r").doesNotContain("\n").doesNotContain("Injected");
        assertThat(traceId).matches("[0-9a-f]{32}");
    }

    @Test
    void oversizedTraceIdIsRejected() throws Exception {
        String traceId = resolvedTraceId("x".repeat(200));
        assertThat(traceId).matches("[0-9a-f]{32}"); // >128 chars fails the regex
    }

    @Test
    void missingTraceIdIsGenerated() throws Exception {
        assertThat(resolvedTraceId(null)).matches("[0-9a-f]{32}");
    }
}
