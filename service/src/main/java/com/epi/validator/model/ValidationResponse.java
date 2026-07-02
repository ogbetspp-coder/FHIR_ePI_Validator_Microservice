package com.epi.validator.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * The validation envelope of {@code POST /api/v1/epi/validate}. Gate on {@code verdict} —
 * this endpoint returns HTTP 200 whenever validation executed, regardless of outcome.
 */
public record ValidationResponse(
        Verdict verdict,
        @Schema(description = "Caller's contract intent: 1 | 2 | 3 | auto")
        String requestedEpiType,
        @Schema(description = "Type inferred from bundle contents — diagnostic only")
        EpiType detectedEpiType,
        @Schema(description = "Type that drove the type checks: requested unless auto, then detected. "
                + "An explicit request is never downgraded by detection.")
        EpiType effectiveEpiType,
        List<String> profilesValidatedAgainst,
        @Schema(description = "Defects ordered fatal, error, warning, information")
        List<Issue> issues,
        @Schema(description = "Full FHIR OperationOutcome for the run")
        JsonNode operationOutcome,
        @Schema(description = "SHA-256 of the raw request body bytes (post gzip inflation)")
        String inputSha256,
        @Schema(description = "Correlation id (accepted inbound via X-Trace-Id, else generated)")
        String traceId,
        ValidatorInfo validatorInfo) {
}
