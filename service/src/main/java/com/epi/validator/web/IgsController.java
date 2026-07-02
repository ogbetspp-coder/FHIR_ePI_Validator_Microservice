package com.epi.validator.web;

import com.epi.validator.config.EpiValidationProperties;
import com.epi.validator.engine.IgPackageMetadata;
import com.epi.validator.engine.IgRuntime;
import com.epi.validator.engine.PackageSetLoader;
import com.epi.validator.engine.ValidatorRegistry;
import com.epi.validator.model.IgInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Introspection: which IG packages and bundle profiles this instance validates against. */
@RestController
@Tag(name = "Introspection")
public class IgsController {

    private final EpiValidationProperties properties;
    private final ValidatorRegistry registry;
    private final PackageSetLoader packageSetLoader;

    public IgsController(EpiValidationProperties properties,
                         ValidatorRegistry registry,
                         PackageSetLoader packageSetLoader) {
        this.properties = properties;
        this.registry = registry;
        this.packageSetLoader = packageSetLoader;
    }

    @Operation(summary = "List configured IGs, their packages, dependencies, and bundle profiles")
    @GetMapping(value = "/api/v1/epi/igs", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<IgInfo> igs() {
        List<IgInfo> result = new ArrayList<>();
        for (EpiValidationProperties.IgConfig config : properties.igs()) {
            boolean ready = registry.hasRuntime(config.id());
            // Use the runtime's already-parsed metadata when available; fall back to parsing
            // the vendored package directly before warm-up completes.
            List<IgPackageMetadata> metadata = ready
                    ? registry.runtimeFor(config.id()).packages()
                    : packageSetLoader.loadMetadata(config);
            IgPackageMetadata ig = metadata.get(0);

            Map<String, String> bundleProfiles = new LinkedHashMap<>();
            if (config.typeProfiles() != null) {
                bundleProfiles.putAll(config.typeProfiles());
            } else if (config.bundleProfile() != null) {
                bundleProfiles.put("all", config.bundleProfile());
            }
            result.add(new IgInfo(
                    config.id(),
                    ig.name(),
                    ig.version(),
                    ig.date(),
                    ig.fhirVersion(),
                    ig.canonical(),
                    config.id().equals(properties.defaultIg()),
                    ig.declaredDependencies().entrySet().stream()
                            .map(e -> new IgInfo.Dependency(e.getKey(), e.getValue()))
                            .toList(),
                    bundleProfiles,
                    ig.profileCount(),
                    ready && registry.isReady()));
        }
        return result;
    }

    IgRuntime runtimeOrNull(String igId) {
        return registry.hasRuntime(igId) ? registry.runtimeFor(igId) : null;
    }
}
