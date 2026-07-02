package com.epi.validator.engine;

import ca.uhn.fhir.util.VersionUtil;
import ca.uhn.fhir.validation.SingleValidationMessage;
import com.epi.validator.model.IssueLayer;
import com.epi.validator.model.IssueSeverity;
import com.epi.validator.model.NormalizedIssue;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts raw validator messages into {@link NormalizedIssue}s: layer classification
 * (fhir vs ig), structured source, and best-effort JSON-pointer locations.
 */
@Component
public class IssueNormalizer {

    private static final Pattern PROFILE_URL = Pattern.compile(
            "https?://[^\\s|)\\],'\"]+/StructureDefinition/[^\\s|)\\],'\"]+");
    private static final Pattern HAPI_RESOURCE_MARKER = Pattern.compile("/\\*[^*]*\\*/");
    private static final Pattern INDEX = Pattern.compile("\\[(\\d+)]");

    /** Canonical base marking issues attributable to the ePI IG rather than base FHIR. */
    private static final String EPI_CANONICAL_MARKER = "emedicinal-product-info";

    private final NormalizedIssue.Source validatorSource = new NormalizedIssue.Source(
            NormalizedIssue.Source.TYPE_PROFILE_VALIDATOR,
            "hapi-fhir-instance-validator",
            VersionUtil.getVersion());

    public NormalizedIssue normalize(SingleValidationMessage message) {
        String text = message.getMessage();
        String profileUrl = extractProfileUrl(text);
        IssueLayer layer = isIgAttributable(text, profileUrl) ? IssueLayer.IG : IssueLayer.FHIR;
        String fhirPath = cleanLocation(message.getLocationString());
        return new NormalizedIssue(
                mapSeverity(message),
                message.getMessageId() != null ? "structure" : "processing",
                message.getMessageId(),
                layer,
                new NormalizedIssue.Location(
                        fhirPath,
                        toJsonPointer(fhirPath),
                        message.getLocationLine(),
                        message.getLocationCol()),
                text,
                profileUrl,
                validatorSource,
                false,
                null,
                null);
    }

    public List<NormalizedIssue> sortBySeverity(List<NormalizedIssue> issues) {
        return issues.stream()
                .sorted(Comparator.comparing(NormalizedIssue::severity))
                .toList();
    }

    private static IssueSeverity mapSeverity(SingleValidationMessage message) {
        return switch (message.getSeverity()) {
            case FATAL -> IssueSeverity.FATAL;
            case ERROR -> IssueSeverity.ERROR;
            case WARNING -> IssueSeverity.WARNING;
            case INFORMATION -> IssueSeverity.INFORMATION;
        };
    }

    private static String extractProfileUrl(String message) {
        if (message == null) {
            return null;
        }
        Matcher m = PROFILE_URL.matcher(message);
        return m.find() ? m.group() : null;
    }

    private static boolean isIgAttributable(String message, String profileUrl) {
        return (profileUrl != null && profileUrl.contains(EPI_CANONICAL_MARKER))
                || (message != null && message.contains(EPI_CANONICAL_MARKER));
    }

    /** Strips HAPI's inline resource markers, e.g. {@code entry[0].resource/*Composition/xyz*{@literal /}.text}. */
    private static String cleanLocation(String location) {
        if (location == null) {
            return null;
        }
        return HAPI_RESOURCE_MARKER.matcher(location).replaceAll("");
    }

    /**
     * Best-effort FHIRPath -> JSON pointer translation. Returns null where the path does not map
     * cleanly (slice names, ofType() casts) — nullable by API contract.
     */
    static String toJsonPointer(String fhirPath) {
        if (fhirPath == null || fhirPath.isBlank()) {
            return null;
        }
        String path = fhirPath.trim();
        if (path.contains("ofType(") || path.contains(":")) {
            return null;
        }
        // Drop the root resource-type segment (Bundle.entry[0] -> /entry/0)
        int firstDot = path.indexOf('.');
        if (firstDot < 0) {
            return "/";
        }
        path = path.substring(firstDot + 1);
        String pointer = "/" + INDEX.matcher(path).replaceAll("/$1").replace(".", "/");
        return pointer.replaceAll("/{2,}", "/");
    }
}
