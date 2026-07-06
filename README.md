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

This service is the gate in an AI extraction pipeline: the agent proposes a bundle, this service
checks it, and the pipeline decides from the `verdict`. Always gate on the verdict, never on the
HTTP status.

## Quick start

Only Docker is required.

```bash
docker compose up --wait          # builds + runs on :8080, waits for validator warm-up

# Known-good example -> expect PASS_WITH_WARNINGS
curl -sS -X POST 'http://localhost:8080/api/v1/epi/validate?epiType=1' \
  -H 'Content-Type: application/fhir+json' \
  --data-binary @examples/good-bundle.json | jq .verdict

# Seeded-defect example -> expect FAIL
curl -sS -X POST 'http://localhost:8080/api/v1/epi/validate?epiType=1' \
  -H 'Content-Type: application/fhir+json' \
  --data-binary @examples/broken-bundle.json | jq '{verdict, errors: [.issues[] | select(.severity=="error")]}'
```

The OpenAPI spec is at `http://localhost:8080/v3/api-docs`.

## FHIR terms used here (for readers new to FHIR)

You do not need deep FHIR knowledge to run or maintain this service. The terms that show up in the
code and responses:

- **Resource**: one typed FHIR object (a `Composition`, an `Organization`).
- **Bundle**: a container of resources. An ePI is a Bundle of `type: document`.
- **Composition**: the first entry of a document Bundle; the label's structure (sections + narrative).
- **Profile**: constraints a resource must satisfy (e.g. `Bundle-uv-epi`). "Validate against a profile" means check those constraints.
- **Implementation Guide (IG)**: a published package of profiles + terminology. Here: `hl7.fhir.uv.emedicinal-product-info` 1.0.0.
- **Reference**: one resource pointing at another. `EPI-DOC-003` checks references resolve inside the Bundle.
- **Narrative**: the human-readable HTML (`text.div`) inside a resource.
- **ClinicalUseDefinition (CUD)**: a machine-readable indication / contraindication / interaction / undesirable-effect / warning.
- **ePI types**: **Type 1** = document only (leaflet); **Type 2** = + structured product data; **Type 3** = + machine-readable clinical data (CUDs).
- **OperationOutcome**: FHIR's standard "list of issues" object. This service returns one (for FHIR tooling) next to a flattened `issues` list (for your code).

## API

### `POST /api/v1/epi/validate`

Send a FHIR document Bundle as `application/fhir+json` or `application/fhir+xml`
(`Content-Encoding: gzip` supported). Bodies are capped at 8 MB after inflation and 1000 entries;
both configurable.

| Param | Values | Notes |
|---|---|---|
| `epiType` | `1`, `2`, `3`, or `auto` (default) | Production callers pass the contracted type. `auto` detects it from content. An explicit request is never downgraded: declare Type 3 and ship Type 2 content, and it fails. |

**Returns HTTP 200 whenever validation ran**, whatever the verdict. Other codes mean the request
never reached validation: 400 unparseable body (you still get the envelope, verdict `FAIL`, one
`parser` issue), 413 too large, 415 wrong media type, 422 not a Bundle or bad `epiType`.

```jsonc
{
  "verdict": "FAIL",                       // PASS | PASS_WITH_WARNINGS | FAIL
  "requestedEpiType": "3",
  "detectedEpiType": "1",                  // diagnostic only
  "effectiveEpiType": "3",                 // what the type checks enforced
  "profilesValidatedAgainst": ["http://hl7.org/fhir/uv/emedicinal-product-info/StructureDefinition/Bundle-uv-epi"],
  "issues": [                              // flat list for your code (line/column when available)
    { "severity": "error", "source": "hapi-validator", "ruleId": "Bundle_BUNDLE_Entry_NotFound",
      "message": "...", "fhirPath": "Bundle.entry[0]", "line": 12, "column": 3 }
  ],
  "operationOutcome": { "resourceType": "OperationOutcome", "issue": [ /* same findings, FHIR format */ ] },
  "inputSha256": "...",                    // hash of the exact bytes judged
  "traceId": "...",                        // from X-Trace-Id if sent, else generated; echoed everywhere
  "validatorInfo": { "fhirVersion": "5.0.0", "hapiVersion": "8.10.0",
                     "igPackage": "hl7.fhir.uv.emedicinal-product-info", "igVersion": "1.0.0" }
}
```

