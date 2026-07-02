package com.epi.validator.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Audit evidence for one validation run: exactly which build, packages, and policy packs
 * judged exactly which input.
 */
public record AuditInfo(
        @Schema(description = "SHA-256 of the immutable build manifest (see GET /api/v1/epi/manifest)")
        String manifestSha256,
        String hapiVersion,
        IgPackageRef igPackage,
        List<PolicyPackRef> policyPacks,
        String warningPolicyMode,
        ValidationMode validationMode,
        InputRef input,
        @Schema(description = "SHA-256 of the returned OperationOutcome JSON encoding")
        String operationOutcomeSha256) {

    public record IgPackageRef(String id, String version, String sha256) {
    }

    public record PolicyPackRef(String id, String version) {
    }

    public record InputRef(
            @Schema(description = "SHA-256 of the raw request body bytes (post gzip inflation)")
            String sha256,
            String contentType,
            long sizeBytes) {
    }
}
