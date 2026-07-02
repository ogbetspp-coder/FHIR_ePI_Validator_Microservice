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
        @DefaultValue("50") int maxBodyMb,
        String igPackage,
        @DefaultValue List<String> dependencyPackages,
        String bundleProfile) {
}
