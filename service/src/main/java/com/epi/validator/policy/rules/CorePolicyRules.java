package com.epi.validator.policy.rules;

import com.epi.validator.model.EpiType;
import com.epi.validator.policy.PolicyContext;
import com.epi.validator.policy.PolicyRule;
import com.epi.validator.policy.PolicyRule.RuleViolation;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.Composition;
import org.hl7.fhir.r5.model.Reference;
import org.hl7.fhir.r5.model.Resource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Java implementations of the epi-gate-core policy pack rules. Severity and rationale live in
 * the pack descriptor (policy-packs/epi-gate-core.yaml); these implementations only detect.
 */
@Configuration(proxyBeanMethods = false)
public class CorePolicyRules {

    private static PolicyRule rule(String id, Function<PolicyContext, List<RuleViolation>> impl) {
        return new PolicyRule() {
            @Override
            public String ruleId() {
                return id;
            }

            @Override
            public List<RuleViolation> evaluate(PolicyContext ctx) {
                return impl.apply(ctx);
            }
        };
    }

    private static Optional<Composition> composition(Bundle bundle) {
        if (bundle.getEntry().isEmpty()) {
            return Optional.empty();
        }
        Resource first = bundle.getEntryFirstRep().getResource();
        return first instanceof Composition c ? Optional.of(c) : Optional.empty();
    }

    @Bean
    PolicyRule epiDoc001FirstEntryComposition() {
        return rule("EPI-DOC-001", ctx -> {
            Resource first = ctx.bundle().getEntry().isEmpty()
                    ? null
                    : ctx.bundle().getEntryFirstRep().getResource();
            if (first instanceof Composition) {
                return List.of();
            }
            return List.of(RuleViolation.at("Bundle.entry[0]", "/entry/0",
                    "The first entry of an ePI document bundle must be a Composition, found "
                            + (first == null ? "no resource" : first.fhirType())));
        });
    }

    @Bean
    PolicyRule epiDoc002DocumentType() {
        return rule("EPI-DOC-002", ctx -> {
            Bundle.BundleType type = ctx.bundle().getType();
            if (type == Bundle.BundleType.DOCUMENT) {
                return List.of();
            }
            return List.of(RuleViolation.at("Bundle.type", "/type",
                    "Bundle.type must be 'document' for an ePI, found '"
                            + (type == null ? "none" : type.toCode()) + "'"));
        });
    }

    @Bean
    PolicyRule epiDoc003SectionsPresent() {
        return rule("EPI-DOC-003", ctx -> {
            Optional<Composition> composition = composition(ctx.bundle());
            if (composition.isEmpty()) {
                return List.of(); // EPI-DOC-001 already reports the missing Composition
            }
            List<Composition.SectionComponent> sections = composition.get().getSection();
            if (sections.isEmpty()) {
                return List.of(RuleViolation.at("Bundle.entry[0].resource.section", "/entry/0/resource/section",
                        "The ePI Composition has no sections; the label content must be carried in sections"));
            }
            List<RuleViolation> violations = new ArrayList<>();
            for (int i = 0; i < sections.size(); i++) {
                Composition.SectionComponent s = sections.get(i);
                boolean hasContent = (s.hasText() && s.getText().hasDiv())
                        || !s.getEntry().isEmpty()
                        || !s.getSection().isEmpty();
                if (!hasContent) {
                    violations.add(RuleViolation.at(
                            "Bundle.entry[0].resource.section[" + i + "]",
                            "/entry/0/resource/section/" + i,
                            "Section '" + s.getTitle() + "' has no narrative text, no entries, and no sub-sections"));
                }
            }
            return violations;
        });
    }

    @Bean
    PolicyRule epiDoc004EntryFullUrl() {
        return rule("EPI-DOC-004", ctx -> {
            List<RuleViolation> violations = new ArrayList<>();
            List<Bundle.BundleEntryComponent> entries = ctx.bundle().getEntry();
            for (int i = 0; i < entries.size(); i++) {
                if (!entries.get(i).hasFullUrl()) {
                    violations.add(RuleViolation.at("Bundle.entry[" + i + "]", "/entry/" + i,
                            "Bundle entry " + i + " ("
                                    + (entries.get(i).getResource() != null
                                            ? entries.get(i).getResource().fhirType() : "no resource")
                                    + ") has no fullUrl"));
                }
            }
            return violations;
        });
    }

