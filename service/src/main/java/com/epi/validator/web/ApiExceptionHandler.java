package com.epi.validator.web;

import com.epi.validator.audit.TraceIdFilter;
import com.epi.validator.model.ValidationResponse;
import com.epi.validator.service.ValidationExecutor;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** Maps service failures onto the HTTP contract (400/413/415/422/429/500/503). */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> apiException(ApiException e, HttpServletRequest request) {
        return problem(e.status(), e.getMessage(), request);
    }

    @ExceptionHandler(UnparseableRequestException.class)
    public ResponseEntity<ValidationResponse> unparseable(UnparseableRequestException e) {
        return ResponseEntity.badRequest().body(e.envelope());
    }

    @ExceptionHandler(GzipRequestFilter.PayloadTooLargeException.class)
    public ResponseEntity<Map<String, Object>> tooLarge(GzipRequestFilter.PayloadTooLargeException e,
                                                        HttpServletRequest request) {
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, e.getMessage(), request);
    }

    @ExceptionHandler(ValidationExecutor.BulkheadFullException.class)
    public ResponseEntity<Map<String, Object>> bulkheadFull(ValidationExecutor.BulkheadFullException e,
                                                            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "1")
                .body(problemBody(HttpStatus.TOO_MANY_REQUESTS, e.getMessage(), request));
    }

    @ExceptionHandler(ValidationExecutor.ValidationTimeoutException.class)
    public ResponseEntity<Map<String, Object>> timeout(ValidationExecutor.ValidationTimeoutException e,
                                                       HttpServletRequest request) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e, HttpServletRequest request) {
        log.error("Unexpected failure handling {} {}", request.getMethod(), request.getRequestURI(), e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error; see logs (traceId in response)", request);
    }

    private static ResponseEntity<Map<String, Object>> problem(HttpStatus status, String message,
                                                               HttpServletRequest request) {
        return ResponseEntity.status(status).body(problemBody(status, message, request));
    }

    private static Map<String, Object> problemBody(HttpStatus status, String message, HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        body.put("traceId", TraceIdFilter.current(request));
        return body;
    }
}
