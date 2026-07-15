# Newer-IG drift baseline

Three ePI document bundles (Type 1/2/3) authored against a **newer ePI IG** than this validator
pins (1.0.0 STU1). Against 1.0.0 all three FAIL, which is correct version-drift detection, and they
are kept here as a negative baseline (contrast with the passing goldens one level up).

- `bundle-epi-type1-example-paracetamol.json` - Type 1 (Paracetamol)
- `bundle-epi-type2-example-paracetamol.json` - Type 2 (Paracetamol / WonderDrug)
- `bundle-epi-type3-example-diflucan.json` - Type 3 (Diflucan, with ClinicalUseDefinition and
  MedicationKnowledge)

## Baseline outcome (pinned IG 1.0.0, HAPI 8.10.0)

| Bundle | epiType | Verdict | Errors | Dominant reason |
|---|---|---|---|---|
| type1 paracetamol | 1 | FAIL | 5 | profiles and required elements not in 1.0.0 |
| type2 paracetamol | 2 | FAIL | 15 | newer codesystem codes plus profile gaps |
| type3 diflucan | 3 | FAIL | 13 | newer codesystem codes and profiles, plus one data bug |

## Why they fail

They declare the newer per-type profiles and codes, which 1.0.0 does not define:

- **Profiles not in 1.0.0** (`bundle-epi-type1/2/3`, `composition-epi-type1`, `binary-epi`): the
  1.0.0 package profiles the document as `Bundle-uv-epi`, so these references do not resolve and,
  with strict unknown-profile checking, become errors.
- **Newer codesystem codes** (`.../CodeSystem/epi-ig#indication`, `#warning`, and similar): not
  defined in 1.0.0, reported as unknown codes.
- **Required elements** the 1.0.0 profile mandates but the bundle omits (for example
  `Bundle.language`).
- A few genuine data issues, independent of version: a contained `Binary` not referenced from its
  resource (type1 and type2), and a patient-characteristic age range with low greater than high,
  invariant `rng-2` (type3).

To make these pass you would pin the validator to the ePI IG version they target, or convert them
to 1.0.0. The passing goldens one level up already cover the 1.0.0 case.

Baseline captured against engine `org.hl7.fhir` 6.9.4.1 (HAPI 8.10.0) and IG
`hl7.fhir.uv.emedicinal-product-info` 1.0.0.
