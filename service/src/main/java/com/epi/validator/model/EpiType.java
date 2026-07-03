package com.epi.validator.model;

import com.fasterxml.jackson.annotation.JsonValue;

/** ePI document type (Type 4 dynamic labels are out of scope for this service). */
public enum EpiType {
    TYPE_1("1"),
    TYPE_2("2"),
    TYPE_3("3"),
    UNKNOWN("unknown");

    private final String wireValue;

    EpiType(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    /** Parses the {@code epiType} request parameter value {@code 1|2|3}; {@code auto} is handled by the caller. */
    public static EpiType fromWire(String value) {
        for (EpiType t : values()) {
            // UNKNOWN is a detection sentinel, never a valid request value; excluding it here means
            // epiType=unknown is rejected (422) rather than silently disabling the type contract.
            if (t != UNKNOWN && t.wireValue.equals(value)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown epiType '" + value + "' (expected 1|2|3|auto)");
    }
}
