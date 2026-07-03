package com.epi.validator.checks;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Locks in the ClinicalUseDefinition.type -> ePI sub-profile mapping, incl. the IG's casing. */
class ClinicalUseDefinitionProfileMappingTest {

    private static final String BASE =
            "http://hl7.org/fhir/uv/emedicinal-product-info/StructureDefinition/ClinicalUseDefinition-";

    @Test
    void mapsAllFiveClinicalTypes() {
        assertThat(ClinicalUseDefinitionProfileValidator.PROFILE_BY_TYPE)
                .containsOnlyKeys("indication", "contraindication", "interaction",
                        "undesirable-effect", "warning");
    }

    @Test
    void kebabTypeCodeMapsToCamelCaseProfileName() {
        // The type code is 'undesirable-effect' but the IG profile is 'undesirableEffect'.
        assertThat(ClinicalUseDefinitionProfileValidator.PROFILE_BY_TYPE.get("undesirable-effect"))
                .isEqualTo(BASE + "undesirableEffect-uv-epi");
    }

    @Test
    void everyProfileUsesTheEpiCanonicalBase() {
        assertThat(ClinicalUseDefinitionProfileValidator.PROFILE_BY_TYPE.values())
                .allMatch(url -> url.startsWith(BASE));
    }
}
