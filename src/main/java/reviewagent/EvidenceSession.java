package reviewagent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;

/** Shared request counts and byte budget across discovery, verification and adjudication. */
final class EvidenceSession {
    record Delivery(Set<String> sources, List<String> missing) { }
    private final EvidenceBundle evidence;
    private final SupplementalEvidence collector;
    private final int fileLimit;
    private final int knowledgeLimit;
    private final Set<String> seen = new LinkedHashSet<>();
    private final Map<String, Set<String>> fulfilled = new LinkedHashMap<>();
    private final Map<String, String> initialRequests = new LinkedHashMap<>();
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

    boolean requestOnly(String response) { return collector.requestOnly(evidence, response); }

    void recordInitialRequests(String candidate, String requests) { initialRequests.put(candidate, requests); }
    String initialRequests(String candidate) { return initialRequests.getOrDefault(candidate, ""); }

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
        return delivery(requests, supplied, true).sources();
    }

    Delivery discoveryDelivery(String requests, EvidenceCatalog supplied) {
        return delivery(requests, supplied, true);
    }
    record VerificationBrief(String question, Map<String, Set<String>> sourcesByRequest) {
        static final VerificationBrief EMPTY = new VerificationBrief("", Map.of());

        Set<String> sources() {
            return sourcesByRequest.values().stream().flatMap(Set::stream).collect(java.util.stream.Collectors.toSet());
        }

        String instructions(EvidenceCatalog supplied) {
            StringBuilder text = new StringBuilder();
            if (!question.isEmpty())
                text.append("\n").append(EvidenceText.block("Discovery's question for independent verification", "text", question))
                        .append("Treat it as a lead. Check all facts needed to decide the original candidate.\n");
            var ids = supplied.sources().stream().map(EvidenceSource::id).collect(java.util.stream.Collectors.toSet());
            var gaps = sourcesByRequest.entrySet().stream()
                    .filter(entry -> entry.getValue().isEmpty() || !ids.containsAll(entry.getValue()))
                    .map(Map.Entry::getKey).sorted().toList();
            if (!gaps.isEmpty()) {
                text.append("\nPLANNED EVIDENCE THAT IS NOT FULLY PRESENT IN THIS REQUEST\n");
                gaps.forEach(gap -> text.append("- ").append(gap).append('\n'));
                text.append("Check whether the supplied evidence already decides the claim. Request any still-decisive gap together.\n");
            }
            return text.toString();
        }
    }

    Map<String, VerificationBrief> verificationBriefs(Map<String, String> questions, Map<String, List<EvidenceRequest>> requests) {
        var byCandidate = new LinkedHashMap<String, VerificationBrief>();
        questions.forEach((id, question) -> byCandidate.put(id, new VerificationBrief(question, Map.of())));
        requests.forEach((candidate, lines) -> {
            var byRequest = new LinkedHashMap<String, Set<String>>();
            for (var request : lines) byRequest.put(request.key(), Set.copyOf(fulfilled.getOrDefault(request.key(), Set.of())));
            byCandidate.put(candidate, new VerificationBrief(questions.getOrDefault(candidate, ""), Map.copyOf(byRequest)));
        });
        return byCandidate;
    }

    Set<String> deliverScoped(String requester, String requests, EvidenceCatalog supplied) {
        return deliverFollowup(requester, requests, supplied, false);
    }

    Set<String> deliverDecisive(String requester, String requests, EvidenceCatalog supplied) {
        return deliverFollowup(requester, requests, supplied, true);
    }

    private Set<String> deliverFollowup(String requester, String requests, EvidenceCatalog supplied, boolean allowPartial) {
        var delivery = delivery(requests, supplied, false);
        if (delivery.missing().isEmpty()) {
            recordFollowup(requester, requests, delivery.sources());
            return delivery.sources();
        }
        if (allowPartial && !delivery.sources().isEmpty()) {
            recordFollowup(requester, requests, delivery.sources(),
                    "Follow-up on newly supplied evidence; unresolved requests remain explicit gaps.");
            for (String request : delivery.missing())
                coverage.append("  - Unavailable evidence: ").append(EvidenceText.oneLine(request)).append('\n');
            lastCoverage += "Unavailable evidence for this follow-up: " + String.join(", ", delivery.missing()) + ".\n";
            return delivery.sources();
        }
        recordFollowup(requester, requests, Set.of(), "Stopped; required evidence for this question is unavailable.");
        for (String request : delivery.missing())
            coverage.append("  - Missing required evidence: ").append(EvidenceText.oneLine(request)).append('\n');
        return Set.of();
    }

    private Delivery delivery(String requests, EvidenceCatalog supplied, boolean includeNavigation) {
        var result = new LinkedHashSet<String>();
        var missing = new LinkedHashSet<String>();
        var visible = supplied.sources().stream().map(EvidenceSource::id).collect(java.util.stream.Collectors.toSet());
        var available = catalog();
        var suppliedBundle = bundle(supplied, 0);
        for (EvidenceRequest request : EvidenceRequest.parse(requests)) {
            if (!collector.reusable(suppliedBundle, request.text()).isEmpty()) continue;
            boolean fulfilledRequest = false;
            for (String id : fulfilled.getOrDefault(request.key(), Set.of())) {
                var source = available.source(id);
                if (source == null || !includeNavigation && source.role(EvidenceSource.Role.INDEX)) continue;
                fulfilledRequest = true;
                if (!visible.contains(id) && !FileEvidenceCache.alreadySupplied(source, supplied)) result.add(id);
            }
            if (!fulfilledRequest) missing.add(request.key());
        }
        return new Delivery(java.util.Collections.unmodifiableSet(result), List.copyOf(missing));
    }

    void recordDelivery(String requester, Set<String> ids) {
        if (ids.isEmpty()) return;
        String sources = catalog().sources().stream().filter(source -> ids.contains(source.id())).map(EvidenceSource::id)
                .collect(java.util.stream.Collectors.joining(", "));
        coverage.append("- Fulfilled requests from ").append(requester).append(": queued for this candidate: ").append(sources).append(".\n");
    }

    void recordFollowup(String requester, String requests, Set<String> ids) {
        recordFollowup(requester, requests, ids, ids.isEmpty() ? "Stopped; no new evidence for this question."
                : "Follow-up on newly supplied evidence.");
    }

    void recordFollowup(String requester, String requests, Set<String> ids, String outcome) {
        coverage.append("- ").append(requester).append(": ")
                .append(outcome).append("\n");
        for (var request : EvidenceRequest.parse(requests))
            coverage.append("  - Question and requested evidence: ").append(EvidenceText.oneLine(request.text())).append('\n');
        recordDelivery(requester, ids);
    }

    private EvidenceBundle bundle(EvidenceCatalog sources, int budget) {
        return new EvidenceBundle(evidence.scope(), evidence.changedFiles(), evidence.markdown(), evidence.coverage(), budget,
                evidence.scopedKnowledge(), sources);
    }

    String markdown() { return markdown.toString(); }

    Set<String> supplyCollected(String requester, List<EvidenceSource> sources, int maxBytes) {
        int remaining = evidence.supplementBudget() - EvidenceText.bytes(markdown.toString());
        var known = catalog();
        var added = new ArrayList<EvidenceSource>();
        var supplied = new ArrayList<EvidenceSource>();
        var ids = new LinkedHashSet<String>();
        for (var source : sources) {
            String block = source.markdown();
            int bytes = EvidenceText.bytes(block);
            boolean fresh = known.source(source.id()) == null;
            if (bytes > maxBytes || fresh && bytes > remaining || !ids.add(source.id())) continue;
            if (fresh) {
                markdown.append(block);
                remaining -= bytes;
                added.add(source);
            }
            maxBytes -= bytes;
            supplied.add(source);
        }
        if (!added.isEmpty()) catalog = catalog.plus(new EvidenceCatalog(added, true));
        if (!supplied.isEmpty()) {
            coverage.append("- Expanded ").append(requester).append(" with collected implementation: ")
                    .append(supplied.stream().map(source -> EvidenceText.oneLine(source.path())).distinct()
                            .collect(java.util.stream.Collectors.joining(", "))).append(".\n");
        }
        return java.util.Collections.unmodifiableSet(ids);
    }

    void recordVerificationBatch(String task, List<String> candidates, Set<String> accepted) {
        coverage.append("- Shared verification [").append(task).append("](").append(task)
                .append("-state.properties): ").append(String.join(", ", candidates)).append(". Validated in batch: ")
                .append(accepted.isEmpty() ? "none" : String.join(", ", accepted))
                .append(". Remaining candidates go to individual verification.\n");
    }

    void recordVerificationReuse(String candidate, String verified) {
        coverage.append("- Verification reused for ").append(candidate).append(" from ").append(verified)
                .append(" (identical candidate and evidence requests; verified proof covered the full claim).\n");
    }

    String coverage() { return coverage.toString(); }
    String lastCoverage() { return lastCoverage; }
    EvidenceIndex index() { return new EvidenceIndex(catalog()); }
    EvidenceCatalog catalog() { return evidence.catalog().plus(catalog); }
}
