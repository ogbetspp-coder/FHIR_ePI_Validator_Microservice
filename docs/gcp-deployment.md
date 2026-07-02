# GCP Deployment

## Cloud Run (recommended)

```bash
gcloud run deploy epi-validator \
  --image=REGION-docker.pkg.dev/PROJECT/REPO/epi-validator:GIT_SHA \
  --memory=2Gi --cpu=2 \
  --min-instances=1 \
  --concurrency=8 \
  --timeout=300 \
  --cpu-boost \
  --ingress=internal \
  --no-allow-unauthenticated \
  --set-env-vars=EPI_VALIDATION_DEFAULTVALIDATIONMODE=gate,EPI_VALIDATION_WARNINGPOLICY_MODE=fail-unless-allowlisted,LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs
```

Why these values:

| Flag | Value | Reason |
|---|---|---|
| `--memory=2Gi` | hard floor | two IG chains + hl7.terminology in memory + snapshots ≈ 1.2–1.5 GB heap; do not undersize |
| `--min-instances=1` | never scale to zero | cold start pays 30–90 s of package loading + snapshot generation |
| `--concurrency=8` | = `EPI_VALIDATION_MAXCONCURRENTVALIDATIONS` | validation is CPU-bound; the app bulkhead and platform concurrency must agree — never stack unbounded layers |
| `--cpu-boost` | on | halves warm-up time |
| startup probe | `/actuator/health/readiness`, `initialDelaySeconds: 20`, `periodSeconds: 10`, `failureThreshold: 18` | readiness flips only after warm-up validations run |
| liveness probe | `/actuator/health/liveness` | |
| `--ingress=internal --no-allow-unauthenticated` | platform auth | the service ships no app-level auth in v1 by design; use IAM invoker + internal ingress (or IAP) |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` | JSON logs | Cloud Logging ingests structured JSON; `traceId` is on every line |

## Environment variable reference (relaxed binding: dashes dropped)

| Variable | Default | Purpose |
|---|---|---|
| `EPI_VALIDATION_DEFAULTIG` | `1.0.0` | IG used when the request omits `igVersion` |
| `EPI_VALIDATION_DEFAULTVALIDATIONMODE` | `exploratory` | **set `gate` in production** |
| `EPI_VALIDATION_MAXBODYMB` | `50` | post-inflation request size cap (413) |
| `EPI_VALIDATION_MAXCONCURRENTVALIDATIONS` | `8` | the single bulkhead knob (429 when full) |
| `EPI_VALIDATION_REQUESTTIMEOUTSECONDS` | `120` | per-validation timeout (503) |
| `EPI_VALIDATION_CONCURRENTBUNDLEVALIDATION` | `false` | keep off until capacity-planned |
| `EPI_VALIDATION_UNKNOWNCODESYSTEMSEVERITY` | `warning` | `error` only with a terminology server |
| `EPI_VALIDATION_FHIRNATIVEENDPOINTENABLED` | `true` | disable `$validate` if unused in prod |
| `EPI_VALIDATION_REMOTETERMINOLOGY_ENABLED` | `false` | enable with an internal tx server |
| `EPI_VALIDATION_REMOTETERMINOLOGY_URL` | — | e.g. Ontoserver / tx mirror base URL |
| `EPI_VALIDATION_WARNINGPOLICY_MODE` | `pass-with-warnings` | **set `fail-unless-allowlisted` in production** |

Warning-policy allowlist entries are list-shaped and easiest managed in a mounted
`application.yaml` (Cloud Run volume / GKE ConfigMap) rather than env vars.

## Terminology

Offline mode (default) downgrades unknown code systems (EDQM, MedDRA, ATC, EMA SPOR lists)
to warnings — govern them via the allowlist with rationales. For full strictness run an
internal terminology server (e.g. Ontoserver on GKE/Cloud Run) and set
`EPI_VALIDATION_REMOTETERMINOLOGY_*`; note remote behavior may differ from offline mode —
re-baseline expected warnings when enabling it.

## GKE alternative

Use the same container. Set requests/limits `memory: 2Gi`, `cpu: 2`; startup probe on
readiness (failureThreshold ≥ 18, period 10 s), liveness on liveness; HPA on CPU (validation
is CPU-bound); PodDisruptionBudget ≥ 1. Keep replicas ≥ 1 at all times (warm-up cost).

## Image supply chain

The image is built from SHA-256-pinned IG packages (see `docs/package-vendoring.md`); CI
uploads a CycloneDX SBOM artifact per build. Tag images with the git SHA; the running
service exposes its build manifest at `/api/v1/epi/manifest` and echoes the manifest hash in
every validation response, so any response can be traced back to the exact package set.
