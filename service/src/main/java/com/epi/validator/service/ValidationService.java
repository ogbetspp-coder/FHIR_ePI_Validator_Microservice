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
import com.epi.validator.web.BodyDecoder;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * One validation run: parse the raw body as FHIR R5, validate it against the pinned ePI IG with
 * the official validator engine, run the simple document/type checks, and derive the verdict
 * from the validation result — never from HTTP status. The {@link FhirValidator} is a shared,
 * thread-safe singleton, so this service is safe under concurrent requests.
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
        // Decode with the document's actual charset (Content-Type / XML declaration / BOM), not a
        // hardcoded UTF-8, so a non-UTF-8 ePI is validated as written rather than corrupted.
        String raw = BodyDecoder.decode(body, contentType);

        Bundle bundle = parseOrFail(raw, contentType, requestedType, body, traceId);
        TypeResolution types = TypeResolution.resolve(requestedType, typeDetector.detect(bundle));

        // Validate the raw source string — never a re-serialized object — so line/column
        // locations survive for the repair loop.
        ValidationResult result = validator.validateWithResult(raw,
                new ValidationOptions().addProfile(properties.bundleProfile()));

        List<Issue> issues = new ArrayList<>();
        result.getMessages().forEach(m -> issues.add(issueMapper.map(m)));
        issues.addAll(simpleChecks.run(bundle, types));
        List<Issue> sorted = issueMapper.sortBySeverity(issues);

        return envelope(verdictOf(sorted), types, List.of(properties.bundleProfile()), sorted,
                appendPolicyIssues((OperationOutcome) result.toOperationOutcome(), sorted), body, traceId);
    }

    private Bundle parseOrFail(String raw, String contentType, String requestedType,
                               byte[] body, String traceId) {
        boolean xml = contentType != null && contentType.toLowerCase(Locale.ROOT).contains("xml");
        IParser parser = xml ? fhirContext.newXmlParser() : fhirContext.newJsonParser();
        Resource resource;
        try {
            resource = (Resource) parser.parseResource(raw);
        } catch (DataFormatException e) {
            Issue issue = new Issue(IssueSeverity.FATAL, Issue.SOURCE_PARSER, "PARSE-001",
                    "Request body is not parseable FHIR: " + e.getMessage(), null, null, null);
            OperationOutcome outcome = new OperationOutcome();
            outcome.addIssue()
                    .setSeverity(OperationOutcome.IssueSeverity.FATAL)
                    .setCode(OperationOutcome.IssueType.INVALID)
                    .setDiagnostics(issue.message());
            TypeResolution unknown = new TypeResolution(requestedType, EpiType.UNKNOWN, EpiType.UNKNOWN);
            throw new UnparseableRequestException(
                    envelope(Verdict.FAIL, unknown, List.of(), List.of(issue), outcome, body, traceId),
                    e.getMessage());
        }
        if (!(resource instanceof Bundle bundle)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Expected a FHIR Bundle (document), received a " + resource.fhirType());
        }
        return bundle;
    }

    /** Assembles the response envelope — the single construction point for both success and parse-failure. */
    private ValidationResponse envelope(Verdict verdict, TypeResolution types, List<String> profiles,
                                        List<Issue> issues, OperationOutcome outcome, byte[] body, String traceId) {
        return new ValidationResponse(
                verdict,
                types.requested(),
                types.detected(),
                types.effective(),
                profiles,
                issues,
                toJsonNode(fhirContext.newJsonParser().encodeResourceToString(outcome)),
                sha256Hex(body),
                traceId,
                validatorInfo);
    }

    /** Appends the simple-check issues to HAPI's OperationOutcome so it covers the whole run. */
    private static OperationOutcome appendPolicyIssues(OperationOutcome outcome, List<Issue> issues) {
        for (Issue issue : issues) {
            if (!Issue.SOURCE_SIMPLE_CHECK.equals(issue.source())) {
                continue;
            }
            OperationOutcome.OperationOutcomeIssueComponent component = outcome.addIssue()
                    .setSeverity(ooSeverity(issue.severity()))
                    .setCode(OperationOutcome.IssueType.BUSINESSRULE)
                    .setDiagnostics(issue.message() + " [" + issue.ruleId() + "]");
            if (issue.fhirPath() != null) {
                component.addExpression(issue.fhirPath());
            }
        }
        return outcome;
    }

    private static OperationOutcome.IssueSeverity ooSeverity(IssueSeverity severity) {
        return switch (severity) {
            case FATAL -> OperationOutcome.IssueSeverity.FATAL;
            case ERROR -> OperationOutcome.IssueSeverity.ERROR;
            case WARNING -> OperationOutcome.IssueSeverity.WARNING;
            case INFORMATION -> OperationOutcome.IssueSeverity.INFORMATION;
        };
    }

    private static Verdict verdictOf(List<Issue> issues) {
        if (issues.stream().anyMatch(Issue::isError)) {
            return Verdict.FAIL;
        }
        return issues.stream().anyMatch(i -> i.severity() == IssueSeverity.WARNING)
                ? Verdict.PASS_WITH_WARNINGS
                : Verdict.PASS;
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
}
