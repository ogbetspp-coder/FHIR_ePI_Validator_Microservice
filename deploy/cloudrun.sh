#!/usr/bin/env bash
# One command: deploy the FHIR ePI Validator to Google Cloud Run, then smoke-test it with
# the sample bundles. Safe to run from anywhere in the repo, and safe to re-run.
#
#   ./deploy/cloudrun.sh
#
# By default it builds with Cloud Build (server-side, no local Docker), which is what makes
# it work reliably in Cloud Shell. Override any of these by exporting them first:
#
#   PROJECT      GCP project id            (default: your current gcloud project)
#   REGION       Cloud Run region          (default: europe-west1)
#   REPO         Artifact Registry repo    (default: epi)
#   SERVICE      Cloud Run service name    (default: epi-validator)
#   OPEN         1 = public demo, 0 = IAM-only + internal ingress   (default: 1)
#   USE_DOCKER   1 = build and push locally instead of Cloud Build  (default: 0)
set -euo pipefail

# Always run from the repo root, whatever directory the script was called from.
cd "$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"

PROJECT="${PROJECT:-$(gcloud config get-value project 2>/dev/null || true)}"
REGION="${REGION:-europe-west1}"
REPO="${REPO:-epi}"
SERVICE="${SERVICE:-epi-validator}"
OPEN="${OPEN:-1}"
USE_DOCKER="${USE_DOCKER:-0}"

if [ -z "${PROJECT}" ]; then
  echo "ERROR: no GCP project set. Run:  gcloud config set project YOUR_PROJECT_ID" >&2
  exit 1
fi

TAG="$(git rev-parse --short HEAD 2>/dev/null || echo latest)"
IMAGE="${REGION}-docker.pkg.dev/${PROJECT}/${REPO}/${SERVICE}:${TAG}"
echo "==> Project ${PROJECT} | Region ${REGION}"
echo "==> Image   ${IMAGE}"
gcloud config set project "${PROJECT}" >/dev/null

# [1/4] Enable only the APIs that are not already on, and make sure the image repo exists.
# The checks are reads; only genuinely missing items issue a mutate, so re-runs stay well
# under the per-minute mutate quota.
echo "==> [1/4] Ensuring APIs are enabled and the image repo exists"
enabled="$(gcloud services list --enabled --format='value(config.name)' 2>/dev/null || true)"
to_enable=""
for svc in run.googleapis.com artifactregistry.googleapis.com cloudbuild.googleapis.com; do
  case " ${enabled} " in *" ${svc} "*) : ;; *) to_enable="${to_enable} ${svc}" ;; esac
done
if [ -n "${to_enable# }" ]; then
  gcloud services enable ${to_enable}
else
  echo "    APIs already enabled."
fi
gcloud artifacts repositories describe "${REPO}" --location="${REGION}" >/dev/null 2>&1 \
  || gcloud artifacts repositories create "${REPO}" \
       --repository-format=docker --location="${REGION}" \
       --description="FHIR ePI validator images"

# [2/4] Build and push the image, unless this exact tag is already in the registry. The build
# runs on Cloud Build (server-side) and finishes even if your shell disconnects, so a re-run
# then skips straight to deploy instead of rebuilding.
if gcloud artifacts docker images describe "${IMAGE}" --format='value(image_summary.digest)' >/dev/null 2>&1; then
  echo "==> [2/4] Image ${IMAGE} already in the registry; skipping build"
elif [ "${USE_DOCKER}" = "1" ]; then
  echo "==> [2/4] Building and pushing locally with Docker"
  gcloud auth configure-docker "${REGION}-docker.pkg.dev" --quiet
  docker build -t "${IMAGE}" -f service/Dockerfile .
  docker push "${IMAGE}"
