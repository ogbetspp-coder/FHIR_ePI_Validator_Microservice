package com.epi.validator.web;

import org.springframework.http.HttpStatus;

/** Request-level failure with a specific HTTP status (422 misuse, 503 not ready, ...). */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
