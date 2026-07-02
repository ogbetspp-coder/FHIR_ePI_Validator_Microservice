package com.epi.validator.policy;

import com.epi.validator.model.IssueSeverity;

import java.util.List;

/**
 * A versioned, declarative policy pack descriptor (loaded from policy-packs/&lt;id&gt;.yaml).
 * The descriptor is the authority for rule severity, enablement, and rationale; the Java
 * {@link PolicyRule} implementations only detect violations.
 */
public record PolicyPackDescriptor(
        String id,
        String version,
        List<String> appliesTo,
        List<RuleDefinition> rules) {

    public record RuleDefinition(
            String ruleId,
            IssueSeverity severity,
            boolean configurable,
            String rationale) {
    }

    public boolean appliesTo(String igId) {
        return appliesTo != null && appliesTo.contains(igId);
    }
}
