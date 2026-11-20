package reviewagent;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

record EvidenceQuery(String command, String side, String language, String context, String resource,
                     String selector, String reason) {
    private static final String REFERENCES = "references:";
    private static final Pattern REFERENCE_NAME = Pattern.compile("[\\p{L}_$][\\p{L}\\p{N}_$-]*(?:\\.[\\p{L}_$][\\p{L}\\p{N}_$-]*)*");

    enum Operation { READ_FILE, FIND_FILES, FIND_REFERENCES, LANGUAGE_RESOURCE }
    Operation operation() {
        if (!command.equals("REQUEST_FILE")) return Operation.LANGUAGE_RESOURCE;
        if (selector.equals("index")) return Operation.FIND_FILES;
        return selector.startsWith(REFERENCES) ? Operation.FIND_REFERENCES : Operation.READ_FILE;
    }

    String reference() { return selector.substring(REFERENCES.length()); }

    static EvidenceQuery parse(String line, LanguageRegistry languages) throws IOException {
        String[] fields = line.split("\\|", line.startsWith("REQUEST_LANGUAGE|") ? 7 : 5);
        if (line.length() > 1200 || fields.length < 2 || !(fields[1].equals("base") || fields[1].equals("target")))
            throw new IOException("Expected a bounded evidence request at base or target");
        if (fields[0].equals("REQUEST_LANGUAGE")) {
            if (fields.length != 7 || java.util.Arrays.stream(fields).anyMatch(String::isBlank)) throw new IOException("Expected seven REQUEST_LANGUAGE fields");
            return new EvidenceQuery(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5], fields[6]);
        }
        if (fields.length != 5 || java.util.Arrays.stream(fields).anyMatch(String::isBlank)) throw new IOException("Expected five file or legacy evidence fields");
        if (fields[0].equals("REQUEST_FILE") && fields[3].startsWith(REFERENCES)
                && !REFERENCE_NAME.matcher(fields[3].substring(REFERENCES.length())).matches())
            throw new IOException("A reference search requires a literal symbol or resource name");
        String language = fields[0].equals("REQUEST_FILE") ? "" : languages.legacy(fields[0]).id();
        return new EvidenceQuery(fields[0], fields[1], language, "", fields[2], fields[3], fields[4]);
    }
    List<String> key() { return List.of(command, side, language, context, resource, selector); }
    String label() { return side + " " + (language.isEmpty() || !command.equals("REQUEST_LANGUAGE") ? "" : "[" + language + "] " + context + " -> ") + resource + " " + selector; }
}
