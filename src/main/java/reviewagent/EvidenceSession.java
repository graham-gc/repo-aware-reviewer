package reviewagent;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;

/** Shared request counts and byte budget across discovery, verification and adjudication. */
final class EvidenceSession {
    private final EvidenceBundle evidence;
    private final SupplementalEvidence collector;
    private final int fileLimit;
    private final int knowledgeLimit;
    private final Set<String> seen = new LinkedHashSet<>();
    private final Map<String, Set<String>> fulfilled = new LinkedHashMap<>();
    private final StringBuilder markdown = new StringBuilder();
    private final StringBuilder coverage = new StringBuilder();
    private int files;
    private int queries;
    private EvidenceCatalog catalog = EvidenceCatalog.empty();
    private String lastCoverage = "";

    EvidenceSession(EvidenceBundle evidence, SupplementalEvidence collector, int fileLimit, int knowledgeLimit) {
        this.evidence = evidence;
        this.collector = collector;
        this.fileLimit = fileLimit;
        this.knowledgeLimit = knowledgeLimit;
    }

    boolean load(String replies, String phase) throws InterruptedException {
        int coverageStart = coverage.length();
        try { return loadRound(replies, phase); }
        finally { lastCoverage = coverage.substring(coverageStart); }
    }

    private boolean loadRound(String replies, String phase) throws InterruptedException {
        StringBuilder fresh = new StringBuilder();
        int newFiles = 0;
        int newQueries = 0;
        int repeated = 0;
        StringBuilder reused = new StringBuilder();
        for (EvidenceRequest request : EvidenceRequest.parse(replies)) {
            boolean knowledge = request.text().startsWith("REQUEST_KNOWLEDGE|");
            String key = request.key();
            Set<String> available = collector.reusable(bundle(catalog(), 0), request.text());
            if (available.isEmpty()) available = fulfilled.getOrDefault(key, Set.of());
            if (!available.isEmpty()) {
                fulfilled.put(key, available);
                if (!seen.add(key)) repeated++;
                reused.append("- ").append(key).append(": REUSED (already collected; no repository read).\n");
                continue;
            }
            if (!seen.add(key)) {
                repeated++;
                continue;
            }
            fresh.append(request.text()).append('\n');
            if (knowledge) newQueries++; else newFiles++;
        }
        coverage.append("\n### ").append(phase).append("\n\nRepeated requests not retried: ").append(repeated).append(".\n");
        coverage.append(reused);
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
        EvidenceBundle round = bundle(catalog(), remaining);
        SupplementalEvidence.Result result = collector.collect(round, requests,
                fileLimit == 0 ? 0 : Math.max(1, fileLimit - files),
                knowledgeLimit == 0 ? 0 : Math.max(1, knowledgeLimit - queries));
        files += fileLimit == 0 ? newFiles : Math.min(newFiles, Math.max(0, fileLimit - files));
        queries += knowledgeLimit == 0 ? newQueries : Math.min(newQueries, Math.max(0, knowledgeLimit - queries));
        markdown.append(result.markdown());
        catalog = catalog.plus(result.catalog());
        result.resolvedSources().forEach((key, ids) -> { if (!ids.isEmpty()) fulfilled.put(key, ids); });
        coverage.append(result.coverage());
        return result.hasMaterial();
    }

    /** Re-deliver known material to this recipient, not to every candidate sharing the session. */
    Set<String> delivery(String requests, EvidenceCatalog supplied) {
        var result = new LinkedHashSet<String>();
        var visible = supplied.sources().stream().map(EvidenceSource::id).collect(java.util.stream.Collectors.toSet());
        for (EvidenceRequest request : EvidenceRequest.parse(requests)) {
            if (!collector.reusable(bundle(supplied, 0), request.text()).isEmpty()) continue;
            for (String id : fulfilled.getOrDefault(request.key(), Set.of())) if (!visible.contains(id)) result.add(id);
        }
        return java.util.Collections.unmodifiableSet(result);
    }

    void recordDelivery(String requester, Set<String> ids) {
        if (ids.isEmpty()) return;
        String sources = catalog().sources().stream().filter(source -> ids.contains(source.id())).map(EvidenceSource::id)
                .collect(java.util.stream.Collectors.joining(", "));
        coverage.append("- Fulfilled requests from ").append(requester).append(": queued for this candidate: ").append(sources).append(".\n");
    }

    private EvidenceBundle bundle(EvidenceCatalog sources, int budget) {
        return new EvidenceBundle(evidence.scope(), evidence.changedFiles(), evidence.markdown(), evidence.coverage(), budget,
                evidence.scopedKnowledge(), sources);
    }

    String markdown() { return markdown.toString(); }
    String coverage() { return coverage.toString(); }
    String lastCoverage() { return lastCoverage; }
    EvidenceIndex index() { return new EvidenceIndex(catalog()); }
    EvidenceCatalog catalog() { return evidence.catalog().plus(catalog); }
}
