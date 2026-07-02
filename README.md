# FHIR ePI Validator Microservice

A **regulated validation gate** for FHIR electronic Product Information (ePI) documents.

This service deterministically validates candidate ePI bundles (Types 1, 2, 3) produced by an
upstream agentic extraction pipeline (PDF/Word drug labels → FHIR), against:

1. **FHIR R5** base structural rules,
2. the **HL7 ePI Implementation Guide** (`hl7.fhir.uv.emedicinal-product-info`), and
3. **versioned, declarative policy packs** (deterministic document + type-gating rules),

and returns a gate verdict (`PASS` / `PASS_WITH_WARNINGS` / `FAIL`) together with a normalized,
machine-readable defect list designed to be fed back to an LLM agent for automated repair.

The service is a **non-AI control point**: the AI proposes, this validator accepts or rejects,
the policy layer explains why, and the audit layer proves exactly which rules, packages, and
inputs were involved.

## Architecture at a glance

```
 ┌────────────────────────┐   candidate ePI bundle    ┌──────────────────────────────┐
 │  Agentic extraction    │ ────────────────────────► │  ePI Validator (this repo)   │
 │  pipeline (PDF/Word →  │                           │                              │
 │  FHIR ePI bundles)     │ ◄──────────────────────── │  L1 parser                   │
 └────────────────────────┘   verdict + normalized    │  L2 FHIR R5 base             │
        │      ▲              defects (LLM-ready)     │  L3 HL7 ePI IG profiles      │
        │      │                                      │  L4 product policy pack      │
        ▼      │                                      │  L5 customer policy packs    │
   fix & retry loop                                   └──────────────────────────────┘
```

- **Service**: Java 21 · Spring Boot 3.5 · HAPI FHIR 8.10 (R5) — embeds the same core
  validation engine as the official HL7 validator. See [`service/`](service/).
- **Python client SDK**: [`clients/python/`](clients/python/) — `epi-validator-client`, the
  pipeline-side gate primitive (`validate_bundle()`, `gate()`, `to_llm_feedback()`).
- **Demo**: [`examples/pipeline_demo.py`](examples/) — the agentic validate → feedback →
  fix → revalidate loop.

## Key properties

| Property | How |
|---|---|
| Deterministic & air-gapped | IG packages vendored as SHA-256-pinned `.tgz` inside the image; no network at build or runtime |
| Dual IG targets | Published **STU1 1.0.0** (default) and a **pinned snapshot of the 1.1.0 CI build** with type-specific `bundle-epi-type1/2/3` profiles, selectable per request |
| Regulated gate semantics | `validationMode=gate`: explicit ePI type required, profile overrides rejected, warning policy enforced, full audit block |
| Type gating | `requestedEpiType` controls the validation target; auto-detection is diagnostic only and can never downgrade the gate |
| Warning governance | `pass-with-warnings` (dev) or `fail-unless-allowlisted` (production) with rationale-carrying allowlist entries |
| Auditability | Every response carries `traceId`, input SHA-256, build-manifest hash, IG package digests, and policy-pack versions |
| Pipeline-ready | Bounded concurrency (single bulkhead knob), readiness gated on validator warm-up, Prometheus metrics, structured logs |

## Quick start

```bash
# Build and run everything (service on :8080)
docker compose up --wait

# Validate an ePI example bundle (regulated gate mode)
curl -sS -X POST 'http://localhost:8080/api/v1/epi/validate?validationMode=gate&epiType=1' \
  -H 'Content-Type: application/fhir+json' \
  --data-binary @examples/bundles/1.0.0/Bundle-bundlepackageleaflet75type1.json | jq .verdict

# Run the agentic-loop demo (validate → LLM feedback → fix → revalidate)
docker compose --profile demo up --exit-code-from demo
```

Local development:

```bash
make build          # mvn verify (service)
make run            # boot the service locally
make python-test    # client SDK tests
make demo           # run pipeline_demo.py against a running service
```

API docs: `http://localhost:8080/swagger-ui.html` · OpenAPI: `/v3/api-docs`
Health: `/actuator/health/readiness` (false until validators are warmed).

## Endpoints

| Endpoint | Purpose |
|---|---|
| `POST /api/v1/epi/validate` | **The regulated pipeline gate (primary API).** Verdict + layered stats + normalized issues + audit |
| `POST /fhir/$validate` | FHIR interop/debug endpoint only. **Not** the regulated pipeline gate (returns HTTP 200 + OperationOutcome even on failure, per FHIR semantics) |
| `GET /api/v1/epi/igs` | Loaded IG packages, dependencies, and bundle profiles |
| `GET /api/v1/epi/manifest` | Immutable build manifest (package digests, policy-pack versions) |

## Documentation

- [`docs/architecture.md`](docs/architecture.md) — 5-layer validation model, validation modes, type resolution
- [`docs/api.md`](docs/api.md) — full API contract and response envelope
- [`docs/policy-packs.md`](docs/policy-packs.md) — authoring versioned policy packs
- [`docs/package-vendoring.md`](docs/package-vendoring.md) — IG package pinning and refresh procedure
- [`docs/gcp-deployment.md`](docs/gcp-deployment.md) — Cloud Run / GKE deployment guidance

## Known limitations

- Offline terminology validation may downgrade unknown code systems (EDQM, MedDRA, ATC) to warnings.
- The ePI 1.1.0 CI build is pinned but is **not an authorized HL7 publication**.
- Type detection is diagnostic and does not prove semantic completeness.
- The service validates bundle conformance, **not medical correctness** of label content.
- Remote terminology behavior may differ from offline terminology mode.
- The `suggestion` field in issues is reserved (always `null` in v1); JSON-pointer locations are
  best-effort and may be `null` where FHIRPath does not map cleanly (slices, choice types).

## License

Apache-2.0 (see [LICENSE](LICENSE)). Note: license choice is a project default — confirm it fits
your organization's policy before external distribution.
