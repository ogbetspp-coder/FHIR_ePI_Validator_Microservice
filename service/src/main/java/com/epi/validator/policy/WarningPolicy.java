package com.epi.validator.policy;

import com.epi.validator.config.EpiValidationProperties;
import com.epi.validator.config.EpiValidationProperties.AllowlistEntry;
import com.epi.validator.config.EpiValidationProperties.WarningPolicyMode;
import com.epi.validator.model.IssueSeverity;
import com.epi.validator.model.NormalizedIssue;
import com.epi.validator.model.Verdict;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Warning governance. Warnings never silently become pass conditions:
 *
 * <ul>
 *   <li>{@code pass-with-warnings}: errors fail; any warnings yield PASS_WITH_WARNINGS.</li>
 *   <li>{@code fail-unless-allowlisted}: errors fail; warnings fail too unless they match a
 *       governed allowlist entry carrying a rationale (controlled deviation).</li>
 * </ul>
 */
@Component
public class WarningPolicy {

    private final WarningPolicyMode mode;
    private final List<CompiledEntry> allowlist;

    record CompiledEntry(Pattern ruleId, Pattern system, String rationale) {
    }

    public record Outcome(Verdict verdict, List<NormalizedIssue> issues) {
    }

    public WarningPolicy(EpiValidationProperties properties) {
        this.mode = properties.warningPolicy().mode();
        this.allowlist = properties.warningPolicy().allowlist() == null
                ? List.of()
                : properties.warningPolicy().allowlist().stream().map(WarningPolicy::compile).toList();
    }

    private static CompiledEntry compile(AllowlistEntry entry) {
        return new CompiledEntry(
                entry.ruleId() == null ? null : globToPattern(entry.ruleId()),
                entry.system() == null ? null : globToPattern(entry.system()),
                entry.rationale());
    }

    static Pattern globToPattern(String glob) {
        StringBuilder sb = new StringBuilder();
        for (String part : glob.split("\\*", -1)) {
            if (!part.isEmpty()) {
                sb.append(Pattern.quote(part));
            }
            sb.append(".*");
        }
        sb.setLength(sb.length() - 2); // trailing .* added once too often
        return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
    }

    public Outcome apply(List<NormalizedIssue> issues) {
        List<NormalizedIssue> marked = new ArrayList<>(issues.size());
        boolean hasError = false;
        boolean hasNonAllowlistedWarning = false;
        boolean hasWarning = false;
        for (NormalizedIssue issue : issues) {
            if (issue.severity().isAtLeastError()) {
                hasError = true;
                marked.add(issue);
                continue;
            }
            if (issue.severity() == IssueSeverity.WARNING) {
                hasWarning = true;
                CompiledEntry match = matchAllowlist(issue);
                if (match != null) {
                    marked.add(new NormalizedIssue(issue.severity(), issue.code(), issue.ruleId(), issue.layer(),
                            issue.location(), issue.message(), issue.profileUrl(), issue.source(),
                            true, match.rationale(), issue.suggestion()));
                    continue;
                }
                hasNonAllowlistedWarning = true;
            }
            marked.add(issue);
        }

        Verdict verdict;
        if (hasError) {
            verdict = Verdict.FAIL;
        } else if (mode == WarningPolicyMode.FAIL_UNLESS_ALLOWLISTED && hasNonAllowlistedWarning) {
            verdict = Verdict.FAIL;
        } else if (hasWarning) {
            verdict = Verdict.PASS_WITH_WARNINGS;
        } else {
            verdict = Verdict.PASS;
        }
        return new Outcome(verdict, marked);
    }

    private CompiledEntry matchAllowlist(NormalizedIssue issue) {
        for (CompiledEntry entry : allowlist) {
            boolean ruleMatches = entry.ruleId() == null
                    || (issue.ruleId() != null && entry.ruleId().matcher(issue.ruleId()).matches());
            boolean systemMatches = entry.system() == null
                    || (issue.message() != null && entry.system().matcher(issue.message()).find());
            if (ruleMatches && systemMatches) {
                return entry;
            }
        }
        return null;
    }

    public WarningPolicyMode mode() {
        return mode;
    }
}
