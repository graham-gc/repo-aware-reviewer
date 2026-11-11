package reviewagent;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Shared request counts and byte budget across discovery, verification and adjudication. */
final class EvidenceSession {
    private final EvidenceBundle evidence;
    private final SupplementalEvidence collector;
    private final int fileLimit;
    private final int knowledgeLimit;
    private final Set<String> seen = new LinkedHashSet<>();
    private final StringBuilder markdown = new StringBuilder();
    private final StringBuilder coverage = new StringBuilder();
    private int files;
    private int queries;

    EvidenceSession(EvidenceBundle evidence, SupplementalEvidence collector, int fileLimit, int knowledgeLimit) {
        this.evidence = evidence;
        this.collector = collector;
        this.fileLimit = fileLimit;
        this.knowledgeLimit = knowledgeLimit;
    }

    boolean load(String replies, String phase) throws InterruptedException {
        StringBuilder fresh = new StringBuilder();
        int newFiles = 0;
        int newQueries = 0;
        int repeated = 0;
        for (EvidenceRequest request : EvidenceRequest.parse(replies)) {
            int fields = request.text().startsWith("REQUEST_KNOWLEDGE|") ? 3 : request.text().startsWith("REQUEST_LANGUAGE|") ? 7 : 5;
            String[] parts = request.text().split("\\|", fields);
            boolean knowledge = parts[0].equals("REQUEST_KNOWLEDGE");
            String key = knowledge && parts.length >= 3 ? parts[0] + "|" + parts[1].strip().toLowerCase(Locale.ROOT)
                    : parts.length >= 5 ? String.join("|", java.util.Arrays.copyOf(parts, parts.length - 1)) : request.text();
            if (!seen.add(key)) {
                repeated++;
                continue;
            }
            fresh.append(request.text()).append('\n');
            if (knowledge) newQueries++; else newFiles++;
        }
        coverage.append("\n### ").append(phase).append("\n\nRepeated requests not retried: ").append(repeated).append(".\n");
        if (fresh.isEmpty() && !markdown.isEmpty()) return false;
        int remaining = evidence.supplementBudget() - EvidenceText.bytes(markdown.toString());
        if (remaining < 1024) {
            coverage.append("Insufficient remaining evidence byte budget including request diagnostics; remaining requests unresolved.\n");
            return false;
        }
        String requests = fresh.toString();
        if (fileLimit > 0 && files >= fileLimit) {
            requests = requests.lines().filter(line -> line.startsWith("REQUEST_KNOWLEDGE|"))
                    .collect(java.util.stream.Collectors.joining("\n"));
            coverage.append("File/language request limit exhausted; skipped: ").append(newFiles).append(".\n");
        }
        if (knowledgeLimit > 0 && queries >= knowledgeLimit) {
            requests = requests.lines().filter(line -> !line.startsWith("REQUEST_KNOWLEDGE|"))
                    .collect(java.util.stream.Collectors.joining("\n"));
            coverage.append("Knowledge query limit exhausted; skipped: ").append(newQueries).append(".\n");
        }
        EvidenceBundle round = new EvidenceBundle(evidence.scope(), evidence.changedFiles(), evidence.markdown(),
                evidence.coverage(), remaining, evidence.scopedKnowledge());
        SupplementalEvidence.Result result = collector.collect(round, requests,
                fileLimit == 0 ? 0 : Math.max(1, fileLimit - files),
                knowledgeLimit == 0 ? 0 : Math.max(1, knowledgeLimit - queries));
        files += fileLimit == 0 ? newFiles : Math.min(newFiles, Math.max(0, fileLimit - files));
        queries += knowledgeLimit == 0 ? newQueries : Math.min(newQueries, Math.max(0, knowledgeLimit - queries));
        markdown.append(result.markdown());
        coverage.append(result.coverage());
        return result.hasMaterial();
    }

    String markdown() { return markdown.toString(); }
    String coverage() { return coverage.toString(); }
    EvidenceIndex index() { return new EvidenceIndex(evidence, markdown()); }
}
