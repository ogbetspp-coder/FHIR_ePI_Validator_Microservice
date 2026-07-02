"""The pipeline gate primitive."""

from __future__ import annotations

from .models import ValidationResult, Verdict


class GateFailed(Exception):
    """Raised when a bundle does not pass the validation gate.

    ``failure.feedback`` is the LLM-ready defect list — inject it into the repair agent's
    prompt and revalidate the corrected bundle.
    """

    def __init__(self, result: ValidationResult):
        self.result = result
        self.feedback = result.to_llm_feedback()
        super().__init__(
            f"Validation gate failed: {result.verdict.value} "
            f"({result.stats.total.fatal + result.stats.total.errors} errors, "
            f"{result.stats.total.warnings} warnings; traceId={result.trace_id})"
        )


def gate(result: ValidationResult, *, fail_on_warnings: bool = False) -> ValidationResult:
    """Return the result if it passes the gate, else raise :class:`GateFailed`.

    The server's warning policy already governs whether warnings fail
    (``fail-unless-allowlisted`` mode); ``fail_on_warnings=True`` additionally hard-fails
    PASS_WITH_WARNINGS client-side for pipelines that require a clean PASS.
    """
    if result.verdict is Verdict.FAIL:
        raise GateFailed(result)
    if fail_on_warnings and result.verdict is Verdict.PASS_WITH_WARNINGS:
        raise GateFailed(result)
    return result
