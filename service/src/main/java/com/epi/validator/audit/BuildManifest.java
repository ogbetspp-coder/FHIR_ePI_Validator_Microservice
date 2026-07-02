package com.epi.validator.audit;

import java.util.List;

/**
 * The immutable build manifest: exactly which validator build, IG packages, and policy packs
 * this artifact validates with. Derived solely from files sealed into the jar; its SHA-256 is
 * echoed in every validation response's audit block.
 */
public record BuildManifest(
        String buildId,
        String hapiVersion,
        String fhirVersion,
        List<IgPackageEntry> igPackages,
        List<PolicyPackEntry> policyPacks) {

    public record IgPackageEntry(
            String id,
            String version,
            String sha256,
            String sourceUrl,
            String vendoredAt,
            String targetFile) {
    }

    public record PolicyPackEntry(String id, String version) {
    }
}
