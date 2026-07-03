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

This service is the gate in an AI extraction pipeline. The agent proposes a bundle, the service
checks it, and the pipeline decides from the `verdict`. Gate on the verdict, never on the HTTP
status.

## Quick start

```bash
docker compose up --wait          # service on :8080 (ready after validator warm-up)

# Known-good example: expect PASS_WITH_WARNINGS
curl -sS -X POST 'http://localhost:8080/api/v1/epi/validate?epiType=1' \
  -H 'Content-Type: application/fhir+json' \
  --data-binary @examples/good-bundle.json | jq .verdict

# Seeded-defect example: expect FAIL with located issues
curl -sS -X POST 'http://localhost:8080/api/v1/epi/validate?epiType=1' \
  -H 'Content-Type: application/fhir+json' \
  --data-binary @examples/broken-bundle.json | jq '{verdict, issues: [.issues[] | select(.severity=="error")]}'

# Or the no-dependency script (exit 0 on pass, 1 on fail)
python3 examples/validate_bundle.py examples/good-bundle.json --epi-type 1
```

Local development: `make build` (tests), `make run`, `make docker`.
OpenAPI spec (JSON): `http://localhost:8080/v3/api-docs`.

## API

### `POST /api/v1/epi/validate`

Send a FHIR Bundle (document) as `application/fhir+json` or `application/fhir+xml`.
`Content-Encoding: gzip` is supported. Bodies are capped at 50 MB after inflation.

| Param | Values | Notes |
|---|---|---|
| `epiType` | `1`, `2`, `3`, or `auto` (default) | Production callers pass the contracted type. `auto` detects the type from content. An explicit request is never downgraded by detection: declare Type 3 and ship Type 2 content, and it fails. |

The endpoint returns **HTTP 200 whenever validation ran**, whatever the verdict. Other status
codes mean the request never reached validation: 400 unparseable body (you still get the envelope,
verdict `FAIL`, one `parser` issue), 413 too large, 415 wrong media type, 422 not a Bundle or bad
`epiType`.

```jsonc
{
  "verdict": "FAIL",                       // PASS | PASS_WITH_WARNINGS | FAIL
  "requestedEpiType": "3",
  "detectedEpiType": "1",                  // diagnostic only
  "effectiveEpiType": "3",                 // what the type checks enforced
  "profilesValidatedAgainst": ["http://hl7.org/fhir/uv/emedicinal-product-info/StructureDefinition/Bundle-uv-epi"],
  "issues": [
    { "severity": "error", "source": "hapi-validator", "ruleId": "Bundle_BUNDLE_Entry_NotFound",
      "message": "...", "fhirPath": "Bundle.entry[0]", "line": 12, "column": 3 },
    { "severity": "error", "source": "simple-check", "ruleId": "EPI-TYPE-001",
      "message": "Requested ePI Type 3 but the bundle contains no machine-readable clinical content ...",
      "fhirPath": "Bundle" }
  ],
  "operationOutcome": { "resourceType": "OperationOutcome", "issue": [ /* full FHIR detail */ ] },
  "inputSha256": "...",                    // hash of the exact bytes that were judged
  "traceId": "...",                        // accepted inbound via X-Trace-Id, echoed everywhere
  "validatorInfo": { "fhirVersion": "5.0.0", "hapiVersion": "8.10.0",
                     "igPackage": "hl7.fhir.uv.emedicinal-product-info", "igVersion": "1.0.0" }
}
```

Verdict rules: any error gives `FAIL`; any warning with no errors gives `PASS_WITH_WARNINGS`;
otherwise `PASS`.

### `GET /api/v1/epi/info`

Returns `{fhirVersion, hapiVersion, igPackage, igVersion, ready}`. Answers "what validator am I
talking to?".

### `GET /actuator/health` (plus `/liveness`, `/readiness`)

Readiness (`/actuator/health/readiness`) returns 503 until the IG package loads, snapshots
generate, and a warm-up validation runs (about 30 to 60 seconds after start). Orchestrators hold
traffic until readiness passes.

## What it validates

The service runs four layers and merges their issues into one list.

1. **Parse** (`parser`). The body must be valid FHIR R5 JSON or XML.
2. **FHIR R5 + ePI IG profiles** (`hapi-validator`). The official HAPI/HL7 validator engine runs
   over the vendored, SHA-256-pinned `hl7.fhir.uv.emedicinal-product-info#1.0.0` package and its
   dependencies. The raw input is validated directly, so issues carry line and column positions.
3. **Simple ePI checks** (`simple-check`). Five hardcoded rules:

   | Rule | Checks |
   |---|---|
   | `EPI-DOC-001` | `Bundle.type` must be `document` |
   | `EPI-DOC-002` | first entry must be a `Composition` |
   | `EPI-DOC-003` | all internal references resolve within the bundle |
   | `EPI-TYPE-001` | requested Type 3 requires clinical content (`ClinicalUseDefinition` / `MedicationKnowledge`) |
   | `EPI-TYPE-002` | requested Type 2 or 3 requires product-data resources (`MedicinalProductDefinition`, ...) |

