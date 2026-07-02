package com.epi.validator.engine;

import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.SingleValidationMessage;
import com.epi.validator.model.Issue;
import com.epi.validator.model.IssueSeverity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IssueMapperTest {

    private final IssueMapper mapper = new IssueMapper();

    private static SingleValidationMessage message(ResultSeverityEnum severity, String text, String location) {
        SingleValidationMessage m = new SingleValidationMessage();
        m.setSeverity(severity);
        m.setMessage(text);
        m.setLocationString(location);
        m.setLocationLine(42);
        m.setLocationCol(7);
        m.setMessageId("Bundle_BUNDLE_Entry_NotFound");
        return m;
    }

    @Test
    void mapsToFlatIssueWithValidatorSource() {
        Issue issue = mapper.map(message(ResultSeverityEnum.ERROR, "broken", "Bundle.entry[0]"));
        assertThat(issue.severity()).isEqualTo(IssueSeverity.ERROR);
        assertThat(issue.source()).isEqualTo(Issue.SOURCE_VALIDATOR);
        assertThat(issue.ruleId()).isEqualTo("Bundle_BUNDLE_Entry_NotFound");
        assertThat(issue.fhirPath()).isEqualTo("Bundle.entry[0]");
        assertThat(issue.line()).isEqualTo(42);
        assertThat(issue.column()).isEqualTo(7);
    }

    @Test
    void hapiResourceMarkersStrippedFromLocation() {
        Issue issue = mapper.map(message(ResultSeverityEnum.ERROR, "broken",
                "Bundle.entry[0].resource/*Composition/abc-123*/.text.div"));
        assertThat(issue.fhirPath()).isEqualTo("Bundle.entry[0].resource.text.div");
    }

    @Test
    void sortsBySeverityFatalFirst() {
        Issue warning = mapper.map(message(ResultSeverityEnum.WARNING, "w", "Bundle"));
        Issue fatal = mapper.map(message(ResultSeverityEnum.FATAL, "f", "Bundle"));
        Issue error = mapper.map(message(ResultSeverityEnum.ERROR, "e", "Bundle"));
        assertThat(mapper.sortBySeverity(List.of(warning, fatal, error)))
                .extracting(Issue::severity)
                .containsExactly(IssueSeverity.FATAL, IssueSeverity.ERROR, IssueSeverity.WARNING);
    }
}
