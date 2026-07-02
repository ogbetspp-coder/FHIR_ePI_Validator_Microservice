from epi_validator_client import ValidationResult, Verdict


def test_envelope_parses_with_camel_case_aliases(envelope):
    result = ValidationResult.model_validate(envelope)
    assert result.verdict is Verdict.FAIL
    assert result.ig_version == "1.1.0"
    assert result.requested_epi_type == "3"
    assert result.detected_epi_type == "2"
    assert result.effective_epi_type == "3"
    assert result.stats.total.errors == 2
    assert result.stats.policy.errors == 1
    assert result.audit.ig_package.version == "1.1.0-cibuild-20260701"
    assert result.audit.input.size_bytes == 27958
    assert result.trace_id == "pipeline-run-0042"


def test_helpers(envelope):
    result = ValidationResult.model_validate(envelope)
    assert not result.passed
    assert len(result.errors) == 2
    assert len(result.warnings) == 1
    assert result.warnings[0].allowlisted is True


def test_llm_feedback_contains_actionable_lines(envelope):
    result = ValidationResult.model_validate(envelope)
    feedback = result.to_llm_feedback()
    assert "Validation verdict: FAIL" in feedback
    assert "requested ePI type 3, detected 2" in feedback
    assert "1. [error/ig] at Bundle.entry[0].resource.section[3].entry[0] (line 812)" in feedback
    assert "EPI-TYPE-001" in feedback
    assert "Why this matters: Type 3 contract requires" in feedback


def test_llm_feedback_truncates_explicitly(envelope):
    result = ValidationResult.model_validate(envelope)
    feedback = result.to_llm_feedback(max_issues=1)
    assert "and 2 more issue(s) not shown" in feedback


def test_unknown_fields_ignored_for_forward_compatibility(envelope):
    envelope["someFutureField"] = {"x": 1}
    result = ValidationResult.model_validate(envelope)
    assert result.verdict is Verdict.FAIL
