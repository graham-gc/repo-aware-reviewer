package reviewagent;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

final class EvidenceText {
    static final Pattern SOURCE_BLOCK = Pattern.compile(
            "(?m)^### ([^\\r\\n]+)\\R\\R(`{3,})([^\\r\\n]*)\\R([\\s\\S]*?)^\\2[ \\t]*(?:\\R|$)");
    private EvidenceText() {
    }

    static int bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    static String linePrefix(String text, int maxBytes) {
        if (maxBytes <= 0) {
            return "";
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return text;
        }
        int end = maxBytes;
        while (end > 0 && bytes[end - 1] != '\n') {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    static String block(String title, String language, String content) {
        int longest = java.util.regex.Pattern.compile("`+").matcher(content).results()
                .mapToInt(match -> match.group().length()).max().orElse(0);
        String fence = "`".repeat(Math.max(3, longest + 1));
        return "### " + title + "\n\n" + fence + language + "\n" + content + "\n" + fence + "\n\n";
    }

    static String numberedLines(java.util.List<String> lines) {
        StringBuilder numbered = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) numbered.append('\n');
            numbered.append(numberedLine(i + 1, lines.get(i)));
        }
        return numbered.toString();
    }

    static String numberedLine(int number, String text) { return number + " | " + text; }

    static String oneLine(String value) {
        return value.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
    }
}
