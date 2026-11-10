package reviewagent;

import java.util.List;

record EvidenceBundle(ReviewScope scope, List<String> changedFiles, String markdown) {
}

