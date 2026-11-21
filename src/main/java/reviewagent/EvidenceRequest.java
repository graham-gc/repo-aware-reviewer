package reviewagent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

record EvidenceRequest(int line, String text) {
    private static final Pattern MARKER = Pattern.compile("REQUEST_[A-Z][A-Z_]*\\|");
    static final String SCOPED_GUIDANCE = """

            SCOPED EVIDENCE REQUESTS
            Put one verifiable missing premise in nextCheck. Explain in reason how each answer would affect
            the verdict. Each request in this group must be necessary to answer that same question. Include
            related base and target excerpts or a consumer and its decisive guard together when both are
            needed. Omit optional background. Prefer exact line ranges or symbols over whole files.
            If a narrower selector in the same file is sufficient on its own, declare it as a fallback:
            REQUEST_FILE|target|src/Worker.java|symbol:run;fallback=42:49|Read the call and the flag passed to it
            The CLI tries the fallback locally if the first selector cannot be supplied. Do not guess line
            numbers. Use a directory or glob with references to locate callers or readers:
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
