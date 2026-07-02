package com.epi.validator.policy;

import com.epi.validator.config.EpiValidationProperties;
import com.epi.validator.config.EpiValidationProperties.IgConfig;
import com.epi.validator.model.ValidationMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The four fail-fast conditions: a broken policy configuration must refuse to start,
 * never silently drift.
 */
class PolicyPackStartupValidationTest {

    private static final PolicyPackLoader LOADER = new PolicyPackLoader();

    private static PolicyRule rule(String id) {
        return new PolicyRule() {
            @Override
            public String ruleId() {
                return id;
            }

            @Override
            public List<RuleViolation> evaluate(PolicyContext context) {
                return List.of();
            }
        };
    }

    private static EpiValidationProperties props(List<String> packs) {
        return new EpiValidationProperties(
                "1.0.0", ValidationMode.EXPLORATORY, 50, 8, 120, false, "warning", true,
                new EpiValidationProperties.ProfileOverride(true, List.of(ValidationMode.EXPLORATORY)),
                new EpiValidationProperties.RemoteTerminology(false, ""),
                new EpiValidationProperties.WarningPolicyProperties(
                        EpiValidationProperties.WarningPolicyMode.PASS_WITH_WARNINGS, List.of()),
                packs,
                List.of(new IgConfig("1.0.0", "pkg.tgz", List.of(), "http://profile", null)));
    }

    @Test
    void validTestPackLoads() {
        PolicyEngine engine = new PolicyEngine(props(List.of("test-pack-valid")), LOADER,
                List.of(rule("TEST-001"), rule("TEST-002")));
        assertThat(engine.activePacks()).hasSize(1);
        assertThat(engine.activePacks().get(0).version()).isEqualTo("9.9.9");
    }

    @Test
    void failsWhenRuleIdHasNoImplementation() {
        assertThatThrownBy(() -> new PolicyEngine(props(List.of("test-pack-valid")), LOADER,
                List.of(rule("TEST-001")))) // TEST-002 declared but not implemented
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TEST-002")
                .hasMessageContaining("no PolicyRule implementation");
    }

    @Test
    void failsWhenImplementationNotDeclaredInAnyPack() {
        assertThatThrownBy(() -> new PolicyEngine(props(List.of("test-pack-valid")), LOADER,
                List.of(rule("TEST-001"), rule("TEST-002"), rule("ROGUE-999"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ROGUE-999")
                .hasMessageContaining("not declared in any active pack");
    }

    @Test
    void failsOnConflictingSeveritiesAcrossPacks() {
        assertThatThrownBy(() -> new PolicyEngine(
                props(List.of("test-pack-valid", "test-pack-conflicting")), LOADER,
                List.of(rule("TEST-001"), rule("TEST-002"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Conflicting severities")
                .hasMessageContaining("TEST-001");
    }

    @Test
    void failsWhenAppliesToReferencesUnknownIg() {
        assertThatThrownBy(() -> new PolicyEngine(props(List.of("test-pack-unknown-ig")), LOADER,
                List.of(rule("TEST-001"), rule("TEST-002"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("appliesTo unknown IG id");
    }

    @Test
    void failsOnStructurallyInvalidDescriptor() {
        assertThatThrownBy(() -> new PolicyEngine(props(List.of("test-pack-bad-severity")), LOADER,
                List.of(rule("TEST-001"), rule("TEST-002"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid severity");
    }
}
