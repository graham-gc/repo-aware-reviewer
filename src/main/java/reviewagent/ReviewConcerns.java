package reviewagent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

/** Deterministic, grouped human-review items, separate from confirmed defects and execution failures. */
final class ReviewConcerns {
    enum Kind { DISPUTED, EVIDENCE_GAP, NOT_COMPLETED }
    record Item(Kind kind, List<ReviewOutput.Decision> members, List<EvidenceIndex.Citation> citations) { }

    static List<Item> collect(List<ReviewOutput.Decision> preliminary, List<ReviewOutput.Decision> adjudicated,
                              List<List<String>> groups) {
        var latest = new LinkedHashMap<String, ReviewOutput.Decision>();
        preliminary.forEach(d -> latest.put(d.verdict().candidate().id(), d));
        adjudicated.forEach(d -> latest.put(d.verdict().candidate().id(), d));
        var consumed = new LinkedHashSet<String>();
        var items = new ArrayList<Item>();
        for (List<String> group : groups) {
            // An execution failure cannot become a semantic disagreement or hide a valid confirmed peer.
            var semantic = group.stream().map(latest::get).filter(d -> d != null && !d.verdict().validationFailed()).toList();
            if (semantic.stream().anyMatch(ReviewConcerns::pending)) {
                items.add(item(semantic.stream().anyMatch(d -> d.verdict().status() == ReviewOutput.Status.DISPUTED)
                        ? Kind.DISPUTED : Kind.EVIDENCE_GAP, semantic, preliminary, adjudicated));
                semantic.forEach(d -> consumed.add(d.verdict().candidate().id()));
            }
        }
        for (var decision : latest.values()) {
            if (consumed.contains(decision.verdict().candidate().id()) || !pending(decision)) continue;
            items.add(item(decision.verdict().validationFailed() ? Kind.NOT_COMPLETED
                    : decision.verdict().status() == ReviewOutput.Status.DISPUTED ? Kind.DISPUTED : Kind.EVIDENCE_GAP,
                    List.of(decision), preliminary, adjudicated));
        }
        return List.copyOf(items);
    }

    private static Item item(Kind kind, List<ReviewOutput.Decision> members, List<ReviewOutput.Decision> preliminary,
                             List<ReviewOutput.Decision> adjudicated) {
        var ids = members.stream().map(d -> d.verdict().candidate().id()).collect(java.util.stream.Collectors.toSet());
        var citations = java.util.stream.Stream.concat(preliminary.stream(), adjudicated.stream())
                .filter(d -> ids.contains(d.verdict().candidate().id())).flatMap(d -> d.citations().stream()).distinct().toList();
        return new Item(kind, members, citations);
    }

    private static boolean pending(ReviewOutput.Decision decision) {
        return decision.verdict().status() == ReviewOutput.Status.INSUFFICIENT_EVIDENCE
                || decision.verdict().status() == ReviewOutput.Status.DISPUTED || decision.verdict().validationFailed();
    }

    static String markdown(List<Item> items, OutputLanguage language) {
        StringBuilder out = new StringBuilder();
        for (Item item : items) {
            var representative = item.members().stream().filter(d -> d.verdict().status() == ReviewOutput.Status.DISPUTED)
                    .findFirst().orElse(item.members().getFirst());
            ReviewFinding finding = finding(representative);
            String label = switch (item.kind()) {
                case DISPUTED -> language.text("Review required — conflicting decisions", "需人工确认 — 裁决存在分歧");
                case EVIDENCE_GAP -> language.text("Review required — evidence gap", "需人工确认 — 证据不足");
                case NOT_COMPLETED -> language.text("Check not completed", "检查未完成");
            };
            out.append("### [").append(label).append("] ").append(ReportText.inline(finding.title())).append("\n\n");
            bullet(out, language.text("Candidates", "候选编号"), item.members().stream().map(d -> d.verdict().candidate().id())
                    .collect(java.util.stream.Collectors.joining(", ")));
            bullet(out, language.text("Candidate location", "候选位置"), item.members().stream().map(ReviewConcerns::finding)
                    .map(f -> "`" + f.path() + ":" + f.line() + (f.endLine() == f.line() ? "" : "–" + f.endLine()) + "`")
                    .distinct().collect(java.util.stream.Collectors.joining("; ")));
            // In a dispute, use the grounded positive members, not the original, potentially overstated allegations.
            // Keep every such member's claim in case semantic grouping accidentally joined independent issues.
            for (String claim : item.members().stream().filter(d -> item.kind() == Kind.DISPUTED
                            ? d.verdict().status() == ReviewOutput.Status.DISPUTED : pending(d)).map(ReviewConcerns::finding)
                    .map(f -> ReportText.summary(f.trigger() + " → " + f.impact(), 400)).distinct().toList()) {
                bullet(out, language.text("Candidate behavior to check", "待核对的候选行为"), claim);
            }
            var contrary = item.members().stream().filter(d -> d.verdict().status() != ReviewOutput.Status.DISPUTED
                    && d.verdict().status() != ReviewOutput.Status.CONFIRMED).findFirst().orElse(representative);
            bullet(out, language.text("Decision / gap summary", "结论或缺口摘要"), ReportText.summary(contrary.verdict().reason(), 400));
            String references = item.citations().stream()
                    .map(c -> ReviewReport.citationReference(c, language)).distinct().limit(6)
                    .map(reference -> "`" + reference + "`").collect(java.util.stream.Collectors.joining("; "));
            if (!references.isEmpty()) bullet(out, language.text("Source references", "源码依据"), references);
            out.append(item.kind() == Kind.NOT_COMPLETED
                    ? language.text("This check has no validated result. Retry or resume it before drawing a conclusion. Details: [coverage.md](coverage.md).\n\n",
                        "此检查尚无有效结果，需重试或续跑后才能形成结论。详情见 [coverage.md](coverage.md)。\n\n")
                    : language.text("This item is not a confirmed defect. Confirm the intended behavior or resolve the missing factual premise. Full decisions and quotations: [coverage.md](coverage.md).\n\n",
                        "本项未计为确认缺陷；需确认变更意图，或补齐存在争议的事实依据。完整裁决与引文见 [coverage.md](coverage.md)。\n\n"));
        }
        return out.toString();
    }

    private static ReviewFinding finding(ReviewOutput.Decision decision) {
        return decision.verdict().confirmed() == null ? decision.verdict().candidate() : decision.verdict().confirmed();
    }

    private static void bullet(StringBuilder out, String label, String value) {
        out.append("- ").append(label).append(": ").append(ReportText.inline(value)).append('\n');
    }
}
