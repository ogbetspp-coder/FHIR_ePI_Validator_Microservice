package com.epi.validator.engine;

import ca.uhn.fhir.context.FhirContext;
import com.epi.validator.config.EpiValidationProperties.IgConfig;
import org.hl7.fhir.common.hapi.validation.support.NpmPackageValidationSupport;
import org.hl7.fhir.utilities.npm.NpmPackage;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the vendored .tgz packages for one IG configuration into an
 * {@link NpmPackageValidationSupport}, and separately parses each package's metadata for the
 * introspection endpoints.
 *
 * <p>Note: {@link NpmPackageValidationSupport} does NOT resolve package dependencies — every
 * dependency .tgz must be listed explicitly in the IG configuration. An integration test
 * enforces that the configured list covers each IG's declared dependencies.
 */
@Component
public class PackageSetLoader {

    private final FhirContext fhirContext;

    public PackageSetLoader(FhirContext fhirContext) {
        this.fhirContext = fhirContext;
    }

    /** All classpath package locations for an IG config: the IG itself first, then dependencies. */
    public List<String> packageLocations(IgConfig config) {
        List<String> locations = new ArrayList<>();
        locations.add(config.igPackage());
        if (config.dependencyPackages() != null) {
            locations.addAll(config.dependencyPackages());
        }
        return locations;
    }

    public NpmPackageValidationSupport loadValidationSupport(IgConfig config) {
        NpmPackageValidationSupport npm = new NpmPackageValidationSupport(fhirContext);
        for (String location : packageLocations(config)) {
            try {
                npm.loadPackageFromClasspath("classpath:" + location);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to load vendored package " + location, e);
            }
        }
        return npm;
    }

    /** Parses package metadata (id, version, canonical, declared deps, profile count) per package. */
    public List<IgPackageMetadata> loadMetadata(IgConfig config) {
        List<IgPackageMetadata> result = new ArrayList<>();
        for (String location : packageLocations(config)) {
            try (InputStream in = new ClassPathResource(location).getInputStream()) {
                NpmPackage pkg = NpmPackage.fromPackage(in);
                Map<String, String> deps = new LinkedHashMap<>();
                pkg.dependencies().forEach(d -> {
                    int hash = d.lastIndexOf('#');
                    if (hash > 0) {
                        deps.put(d.substring(0, hash), d.substring(hash + 1));
                    } else {
                        deps.put(d, "");
                    }
                });
                int profileCount;
                try {
                    profileCount = pkg.listResources("StructureDefinition").size();
                } catch (IOException e) {
                    profileCount = -1;
                }
                result.add(new IgPackageMetadata(
                        pkg.name(),
                        pkg.version(),
                        pkg.canonical(),
                        pkg.date(),
                        pkg.fhirVersion(),
                        deps,
                        profileCount));
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read package metadata from " + location, e);
            }
        }
        return result;
    }
}
