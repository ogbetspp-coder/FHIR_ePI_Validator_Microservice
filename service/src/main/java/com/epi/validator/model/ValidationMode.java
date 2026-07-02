package com.epi.validator.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Validation mode.
 *
 * <p>{@code GATE} is the regulated pipeline path: the ePI type must be requested explicitly
 * (never {@code auto}), profile overrides are rejected unless configuration allows them, the
 * warning policy is enforced, and the audit block is always included.
 *
 * <p>{@code EXPLORATORY} is for agent debugging, workbench preview, demos, and malformed
 * inbound triage. Production pipeline callers SHOULD use {@code gate}.
 */
public enum ValidationMode {
    EXPLORATORY("exploratory"),
    GATE("gate");

    private final String wireValue;

    ValidationMode(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    public static ValidationMode fromWire(String value) {
        for (ValidationMode m : values()) {
            if (m.wireValue.equalsIgnoreCase(value)) {
                return m;
            }
        }
        throw new IllegalArgumentException("Unknown validationMode '" + value + "' (expected exploratory|gate)");
    }
}
