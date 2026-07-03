package com.epi.validator.engine;

import com.epi.validator.model.EpiType;
import org.hl7.fhir.r5.model.Bundle;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Infers the ePI type from bundle contents. <b>Diagnostic only</b>: detection informs the
 * response and drives the gate solely when the caller requested {@code epiType=auto}
 * (exploratory usage). It never downgrades an explicitly requested type.
 */
@Component
public class EpiTypeDetector {

    /**
     * Clinical/knowledge resources that mark a Type 3 ePI (machine-readable clinical data).
     * ClinicalUseDefinition is the definitive marker in the 1.0.0 IG; MedicationKnowledge is
     * retained for forward-compatibility with the 1.1.0 line (detection is diagnostic-only).
     */
    static final Set<String> TYPE_3_MARKERS = Set.of(
            "ClinicalUseDefinition",
            "MedicationKnowledge");

    /** Structured product-data resources that mark a Type 2 ePI. */
    static final Set<String> TYPE_2_MARKERS = Set.of(
            "MedicinalProductDefinition",
            "PackagedProductDefinition",
            "AdministrableProductDefinition",
            "ManufacturedItemDefinition",
            "Ingredient",
            "SubstanceDefinition",
            "RegulatedAuthorization");

    public EpiType detect(Bundle bundle) {
        boolean hasComposition = false;
        boolean hasType2 = false;
        boolean hasType3 = false;
        for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
            if (entry.getResource() == null) {
                continue;
            }
            String type = entry.getResource().fhirType();
            if ("Composition".equals(type)) {
                hasComposition = true;
            } else if (TYPE_3_MARKERS.contains(type)) {
                hasType3 = true;
            } else if (TYPE_2_MARKERS.contains(type)) {
                hasType2 = true;
            }
        }
        if (hasType3) {
            return EpiType.TYPE_3;
        }
        if (hasType2) {
            return EpiType.TYPE_2;
        }
        if (hasComposition) {
            return EpiType.TYPE_1;
        }
        return EpiType.UNKNOWN;
    }

    public boolean hasType2Content(Bundle bundle) {
        return bundle.getEntry().stream()
                .filter(e -> e.getResource() != null)
                .anyMatch(e -> TYPE_2_MARKERS.contains(e.getResource().fhirType()));
    }

    public boolean hasType3Content(Bundle bundle) {
        return bundle.getEntry().stream()
                .filter(e -> e.getResource() != null)
                .anyMatch(e -> TYPE_3_MARKERS.contains(e.getResource().fhirType()));
    }
}
