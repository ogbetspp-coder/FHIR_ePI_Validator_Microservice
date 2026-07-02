package com.epi.validator.config;

import com.epi.validator.model.EpiType;
import com.epi.validator.model.ValidationMode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * All service configuration under {@code epi.validation}. Environment overrides use Spring's
 * relaxed binding with dashes dropped, e.g. {@code default-ig} -> {@code EPI_VALIDATION_DEFAULTIG};
 * the exact table is published in docs/gcp-deployment.md.
 */
@ConfigurationProperties(prefix = "epi.validation")
public record EpiValidationProperties(
        String defaultIg,
        @DefaultValue("exploratory") ValidationMode defaultValidationMode,
        @DefaultValue("50") int maxBodyMb,
        @DefaultValue("8") int maxConcurrentValidations,
        @DefaultValue("120") int requestTimeoutSeconds,
        @DefaultValue("false") boolean concurrentBundleValidation,
        @DefaultValue("warning") String unknownCodeSystemSeverity,
        @DefaultValue("true") boolean fhirNativeEndpointEnabled,
        @DefaultValue ProfileOverride profileOverride,
        @DefaultValue RemoteTerminology remoteTerminology,
        @DefaultValue WarningPolicyProperties warningPolicy,
        @DefaultValue("epi-gate-core") List<String> policyPacks,
        List<IgConfig> igs) {

    public record ProfileOverride(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("exploratory") List<ValidationMode> allowedInModes) {

        public boolean allowedIn(ValidationMode mode) {
            return enabled && allowedInModes != null && allowedInModes.contains(mode);
        }
    }

    public record RemoteTerminology(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("") String url) {
    }

    public record WarningPolicyProperties(
            @DefaultValue("pass-with-warnings") WarningPolicyMode mode,
            @DefaultValue List<AllowlistEntry> allowlist) {
    }

    public enum WarningPolicyMode {
        PASS_WITH_WARNINGS,
        FAIL_UNLESS_ALLOWLISTED;

        public String wireValue() {
            return name().toLowerCase().replace('_', '-');
        }
    }

    /**
     * A governed warning deviation. {@code ruleId} matches the normalized issue ruleId
     * (glob '*' supported); {@code system} optionally narrows to a code system URL glob
     * mentioned in the message; {@code rationale} documents why the deviation is accepted.
     */
    public record AllowlistEntry(String ruleId, String system, String severity, String rationale) {
    }

    public record IgConfig(
            String id,
            String igPackage,
            @DefaultValue List<String> dependencyPackages,
            String bundleProfile,
            Map<String, String> typeProfiles) {

        /** Profile canonical for the given effective type, or the generic bundle profile. */
        public Optional<String> profileFor(EpiType effectiveType) {
            if (typeProfiles != null && effectiveType != null) {
                String url = typeProfiles.get(effectiveType.wireValue());
                if (url != null) {
                    return Optional.of(url);
                }
            }
            return Optional.ofNullable(bundleProfile);
        }
    }

    public IgConfig igConfigOrThrow(String igId) {
        String effective = igId == null || igId.isBlank() ? defaultIg : igId;
        return igs.stream()
                .filter(ig -> ig.id().equals(effective))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown igVersion '" + effective + "'; configured: "
                                + igs.stream().map(IgConfig::id).toList()));
    }
}
