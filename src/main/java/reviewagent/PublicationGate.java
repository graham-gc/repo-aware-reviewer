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
            var present = group.stream().filter(byId::containsKey).map(byId::get).toList();
            if (present.size() < 2) continue;
            var representative = representative(present, index, target, true);
            if (representative != null) {
                String keep = representative.verdict().candidate().id();
                present.stream().map(decision -> decision.verdict().candidate().id())
                        .filter(id -> !id.equals(keep)).forEach(omitted::add);
            }
        }
        return decisions.stream().filter(decision -> !omitted.contains(decision.verdict().candidate().id())).toList();
    }

    static ReviewOutput.Decision representative(List<ReviewOutput.Decision> group, EvidenceIndex index, String target) {
        return representative(group, index, target, false);
    }

    private static ReviewOutput.Decision representative(List<ReviewOutput.Decision> group, EvidenceIndex index,
                                                          String target, boolean finalReport) {
        if (group.isEmpty()) return null;
        var first = group.getFirst().verdict().confirmed();
        if (first == null) return null;
        int overlapStart = first.line(), overlapEnd = first.endLine();
        Proof common = proof(group.getFirst(), index, target, false);
        boolean anchored = touches(first, common.trigger());
        for (var member : group.subList(1, group.size())) {
            var finding = member.verdict().confirmed();
            if (finding == null || !first.path().equals(finding.path())) return null;
            overlapStart = Math.max(overlapStart, finding.line());
            overlapEnd = Math.min(overlapEnd, finding.endLine());
            var witness = proof(member, index, target, false);
            anchored &= touches(finding, witness.trigger());
            common = new Proof(intersection(common.trigger(), witness.trigger()), intersection(common.outcome(), witness.outcome()));
        }
        if (common.trigger().isEmpty() || common.outcome().isEmpty()) return null;
        // Final verdicts can mark different statements in the same operation. Require each anchor to
        // belong to its pinned trigger and both triggers to intersect in the changed file.
        if (overlapStart > overlapEnd && (!finalReport || !anchored
                || common.trigger().stream().noneMatch(span -> span.path().equals(first.path())))) return null;
        var severity = Comparator.comparing((ReviewOutput.Decision member) -> member.verdict().confirmed().severity());
        return group.stream().min(finalReport ? severity.reversed() : severity).orElseThrow();
    }

    private static boolean touches(ReviewFinding finding, Set<Span> spans) {
        return spans.stream().anyMatch(span -> span.path().equals(finding.path())
                && span.first() <= finding.endLine() && finding.line() <= span.last());
    }

    private static Set<Span> intersection(Set<Span> first, Set<Span> second) {
        var common = new HashSet<Span>();
        for (var a : first) {
            for (var b : second) {
                int from = Math.max(a.first(), b.first());
                int to = Math.min(a.last(), b.last());
                if (a.path().equals(b.path()) && from <= to) common.add(new Span(a.path(), from, to));
            }
        }
        return common;
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
