package reviewagent;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Reuses an independent verdict when no policy, severity, or conflicting verdict still requires judgment. */
final class PublicationGate {
    private PublicationGate() { }

    private record Span(String path, int first, int last) { }
    private record Proof(Set<Span> trigger, Set<Span> outcome) { }

    static boolean enabledFor(CliOptions options) {
        return options.requirement() == null && options.knowledge() == null;
    }

    static boolean ready(ReviewOutput.Decision decision, EvidenceIndex index, String target) {
        var proof = proof(decision, index, target, true);
        return !proof.trigger().isEmpty() && !proof.outcome().isEmpty();
    }

    static List<ReviewOutput.Decision> reportDecisions(List<ReviewOutput.Decision> decisions, List<List<String>> groups,
                                                       EvidenceIndex index, String target) {
        var byId = new HashMap<String, ReviewOutput.Decision>();
        decisions.forEach(decision -> byId.put(decision.verdict().candidate().id(), decision));
        var omitted = new HashSet<String>();
        for (List<String> group : groups) {
            if (group.size() < 2 || !byId.keySet().containsAll(group)) continue;
            var representative = representative(group.stream().map(byId::get).toList(), index, target);
            if (representative != null) {
                String keep = representative.verdict().candidate().id();
                group.stream().filter(id -> !id.equals(keep)).forEach(omitted::add);
            }
        }
        return decisions.stream().filter(decision -> !omitted.contains(decision.verdict().candidate().id())).toList();
    }

    static ReviewOutput.Decision representative(List<ReviewOutput.Decision> group, EvidenceIndex index, String target) {
        if (group.isEmpty()) return null;
        var first = group.getFirst().verdict().confirmed();
        if (first == null) return null;
        int overlapStart = first.line(), overlapEnd = first.endLine();
        Proof common = proof(group.getFirst(), index, target, false);
        for (var member : group.subList(1, group.size())) {
            var finding = member.verdict().confirmed();
            if (finding == null || !first.path().equals(finding.path())) return null;
            overlapStart = Math.max(overlapStart, finding.line());
            overlapEnd = Math.min(overlapEnd, finding.endLine());
            var witness = proof(member, index, target, false);
            common.trigger().retainAll(witness.trigger());
            common.outcome().retainAll(witness.outcome());
        }
        // The reconciliation stage proposes equivalent behaviors. Require a shared pinned trigger and outcome
        // as well as an overlapping location before one decision can represent several independent verdicts.
        if (overlapStart > overlapEnd || common.trigger().isEmpty() || common.outcome().isEmpty()) return null;
        return group.stream().min(Comparator.comparing(member -> member.verdict().confirmed().severity())).orElseThrow();
    }

    private static Proof proof(ReviewOutput.Decision decision, EvidenceIndex index, String target, boolean exactOutcome) {
        var verdict = decision.verdict();
        var finding = verdict.confirmed();
        var trigger = new HashSet<Span>();
        var outcome = new HashSet<Span>();
        var proof = new Proof(trigger, outcome);
        if (verdict.status() != ReviewOutput.Status.CONFIRMED || verdict.validationFailed() || finding == null
                || !verdict.nextCheck().isBlank()
                || finding.severity().compareTo(ReviewFinding.Severity.MEDIUM) < 0
                || verdict.candidate().severity().compareTo(ReviewFinding.Severity.MEDIUM) < 0
                || decision.relation() != ReviewOutput.Relation.INTRODUCED
                || decision.support().kind() != ReviewSupport.Kind.REACHABLE_FAILURE
                || decision.support().premises().stream().anyMatch(premise -> premise.basis() == ReviewSupport.Basis.CONTRACT))
            return proof;

        trigger.addAll(pinnedSpans(decision, index, target, ReviewSupport.Basis.IMPLEMENTATION, finding.trigger()));
        // Rendering reconciles final, already-grounded members. The fast path additionally requires
        // an explicit link to the exact impact before an independent verdict can skip publication review.
        outcome.addAll(pinnedSpans(decision, index, target, ReviewSupport.Basis.OUTCOME,
                exactOutcome ? finding.impact() : ""));
        return proof;
    }

    private static Set<Span> pinnedSpans(ReviewOutput.Decision decision, EvidenceIndex index, String target,
                                         ReviewSupport.Basis basis, String statement) {
        String expected = EvidenceIndex.normalize(statement);
        return decision.support().premises().stream()
                .filter(premise -> premise.basis() == basis
                        && (expected.isEmpty() || EvidenceIndex.normalize(premise.statement()).equals(expected)))
                .flatMap(premise -> premise.citations().stream())
                .filter(citation -> index.hasPinnedTarget(List.of(citation)))
                .flatMap(citation -> citation.locations().stream())
                .filter(location -> location.revision().equals(target) && location.side() != EvidenceSource.Side.REMOVED)
                .map(location -> new Span(location.path(), location.firstLine(), location.lastLine()))
                .collect(Collectors.toSet());
    }
}
