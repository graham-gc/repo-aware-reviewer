package reviewagent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/** Evidence-driven verification, cross-verdict adjudication and independent acceptance audit. */
final class ReviewQuality {
    record AssessmentResult(String details, String summary) { }
    private final CliOptions options;
    private final EvidenceBundle evidence;
    private final EvidenceSession session;
    private final PromptFactory prompts;
    private final AgentRunner agent;
    private final Path directory;
    private final CommandProgress progress;
    private final int stages;

    ReviewQuality(CliOptions options, EvidenceBundle evidence, EvidenceSession session, PromptFactory prompts,
                  AgentRunner agent, Path directory, CommandProgress progress, int stages) {
        this.options = options;
        this.evidence = evidence;
        this.session = session;
        this.prompts = prompts;
        this.agent = agent;
        this.directory = directory;
        this.progress = progress;
        this.stages = stages;
    }

    List<ReviewOutput.Decision> verify(List<ReviewFinding> findings) throws IOException, InterruptedException {
        return verifyRounds(findings, (candidate, supplemental) -> prompts.verifyFinding(evidence, candidate, supplemental, session.index()),
                "verification", 5, "Verifying candidate issues");
    }

    List<ReviewOutput.Decision> adjudicate(List<ReviewOutput.Decision> preliminary, String assessment) throws IOException, InterruptedException {
        if (preliminary.stream().noneMatch(d -> d.verdict().status() == ReviewOutput.Status.CONFIRMED)) return List.of();
        List<ReviewFinding> candidates = preliminary.stream().map(d -> d.verdict().candidate()).toList();
        var groupsReply = tasks(List.of(new ReviewTasks.Task("groups", prompts.groupCandidates(preliminary),
                reply -> ReviewOutput.candidateGroups(reply, candidates))), "adjudication", 7, "Reconciling candidate verdicts");
        var groups = ReviewOutput.candidateGroups(groupsReply.get("groups"), candidates);
        Map<String, List<ReviewOutput.Decision>> related = new LinkedHashMap<>();
        for (List<String> ids : groups) {
            List<ReviewOutput.Decision> members = preliminary.stream().filter(d -> ids.contains(d.verdict().candidate().id())).toList();
            for (String id : ids) related.put(id, members);
        }
        // Verify each positive member, so an incorrect semantic grouping cannot erase independent defects.
        List<ReviewFinding> positives = preliminary.stream().filter(d -> d.verdict().status() == ReviewOutput.Status.CONFIRMED)
                .map(d -> d.verdict().confirmed()).toList();
        List<ReviewOutput.Decision> adjudicated = verifyRounds(positives, (candidate, supplemental) -> prompts.adjudicate(evidence, candidate,
                related.get(candidate.id()), supplemental, session.index()) + "\nINDEPENDENTLY AUDITED REQUIREMENT ASSESSMENT\n"
                + "Recheck any contradiction between the proposed finding and this assessment against the source.\n" + assessment,
                "adjudication", 7, "Adjudicating findings before publication");
        return resolveConflicts(adjudicated, groups, OutputLanguage.parse(options.language()));
    }

    static List<ReviewOutput.Decision> resolveConflicts(List<ReviewOutput.Decision> decisions, List<List<String>> groups,
                                                       OutputLanguage language) {
        var disputed = new java.util.HashSet<String>();
        for (List<String> group : groups) {
            var statuses = decisions.stream().filter(d -> group.contains(d.verdict().candidate().id()))
                    .map(d -> d.verdict().status()).collect(java.util.stream.Collectors.toSet());
            if (statuses.contains(ReviewOutput.Status.CONFIRMED) && statuses.size() > 1) disputed.addAll(group);
        }
        return decisions.stream().map(decision -> {
            var verdict = decision.verdict();
            if (!disputed.contains(verdict.candidate().id()) || verdict.status() != ReviewOutput.Status.CONFIRMED) return decision;
            String reason = language.text("Publication adjudicators disagree on the same issue; withheld pending clarification. ",
                    "同一问题的发布前裁决仍有相反结论，保留待确认。 ") + verdict.reason();
            return new ReviewOutput.Decision(new ReviewOutput.Verification(verdict.candidate(),
                    ReviewOutput.Status.INSUFFICIENT_EVIDENCE, reason, null), ReviewOutput.Relation.UNRESOLVED,
                    decision.baseline(), decision.contract(), decision.citations(), decision.support());
        }).toList();
    }