4. **ClinicalUseDefinition sub-profiles** (`clinical-profile`, rule `EPI-CUD-PROFILE`). Each
   `ClinicalUseDefinition` is validated against the ePI sub-profile for its `type` (indication,
   contraindication, interaction, undesirable-effect, warning), whether or not the resource
   declares `meta.profile`. The `Bundle-uv-epi` profile only pins base ClinicalUseDefinition, so
   without this layer an AI-generated Type 3 bundle that omits `meta.profile` would skip the
   constraints that define Type 3.

Everything runs **offline**. Packages are vendored into the image
(`service/src/main/resources/packages/`), pinned in `tools/packages.lock.json`, and verified by
`tools/vendor-packages.sh --verify`. There is no network at build or runtime.

## Deployment

The container needs **2 GiB memory** for terminology and profile snapshots. Do not scale to zero:
a cold start pays the 30 to 60 second warm-up. Cloud Run example:

```bash
gcloud run deploy epi-validator --image=IMAGE --memory=2Gi --cpu=2 \
  --min-instances=1 --concurrency=8 --cpu-boost \
  --ingress=internal --no-allow-unauthenticated
```

Authentication is left to the platform (IAM and ingress) by design. See [SECURITY.md](SECURITY.md).

## Examples

- `examples/good-bundle.json`: the IG's own Type 1 example (package leaflet) with its three known
  reference defects repaired, so it genuinely passes.
- `examples/broken-bundle.json`: the same bundle with two seeded extraction defects, a section
  stripped of its content (profile constraint `cmp-1`) and a dangling author reference
  (`EPI-DOC-003`).
- `examples/validate_bundle.py`: a dependency-free client. Its exit code follows the verdict.

## Development and handover

Prerequisites: Docker for the container path; Java 21 and Maven 3.9+ for local Maven builds.
Nothing else, and no network (the IG packages are vendored).

```bash
docker compose up --wait            # run it with zero local toolchain
make build                          # local build + 71 offline tests (unit + integration)
make run                            # run locally on :8080
make docker                         # build the container image
tools/vendor-packages.sh --verify   # confirm vendored packages match the SHA-256 lockfile
```

Layout: the Spring Boot service is under `service/` (base package `com.epi.validator`). `engine/`
is the validation chain and type resolution, `checks/` is the five sanity rules plus the
ClinicalUseDefinition sub-profile enforcement, `web/` is controllers, filters, and body decoding,
`service/` is orchestration. IG packages and their SHA-256 lockfile live in
`service/src/main/resources/packages/` and `tools/`.

**Cross-check against the official HL7 validator.** CI runs the reference `org.hl7.fhir.validation`
engine (the same engine behind validator.fhir.org and `validator_cli`), pinned to the same core
version this service embeds, against the example bundles. It asserts the engine reaches the same
verdict as the gate, with terminology and example-URL policy aligned. These two tests are gated on
`-Dcrosscheck` so a normal build stays offline. Run them locally:

```bash
mvn -f service/pom.xml -Dcrosscheck=true test-compile failsafe:integration-test \
  -Dit.test=OfficialValidatorCrossCheckIT
```

**Refreshing a pinned package** is a deliberate, reviewed change. See
[`tools/vendor-packages.sh`](tools/vendor-packages.sh) (`--refresh`) and the lockfile header.

Security posture (offline operation, input hardening, tested threat cases) is in
[SECURITY.md](SECURITY.md). CI publishes a CycloneDX SBOM per build.

## Known limitations

- **Offline terminology.** Codes in external terminologies (SNOMED CT, WHO ATC, MedDRA, EDQM, EMA
  SPOR, UNII) are not verified, because those code systems cannot be distributed offline. A wrong
  code in one of them is a *warning*, not an error. Do not read `PASS_WITH_WARNINGS` as
  "terminology validated." Restoring strict code checking means adding an internal terminology
  server to the validation chain (phase 2). Codes in known, complete code systems (for example FHIR
  core) are still validated and error normally.
- **Conformance, not correctness.** The service checks bundle and profile conformance, not the
  medical accuracy of the label content.
- **Type detection is a heuristic.** Detection is diagnostic. The type contract is enforced only
  for an explicitly requested `epiType`.
- **One IG target:** `hl7.fhir.uv.emedicinal-product-info#1.0.0` (FHIR R5). Support for the ePI
  1.1.0 CI build, policy packs, warning allowlists, a Python SDK, and richer audit lives in this
  repo's git history and can return as phase 2.

Request bodies are decoded by their declared charset (Content-Type `charset`, then an XML encoding
declaration, then a BOM, then UTF-8), so a non-UTF-8 document is validated as written. An
undecodable body is rejected with 400 rather than silently corrupted.

## License

Apache-2.0 (see [LICENSE](LICENSE)). The license is a project default. Confirm it fits your
organization's policy before external distribution.
