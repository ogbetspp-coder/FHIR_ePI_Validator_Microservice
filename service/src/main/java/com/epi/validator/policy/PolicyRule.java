package com.epi.validator.policy;

import java.util.List;

/**
 * A deterministic policy check. Implementations detect violations; severity and rationale come
 * from the pack descriptor that declares the ruleId. Every implementation must be declared in
 * an active pack and vice versa — enforced at startup (fail-fast).
 */
public interface PolicyRule {

    String ruleId();

    List<RuleViolation> evaluate(PolicyContext context);

    record RuleViolation(String fhirPath, String jsonPointer, String message) {
        public static RuleViolation at(String fhirPath, String jsonPointer, String message) {
            return new RuleViolation(fhirPath, jsonPointer, message);
        }
    }
}
