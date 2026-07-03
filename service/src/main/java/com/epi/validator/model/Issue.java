package com.epi.validator.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/** One validation defect, flattened for the repair agent. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Issue(
        @Schema(description = "fatal | error | warning | information")
        IssueSeverity severity,
        @Schema(description = "hapi-validator | simple-check | clinical-profile | parser")
        String source,
        @Schema(description = "Validator message id or check rule id (e.g. EPI-TYPE-001, EPI-CUD-PROFILE)")
        String ruleId,
        @Schema(description = "Human/LLM-readable defect message")
        String message,
        @Schema(description = "FHIRPath-style location, e.g. Bundle.entry[0].resource.section[3]")
        String fhirPath,
        @Schema(description = "1-based line in the submitted raw body, when available")
        Integer line,
        @Schema(description = "1-based column in the submitted raw body, when available")
        Integer column) {

    public static final String SOURCE_VALIDATOR = "hapi-validator";
    public static final String SOURCE_SIMPLE_CHECK = "simple-check";
    public static final String SOURCE_CLINICAL_PROFILE = "clinical-profile";
    public static final String SOURCE_PARSER = "parser";

    public boolean isError() {
        return severity == IssueSeverity.FATAL || severity == IssueSeverity.ERROR;
    }
}
