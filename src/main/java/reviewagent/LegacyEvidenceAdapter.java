package reviewagent;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.regex.Pattern;

/** Compatibility boundary for existing provider Markdown and old internal fixtures. */
final class LegacyEvidenceAdapter {
    private static final Pattern BLOCK = Pattern.compile("(?m)^### ([^\\r\\n]+)\\R\\R(`{3,})([^\\r\\n]*)\\R([\\s\\S]*?)^\\2[ \\t]*(?:\\R|$)");
    static EvidenceCatalog read(String markdown, ReviewScope scope, LanguageRegistry languages) {
        var entries = new ArrayList<EvidenceSource>();
        var blocks = BLOCK.matcher(markdown);
        while (blocks.find()) {
            String title = blocks.group(1), format = blocks.group(3).strip(), text = blocks.group(4);
            var roles = EnumSet.noneOf(EvidenceSource.Role.class);
            boolean contract = format.equals("markdown") || title.matches("(?:AGENTS|CLAUDE|REVIEW)\\.md at base");
            if (contract) roles.add(EvidenceSource.Role.CONTRACT);
            if (format.equals("index") || format.equals("metadata")) roles.add(EvidenceSource.Role.INDEX);
            if (format.equals("diff")) { roles.add(EvidenceSource.Role.DIFF); roles.add(EvidenceSource.Role.BASELINE); roles.add(EvidenceSource.Role.TARGET); }
            String path = title, revision = "";
            int first = 1;
            var range = Pattern.compile(" \\(lines (\\d+)–\\d+\\)$").matcher(path);
            if (range.find()) { first = Integer.parseInt(range.group(1)); path = path.substring(0, range.start()); }
            for (String ref : java.util.List.of(scope.baseCommit(), scope.targetCommit(), "base")) {
                if (path.endsWith(" at " + ref)) { revision = ref; path = path.substring(0, path.length() - ref.length() - 4); break; }
            }
            if (title.contains(" at " + scope.baseCommit()) || title.endsWith(" at base")) roles.add(EvidenceSource.Role.BASELINE);
            if (title.contains(" at " + scope.targetCommit())) roles.add(EvidenceSource.Role.TARGET);
            boolean code = EvidenceCatalog.implementation(languages, path);
            if (!contract && !roles.contains(EvidenceSource.Role.INDEX) && code) roles.add(EvidenceSource.Role.IMPLEMENTATION);
            if (revision.isEmpty() && !roles.contains(EvidenceSource.Role.DIFF) || title.contains(" · ")) { path = ""; first = 0; }
            if (roles.contains(EvidenceSource.Role.DIFF)) path = title;
            entries.add(EvidenceSource.create(title, format, text, revision, path, first, roles, false));
        }
        return new EvidenceCatalog(entries, false);
    }
}
