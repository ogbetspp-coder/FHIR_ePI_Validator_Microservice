# epi-validator-client

Python client for the FHIR ePI validation gate.

```python
from epi_validator_client import EpiValidatorClient, gate, GateFailed

client = EpiValidatorClient("http://localhost:8080")
client.wait_ready()

result = client.validate_bundle(bundle_dict, epi_type="2", validation_mode="gate")
try:
    gate(result)                       # raises GateFailed on FAIL
except GateFailed as failure:
    llm_prompt_fragment = failure.feedback   # numbered defect list for the repair agent
```

Production pipeline callers SHOULD pass the contracted `epi_type` explicitly and use
`validation_mode="gate"`. `epi_type="auto"` is for exploratory validation, diagnostics,
demos, and malformed inbound triage.
