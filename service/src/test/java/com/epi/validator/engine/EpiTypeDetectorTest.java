package com.epi.validator.engine;

import com.epi.validator.model.EpiType;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.ClinicalUseDefinition;
import org.hl7.fhir.r5.model.Composition;
import org.hl7.fhir.r5.model.Ingredient;
import org.hl7.fhir.r5.model.MedicationKnowledge;
import org.hl7.fhir.r5.model.MedicinalProductDefinition;
import org.hl7.fhir.r5.model.Organization;
import org.hl7.fhir.r5.model.Resource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EpiTypeDetectorTest {

    private final EpiTypeDetector detector = new EpiTypeDetector();

    private static Bundle bundleOf(Resource... resources) {
        Bundle bundle = new Bundle();
        for (Resource resource : resources) {
            bundle.addEntry().setResource(resource);
        }
        return bundle;
    }

    @Test
    void compositionOnlyIsType1() {
        assertThat(detector.detect(bundleOf(new Composition(), new Organization())))
                .isEqualTo(EpiType.TYPE_1);
    }

    @Test
    void productDataIsType2() {
        assertThat(detector.detect(bundleOf(new Composition(), new MedicinalProductDefinition())))
                .isEqualTo(EpiType.TYPE_2);
        assertThat(detector.detect(bundleOf(new Composition(), new Ingredient())))
                .isEqualTo(EpiType.TYPE_2);
    }

    @Test
    void clinicalContentIsType3() {
        assertThat(detector.detect(bundleOf(new Composition(), new MedicinalProductDefinition(),
                new ClinicalUseDefinition()))).isEqualTo(EpiType.TYPE_3);
        assertThat(detector.detect(bundleOf(new Composition(), new MedicationKnowledge())))
                .isEqualTo(EpiType.TYPE_3);
    }

    @Test
    void emptyOrForeignBundleIsUnknown() {
        assertThat(detector.detect(new Bundle())).isEqualTo(EpiType.UNKNOWN);
        assertThat(detector.detect(bundleOf(new Organization()))).isEqualTo(EpiType.UNKNOWN);
    }

    @Test
    void contentMarkersReportedIndependently() {
        Bundle type3 = bundleOf(new Composition(), new ClinicalUseDefinition());
        assertThat(detector.hasType3Content(type3)).isTrue();
        assertThat(detector.hasType2Content(type3)).isFalse();
    }
}
