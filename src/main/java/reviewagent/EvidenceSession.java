package reviewagent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;

/** Shared request counts and byte budget across discovery, verification and adjudication. */
final class EvidenceSession {
    record Delivery(Set<String> sources, List<String> missing, List<String> recoveries) { }
    private final EvidenceBundle evidence;
    private final SupplementalEvidence collector;
    private final int fileLimit;
    private final int knowledgeLimit;
    private final Set<String> seen = new LinkedHashSet<>();
    private final Map<String, Set<String>> fulfilled = new LinkedHashMap<>();
    private final Map<String, String> failures = new LinkedHashMap<>();
    private final Map<String, String> recoveredPaths = new LinkedHashMap<>();
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
        return load(replies, phase, "");
    }

    void loadDiscovery(String replies, String phase) throws InterruptedException {
        load(replies, phase, replies);
    }

    private boolean load(String replies, String phase, String requestGroup) throws InterruptedException {
        int coverageStart = coverage.length();
        try { return loadRound(replies, phase, requestGroup); }
        finally { lastCoverage = coverage.substring(coverageStart); }
    }

    private boolean loadRound(String replies, String phase, String requestGroup) throws InterruptedException {
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
                knowledgeLimit == 0 ? 0 : Math.max(1, knowledgeLimit - queries), requestGroup);
        files += fileLimit == 0 ? newFiles : Math.min(newFiles, Math.max(0, fileLimit - files));
        queries += knowledgeLimit == 0 ? newQueries : Math.min(newQueries, Math.max(0, knowledgeLimit - queries));
        markdown.append(result.markdown());
        catalog = catalog.plus(result.catalog());
        result.resolvedSources().forEach((key, ids) -> { if (!ids.isEmpty()) fulfilled.put(key, ids); });
        failures.putAll(result.failures());
        recoveredPaths.putAll(result.recoveredPaths());
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

    List<String> outstandingDiscovery(String requests, EvidenceCatalog supplied) {
        return delivery(requests, supplied, true, true).missing();
    }
    record VerificationBrief(String question, Map<Integer, String> premises, Map<Integer, List<VerificationRead>> reads) {
        static final VerificationBrief EMPTY = new VerificationBrief("", Map.of(), Map.of());

        Set<String> sources() {
            return reads.values().stream().flatMap(List::stream).flatMap(read -> read.sources().stream())
                    .collect(java.util.stream.Collectors.toSet());
        }

        List<Set<String>> premiseSources() {
            return premises.keySet().stream().sorted().map(id -> reads.getOrDefault(id, List.of()).stream()
                    .flatMap(read -> read.sources().stream()).collect(java.util.stream.Collectors.toUnmodifiableSet())).toList();
        }

        String instructions(EvidenceCatalog supplied) {
            StringBuilder text = new StringBuilder();
            if (!question.isEmpty())
                text.append("\n").append(EvidenceText.block("Discovery's question for independent verification", "text", question))
                        .append("Treat it as a lead. Check all facts needed to decide the original candidate.\n");
            if (!reads.isEmpty() || !premises.isEmpty()) {
                text.append("\nDISCOVERY EVIDENCE HANDOFF\n");
                premises.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                    int id = entry.getKey();
                    text.append("Premise ").append(id).append(" — ")
                            .append(entry.getValue().isBlank() ? "Conflicting descriptions in discovery" : entry.getValue()).append('\n');
                    var required = reads.getOrDefault(id, List.of());
                    if (required.isEmpty()) text.append("- Discovery did not name a source. Check the supplied evidence for this premise.\n");
                    required.forEach(read -> text.append(read.describe(supplied)));
                });
                reads.getOrDefault(0, List.of()).forEach(read -> text.append(read.describe(supplied)));
                text.append("Check the supplied evidence for a decisive refutation first. For any remaining decisive gap, "
                        + "request a specific alternative or a narrower excerpt, or state what a maintainer must establish.\n");
            }
            return text.toString();
        }
    }

    record VerificationRead(String request, Set<String> sources, String failure, String purpose, String recovery) {
        String describe(EvidenceCatalog supplied) {
            return "- " + EvidenceText.oneLine(request) + " — " + availability(supplied) + ". "
                    + (recovery.isEmpty() ? "" : recovery + ". ") + "Check: " + purpose + "\n";
        }

        private String availability(EvidenceCatalog supplied) {
            if (sources.isEmpty()) return failure.isBlank() ? "not collected" : "unavailable: " + failure;
            var visible = supplied.select(sources);
            if (visible.sources().size() < sources.size()) return "collected, but not fully included in this prompt";
            return "supplied as " + visible.sources().stream().map(visible::reference)
                    .collect(java.util.stream.Collectors.joining(", "));
        }
    }

    Map<String, VerificationBrief> verificationBriefs(Map<String, String> questions,
            Map<String, Map<Integer, String>> candidatePremises, Map<String, List<EvidenceRequest>> requests) {
        var byCandidate = new LinkedHashMap<String, VerificationBrief>();
        var candidates = new LinkedHashSet<>(questions.keySet());
        candidates.addAll(candidatePremises.keySet());
        candidates.addAll(requests.keySet());
        for (String candidate : candidates) {
            var premises = candidatePremises.getOrDefault(candidate, Map.of());
            var byPremise = new LinkedHashMap<Integer, LinkedHashMap<String, VerificationRead>>();
            for (var request : requests.getOrDefault(candidate, List.of())) {
                String key = request.key();
                int premise = premises.containsKey(request.discoveryPremise()) ? request.discoveryPremise() : 0;
                byPremise.computeIfAbsent(premise, unused -> new LinkedHashMap<>()).putIfAbsent(key,
                        new VerificationRead(key, fulfilled.getOrDefault(key, Set.of()), failures.getOrDefault(key, ""),
                                EvidenceText.linePrefix(EvidenceText.oneLine(request.discoveryPurpose()), 300),
                                recoveredPaths.getOrDefault(key, "")));
            }
            var reads = new LinkedHashMap<Integer, List<VerificationRead>>();
            byPremise.forEach((id, byRequest) -> reads.put(id, List.copyOf(byRequest.values())));
            byCandidate.put(candidate, new VerificationBrief(questions.getOrDefault(candidate, ""), Map.copyOf(premises), Map.copyOf(reads)));
        }
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
        if (!delivery.recoveries().isEmpty()) lastCoverage += "\n" + String.join("\n", delivery.recoveries()) + "\n";
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
        return delivery(requests, supplied, includeNavigation, false);
    }

    private Delivery delivery(String requests, EvidenceCatalog supplied, boolean includeNavigation, boolean suppliedOnly) {
        var result = new LinkedHashSet<String>();
        var missing = new LinkedHashSet<String>();
        var recoveries = new LinkedHashSet<String>();
        var visible = supplied.sources().stream().map(EvidenceSource::id).collect(java.util.stream.Collectors.toSet());
        var available = catalog();
        var suppliedBundle = bundle(supplied, 0);
        for (EvidenceRequest request : EvidenceRequest.parse(requests)) {
            if (!collector.reusable(suppliedBundle, request.text()).isEmpty()) continue;
            boolean fulfilledRequest = false;
            for (String id : fulfilled.getOrDefault(request.key(), Set.of())) {
                var source = available.source(id);
                if (source == null || !includeNavigation && source.role(EvidenceSource.Role.INDEX)) continue;
                boolean alreadySupplied = visible.contains(id) || FileEvidenceCache.alreadySupplied(source, supplied);
                if (!alreadySupplied && suppliedOnly) continue;
                fulfilledRequest = true;
                if (!alreadySupplied) result.add(id);
            }
            if (!fulfilledRequest) {
                String reason = failures.get(request.key());
                missing.add(reason == null ? request.key() : request.key() + " (" + reason + ")");
            } else if (recoveredPaths.containsKey(request.key())) {
                recoveries.add(recoveredPaths.get(request.key()));
            }
        }
        return new Delivery(java.util.Collections.unmodifiableSet(result), List.copyOf(missing), List.copyOf(recoveries));
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
