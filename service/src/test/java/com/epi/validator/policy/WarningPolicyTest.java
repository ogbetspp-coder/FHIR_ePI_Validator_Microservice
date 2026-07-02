package com.epi.validator.policy;

import com.epi.validator.config.EpiValidationProperties;
import com.epi.validator.config.EpiValidationProperties.AllowlistEntry;
import com.epi.validator.config.EpiValidationProperties.WarningPolicyMode;
import com.epi.validator.config.EpiValidationProperties.WarningPolicyProperties;
import com.epi.validator.model.IssueLayer;
import com.epi.validator.model.IssueSeverity;
import com.epi.validator.model.NormalizedIssue;
import com.epi.validator.model.ValidationMode;
import com.epi.validator.model.Verdict;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WarningPolicyTest {

    private static NormalizedIssue issue(IssueSeverity severity, String ruleId, String message) {
        return new NormalizedIssue(severity, "structure", ruleId, IssueLayer.FHIR,
                new NormalizedIssue.Location(null, null, null, null), message, null,
                new NormalizedIssue.Source("profile-validator", "test", "0"), false, null, null);
    }

    private static WarningPolicy policy(WarningPolicyMode mode, List<AllowlistEntry> allowlist) {
        return new WarningPolicy(new EpiValidationProperties(
                "1.0.0", ValidationMode.EXPLORATORY, 50, 8, 120, false, "warning", true,
                new EpiValidationProperties.ProfileOverride(true, List.of(ValidationMode.EXPLORATORY)),
                new EpiValidationProperties.RemoteTerminology(false, ""),
                new WarningPolicyProperties(mode, allowlist),
                List.of(), List.of()));
    }

    @Test
    void errorsAlwaysFail() {
        WarningPolicy p = policy(WarningPolicyMode.PASS_WITH_WARNINGS, List.of());
        assertThat(p.apply(List.of(issue(IssueSeverity.ERROR, "X", "boom"))).verdict())
                .isEqualTo(Verdict.FAIL);
    }

    @Test
    void passWithWarningsTolleratesWarnings() {
        WarningPolicy p = policy(WarningPolicyMode.PASS_WITH_WARNINGS, List.of());
        assertThat(p.apply(List.of(issue(IssueSeverity.WARNING, "X", "meh"))).verdict())
                .isEqualTo(Verdict.PASS_WITH_WARNINGS);
        assertThat(p.apply(List.of()).verdict()).isEqualTo(Verdict.PASS);
    }

    @Test
    void failUnlessAllowlistedFailsUnknownWarnings() {
        WarningPolicy p = policy(WarningPolicyMode.FAIL_UNLESS_ALLOWLISTED, List.of());
        assertThat(p.apply(List.of(issue(IssueSeverity.WARNING, "X", "unknown warning"))).verdict())
                .isEqualTo(Verdict.FAIL);
    }

    @Test
    void allowlistedWarningIsControlledDeviation() {
        WarningPolicy p = policy(WarningPolicyMode.FAIL_UNLESS_ALLOWLISTED, List.of(
                new AllowlistEntry("Terminology_TX_*", "https://spor.ema.europa.eu/v1/lists/*",
                        "warning", "Checked upstream by SPOR adapter")));
        var outcome = p.apply(List.of(issue(IssueSeverity.WARNING, "Terminology_TX_System_NotKnown",
                "CodeSystem 'https://spor.ema.europa.eu/v1/lists/200000000014' is unknown")));
        assertThat(outcome.verdict()).isEqualTo(Verdict.PASS_WITH_WARNINGS);
        assertThat(outcome.issues().get(0).allowlisted()).isTrue();
        assertThat(outcome.issues().get(0).rationale()).contains("SPOR");
    }

    @Test
    void allowlistRequiresBothRuleAndSystemWhenBothSet() {
        WarningPolicy p = policy(WarningPolicyMode.FAIL_UNLESS_ALLOWLISTED, List.of(
                new AllowlistEntry("Terminology_TX_*", "https://spor.ema.europa.eu/*", "warning", "ok")));
        // Same rule id, different code system -> not allowlisted -> FAIL
        var outcome = p.apply(List.of(issue(IssueSeverity.WARNING, "Terminology_TX_System_NotKnown",
                "CodeSystem 'http://other.example/cs' is unknown")));
        assertThat(outcome.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(outcome.issues().get(0).allowlisted()).isFalse();
    }

    @Test
    void globCompilation() {
        assertThat(WarningPolicy.globToPattern("abc*def").matcher("abcXYZdef").matches()).isTrue();
        assertThat(WarningPolicy.globToPattern("abc").matcher("abc").matches()).isTrue();
        assertThat(WarningPolicy.globToPattern("abc").matcher("abcd").matches()).isFalse();
        assertThat(WarningPolicy.globToPattern("*tail").matcher("long-tail").matches()).isTrue();
    }
}
