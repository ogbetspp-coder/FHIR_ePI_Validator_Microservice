# FHIR ePI Validator Microservice

A lean deployable validation service for checking AI-generated FHIR ePI Bundles against
FHIR R5 and the pinned HL7 ePI 1.0.0 STU1 Implementation Guide using the official HAPI
validator.

```
AI-generated FHIR ePI Bundle
→ POST /api/v1/epi/validate
→ parse as FHIR R5
→ validate against the pinned ePI IG (offline, official engine, raw input)
→ PASS | PASS_WITH_WARNINGS | FAIL  +  OperationOutcome  +  simplified issues
```

The service is a deterministic control point for an AI extraction pipeline: the agent
proposes a bundle, this service checks it, and the pipeline gates on the **verdict** —
never on HTTP status.

## Quick start

```bash
docker compose up --wait          # service on :8080 (readiness gated on validator warm-up)

# Validate the known-good example (expect PASS_WITH_WARNINGS)
curl -sS -X POST 'http://localhost:8080/api/v1/epi/validate?epiType=1' \
  -H 'Content-Type: application/fhir+json' \
  --data-binary @examples/good-bundle.json | jq .verdict

# Validate the seeded-defect example (expect FAIL with located issues)
curl -sS -X POST 'http://localhost:8080/api/v1/epi/validate?epiType=1' \
  -H 'Content-Type: application/fhir+json' \
  --data-binary @examples/broken-bundle.json | jq '{verdict, issues: [.issues[] | select(.severity=="error")]}'

# Or the no-dependency script (exit 0 on pass, 1 on fail)
python3 examples/validate_bundle.py examples/good-bundle.json --epi-type 1
```

Local development: `make build` (tests), `make run`, `make docker`.
Swagger UI: `http://localhost:8080/swagger-ui.html`.

## API

### `POST /api/v1/epi/validate`

Body: a FHIR Bundle (document) as `application/fhir+json` or `application/fhir+xml`;
`Content-Encoding: gzip` supported (bodies capped at 50 MB post-inflation).

| Param | Values | Notes |
|---|---|---|
| `epiType` | `1` \| `2` \| `3` \| `auto` (default) | Production callers pass the contracted type. `auto` detects from content, but **an explicit request is never downgraded by detection** — declaring Type 3 and shipping Type 2 content fails. |

Returns **HTTP 200 whenever validation executed**, regardless of verdict. 400 = unparseable
body (still returns the envelope, verdict FAIL, one `parser` issue). 413/415/422 = misuse.

```jsonc
{
  "verdict": "FAIL",                       // PASS | PASS_WITH_WARNINGS | FAIL
  "requestedEpiType": "3",
  "detectedEpiType": "1",                  // diagnostic only
  "effectiveEpiType": "3",                 // what the type checks enforced
  "profilesValidatedAgainst": ["http://hl7.org/fhir/uv/emedicinal-product-info/StructureDefinition/Bundle-uv-epi"],
  "issues": [
    { "severity": "error", "source": "hapi-validator", "ruleId": "Bundle_BUNDLE_Entry_NotFound",
      "message": "…", "fhirPath": "Bundle.entry[0]", "line": 12, "column": 3 },
    { "severity": "error", "source": "simple-check", "ruleId": "EPI-TYPE-001",
      "message": "Requested ePI Type 3 but the bundle contains no machine-readable clinical content …",
      "fhirPath": "Bundle" }
  ],
  "operationOutcome": { "resourceType": "OperationOutcome", "issue": [ /* full FHIR detail */ ] },
  "inputSha256": "…",                      // hash of the exact bytes that were judged
  "traceId": "…",                          // accepted inbound via X-Trace-Id, echoed everywhere
  "validatorInfo": { "fhirVersion": "5.0.0", "hapiVersion": "8.10.0",
                     "igPackage": "hl7.fhir.uv.emedicinal-product-info", "igVersion": "1.0.0" }
}
```

Verdict rules: any error → `FAIL`; any warning → `PASS_WITH_WARNINGS`; else `PASS`.

### `GET /api/v1/epi/info`

`{fhirVersion, hapiVersion, igPackage, igVersion, ready}` — what validator am I talking to.

