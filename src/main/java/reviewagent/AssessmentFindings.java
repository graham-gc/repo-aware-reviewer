package reviewagent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Routes audited unmet behaviors through the same verification and publication path as discovery findings. */
final class AssessmentFindings {
    record Origin(ReviewOutput.AssessmentItem item, ReviewFinding candidate) { }
    record Plan(List<Origin> origins, List<ReviewOutput.AssessmentItem> unmapped) {
        List<ReviewFinding> candidates() { return origins.stream().map(Origin::candidate).toList(); }
        ReviewOutput.AssessmentItem item(String candidateId) {
            return origins.stream().filter(origin -> origin.candidate().id().equals(candidateId)).findFirst().orElseThrow().item();
        }
        static Plan empty() { return new Plan(List.of(), List.of()); }
    }

    static Plan plan(List<ReviewOutput.AssessmentItem> items, EvidenceBundle evidence, EvidenceIndex index, OutputLanguage language) {
        var origins = new ArrayList<Origin>();
        var unmapped = new ArrayList<ReviewOutput.AssessmentItem>();
        for (var item : items) {
            if (item.status() != ReviewOutput.AssessmentStatus.NOT_SATISFIED) continue;
            var location = index.targetLocations(item.citations()).stream()
                    .filter(span -> evidence.changedFiles().contains(span.path())).findFirst();
            if (location.isEmpty()) { unmapped.add(item); continue; }
            var span = location.get();
            var candidate = new ReviewFinding("REQ-" + item.id(), item.behavior(), ReviewFinding.Severity.MEDIUM,
                    span.path(), span.firstLine(), Math.min(span.lastLine(), span.firstLine() + 2), item.behavior(), item.gap(),
                    item.reasoning(), language.text("Unmet requirement proposed by independent assessment ", "独立验收提出的未满足需求 ") + item.id());
            origins.add(new Origin(item, candidate));
        }
        return new Plan(List.copyOf(origins), List.copyOf(unmapped));
    }

    static List<ReviewOutput.AssessmentItem> reconcile(List<ReviewOutput.AssessmentItem> items, Plan plan,
                                                     List<ReviewOutput.Decision> decisions, OutputLanguage language) {
        Map<String, ReviewOutput.Decision> byId = byId(decisions);
        var origins = new LinkedHashMap<String, Origin>();
        plan.origins().forEach(origin -> origins.put(origin.item().id(), origin));
        var unmapped = plan.unmapped().stream().map(ReviewOutput.AssessmentItem::id).collect(java.util.stream.Collectors.toSet());
        return items.stream().map(item -> {
            Origin origin = origins.get(item.id());
            if (origin == null && !unmapped.contains(item.id())) return item;
            ReviewOutput.Decision decision = origin == null ? null : byId.get(origin.candidate().id());
            boolean confirmed = decision != null && decision.verdict().status() == ReviewOutput.Status.CONFIRMED;
            String explanation = origin == null
                    ? language.text("No quoted changed-file implementation location supports a review candidate; manual review is required.",
                        "所引证据无法定位到可复核的变更实现，需人工确认其适用范围。")
                    : decision == null ? language.text("Candidate verification is pending: ", "候选尚未完成复核：") + origin.candidate().id()
                    : language.text("Candidate ", "候选 ") + origin.candidate().id() + ": " + label(decision, language) + ". " + decision.verdict().reason();
            String reasoning = confirmed ? explanation : language.text("The initial unmet assessment was not confirmed. ", "初审的未满足判断尚未获得确认。 ") + explanation;
            return new ReviewOutput.AssessmentItem(item.id(), item.requirement(), item.requirementText(), item.behavior(),
                    confirmed ? ReviewOutput.AssessmentStatus.NOT_SATISFIED : ReviewOutput.AssessmentStatus.INSUFFICIENT_EVIDENCE,
                    reasoning, confirmed ? item.boundaries() : language.text("Initial boundary notes, pending confirmation: ", "初审边界记录，尚待确认：") + item.boundaries(),
                    confirmed ? item.gap() : explanation, confirmed ? decision.citations() : item.citations(), item.policyBasis(),
                    confirmed ? item.policyReason() : language.text("Initial policy rationale, pending confirmation: ", "初审规则依据，尚待确认：") + item.policyReason());
        }).toList();
    }

    static String markdown(Plan plan, List<ReviewOutput.Decision> decisions, OutputLanguage language) {
        var byId = byId(decisions);
        StringBuilder out = new StringBuilder(language.text("# Requirement finding reconciliation\n\n", "# 需求问题复核\n\n"));
        out.append(language.text("Audited unmet items are candidate allegations until independent verification and publication adjudication complete.\n\n",
                "验收未满足项在独立复核及发布裁决完成前，均为待核实的候选问题。\n\n"));
        for (var origin : plan.origins()) {
            var decision = byId.get(origin.candidate().id());
            out.append("- ").append(origin.item().id()).append(" → ").append(origin.candidate().id()).append(": ")
                    .append(decision == null ? language.text("PENDING", "待复核") : label(decision, language))
                    .append(" — ").append(EvidenceText.oneLine(origin.item().behavior())).append('\n');
            if (decision != null) out.append("  - ").append(EvidenceText.oneLine(decision.verdict().reason())).append('\n');
        }
        for (var item : plan.unmapped()) out.append("- ").append(item.id()).append(": ")
                .append(language.text("UNMAPPED — no quoted changed-file implementation location; retained as insufficient evidence.",
                        "无法定位到所引变更实现，保留为证据不足，需人工核对。")).append('\n');
        if (plan.origins().isEmpty() && plan.unmapped().isEmpty()) out.append(language.text("No unmet assessment items.\n", "没有需要转入复核的未满足验收项。\n"));
        return out.toString();
    }

    private static Map<String, ReviewOutput.Decision> byId(List<ReviewOutput.Decision> decisions) {
        var byId = new LinkedHashMap<String, ReviewOutput.Decision>();
        decisions.forEach(decision -> byId.put(decision.verdict().candidate().id(), decision));
        return byId;
    }

    private static String label(ReviewOutput.Decision decision, OutputLanguage language) {
        if (decision.verdict().validationFailed()) return language.text("INVALID_RESPONSE", "回复校验失败");
        return language.text(decision.verdict().status().name(), switch (decision.verdict().status()) {
            case CONFIRMED -> "已确认"; case REJECTED -> "已驳回"; case INSUFFICIENT_EVIDENCE -> "证据不足";
        });
    }
}
