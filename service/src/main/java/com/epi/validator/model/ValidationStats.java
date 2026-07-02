package com.epi.validator.model;

import java.util.List;
import java.util.Map;

/** Per-layer and total issue counts for one validation run. */
public record ValidationStats(
        LayerCount parser,
        LayerCount fhir,
        LayerCount ig,
        LayerCount policy,
        TotalCount total,
        long durationMillis) {

    public record LayerCount(int errors, int warnings) {
    }

    public record TotalCount(int fatal, int errors, int warnings, int information) {
    }

    public static ValidationStats of(List<NormalizedIssue> issues, long durationMillis) {
        Map<IssueLayer, List<NormalizedIssue>> byLayer = issues.stream()
                .collect(java.util.stream.Collectors.groupingBy(NormalizedIssue::layer));
        return new ValidationStats(
                layerCount(byLayer.get(IssueLayer.PARSER)),
                layerCount(byLayer.get(IssueLayer.FHIR)),
                layerCount(byLayer.get(IssueLayer.IG)),
                layerCount(byLayer.get(IssueLayer.POLICY)),
                new TotalCount(
                        count(issues, IssueSeverity.FATAL),
                        count(issues, IssueSeverity.ERROR),
                        count(issues, IssueSeverity.WARNING),
                        count(issues, IssueSeverity.INFORMATION)),
                durationMillis);
    }

    private static LayerCount layerCount(List<NormalizedIssue> issues) {
        if (issues == null) {
            return new LayerCount(0, 0);
        }
        int errors = (int) issues.stream().filter(i -> i.severity().isAtLeastError()).count();
        int warnings = (int) issues.stream().filter(i -> i.severity() == IssueSeverity.WARNING).count();
        return new LayerCount(errors, warnings);
    }

    private static int count(List<NormalizedIssue> issues, IssueSeverity severity) {
        return (int) issues.stream().filter(i -> i.severity() == severity).count();
    }
}
