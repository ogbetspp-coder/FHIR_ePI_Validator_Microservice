package com.epi.validator.policy;

import com.epi.validator.config.EpiValidationProperties;
import com.epi.validator.model.IssueLayer;
import com.epi.validator.model.NormalizedIssue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Runs the active policy packs (Layers 4-5) and performs the fail-fast startup validation:
 * the service refuses to start if a declared ruleId has no Java implementation, an
 * implementation is not declared in any active pack, duplicate ruleIds conflict on severity,
 * or a pack applies to an unconfigured IG id. Silent policy drift is not an option.
 */
@Component
public class PolicyEngine {

    private static final Logger log = LoggerFactory.getLogger(PolicyEngine.class);

    private final List<PolicyPackDescriptor> packs;
    private final Map<String, PolicyRule> rulesById;
    /** ruleId -> (packId, definition), first-declaring pack wins for provenance. */
    private final Map<String, PackRule> effectiveRules = new LinkedHashMap<>();

    record PackRule(PolicyPackDescriptor pack, PolicyPackDescriptor.RuleDefinition definition) {
    }

    public PolicyEngine(EpiValidationProperties properties,
                        PolicyPackLoader loader,
                        List<PolicyRule> ruleImplementations) {
        this.packs = loader.load(properties.policyPacks());
        this.rulesById = ruleImplementations.stream()
                .collect(Collectors.toMap(PolicyRule::ruleId, r -> r, (a, b) -> {
                    throw new IllegalStateException("Duplicate PolicyRule implementation for ruleId " + a.ruleId());
                }));
        validateAtStartup(properties);
        for (PolicyPackDescriptor pack : packs) {
            for (PolicyPackDescriptor.RuleDefinition def : pack.rules()) {
                effectiveRules.putIfAbsent(def.ruleId(), new PackRule(pack, def));
            }
        }
        log.info("Policy engine ready: packs {}", packs.stream()
                .map(p -> p.id() + "@" + p.version()).toList());
    }

    private void validateAtStartup(EpiValidationProperties properties) {
        Set<String> configuredIgIds = properties.igs().stream()
                .map(EpiValidationProperties.IgConfig::id)
                .collect(Collectors.toSet());
        Map<String, PolicyPackDescriptor.RuleDefinition> severityByRule = new HashMap<>();
        Set<String> declared = new HashSet<>();

        for (PolicyPackDescriptor pack : packs) {
            for (String igId : pack.appliesTo()) {
                if (!configuredIgIds.contains(igId)) {
                    throw new IllegalStateException("Policy pack '" + pack.id() + "' appliesTo unknown IG id '"
                            + igId + "'; configured IGs: " + configuredIgIds);
                }
            }
            for (PolicyPackDescriptor.RuleDefinition def : pack.rules()) {
                declared.add(def.ruleId());
                if (!rulesById.containsKey(def.ruleId())) {
                    throw new IllegalStateException("Policy pack '" + pack.id() + "' declares ruleId '"
                            + def.ruleId() + "' but no PolicyRule implementation exists for it");
                }
                PolicyPackDescriptor.RuleDefinition existing = severityByRule.putIfAbsent(def.ruleId(), def);
                if (existing != null && existing.severity() != def.severity()) {
                    throw new IllegalStateException("Conflicting severities for ruleId '" + def.ruleId()
                            + "' across policy packs: " + existing.severity() + " vs " + def.severity());
                }
            }
        }
        Set<String> undeclared = new HashSet<>(rulesById.keySet());
        undeclared.removeAll(declared);
        if (!undeclared.isEmpty()) {
            throw new IllegalStateException("PolicyRule implementations not declared in any active pack: "
                    + undeclared + " — declare them in a pack or remove the implementation");
        }
    }

    /** Runs all rules from packs applicable to the given IG id. */
    public List<NormalizedIssue> evaluate(PolicyContext context) {
        List<NormalizedIssue> issues = new ArrayList<>();
        for (Map.Entry<String, PackRule> entry : effectiveRules.entrySet()) {
            PackRule packRule = entry.getValue();
            if (!packRule.pack().appliesTo(context.igId())) {
                continue;
            }
            PolicyRule rule = rulesById.get(entry.getKey());
            for (PolicyRule.RuleViolation violation : rule.evaluate(context)) {
                issues.add(new NormalizedIssue(
                        packRule.definition().severity(),
                        "business-rule",
                        packRule.definition().ruleId(),
                        IssueLayer.POLICY,
                        new NormalizedIssue.Location(violation.fhirPath(), violation.jsonPointer(), null, null),
                        violation.message(),
                        null,
                        new NormalizedIssue.Source(
                                NormalizedIssue.Source.TYPE_POLICY_PACK,
                                packRule.pack().id(),
                                packRule.pack().version()),
                        false,
                        packRule.definition().rationale(),
                        null));
            }
        }
        return issues;
    }

    public List<PolicyPackDescriptor> activePacks() {
        return packs;
    }
}
