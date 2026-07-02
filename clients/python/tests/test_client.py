import gzip
import json

import httpx
import pytest
import respx

from epi_validator_client import ApiError, EpiValidatorClient, Verdict

BASE = "http://validator.test"
VALIDATE = f"{BASE}/api/v1/epi/validate"


@respx.mock
def test_validate_bundle_sends_params_and_parses_envelope(envelope):
    route = respx.post(VALIDATE).mock(return_value=httpx.Response(200, json=envelope))
    client = EpiValidatorClient(BASE)
    result = client.validate_bundle(
        {"resourceType": "Bundle"},
        epi_type="3",
        validation_mode="gate",
        ig_version="1.1.0",
        trace_id="pipeline-run-0042",
        gzip=False,
    )
    assert result.verdict is Verdict.FAIL
    request = route.calls.last.request
    assert request.url.params["epiType"] == "3"
    assert request.url.params["validationMode"] == "gate"
    assert request.url.params["igVersion"] == "1.1.0"
    assert request.headers["X-Trace-Id"] == "pipeline-run-0042"
    assert json.loads(request.content) == {"resourceType": "Bundle"}


@respx.mock
def test_gzip_body_encoding(envelope):
    route = respx.post(VALIDATE).mock(return_value=httpx.Response(200, json=envelope))
    EpiValidatorClient(BASE).validate_bundle({"resourceType": "Bundle"}, epi_type="1")
    request = route.calls.last.request
    assert request.headers["Content-Encoding"] == "gzip"
    assert json.loads(gzip.decompress(request.content)) == {"resourceType": "Bundle"}


@respx.mock
def test_400_with_envelope_returns_result_not_exception(envelope):
    envelope["verdict"] = "FAIL"
    respx.post(VALIDATE).mock(return_value=httpx.Response(400, json=envelope))
    result = EpiValidatorClient(BASE).validate_bundle(b"{broken", epi_type="1", gzip=False)
    assert result.verdict is Verdict.FAIL


@respx.mock
def test_422_misuse_raises_api_error():
    respx.post(VALIDATE).mock(return_value=httpx.Response(
        422, json={"status": 422, "message": "epiType=auto not allowed in gate mode",
                   "traceId": "t-1"}))
    with pytest.raises(ApiError) as excinfo:
        EpiValidatorClient(BASE).validate_bundle({}, epi_type="auto", validation_mode="gate")
    assert excinfo.value.status_code == 422
    assert "gate mode" in excinfo.value.message
    assert excinfo.value.trace_id == "t-1"


@respx.mock
def test_retries_on_503_then_succeeds(envelope):
    route = respx.post(VALIDATE).mock(side_effect=[
        httpx.Response(503, json={"message": "warming up"}),
        httpx.Response(200, json=envelope),
    ])
    result = EpiValidatorClient(BASE).validate_bundle({}, epi_type="1")
    assert result.verdict is Verdict.FAIL
    assert route.call_count == 2


@respx.mock
def test_gives_up_after_three_transient_failures():
    respx.post(VALIDATE).mock(return_value=httpx.Response(503, json={"message": "down"}))
    from epi_validator_client import TransientError

    with pytest.raises(TransientError):
        EpiValidatorClient(BASE).validate_bundle({}, epi_type="1")


@respx.mock
def test_wait_ready_polls_until_up():
    respx.get(f"{BASE}/actuator/health/readiness").mock(side_effect=[
        httpx.Response(503, json={"status": "OUT_OF_SERVICE"}),
        httpx.Response(200, json={"status": "UP"}),
    ])
    EpiValidatorClient(BASE).wait_ready(timeout=5, interval=0.01)


@respx.mock
@pytest.mark.anyio
async def test_async_client(envelope):
    respx.post(VALIDATE).mock(return_value=httpx.Response(200, json=envelope))
    from epi_validator_client import AsyncEpiValidatorClient

    async with AsyncEpiValidatorClient(BASE) as client:
        result = await client.validate_bundle({}, epi_type="3", validation_mode="gate")
    assert result.verdict is Verdict.FAIL


@pytest.fixture
def anyio_backend():
    return "asyncio"
