package com.epi.validator.checks;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.util.FhirTerser;
import com.epi.validator.engine.EpiTypeDetector;
import com.epi.validator.engine.TypeResolution;
import com.epi.validator.model.EpiType;
import com.epi.validator.model.Issue;
import com.epi.validator.model.IssueSeverity;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.Composition;
import org.hl7.fhir.r5.model.DomainResource;
import org.hl7.fhir.r5.model.Reference;
import org.hl7.fhir.r5.model.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Five hardcoded ePI sanity checks, deliberately not a rule framework. They give the repair
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
 * The TYPE checks only fire for an explicitly requested type. That is the contract being
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
            // An entry with no resource is an empty slot, not a resolvable target: a reference to
            // its fullUrl is still dangling, so do not add that fullUrl to the target set.
            Resource resource = entry.getResource();
            if (resource == null) {
                continue;
            }
            if (entry.hasFullUrl()) {
                targets.add(entry.getFullUrl());
            }
            if (resource.hasId()) {
                targets.add(resource.fhirType() + "/" + resource.getIdPart());
            }
        }
        List<Bundle.BundleEntryComponent> entries = bundle.getEntry();
        for (int i = 0; i < entries.size(); i++) {
            Resource resource = entries.get(i).getResource();
            if (resource == null) {
                continue;
            }
            for (Reference ref : outboundReferences(resource)) {
                String value = ref.getReference();
                if (value == null || value.startsWith("#")) {
                    continue; // display-only, or a contained-local (#id) reference
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

    /**
     * References borne directly by the entry resource, excluding those inside its contained
     * resources. A contained resource is a self-contained sub-document whose outbound
     * references are out of scope for bundle-entry integrity (and would otherwise false-fail).
     */
    private Set<Reference> outboundReferences(Resource resource) {
        FhirTerser terser = fhirContext.newTerser();
        Set<Reference> all = new LinkedHashSet<>(
                terser.getAllPopulatedChildElementsOfType(resource, Reference.class));
        if (resource instanceof DomainResource domain) {
            Set<Reference> contained = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Resource c : domain.getContained()) {
                contained.addAll(terser.getAllPopulatedChildElementsOfType(c, Reference.class));
            }
            all.removeIf(contained::contains);
        }
        return all;
    }

    private static boolean resolves(String reference, Set<String> targets) {
        // Normalize away any version suffix (Organization/1/_history/2 -> Organization/1).
        String ref = reference;
        int history = ref.indexOf("/_history/");
        if (history >= 0) {
            ref = ref.substring(0, history);
        }
        // Exact match only. An absolute reference (scheme://... or urn:...) must match an entry
        // fullUrl exactly; a relative Type/id must match an entry's resource id. Never loose-match
        // by URL tail: two different servers can share a Type/id and are NOT the same resource.
        return targets.contains(ref);
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
