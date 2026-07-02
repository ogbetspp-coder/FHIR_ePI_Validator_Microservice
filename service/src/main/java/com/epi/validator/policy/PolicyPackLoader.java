package com.epi.validator.policy;

import com.epi.validator.model.IssueSeverity;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads and structurally validates policy pack descriptors from
 * {@code classpath:policy-packs/<id>.yaml}. Any structural defect fails startup — a broken
 * policy descriptor must never be silently skipped.
 */
@Component
public class PolicyPackLoader {

    private static final Set<String> SEVERITIES = Set.of("error", "warning", "information");

    public List<PolicyPackDescriptor> load(List<String> packIds) {
        List<PolicyPackDescriptor> packs = new ArrayList<>();
        for (String packId : packIds) {
            packs.add(loadOne(packId));
        }
        return packs;
    }

    @SuppressWarnings("unchecked")
    private PolicyPackDescriptor loadOne(String packId) {
        String location = "policy-packs/" + packId + ".yaml";
        Map<String, Object> raw;
        try (InputStream in = new ClassPathResource(location).getInputStream()) {
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            raw = yaml.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read policy pack descriptor " + location, e);
        }
        if (raw == null) {
            throw invalid(location, "descriptor is empty");
        }

        String id = requireString(raw, "id", location);
        if (!packId.equals(id)) {
            throw invalid(location, "descriptor id '" + id + "' does not match its file/pack id '" + packId + "'");
        }
        String version = requireString(raw, "version", location);
        Object appliesToRaw = raw.get("appliesTo");
        if (!(appliesToRaw instanceof List<?> appliesToList) || appliesToList.isEmpty()) {
            throw invalid(location, "appliesTo must be a non-empty list of IG configuration ids");
        }
        List<String> appliesTo = appliesToList.stream().map(String::valueOf).toList();

        Object rulesRaw = raw.get("rules");
        if (!(rulesRaw instanceof List<?> rulesList) || rulesList.isEmpty()) {
            throw invalid(location, "rules must be a non-empty list");
        }
        Set<String> seen = new HashSet<>();
        List<PolicyPackDescriptor.RuleDefinition> rules = new ArrayList<>();
        for (Object ruleRaw : rulesList) {
            if (!(ruleRaw instanceof Map)) {
                throw invalid(location, "each rule must be a mapping with ruleId/severity/rationale");
            }
            Map<String, Object> rule = (Map<String, Object>) ruleRaw;
            String ruleId = requireString(rule, "ruleId", location);
            if (!seen.add(ruleId)) {
                throw invalid(location, "duplicate ruleId '" + ruleId + "' within the pack");
            }
            String severity = requireString(rule, "severity", location).toLowerCase();
            if (!SEVERITIES.contains(severity)) {
                throw invalid(location, "rule " + ruleId + " has invalid severity '" + severity
                        + "' (expected error|warning|information)");
            }
            String rationale = requireString(rule, "rationale", location);
            boolean configurable = Boolean.TRUE.equals(rule.get("configurable"));
            rules.add(new PolicyPackDescriptor.RuleDefinition(
                    ruleId,
                    IssueSeverity.valueOf(severity.equals("information") ? "INFORMATION" : severity.toUpperCase()),
                    configurable,
                    rationale));
        }
        return new PolicyPackDescriptor(id, version, appliesTo, rules);
    }

    private static String requireString(Map<String, Object> map, String key, String location) {
        Object value = map.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw invalid(location, "missing required field '" + key + "'");
        }
        return String.valueOf(value);
    }

    private static IllegalStateException invalid(String location, String reason) {
        return new IllegalStateException("Invalid policy pack descriptor " + location + ": " + reason);
    }
}
