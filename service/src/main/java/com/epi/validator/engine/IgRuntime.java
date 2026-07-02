package com.epi.validator.engine;

import ca.uhn.fhir.validation.FhirValidator;
import com.epi.validator.config.EpiValidationProperties.IgConfig;

import java.util.List;

/** A fully built, warmed validator chain for one IG version, plus its metadata. */
public record IgRuntime(
        IgConfig config,
        FhirValidator validator,
        List<IgPackageMetadata> packages) {

    /** Metadata of the IG package itself (always loaded first). */
    public IgPackageMetadata igPackage() {
        return packages.get(0);
    }
}
