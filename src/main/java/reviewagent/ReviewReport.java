package reviewagent;

import static reviewagent.ReviewXml.*;
import org.w3c.dom.Element;
import reviewagent.ReviewOutput.AssessmentItem;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;

/** Deterministic report presentation over validated review results. */
final class ReviewReport {
    static String assessmentMarkdown(List<AssessmentItem> items, OutputLanguage language) {
        if (items.isEmpty()) return language.text("No requirements document was supplied; acceptance was not assessed.",
                "未提供需求文档，未进行需求验收。") + "\n";
        StringBuilder out = new StringBuilder();
        for (AssessmentItem item : items) {
            String status = language.text(item.status().name(), switch (item.status()) {
                case SATISFIED -> "满足";
                case NOT_SATISFIED -> "未满足";
                case INSUFFICIENT_EVIDENCE -> "证据不足";
                case NOT_COMPLETED -> "未完成";
            });
            out.append("### ").append(item.id()).append(" — ").append(ReportText.inline(item.behavior())).append("\n\n")
                    .append("- ").append(language.text("Assessment", "结论")).append(": ").append(status).append('\n')
                    .append("- ").append(language.text("Requirement", "需求")).append(": ").append(ReportText.inline(item.requirementText())).append('\n')
                    .append("- ").append(language.text("Reasoning", "依据")).append(": ").append(ReportText.inline(item.reasoning())).append('\n')
                    .append("- ").append(language.text("Boundary checks", "边界核查")).append(": ").append(emptyValue(item.boundaries(), language)).append('\n')
                    .append("- ").append(language.text("Policy basis", "规则依据")).append(": ")
                    .append(language.text(item.policyBasis().name(), item.policyBasis() == ReviewOutput.PolicyBasis.EXPLICIT ? "原文明示" : "尚未规定"))
                    .append(" — ").append(ReportText.inline(item.policyReason())).append('\n')
                    .append("- ").append(language.text("Gap or limitation", "缺口或限制")).append(": ").append(emptyValue(item.gap(), language)).append('\n');
            for (EvidenceIndex.Citation citation : item.citations()) {
                out.append("- ").append(language.text("Source", "证据来源")).append(": ")
                        .append('`').append(citationReference(citation, language)).append("`\n");
            }
            out.append('\n');
        }
        return out.toString();
    }

    static String assessmentSummary(List<AssessmentItem> items, OutputLanguage language) {
        if (items.isEmpty()) return assessmentMarkdown(items, language);
        StringBuilder out = new StringBuilder();
        out.append(language.text("Static assessment: ", "静态评估："));
        for (var status : ReviewOutput.AssessmentStatus.values()) {
            long count = items.stream().filter(item -> item.status() == status).count();
            out.append(status(status, language)).append(' ').append(count).append("; ");
        }
        out.append("\n\n").append(language.text(
                "Detailed reasoning, boundaries and verified source spans: [requirement-assessment.md](requirement-assessment.md).",
                "完整依据、边界核查及核准的源码行号见 [requirement-assessment.md](requirement-assessment.md)。")).append("\n\n");
        var ordered = items.stream().sorted(Comparator.comparingInt(item -> switch (item.status()) {
            case NOT_COMPLETED -> 0; case NOT_SATISFIED -> 1; case INSUFFICIENT_EVIDENCE -> 2; case SATISFIED -> 3;
        })).toList();
        for (AssessmentItem item : ordered) {
            out.append("- **").append(item.id()).append(" · ").append(status(item.status(), language)).append("** — ")
                    .append(ReportText.inline(item.behavior()));
            if (item.status() != ReviewOutput.AssessmentStatus.SATISFIED) out.append(" — ").append(emptyValue(item.gap(), language));
            out.append('\n');
        }
        return out.toString();
    }

    private static String status(ReviewOutput.AssessmentStatus status, OutputLanguage language) {
        return language.text(status.name(), switch (status) {
            case SATISFIED -> "满足"; case NOT_SATISFIED -> "未满足"; case INSUFFICIENT_EVIDENCE -> "证据不足";
            case NOT_COMPLETED -> "未完成";
        });
    }

    private static String emptyValue(String text, OutputLanguage language) {
        return switch (text.strip().toLowerCase(java.util.Locale.ROOT)) {
            case "none", "none.", "n/a", "not applicable", "无", "无。", "不适用" -> language.text("None", "无");
            default -> ReportText.inline(text);
        };
    }

    static String citationReference(EvidenceIndex.Citation citation, OutputLanguage language) {
        if (!citation.locations().isEmpty()) return citation.locations().stream().map(location ->
                ReportText.inline(location.path()) + ":" + location.firstLine()
                        + (location.lastLine() == location.firstLine() ? "" : "–" + location.lastLine())
                        + " @ " + location.revision()).distinct().collect(java.util.stream.Collectors.joining("; "));
        return ReportText.inline(citationReference(citation.source(), language));
    }

