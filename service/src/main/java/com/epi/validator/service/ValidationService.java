package com.epi.validator.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.validation.ValidationOptions;
import ca.uhn.fhir.validation.ValidationResult;
import com.epi.validator.audit.ManifestService;
import com.epi.validator.config.EpiValidationProperties;
import com.epi.validator.config.EpiValidationProperties.IgConfig;
import com.epi.validator.engine.EpiTypeDetector;
import com.epi.validator.engine.IgRuntime;
import com.epi.validator.engine.IssueNormalizer;
import com.epi.validator.engine.ProfileResolver;
import com.epi.validator.engine.TypeResolution;
import com.epi.validator.engine.ValidatorRegistry;
import com.epi.validator.model.AuditInfo;
import com.epi.validator.model.EpiType;
import com.epi.validator.model.IssueLayer;
import com.epi.validator.model.IssueSeverity;
import com.epi.validator.model.NormalizedIssue;
import com.epi.validator.model.ValidationMode;
import com.epi.validator.model.ValidationResponse;
import com.epi.validator.model.ValidationStats;
import com.epi.validator.model.Verdict;
import com.epi.validator.policy.PolicyContext;
import com.epi.validator.policy.PolicyEngine;
import com.epi.validator.policy.WarningPolicy;
import com.epi.validator.web.ApiException;
import com.epi.validator.web.UnparseableRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.OperationOutcome;
import org.hl7.fhir.r5.model.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates one validation run through all five layers:
 * parse -> FHIR R5 + ePI IG profile validation -> policy packs -> warning policy -> verdict.
 */
@Service
public class ValidationService {

    private final FhirContext fhirContext;
    private final EpiValidationProperties properties;
    private final ValidatorRegistry registry;
    private final EpiTypeDetector typeDetector;
    private final ProfileResolver profileResolver;
    private final PolicyEngine policyEngine;
    private final WarningPolicy warningPolicy;
    private final IssueNormalizer normalizer;
    private final ManifestService manifestService;
    private final ValidationExecutor executor;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ValidationService(FhirContext fhirContext,
                             EpiValidationProperties properties,
                             ValidatorRegistry registry,
                             EpiTypeDetector typeDetector,
                             ProfileResolver profileResolver,
                             PolicyEngine policyEngine,
                             WarningPolicy warningPolicy,
                             IssueNormalizer normalizer,
                             ManifestService manifestService,
                             ValidationExecutor executor,
                             MeterRegistry meterRegistry) {
        this.fhirContext = fhirContext;
        this.properties = properties;
        this.registry = registry;
        this.typeDetector = typeDetector;
        this.profileResolver = profileResolver;
        this.policyEngine = policyEngine;
        this.warningPolicy = warningPolicy;
        this.normalizer = normalizer;
        this.manifestService = manifestService;
        this.executor = executor;
        this.meterRegistry = meterRegistry;
    }

    public record Request(
            byte[] body,
            String contentType,
            String validationModeParam,
            String epiTypeParam,
            String igVersionParam,
            List<String> profiles,
            boolean includeOperationOutcome,
            String traceId) {
    }

    public ValidationResponse validate(Request request) {
        long started = System.nanoTime();

        IgConfig igConfig = resolveIgConfig(request.igVersionParam());
        ValidationMode mode = resolveMode(request.validationModeParam());
        String requestedType = normalizeRequestedType(request.epiTypeParam(), mode);

        if (!registry.isReady() || !registry.hasRuntime(igConfig.id())) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Validators are still warming up; check /actuator/health/readiness");
        }
        IgRuntime runtime = registry.runtimeFor(igConfig.id());

        String raw = new String(request.body(), StandardCharsets.UTF_8);
        Bundle bundle = parseBundle(raw, request, mode, igConfig);

        EpiType detected = typeDetector.detect(bundle);
        TypeResolution resolution = TypeResolution.resolve(requestedType, detected);
        List<String> profiles = profileResolver.resolve(igConfig, mode, resolution.effective(), request.profiles());

        ValidationOptions options = new ValidationOptions();
        profiles.forEach(options::addProfile);

        // Validate from the raw source string — never a re-serialized object — so the validator
        // can report line/column locations for the repair loop.
        ValidationResult result = executor.execute(
                () -> runtime.validator().validateWithResult(raw, options));

