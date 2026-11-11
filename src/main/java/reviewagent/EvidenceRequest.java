package reviewagent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

record EvidenceRequest(int line, String text) {
    private static final Pattern MARKER = Pattern.compile("REQUEST_[A-Z][A-Z_]*\\|");

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
