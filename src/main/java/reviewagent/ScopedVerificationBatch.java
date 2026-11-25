package reviewagent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.w3c.dom.Element;

/** Share evidence in one request while validating each verdict against its own candidate. */
record ScopedVerificationBatch(List<ReviewFinding> candidates, VerificationEvidence view, String prompt) {
    private static final int MAX_CANDIDATES = 3;
    private static final int MAX_PROMPT_BYTES = 96_000;
    private record Span(String path, int first, int last) { }

    String id() { return "batch-" + candidates.getFirst().id(); }

    static List<ScopedVerificationBatch> plan(List<String> taskIds, Map<String, ReviewOutput.Decision> eligible,
                                              Map<String, VerificationEvidence> views, OutputLanguage language) {
        var members = taskIds.stream().filter(eligible::containsKey)
                .map(id -> eligible.get(id).verdict().confirmed()).toList();
        var batches = new ArrayList<ScopedVerificationBatch>();
        var assigned = new LinkedHashSet<String>();
        for (int i = 0; i < members.size(); i++) {
            var first = members.get(i);
            var view = views.get(first.id());
            if (assigned.contains(first.id()) || view.fallback()) continue;
            String target = view.bundle().scope().targetCommit();
            var common = outcomeSpans(eligible.get(first.id()), target);
            if (common.isEmpty()) continue;
            var group = new ArrayList<>(List.of(first));
            for (int j = i + 1; j < members.size() && group.size() < MAX_CANDIDATES; j++) {
                var next = members.get(j);
                var other = views.get(next.id());
                if (assigned.contains(next.id()) || !first.path().equals(next.path()) || other.fallback()
                        || !view.bundle().catalog().sources().equals(other.bundle().catalog().sources())) continue;
                var shared = outcomeSpans(eligible.get(next.id()), target);
                shared.retainAll(common);
                if (shared.isEmpty()) continue;
                common = shared;
                group.add(next);
            }
            if (group.size() < 2) continue;
            String prompt = prompt(group, view, language);
            if (EvidenceText.bytes(prompt) > MAX_PROMPT_BYTES) continue;
            group.forEach(candidate -> assigned.add(candidate.id()));
            batches.add(new ScopedVerificationBatch(List.copyOf(group), view, prompt));
        }
        return batches;
    }

    private static Set<Span> outcomeSpans(ReviewOutput.Decision decision, String target) {
        String anchor = decision.verdict().confirmed().path();
        return decision.support().premises().stream().filter(premise -> premise.basis() == ReviewSupport.Basis.OUTCOME)
                .flatMap(premise -> premise.citations().stream()).flatMap(citation -> citation.locations().stream())
                .filter(location -> location.revision().equals(target) && location.side() != EvidenceSource.Side.REMOVED
                        && !location.path().equals(anchor))
                .map(location -> new Span(location.path(), location.firstLine(), location.lastLine()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String prompt(List<ReviewFinding> candidates, VerificationEvidence view, OutputLanguage language) {
        String ids = candidates.stream().map(ReviewFinding::id).collect(Collectors.joining(", "));
        return """
                You are the independent verifier for these distinct code-review candidates.

                REQUIRED RESPONSE
                Return exactly one <verifications> document. Include one direct <verification id="..."> child
                for each supplied candidate ID (%s). Use an outer id on every verdict, including rejections.
                If a claim needs more evidence, use INSUFFICIENT_EVIDENCE and name the missing premise in reason.
                This batch accepts only verdict fields. The CLI can move an unresolved member to individual verification.

                For CONFIRMED, use this structure with the actual ID, target location and decisive premise citations:
                <verifications><verification id="%s">
                  <status>CONFIRMED</status><reason>What proves this particular trigger and outcome</reason>
                  <relation>INTRODUCED</relation><baseline>Base behavior</baseline><contract>Applicable contract or invariant</contract>
                  <support>REACHABLE_FAILURE</support><premises>
                    <premise><statement>Necessary reachable behavior</statement><basis>IMPLEMENTATION</basis>
                      <citations><citation><source>SRC-2</source><lines>1:2</lines></citation></citations></premise>
                    <premise><statement>Same text as finding impact</statement><basis>OUTCOME</basis>
                      <citations><citation><source>SRC-2</source><lines>3:4</lines></citation></citations></premise>
                  </premises><citations><citation><source>SRC-1</source><lines>1:2</lines></citation></citations>
                  <finding id="%s"><title>Verified issue</title><severity>MEDIUM</severity><path>changed-file</path>
                    <line>1</line><endLine>1</endLine><trigger>Reachable trigger</trigger><impact>Same text as finding impact</impact>
                    <evidence>Decisive code</evidence><diffRelation>Why this change introduces the failure</diffRelation></finding>
                </verification></verifications>
                Use INTRODUCED or EXPOSED_BY_CHANGE for a proved regression. Cite base source or the relevant diff side
                for attribution and pinned target implementation for behavior. A new-file diff header establishes absence at base.
                Use REJECTED with decisive target citations for a disproved claim. Omit finding for REJECTED
                and INSUFFICIENT_EVIDENCE. Write descriptive text in %s.

                VERIFICATION TASK
                Use the pinned evidence and check each candidate on its own trigger, guards, caller or producer,
                outcome and change attribution. Search for counterevidence per candidate. Shared code does not
                imply shared outcomes. Limit each decision to what its own citations prove.

                """.formatted(ids, candidates.getFirst().id(), candidates.getFirst().id(), language.code())
                + PromptFactory.SCOPED_OUTCOME_RULES + "\nCANDIDATES TO VERIFY\n"
                + candidates.stream().map(ReviewOutput::findingXml).collect(Collectors.joining("\n")) + "\n\n"
                + EvidenceIndex.LINE_CITATION_GUIDANCE + AdaptiveReview.promptEvidence(view.bundle(), view.index());
    }

    Map<String, ReviewOutput.Decision> decisions(String reply, OutputLanguage language) throws IOException {
        var block = ReviewXml.xmlEnvelope(reply, "verifications");
        if (!block.remainder().isBlank() || block.root().hasAttributes())
            throw new IOException("Return only the verifications document");
        var byId = new LinkedHashMap<String, List<Element>>();
        for (Element element : ReviewXml.children(block.root(), "verification"))
            byId.computeIfAbsent(element.getAttribute("id"), unused -> new ArrayList<>()).add(element);
        var results = new LinkedHashMap<String, ReviewOutput.Decision>();
        for (var candidate : candidates) {
            var entries = byId.getOrDefault(candidate.id(), List.of());
            if (entries.size() != 1 || entries.getFirst().getAttributes().getLength() != 1) continue;
            try {
                var entry = entries.getFirst();
                ReviewSchema.validate(entry);
                var parsed = ReviewOutput.decision(ReviewXml.fields(entry, ReviewSchema.VERIFICATION),
                        candidate, view.bundle().changedFiles());
                var grounded = ReviewGrounding.verification(parsed, view.index(), false);
                var checked = ReviewGrounding.scopedPublication(grounded, language);
                if (checked.verdict().status() == ReviewOutput.Status.CONFIRMED
                        || checked.verdict().status() == ReviewOutput.Status.REJECTED
                        && view.index().hasPinnedTarget(checked.citations()))
                    results.put(candidate.id(), grounded);
            } catch (IOException invalid) {
                // The caller sends this candidate through the established individual path.
            }
        }
        return results;
    }
}