Verdict: any error -> `FAIL`; any warning and no error -> `PASS_WITH_WARNINGS`; otherwise `PASS`.
Each issue's `source` is `parser`, `hapi-validator`, `simple-check`, or `clinical-profile`.

### `GET /api/v1/epi/info`

`{fhirVersion, hapiVersion, igPackage, igVersion, ready}` - which validator you are talking to.

### `GET /actuator/health/{liveness,readiness}`

Readiness is 503 until the IG loads and a warm-up validation runs (~30-60 s after start), then 200.
In the container these are on port 8081 (see Deploy).

## What it validates

Four layers, merged into one `issues` list:

1. **Parse** (`parser`): valid FHIR R5 JSON or XML.
2. **FHIR R5 + ePI profiles** (`hapi-validator`): the official HAPI/HL7 engine over the vendored,
   SHA-256-pinned `hl7.fhir.uv.emedicinal-product-info#1.0.0` package. Raw input is validated, so
   issues carry line/column.
3. **Simple ePI checks** (`simple-check`): five plain rules.

   | Rule | Checks |
   |---|---|
   | `EPI-DOC-001` | `Bundle.type` must be `document` |
   | `EPI-DOC-002` | first entry must be a `Composition` |
   | `EPI-DOC-003` | all internal references resolve within the bundle |
   | `EPI-TYPE-001` | requested Type 3 requires clinical content (`ClinicalUseDefinition`) |
   | `EPI-TYPE-002` | requested Type 2/3 requires product data (`MedicinalProductDefinition`, ...) |

4. **ClinicalUseDefinition sub-profiles** (`clinical-profile`, `EPI-CUD-PROFILE`): each CUD is
   validated against the ePI sub-profile for its type, even when the resource omits `meta.profile`.
   Without this, an AI-generated Type 3 bundle could skip the constraints that define Type 3.

Everything runs **offline**: IG packages are vendored into the image and SHA-256-pinned in
`tools/packages.lock.json`. No network at build or runtime.

## Deploy to Google Cloud Run

Prerequisites: the `gcloud` CLI, a GCP project, and an Artifact Registry Docker repo.

```bash
# Set these once for your environment
PROJECT=your-project;  REGION=europe-west1;  REPO=your-artifact-registry-repo
IMAGE="$REGION-docker.pkg.dev/$PROJECT/$REPO/epi-validator:$(git rev-parse --short HEAD)"

# 1. Build the image (service/Dockerfile, context = repo root) and push it
make docker                                                   # -> epi-validator:local
gcloud auth configure-docker "$REGION-docker.pkg.dev" --quiet # one-time
docker tag epi-validator:local "$IMAGE"
docker push "$IMAGE"

# 2. Deploy
gcloud run deploy epi-validator \
  --image="$IMAGE" --region="$REGION" \
  --memory=2Gi --cpu=2 --cpu-boost \
  --min-instances=1 --concurrency=4 \
  --ingress=internal --no-allow-unauthenticated
```

What each flag is for:

| Flag | Why |
|---|---|
| `--memory=2Gi` | The validator loads the ePI IG + terminology (~1.5 GB resident). Less will OOM. |
| `--cpu=2 --cpu-boost` | Validation is CPU-bound; boost shortens the cold-start warm-up. |
| `--min-instances=1` | Never scale to zero: a cold start pays a 30-60 s warm-up. |
| `--concurrency=4` | Must equal `server.tomcat.threads.max` (4). See capacity below. |
| `--ingress=internal --no-allow-unauthenticated` | No app-layer auth by design: gate with IAM + internal ingress. |

- **Ports:** the API serves on Cloud Run's `$PORT` (8080); health probes are on 8081. Cloud Run's
  default TCP startup check suffices, and `--min-instances=1` pays the warm-up once. (8081 is for
  GKE/Docker HTTP probes; Cloud Run ignores it.)