    @Bean
    PolicyRule epiDoc005ReferencesResolve() {
        return rule("EPI-DOC-005", ctx -> {
            Set<String> targets = new HashSet<>();
            for (Bundle.BundleEntryComponent entry : ctx.bundle().getEntry()) {
                if (entry.hasFullUrl()) {
                    targets.add(entry.getFullUrl());
                }
                Resource resource = entry.getResource();
                if (resource != null && resource.hasId()) {
                    targets.add(resource.fhirType() + "/" + resource.getIdPart());
                }
            }
            List<RuleViolation> violations = new ArrayList<>();
            List<Bundle.BundleEntryComponent> entries = ctx.bundle().getEntry();
            for (int i = 0; i < entries.size(); i++) {
                Resource resource = entries.get(i).getResource();
                if (resource == null) {
                    continue;
                }
                for (Reference ref : ctx.fhirContext().newTerser()
                        .getAllPopulatedChildElementsOfType(resource, Reference.class)) {
                    String value = ref.getReference();
                    if (value == null || value.startsWith("#")) {
                        continue; // display-only or contained
                    }
                    if (!resolves(value, targets)) {
                        violations.add(RuleViolation.at(
                                "Bundle.entry[" + i + "].resource", "/entry/" + i + "/resource",
                                "Reference '" + value + "' in " + resource.fhirType()
                                        + " does not resolve to any entry in the document bundle"));
                    }
                }
            }
            return violations;
        });
    }

    private static boolean resolves(String reference, Set<String> targets) {
        if (targets.contains(reference)) {
            return true;
        }
        // Absolute references may resolve to an entry whose fullUrl tail matches Type/id
        String[] parts = reference.split("/");
        if (parts.length >= 2) {
            String tail = parts[parts.length - 2] + "/" + parts[parts.length - 1];
            return targets.contains(tail);
        }
        return false;
    }

    @Bean
    PolicyRule epiDoc006BundleIdentifier() {
        return rule("EPI-DOC-006", ctx -> {
            if (ctx.bundle().hasIdentifier() && ctx.bundle().getIdentifier().hasValue()) {
                return List.of();
            }
            return List.of(RuleViolation.at("Bundle.identifier", "/identifier",
                    "An ePI document bundle must carry a persistent Bundle.identifier"));
        });
    }

    @Bean
    PolicyRule epiDoc007CompositionTypeCoded() {
        return rule("EPI-DOC-007", ctx -> composition(ctx.bundle())
                .filter(c -> !(c.hasType() && c.getType().hasCoding()
                        && c.getType().getCodingFirstRep().hasSystem()
                        && c.getType().getCodingFirstRep().hasCode()))
                .map(c -> List.of(RuleViolation.at(
                        "Bundle.entry[0].resource.type", "/entry/0/resource/type",
                        "Composition.type should carry a coded document type (system + code)")))
                .orElse(List.of()));
    }

    @Bean
    PolicyRule epiType001Type3RequiresClinicalContent() {
        return rule("EPI-TYPE-001", ctx -> {
            if (ctx.typeResolution().isAuto()) {
                return List.of();
            }
            if (ctx.typeResolution().effective() == EpiType.TYPE_3 && !ctx.hasType3Content()) {
                return List.of(RuleViolation.at("Bundle", "/",
                        "Requested ePI Type 3 but the bundle contains no machine-readable clinical content "
                                + "(ClinicalUseDefinition / MedicationKnowledge)"));
            }
            return List.of();
        });
    }

    @Bean
    PolicyRule epiType002Type2RequiresProductData() {
        return rule("EPI-TYPE-002", ctx -> {
            if (ctx.typeResolution().isAuto()) {
                return List.of();
            }
            EpiType effective = ctx.typeResolution().effective();
            if ((effective == EpiType.TYPE_2 || effective == EpiType.TYPE_3) && !ctx.hasType2Content()) {
                return List.of(RuleViolation.at("Bundle", "/",
                        "Requested ePI Type " + effective.wireValue()
                                + " but the bundle contains no structured product data "
                                + "(MedicinalProductDefinition / PackagedProductDefinition / Ingredient / ...)"));
            }
            return List.of();
        });
    }

    @Bean
    PolicyRule epiType003Type1WithStructuredData() {
        return rule("EPI-TYPE-003", ctx -> {
            if (ctx.typeResolution().isAuto()) {
                return List.of();
            }
            if (ctx.typeResolution().effective() == EpiType.TYPE_1
                    && (ctx.hasType2Content() || ctx.hasType3Content())) {
                return List.of(RuleViolation.at("Bundle", "/",
                        "Requested ePI Type 1 (digital label) but the bundle carries Type 2/3 structured "
                                + "resources — declare the correct type or remove the structured content"));
            }
            return List.of();
        });
    }
}
