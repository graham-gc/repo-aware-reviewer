package reviewagent;

import java.util.List;
import java.util.Set;

/** Prior model opinions and navigation outcomes are context only, never new proof or business rules. */
record VerificationContinuation(ReviewOutput.Decision previous, String requests, String outcomes, Set<String> supplied,
                                String question) {
    VerificationContinuation { supplied = Set.copyOf(supplied); }

    VerificationContinuation(ReviewOutput.Decision previous, String requests, String outcomes, Set<String> supplied) {
        this(previous, requests, outcomes, supplied, "");
    }

    String markdown(VerificationEvidence view) {
        String decision = previous == null ? "The previous batch supplied only a usable evidence question. Remaining check: "
                + ReportText.summary(question, 500)
                : previous.verdict().validationFailed() ? "The previous check did not produce a validated decision."
                : "Previous model opinion: " + previous.verdict().status() + ".\nReason: "
                    + ReportText.summary(previous.verdict().reason(), 600) + "\nOpen contract/context: "
                    + ReportText.summary(previous.contract(), 300) + "\nRemaining check: "
                    + ReportText.summary(previous.verdict().nextCheck(), 500);
        List<EvidenceSource> added = view.bundle().catalog().sources().stream().filter(source -> !supplied.contains(source.id())).toList();
        String inventory = added.stream().map(source -> "- " + view.bundle().catalog().describe(source) + "\n")
                .collect(java.util.stream.Collectors.joining());
        return "\n\nVERIFICATION FOLLOW-UP CONTEXT\n"
                + "Continue the same candidate against the supplied pinned sources. The previous opinion is not evidence or an approved rule. "
                + "Check whether new material resolves the specific missing premise; revise any earlier judgment when warranted. "
                + "Navigation paths and request outcomes do not prove file contents. Cite only sources actually included in this task. "
                + "Do not repeat an already processed request; request a different useful source or retain the unresolved gap.\n"
                + EvidenceText.block("Previous check (model opinion, not source evidence)", "text", decision)
                + EvidenceText.block("Previous requests by this candidate", "text", bounded(requests, 1_600))
                + EvidenceText.block("Request processing for that round (shared batch)", "text", bounded(outcomes, 2_400))
                + EvidenceText.block("Newly supplied source references", "text", bounded(inventory, 2_000));
    }

    private static String bounded(String text, int bytes) {
        if (text.isBlank()) return "None recorded.";
        return EvidenceText.bytes(text) <= bytes ? text : EvidenceText.linePrefix(text, bytes) + "\n[Context truncated to byte budget.]";
    }
}
