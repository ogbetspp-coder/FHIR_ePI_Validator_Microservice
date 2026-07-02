package com.epi.validator.engine;

import com.epi.validator.config.EpiValidationProperties;
import com.epi.validator.config.EpiValidationProperties.IgConfig;
import com.epi.validator.config.EpiValidationProperties.ProfileOverride;
import com.epi.validator.model.EpiType;
import com.epi.validator.model.ValidationMode;
import com.epi.validator.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProfileResolverTest {

    private static final String GENERIC = "http://example.org/StructureDefinition/Bundle-uv-epi";
    private static final String TYPE2 = "http://example.org/StructureDefinition/bundle-epi-type2";
    private static final String OVERRIDE = "http://example.org/StructureDefinition/custom";

    private static EpiValidationProperties props(ProfileOverride override) {
        return new EpiValidationProperties(
                "1.0.0", ValidationMode.EXPLORATORY, 50, 8, 120, false, "warning", true,
                override,
                new EpiValidationProperties.RemoteTerminology(false, ""),
                new EpiValidationProperties.WarningPolicyProperties(
                        EpiValidationProperties.WarningPolicyMode.PASS_WITH_WARNINGS, List.of()),
                List.of("epi-gate-core"),
                List.of());
    }

    private static IgConfig genericIg() {
        return new IgConfig("1.0.0", "pkg.tgz", List.of(), GENERIC, null);
    }

    private static IgConfig typedIg() {
        return new IgConfig("1.1.0", "pkg.tgz", List.of(), null,
                Map.of("1", "u1", "2", TYPE2, "3", "u3"));
    }

    private final ProfileResolver resolver = new ProfileResolver(
            props(new ProfileOverride(true, List.of(ValidationMode.EXPLORATORY))));

    @Test
    void genericIgAlwaysUsesBundleProfile() {
        assertThat(resolver.resolve(genericIg(), ValidationMode.GATE, EpiType.TYPE_3, null))
                .containsExactly(GENERIC);
    }

    @Test
    void typedIgSelectsProfileByEffectiveType() {
        assertThat(resolver.resolve(typedIg(), ValidationMode.GATE, EpiType.TYPE_2, null))
                .containsExactly(TYPE2);
    }

    @Test
    void overrideAllowedInExploratory() {
        assertThat(resolver.resolve(genericIg(), ValidationMode.EXPLORATORY, EpiType.TYPE_1, List.of(OVERRIDE)))
                .containsExactly(OVERRIDE);
    }

    @Test
    void overrideRejectedInGateMode() {
        assertThatThrownBy(() ->
                resolver.resolve(genericIg(), ValidationMode.GATE, EpiType.TYPE_1, List.of(OVERRIDE)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void overrideAllowedInGateWhenConfigured() {
        ProfileResolver permissive = new ProfileResolver(
                props(new ProfileOverride(true, List.of(ValidationMode.EXPLORATORY, ValidationMode.GATE))));
        assertThat(permissive.resolve(genericIg(), ValidationMode.GATE, EpiType.TYPE_1, List.of(OVERRIDE)))
                .containsExactly(OVERRIDE);
    }

    @Test
    void overrideRejectedWhenGloballyDisabled() {
        ProfileResolver disabled = new ProfileResolver(props(new ProfileOverride(false, List.of())));
        assertThatThrownBy(() ->
                disabled.resolve(genericIg(), ValidationMode.EXPLORATORY, EpiType.TYPE_1, List.of(OVERRIDE)))
                .isInstanceOf(ApiException.class);
    }
}
