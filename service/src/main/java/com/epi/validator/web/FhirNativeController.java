package com.epi.validator.web;

import ca.uhn.fhir.context.FhirContext;
import com.epi.validator.audit.TraceIdFilter;
import com.epi.validator.service.ValidationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.hl7.fhir.r5.model.OperationOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * FHIR interop/debug endpoint only. Not the regulated pipeline gate.
 *
 * <p>Per FHIR operation semantics this returns HTTP 200 with an OperationOutcome even when
 * validation found errors — pipeline code must gate on {@code POST /api/v1/epi/validate}'s
 * envelope, never on this endpoint's HTTP status.
 */
@RestController
@ConditionalOnProperty(prefix = "epi.validation", name = "fhir-native-endpoint-enabled", havingValue = "true")
@Tag(name = "FHIR interop", description = "FHIR interop/debug endpoint only. Not the regulated pipeline gate.")
public class FhirNativeController {

    private static final String FHIR_JSON = "application/fhir+json";

    private final ValidationService validationService;
    private final FhirContext fhirContext;

    public FhirNativeController(ValidationService validationService, FhirContext fhirContext) {
        this.validationService = validationService;
        this.fhirContext = fhirContext;
    }

    @Operation(
            summary = "FHIR-native $validate (interop/debug only — not the regulated pipeline gate)",
            description = "Returns HTTP 200 + OperationOutcome even when issues are errors, per FHIR semantics.")
    @PostMapping(
            value = "/fhir/$validate",
            consumes = {FHIR_JSON, MediaType.APPLICATION_JSON_VALUE,
                    "application/fhir+xml", MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE},
            produces = FHIR_JSON)
    public ResponseEntity<String> validate(
            @RequestBody byte[] body,
            @RequestParam(name = "profile", required = false) List<String> profile,
            @RequestParam(name = "igVersion", required = false) String igVersion,
            HttpServletRequest request) {
        OperationOutcome outcome = validationService.validateToOperationOutcome(
                body, request.getContentType(), profile, igVersion, TraceIdFilter.current(request));
        String encoded = fhirContext.newJsonParser().setPrettyPrint(true).encodeResourceToString(outcome);
        return ResponseEntity.ok().header("Content-Type", FHIR_JSON).body(encoded);
    }
}
