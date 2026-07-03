package com.epi.validator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Service configuration under {@code epi.validation}. Env overrides use relaxed binding with
 * dashes dropped, e.g. {@code max-body-mb} -> {@code EPI_VALIDATION_MAXBODYMB}.
 */
@ConfigurationProperties(prefix = "epi.validation")
public record EpiValidationProperties(
        @DefaultValue("8") int maxBodyMb,
        @DefaultValue("1000") int maxBundleEntries,
        String igPackage,
        @DefaultValue List<String> dependencyPackages,
        String bundleProfile) {

    public EpiValidationProperties {
        if (maxBodyMb <= 0) {
            throw new IllegalArgumentException(
                    "epi.validation.max-body-mb must be a positive number of megabytes, was " + maxBodyMb);
        }
        if (maxBundleEntries <= 0) {
            throw new IllegalArgumentException(
                    "epi.validation.max-bundle-entries must be positive, was " + maxBundleEntries);
        }
    }
}
