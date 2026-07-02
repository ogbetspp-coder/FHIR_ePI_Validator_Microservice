# IG Package Vendoring

## Why vendored

Air-gapped, reproducible builds are a hard requirement: the service must validate identically
in CI, on a laptop, and in production, with **no network at build or runtime**. The FHIR
package registries and especially the ePI **1.1.0 CI build** (build.fhir.org — *not an
authorized HL7 publication*, content changes daily) are outside the build's trust boundary.

All packages are committed as `.tgz` under `service/src/main/resources/packages/` (~15 MB)
and pinned in `tools/packages.lock.json`:

| Package | Version | Used by |
|---|---|---|
| `hl7.fhir.uv.emedicinal-product-info` | 1.0.0 (STU1) | IG chain `1.0.0` (default) |
| `hl7.terminology.r5` | 5.0.0 | dep of ePI 1.0.0 |
| `hl7.fhir.uv.extensions.r5` | 1.0.0 | dep of ePI 1.0.0 |
| `hl7.fhir.uv.emedicinal-product-info` | 1.1.0-cibuild-**20260701** | IG chain `1.1.0` (type profiles) |
| `hl7.terminology.r5` | 7.2.0 | dep of ePI 1.1.0 snapshot |
| `hl7.fhir.uv.extensions.r5` | 5.3.0 | dep of ePI 1.1.0 snapshot |

`hl7.fhir.r5.core#5.0.0` is intentionally NOT vendored — `DefaultProfileValidationSupport`
supplies the base R5 conformance resources.

Version-resolution note: `hl7.fhir.uv.extensions.r5#5.3.0` internally declares
`hl7.terminology.r5#7.1.0`, while the ePI 1.1.0 snapshot pins `7.2.0`. One THO version per
chain: the IG's own pin wins (standard validator behavior).

## The lockfile is the source of truth

`tools/packages.lock.json` holds `{name, version, url, sha256, targetFile, packageDate,
vendoredAt, notes}` per package. It is also **sealed into the jar** at build time and feeds
the runtime build manifest (`/api/v1/epi/manifest`) and the `audit.igPackage.sha256` of every
validation response.

## Workflows

```bash
tools/vendor-packages.sh --verify     # offline: committed .tgz match the lockfile (CI runs this)
tools/vendor-packages.sh              # re-download everything, verify SHA-256 (drift = loud failure)
tools/vendor-packages.sh --refresh hl7.fhir.uv.emedicinal-product-info  # deliberate update
```

## Refreshing a pin (deliberate, reviewed change)

1. `tools/vendor-packages.sh --refresh <name>` — downloads, prints the new SHA-256 and
   packageDate. For the CI-build snapshot, rename the target file to the new date stamp.
2. Update `tools/packages.lock.json` by hand (sha256, packageDate, targetFile, vendoredAt).
3. If the refreshed IG changed its declared dependencies (`package/package.json` inside the
   tgz), vendor those too and update `application.yaml`'s `dependency-packages` —
   `PackageDependencyCoverageIT` fails the build if you forget.
4. Re-run `mvn -f service/pom.xml verify`. Expect `ValidateIgExamplesIT` baseline drift when
   IG content changed: review each count change, then update
   `service/src/test/resources/expected-baselines.yaml` **with justification in the PR**.
5. Commit lockfile + .tgz + baselines together. Never bump baseline numbers blindly.
