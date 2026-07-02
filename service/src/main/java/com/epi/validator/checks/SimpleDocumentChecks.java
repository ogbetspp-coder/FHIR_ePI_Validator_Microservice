package com.epi.validator.checks;

import ca.uhn.fhir.context.FhirContext;
import com.epi.validator.engine.EpiTypeDetector;
import com.epi.validator.engine.TypeResolution;
import com.epi.validator.model.EpiType;
import com.epi.validator.model.Issue;
import com.epi.validator.model.IssueSeverity;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.Composition;
import org.hl7.fhir.r5.model.Reference;
import org.hl7.fhir.r5.model.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Five hardcoded ePI sanity checks — deliberately not a rule framework. They give the repair
 * agent friendlier signals than raw profile errors and enforce the type contract:
 *
 * <pre>
 * EPI-DOC-001  Bundle.type must be 'document'
 * EPI-DOC-002  first entry must be a Composition
 * EPI-DOC-003  all internal references resolve within the bundle
 * EPI-TYPE-001 requested Type 3 requires clinical content (ClinicalUseDefinition/MedicationKnowledge)
 * EPI-TYPE-002 requested Type 2/3 requires product-data resources
 * </pre>
 *
 * The TYPE checks only fire for an explicitly requested type — that is the contract being
 * enforced; with {@code epiType=auto} there is no contract, and detection alone never
 * downgrades an explicit request.
 */
@Component
public class SimpleDocumentChecks {

    private final FhirContext fhirContext;
    private final EpiTypeDetector detector;

    public SimpleDocumentChecks(FhirContext fhirContext, EpiTypeDetector detector) {
        this.fhirContext = fhirContext;
        this.detector = detector;
    }

    public List<Issue> run(Bundle bundle, TypeResolution types) {
        List<Issue> issues = new ArrayList<>();
        checkDocumentType(bundle, issues);
        checkFirstEntryComposition(bundle, issues);
        checkReferencesResolve(bundle, issues);
        checkTypeContract(bundle, types, issues);
        return issues;
    }

    private void checkDocumentType(Bundle bundle, List<Issue> issues) {
        Bundle.BundleType type = bundle.getType();
        if (type != Bundle.BundleType.DOCUMENT) {
            issues.add(error("EPI-DOC-001",
                    "Bundle.type must be 'document' for an ePI, found '"
                            + (type == null ? "none" : type.toCode()) + "'",
                    "Bundle.type"));
        }
    }

    private void checkFirstEntryComposition(Bundle bundle, List<Issue> issues) {
        Resource first = bundle.getEntry().isEmpty() ? null : bundle.getEntryFirstRep().getResource();
        if (!(first instanceof Composition)) {
            issues.add(error("EPI-DOC-002",
                    "The first entry of an ePI document bundle must be a Composition, found "
                            + (first == null ? "no resource" : first.fhirType()),
                    "Bundle.entry[0]"));
        }
    }

    private void checkReferencesResolve(Bundle bundle, List<Issue> issues) {
        Set<String> targets = new HashSet<>();
        for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
            if (entry.hasFullUrl()) {
                targets.add(entry.getFullUrl());
            }
            Resource resource = entry.getResource();
            if (resource != null && resource.hasId()) {
                targets.add(resource.fhirType() + "/" + resource.getIdPart());
            }
        }
        List<Bundle.BundleEntryComponent> entries = bundle.getEntry();
        for (int i = 0; i < entries.size(); i++) {
            Resource resource = entries.get(i).getResource();
            if (resource == null) {
                continue;
            }
            for (Reference ref : fhirContext.newTerser()
                    .getAllPopulatedChildElementsOfType(resource, Reference.class)) {
                String value = ref.getReference();
                if (value == null || value.startsWith("#")) {
                    continue; // display-only or contained
                }
                if (!resolves(value, targets)) {
                    issues.add(error("EPI-DOC-003",
                            "Reference '" + value + "' in " + resource.fhirType()
                                    + " does not resolve to any entry in the document bundle",
                            "Bundle.entry[" + i + "].resource"));
                }
            }
        }
    }

    private static boolean resolves(String reference, Set<String> targets) {
        if (targets.contains(reference)) {
            return true;
        }
        // Absolute references may resolve to an entry whose fullUrl tail matches Type/id
        String[] parts = reference.split("/");
        if (parts.length >= 2) {
            return targets.contains(parts[parts.length - 2] + "/" + parts[parts.length - 1]);
        }
        return false;
    }

    private void checkTypeContract(Bundle bundle, TypeResolution types, List<Issue> issues) {
        if (types.isAuto()) {
            return;
        }
        EpiType effective = types.effective();
        if (effective == EpiType.TYPE_3 && !detector.hasType3Content(bundle)) {
            issues.add(error("EPI-TYPE-001",
                    "Requested ePI Type 3 but the bundle contains no machine-readable clinical "
                            + "content (ClinicalUseDefinition / MedicationKnowledge)",
                    "Bundle"));
        }
        if ((effective == EpiType.TYPE_2 || effective == EpiType.TYPE_3)
                && !detector.hasType2Content(bundle)) {
            issues.add(error("EPI-TYPE-002",
                    "Requested ePI Type " + effective.wireValue() + " but the bundle contains no "
                            + "structured product data (MedicinalProductDefinition / "
                            + "PackagedProductDefinition / Ingredient / ...)",
                    "Bundle"));
        }
    }

    private static Issue error(String ruleId, String message, String fhirPath) {
        return new Issue(IssueSeverity.ERROR, Issue.SOURCE_SIMPLE_CHECK, ruleId, message, fhirPath, null, null);
    }
}
