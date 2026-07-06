# Validation reference

Everything the service checks, the issue types it can emit, and how they map to a verdict. It runs
the official HAPI FHIR / HL7 validation engine (`org.hl7.fhir.validation`, the engine behind
validator.fhir.org) against FHIR R5 and the pinned ePI IG
(`hl7.fhir.uv.emedicinal-product-info` 1.0.0), plus a few ePI-specific checks, fully offline at
runtime (see the README for the build-time distinction).

## Verdict

Every issue has a severity. The verdict is derived from the merged issue list:

| Verdict | When |
|---|---|
| `FAIL` | any `fatal` or `error` issue |
| `PASS_WITH_WARNINGS` | at least one `warning`, and no error |
| `PASS` | no error and no warning |

HTTP status is `200` whenever validation ran, whatever the verdict. Gate on `verdict`, never on the
HTTP status.

`PASS_WITH_WARNINGS` is not an approval. It means no structural or profile errors, with warnings
remaining (usually offline terminology, see below). The consuming workflow must define whether
warnings block commit, route to review, or pass.

## Severities

| Severity | Meaning | Verdict impact |
|---|---|---|
| `fatal` | validation could not continue | FAIL |
| `error` | conformance violation | FAIL |
| `warning` | should fix, or not checkable offline | PASS_WITH_WARNINGS |
| `information` | advisory / best-practice note | none |

## Sources

Each issue's `source` says which layer produced it:

| Source | Layer |
|---|---|
| `parser` | input could not be parsed as FHIR R5 |
| `hapi-validator` | the official engine: FHIR R5 plus the ePI IG profiles |
| `simple-check` | ePI document and type-contract rules (below) |
| `clinical-profile` | ClinicalUseDefinition sub-profile enforcement (below) |

## Service rules (fixed, enumerable)

Checks this service adds on top of the engine. They have stable `ruleId`s, so a pipeline can branch
on them.

| ruleId | source | severity | Fires when | Example message |
|---|---|---|---|---|
| `EPI-DOC-001` | simple-check | error | `Bundle.type` is not `document` | Bundle.type must be 'document' for an ePI, found 'collection' |
| `EPI-DOC-002` | simple-check | error | first entry is not a `Composition` | The first entry of an ePI document bundle must be a Composition, found Organization |
| `EPI-DOC-003` | simple-check | error | an internal reference does not resolve to a bundle entry | Reference 'Organization/x' in Composition does not resolve to any entry in the document bundle |
| `EPI-TYPE-001` | simple-check | error | `epiType=3` requested but no clinical content | Requested ePI Type 3 but the bundle contains no machine-readable clinical content (ClinicalUseDefinition / MedicationKnowledge) |
| `EPI-TYPE-002` | simple-check | error | `epiType=2` or `3` requested but no product data | Requested ePI Type 2 but the bundle contains no structured product data (MedicinalProductDefinition / ...) |
| `EPI-CUD-PROFILE` | clinical-profile | error or warning | a `ClinicalUseDefinition` violates the ePI sub-profile for its type | (engine message) (ePI indication profile) |

Notes:

- Only these `EPI-*` rule ids are project-owned and stable. `hapi-validator` ruleIds are engine
  diagnostics that can change across engine versions; do not build long-term workflow logic on
  them.
- `EPI-TYPE-*` fire only for an explicitly requested `epiType` (1, 2, or 3). With `epiType=auto`
  there is no contract to enforce, and `auto` never downgrades an explicit request.
- `EPI-CUD-PROFILE` validates every `ClinicalUseDefinition` against its type-specific ePI
  sub-profile (indication, contraindication, interaction, undesirable-effect, warning), even when
  the resource omits `meta.profile`, so a Type 3 bundle cannot skip the constraints that define
  Type 3. Its severity is whatever the sub-profile violation is.

## Engine checks (open-ended)

The `hapi-validator` issues come from the official engine and are not a fixed list: it draws from
the full FHIR R5 plus IG message catalog (the same one validator.fhir.org uses). They fall into
these categories:

| Category | What it catches | Example message |
|---|---|---|
| Structure / profile | elements not allowed by the profile, missing required elements, fixed or pattern values, unknown slices | Element 'x' is unknown or does not match any slice for the profile |
| Cardinality | too few or too many occurrences of an element | Element 'title': minimum required = 1, but only found 0 |
| Data types and formats | malformed dates, codes, URIs, quantities, ids | The value '...' is not a valid date/time |
| Invariants (FHIRPath) | named constraints on a resource | Constraint failed: cmp-1: 'A section must contain at least one of text, entries, or sub-sections' |
| Terminology | code-system and value-set bindings, expansions | CodeSystem is unknown and can't be validated: http://example.org (warning, see below) |
| References and bundle integrity | unresolved references, entries unreachable from the Composition | Entry 'urn:uuid:...' isn't reachable by traversing links (forward or backward) from the Composition |
| Narrative (XHTML) | narrative well-formedness, unresolved internal hyperlinks | Hyperlink '#Organization_...' at 'div/p/a' could not be resolved |
| Best practice | advisory notes, usually `information` | The string value contains text that looks like embedded HTML tags |

The definitive message set is the official validator's; this service reports what that engine
produces, unchanged. CI cross-checks that the verdict agrees with a standalone run of
`org.hl7.fhir.validation`.

## Unknown code systems (offline policy)

Real ePIs reference code systems with no freely distributable representation (EDQM Standard Terms,
MedDRA, WHO ATC, EMA SPOR). Offline they cannot be expanded, so unknown code systems are downgraded
to `warning` instead of `error`. Otherwise every real ePI would hard-fail. This is why a valid
leaflet returns `PASS_WITH_WARNINGS`, not `PASS`.

## ePI type detection and contract

`epiType` = `1` | `2` | `3` | `auto` (default). The effective type is the requested type, unless
`auto`, in which case it is detected from content. Detection keys off resource types present in the
bundle:

- Type 3 markers (machine-readable clinical data): `ClinicalUseDefinition`, `MedicationKnowledge`.
- Type 2 markers (structured product data): `MedicinalProductDefinition`, `PackagedProductDefinition`,
  `AdministrableProductDefinition`, `ManufacturedItemDefinition`, `Ingredient`,
  `RegulatedAuthorization`.
- Otherwise Type 1 (narrative leaflet).

An explicit request is never downgraded: declare Type 3 and ship Type 2 content and it fails with
`EPI-TYPE-001`.

## Request-level errors (HTTP)

These mean the request never reached validation (no envelope, except the 400-unparseable case):

| Status | Meaning |
|---|---|
| `200` | validation ran; read `verdict` |
| `400` | body unreadable, bad gzip, or unparseable FHIR (unparseable still returns the envelope, verdict `FAIL`, one `parser` issue) |
| `413` | body over the size cap (`max-body-mb`) or entry-count cap (`max-bundle-entries`) |
| `415` | unsupported `Content-Type` |
| `422` | parsed, but not a `Bundle`, or an invalid `epiType` value |
| `500` | unexpected internal error (traceId in the response) |
| `503` | `GET /actuator/health/readiness` only, while the IG loads and warms up (about 30 to 60 s after start) |
