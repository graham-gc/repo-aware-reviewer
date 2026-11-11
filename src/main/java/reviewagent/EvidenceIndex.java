package reviewagent;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Index only material actually supplied to the model, never candidate allegations. */
final class EvidenceIndex {
    private static final Pattern BLOCK = Pattern.compile(
            "(?m)^### ([^\\r\\n]+)\\R\\R(`{3,})([^\\r\\n]*)\\R([\\s\\S]*?)^\\2[ \\t]*(?:\\R|$)");
    record Citation(String source, String quote) { }
    record Source(String text, boolean implementation, boolean baseline, boolean target, boolean contract, boolean diff) { }
    private final Map<String, Source> sources = new LinkedHashMap<>();

    EvidenceIndex(EvidenceBundle evidence, String supplemental) {
        var blocks = BLOCK.matcher(evidence.markdown() + supplemental);
        while (blocks.find()) {
            String title = blocks.group(1);
            String language = blocks.group(3).strip();
            boolean contract = language.equals("markdown") || title.matches("(?:AGENTS|CLAUDE|REVIEW)\\.md at base");
            boolean prose = title.matches("(?i).*\\.(?:md|mdx|txt|rst|adoc)(?: at .*|$)");
            String sourcePath = title;
            for (String revision : List.of(evidence.scope().baseCommit(), evidence.scope().targetCommit(), "base")) {
                int suffix = sourcePath.lastIndexOf(" at " + revision);
                if (suffix >= 0) sourcePath = sourcePath.substring(0, suffix);
            }
            if (!sourcePath.contains(" · ")) {
                try { prose = !LanguageRegistry.defaults().forPath(sourcePath).implementationEvidence(sourcePath); }
                catch (IOException unknown) { /* Keep the generic behavior for unknown or external block titles. */ }
            }
            boolean implementation = !contract && !prose && !Set.of("index", "metadata").contains(language);
            boolean diff = language.equals("diff");
            sources.put(title, new Source(blocks.group(4), implementation,
                    diff || title.contains(" at " + evidence.scope().baseCommit()) || title.endsWith(" at base"),
                    diff || title.contains(" at " + evidence.scope().targetCommit()), contract, diff));
        }
    }

    String inventory() {
        return sources.entrySet().stream().map(entry -> "- " + entry.getKey() + " ["
                + (entry.getValue().contract() ? "contract" : "source") + "]\n")
                .collect(java.util.stream.Collectors.joining());
    }

    void validate(List<Citation> citations) throws IOException {
        for (Citation citation : citations) {
            Source source = sources.get(citation.source());
            String quote = normalize(citation.quote());
            if (source == null || quote.length() < 12 || !normalize(source.text()).contains(quote)) {
                throw new IOException("Citation must quote an included evidence block verbatim: "
                        + EvidenceText.oneLine(citation.source()));
            }
        }
    }

    boolean hasImplementation(List<Citation> citations) {
        return citations.stream().map(c -> sources.get(c.source())).anyMatch(s -> s != null && s.implementation());
    }

    boolean hasBaseline(List<Citation> citations) {
        return citations.stream().anyMatch(c -> {
            Source source = sources.get(c.source());
            return source != null && source.baseline() && source.implementation()
                    && (!source.diff() || source.text().contains("new file mode ") || quotesDiffSide(c, source, '-'));
        });
    }

    boolean hasTarget(List<Citation> citations) {
        return citations.stream().anyMatch(c -> {
            Source source = sources.get(c.source());
            return source != null && source.target() && source.implementation()
                    && (!source.diff() || quotesDiffSide(c, source, '+'));
        });
    }

    private static boolean quotesDiffSide(Citation citation, Source source, char marker) {
        String quote = normalize(citation.quote());
        return source.text().lines().filter(line -> line.length() > 1 && line.charAt(0) == marker
                        && !line.startsWith("" + marker + marker + marker))
                .map(line -> normalize(line.substring(1))).filter(line -> line.length() >= 12)
                .anyMatch(line -> line.contains(quote) || quote.contains(line));
    }

    boolean hasContract(List<Citation> citations) {
        return citations.stream().map(c -> sources.get(c.source())).anyMatch(s -> s != null && s.contract());
    }

    static String normalize(String text) {
        return text.strip().replaceAll("\\s+", " ");
    }
}
