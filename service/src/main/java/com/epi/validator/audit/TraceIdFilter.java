package com.epi.validator.audit;

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
 * Correlation id for ALCOA+/Part-11-style traceability: accepted from {@code X-Trace-Id} or a
 * W3C {@code traceparent} header, else generated. Present in MDC (all log lines), the response
 * header, and the validation envelope — one id connects source document, extraction proposal,
 * candidate bundle, validation result, and approval across the pipeline.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";
    public static final String ATTRIBUTE = TraceIdFilter.class.getName() + ".traceId";
    public static final String MDC_KEY = "traceId";

    private static final Pattern TRACEPARENT =
            Pattern.compile("^[0-9a-f]{2}-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$");
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
        String traceparent = request.getHeader("traceparent");
        if (traceparent != null) {
            var matcher = TRACEPARENT.matcher(traceparent.trim().toLowerCase());
            if (matcher.matches()) {
                return matcher.group(1);
            }
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static String current(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        return value != null ? value.toString() : UUID.randomUUID().toString().replace("-", "");
    }
}
