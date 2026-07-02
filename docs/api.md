# API Contract

Interactive docs: `/swagger-ui.html` · OpenAPI: `/v3/api-docs`

## `POST /api/v1/epi/validate` — the regulated pipeline gate (primary API)

**HTTP status is not the verdict.** This endpoint returns `200` whenever validation
*executed*, regardless of outcome. Gate on the envelope's `verdict`.

### Request

- Body: FHIR Bundle (document). `Content-Type: application/fhir+json`, `application/json`,
  `application/fhir+xml`, `application/xml`. Optional `Content-Encoding: gzip`
  (recommended for bundles carrying base64 PDFs in Binary resources).
- Recommended header: `X-Trace-Id: <your pipeline run id>` (echoed everywhere).

| Query param | Values | Default | Notes |
|---|---|---|---|
| `validationMode` | `exploratory` \| `gate` | server config | `gate` = regulated path |
| `epiType` | `1` \| `2` \| `3` \| `auto` | `auto` | `auto` rejected in gate mode; production callers pass the contracted type |
| `igVersion` | configured IG id (`1.0.0`, `1.1.0`) | server config | |
| `profile` | canonical URL (repeatable) | — | rejected in gate mode unless configured |
| `includeOperationOutcome` | boolean | `true` | `false` shrinks the envelope |

### Response statuses

| Status | Meaning | Body |
|---|---|---|
| 200 | validation executed (verdict may be FAIL) | envelope |
| 400 | body not parseable as FHIR | **envelope** (verdict FAIL, one `parser`-layer issue) |
| 413 | body exceeds `max-body-mb` (post-inflation) | problem JSON |
| 415 | unsupported content type | problem JSON |
| 422 | misuse: unknown `igVersion`/`epiType`, non-Bundle resource, `auto` in gate mode, disallowed profile override | problem JSON |
| 429 | bulkhead full (`Retry-After: 1`) | problem JSON |
| 503 | validators warming up, or validation exceeded the request timeout | problem JSON |

### Envelope

```jsonc
{
  "verdict": "PASS | PASS_WITH_WARNINGS | FAIL",
  "validationMode": "gate",
  "igVersion": "1.1.0",
  "requestedEpiType": "3",          // caller intent
  "detectedEpiType": "2",           // diagnostic inference — never selects the gate
  "effectiveEpiType": "3",          // what actually drove profiles + type rules
  "profilesValidatedAgainst": ["http://hl7.org/fhir/uv/emedicinal-product-info/StructureDefinition/bundle-epi-type3"],
  "stats": {
    "parser": {"errors": 0, "warnings": 0},
    "fhir":   {"errors": 0, "warnings": 2},
    "ig":     {"errors": 1, "warnings": 0},
    "policy": {"errors": 1, "warnings": 0},
    "total":  {"fatal": 0, "errors": 2, "warnings": 2, "information": 1},
    "durationMillis": 431
  },
  "issues": [                        // ordered fatal -> error -> warning -> information
    {
      "severity": "error",
      "code": "business-rule",
      "ruleId": "EPI-TYPE-001",
      "layer": "policy",
      "location": {
        "fhirPath": "Bundle",
        "jsonPointer": "/",          // best-effort; null where FHIRPath doesn't map (slices, ofType)
        "line": null, "column": null // populated for validator issues on JSON input
      },
      "message": "Requested ePI Type 3 but the bundle contains no machine-readable clinical content ...",
      "profileUrl": null,
      "source": {"type": "policy-pack", "id": "epi-gate-core", "version": "0.1.0"},
      "allowlisted": false,
      "rationale": "Type 3 contract requires machine-readable clinical content; ...",
      "suggestion": null             // reserved, always null in v1
    }
  ],
  "traceId": "pipeline-run-0042",
  "audit": {
    "manifestSha256": "…64 hex…",
    "hapiVersion": "8.10.0",
    "igPackage": {"id": "hl7.fhir.uv.emedicinal-product-info", "version": "1.1.0-cibuild-20260701", "sha256": "…"},
    "policyPacks": [{"id": "epi-gate-core", "version": "0.1.0"}],
    "warningPolicyMode": "pass-with-warnings",
    "validationMode": "gate",
    "input": {"sha256": "…", "contentType": "application/fhir+json", "sizeBytes": 27958},
    "operationOutcomeSha256": "…"
  },
  "operationOutcome": { "resourceType": "OperationOutcome", "issue": [ /* … */ ] }
}
```

`issue.source.type` is `profile-validator` (layers 2–3), `policy-pack` (layers 4–5), or
`parser` (layer 1).

## `POST /fhir/$validate`

**FHIR interop/debug endpoint only. Not the regulated pipeline gate.**

Same engine; accepts `?profile=` and `?igVersion=`; returns a raw `OperationOutcome` with
HTTP `200` even when issues are errors, per FHIR operation semantics. Pipeline code must
never gate on this endpoint's HTTP status. Disable in production with
`epi.validation.fhir-native-endpoint-enabled=false` if unused.

## `GET /api/v1/epi/igs`

Configured IGs with package metadata, declared dependencies, bundle profiles per type,
profile count, and readiness.

## `GET /api/v1/epi/manifest`

The immutable build manifest (buildId, HAPI version, SHA-256-pinned packages, policy packs)
and its `manifestSha256` — the same hash echoed in every validation `audit` block.

## Operational endpoints

- `/actuator/health/liveness` — process is alive
- `/actuator/health/readiness` — `UP` only after all validator chains are built and warmed
  (30–90 s after start)
- `/actuator/prometheus` — includes timer `epi_validation_duration` tagged
  `igVersion`, `mode`, `epiType`, `verdict`
