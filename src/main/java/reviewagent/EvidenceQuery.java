package reviewagent;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

record EvidenceQuery(String command, String side, String language, String context, String resource,
                     String selector, String reason, String fallback) {
    private static final String FALLBACK = ";fallback=";
    static final Pattern LINES = Pattern.compile("[1-9][0-9]{0,6}:[1-9][0-9]{0,6}");
    static final String SYMBOL = "symbol:";
    static final String DECLARATION = "declaration:";
    static final String NEAR = "near:";
    private static final String REFERENCES = "references:";
    private static final String REFERENCE_NAME = "[\\p{L}_$][\\p{L}\\p{N}_$-]*(?:\\.[\\p{L}_$][\\p{L}\\p{N}_$-]*)*";
    private static final Pattern NAME = Pattern.compile(REFERENCE_NAME);
    private static final Pattern ANCHOR = Pattern.compile(REFERENCE_NAME + "\\(?");
    private static final Pattern CONSTRUCTION = Pattern.compile("new[ \\t]+(" + REFERENCE_NAME + ")[ \\t]*\\(?");

    enum Operation { READ_FILE, FIND_FILES, FIND_REFERENCES, LANGUAGE_RESOURCE }
    Operation operation() {
        if (!command.equals("REQUEST_FILE")) return Operation.LANGUAGE_RESOURCE;
        if (selector.equals("index")) return Operation.FIND_FILES;
        return selector.startsWith(REFERENCES) ? Operation.FIND_REFERENCES : Operation.READ_FILE;
    }

    String reference() { return selector.substring(REFERENCES.length()); }

    static String normalizeSelector(String selector) {
        if (!selector.startsWith(REFERENCES)) return selector;
        var construction = CONSTRUCTION.matcher(selector.substring(REFERENCES.length()));
        return construction.matches() ? REFERENCES + "new " + construction.group(1) : selector;
    }

    static EvidenceQuery parse(String line, LanguageRegistry languages) throws IOException {
        String[] fields = line.split("\\|", line.startsWith("REQUEST_LANGUAGE|") ? 7 : 5);
        if (line.length() > 1200 || fields.length < 2 || !(fields[1].equals("base") || fields[1].equals("target")))
            throw new IOException("Expected a bounded evidence request at base or target");
        if (fields[0].equals("REQUEST_LANGUAGE")) {
            if (fields.length != 7 || java.util.Arrays.stream(fields).anyMatch(String::isBlank)) throw new IOException("Expected seven REQUEST_LANGUAGE fields");
            return new EvidenceQuery(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5], fields[6], "");
        }
        if (fields.length != 5 || java.util.Arrays.stream(fields).anyMatch(String::isBlank)) throw new IOException("Expected five file or legacy evidence fields");
        boolean file = fields[0].equals("REQUEST_FILE");
        String fallback = "";
        int alternative = file ? fields[3].indexOf(FALLBACK) : -1;
        if (alternative >= 0) {
            fallback = fields[3].substring(alternative + FALLBACK.length());
            fields[3] = fields[3].substring(0, alternative);
            boolean range = LINES.matcher(fallback).matches();
            boolean near = fallback.startsWith(NEAR) && ANCHOR.matcher(fallback.substring(NEAR.length())).matches();
            boolean narrower = fields[3].equals("all") && (range || named(fallback, SYMBOL) || named(fallback, DECLARATION))
                    || named(fields[3], SYMBOL) && (range || near);
            if (!narrower) throw new IOException("Expected a narrower file selector after " + FALLBACK);
        }
        if (file && fields[3].startsWith(DECLARATION) && !named(fields[3], DECLARATION))
            throw new IOException("Expected a name after " + DECLARATION);
        if (file && fields[3].startsWith(REFERENCES)) {
            String reference = fields[3].substring(REFERENCES.length());
            if (!NAME.matcher(reference).matches() && !CONSTRUCTION.matcher(reference).matches())
                throw new IOException("A reference search requires a literal symbol, resource name, or new Type");
            fields[3] = normalizeSelector(fields[3]);
        }
        String language = file ? "" : languages.legacy(fields[0]).id();
        return new EvidenceQuery(fields[0], fields[1], language, "", fields[2], fields[3], fields[4], fallback);
    }

    private static boolean named(String selector, String prefix) {
        return selector.startsWith(prefix) && !selector.substring(prefix.length()).isBlank()
                && !selector.contains(FALLBACK);
    }

    EvidenceQuery fallbackQuery() {
        return new EvidenceQuery(command, side, language, context, resource, fallback, reason, "");
    }

    List<String> key() { return List.of(command, side, language, context, resource, selector, fallback); }
    String label() { return side + " " + (language.isEmpty() || !command.equals("REQUEST_LANGUAGE") ? "" : "[" + language + "] " + context + " -> ") + resource + " " + selector; }
}
