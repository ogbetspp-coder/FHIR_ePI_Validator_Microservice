# Official HL7 ePI example bundles

These are the three example document bundles shipped inside the pinned IG package
(`hl7.fhir.uv.emedicinal-product-info` 1.0.0), copied here unmodified for demos and manual
testing:

- `official-type1.json` - Type 1 leaflet (PDF-style narrative)
- `official-type2.json` - Type 2 leaflet (structured sections)
- `official-type3.json` - Type 3 leaflet (structured product and clinical data)

Note: run as-is, all three currently **FAIL** validation. That is expected and instructive.
They contain real defects the official HAPI engine flags, for example author references of the
form `Organization/<uuid>` that do not resolve to the bundle entry (whose id is
`urn:uuid:<uuid>`), and narrative hyperlinks that do not resolve. These illustrative examples
were never conformance-tested, and this service uses the same engine as validator.fhir.org, so
it reports the same issues.

`../good-bundle.json` is the Type 1 example with those reference defects repaired, so it passes
(`PASS_WITH_WARNINGS`). `../broken-bundle.json` is the Type 1 example with two defects seeded on
purpose. Use those two for a clean pass/fail demo; use these three to show that the validator
catches real problems even in the official examples.
