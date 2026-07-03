package com.epi.validator.model;

import com.fasterxml.jackson.annotation.JsonValue;

/** Normalized issue severity, ordered most severe first. */
public enum IssueSeverity {
    FATAL("fatal"),
    ERROR("error"),
    WARNING("warning"),
    INFORMATION("information");

    private final String wireValue;

    IssueSeverity(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }
}