    private List<ReviewOutput.Decision> verifyRounds(List<ReviewFinding> findings,
            BiFunction<ReviewFinding, String, String> prompt, String folder, int stage, String description)
            throws IOException, InterruptedException {
        var results = new LinkedHashMap<String, ReviewOutput.Decision>();
        List<ReviewFinding> pending = findings;
        int round = 1;
        while (!pending.isEmpty()) {
            EvidenceIndex index = session.index();
            String supplemental = session.markdown();
            var requests = pending.stream().map(candidate -> new ReviewTasks.Task(candidate.id(), prompt.apply(candidate, supplemental),
                    reply -> ReviewGrounding.verification(reply, candidate, evidence, index))).toList();
            String path = round == 1 ? folder : folder + "/round-" + round;
            var replies = tasks(requests, path, stage, description + " (round " + round + ")");
            for (ReviewFinding candidate : pending) {
                results.put(candidate.id(), ReviewGrounding.verification(replies.get(candidate.id()), candidate, evidence, index));
            }
            StringBuilder evidenceRequests = new StringBuilder();
            for (String reply : replies.values()) evidenceRequests.append(ReviewOutput.requestText(reply, "verification")).append('\n');
            boolean material = session.load(evidenceRequests.toString(), folder + " round " + round);
            saveEvidence();
            if (!material) break;
            pending = findings.stream().filter(candidate -> results.get(candidate.id()).verdict().status()
                    == ReviewOutput.Status.INSUFFICIENT_EVIDENCE).toList();
            round++;
        }
        return findings.stream().map(candidate -> results.get(candidate.id())).toList();
    }

    AssessmentResult assess(InputDocument requirement, String decisions) throws IOException, InterruptedException {
        OutputLanguage language = OutputLanguage.parse(options.language());
        if (requirement == null) {
            String text = ReviewReport.assessmentMarkdown(List.of(), language);
            return new AssessmentResult(text, text);
        }
        EvidenceIndex index = session.index();
        String draftPrompt = prompts.assessRequirements(evidence, session.markdown(), decisions, "None", false, index);
        var draft = tasks(List.of(new ReviewTasks.Task("draft", draftPrompt,
                reply -> ReviewGrounding.assessment(reply, requirement, index, List.of()))), "assessment", 6, "Assessing requirement behaviors");
        String previous = draft.get("draft");
        List<ReviewOutput.AssessmentItem> items = ReviewGrounding.assessment(previous, requirement, index, List.of());
        session.load(ReviewOutput.requestText(previous, "assessment"), "Requirement assessment draft");
        saveEvidence();
        int round = 1;
        while (true) {
            EvidenceIndex currentIndex = session.index();
            List<ReviewOutput.AssessmentItem> previousItems = items;
            String prompt = prompts.assessRequirements(evidence, session.markdown(), decisions, previous, true, currentIndex);
            var audit = tasks(List.of(new ReviewTasks.Task("audit", prompt,
                    reply -> ReviewGrounding.assessment(reply, requirement, currentIndex, previousItems))),
                    round == 1 ? "assessment" : "assessment/round-" + round, 6, "Auditing requirement behaviors (round " + round + ")");
            previous = audit.get("audit");
            items = ReviewGrounding.assessment(previous, requirement, currentIndex, previousItems);
            boolean material = session.load(ReviewOutput.requestText(previous, "assessment"), "Requirement assessment audit round " + round);
            saveEvidence();
            if (!material) break;
            round++;
        }
        String markdown = ReviewReport.assessmentMarkdown(items, language);
        Files.writeString(directory.resolve("requirement-assessment.md"), markdown, StandardCharsets.UTF_8);
        return new AssessmentResult(markdown, ReviewReport.assessmentSummary(items, language));
    }

    void saveEvidence() throws IOException {
        Files.writeString(directory.resolve("supplemental-evidence.md"), session.markdown(), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("coverage.md"), evidence.coverage() + session.coverage(), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("evidence-index.md"), session.catalog().manifest(), StandardCharsets.UTF_8);
    }

