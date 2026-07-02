package com.epi.validator.web;

import com.epi.validator.model.ValidationResponse;

/**
 * The request body could not be parsed as FHIR. Carries the full validation envelope (verdict
 * FAIL, single parser-layer issue) so the pipeline still receives its normal contract on 400.
 */
public class UnparseableRequestException extends RuntimeException {

    private final transient ValidationResponse envelope;

    public UnparseableRequestException(ValidationResponse envelope, String message) {
        super(message);
        this.envelope = envelope;
    }

    public ValidationResponse envelope() {
        return envelope;
    }
}
