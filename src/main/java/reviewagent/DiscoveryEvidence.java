package reviewagent;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keep all changes and contract material in every independent discovery request. */
final class DiscoveryEvidence {
    enum Focus {
        CORRECTNESS("R1", 0, """
                Correctness and regressions. Trace control flow and state changes on normal execution paths.
                Trace valid operations to the returned result or persisted state in the affected supported implementation.
                """),
        CONTRACT("R2", 1, """
                Business contracts and compatibility. Compare pinned base and target public behavior and business rules.
                Check the consumer's contract, old and new interface registrations, and any compatibility aliases.
                """),
        FAILURE_RECOVERY("R3", 2, """
                Failure handling and recovery. Check partial success, errors, retries, fallbacks, rollback and cleanup.
                Trace a failure through the enabled configuration, affected state or resources, and the caller's result.
                """),
        REQUIREMENTS("R4", 1, """
                Requirements compliance. Compare target behavior with the supplied requirements or business knowledge.
                For each candidate, identify the explicit rule and the changed implementation that fails to meet it.
                Leave general policy questions and full acceptance assessment outside this task.
                """),
        SECURITY("R5", 2, """
                Security and isolation. Check authorization, user and tenant isolation, and sensitive data handling.
                Trace identity and untrusted data through enabled paths from their source to the affected read, write or renderer.
                """),
        INPUT_CONVERSION("R6", 2, """
                Boundary inputs and data conversion. Check empty, repeated or extreme values and format round trips.
                Trace the input contract, validation and conversion through to the consuming code and its observable result.
                """);

        static final int MODEL_SLOTS = 3;

        final String id;
        final int modelSlot;
        final String instructions;

        Focus(String id, int modelSlot, String instructions) {
            this.id = id;
            this.modelSlot = modelSlot;
            this.instructions = instructions;
        }

        static List<Focus> active(CliOptions options) {
            return java.util.Arrays.stream(values())
                    .filter(focus -> focus != REQUIREMENTS || options.requirement() != null || options.knowledge() != null)
                    .toList();
        }

        boolean keepSource(EvidenceSource source) {
            String path = "/" + source.path().toLowerCase(Locale.ROOT);
            return switch (this) {
                case CORRECTNESS -> true;
                case CONTRACT, REQUIREMENTS -> path.contains("/tests/") || path.contains("/test/") || path.contains("/docs/")
                        || path.endsWith(".md") || path.endsWith("_test.go");
                case FAILURE_RECOVERY, SECURITY, INPUT_CONVERSION ->
                        FocusedReview.hasRiskSignal(source.path()) || FocusedReview.hasRiskSignal(source.text());
            };
        }
    }

    private static final Pattern WHOLE_HUNK = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+1(?:,(\\d+))? @@.*$");

    static EvidenceBundle forFocus(EvidenceBundle evidence, Focus focus) {
        if (focus == Focus.CORRECTNESS) return evidence;
        var sources = evidence.catalog().sources();
        var diffs = new HashMap<String, EvidenceSource>();
        for (var source : sources) {
            if (source.role(EvidenceSource.Role.DIFF) && source.endOfFile()) diffs.put(source.path(), source);
        }
        var duplicates = new HashMap<String, EvidenceSource>();
        for (var source : sources) {
            if (!source.role(EvidenceSource.Role.DIFF) && source.role(EvidenceSource.Role.TARGET)
                    && !source.role(EvidenceSource.Role.CONTRACT) && !source.role(EvidenceSource.Role.INDEX)
                    && source.authoritative() && source.firstLine() == 1 && source.endOfFile()
                    && !focus.keepSource(source) && repeatsTarget(diffs.get(source.path()), source)) {
                duplicates.put(source.title(), source);
            }
        }
        if (duplicates.isEmpty()) return evidence;

        var included = new LinkedHashSet<String>();
        sources.forEach(source -> included.add(source.id()));
        var blocks = EvidenceText.SOURCE_BLOCK.matcher(evidence.markdown());
        var markdown = new StringBuilder();
        while (blocks.find()) {
            var source = duplicates.get(blocks.group(1));
            if (source != null && blocks.group().equals(source.markdown().stripTrailing() + "\n")) {
                String note = "- " + EvidenceText.oneLine(source.path()) + " — the complete target body is included in the diff above.\n";
                if (EvidenceText.bytes(note) >= EvidenceText.bytes(blocks.group())) continue;
                included.remove(source.id());
                blocks.appendReplacement(markdown, Matcher.quoteReplacement(note));
            }
        }
        if (included.size() == sources.size()) return evidence;
        return new EvidenceBundle(evidence.scope(), evidence.changedFiles(), blocks.appendTail(markdown).toString(),
                evidence.coverage(), evidence.supplementBudget(), evidence.scopedKnowledge(), evidence.catalog().select(included));
    }

    private static boolean repeatsTarget(EvidenceSource diff, EvidenceSource source) {
        if (diff == null || !diff.revision().equals(source.revision())) return false;
        List<String> patch = diff.text().lines().toList();
        int start = 0;
        while (start < patch.size() && !patch.get(start).startsWith("@@ ")) start++;
        if (start == 0 || start == patch.size() || patch.subList(1, start).stream().anyMatch(line -> line.startsWith("diff --git "))) return false;
        var hunk = WHOLE_HUNK.matcher(patch.get(start));
        if (!hunk.matches()) return false;
        var target = source.text().lines().toList();
        if (!Integer.toString(target.size()).equals(hunk.group(1) == null ? "1" : hunk.group(1))) return false;
        int line = 0;
        for (String entry : patch.subList(start + 1, patch.size())) {
            if (entry.startsWith("-") || entry.equals("\\ No newline at end of file")) continue;
            if ((!entry.startsWith("+") && !entry.startsWith(" ")) || line == target.size()
                    || !entry.substring(1).equals(target.get(line++))) return false;
        }
        return line == target.size();
    }
}
