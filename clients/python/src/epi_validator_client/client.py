"""HTTP clients for the ePI validation gate.

Production pipeline callers SHOULD pass the contracted ``epi_type`` explicitly and use
``validation_mode="gate"``; ``epi_type="auto"`` is for exploratory validation, diagnostics,
demos, and malformed inbound triage.
"""

from __future__ import annotations

import gzip as gzip_module
import json
import time
from pathlib import Path
from typing import Any

import httpx
from tenacity import retry, retry_if_exception_type, stop_after_attempt, wait_exponential

from .models import ValidationResult

_VALIDATE_PATH = "/api/v1/epi/validate"
_READINESS_PATH = "/actuator/health/readiness"
_RETRYABLE_STATUS = {429, 502, 503, 504}


class ApiError(Exception):
    """Non-retryable API failure (misuse: 413/415/422, or unexpected 5xx after retries)."""

    def __init__(self, status_code: int, message: str, trace_id: str | None = None):
        super().__init__(f"HTTP {status_code}: {message}")
        self.status_code = status_code
        self.message = message
        self.trace_id = trace_id


class TransientError(Exception):
    """Retryable failure (connection error or 429/502/503/504)."""

    def __init__(self, message: str, retry_after: float | None = None):
        super().__init__(message)
        self.retry_after = retry_after


def _prepare_body(bundle: dict[str, Any] | str | bytes | Path) -> bytes:
    if isinstance(bundle, Path):
        return bundle.read_bytes()
    if isinstance(bundle, dict):
        return json.dumps(bundle).encode("utf-8")
    if isinstance(bundle, str):
        return bundle.encode("utf-8")
    return bundle


def _build_request(
    *,
    bundle: dict[str, Any] | str | bytes | Path,
    epi_type: str,
    validation_mode: str | None,
    ig_version: str | None,
    profile: str | list[str] | None,
    include_operation_outcome: bool,
    use_gzip: bool,
    trace_id: str | None,
    content_type: str,
) -> tuple[dict[str, str], list[tuple[str, str]], bytes]:
    params: list[tuple[str, str]] = [("epiType", epi_type)]
    if validation_mode:
        params.append(("validationMode", validation_mode))
    if ig_version:
        params.append(("igVersion", ig_version))
    if profile:
        for p in [profile] if isinstance(profile, str) else profile:
            params.append(("profile", p))
    if not include_operation_outcome:
        params.append(("includeOperationOutcome", "false"))

    body = _prepare_body(bundle)
    headers = {"Content-Type": content_type}
    if trace_id:
        headers["X-Trace-Id"] = trace_id
    if use_gzip:
        body = gzip_module.compress(body)
        headers["Content-Encoding"] = "gzip"
    return headers, params, body


def _handle_response(response: httpx.Response) -> ValidationResult:
    if response.status_code in _RETRYABLE_STATUS:
        retry_after = response.headers.get("Retry-After")
        raise TransientError(
            f"HTTP {response.status_code} from validator",
            retry_after=float(retry_after) if retry_after else None,
        )
    if response.status_code in (200, 400):
        payload = response.json()
        if "verdict" in payload:
            # 400-with-envelope is the unparseable-input contract: still a ValidationResult,
            # so the repair loop receives its normal feedback shape.
            return ValidationResult.model_validate(payload)
    try:
        detail = response.json()
        message = detail.get("message", response.text)
        trace_id = detail.get("traceId")
    except (json.JSONDecodeError, ValueError):
        message, trace_id = response.text, None
    raise ApiError(response.status_code, message, trace_id)


_retry_decorator = retry(
    retry=retry_if_exception_type((TransientError, httpx.ConnectError, httpx.ConnectTimeout)),
    stop=stop_after_attempt(3),
    wait=wait_exponential(multiplier=0.5, max=10),
    reraise=True,
)


class EpiValidatorClient:
    """Synchronous client. See module docstring for gate-mode guidance."""

    def __init__(
        self,
        base_url: str,
        *,
        timeout: float = 120.0,
        headers: dict[str, str] | None = None,
    ):
        self._client = httpx.Client(base_url=base_url, timeout=timeout, headers=headers or {})

    def close(self) -> None:
        self._client.close()

    def __enter__(self) -> EpiValidatorClient:
        return self

    def __exit__(self, *exc_info: object) -> None:
        self.close()

    @_retry_decorator
    def validate_bundle(
        self,
        bundle: dict[str, Any] | str | bytes | Path,
        *,
        epi_type: str = "auto",
        validation_mode: str | None = None,
        ig_version: str | None = None,
        profile: str | list[str] | None = None,
        include_operation_outcome: bool = True,
        gzip: bool = True,
        trace_id: str | None = None,
        content_type: str = "application/fhir+json",
    ) -> ValidationResult:
        """Validate one ePI bundle and return the envelope.

        Gate on ``result.verdict`` (or use :func:`epi_validator_client.gate`) — never on
        HTTP status. Production callers pass the contracted ``epi_type`` and
        ``validation_mode="gate"``.
        """
        headers, params, body = _build_request(
            bundle=bundle,
            epi_type=epi_type,
            validation_mode=validation_mode,
            ig_version=ig_version,
            profile=profile,
            include_operation_outcome=include_operation_outcome,
            use_gzip=gzip,
            trace_id=trace_id,
            content_type=content_type,
        )
        response = self._client.post(_VALIDATE_PATH, params=params, headers=headers, content=body)
        return _handle_response(response)

    def wait_ready(self, timeout: float = 300.0, interval: float = 2.0) -> None:
        """Block until the validator reports readiness (validators warmed) or raise TimeoutError."""
        deadline = time.monotonic() + timeout
        last: str = "unreached"
        while time.monotonic() < deadline:
            try:
                response = self._client.get(_READINESS_PATH)
                if response.status_code == 200:
                    return
                last = f"HTTP {response.status_code}"
            except httpx.HTTPError as e:  # noqa: PERF203 - deliberate poll loop
                last = str(e)
            time.sleep(interval)
        raise TimeoutError(f"Validator not ready after {timeout}s (last: {last})")


class AsyncEpiValidatorClient:
    """Async twin of :class:`EpiValidatorClient` for concurrent pipeline stages."""

    def __init__(
        self,
        base_url: str,
        *,
        timeout: float = 120.0,
        headers: dict[str, str] | None = None,
    ):
        self._client = httpx.AsyncClient(base_url=base_url, timeout=timeout, headers=headers or {})

    async def aclose(self) -> None:
        await self._client.aclose()

    async def __aenter__(self) -> AsyncEpiValidatorClient:
        return self

    async def __aexit__(self, *exc_info: object) -> None:
        await self.aclose()

    @_retry_decorator
    async def validate_bundle(
        self,
        bundle: dict[str, Any] | str | bytes | Path,
        *,
        epi_type: str = "auto",
        validation_mode: str | None = None,
        ig_version: str | None = None,
        profile: str | list[str] | None = None,
        include_operation_outcome: bool = True,
        gzip: bool = True,
        trace_id: str | None = None,
        content_type: str = "application/fhir+json",
    ) -> ValidationResult:
        headers, params, body = _build_request(
            bundle=bundle,
            epi_type=epi_type,
            validation_mode=validation_mode,
            ig_version=ig_version,
            profile=profile,
            include_operation_outcome=include_operation_outcome,
            use_gzip=gzip,
            trace_id=trace_id,
            content_type=content_type,
        )
        response = await self._client.post(
            _VALIDATE_PATH, params=params, headers=headers, content=body
        )
        return _handle_response(response)
