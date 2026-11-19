package reviewagent;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** One independently confirmed verdict may cover duplicates of the same local failure. */
record SharedAdjudication(List<String> candidateIds, ReviewFinding anchor,
                          Set<EvidenceSource.Location> baselineEvidence, Set<EvidenceSource.Location> targetEvidence) {
    static SharedAdjudication eligible(List<ReviewOutput.Decision> members, ReviewScope scope, EvidenceCatalog catalog) {
        if (members.size() < 2 || members.stream().anyMatch(member -> !localFailure(member))) return null;

        var first = members.getFirst();
        var anchor = first.verdict().confirmed();
        var baseline = citedLocations(first, anchor, scope.baseCommit(), false, catalog);
        var target = citedLocations(first, anchor, scope.targetCommit(), true, catalog);
        for (var member : members.subList(1, members.size())) {
            var finding = member.verdict().confirmed();
            if (!sameLocation(anchor, finding)) return null;
            baseline.retainAll(citedLocations(member, anchor, scope.baseCommit(), false, catalog));
            target.retainAll(citedLocations(member, anchor, scope.targetCommit(), true, catalog));
        }
        if (baseline.isEmpty() || target.isEmpty()) return null;
        return new SharedAdjudication(members.stream().map(member -> member.verdict().candidate().id()).toList(),
                anchor, Set.copyOf(baseline), Set.copyOf(target));
    }

    boolean accepts(ReviewOutput.Decision decision, ReviewScope scope, EvidenceCatalog catalog) {
        return localFailure(decision) && sameLocation(anchor, decision.verdict().confirmed())
                && decision.sharedCandidates().size() == candidateIds.size()
                && new LinkedHashSet<>(decision.sharedCandidates()).equals(new LinkedHashSet<>(candidateIds))
                && overlaps(baselineEvidence, citedLocations(decision, anchor, scope.baseCommit(), false, catalog))
                && overlaps(targetEvidence, citedLocations(decision, anchor, scope.targetCommit(), true, catalog));
    }

    String instructions() {
        return """
                SHARING THIS PUBLICATION VERDICT
                Compare each preliminary finding's trigger, observable result and justification. When all
                listed candidates describe the same local failure, and one CONFIRMED verdict with the same
                proven scope applies to every candidate, add this field directly inside <verification>:
                <sharedCandidates>%s</sharedCandidates>
                If preliminary severity differs, assign one severity based on the proved impact and preconditions.
                Cite the shared baseline and target code evidence when it supports the group. Otherwise,
                decide only the representative and omit this field. In particular, keep findings
                with different affected inputs, failure behavior, or a different governing rule separate.
                """.formatted(String.join(",", candidateIds));
    }

    private static boolean localFailure(ReviewOutput.Decision decision) {
        var verdict = decision.verdict();
        if (verdict.status() != ReviewOutput.Status.CONFIRMED || verdict.validationFailed() || verdict.confirmed() == null
                || decision.relation() != ReviewOutput.Relation.INTRODUCED
                || decision.support().kind() != ReviewSupport.Kind.REACHABLE_FAILURE) return false;
        String path = verdict.confirmed().path();
        return !decision.support().premises().isEmpty() && decision.support().premises().stream().allMatch(
                premise -> premise.basis() == ReviewSupport.Basis.IMPLEMENTATION && !premise.citations().isEmpty()
                        && premise.citations().stream().allMatch(citation -> !citation.locations().isEmpty()
                            && citation.locations().stream().allMatch(location -> location.path().equals(path))));
    }

    private static boolean sameLocation(ReviewFinding first, ReviewFinding second) {
        return first.path().equals(second.path()) && first.line() == second.line() && first.endLine() == second.endLine();
    }

    private static Set<EvidenceSource.Location> citedLocations(ReviewOutput.Decision decision, ReviewFinding anchor,
                                                              String revision, boolean requireAnchor, EvidenceCatalog catalog) {
        Set<EvidenceSource.Location> locations = new LinkedHashSet<>();
        for (var citation : decision.citations()) {
            var source = catalog.source(citation.sourceId());
            if (source == null || !source.authoritative() || !source.role(EvidenceSource.Role.IMPLEMENTATION)) continue;
            citation.locations().stream()
                    .filter(location -> location.path().equals(anchor.path()) && location.revision().equals(revision))
                    .filter(location -> !requireAnchor || location.firstLine() <= anchor.line() && location.lastLine() >= anchor.endLine())
                    .forEach(locations::add);
        }
        return locations;
    }

    private static boolean overlaps(Set<EvidenceSource.Location> shared, Set<EvidenceSource.Location> cited) {
        return shared.stream().anyMatch(first -> cited.stream().anyMatch(second -> first.sourceId().equals(second.sourceId())
                && first.side() == second.side()
                && first.firstLine() <= second.lastLine() && second.firstLine() <= first.lastLine()));
    }
}
