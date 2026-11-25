package reviewagent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Element;

/** Pack independent verdicts that already have the same pinned evidence view. */
record VerificationBatch(List<Member> members, String prompt) {
    private static final int MAX_MEMBERS = 3;
    private static final Set<String> FIELDS;
    static {
        var fields = new LinkedHashSet<>(ReviewSchema.VERIFICATION);
        fields.add("requests");
        FIELDS = Set.copyOf(fields);
    }

    record Member(ReviewFinding candidate, VerificationEvidence view, ReviewTasks.Task task) { }

    record EvidenceGap(String question, String requests) { }
    record Replies(Map<String, String> accepted, Map<String, EvidenceGap> evidenceGaps) { }
    private record Individual(String response, EvidenceGap evidenceGap) { }

    record Plan(List<Member> members, List<VerificationBatch> batches) {
        int requests() {
            return members.size() + batches.size() - batches.stream().mapToInt(batch -> batch.members().size()).sum();
        }

        long inputBytes() {
            // Start from the individual requests and replace each participating set with its batch.
            long bytes = members.stream().mapToLong(member -> EvidenceText.bytes(member.task().prompt())).sum();
            for (var batch : batches) {
                bytes += EvidenceText.bytes(batch.prompt());
                bytes -= batch.members().stream().mapToLong(member -> EvidenceText.bytes(member.task().prompt())).sum();
            }
            return bytes;
        }

        boolean improvesOn(Plan other) {
            int requests = requests(), previous = other.requests();
            long bytes = inputBytes(), previousBytes = other.inputBytes();
            return requests <= previous && bytes <= previousBytes && (requests < previous || bytes < previousBytes);
        }
    }

    static final class Validation implements ReviewTasks.Validator {
        final VerificationBatch batch;
        private Replies replies = new Replies(Map.of(), Map.of());

        Validation(VerificationBatch batch) { this.batch = batch; }

        @Override public void validate(String response) throws IOException {
            replies = batch.responses(response);
            if (replies.accepted().isEmpty() && replies.evidenceGaps().isEmpty())
                throw new IOException("Batch supplied no validated candidate verdicts or scoped evidence requests");
        }

        @Override public boolean allowOutputCorrection() { return false; }

        // Read by the coordinator after the worker's future completes.
        Replies replies() { return replies; }
    }

    @FunctionalInterface
    interface Prompt {
        String build(List<ReviewFinding> candidates, VerificationEvidence view);
    }

    String id() { return "batch-" + members.getFirst().candidate().id(); }

    static Plan plan(List<Member> members, Prompt render, int maximumBytes) {
        return plan(members, render, maximumBytes, (first, other) -> true);
    }

    static Plan plan(List<Member> members, Prompt render, int maximumBytes,
                     java.util.function.BiPredicate<ReviewFinding, ReviewFinding> comparable) {
        var batches = new ArrayList<VerificationBatch>();
        var assigned = new LinkedHashSet<String>();
        for (var first : members) {
            if (first.view().fallback() || assigned.contains(first.candidate().id())) continue;
            var group = new ArrayList<>(List.of(first));
            String prompt = "";
            long individualBytes = EvidenceText.bytes(first.task().prompt());
            for (var other : members) {
                if (group.size() == MAX_MEMBERS) break;
                if (other == first || assigned.contains(other.candidate().id()) || !comparable.test(first.candidate(), other.candidate())
                        || !sharesEvidence(first, other)) continue;
                group.add(other);
                String proposed = render.build(group.stream().map(Member::candidate).toList(), first.view());
                int bytes = EvidenceText.bytes(proposed);
                int extra = EvidenceText.bytes(other.task().prompt());
                if (bytes > maximumBytes || bytes >= individualBytes + extra) group.removeLast();
                else {
                    prompt = proposed;
                    individualBytes += extra;
                }
            }
            if (group.size() > 1) {
                group.forEach(member -> assigned.add(member.candidate().id()));
                batches.add(new VerificationBatch(List.copyOf(group), prompt));
            }
        }
        return new Plan(members, batches);
    }

    private static boolean sharesEvidence(Member first, Member other) {
        return !other.view().fallback() && first.candidate().path().equals(other.candidate().path())
                && first.view().bundle().catalog().sources().equals(other.view().bundle().catalog().sources())
                && first.view().index().inventory().equals(other.view().index().inventory());
    }

    Replies responses(String reply) throws IOException {
        var block = ReviewXml.xmlEnvelope(reply, "verifications");
        if (!block.remainder().isBlank() || block.root().hasAttributes())
            throw new IOException("Return one verifications document without external evidence requests.");
        var grouped = new LinkedHashMap<String, List<Element>>();
        for (var entry : ReviewXml.children(block.root(), "verification"))
            grouped.computeIfAbsent(entry.getAttribute("id"), unused -> new ArrayList<>()).add(entry);

        var results = new LinkedHashMap<String, String>();
        var gaps = new LinkedHashMap<String, EvidenceGap>();
        for (var member : members) {
            String id = member.candidate().id();
            var entries = grouped.getOrDefault(id, List.of());
            if (entries.size() != 1 || entries.getFirst().getAttributes().getLength() != 1) continue;
            Individual individual;
            try {
                individual = individualResponse(entries.getFirst());
            } catch (IOException invalid) {
                continue;
            }
            try {
                member.task().validator().validate(individual.response());
                results.put(id, individual.response());
            } catch (IOException invalid) {
                // The question may still guide local evidence collection. Its accompanying verdict is unusable.
                if (individual.evidenceGap() != null) gaps.put(id, individual.evidenceGap());
            }
        }
        return new Replies(results, gaps);
    }

    private static Individual individualResponse(Element entry) throws IOException {
        var verdict = (Element) entry.cloneNode(true);
        verdict.removeAttribute("id");
        var parts = ReviewXml.fields(verdict, FIELDS);
        EvidenceGap gap = null;
        if (parts.containsKey("requests")) {
            var element = parts.get("requests");
            if (element.hasAttributes() || element.getElementsByTagName("*").getLength() != 0
                    || !ReviewXml.text(parts, "status").equals("INSUFFICIENT_EVIDENCE"))
                throw new IOException("Evidence requests require a clear premise in their own verdict.");
            String question = ReviewXml.text(parts, "nextCheck");
            String requests = ReviewXml.text(parts, "requests");
            var lines = requests.lines().map(String::strip).filter(line -> !line.isBlank()).toList();
            if (!lines.equals(EvidenceRequest.parse(requests).stream().map(EvidenceRequest::text).toList()))
                throw new IOException("Place one valid evidence request on each requests line.");
            gap = new EvidenceGap(question, requests);
            verdict.removeChild(element);
        }
        return new Individual(ReviewXml.markup(verdict) + (gap == null ? "" : "\n" + gap.requests() + "\n"), gap);
    }
}
