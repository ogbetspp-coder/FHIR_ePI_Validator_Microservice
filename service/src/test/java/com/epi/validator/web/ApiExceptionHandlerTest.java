package com.epi.validator.web;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** The failure-to-HTTP contract, proven without booting the validator. */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();
    private final HttpServletRequest request = mock(HttpServletRequest.class);

    @Test
    void apiExceptionKeepsItsStatus() {
        ResponseEntity<Map<String, Object>> response = handler.apiException(
                new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "too big"), request);
        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(response.getBody()).containsEntry("status", 413).containsEntry("message", "too big");
        assertThat(response.getBody().get("traceId")).isNotNull();
    }

    @Test
    void springWebExceptionsKeepTheirStatusNot500() {
        // 404/405/415 arrive as ErrorResponse subtypes; they must not be flattened to 500.
        ResponseEntity<Map<String, Object>> response = handler.unexpected(
                new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED, "nope"), request);
        assertThat(response.getStatusCode().value()).isEqualTo(405);
    }

    @Test
    void genuinelyUnexpectedErrorsBecome500() {
        ResponseEntity<Map<String, Object>> response = handler.unexpected(
                new IllegalStateException("boom"), request);
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        // Never leak internals in the message
        assertThat(response.getBody().get("message").toString()).doesNotContain("boom");
    }
}
