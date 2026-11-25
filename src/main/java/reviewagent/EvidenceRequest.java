package reviewagent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

record EvidenceRequest(int line, String text) {
    private static final Pattern MARKER = Pattern.compile("REQUEST_[A-Z][A-Z_]*\\|");
    private static final Pattern DISCOVERY_OWNER = Pattern.compile("\\[finding:([1-9][0-9]{0,8})\\]\\h+\\S");
    private static final Pattern DISCOVERY_PREMISE = Pattern.compile("\\[premise:([1-9][0-9]{0,8})\\]\\h+");
    static final String PATH_GUIDANCE = """
            Use the literal base for the old revision or target for the new one. Copy the full path from the
            repository root exactly as supplied for that revision, including every directory. Replace the
            fields in braces with real values. If the full file path was not supplied, search for a known symbol
            or request an index in a known directory. The root directory is the literal dot:
            `REQUEST_FILE|target|.|index|Locate the implementation`.
            File indexes supply names only. Request the relevant source before drawing a conclusion.
            """;
    static final String SCOPED_GUIDANCE = """

            SCOPED EVIDENCE REQUESTS
            Put one verifiable missing premise in nextCheck. Explain in reason how each answer would affect
            the verdict. Each request in this group must be necessary to answer that same question. Include
            related base and target excerpts or a consumer and its decisive guard together when both are
            needed. Omit optional background. Prefer exact line ranges or symbols over whole files.
            For a declaration, use the file's language selector: `declaration:Worker#run` illustrates a
            Java method signature selector. The file request form is `REQUEST_FILE|{side}|{path}|{selector}|{reason}`.
            Use references only to locate uses. A reference or constructor does not establish a declaration.
            If a narrower selector in the same file is sufficient on its own, declare it as a fallback:
            In the selector field, `35:78;fallback=42:49`, `symbol:run;fallback=42:49`, and
            `symbol:run;fallback=near:dispatch(` illustrate this.
            The CLI tries the fallback locally if the first selector cannot be supplied. Do not guess line
            numbers. A near fallback needs one literal match inside the requested symbol; verify the binding
            and surrounding control flow. Use a directory or glob with references to locate callers or readers:
            For example, the selector can be `references:Service.write` or `references:new Worker`.
            The CLI supplies bounded pinned excerpts for a literal reference search. Check the binding and reachability.
            A scoped follow-up requires a result for every request in the group and at least one new
            evidence block. A list of file names cannot satisfy a required request. If a decisive premise
            cannot be located, record the specific evidence gap in the verdict.
            """;

    String key() {
        int count = fieldCount();
        String[] fields = text.split("\\|", count);
        if (fields.length != count || fields[count - 1].isBlank() || count != 3 && text.length() > EvidenceQuery.MAX_LENGTH)
            return "Malformed request: " + text;
        if (fields.length == 5 && fields[0].equals("REQUEST_FILE")) fields[3] = EvidenceQuery.normalizeSelector(fields[3]);
        return count == 3
                ? fields[0] + "|" + fields[1].strip().toLowerCase(Locale.ROOT)
                : String.join("|", java.util.Arrays.copyOf(fields, count - 1));
    }

    boolean navigationOnly() {
        String[] fields = text.split("\\|", 7);
        return fields.length >= 5 && fields[0].equals("REQUEST_FILE") && fields[3].strip().equals("index")
                || fields.length == 7 && fields[0].equals("REQUEST_LANGUAGE") && fields[5].strip().equals("index");
    }

    int discoveryOwner() {
        int count = fieldCount();
        if (navigationOnly() || count == 5 && !text.startsWith("REQUEST_FILE|")) return 0;
        String[] fields = text.split("\\|", count);
        if (fields.length != count || count == 5 && fields[3].startsWith("references:") && discoveryPremise() == 0) return 0;
        return findingTag(fields[count - 1]);
    }

    int findingTag() {
        int count = fieldCount();
        String[] fields = text.split("\\|", count);
        return fields.length == count ? findingTag(fields[count - 1]) : 0;
    }

    private static int findingTag(String reason) {
        var owner = DISCOVERY_OWNER.matcher(reason.stripLeading());
        return owner.lookingAt() ? Integer.parseInt(owner.group(1)) : 0;
    }

    String discoveryPurpose() {
        String reason = discoveryReason();
        return DISCOVERY_PREMISE.matcher(reason).replaceFirst("").strip();
    }

    int discoveryPremise() {
        var premise = DISCOVERY_PREMISE.matcher(discoveryReason());
        return premise.lookingAt() ? Integer.parseInt(premise.group(1)) : 0;
    }

    private String discoveryReason() {
        String[] fields = text.split("\\|", fieldCount());
        String reason = fields[fields.length - 1].strip();
        return DISCOVERY_OWNER.matcher(reason).lookingAt() ? reason.substring(reason.indexOf(']') + 1).strip() : "";
    }

    private int fieldCount() {
        return text.startsWith("REQUEST_KNOWLEDGE|") ? 3 : text.startsWith("REQUEST_LANGUAGE|") ? 7 : 5;
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
