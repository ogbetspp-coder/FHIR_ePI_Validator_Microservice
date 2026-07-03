# Security

## Reporting a vulnerability

Report suspected vulnerabilities privately to the maintaining team (replace with your internal
security contact / process before external distribution). Do not open public issues for
security reports.

## Posture

This service is a stateless validation gate with no database, no persistence, and no user data
at rest. It is designed to run air-gapped.

| Control | Implementation |
|---|---|
| No runtime network | IG packages are vendored into the image (SHA-256-pinned); validation is fully offline. Only an optional, explicitly-configured terminology server would add egress. |
| Supply chain | Packages pinned by SHA-256 in `tools/packages.lock.json` and verified in CI (`vendor-packages.sh --verify`); Maven dependencies pinned via the HAPI BOM; a CycloneDX SBOM is produced per build. |
| Reproducible build | Pinned toolchain (Java 21, Spring Boot, HAPI), `project.build.outputTimestamp`. |
| Container | Multi-stage build, distroless-adjacent JRE base, runs as a non-root user (uid 10001), bounded heap/metaspace with fail-fast on OOM. |
| Resource limits | Request bodies capped (default 50 MB) and enforced while streaming; gzip inflated under the same ceiling (bomb-safe); concurrent validations bounded (Tomcat threads) so load cannot exhaust the heap. |
| Fail-fast startup | A missing or corrupt package aborts startup with a non-zero exit — a broken instance never becomes ready. |

## Input hardening (regression-tested)

Untrusted FHIR JSON/XML is parsed by the HAPI/HL7 core with a hardened configuration. The
following are verified by the automated test suite and were confirmed by live testing:

- **XXE (XML External Entities)** — disabled; external-entity references are rejected and never
  resolved (no file disclosure / SSRF via the XML parser).
- **XML entity expansion ("billion laughs")** — bounded; rejected in milliseconds.
- **Deeply nested JSON** — bounded; rejected without a stack overflow; the service stays healthy.
- **Charset handling** — bodies are decoded by their declared charset; an undecodable body is a
  clean `400`, never silently corrupted content that could pass validation.
- **Trace-id / header injection** — `X-Trace-Id` is validated against a strict allowlist regex;
  CR/LF or oversized values are discarded (no HTTP response-header injection).
- **Oversized / compressed payloads** — capped while streaming (`413`), before the whole body is
  buffered.

## Out of scope (by design)

- **Authentication / authorization** — none at the application layer; deploy behind platform IAM
  (e.g. Cloud Run internal ingress + IAM invoker, or an API gateway).
- **Terminology correctness offline** — codes in external terminologies (SNOMED CT, WHO ATC,
  MedDRA, EDQM, UNII, EMA SPOR) are not validated offline and surface as warnings, not errors.
  See the README "Known limitations".
- **Medical correctness** — the service validates bundle/profile conformance, not the clinical
  accuracy of label content.
