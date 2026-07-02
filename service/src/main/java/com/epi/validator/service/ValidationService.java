package com.epi.validator.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ValidationOptions;
import ca.uhn.fhir.validation.ValidationResult;
import com.epi.validator.checks.SimpleDocumentChecks;
import com.epi.validator.config.EpiValidationProperties;
import com.epi.validator.engine.EpiTypeDetector;
import com.epi.validator.engine.IssueMapper;
import com.epi.validator.engine.TypeResolution;
import com.epi.validator.model.EpiType;
import com.epi.validator.model.Issue;
import com.epi.validator.model.IssueSeverity;
import com.epi.validator.model.ValidationResponse;
import com.epi.validator.model.ValidatorInfo;
import com.epi.validator.model.Verdict;
import com.epi.validator.web.ApiException;
import com.epi.validator.web.UnparseableRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.OperationOutcome;
import org.hl7.fhir.r5.model.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * One validation run: parse the raw body as FHIR R5, validate it against the pinned ePI IG
 * with the official validator engine, run the simple document/type checks, and derive the
 * verdict from the validation result — never from HTTP status.
 */
@Service
public class ValidationService {

    private final FhirContext fhirContext;
    private final FhirValidator validator;
    private final EpiValidationProperties properties;
    private final EpiTypeDetector typeDetector;
    private final SimpleDocumentChecks simpleChecks;
    private final IssueMapper issueMapper;
    private final ValidatorInfo validatorInfo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ValidationService(FhirContext fhirContext,
                             FhirValidator validator,
                             EpiValidationProperties properties,
                             EpiTypeDetector typeDetector,
                             SimpleDocumentChecks simpleChecks,
                             IssueMapper issueMapper,
                             ValidatorInfo validatorInfo) {
        this.fhirContext = fhirContext;
        this.validator = validator;
        this.properties = properties;
        this.typeDetector = typeDetector;
        this.simpleChecks = simpleChecks;
        this.issueMapper = issueMapper;
        this.validatorInfo = validatorInfo;
    }

    public ValidationResponse validate(byte[] body, String contentType, String epiTypeParam, String traceId) {
        String requestedType = normalizeRequestedType(epiTypeParam);
        String raw = new String(body, StandardCharsets.UTF_8);

        Bundle bundle = parseBundle(raw, contentType, requestedType, body, traceId);
        EpiType detected = typeDetector.detect(bundle);
        TypeResolution types = TypeResolution.resolve(requestedType, detected);

        // Validate the raw source string — never a re-serialized object — so line/column
        // locations survive for the repair loop.
        ValidationResult result = validator.validateWithResult(raw,
                new ValidationOptions().addProfile(properties.bundleProfile()));

        List<Issue> issues = new ArrayList<>();
        result.getMessages().forEach(m -> issues.add(issueMapper.map(m)));
        issues.addAll(simpleChecks.run(bundle, types));
        List<Issue> sorted = issueMapper.sortBySeverity(issues);

        OperationOutcome outcome = buildOperationOutcome(result, sorted);
        return new ValidationResponse(
                verdictOf(sorted),
                types.requested(),
                types.detected(),
                types.effective(),
                List.of(properties.bundleProfile()),
                sorted,
                toJsonNode(fhirContext.newJsonParser().encodeResourceToString(outcome)),
                sha256Hex(body),
                traceId,
                validatorInfo);
    }

    private static Verdict verdictOf(List<Issue> issues) {
        boolean hasError = issues.stream().anyMatch(Issue::isError);
        if (hasError) {
            return Verdict.FAIL;
        }
        boolean hasWarning = issues.stream().anyMatch(i -> i.severity() == IssueSeverity.WARNING);
        return hasWarning ? Verdict.PASS_WITH_WARNINGS : Verdict.PASS;
    }

    private String normalizeRequestedType(String epiTypeParam) {
        String requested = epiTypeParam == null || epiTypeParam.isBlank()
                ? TypeResolution.AUTO
                : epiTypeParam.toLowerCase(Locale.ROOT);
        if (!TypeResolution.AUTO.equals(requested)) {
            try {
                EpiType.fromWire(requested);
            } catch (IllegalArgumentException e) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
            }
        }
        return requested;
    }

    private Bundle parseBundle(String raw, String contentType, String requestedType,
                               byte[] body, String traceId) {
        boolean xml = contentType != null && contentType.toLowerCase(Locale.ROOT).contains("xml");
        IParser parser = xml ? fhirContext.newXmlParser() : fhirContext.newJsonParser();
        Resource resource;
        try {
            resource = (Resource) parser.parseResource(raw);
        } catch (DataFormatException e) {
            throw new UnparseableRequestException(
                    parserFailureEnvelope(requestedType, body, traceId, e), e.getMessage());
        }
        if (!(resource instanceof Bundle bundle)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Expected a FHIR Bundle (document), received a " + resource.fhirType());
        }
        return bundle;
    }

    private ValidationResponse parserFailureEnvelope(String requestedType, byte[] body, String traceId,
                                                     DataFormatException cause) {
        Issue issue = new Issue(IssueSeverity.FATAL, Issue.SOURCE_PARSER, "PARSE-001",
                "Request body is not parseable FHIR: " + cause.getMessage(), null, null, null);
        OperationOutcome outcome = new OperationOutcome();
        OperationOutcome.OperationOutcomeIssueComponent component = outcome.addIssue();
        component.setSeverity(OperationOutcome.IssueSeverity.FATAL);
        component.setCode(OperationOutcome.IssueType.INVALID);
        component.setDiagnostics(issue.message());
        return new ValidationResponse(
                Verdict.FAIL,
                requestedType,
                EpiType.UNKNOWN,
                EpiType.UNKNOWN,
                List.of(),
                List.of(issue),
                toJsonNode(fhirContext.newJsonParser().encodeResourceToString(outcome)),
                sha256Hex(body),
                traceId,
                validatorInfo);
    }

    private OperationOutcome buildOperationOutcome(ValidationResult result, List<Issue> issues) {
        // HAPI's OperationOutcome carries line/col extensions; append the simple-check issues
        // so the OperationOutcome covers the whole run.
        OperationOutcome outcome = (OperationOutcome) result.toOperationOutcome();
        issues.stream()
                .filter(i -> Issue.SOURCE_SIMPLE_CHECK.equals(i.source()))
                .forEach(i -> {
                    OperationOutcome.OperationOutcomeIssueComponent component = outcome.addIssue();
                    component.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                    component.setCode(OperationOutcome.IssueType.BUSINESSRULE);
                    component.setDiagnostics(i.message() + " [" + i.ruleId() + "]");
                    if (i.fhirPath() != null) {
                        component.addExpression(i.fhirPath());
                    }
                });
        return outcome;
    }

    private JsonNode toJsonNode(String encoded) {
        try {
            return objectMapper.readTree(encoded);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to embed OperationOutcome", e);
        }
    }

    static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Exposed for the info endpoint. */
    public ValidatorInfo validatorInfo() {
        return validatorInfo;
    }
}
