package reviewagent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** Reuse publication tasks only for identical independently verified claims and proofs. */
final class SharedAdjudication {
    private SharedAdjudication() { }

    private record Claim(String title, String path, int line, int endLine, String trigger, String impact,
                         String evidence, String diffRelation) {
        Claim(ReviewFinding finding) {
            this(finding.title(), finding.path(), finding.line(), finding.endLine(), finding.trigger(), finding.impact(),
                    finding.evidence(), finding.diffRelation());
        }
    }

    private record Proof(Claim allegation, Claim confirmed, String reason, String baseline, String contract,
                         Set<EvidenceIndex.Citation> citations, ReviewSupport support) {
        Proof(ReviewOutput.Decision decision) {
            this(new Claim(decision.verdict().candidate()), new Claim(decision.verdict().confirmed()),
                    decision.verdict().reason(), decision.baseline(), decision.contract(), Set.copyOf(decision.citations()),
                    decision.support());
        }
    }

    static List<List<String>> equivalentGroups(List<ReviewOutput.Decision> members) {
        if (members.size() < 2 || members.stream().anyMatch(member -> !introducedFailure(member))) return List.of();

        var groups = new LinkedHashMap<Proof, List<String>>();
        for (var member : members) {
            // Severity is reassessed by the publication judge from the shared proof.
            groups.computeIfAbsent(new Proof(member), unused -> new ArrayList<>()).add(member.verdict().candidate().id());
        }
        return groups.values().stream().filter(group -> group.size() > 1).map(List::copyOf).toList();
    }

    private static boolean introducedFailure(ReviewOutput.Decision decision) {
        var verdict = decision.verdict();
        return verdict.status() == ReviewOutput.Status.CONFIRMED && !verdict.validationFailed()
                && verdict.confirmed() != null && decision.relation() == ReviewOutput.Relation.INTRODUCED
                && decision.support().kind() == ReviewSupport.Kind.REACHABLE_FAILURE
                && !decision.support().premises().isEmpty() && !decision.citations().isEmpty();
    }
}
