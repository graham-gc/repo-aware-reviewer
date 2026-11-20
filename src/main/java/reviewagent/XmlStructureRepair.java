package reviewagent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Bounded delimiter repairs that preserve response values and require an explicit root closer. */
final class XmlStructureRepair {
    record Candidate(String response, String note) { }
    private enum ScanMode { TEXT_BOUNDARIES, RAW_RESPONSE, PROTECTED_FIELDS }
    private static final Pattern TAG = Pattern.compile("<(/?)([A-Za-z][A-Za-z0-9-]*)(?:\\s[^<>]*?)?\\s*(/?)>");
    private static final int MAX_CANDIDATES = 12;

    static void checkTextBoundaries(String content) throws AmbiguousTextFieldException {
        var possible = new ArrayList<Candidate>();
        scan(content, content, 0, content.length(), ScanMode.TEXT_BOUNDARIES, possible);
        if (!possible.isEmpty()) throw new AmbiguousTextFieldException("Ambiguous review text-field boundary: "
                + possible.getFirst().note().replace("Inserted missing", "Possible missing")
                + " No repair applied: close the text field explicitly, or use CDATA for literal protocol tags.");
    }

    static List<Candidate> candidates(String response, String root) {
        String stripped = response.stripLeading();
        if (stripped.startsWith(root + ">") && stripped.contains("</" + root + ">")) {
            int start = response.length() - stripped.length();
            return List.of(new Candidate(response.substring(0, start) + "<" + stripped,
                    "Inserted missing < at the opening response tag; delimiter insertion only; raw response retained."));
        }
        var opening = Pattern.compile("(?m)^[ \\t]*<" + Pattern.quote(root) + "(?=[\\s>])").matcher(response);
        if (!opening.find()) return List.of();
        int start = response.indexOf('<', opening.start());
        int end = response.lastIndexOf("</" + root + ">");
        if (end < start) return List.of();
        end += root.length() + 3;
        String content = response.substring(start, end);
        var result = new ArrayList<Candidate>();
        scan(response, content, start, end, ScanMode.RAW_RESPONSE, result);
        try {
            // Complete scalar fields are opaque before considering missing container delimiters.
            scan(response, XmlTextFields.protect(content), start, end, ScanMode.PROTECTED_FIELDS, result);
        } catch (IOException incompleteScalar) { /* Scalar candidates remain available; no guessing at EOF. */ }
        return List.copyOf(result);
    }

    private static void scan(String response, String content, int start, int end, ScanMode mode, List<Candidate> result) {
        var stack = new ArrayList<String>();
        var tokens = XmlTextFields.TOKEN.matcher(content);
        String previousCloser = "";
        int previousCloserEnd = 0;
        while (tokens.find() && result.size() < MAX_CANDIDATES) {
            var tag = TAG.matcher(tokens.group());
            if (!tag.matches()) continue;
            String name = tag.group(2);
            boolean closing = tag.group(1).equals("/"), selfClosing = tag.group(3).equals("/");
            if (!closing && ReviewSchema.TEXT.contains(name) && !selfClosing) {
                XmlTextFields.End fieldEnd = null;
                try { fieldEnd = XmlTextFields.end(content, tokens.end(), name); }
                catch (IOException missingEnd) { /* Search only for an explicit closing parent. */ }
                if (mode != ScanMode.PROTECTED_FIELDS && !stack.isEmpty()) {
                    int boundary = parentBoundary(content, tokens.end(), fieldEnd == null ? content.length() : fieldEnd.contentEnd(), stack.getLast());
                    if (boundary >= 0) add(result, response, content, start, end, boundary, "</" + name + ">",
                            "Inserted missing </" + name + "> before </" + stack.getLast() + ">", name);
                }
                if (fieldEnd == null) return;
                tokens.region(fieldEnd.end(), content.length());
                continue;
            }
            if (!closing) {
                if (!ReviewSchema.container(name)) return;
                if (!selfClosing) stack.add(name);
            } else {
                int ancestor = stack.lastIndexOf(name);
                if (ancestor < 0) {
                    // An immediately repeated child closer can be a typo for the current container's closer.
                    if (mode == ScanMode.RAW_RESPONSE && stack.size() > 1 && name.equals(previousCloser)
                            && ReviewSchema.allows(stack.getLast(), name)
                            && content.substring(previousCloserEnd, tokens.start()).isBlank()
                            && tokens.group().substring(tag.end(2), tokens.group().length() - 1).isBlank()) {
                        String expected = stack.getLast();
                        int from = start + tokens.start() + tag.start(2);
                        int to = start + tokens.start() + tag.end(2);
                        long line = content.substring(0, tokens.start()).chars().filter(c -> c == '\n').count() + 1;
                        result.add(new Candidate(response.substring(0, from) + expected + response.substring(to),
                                "Replaced repeated </" + name + "> with </" + expected + "> at response-block line " + line
                                        + "; only the closing tag name changed; raw response retained."));
                    }
                    return;
                }
                if (ancestor == stack.size() - 1) {
                    previousCloser = name;
                    previousCloserEnd = tokens.end();
                }
                if (mode == ScanMode.PROTECTED_FIELDS && ancestor < stack.size() - 1 && stack.size() - ancestor <= 4) {
                    StringBuilder missing = new StringBuilder();
                    for (int i = stack.size() - 1; i > ancestor; i--) missing.append("</").append(stack.get(i)).append('>');
                    add(result, response, content, start, end, tokens.start(), missing.toString(),
                            "Inserted missing container closers " + missing + " before </" + name + ">", name);
                }
                while (stack.size() > ancestor) stack.removeLast();
            }
        }
    }

    private static int parentBoundary(String text, int from, int before, String parent) {
        var tokens = XmlTextFields.TOKEN.matcher(text).region(from, before);
        while (tokens.find()) {
            if (!tokens.group().matches("</" + Pattern.quote(parent) + "\\s*>")) {
                if (tokens.group().startsWith("</")) return -1; // A conflicting closer is not an omitted delimiter.
                continue;
            }
            int line = text.lastIndexOf('\n', tokens.start() - 1) + 1;
            if (text.substring(line, tokens.start()).isBlank()) return tokens.start();
        }
        return -1;
    }

    private static void add(List<Candidate> result, String response, String content, int start, int end,
                            int position, String insertion, String note, String field) {
        int line = 1 + (int) content.substring(0, position).chars().filter(c -> c == '\n').count();
        String repaired = response.substring(0, start) + content.substring(0, position) + insertion + content.substring(position) + response.substring(end);
        result.add(new Candidate(repaired, note + " at response-block line " + line
                + "; delimiter insertion only; original " + field + " text and raw response retained."));
    }
}
