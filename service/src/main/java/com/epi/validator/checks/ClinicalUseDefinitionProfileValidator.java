package com.epi.validator.checks;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.SingleValidationMessage;
import ca.uhn.fhir.validation.ValidationOptions;
import ca.uhn.fhir.validation.ValidationResult;
import com.epi.validator.engine.IssueMapper;
import com.epi.validator.model.Issue;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.ClinicalUseDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Enforces the ePI ClinicalUseDefinition sub-profiles for Type-3 content.
 *
 * <p>The {@code Bundle-uv-epi} profile only pins <em>base</em> ClinicalUseDefinition for the
 * clinical-use entry slice, so the ePI-specific constraints (indication / contraindication /
 * interaction / undesirable-effect / warning) are otherwise enforced only when the submitter
 * declares the matching {@code meta.profile}. An AI-generated bundle that omits it would pass on
 * base validation while quietly skipping the constraints that define Type 3. This validator
 * closes that gap: every ClinicalUseDefinition is validated against the sub-profile derived from
 * its {@code type}, regardless of {@code meta.profile}.
 *
 * <p>Each resource is validated standalone; document-level narrative hyperlink issues (which only
 * resolve in the whole-document context, already checked by the bundle pass) are filtered out.
 */
@Component
public class ClinicalUseDefinitionProfileValidator {

    private static final String BASE =
            "http://hl7.org/fhir/uv/emedicinal-product-info/StructureDefinition/ClinicalUseDefinition-";

    /**
     * ClinicalUseDefinition.type code -> ePI sub-profile canonical. Note the IG's casing: the
     * type code {@code undesirable-effect} (kebab) maps to the {@code undesirableEffect} profile.
     */
    static final Map<String, String> PROFILE_BY_TYPE = Map.of(
            "indication", BASE + "indication-uv-epi",
            "contraindication", BASE + "contraindication-uv-epi",
            "interaction", BASE + "interaction-uv-epi",
            "undesirable-effect", BASE + "undesirableEffect-uv-epi",
            "warning", BASE + "warning-uv-epi");

    private final FhirContext fhirContext;
    private final FhirValidator validator;
    private final IssueMapper issueMapper;

    public ClinicalUseDefinitionProfileValidator(FhirContext fhirContext,
                                                 FhirValidator validator,
                                                 IssueMapper issueMapper) {
        this.fhirContext = fhirContext;
        this.validator = validator;
        this.issueMapper = issueMapper;
    }

    public List<Issue> validate(Bundle bundle) {
        List<Issue> issues = new ArrayList<>();
        List<Bundle.BundleEntryComponent> entries = bundle.getEntry();
        for (int i = 0; i < entries.size(); i++) {
            if (!(entries.get(i).getResource() instanceof ClinicalUseDefinition cud)) {
                continue;
            }
            String type = cud.hasType() ? cud.getType().toCode() : null;
            String profile = PROFILE_BY_TYPE.get(type);
            if (profile == null) {
                continue; // untyped, or a type outside the ePI sub-profile set
            }
            String location = "Bundle.entry[" + i + "].resource";
            String json = fhirContext.newJsonParser().encodeResourceToString(cud);
            ValidationResult result = validator.validateWithResult(json, new ValidationOptions().addProfile(profile));
            for (SingleValidationMessage message : result.getMessages()) {
                if (isDocumentNarrative(message)) {
                    continue;
                }
                Issue mapped = issueMapper.map(message);
                issues.add(new Issue(
                        mapped.severity(),
                        Issue.SOURCE_CLINICAL_PROFILE,
                        "EPI-CUD-PROFILE",
                        mapped.message() + " (ePI " + type + " profile)",
                        location,
                        null,
                        null));
            }
        }
        return issues;
    }

    /**
     * True for narrative hyperlink issues, which cannot resolve when a resource is validated
     * outside its document and are already covered by the whole-bundle validation.
     */
    private static boolean isDocumentNarrative(SingleValidationMessage message) {
        String location = message.getLocationString() == null ? "" : message.getLocationString();
        String text = message.getMessage() == null ? "" : message.getMessage();
        return location.contains(".text") || location.contains("div/") || text.startsWith("Hyperlink");
    }
}
