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

`PASS_WITH_WARNINGS` is not an approval. It means no structural or profile errors, with warnings
remaining (usually offline terminology). Your workflow decides whether warnings block, route to
human review, or pass.

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

**Runtime validation is fully offline**: IG packages are vendored into the image and SHA-256-pinned
in `tools/packages.lock.json`, and validation makes no outbound calls. Two deliberate exceptions:
the Docker build resolves Maven dependencies (Maven Central), and the CI cross-check job downloads
the official validator's packages. Neither touches the running service.

Full catalog of issue types, severities, sources, the fixed `EPI-*` rules, the engine check
categories, and HTTP status codes: [docs/VALIDATION.md](docs/VALIDATION.md). Handover notes,
runbook, and checklists: [docs/ENGINEERING_HANDOVER.md](docs/ENGINEERING_HANDOVER.md).

## Deploy to Google Cloud Run

Prerequisites: the `gcloud` CLI and a GCP project. The build runs in Cloud Build, so you do not
need local Docker (this is what makes it work cleanly in Cloud Shell).

One command builds the image, deploys it, and smoke-tests it with the sample bundles:

```bash
gcloud config set project YOUR_PROJECT_ID
./deploy/cloudrun.sh
```

It enables the APIs, creates the image repo if needed, grants the Cloud Build service account
its roles, builds and pushes with Cloud Build, deploys to Cloud Run, waits for warm-up, then
prints the verdicts for the good and broken bundles. The first run needs a project owner or
admin (it edits IAM once); later runs and other users do not. Override defaults by exporting env
vars first: `REGION` (default `europe-west1`), `REPO`, `SERVICE`, `OPEN=0` (IAM-only instead of a
public demo), `USE_DOCKER=1` (build locally instead of Cloud Build). Delete the demo when done:
`gcloud run services delete epi-validator --region=europe-west1`.

<details>
<summary>Prefer to run the steps by hand? Expand.</summary>

```bash
# Set these for your environment
PROJECT=your-project;  REGION=europe-west1;  REPO=epi
gcloud config set project "$PROJECT"

# 0. One-time setup: enable the APIs, create the image repo, let Docker push to it
gcloud services enable run.googleapis.com artifactregistry.googleapis.com
gcloud artifacts repositories create "$REPO" --repository-format=docker --location="$REGION"
gcloud auth configure-docker "$REGION-docker.pkg.dev" --quiet

# 1. Build the image (service/Dockerfile, context = repo root) and push it
IMAGE="$REGION-docker.pkg.dev/$PROJECT/$REPO/epi-validator:$(git rev-parse --short HEAD)"
make docker
docker tag epi-validator:local "$IMAGE"
docker push "$IMAGE"

# 2. Deploy
gcloud run deploy epi-validator \
  --image="$IMAGE" --region="$REGION" \
  --memory=2Gi --cpu=2 --cpu-boost \
  --min-instances=1 --concurrency=4 \
  --ingress=internal --no-allow-unauthenticated
```
</details>

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
  `--set-env-vars EPI_VALIDATION_MAXBODYMB=...,EPI_VALIDATION_MAXBUNDLEENTRIES=...`. The Tomcat
  pool is overridable with `SERVER_TOMCAT_THREADS_MAX`; if you raise it, set `--concurrency` to the
  same value and redo the memory math.

Auth, network posture, and tested threat cases are in [SECURITY.md](SECURITY.md).

### Demo on your own data

`./deploy/cloudrun.sh` already deploys an open instance and runs the three sample validations:
the good bundle (PASS_WITH_WARNINGS), the broken bundle (FAIL, each error located), and the good
bundle mislabelled as Type 3 (FAIL, EPI-TYPE-001 catches it). To validate one of your pipeline's
own bundles against the running service, point `validate_bundle.py` at its URL (Python 3 only, no
dependencies):

```bash
URL=$(gcloud run services describe epi-validator --region=europe-west1 --format='value(status.url)')
python3 examples/validate_bundle.py path/to/your-bundle.json --epi-type 1 --base-url "$URL"
```

For an authenticated instance (`OPEN=0`), add `-H "Authorization: Bearer $(gcloud auth print-identity-token)"`
to your `curl` calls; `validate_bundle.py` itself sends no auth header.

### Show it to colleagues

