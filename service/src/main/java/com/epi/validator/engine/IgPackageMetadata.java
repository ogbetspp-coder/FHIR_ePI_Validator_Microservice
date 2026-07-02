package com.epi.validator.engine;

import java.util.Map;

/** Metadata captured from an NPM package's package.json + index at load time. */
public record IgPackageMetadata(
        String name,
        String version,
        String canonical,
        String date,
        String fhirVersion,
        Map<String, String> declaredDependencies,
        int profileCount) {
}
