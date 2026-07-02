package com.epi.validator.it;

import com.epi.validator.config.EpiValidationProperties;
import com.epi.validator.engine.IgPackageMetadata;
import com.epi.validator.engine.PackageSetLoader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NpmPackageValidationSupport does not resolve package dependencies — every dependency .tgz
 * must be configured explicitly. This test fails the build when an IG's declared dependencies
 * are not covered by its configured dependency-packages list (the drift guard for refreshes).
 */
class PackageDependencyCoverageIT extends AbstractServiceIT {

    /** Supplied by DefaultProfileValidationSupport, never vendored. */
    private static final Set<String> PROVIDED_BY_CORE = Set.of("hl7.fhir.r5.core");

    @Autowired
    private EpiValidationProperties properties;

    @Autowired
    private PackageSetLoader loader;

    @Test
    void everyDeclaredDependencyIsVendoredAndConfigured() {
        for (EpiValidationProperties.IgConfig ig : properties.igs()) {
            List<IgPackageMetadata> metadata = loader.loadMetadata(ig);
            IgPackageMetadata igPackage = metadata.get(0);
            Set<String> loadedNames = metadata.stream()
                    .map(IgPackageMetadata::name)
                    .collect(Collectors.toSet());
            Map<String, String> loadedVersions = metadata.stream()
                    .collect(Collectors.toMap(IgPackageMetadata::name, IgPackageMetadata::version, (a, b) -> a));

            for (Map.Entry<String, String> dep : igPackage.declaredDependencies().entrySet()) {
                if (PROVIDED_BY_CORE.contains(dep.getKey())) {
                    continue;
                }
                assertThat(loadedNames)
                        .as("IG %s declares dependency %s#%s but no package with that name is configured",
                                ig.id(), dep.getKey(), dep.getValue())
                        .contains(dep.getKey());
                assertThat(loadedVersions.get(dep.getKey()))
                        .as("IG %s: configured version for %s must match the IG's declared pin",
                                ig.id(), dep.getKey())
                        .isEqualTo(dep.getValue());
            }
        }
    }
}