        List<NormalizedIssue> issues = new ArrayList<>();
        result.getMessages().forEach(m -> issues.add(normalizer.normalize(m)));
        issues.addAll(policyEngine.evaluate(new PolicyContext(
                fhirContext,
                bundle,
                resolution,
                igConfig.id(),
                typeDetector.hasType2Content(bundle),
                typeDetector.hasType3Content(bundle))));

        WarningPolicy.Outcome outcome = warningPolicy.apply(normalizer.sortBySeverity(issues));

        OperationOutcome operationOutcome = buildOperationOutcome(result, outcome.issues());
        String encodedOutcome = fhirContext.newJsonParser().encodeResourceToString(operationOutcome);

        long durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        ValidationStats stats = ValidationStats.of(outcome.issues(), durationMillis);

        ValidationResponse response = new ValidationResponse(
                outcome.verdict(),
                mode,
                igConfig.id(),
                resolution.requested(),
                resolution.detected(),
                resolution.effective(),
                profiles,
                stats,
                outcome.issues(),
                request.traceId(),
                buildAudit(request, mode, igConfig, encodedOutcome),
                request.includeOperationOutcome() ? toJsonNode(encodedOutcome) : null);

        recordMetrics(igConfig.id(), mode, resolution.effective(), outcome.verdict(), durationMillis);
        return response;
    }

    /** FHIR-native $validate: same engine, returns the raw OperationOutcome only. */
    public OperationOutcome validateToOperationOutcome(byte[] body, String contentType, List<String> profiles,
                                                       String igVersionParam, String traceId) {
        ValidationResponse response = validate(new Request(
                body, contentType, ValidationMode.EXPLORATORY.wireValue(), TypeResolution.AUTO,
                igVersionParam, profiles, false, traceId));
        // Rebuild the OperationOutcome from the normalized issues (single code path for both APIs)
        OperationOutcome outcome = new OperationOutcome();
        for (NormalizedIssue issue : response.issues()) {
            outcome.addIssue(toOutcomeIssue(issue));
        }
        if (response.issues().isEmpty()) {
            OperationOutcome.OperationOutcomeIssueComponent ok = outcome.addIssue();
            ok.setSeverity(OperationOutcome.IssueSeverity.INFORMATION);
            ok.setCode(OperationOutcome.IssueType.INFORMATIONAL);
            ok.getDetails().setText("Validation passed with no issues");
        }
        return outcome;
    }

    private IgConfig resolveIgConfig(String igVersionParam) {
        try {
            return properties.igConfigOrThrow(igVersionParam);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        }
    }

    private ValidationMode resolveMode(String modeParam) {
        if (modeParam == null || modeParam.isBlank()) {
            return properties.defaultValidationMode();
        }
        try {
            return ValidationMode.fromWire(modeParam);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        }
    }

    private String normalizeRequestedType(String epiTypeParam, ValidationMode mode) {
        String requested = epiTypeParam == null || epiTypeParam.isBlank()
                ? TypeResolution.AUTO
                : epiTypeParam.toLowerCase(Locale.ROOT);
        if (!TypeResolution.AUTO.equals(requested)) {
            try {
                EpiType.fromWire(requested);
            } catch (IllegalArgumentException e) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
            }
        } else if (mode == ValidationMode.GATE) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "validationMode=gate requires an explicit epiType (1|2|3); epiType=auto is for "
                            + "exploratory validation, diagnostics, demos, and malformed inbound triage");
        }
        return requested;
    }

    private Bundle parseBundle(String raw, Request request, ValidationMode mode, IgConfig igConfig) {
        boolean xml = request.contentType() != null
                && request.contentType().toLowerCase(Locale.ROOT).contains("xml");
        IParser parser = xml ? fhirContext.newXmlParser() : fhirContext.newJsonParser();
        Resource resource;
        try {
            resource = (Resource) parser.parseResource(raw);
        } catch (DataFormatException e) {
            throw new UnparseableRequestException(
                    parserFailureEnvelope(request, mode, igConfig, e), e.getMessage());
        }
        if (!(resource instanceof Bundle bundle)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Expected a FHIR Bundle (document), received a " + resource.fhirType());
        }
        return bundle;
    }

    private ValidationResponse parserFailureEnvelope(Request request, ValidationMode mode, IgConfig igConfig,
                                                     DataFormatException cause) {
        NormalizedIssue issue = new NormalizedIssue(
                IssueSeverity.FATAL,
                "invalid",
                "PARSE-001",
                IssueLayer.PARSER,
                new NormalizedIssue.Location(null, null, null, null),
                "Request body is not parseable FHIR: " + cause.getMessage(),
                null,
                new NormalizedIssue.Source(NormalizedIssue.Source.TYPE_PARSER, "hapi-fhir-parser",
                        ca.uhn.fhir.util.VersionUtil.getVersion()),
                false,
                null,
                null);
        List<NormalizedIssue> issues = List.of(issue);
        return new ValidationResponse(
                Verdict.FAIL,
                mode,
                igConfig.id(),
                request.epiTypeParam() == null ? TypeResolution.AUTO : request.epiTypeParam(),
                EpiType.UNKNOWN,
                EpiType.UNKNOWN,
                List.of(),
                ValidationStats.of(issues, 0),
                issues,
                request.traceId(),
                buildAudit(request, mode, igConfig, null),
                null);
    }

    private AuditInfo buildAudit(Request request, ValidationMode mode, IgConfig igConfig, String encodedOutcome) {
        var packageEntry = manifestService.entryForTargetFile(igConfig.igPackage());
        return new AuditInfo(
                manifestService.manifestSha256(),
                manifestService.manifest().hapiVersion(),
                packageEntry == null
                        ? new AuditInfo.IgPackageRef(null, igConfig.id(), null)
                        : new AuditInfo.IgPackageRef(packageEntry.id(), packageEntry.version(), packageEntry.sha256()),
                policyEngine.activePacks().stream()
                        .map(p -> new AuditInfo.PolicyPackRef(p.id(), p.version()))
                        .toList(),
                warningPolicy.mode().wireValue(),
                mode,
                new AuditInfo.InputRef(
                        ManifestService.sha256Hex(request.body()),
                        request.contentType(),
                        request.body().length),
                encodedOutcome == null ? null : ManifestService.sha256Hex(encodedOutcome));
    }

    private OperationOutcome buildOperationOutcome(ValidationResult result, List<NormalizedIssue> allIssues) {
        // Start from HAPI's OperationOutcome (carries line/col extensions), then append the
        // policy-layer issues so the OO is complete across all layers.
        OperationOutcome outcome = (OperationOutcome) result.toOperationOutcome();
        allIssues.stream()
                .filter(i -> i.layer() == IssueLayer.POLICY)
                .forEach(i -> outcome.addIssue(toOutcomeIssue(i)));
        return outcome;
    }

    private OperationOutcome.OperationOutcomeIssueComponent toOutcomeIssue(NormalizedIssue issue) {
        OperationOutcome.OperationOutcomeIssueComponent component =
                new OperationOutcome.OperationOutcomeIssueComponent();
        component.setSeverity(switch (issue.severity()) {
            case FATAL -> OperationOutcome.IssueSeverity.FATAL;
            case ERROR -> OperationOutcome.IssueSeverity.ERROR;
            case WARNING -> OperationOutcome.IssueSeverity.WARNING;
            case INFORMATION -> OperationOutcome.IssueSeverity.INFORMATION;
        });
        component.setCode(issue.layer() == IssueLayer.POLICY
                ? OperationOutcome.IssueType.BUSINESSRULE
                : OperationOutcome.IssueType.PROCESSING);
        component.setDiagnostics(issue.message()
                + (issue.ruleId() != null ? " [" + issue.ruleId() + "]" : ""));
        if (issue.location() != null && issue.location().fhirPath() != null) {
            component.addExpression(issue.location().fhirPath());
        }
        return component;
    }

    private JsonNode toJsonNode(String encodedOutcome) {
        try {
            return objectMapper.readTree(encodedOutcome);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to embed OperationOutcome", e);
        }
    }

    private void recordMetrics(String igId, ValidationMode mode, EpiType effectiveType,
                               Verdict verdict, long durationMillis) {
        Timer.builder("epi.validation.duration")
                .tag("igVersion", igId)
                .tag("mode", mode.wireValue())
                .tag("epiType", effectiveType.wireValue())
                .tag("verdict", verdict.name())
                .register(meterRegistry)
                .record(durationMillis, TimeUnit.MILLISECONDS);
    }
}
