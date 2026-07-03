package com.epi.validator.model;

/** What validator am I talking to: built once at startup, echoed in every response. */
public record ValidatorInfo(
        String fhirVersion,
        String hapiVersion,
        String igPackage,
        String igVersion) {
}
