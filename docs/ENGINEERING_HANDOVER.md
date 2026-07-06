# Engineering handover

What you are taking over, its boundaries, and what to check before relying on it.

## What this is

A stateless validation gate. A candidate FHIR ePI Bundle goes to `POST /api/v1/epi/validate`, is
parsed as FHIR R5, validated against the pinned HL7 ePI 1.0.0 IG with the official HAPI engine
plus six project rules, and comes back as `PASS | PASS_WITH_WARNINGS | FAIL` with located issues.
It sits after AI extraction or FHIR generation, and before commit, review, or exchange.

## What this is not

- Not a medical-content correctness checker. It checks conformance, not clinical accuracy.
- Not a terminology authority. Offline, external code systems (EDQM, MedDRA, ATC, SPOR) surface as
  warnings, not errors.
- Not an audit repository. It emits evidence (`inputSha256`, `traceId`, `validatorInfo`) but
  persists nothing. The calling workflow must store the request hash, full response, IG and
  lockfile versions, source document id, and reviewer decision in its system of record.
- Not authenticated. The platform (IAM, internal ingress, gateway) owns that boundary. See
  SECURITY.md.

## Architecture

`web/` (controllers, trace id, body decoding, limits) -> `service/ValidationService` (orchestrates,
derives the verdict) -> `engine/` (HAPI validator chain over the vendored packages, type
resolution, issue mapping) and `checks/` (the five simple rules plus ClinicalUseDefinition
sub-profile enforcement). Startup loads the IG and runs one warm-up validation (~30-60 s);
readiness flips only after that. API on `$PORT` (8080); health probes on 8081 in the container.
Raw input is validated (not a reserialized model) so issue line/column numbers match the bytes the
caller sent.

## Contracts

- Endpoint, envelope, and flags: README. Full issue catalog: docs/VALIDATION.md.
- Only `EPI-*` rule ids are stable, project-owned contract. `hapi-validator` ruleIds are
  diagnostics and can change with engine versions.
- OpenAPI spec is served at `/v3/api-docs`.

## Offline model

Runtime validation is fully offline (vendored, SHA-256-pinned packages; no outbound calls). The
Docker build resolves Maven dependencies, and the CI cross-check job downloads the official
validator's packages by design. Update the pinned IG with `tools/vendor-packages.sh` (writes
`tools/packages.lock.json`), then rebuild and rerun the full suite; expect verdict shifts on IG
version changes and review them deliberately.

## Run and test

```bash
make build     # compile + full offline test suite
make run       # local :8080
make docker    # container image
make smoke     # validate the sample bundles against a running instance
```

## Troubleshooting

- Container exits immediately: read the container logs first
  (`gcloud run services logs read epi-validator --region=...` or `docker logs`).
- Readiness stays 503: warm-up takes 30-60 s. If it never flips, check memory; the validator needs
  2 GiB. OOM aborts startup by design.
- 413: body over `max-body-mb` (8) or over `max-bundle-entries` (1000). Raise via env vars together
  with memory (README capacity note).
- 422: parsed but not a Bundle, or a bad `epiType` value. 400: unreadable or unparseable body (the
  unparseable case still returns the envelope with a `parser` issue).

## Production checklist

- [ ] Merge to `main` (CI and the deploy workflow trigger on `main`).
- [ ] Run the one-time WIF setup and set the three GitHub Actions variables (README).
- [ ] Set the vulnerability contact in SECURITY.md.
- [ ] Confirm ingress and auth: internal ingress + IAM invoker (never public in production).
- [ ] Agree the `PASS_WITH_WARNINGS` policy: block, review, or pass.
- [ ] Agree where audit evidence is persisted (this service stores nothing).
- [ ] Decide the demo UI posture: it is demo-only; the JSON API is the production interface.
- [ ] Optional: structured JSON logging for Cloud Logging severity parsing (adds
      `spring-cloud-gcp-starter-logging`; plain text works but severities show as default).

## Acceptance checklist (run before sign-off)

- [ ] `tools/vendor-packages.sh --verify` green.
- [ ] `mvn -f service/pom.xml verify` green (53 unit + 22 integration tests).
- [ ] `docker compose up -d --wait` then `make smoke` green.
- [ ] `./deploy/cloudrun.sh` ends with the three expected verdicts
      (PASS_WITH_WARNINGS / FAIL / FAIL).
