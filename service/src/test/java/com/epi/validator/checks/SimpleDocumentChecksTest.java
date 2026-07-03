package com.epi.validator.checks;

import ca.uhn.fhir.context.FhirContext;
import com.epi.validator.engine.EpiTypeDetector;
import com.epi.validator.engine.TypeResolution;
import com.epi.validator.model.EpiType;
import com.epi.validator.model.Issue;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.ClinicalUseDefinition;
import org.hl7.fhir.r5.model.Composition;
import org.hl7.fhir.r5.model.MedicinalProductDefinition;
import org.hl7.fhir.r5.model.Organization;
import org.hl7.fhir.r5.model.Reference;
import org.hl7.fhir.r5.model.Resource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SimpleDocumentChecksTest {

    private static final FhirContext CTX = FhirContext.forR5();
    private final EpiTypeDetector detector = new EpiTypeDetector();
    private final SimpleDocumentChecks checks = new SimpleDocumentChecks(CTX, detector);

    private static Bundle documentBundle(Resource... resources) {
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.DOCUMENT);
        for (Resource resource : resources) {
            resource.setId(resource.fhirType().toLowerCase() + "-1");
            bundle.addEntry()
                    .setFullUrl("urn:uuid:" + resource.fhirType().toLowerCase() + "-1")
                    .setResource(resource);
        }
        return bundle;
    }

    private static List<String> ruleIds(List<Issue> issues) {
        return issues.stream().map(Issue::ruleId).toList();
    }

    private static TypeResolution auto(Bundle bundle, EpiTypeDetector detector) {
        return TypeResolution.resolve("auto", detector.detect(bundle));
    }

    @Test
    void cleanDocumentBundleHasNoFindings() {
        Bundle bundle = documentBundle(new Composition(), new Organization());
        assertThat(checks.run(bundle, auto(bundle, detector))).isEmpty();
    }

    @Test
    void nonDocumentTypeFailsDoc001() {
        Bundle bundle = documentBundle(new Composition());
        bundle.setType(Bundle.BundleType.COLLECTION);
        assertThat(ruleIds(checks.run(bundle, auto(bundle, detector)))).contains("EPI-DOC-001");
    }

    @Test
    void firstEntryNotCompositionFailsDoc002() {
        Bundle bundle = documentBundle(new Organization(), new Composition());
        assertThat(ruleIds(checks.run(bundle, auto(bundle, detector)))).contains("EPI-DOC-002");
    }

    @Test
    void danglingReferenceFailsDoc003() {
        Composition composition = new Composition();
        composition.addAuthor(new Reference("Organization/not-in-bundle"));
        Bundle bundle = documentBundle(composition);
        List<Issue> issues = checks.run(bundle, auto(bundle, detector));
        assertThat(ruleIds(issues)).contains("EPI-DOC-003");
        assertThat(issues.stream().filter(i -> "EPI-DOC-003".equals(i.ruleId())).findFirst().get()
                .message()).contains("Organization/not-in-bundle");
    }

    @Test
    void referenceToResourcelessEntryIsDangling() {
        // An entry carrying only a fullUrl (no resource) is an empty slot: a reference to it must
        // still be flagged as dangling (EPI-DOC-003), not silently treated as resolved.
        Composition composition = new Composition();
        composition.addAuthor(new Reference("urn:uuid:ghost"));
        Bundle bundle = documentBundle(composition);
        bundle.addEntry().setFullUrl("urn:uuid:ghost"); // fullUrl only, no resource
        assertThat(ruleIds(checks.run(bundle, auto(bundle, detector)))).contains("EPI-DOC-003");
    }

    @Test
    void resolvableReferencesPassDoc003() {
        Composition composition = new Composition();
        // Relative Type/id resolves against the entry's resource id; urn matches fullUrl
        composition.addAuthor(new Reference("Organization/organization-1"));
        Bundle bundle = documentBundle(composition, new Organization());
        assertThat(ruleIds(checks.run(bundle, auto(bundle, detector))))
                .doesNotContain("EPI-DOC-003");
    }

    @Test
    void versionedReferenceResolves() {
        // Organization/id/_history/v must resolve to the in-bundle Organization/id (no false FAIL).
        Composition composition = new Composition();
        composition.addAuthor(new Reference("Organization/organization-1/_history/3"));
        Bundle bundle = documentBundle(composition, new Organization());
        assertThat(ruleIds(checks.run(bundle, auto(bundle, detector))))
                .doesNotContain("EPI-DOC-003");
    }

    @Test
    void absoluteExternalReferenceIsNotFalselyResolvedByTailMatch() {
        // An external absolute URL that merely shares a Type/id tail with an in-bundle resource
        // must NOT be treated as resolved (that was a false PASS on a genuinely external ref).
        Composition composition = new Composition();
        composition.addAuthor(new Reference("https://external.example.com/fhir/Organization/organization-1"));
        Bundle bundle = documentBundle(composition, new Organization());
        assertThat(ruleIds(checks.run(bundle, auto(bundle, detector)))).contains("EPI-DOC-003");
    }

    @Test
    void referencesInsideContainedResourcesAreOutOfScope() {
        // A valid top-level ref resolves; a dangling ref inside a contained resource is skipped.
        Composition composition = new Composition();
        composition.addAuthor(new Reference("Organization/organization-1")); // valid, in-bundle
        Organization contained = new Organization();
        contained.setId("c1");
        contained.setPartOf(new Reference("Organization/ghost-not-in-bundle")); // dangling, contained
        composition.addContained(contained);
        Bundle bundle = documentBundle(composition, new Organization());
        assertThat(ruleIds(checks.run(bundle, auto(bundle, detector))))
                .doesNotContain("EPI-DOC-003");
    }

    @Test
    void requestedType3WithoutClinicalContentFailsType001() {
        Bundle bundle = documentBundle(new Composition(), new MedicinalProductDefinition());
        TypeResolution types = TypeResolution.resolve("3", detector.detect(bundle));
        List<String> ids = ruleIds(checks.run(bundle, types));
        assertThat(ids).contains("EPI-TYPE-001");
        assertThat(ids).doesNotContain("EPI-TYPE-002"); // product data IS present
    }

    @Test
    void requestedType2WithoutProductDataFailsType002() {
        Bundle bundle = documentBundle(new Composition());
        TypeResolution types = TypeResolution.resolve("2", detector.detect(bundle));
        assertThat(ruleIds(checks.run(bundle, types))).contains("EPI-TYPE-002");
    }

    @Test
    void type3SatisfiedByClinicalAndProductContent() {
        Bundle bundle = documentBundle(new Composition(), new MedicinalProductDefinition(),
                new ClinicalUseDefinition());
        TypeResolution types = TypeResolution.resolve("3", detector.detect(bundle));
        assertThat(ruleIds(checks.run(bundle, types)))
                .doesNotContain("EPI-TYPE-001", "EPI-TYPE-002");
    }

    @Test
    void autoNeverTriggersTypeContractChecks() {
        Bundle bundle = documentBundle(new Composition());
        TypeResolution types = TypeResolution.resolve("auto", EpiType.TYPE_1);
        assertThat(ruleIds(checks.run(bundle, types)))
                .doesNotContain("EPI-TYPE-001", "EPI-TYPE-002");
    }
}
