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

# [1/4] Enable the APIs and make sure the image repo exists. Both steps are idempotent.
echo "==> [1/4] Enabling APIs and ensuring the image repo exists"
gcloud services enable run.googleapis.com artifactregistry.googleapis.com cloudbuild.googleapis.com
gcloud artifacts repositories describe "${REPO}" --location="${REGION}" >/dev/null 2>&1 \
  || gcloud artifacts repositories create "${REPO}" \
       --repository-format=docker --location="${REGION}" \
       --description="FHIR ePI validator images"

# [2/4] Build and push the image.
if [ "${USE_DOCKER}" = "1" ]; then
  echo "==> [2/4] Building and pushing locally with Docker"
  gcloud auth configure-docker "${REGION}-docker.pkg.dev" --quiet
  docker build -t "${IMAGE}" -f service/Dockerfile .
  docker push "${IMAGE}"
else
  echo "==> [2/4] Building and pushing with Cloud Build (no local Docker)"
  gcloud builds submit . --config deploy/cloudbuild.yaml --substitutions "_IMAGE=${IMAGE}"
fi

# [3/4] Deploy to Cloud Run. See README for what each flag is for.
echo "==> [3/4] Deploying to Cloud Run"
if [ "${OPEN}" = "1" ]; then
  ACCESS=(--ingress=all --allow-unauthenticated)          # open demo: lock down or delete after
else
  ACCESS=(--ingress=internal --no-allow-unauthenticated)  # gate with IAM instead
fi
gcloud run deploy "${SERVICE}" \
  --image="${IMAGE}" --region="${REGION}" \
  --memory=2Gi --cpu=2 --cpu-boost \
  --min-instances=1 --concurrency=4 \
  "${ACCESS[@]}"

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
