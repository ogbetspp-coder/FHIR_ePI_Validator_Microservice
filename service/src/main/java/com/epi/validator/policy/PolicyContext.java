package com.epi.validator.policy;

import ca.uhn.fhir.context.FhirContext;
import com.epi.validator.engine.TypeResolution;
import org.hl7.fhir.r5.model.Bundle;

/** Everything a policy rule may inspect for one validation run. */
public record PolicyContext(
        FhirContext fhirContext,
        Bundle bundle,
        TypeResolution typeResolution,
        String igId,
        boolean hasType2Content,
        boolean hasType3Content) {
}