The service root (`/`) serves a small self-contained web UI. Pick a type (1, 2, or 3) and
correct or incorrect to load a matching sample and validate it, or paste your own bundle and click
Validate. It shows the verdict with each error and warning located by line. It is a single static
file served by the app (no CDN, no external calls, same-origin with the API). Open the Cloud Run
URL in a browser. The UI is a demo surface: on a locked-down deployment (`OPEN=0`) it sits behind
IAM and sends no auth headers, so treat the JSON API as the production interface.

`examples/demo.sh` runs the same three cases from the terminal. Point it at your Cloud Run URL:

```bash
./examples/demo.sh https://epi-validator-XXXX-ew.a.run.app
```

It walks through three cases: a valid leaflet (PASS_WITH_WARNINGS), the same leaflet with two
AI-style defects (FAIL, each located by line), and the leaflet mislabelled as Type 3 (FAIL, type
contract rejected). `good-bundle.json` and `broken-bundle.json` are the official HL7 Type 1
example, cleaned and defect-seeded respectively.

The three unmodified official IG examples are in `examples/ig/` (Type 1, 2, and 3). Run as-is they
all FAIL, because they contain real reference and narrative defects the official engine flags,
which is a good way to show the validator earns its keep even on the reference examples. See
`examples/ig/README.md`.

## Continuous deployment (GitHub Actions)

`.github/workflows/deploy.yml` builds and deploys to Cloud Run on every push to `main` (and on
manual dispatch), with no service-account keys, using Workload Identity Federation. It deploys a
locked-down service (internal ingress, IAM only); for a public demo use `deploy/cloudrun.sh`.
Both CI and deploy trigger on `main`, so they activate once this branch is merged.

One-time setup, run once by a project owner (replace `REPO` with your `owner/repo`):

```bash
PROJECT=your-project;  REGION=europe-west1;  REPO=your-org/your-repo
NUM=$(gcloud projects describe "$PROJECT" --format='value(projectNumber)')

gcloud services enable run.googleapis.com artifactregistry.googleapis.com cloudbuild.googleapis.com --project "$PROJECT"
gcloud artifacts repositories create epi --project "$PROJECT" --repository-format=docker --location "$REGION" 2>/dev/null || true

# Deployer service account + the roles a build-and-deploy needs
gcloud iam service-accounts create epi-deployer --project "$PROJECT" --display-name "ePI CD"
SA="epi-deployer@$PROJECT.iam.gserviceaccount.com"
for r in run.admin cloudbuild.builds.editor artifactregistry.writer iam.serviceAccountUser storage.admin; do
  gcloud projects add-iam-policy-binding "$PROJECT" --member "serviceAccount:$SA" --role "roles/$r"; done
# Cloud Build runs as the Compute Engine default SA; grant it what a build needs
for r in cloudbuild.builds.builder artifactregistry.writer logging.logWriter; do
  gcloud projects add-iam-policy-binding "$PROJECT" --member "serviceAccount:$NUM-compute@developer.gserviceaccount.com" --role "roles/$r"; done

# Workload Identity pool + provider that trusts this GitHub repo, then let it impersonate the SA
gcloud iam workload-identity-pools create github --project "$PROJECT" --location global
gcloud iam workload-identity-pools providers create-oidc github --project "$PROJECT" --location global \
  --workload-identity-pool github --issuer-uri "https://token.actions.githubusercontent.com" \
  --attribute-mapping "google.subject=assertion.sub,attribute.repository=assertion.repository" \
  --attribute-condition "assertion.repository=='$REPO'"
POOL=$(gcloud iam workload-identity-pools describe github --project "$PROJECT" --location global --format='value(name)')
gcloud iam service-accounts add-iam-policy-binding "$SA" --project "$PROJECT" \
  --role roles/iam.workloadIdentityUser \
  --member "principalSet://iam.googleapis.com/$POOL/attribute.repository/$REPO"
echo "GCP_WIF_PROVIDER=$POOL/providers/github"
```

Then in GitHub, under Settings -> Secrets and variables -> Actions -> Variables, add three
repository variables: `GCP_PROJECT` (project id), `GCP_DEPLOY_SA` (the `epi-deployer@...` email),
and `GCP_WIF_PROVIDER` (printed by the last command). Pushes to `main` now deploy automatically.

## Local development

Prerequisites: Docker for the container path; Java 21 + Maven 3.9+ for local builds. IG packages
are vendored; the first build fetches Maven dependencies, after that everything runs offline.

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
