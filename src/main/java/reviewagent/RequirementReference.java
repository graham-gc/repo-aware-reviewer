package reviewagent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** References only the explicitly supplied primary requirements document, never a repository or knowledge block. */
final class RequirementReference {
    static ReviewOutput.AssessmentItem resolve(ReviewOutput.AssessmentItem item, InputDocument document) throws IOException {
        if (item.requirementLines().isEmpty()) return item;
        String text = source(document).excerpt(item.requirementLines()).text().strip();
        if (text.isEmpty()) throw new IOException("Requirement selection must contain primary-document text");
        return new ReviewOutput.AssessmentItem(item.id(), text, item.requirementText(), item.behavior(), item.status(), item.reasoning(),
                item.boundaries(), item.gap(), item.citations(), item.policyBasis(), item.policyReason(), item.requirementLines());
    }

    static boolean contains(InputDocument document, String text) {
        return contains(EvidenceIndex.normalize(document.content()), text);
    }

    static boolean contains(String normalizedDocument, String text) {
        return !text.isBlank() && normalizedDocument.contains(EvidenceIndex.normalize(text));
    }

    static String hint(InputDocument document, String quotation) {
        List<String> wanted = quotation.lines().map(EvidenceIndex::normalize)
                .filter(line -> !line.isBlank() && !alignment(line)).toList();
        if (wanted.isEmpty()) return "";
        List<String> lines = document.content().lines().toList();
        var ranges = new ArrayList<String>();
        for (int start = 0; start < lines.size(); start++) {
            if (!EvidenceIndex.normalize(lines.get(start)).equals(wanted.getFirst())) continue;
            int matched = 0, last = start;
            for (int line = start; line < lines.size() && line - start < 200; line++) {
                String actual = EvidenceIndex.normalize(lines.get(line));
                if (actual.isBlank() || alignment(actual)) continue;
                if (!actual.equals(wanted.get(matched))) break;
                last = line;
                if (++matched == wanted.size()) { ranges.add((start + 1) + ":" + (last + 1)); break; }
            }
        }
        if (ranges.size() != 1) return "";
        String selector = ranges.getFirst();
        try {
            return "\nExact primary-document selector (replace requirement, do not include both fields): <requirementLines>"
                    + selector + "</requirementLines>. The CLI extracts the original text; do not add Markdown separator rows.\n"
                    + EvidenceText.block("Primary requirement lines " + selector, "text", EvidenceText.linePrefix(source(document).excerpt(selector).text(), 6_000));
        } catch (IOException unavailable) { return ""; }
    }

    private static boolean alignment(String line) {
        if (!line.startsWith("|") || !line.endsWith("|")) return false;
        String[] cells = line.substring(1, line.length() - 1).split("\\|", -1);
        return cells.length >= 2 && java.util.Arrays.stream(cells).allMatch(cell -> cell.strip().matches(":?-{3,}:?"));
    }

    private static EvidenceSource source(InputDocument document) {
        return EvidenceSources.contract("External requirement — " + document.path(), document.path().toString(),
                "document:" + document.sha256(), 1, document.content());
    }
}
