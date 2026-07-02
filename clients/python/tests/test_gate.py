import pytest

from epi_validator_client import GateFailed, ValidationResult, gate


def test_gate_returns_result_on_pass(passing_envelope):
    result = ValidationResult.model_validate(passing_envelope)
    assert gate(result) is result


def test_gate_raises_on_fail_with_llm_feedback(envelope):
    result = ValidationResult.model_validate(envelope)
    with pytest.raises(GateFailed) as excinfo:
        gate(result)
    assert "Defects to fix:" in excinfo.value.feedback
    assert "EPI-TYPE-001" in excinfo.value.feedback
    assert excinfo.value.result is result
    assert "traceId=pipeline-run-0042" in str(excinfo.value)


def test_gate_fail_on_warnings_hardens_the_gate(passing_envelope):
    result = ValidationResult.model_validate(passing_envelope)
    gate(result)  # passes by default
    with pytest.raises(GateFailed):
        gate(result, fail_on_warnings=True)