- **Capacity:** `max-body-mb` (8) x `concurrency` (4) must fit the heap. For larger bodies, raise
  `--memory` and `EPI_VALIDATION_MAXBODYMB` together (never concurrency alone). Tune via
  `--set-env-vars EPI_VALIDATION_MAXBODYMB=...,EPI_VALIDATION_MAXBUNDLEENTRIES=...`.

Auth, network posture, and tested threat cases are in [SECURITY.md](SECURITY.md).

### Demo it (feed test data, see the outcomes)

For a throwaway demo, deploy it reachable and open (lock down or delete afterwards), then feed the
sample bundles and watch the verdicts. `validate_bundle.py` needs only Python 3 (stdlib), and its
`--base-url` points it at the Cloud Run URL:

```bash
gcloud run deploy epi-validator --image="$IMAGE" --region="$REGION" \
  --memory=2Gi --cpu=2 --cpu-boost --min-instances=1 --concurrency=4 \
  --ingress=all --allow-unauthenticated

URL=$(gcloud run services describe epi-validator --region="$REGION" --format='value(status.url)')
curl -s "$URL/api/v1/epi/info"        # {"...","ready": true} once warm (first warm-up ~30-60 s)

# Feed the fixtures and read the outcomes:
python3 examples/validate_bundle.py examples/good-bundle.json   --epi-type 1 --base-url "$URL"  # -> PASS_WITH_WARNINGS
python3 examples/validate_bundle.py examples/broken-bundle.json --epi-type 1 --base-url "$URL"  # -> FAIL, each error located
python3 examples/validate_bundle.py examples/good-bundle.json   --epi-type 3 --base-url "$URL"  # -> FAIL, EPI-TYPE-001 (mislabel caught)
```

To keep the demo instance authenticated instead, drop `--allow-unauthenticated` and call it with
`curl -H "Authorization: Bearer $(gcloud auth print-identity-token)" ...` (the script does not add
auth headers). Swap in one of your pipeline's own generated bundles for a demo on real data.

## Local development

Prerequisites: Docker for the container path; Java 21 + Maven 3.9+ for local builds. No network
(IG packages are vendored).

```bash
make build        # compile + run the full offline test suite (unit + integration)
make run          # run locally on :8080
make docker       # build the container image
make smoke        # validate the good/broken examples against a running instance
```

Layout under `service/src/main/java/com/epi/validator/`: `engine/` = validation chain + type
resolution, `checks/` = the five sanity rules + the ClinicalUseDefinition sub-profile pass, `web/`
= controllers, filters, body decoding, `service/` = orchestration. IG packages and their SHA-256
lockfile live in `service/src/main/resources/packages/` and `tools/`.

- **`examples/`**: `good-bundle.json` (passes), `broken-bundle.json` (fails), and
  `validate_bundle.py` (a dependency-free client whose exit code follows the verdict).
- **Cross-check vs the official HL7 validator** (in CI, and runnable locally): confirms this service
  reaches the same verdict as `org.hl7.fhir.validation`, the engine behind validator.fhir.org. Gated
  on `-Dcrosscheck` so normal builds stay offline:
  ```bash
  mvn -f service/pom.xml -Dcrosscheck=true test-compile failsafe:integration-test -Dit.test=OfficialValidatorCrossCheckIT
  ```
- **Refreshing a pinned IG package** is a deliberate change: see `tools/vendor-packages.sh --refresh`
  and re-run `tools/vendor-packages.sh --verify`. CI publishes a CycloneDX SBOM per build.

## Known limitations

- **Offline terminology.** External code systems (SNOMED CT, WHO ATC, MedDRA, EDQM, EMA SPOR, UNII)
  are not distributable offline, so a wrong code in one of them is a *warning*, not an error. Do not
  read `PASS_WITH_WARNINGS` as "terminology validated." Codes in known code systems (e.g. FHIR core)
  still error. Point the chain at an internal terminology server to restore strict checking.
- **Conformance, not correctness.** It checks bundle/profile conformance, not the medical accuracy
  of the label.
- **Type detection is a heuristic** - diagnostic only; the type contract is enforced only for an
  explicitly requested `epiType`.
- **One IG target:** `hl7.fhir.uv.emedicinal-product-info#1.0.0` (FHIR R5).

## License

Apache-2.0 (see [LICENSE](LICENSE)). Confirm it fits your organization's policy before external
distribution.
