# Security

## Reporting a vulnerability

Report suspected vulnerabilities privately to the maintaining team. Replace this with your
internal security contact and process before external distribution. Do not open public issues for
security reports.

## Posture

This service is a stateless validation gate. It has no database, no persistence, and no user data
at rest. It runs fully offline.

| Control | Implementation |
|---|---|
| No runtime network | IG packages are vendored into the image and pinned by SHA-256. Validation is fully offline and the service makes no outbound calls. |
| Supply chain | Packages are pinned by SHA-256 in `tools/packages.lock.json` and verified in CI (`vendor-packages.sh --verify`). Maven dependencies are pinned through the HAPI BOM. A CycloneDX SBOM is produced per build. |
| Reproducible build | Pinned toolchain (Java 21, Spring Boot, HAPI) and `project.build.outputTimestamp`. |
| Container | Multi-stage build on a distroless-adjacent JRE base. Runs as a non-root user (uid 10001) with a bounded heap and metaspace, and fails fast on OOM. |
| Resource limits | Request bodies are capped (default 8 MB) while streaming and bundles at 1000 entries, so no single request can pin a worker or exhaust memory. Gzip is inflated under the same byte ceiling (bomb-safe). Concurrent validations are bounded by the Tomcat thread pool (default 4), sized so body-cap x concurrency fits the heap. |
| Fail-fast startup | A missing or corrupt package, or an invalid size limit, aborts startup with a non-zero exit. A broken instance never becomes ready. |

## Input hardening (regression-tested)

Untrusted FHIR JSON and XML is parsed by the HAPI/HL7 core with a hardened configuration. The
following are covered by the automated test suite and confirmed by live testing.

- **XXE (XML external entities):** disabled. External-entity references are rejected and never
  resolved, so there is no file disclosure or SSRF through the XML parser.
- **XML entity expansion ("billion laughs"):** bounded. Rejected in milliseconds.
- **Deeply nested JSON:** bounded. Rejected without a stack overflow, and the service stays
  healthy.
- **Charset handling:** bodies are decoded by their declared charset. An undecodable body is a
  clean 400, never silently corrupted content that could pass validation.
- **Trace-id and header injection:** `X-Trace-Id` is checked against a strict allowlist regex. CR,
  LF, and oversized values are discarded, so there is no HTTP response-header injection.
- **Oversized and compressed payloads:** capped while streaming (413), before the whole body is
  buffered.

## Out of scope (by design)

- **Authentication and authorization:** none at the application layer. Deploy behind platform IAM,
  for example Cloud Run internal ingress with an IAM invoker, or an API gateway.
- **Offline terminology correctness:** codes in external terminologies (SNOMED CT, WHO ATC, MedDRA,
  EDQM, UNII, EMA SPOR) are not validated offline and surface as warnings, not errors. See the
  README "Known limitations".
- **Medical correctness:** the service validates bundle and profile conformance, not the clinical
  accuracy of the label content.
