"""Client for the FHIR ePI validation gate."""

from .client import ApiError, AsyncEpiValidatorClient, EpiValidatorClient, TransientError
from .gate import GateFailed, gate
from .models import (
    Audit,
    Issue,
    IssueLocation,
    IssueSource,
    Stats,
    ValidationResult,
    Verdict,
)

__all__ = [
    "ApiError",
    "AsyncEpiValidatorClient",
    "Audit",
    "EpiValidatorClient",
    "GateFailed",
    "Issue",
    "IssueLocation",
    "IssueSource",
    "Stats",
    "TransientError",
    "ValidationResult",
    "Verdict",
    "gate",
]

__version__ = "0.1.0"