    private static String citationReference(String source, OutputLanguage language) {
        if (source.startsWith("KB-")) return source.split(" — ", 2)[0];
        return source.replace("External requirement — ", language.text("Requirements: ", "需求文档："))
                .replace("Human-maintained knowledge base — ", language.text("Knowledge base: ", "主知识库："));
    }

    static String finalReport(String response, List<ReviewFinding> confirmed, OutputLanguage language,
                              String assessment) throws IOException {
        return finalReport(response, confirmed, language, assessment, List.of());
    }

    static String finalReport(String response, List<ReviewFinding> confirmed, OutputLanguage language,
                              String assessment, List<ReviewConcerns.Item> concerns) throws IOException {
        String report = render(response, confirmed, language, concerns);
        var headings = headings(language);
        int start = report.indexOf("\n" + headings.get(3) + "\n") + 1 + headings.get(3).length();
        int end = report.indexOf("\n" + headings.get(4) + "\n", start) + 1;
        return report.substring(0, start) + "\n\n" + assessment.strip() + "\n\n" + report.substring(end);
    }

    static String finalReport(String response, List<ReviewFinding> confirmed, OutputLanguage language) throws IOException {
        return render(response, confirmed, language, List.of());
    }

    private static String render(String response, List<ReviewFinding> confirmed, OutputLanguage language,
                                 List<ReviewConcerns.Item> concerns) throws IOException {
        XmlBlock block = xml(response, "finding-groups");
        var byId = new LinkedHashMap<String, ReviewFinding>();
        confirmed.forEach(finding -> byId.put(finding.id(), finding));
        var seen = new HashSet<String>();
        var representatives = new ArrayList<ReviewFinding>();
        for (Element group : children(block.root(), "group")) {
            var members = new ArrayList<ReviewFinding>();
            for (Element id : children(group, "id")) {
                String value = id.getTextContent().strip();
                if (!byId.containsKey(value) || !seen.add(value)) {
                    throw new IOException("Final grouping contains an unknown or repeated finding ID");
                }
                members.add(byId.get(value));
            }
            if (members.isEmpty()) {
                throw new IOException("Final grouping contains an empty group");
            }
            representatives.add(members.stream().min(Comparator.comparing(ReviewFinding::severity)).orElseThrow());
        }
        if (!seen.equals(byId.keySet())) {
            throw new IOException("Final grouping omitted confirmed findings");
        }
        String report = block.remainder().strip();
        List<String> headings = headings(language);
        if (!report.lines().filter(line -> line.startsWith("# ")).toList().equals(headings)) {
            throw new IOException("Final report has missing, repeated, or unexpected top-level headings");
        }
        String findingsHeading = headings.get(2);
        int start = report.indexOf("\n" + findingsHeading + "\n") + 1 + findingsHeading.length();
        int end = report.indexOf("\n" + headings.get(3) + "\n", start) + 1;
        return report.substring(0, start) + "\n\n" + findingsMarkdown(representatives, concerns, language) + report.substring(end) + "\n";
    }

    private static String findingsMarkdown(List<ReviewFinding> confirmed, List<ReviewConcerns.Item> concerns, OutputLanguage language) {
        StringBuilder findings = new StringBuilder();
        if (!concerns.isEmpty()) {
            long unfinished = concerns.stream().filter(item -> item.kind() == ReviewConcerns.Kind.NOT_COMPLETED).count();
            findings.append(language.text("Confirmed defects: ", "已确认缺陷：")).append(confirmed.size())
                    .append(language.text("; review-required items: ", "；需人工确认事项：")).append(concerns.size() - unfinished)
                    .append(language.text("; unfinished checks: ", "；未完成检查：")).append(unfinished).append(".\n\n");
        } else if (confirmed.isEmpty()) {
            findings.append(language.text("No verified findings.", "未发现经复核确认的问题。")).append("\n\n");
        }
        for (ReviewFinding finding : confirmed.stream().sorted(Comparator.comparing(ReviewFinding::severity)).toList()) {
            findings.append(finding.markdown(language));
        }
        findings.append(ReviewConcerns.markdown(concerns, language));
        return findings.toString();
    }

    static List<String> headings(OutputLanguage language) {
        return language == OutputLanguage.ZH_CN
                ? List.of("# 审查范围", "# 功能与业务变更", "# 问题发现", "# 需求验收", "# 知识库反馈", "# 验证限制")
                : List.of("# Review scope", "# Functional and business change", "# Findings", "# Requirement assessment",
                        "# Knowledge-base feedback", "# Verification limits");
    }

