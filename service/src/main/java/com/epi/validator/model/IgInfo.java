package com.epi.validator.model;

import java.util.List;
import java.util.Map;

/** Introspection payload for one configured IG (GET /api/v1/epi/igs). */
public record IgInfo(
        String igId,
        String packageId,
        String packageVersion,
        String packageDate,
        String fhirVersion,
        String canonical,
        boolean isDefault,
        List<Dependency> dependencies,
        Map<String, String> bundleProfiles,
        int profileCount,
        boolean ready) {

    public record Dependency(String name, String version) {
    }
}
