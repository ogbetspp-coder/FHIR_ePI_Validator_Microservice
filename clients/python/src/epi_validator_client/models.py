"""Pydantic models mirroring the validation envelope of POST /api/v1/epi/validate."""

from __future__ import annotations

from enum import Enum
from typing import Any

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel


class _Camel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="ignore")


class Verdict(str, Enum):
    PASS = "PASS"
    PASS_WITH_WARNINGS = "PASS_WITH_WARNINGS"
    FAIL = "FAIL"


class IssueLocation(_Camel):
    fhir_path: str | None = None
    json_pointer: str | None = None
    line: int | None = None
    column: int | None = None


class IssueSource(_Camel):
    type: str | None = None
    id: str | None = None
    version: str | None = None


class Issue(_Camel):
    severity: str
    code: str | None = None
    rule_id: str | None = None
    layer: str | None = None
    location: IssueLocation | None = None
    message: str
    profile_url: str | None = None
    source: IssueSource | None = None
    allowlisted: bool = False
    rationale: str | None = None
    suggestion: str | None = None

    def describe(self) -> str:
        """One-line, LLM-consumable description of the defect."""
        where = ""
        if self.location and self.location.fhir_path:
            where = f" at {self.location.fhir_path}"
            if self.location.line is not None:
                where += f" (line {self.location.line})"
        rule = f" [{self.rule_id}]" if self.rule_id else ""
        hint = f" Why this matters: {self.rationale}" if self.rationale else ""
        return f"[{self.severity}/{self.layer}]{where}: {self.message}{rule}{hint}"


class LayerCount(_Camel):
    errors: int = 0
    warnings: int = 0


class TotalCount(_Camel):
    fatal: int = 0
    errors: int = 0
    warnings: int = 0
    information: int = 0


class Stats(_Camel):
    parser: LayerCount = Field(default_factory=LayerCount)
    fhir: LayerCount = Field(default_factory=LayerCount)
    ig: LayerCount = Field(default_factory=LayerCount)
    policy: LayerCount = Field(default_factory=LayerCount)
    total: TotalCount = Field(default_factory=TotalCount)
    duration_millis: int = 0


class IgPackageRef(_Camel):
    id: str | None = None
    version: str | None = None
    sha256: str | None = None


class PolicyPackRef(_Camel):
    id: str
    version: str


class InputRef(_Camel):
    sha256: str | None = None
    content_type: str | None = None
    size_bytes: int | None = None


class Audit(_Camel):
    manifest_sha256: str | None = None
    hapi_version: str | None = None
    ig_package: IgPackageRef | None = None
    policy_packs: list[PolicyPackRef] = Field(default_factory=list)
    warning_policy_mode: str | None = None
    validation_mode: str | None = None
    input: InputRef | None = None
    operation_outcome_sha256: str | None = None


class ValidationResult(_Camel):
    verdict: Verdict
    validation_mode: str | None = None
    ig_version: str | None = None
    requested_epi_type: str | None = None
    detected_epi_type: str | None = None
    effective_epi_type: str | None = None
    profiles_validated_against: list[str] = Field(default_factory=list)
    stats: Stats = Field(default_factory=Stats)
    issues: list[Issue] = Field(default_factory=list)
    trace_id: str | None = None
    audit: Audit | None = None
    operation_outcome: dict[str, Any] | None = None

    @property
    def passed(self) -> bool:
        """True for PASS and PASS_WITH_WARNINGS (warnings are governed server-side)."""
        return self.verdict is not Verdict.FAIL

    @property
    def errors(self) -> list[Issue]:
        return [i for i in self.issues if i.severity in ("fatal", "error")]

    @property
    def warnings(self) -> list[Issue]:
        return [i for i in self.issues if i.severity == "warning"]

    def to_llm_feedback(self, max_issues: int = 25) -> str:
        """Render the defect list as a compact numbered block for the repair agent's prompt.

        Errors come first (already server-ordered); truncated with an explicit tail so the
        agent knows the list is incomplete rather than silently clipped.
        """
        relevant = [i for i in self.issues if i.severity in ("fatal", "error", "warning")]
        lines = [
            f"Validation verdict: {self.verdict.value} "
            f"(errors={self.stats.total.fatal + self.stats.total.errors}, "
            f"warnings={self.stats.total.warnings}; "
            f"requested ePI type {self.requested_epi_type}, detected {self.detected_epi_type})",
            "Defects to fix:",
        ]
        for n, issue in enumerate(relevant[:max_issues], start=1):
            lines.append(f"{n}. {issue.describe()}")
        remaining = len(relevant) - max_issues
        if remaining > 0:
            lines.append(f"... and {remaining} more issue(s) not shown — fix the above first, then revalidate.")
        return "\n".join(lines)
