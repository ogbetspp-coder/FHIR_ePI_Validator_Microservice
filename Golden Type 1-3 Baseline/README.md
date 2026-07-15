# Golden Type 1-3 Baseline

Known-good ePI document bundles, one per type, that **pass** this validator (pinned IG 1.0.0 STU1).
Use them as a regression baseline: the verdict and the stable fields below should not change unless
the engine or IG pin changes.

- `type1-golden.json` - Type 1 leaflet
- `type2-golden.json` - Type 2 product bundle
- `type3-golden.json` - Type 3 bundle with ClinicalUseDefinition

## Expected outcome (engine HAPI 8.10.0, IG 1.0.0)

| Golden | epiType | Verdict | Errors | Warnings | EPI-* rules |
|---|---|---|---|---|---|
| `type1-golden` | 1 | PASS_WITH_WARNINGS | 0 | 10 | none |
| `type2-golden` | 2 | PASS_WITH_WARNINGS | 0 | 12 | none |
| `type3-golden` | 3 | PASS_WITH_WARNINGS | 0 | 12 | none |

Stable assertions for regression (do not assert exact engine message text, which can shift with
versions):

- `verdict` is PASS_WITH_WARNINGS
- `requestedEpiType` = `detectedEpiType` = `effectiveEpiType` = the type
- 0 error or fatal issues, and no `simple-check` (`EPI-*`) or `clinical-profile` issues
- all warnings are offline terminology (unknown code systems, `hapi-validator`)
- `validatorInfo.igVersion` is 1.0.0

These are the repaired official HL7 1.0.0 examples, the same bundles behind the UI's green results.
Re-check any time by POSTing each file to `/api/v1/epi/validate?epiType=N`, or with
`examples/demo.sh <url>`.

## newer-ig-drift/

The paracetamol / diflucan bundles provided separately are authored against a newer ePI IG than the
pinned 1.0.0, so all three FAIL here (correct version-drift detection). They are kept as a negative
baseline in `newer-ig-drift/`. See the README there.
