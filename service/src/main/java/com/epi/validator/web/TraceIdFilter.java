package com.epi.validator.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Correlation id for ALCOA+/Part-11-style traceability: accepted from the {@code X-Trace-Id}
 * header (validated against a strict allowlist) or generated. Present in MDC (all log lines), the
 * response header, and the validation envelope. One id connects source document, extraction
 * proposal, candidate bundle, validation result, and approval across the pipeline.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";
    public static final String ATTRIBUTE = TraceIdFilter.class.getName() + ".traceId";
    public static final String MDC_KEY = "traceId";

    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._-]{1,128}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = resolveTraceId(request);
        request.setAttribute(ATTRIBUTE, traceId);
        response.setHeader(HEADER, traceId);
        MDC.put(MDC_KEY, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private static String resolveTraceId(HttpServletRequest request) {
        String explicit = request.getHeader(HEADER);
        if (explicit != null && SAFE_ID.matcher(explicit).matches()) {
            return explicit;
        }
        return newTraceId();
    }

    public static String current(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        return value != null ? value.toString() : newTraceId();
    }

    private static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
