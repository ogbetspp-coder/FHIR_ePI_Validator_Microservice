package com.epi.validator.engine;

import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.SingleValidationMessage;
import com.epi.validator.model.IssueLayer;
import com.epi.validator.model.IssueSeverity;
import com.epi.validator.model.NormalizedIssue;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IssueNormalizerTest {

    private final IssueNormalizer normalizer = new IssueNormalizer();

    private static SingleValidationMessage message(ResultSeverityEnum severity, String text, String location) {
        SingleValidationMessage m = new SingleValidationMessage();
        m.setSeverity(severity);
        m.setMessage(text);
        m.setLocationString(location);
        m.setLocationLine(42);
        m.setLocationCol(7);
        return m;
    }

    @Test
    void epiProfileIssuesClassifyAsIgLayer() {
        NormalizedIssue issue = normalizer.normalize(message(ResultSeverityEnum.ERROR,
                "Slice matching failed (from http://hl7.org/fhir/uv/emedicinal-product-info/StructureDefinition/bundle-epi-type2|1.1.0)",
                "Bundle.entry[0]"));
        assertThat(issue.layer()).isEqualTo(IssueLayer.IG);
        assertThat(issue.profileUrl()).contains("emedicinal-product-info");
        assertThat(issue.severity()).isEqualTo(IssueSeverity.ERROR);
        assertThat(issue.location().line()).isEqualTo(42);
    }

    @Test
    void baseFhirIssuesClassifyAsFhirLayer() {
        NormalizedIssue issue = normalizer.normalize(message(ResultSeverityEnum.WARNING,
                "Constraint failed: dom-6 (from http://hl7.org/fhir/StructureDefinition/Composition)",
                "Bundle.entry[0].resource"));
        assertThat(issue.layer()).isEqualTo(IssueLayer.FHIR);
        assertThat(issue.source().type()).isEqualTo(NormalizedIssue.Source.TYPE_PROFILE_VALIDATOR);
    }

    @Test
    void hapiResourceMarkersStrippedFromLocation() {
        NormalizedIssue issue = normalizer.normalize(message(ResultSeverityEnum.ERROR,
                "broken", "Bundle.entry[0].resource/*Composition/abc-123*/.text.div"));
        assertThat(issue.location().fhirPath()).isEqualTo("Bundle.entry[0].resource.text.div");
        assertThat(issue.location().jsonPointer()).isEqualTo("/entry/0/resource/text/div");
    }

    @Test
    void jsonPointerBestEffort() {
        assertThat(IssueNormalizer.toJsonPointer("Bundle.entry[3].resource.section[2].entry[0]"))
                .isEqualTo("/entry/3/resource/section/2/entry/0");
        assertThat(IssueNormalizer.toJsonPointer("Bundle")).isEqualTo("/");
        // Slices and ofType casts do not map cleanly -> null by contract
        assertThat(IssueNormalizer.toJsonPointer("Bundle.entry:composition")).isNull();
        assertThat(IssueNormalizer.toJsonPointer("Patient.value.ofType(Range)")).isNull();
        assertThat(IssueNormalizer.toJsonPointer(null)).isNull();
    }

    @Test
    void severityOrderingSortsFatalFirst() {
        NormalizedIssue warning = normalizer.normalize(message(ResultSeverityEnum.WARNING, "w", "Bundle"));
        NormalizedIssue fatal = normalizer.normalize(message(ResultSeverityEnum.FATAL, "f", "Bundle"));
        NormalizedIssue error = normalizer.normalize(message(ResultSeverityEnum.ERROR, "e", "Bundle"));
        assertThat(normalizer.sortBySeverity(java.util.List.of(warning, fatal, error)))
                .extracting(NormalizedIssue::severity)
                .containsExactly(IssueSeverity.FATAL, IssueSeverity.ERROR, IssueSeverity.WARNING);
    }
}