    private Map<String, String> tasks(List<ReviewTasks.Task> tasks, String folder, int stage, String description)
            throws IOException, InterruptedException {
        return ReviewTasks.run(tasks, agent, directory.resolve(folder), options.reviewParallelism(), progress,
                stage, stages, description, options.resumeDirectory() != null);
    }

    static String coverage(String title, List<ReviewOutput.Decision> decisions) {
        StringBuilder out = new StringBuilder("\n## " + title + "\n\n");
        for (var decision : decisions) {
            var verdict = decision.verdict();
            out.append("- ").append(verdict.candidate().id()).append(" — ").append(verdict.status()).append(". ")
                    .append(EvidenceText.oneLine(verdict.candidate().title())).append(". ")
                    .append(EvidenceText.oneLine(verdict.reason())).append(" Relationship: ").append(decision.relation())
                    .append(". Baseline: ").append(EvidenceText.oneLine(decision.baseline()))
                    .append(". Contract: ").append(EvidenceText.oneLine(decision.contract())).append('\n');
            out.append("  - Support: ").append(decision.support().kind()).append('\n');
            for (var premise : decision.support().premises()) out.append("  - Premise [").append(premise.basis()).append("]: ")
                    .append(EvidenceText.oneLine(premise.statement())).append('\n');
            for (var citation : decision.citations()) out.append("  - ").append(ReviewReport.citationReference(citation, OutputLanguage.EN))
                    .append(": ").append(EvidenceText.oneLine(citation.quote())).append('\n');
        }
        return out.toString();
    }

    static ReviewFinding publishedFinding(ReviewOutput.Decision decision, OutputLanguage language) {
        ReviewFinding finding = decision.verdict().confirmed();
        String relation = switch (decision.relation()) {
            case INTRODUCED -> language.text("Introduced by this change", "本次变更引入");
            case EXPOSED_BY_CHANGE -> language.text("Existing defect exposed by this change", "本次变更触发既有缺陷");
            case UNMET_REQUIREMENT -> language.text("Explicit requirement or rule not met", "未满足明确需求或规则");
            default -> throw new IllegalArgumentException("Unpublishable finding relationship");
        };
        String references = decision.citations().stream().map(c -> ReviewReport.citationReference(c, language))
                .distinct().collect(java.util.stream.Collectors.joining("; "));
        String premises = decision.support().premises().stream().map(ReviewSupport.Premise::statement)
                .collect(java.util.stream.Collectors.joining("; "));
        String support = language.text(decision.support().kind().name(), switch (decision.support().kind()) {
            case REACHABLE_FAILURE -> "可达故障";
            case EXPLICIT_DEFENSIVE_REQUIREMENT -> "明确防御要求";
            case HARDENING -> "加固建议";
            case UNRESOLVED -> "尚未确定";
        });
        return new ReviewFinding(finding.id(), finding.title(), finding.severity(), finding.path(), finding.line(), finding.endLine(),
                finding.trigger(), finding.impact(), support + ": " + references + (premises.isEmpty() ? "" : " — " + premises),
                relation + ": " + finding.diffRelation());
    }

    static String unresolved(List<ReviewOutput.Decision> preliminary, List<ReviewOutput.Decision> adjudicated,
                             OutputLanguage language) {
        var latest = new LinkedHashMap<String, ReviewOutput.Decision>();
        preliminary.forEach(d -> latest.put(d.verdict().candidate().id(), d));
        adjudicated.forEach(d -> latest.put(d.verdict().candidate().id(), d));
        var pending = latest.values().stream().filter(d -> d.verdict().status() == ReviewOutput.Status.INSUFFICIENT_EVIDENCE).toList();
        if (pending.isEmpty()) return "";
        StringBuilder out = new StringBuilder("\n### " + language.text("Unconfirmed candidates", "未确认候选") + "\n\n");
        for (var decision : pending) out.append("- ").append(decision.verdict().candidate().id()).append(": ")
                .append(EvidenceText.oneLine(decision.verdict().reason())).append('\n');
        return out.toString();
    }
}