    static String adaptiveReport(EvidenceBundle evidence, String summary, List<ReviewFinding> confirmed,
                                 List<ReviewConcerns.Item> concerns, OutputLanguage language) {
        var scope = evidence.scope();
        List<String> content = List.of(
                language.text("Base", "基线") + " — " + code(scope.baseCommit()) + "\n\n"
                        + language.text("Target", "目标") + " — " + code(scope.targetCommit()) + "\n\n"
                        + language.text("Changed file", "变更文件") + " — " + code(EvidenceText.oneLine(evidence.changedFiles().getFirst())),
                ReportText.inline(summary),
                findingsMarkdown(confirmed, concerns, language),
                assessmentMarkdown(List.of(), language),
                language.text("No knowledge base was supplied.", "未提供知识库。"),
                language.text("Static review with the complete local diff and source at both revisions. No build or tests were run. "
                                + "See [coverage.md](coverage.md) for evidence and the selected review route.",
                        "静态审查使用了完整的局部差异和基线、目标源码。未执行构建或测试。"
                                + "审查路线及证据详见 [coverage.md](coverage.md)。"));
        return sections(content, language);
    }

    private static String code(String value) {
        return "`" + value.replace("`", "\\`") + "`";
    }

    private static String sections(List<String> content, OutputLanguage language) {
        StringBuilder report = new StringBuilder();
        List<String> titles = headings(language);
        for (int i = 0; i < titles.size(); i++) report.append(titles.get(i)).append("\n\n").append(content.get(i).strip()).append("\n\n");
        return report.toString();
    }

    static String incompleteReport(ReviewScope scope, OutputLanguage language, List<ReviewFinding> published,
                                   List<ReviewConcerns.Item> concerns, String assessment, List<String> failures) {
        String findings = published.isEmpty() && concerns.isEmpty()
                ? language.text("No publishable conclusions are available from the completed stages. This does not mean the change has no defects.",
                    "已完成阶段尚无可发布的结论，这不代表变更没有问题。")
                : language.text("The following results are retained from completed stages; final report grouping may be unfinished.\n\n",
                    "以下结果保留自已完成阶段；最终报告去重可能尚未完成。\n\n")
                    + findingsMarkdown(published, concerns, language);
        List<String> content = List.of(
                language.text("Base", "基线") + ": `" + scope.baseCommit() + "`\n\n"
                        + language.text("Target", "目标") + ": `" + scope.targetCommit() + "`",
                language.text("Review execution did not finish all required stages. No generated narrative is substituted for missing analysis.",
                    "本次执行未完成全部必要审查环节，缺失分析没有对应结论。"),
                findings, assessment,
                language.text("Not completed in this report.", "本报告未完成此项反馈。"),
                language.text("Static review only; no target build or tests were run. Unfinished checks are execution failures, not evidence-based business verdicts.\n\n",
                    "仅做静态审查，未执行目标仓库构建或测试。未完成检查表示执行未完成，不是业务证据不足的判断。\n\n")
                        + failures.stream().map(failure -> "- " + ReportText.inline(failure)).collect(java.util.stream.Collectors.joining("\n")));
        return sections(content, language);
    }

    static String emptyDiffReport(ReviewScope scope, OutputLanguage language, boolean hasRequirement) {
        List<String> content = List.of(
                language.text("Base", "基线") + ": `" + scope.baseCommit() + "`\n\n"
                        + language.text("Target", "目标") + ": `" + scope.targetCommit() + "`",
                language.text("The baseline and target contain no file differences.", "基线与目标之间没有文件差异。"),
                language.text("Skipped: there are no changes to review.", "已跳过：没有需要审查的变更。"),
                emptyDiffAssessment(language, hasRequirement),
                language.text("Skipped because there are no code changes.", "没有代码变更，未评估知识库反馈。"),
                language.text("This execution made no model calls and ran no build, tests, or static analyzer. "
                                + "Choose an earlier baseline to review a change, or use --commit <ref> to compare a commit with its first parent.",
                        "本次执行未调用模型，未执行构建、测试或静态检查。请指定更早的基线来审查变更，"
                                + "或使用 --commit <ref> 比较某次提交与其第一父提交。"));
        return sections(content, language);
    }

    static String emptyDiffAssessment(OutputLanguage language, boolean hasRequirement) {
        return hasRequirement
                ? language.text("Not assessed: an empty diff does not establish that the target satisfies the requirements.",
                    "未进行需求验收：空 diff 不能证明目标版本已满足需求。")
                : language.text("No requirements document was supplied; acceptance was not assessed.", "未提供需求文档，未进行需求验收。");
    }

}
