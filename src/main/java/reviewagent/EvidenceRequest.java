package reviewagent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

record EvidenceRequest(int line, String text) {
    private static final Pattern MARKER = Pattern.compile("REQUEST_[A-Z][A-Z_]*\\|");

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
