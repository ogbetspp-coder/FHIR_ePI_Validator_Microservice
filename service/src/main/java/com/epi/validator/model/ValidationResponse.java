package com.epi.validator.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * The validation envelope returned by {@code POST /api/v1/epi/validate} — the contract the
 * pipeline gates on. Never gate on HTTP status: this endpoint returns 200 whenever validation
 * executed, regardless of verdict.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ValidationResponse(
        Verdict verdict,
        ValidationMode validationMode,
        @Schema(description = "IG configuration id the bundle was validated against (e.g. 1.0.0)")
        String igVersion,
        @Schema(description = "Caller's contract intent: 1 | 2 | 3 | auto")
        String requestedEpiType,
        @Schema(description = "Type inferred from bundle contents — diagnostic only, never selects the gate")
        EpiType detectedEpiType,
        @Schema(description = "Type that actually drove profile selection and type rules")
        EpiType effectiveEpiType,
        List<String> profilesValidatedAgainst,
        ValidationStats stats,
        @Schema(description = "Normalized issues ordered fatal, error, warning, information")
        List<NormalizedIssue> issues,
        @Schema(description = "Correlation id; propagate it through the pipeline (accepted inbound via X-Trace-Id)")
        String traceId,
        AuditInfo audit,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(description = "Full FHIR OperationOutcome; omitted when includeOperationOutcome=false")
        JsonNode operationOutcome) {
}
