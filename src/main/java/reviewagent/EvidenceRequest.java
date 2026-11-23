package reviewagent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

record EvidenceRequest(int line, String text) {
    private static final Pattern MARKER = Pattern.compile("REQUEST_[A-Z][A-Z_]*\\|");
    private static final Pattern DISCOVERY_OWNER = Pattern.compile("\\[finding:([1-9][0-9]{0,8})\\]\\h+\\S");
    static final String SCOPED_GUIDANCE = """

            SCOPED EVIDENCE REQUESTS
            Put one verifiable missing premise in nextCheck. Explain in reason how each answer would affect
            the verdict. Each request in this group must be necessary to answer that same question. Include
            related base and target excerpts or a consumer and its decisive guard together when both are
            needed. Omit optional background. Prefer exact line ranges or symbols over whole files.
            For a declaration, name its file and use the language's declaration selector, for example
            REQUEST_FILE|target|src/Worker.java|declaration:Worker#run|Check the declared parameter types
            Use references only to locate uses. A reference or constructor does not establish a declaration.
            If a narrower selector in the same file is sufficient on its own, declare it as a fallback:
            REQUEST_FILE|target|src/Worker.java|symbol:run;fallback=42:49|Read the call and the flag passed to it
            REQUEST_FILE|target|src/Worker.java|symbol:run;fallback=near:dispatch(|Read the deciding call inside run
            The CLI tries the fallback locally if the first selector cannot be supplied. Do not guess line
            numbers. A near fallback needs one literal match inside the requested symbol; verify the binding
            and surrounding control flow. Use a directory or glob with references to locate callers or readers:
            REQUEST_FILE|target|src/main/**/*.java|references:Service.write|Find the caller and its guard
            REQUEST_FILE|target|src/main/**/*.java|references:new Worker|Locate constructor call sites
            The CLI supplies bounded pinned excerpts for a literal reference search. Check the binding and reachability.
            A scoped follow-up requires a result for every request in the group and at least one new
            evidence block. A list of file names cannot satisfy a required request. If a decisive premise
            cannot be located, record the specific evidence gap in the verdict.
            """;

    String key() {
        int count = text.startsWith("REQUEST_KNOWLEDGE|") ? 3 : text.startsWith("REQUEST_LANGUAGE|") ? 7 : 5;
        String[] fields = text.split("\\|", count);
        if (fields.length == 5 && fields[0].equals("REQUEST_FILE")) fields[3] = EvidenceQuery.normalizeSelector(fields[3]);
        return text.startsWith("REQUEST_KNOWLEDGE|") && fields.length >= 3
                ? fields[0] + "|" + fields[1].strip().toLowerCase(Locale.ROOT)
                : fields.length >= 5 ? String.join("|", java.util.Arrays.copyOf(fields, fields.length - 1)) : text;
    }

    boolean navigationOnly() {
        String[] fields = text.split("\\|", 7);
        return fields.length >= 5 && fields[0].equals("REQUEST_FILE") && fields[3].strip().equals("index")
                || fields.length == 7 && fields[0].equals("REQUEST_LANGUAGE") && fields[5].strip().equals("index");
    }

    int discoveryOwner() {
        String[] fields = text.split("\\|", 5);
        if (fields.length != 5 || !fields[0].equals("REQUEST_FILE") || navigationOnly()
                || fields[3].startsWith("references:")) return 0;
        var owner = DISCOVERY_OWNER.matcher(fields[4].stripLeading());
        return owner.lookingAt() ? Integer.parseInt(owner.group(1)) : 0;
    }

    static List<EvidenceRequest> parse(String candidates) {
        var requests = new ArrayList<EvidenceRequest>();
        int lineNumber = 0;
        for (String candidate : candidates.lines().toList()) {
            lineNumber++;
            String line = candidate.strip();
            var marker = MARKER.matcher(line);
            if (!marker.lookingAt()) {
                continue;
            }
            int start = 0;
            while (marker.find()) {
                requests.add(new EvidenceRequest(lineNumber, line.substring(start, marker.start()).stripTrailing()));
                start = marker.start();
            }
            requests.add(new EvidenceRequest(lineNumber, line.substring(start)));
        }
        return List.copyOf(requests);
    }
}
