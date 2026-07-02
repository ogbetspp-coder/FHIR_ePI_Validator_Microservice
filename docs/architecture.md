# Architecture

## Role in the pipeline

This service is the **regulated validation gate** of an agentic ePI pipeline: an extraction
agent turns PDF/Word drug labels into candidate FHIR ePI document bundles (Types 1/2/3) and
POSTs them here. The service deterministically validates, returns a verdict plus a normalized,
LLM-consumable defect list, and prevents downstream commit until the bundle passes (or passes
under a controlled warning policy).

It is a **non-AI control point**: the AI proposes, this validator accepts or rejects, the
policy layer explains why, and the audit layer proves exactly which rules, packages, and
inputs were involved.

## The 5-layer validation model

```
Layer 1  parser   parse / well-formedness            (HAPI JSON/XML parser)
Layer 2  fhir     FHIR R5 base structural validation (validator core)
Layer 3  ig       HL7 ePI IG profile validation      (per-IG-version chain)
Layer 4  policy   product policy pack                (epi-gate-core)
Layer 5  policy   customer/regional policy packs     (config-added)
```

Every issue carries `layer` and a structured `source`; stats are reported per layer. In a
review you can state precisely: "this failed our policy pack, not the HL7 IG" or "this is an
ePI profile issue, not a parser issue". Never blur these categories.

Layer classification for validator output: issues whose profile context is under the ePI
canonical (`http://hl7.org/fhir/uv/emedicinal-product-info/...`) → `ig`; otherwise → `fhir`.
Policy-engine issues → `policy`; parse failures → `parser`.

## Validation modes

| | `exploratory` | `gate` |
|---|---|---|
| `epiType=auto` | allowed | rejected (422) |
| Profile override (`profile=` param) | allowed (config default) | rejected (422) unless `profile-override.allowed-in-modes` includes `gate` |
| Warning policy | enforced as configured | enforced as configured |
| Intended use | agent debugging, workbench preview, malformed inbound triage | **the regulated pipeline path** |

Server default mode is configurable (`epi.validation.default-validation-mode`); production
deployments set `gate`. Production pipeline callers SHOULD pass `epiType` explicitly and use
`validationMode=gate`.

## Type resolution — auto-detection never weakens the gate

```
requestedEpiType = caller's contract intent ("1"|"2"|"3"|"auto")
detectedEpiType  = inferred from bundle contents (diagnostic, always reported)
effectiveEpiType = requested, unless requested=auto, then detected
```

The validation target (profile + type rules) follows `effectiveEpiType`. This prevents the
catastrophic case where a supposed Type 3 bundle missing its clinical content would be
auto-detected as Type 2, validated against the weaker profile, and accidentally pass.
Type-gating rules in the core policy pack close the loop:

- `EPI-TYPE-001` (error): requested Type 3 but no clinical structured content
  (ClinicalUseDefinition / MedicationKnowledge)
- `EPI-TYPE-002` (error): requested Type 2/3 but no structured product data
- `EPI-TYPE-003` (warning): requested Type 1 but Type 2/3 resources present

Detection heuristics (verified against the IG's own examples): `ClinicalUseDefinition` or
`MedicationKnowledge` → Type 3; any of `MedicinalProductDefinition`, `PackagedProductDefinition`,
`AdministrableProductDefinition`, `ManufacturedItemDefinition`, `Ingredient`,
`SubstanceDefinition`, `RegulatedAuthorization` → Type 2; else `Composition` → Type 1.

## Isolated validation chains per IG version

ePI STU1 1.0.0 and the 1.1.0 CI snapshot define StructureDefinitions under the **same
canonical base**. The service therefore builds one fully isolated
`ValidationSupportChain` per configured IG (`ValidatorFactory`) and never merges them —
a merged chain would resolve profiles across versions non-deterministically.

Chain composition (in order): `NpmPackageValidationSupport` (vendored IG + dependency tgz) →
`DefaultProfileValidationSupport` → `CommonCodeSystemsTerminologyService` →
[`RemoteTerminologyServiceValidationSupport` when enabled] →
`InMemoryTerminologyServerValidationSupport` → `SnapshotGeneratingValidationSupport` →
`UnknownCodeSystemWarningValidationSupport`, wrapped in the chain's built-in cache
(`CachingValidationSupport` is deprecated in HAPI 8.x and not used).

**Raw-string validation**: bundles are validated from the raw request bytes, never a
re-serialized object — that is what preserves line/column positions in issue locations for
the repair loop.

## Warning governance

Warnings never silently become pass conditions:

- `pass-with-warnings` (dev default): errors fail; warnings yield `PASS_WITH_WARNINGS`.
- `fail-unless-allowlisted` (production): errors fail; warnings fail too unless they match a
  governed allowlist entry with a documented rationale (a *controlled deviation*). Matched
  issues are flagged `allowlisted: true` and carry the rationale.

Offline terminology reality: ePI content references EDQM Standard Terms, MedDRA, and WHO ATC
codes with no distributable offline CodeSystems. `UnknownCodeSystemWarningValidationSupport`
downgrades unknown-code-system findings to warnings (configurable); a remote terminology
server (Ontoserver / tx mirror) restores strictness where deployed.

## Audit and traceability

- Build-time: the SHA-256-pinned package lockfile is sealed into the jar; at startup the
  service derives the immutable **build manifest** (buildId, HAPI version, package digests,
  policy pack versions) and its hash. `GET /api/v1/epi/manifest`.
- Per request: the envelope's `audit` block records the manifest hash, IG package digest,
  policy packs, warning-policy mode, validation mode, **input SHA-256 + size**, and the
  OperationOutcome SHA-256 — complete evidence of exactly what judged exactly which input.
- `traceId` (accepted via `X-Trace-Id` or W3C `traceparent`, else generated) appears in the
  envelope, the response header, and every log line (MDC) — one id connects source document →
  extraction → candidate bundle → validation → approval (ALCOA+ style).

## Concurrency model

One knob: `epi.validation.max-concurrent-validations` (bounded pool, no queue). Excess
requests get 429 + `Retry-After`; validations exceeding `request-timeout-seconds` are
cancelled with 503. HAPI bundle-level concurrency is **off** in the first production cut.
Align the bulkhead with the platform's per-instance concurrency (Cloud Run `--concurrency`);
never stack unbounded concurrency layers.

## Implementation red lines

1. Do not merge IG validation chains.
2. Do not let auto-detection choose a weaker gate than the requested type.
3. Do not validate a parsed/re-serialized object when line/column is needed.
4. Do not treat HTTP 200 as validation pass.
5. Do not allow warnings to become uncontrolled pass conditions.
6. Do not let profile override bypass effectiveEpiType in gate mode.
7. Do not report ready before packages load, snapshots generate, and warmup validation runs.
