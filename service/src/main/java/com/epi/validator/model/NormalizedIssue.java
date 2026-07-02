package com.epi.validator.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A single validation defect, normalized across all layers into the shape the pipeline's
 * repair agent consumes.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record NormalizedIssue(
        @Schema(description = "Issue severity: fatal | error | warning | information")
        IssueSeverity severity,
        @Schema(description = "Machine code for the kind of issue (FHIR issue type or policy code)")
        String code,
        @Schema(description = "Stable rule identifier: validator message id or policy ruleId (e.g. EPI-TYPE-001)")
        String ruleId,
        @Schema(description = "Validation layer: parser | fhir | ig | policy")
        IssueLayer layer,
        Location location,
        @Schema(description = "Human/LLM-readable defect message")
        String message,
        @Schema(description = "Profile the issue was raised against, when attributable")
        String profileUrl,
        Source source,
        @Schema(description = "True when a warning matched a governed allowlist entry (controlled deviation)")
        boolean allowlisted,
        @Schema(description = "Rationale: for policy rules, why the rule exists; for allowlisted warnings, why the deviation is accepted")
        String rationale,
        @Schema(description = "Reserved for future rule-based fix hints; always null in v1")
        String suggestion) {

    /** Where the defect is located in the submitted document. */
    public record Location(
            @Schema(description = "FHIRPath-style location, e.g. Bundle.entry[0].resource.section[3]")
            String fhirPath,
            @Schema(description = "Best-effort JSON pointer; null where FHIRPath does not map cleanly (slices, choice types)")
            String jsonPointer,
            @Schema(description = "1-based line in the submitted raw body, when available")
            Integer line,
            @Schema(description = "1-based column in the submitted raw body, when available")
            Integer column) {
    }

    /** Structured provenance of the issue. */
    public record Source(
            @Schema(description = "Origin type: profile-validator | policy-pack | parser")
            String type,
            @Schema(description = "Origin id: validator engine name or policy pack id")
            String id,
            @Schema(description = "Origin version: engine version or policy pack version")
            String version) {

        public static final String TYPE_PROFILE_VALIDATOR = "profile-validator";
        public static final String TYPE_POLICY_PACK = "policy-pack";
        public static final String TYPE_PARSER = "parser";
    }
}
