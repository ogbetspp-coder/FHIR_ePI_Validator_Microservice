package com.epi.validator.engine;

import com.epi.validator.config.EpiValidationProperties;
import com.epi.validator.config.EpiValidationProperties.IgConfig;
import com.epi.validator.model.EpiType;
import com.epi.validator.model.ValidationMode;
import com.epi.validator.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Chooses the profile canonical(s) a bundle is validated against.
 *
 * <p>In {@code gate} mode the profile comes from the effective ePI type only; explicit
 * {@code profile} overrides are rejected unless configuration allows them for gate mode
 * (they could otherwise bypass the intended type profile).
 */
@Component
public class ProfileResolver {

    private final EpiValidationProperties properties;

    public ProfileResolver(EpiValidationProperties properties) {
        this.properties = properties;
    }

    public List<String> resolve(IgConfig igConfig,
                                ValidationMode mode,
                                EpiType effectiveType,
                                List<String> explicitProfiles) {
        if (explicitProfiles != null && !explicitProfiles.isEmpty()) {
            if (!properties.profileOverride().allowedIn(mode)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Explicit profile override is not allowed in validationMode="
                                + mode.wireValue()
                                + " (epi.validation.profile-override.allowed-in-modes)");
            }
            return List.copyOf(explicitProfiles);
        }
        return igConfig.profileFor(effectiveType)
                .map(List::of)
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "No bundle profile configured for IG '" + igConfig.id()
                                + "' and ePI type '" + effectiveType.wireValue() + "'"));
    }
}
