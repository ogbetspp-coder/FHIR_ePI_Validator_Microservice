package com.epi.validator.web;

import com.epi.validator.model.ValidationResponse;
import com.epi.validator.service.ValidationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The one validation endpoint. */
@RestController
@RequestMapping("/api/v1/epi")
@Tag(name = "ePI validation")
public class ValidationController {

    private final ValidationService validationService;

    public ValidationController(ValidationService validationService) {
        this.validationService = validationService;
    }

    @Operation(
            summary = "Validate an ePI document bundle against FHIR R5 + the pinned ePI IG",
            description = """
                    Returns HTTP 200 whenever validation executed, regardless of verdict — gate on \
                    the envelope's `verdict`, never on HTTP status. Production callers should pass \
                    epiType=1|2|3 explicitly; `auto` detects the type from content but an explicit \
                    request is never downgraded by detection.""")
    @PostMapping(
            value = "/validate",
            consumes = {"application/fhir+json", MediaType.APPLICATION_JSON_VALUE,
                    "application/fhir+xml", MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ValidationResponse validate(
            @RequestBody byte[] body,
            @Parameter(description = "1 | 2 | 3 | auto — production callers pass the contracted type")
            @RequestParam(name = "epiType", required = false, defaultValue = "auto") String epiType,
            HttpServletRequest request) {
        return validationService.validate(
                body, request.getContentType(), epiType, TraceIdFilter.current(request));
    }
}