### `GET /actuator/health` (+ `/liveness`, `/readiness`)

Readiness turns UP only after the IG package is loaded, snapshots are generated, and a
warm-up validation has run (~30–60 s after start).

## What it validates

1. **Parse** — body must be valid FHIR R5 JSON/XML (`parser` issues).
2. **FHIR R5 + ePI IG profiles** — the official HAPI/HL7 validator engine over the vendored,
   SHA-256-pinned `hl7.fhir.uv.emedicinal-product-info#1.0.0` package and its declared
   dependencies (`hapi-validator` issues). Raw input is validated directly, so issues carry
   line/column positions.
3. **Simple ePI checks** (`simple-check` issues) — five hardcoded sanity rules:

   | Rule | Checks |
   |---|---|
   | `EPI-DOC-001` | `Bundle.type` must be `document` |
   | `EPI-DOC-002` | first entry must be a `Composition` |
   | `EPI-DOC-003` | all internal references resolve within the bundle |
   | `EPI-TYPE-001` | requested Type 3 requires clinical content (`ClinicalUseDefinition`/`MedicationKnowledge`) |
   | `EPI-TYPE-002` | requested Type 2/3 requires product-data resources (`MedicinalProductDefinition`, …) |

Everything runs **offline**: packages are vendored into the image
(`service/src/main/resources/packages/`, pinned in `tools/packages.lock.json`,
verified by `tools/vendor-packages.sh --verify`). No network at build or runtime.

## Deployment

The container needs **2 GiB memory** (terminology + profile snapshots) and should never
scale to zero (cold start pays the ~30–60 s warm-up). Cloud Run example:

```bash
gcloud run deploy epi-validator --image=IMAGE --memory=2Gi --cpu=2 \
  --min-instances=1 --concurrency=8 --cpu-boost \
  --ingress=internal --no-allow-unauthenticated
```

Auth is left to the platform (IAM/ingress) by design.

## Examples

- `examples/good-bundle.json` — the IG's own Type 1 example (package leaflet) with its three
  known reference defects repaired, so it genuinely passes.
- `examples/broken-bundle.json` — the same bundle with two seeded extraction defects: a
  section stripped of its content (profile constraint `cmp-1`) and a dangling author
  reference (`EPI-DOC-003`).
- `examples/validate_bundle.py` — dependency-free client; exit code follows the verdict.

## Known limitations

- Offline terminology: **codes are not verified against external terminologies** (SNOMED CT,
  WHO ATC, MedDRA, EDQM, EMA SPOR, UNII) — those code systems are not distributable offline,
  so a wrong code in one of them yields a *warning*, not an error. `PASS_WITH_WARNINGS` must not
  be read as "terminology validated." Point `EPI_VALIDATION_REMOTETERMINOLOGY_*` at an internal
  terminology server (Ontoserver/tx mirror) to restore strict code checking. (Codes in *known,
  complete* code systems, e.g. FHIR core, are still validated and error normally.)
- **ClinicalUseDefinition ePI sub-profiles** (indication / contraindication / interaction /
  undesirable-effect / warning) are enforced only when the resource declares the corresponding
  ePI `meta.profile`. A Type-3 bundle whose ClinicalUseDefinition entries omit `meta.profile` is
  validated against base ClinicalUseDefinition only. `EPI-TYPE-001` still confirms clinical
  content is *present*. Explicit per-resource sub-profile enforcement is the top phase-2 item.
- Validates bundle conformance, **not medical correctness** of label content.
- ePI type detection is a diagnostic heuristic; the type *contract* is enforced only for an
  explicitly requested `epiType`.
- One IG target: `hl7.fhir.uv.emedicinal-product-info#1.0.0` (FHIR R5). Support for the ePI
  1.1.0 CI build's type-specific profiles, policy packs, warning allowlists, a Python SDK,
  and richer audit exist in this repo's git history and can return as phase 2.

Request bodies are decoded by their declared charset (Content-Type `charset`, XML encoding
declaration, or BOM; UTF-8 by default) so non-UTF-8 documents are validated as written; an
undecodable body is rejected with 400 rather than silently corrupted.

## License

Apache-2.0 (see [LICENSE](LICENSE)). License choice is a project default — confirm it fits
your organization's policy before external distribution.
