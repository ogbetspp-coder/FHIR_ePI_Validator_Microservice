# ADR-0001: Runtime and ingress model

Status: accepted.

## Context

The service gates AI-generated FHIR ePI bundles in a regulated pipeline. The HAPI validator loads
the IG and terminology into memory (~1.5 GB resident) and each validation pins a CPU-bound thread
for its duration.

## Decision

- Stateless validation gate: no database, no persistence, no user data at rest. Audit evidence is
  emitted in the response and persisted by the caller.
- Cloud Run as the reference runtime, deployed with internal ingress and an IAM invoker. No
  application-layer authentication; the platform owns that boundary.
- Runtime is offline: IG packages are vendored into the image and SHA-256-pinned. Builds resolve
  Maven dependencies; the CI cross-check job is network-enabled by design.
- 2 GiB memory, 2 CPU baseline. `--min-instances=1` because a cold start pays the 30-60 s warm-up.
- `--concurrency=4` equals `server.tomcat.threads.max` (4), and `max-body-mb` (8) x concurrency
  must fit the heap (measured). Changing one requires changing the others.
- Health probes on a separate management port (8081) in the container so request load cannot
  starve them.
- The fat jar is run directly (`java -jar`); exploded-layer images proved builder-sensitive and
  produced an unbootable image on Cloud Build.

## Consequences

- Scaling changes are a memory-math exercise, not a flag flip.
- The caller (pipeline) is responsible for audit retention and for the warnings policy.
- Any future auth requirement lands in the platform layer (gateway, IAM), not in this codebase.
