package com.epi.validator.web;

import com.epi.validator.audit.TraceIdFilter;
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

import java.util.List;

/** The regulated pipeline gate — the primary API of this service. */
@RestController
@RequestMapping("/api/v1/epi")
@Tag(name = "ePI validation gate")
public class ValidationController {

    private final ValidationService validationService;

    public ValidationController(ValidationService validationService) {
        this.validationService = validationService;
    }

    @Operation(
            summary = "Validate an ePI document bundle (regulated pipeline gate)",
            description = """
                    Returns HTTP 200 whenever validation executed, regardless of verdict — gate on the \
                    envelope's `verdict`, never on HTTP status. Production pipeline callers SHOULD pass \
                    epiType explicitly and use validationMode=gate; epiType=auto is for exploratory \
                    validation, diagnostics, demos, and malformed inbound triage.""")
    @PostMapping(
            value = "/validate",
            consumes = {"application/fhir+json", MediaType.APPLICATION_JSON_VALUE,
                    "application/fhir+xml", MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ValidationResponse validate(
            @RequestBody byte[] body,
            @Parameter(description = "exploratory | gate (default from server config)")
            @RequestParam(name = "validationMode", required = false) String validationMode,
            @Parameter(description = "1 | 2 | 3 | auto — auto is rejected in gate mode")
            @RequestParam(name = "epiType", required = false, defaultValue = "auto") String epiType,
            @Parameter(description = "IG configuration id (default from server config)")
            @RequestParam(name = "igVersion", required = false) String igVersion,
            @Parameter(description = "Explicit profile canonical(s); restricted by mode (profile-override config)")
            @RequestParam(name = "profile", required = false) List<String> profile,
            @RequestParam(name = "includeOperationOutcome", required = false, defaultValue = "true")
            boolean includeOperationOutcome,
            HttpServletRequest request) {
        return validationService.validate(new ValidationService.Request(
                body,
                request.getContentType(),
                validationMode,
                epiType,
                igVersion,
                profile,
                includeOperationOutcome,
                TraceIdFilter.current(request)));
    }
}