else
  echo "==> [2/4] Building and pushing with Cloud Build (no local Docker)"
  # Cloud Build runs as the Compute Engine default service account, which on a new project
  # starts with no roles. Grant it what a build needs: read the uploaded source, push the
  # image to Artifact Registry, and write build logs. Idempotent, so re-runs are no-ops.
  PROJECT_NUMBER="$(gcloud projects describe "${PROJECT}" --format='value(projectNumber)')"
  CB_SA="${PROJECT_NUMBER}-compute@developer.gserviceaccount.com"
  for role in roles/cloudbuild.builds.builder roles/artifactregistry.writer roles/logging.logWriter; do
    gcloud projects add-iam-policy-binding "${PROJECT}" \
      --member="serviceAccount:${CB_SA}" --role="${role}" --condition=None >/dev/null
  done
  gcloud builds submit . --config deploy/cloudbuild.yaml --substitutions "_IMAGE=${IMAGE}"
fi

# [3/4] Deploy to Cloud Run. See README for what each flag is for.
echo "==> [3/4] Deploying to Cloud Run"
if [ "${OPEN}" = "1" ]; then
  ACCESS=(--ingress=all --allow-unauthenticated)          # open demo: lock down or delete after
else
  ACCESS=(--ingress=internal --no-allow-unauthenticated)  # gate with IAM instead
fi
if ! gcloud run deploy "${SERVICE}" \
  --image="${IMAGE}" --region="${REGION}" \
  --memory=2Gi --cpu=2 --cpu-boost \
  --min-instances=1 --concurrency=4 \
  "${ACCESS[@]}"; then
  echo "" >&2
  echo "Deploy failed. Most recent container logs (the real reason is usually here):" >&2
  gcloud logging read \
    "resource.type=\"cloud_run_revision\" resource.labels.service_name=\"${SERVICE}\"" \
    --limit=25 --freshness=1h --order=asc \
    --format='value(textPayload)' >&2 || true
  exit 1
fi

URL="$(gcloud run services describe "${SERVICE}" --region="${REGION}" --format='value(status.url)')"
[ -n "${URL}" ] || { echo "ERROR: could not read the service URL; see the deploy output above." >&2; exit 1; }
echo "==> Service URL: ${URL}"

# [4/4] Wait for warm-up, then feed the sample bundles and print the verdicts.
# The sample smoke test needs an open instance (validate_bundle.py sends no auth header).
if [ "${OPEN}" != "1" ]; then
  echo "==> Deployed locked down (OPEN=0). Skipping the sample smoke test; call it with a token:"
  echo "    curl -H \"Authorization: Bearer \$(gcloud auth print-identity-token)\" ${URL}/api/v1/epi/info"
  echo "Done. Service: ${URL}"
  exit 0
fi

echo "==> [4/4] Waiting for warm-up (first start is ~30-60 s)"
ready=0
for _ in $(seq 1 36); do
  if curl -fsS "${URL}/api/v1/epi/info" 2>/dev/null | grep -q '"ready"[: ]*true'; then
    ready=1; break
  fi
  sleep 5
done
[ "${ready}" = "1" ] || echo "WARN: still warming after 3 min; the checks below may return 503, retry shortly."

echo "==> info:"; curl -s "${URL}/api/v1/epi/info"; echo
echo "==> good-bundle, Type 1 (expect PASS_WITH_WARNINGS):"
python3 examples/validate_bundle.py examples/good-bundle.json   --epi-type 1 --base-url "${URL}" || true
echo "==> broken-bundle, Type 1 (expect FAIL, each error located):"
python3 examples/validate_bundle.py examples/broken-bundle.json --epi-type 1 --base-url "${URL}" || true
echo "==> good-bundle as Type 3 (expect FAIL, EPI-TYPE-001 catches the mislabel):"
python3 examples/validate_bundle.py examples/good-bundle.json   --epi-type 3 --base-url "${URL}" || true

echo
echo "Done. Service: ${URL}"
echo "This is an OPEN demo instance. When finished, delete it:"
echo "  gcloud run services delete ${SERVICE} --region=${REGION}"
