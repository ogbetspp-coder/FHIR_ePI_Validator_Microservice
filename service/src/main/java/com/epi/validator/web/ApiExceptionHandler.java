package com.epi.validator.web;

import com.epi.validator.model.ValidationResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** Maps failures onto the HTTP contract (400/413/415/422/500). */
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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e, HttpServletRequest request) {
        // Spring web exceptions (404 no route, 405 wrong method, 415 bad media type, ...) carry
        // their own status, so preserve it instead of flattening everything to 500.
        if (e instanceof ErrorResponse errorResponse) {
            // resolve() returns null (never throws) for a non-standard code; fall through to 500.
            HttpStatus status = HttpStatus.resolve(errorResponse.getStatusCode().value());
            if (status != null) {
                return problem(status, e.getMessage(), request);
            }
        }
        log.error("Unexpected failure handling {} {}", request.getMethod(), request.getRequestURI(), e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error; see logs (traceId in response)", request);
    }

    private static ResponseEntity<Map<String, Object>> problem(HttpStatus status, String message,
                                                               HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        body.put("traceId", TraceIdFilter.current(request));
        return ResponseEntity.status(status).body(body);
    }
}
