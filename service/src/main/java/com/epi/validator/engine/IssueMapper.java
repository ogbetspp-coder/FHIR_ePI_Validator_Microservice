package com.epi.validator.engine;

import ca.uhn.fhir.validation.SingleValidationMessage;
import com.epi.validator.model.Issue;
import com.epi.validator.model.IssueSeverity;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** Maps raw validator messages to the flat {@link Issue} shape the pipeline consumes. */
@Component
public class IssueMapper {

    /** HAPI embeds resource markers in locations: {@code entry[0].resource/*Composition/xyz*}{@code /.text}. */
    private static final Pattern HAPI_RESOURCE_MARKER = Pattern.compile("/\\*[^*]*\\*/");

    public Issue map(SingleValidationMessage message) {
        return new Issue(
                mapSeverity(message),
                Issue.SOURCE_VALIDATOR,
                message.getMessageId(),
                message.getMessage(),
                cleanLocation(message.getLocationString()),
                message.getLocationLine(),
                message.getLocationCol());
    }

    public List<Issue> sortBySeverity(List<Issue> issues) {
        return issues.stream().sorted(Comparator.comparing(Issue::severity)).toList();
    }

    private static IssueSeverity mapSeverity(SingleValidationMessage message) {
        if (message.getSeverity() == null) {
            return IssueSeverity.ERROR; // defensive: HAPI always sets severity, but never NPE a run
        }
        return switch (message.getSeverity()) {
            case FATAL -> IssueSeverity.FATAL;
            case ERROR -> IssueSeverity.ERROR;
            case WARNING -> IssueSeverity.WARNING;
            case INFORMATION -> IssueSeverity.INFORMATION;
        };
    }

    static String cleanLocation(String location) {
        if (location == null) {
            return null;
        }
        return HAPI_RESOURCE_MARKER.matcher(location).replaceAll("");
    }
}
