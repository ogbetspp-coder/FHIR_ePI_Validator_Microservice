# Policy Packs

Policy packs are the **versioned, declarative** carrier of deterministic business rules
(validation layers 4–5). They keep three things separate that must never blur:

1. what base FHIR requires (layer 2),
2. what the HL7 ePI IG requires (layer 3),
3. what *your* product / customer / regional policy requires (layers 4–5).

Every policy violation carries the pack id + version in its structured `source`, the rule's
`rationale`, and is echoed in the response `audit.policyPacks` — you can always answer *which
rule, which version, why*.

## Anatomy

`service/src/main/resources/policy-packs/<id>.yaml` (authoring schema:
`policy-pack.schema.json` in the same directory):

```yaml
id: epi-gate-core          # must equal the file name
version: 0.1.0             # echoed in every violation and audit block
appliesTo: ["1.0.0", "1.1.0"]   # IG configuration ids this pack runs for
rules:
  - ruleId: EPI-DOC-001
    severity: error        # error | warning | information — the descriptor is authoritative
    configurable: false    # whether derived packs may downgrade/disable it
    rationale: "FHIR document bundles require a Composition as the first entry"
```

The Java side (`PolicyRule` implementations, e.g.
`com.epi.validator.policy.rules.CorePolicyRules`) only *detects* violations; severity and
rationale always come from the descriptor.

## Core pack: `epi-gate-core@0.1.0`

| Rule | Severity | Checks |
|---|---|---|
| EPI-DOC-001 | error | first bundle entry is a Composition |
| EPI-DOC-002 | error | `Bundle.type = document` |
| EPI-DOC-003 | error | sections carry narrative, entries, or sub-sections (extraction-loss detector) |
| EPI-DOC-004 | error | every entry has `fullUrl` |
| EPI-DOC-005 | error | every Reference resolves within the document bundle |
| EPI-DOC-006 | error | persistent `Bundle.identifier` present |
| EPI-DOC-007 | warning | `Composition.type` coded (system + code) |
| EPI-TYPE-001 | error | requested Type 3 ⇒ clinical content present |
| EPI-TYPE-002 | error | requested Type 2/3 ⇒ product data present |
| EPI-TYPE-003 | warning | requested Type 1 but structured 2/3 content present |

EPI-TYPE rules only fire for explicitly requested types (a contract to enforce); with
`epiType=auto` there is no contract, so they are inert.

## Fail-fast startup validation

A broken policy configuration must never drift silently. The service **refuses to start** if:

1. a declared `ruleId` has no registered `PolicyRule` implementation;
2. a registered implementation is not declared in any active pack;
3. the same `ruleId` appears in two active packs with different severities;
4. a pack's `appliesTo` references an IG id that is not configured;
5. a descriptor is structurally invalid (missing fields, bad severity, duplicate ruleIds).

Covered by `PolicyPackStartupValidationTest`.

## Adding a customer/regional pack

1. Implement the new rules as `PolicyRule` beans (a `@Configuration` with `@Bean` methods,
   as in `CorePolicyRules`).
2. Add `policy-packs/<customer-id>.yaml` declaring them, with `appliesTo` and rationales.
3. Append the pack id to `epi.validation.policy-packs` in configuration.
4. Startup validation enforces consistency; each violation is attributed to
   `<customer-id>@<version>` in `issue.source`.
