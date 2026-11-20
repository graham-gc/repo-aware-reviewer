package reviewagent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

record EvidenceRequest(int line, String text) {
    private static final Pattern MARKER = Pattern.compile("REQUEST_[A-Z][A-Z_]*\\|");
    static final String SCOPED_GUIDANCE = """

            SCOPED EVIDENCE REQUESTS
            State the missing premise and which answer would change the verdict. Request related base and target
            excerpts together. Use a line range or symbol selector when the file is known. To locate callers or
            readers in tracked source, use a literal symbol or resource name from the supplied evidence:
            REQUEST_FILE|target|src/main/**/*.java|references:Service.write|Find the caller and its guard
            In <adaptive-review> XML, put the raw request lines directly inside <requests>, without child tags.
            In structured JSON, use one string per item in the requests array. For <verification> XML, put the
            lines after the closing </verification> tag.
            The CLI supplies bounded pinned excerpts for a literal reference search. Check the binding and reachability.
            A file-name index alone will not schedule a scoped follow-up. If the decisive premise cannot be located,
            record the specific evidence gap in the verdict.
            """;

    String key() {
        int count = text.startsWith("REQUEST_KNOWLEDGE|") ? 3 : text.startsWith("REQUEST_LANGUAGE|") ? 7 : 5;
        String[] fields = text.split("\\|", count);
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
