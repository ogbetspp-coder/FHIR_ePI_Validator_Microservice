package com.epi.validator.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The validation layer an issue originated from. Keeping these categories distinct is a design
 * requirement: base-FHIR failures, ePI IG profile failures, and deterministic policy failures
 * must never blur together.
 */
public enum IssueLayer {
    /** Layer 1: parse / well-formedness. */
    PARSER("parser"),
    /** Layer 2: FHIR R5 base structural validation. */
    FHIR("fhir"),
    /** Layer 3: HL7 ePI IG profile validation. */
    IG("ig"),
    /** Layers 4-5: product / customer policy packs. */
    POLICY("policy");

    private final String wireValue;

    IssueLayer(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }
}
