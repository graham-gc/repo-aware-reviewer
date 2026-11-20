package reviewagent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.function.Function;

/** Evidence-driven verification, cross-verdict adjudication and independent acceptance audit. */
final class ReviewQuality {
    record Adjudication(List<ReviewOutput.Decision> decisions, List<List<String>> groups, String groupingNote) {
        Adjudication(List<ReviewOutput.Decision> decisions, List<List<String>> groups) { this(decisions, groups, ""); }
    }
    record AssessmentResult(String details, String summary, List<ReviewOutput.AssessmentItem> items) {
        AssessmentResult(String details, String summary) { this(details, summary, List.of()); }
    }
    record Completion(boolean complete, int invalidCandidates, int unmappedRequirements, int failedTasks, String notice, String markdown) {
        int pendingChecks() { return invalidCandidates + unmappedRequirements + failedTasks; }
    }
    private final CliOptions options;
    private final EvidenceBundle evidence;
    private final EvidenceSession session;
    private final PromptFactory prompts;
    private final AgentRunner agent;
    private final Path directory;
    private final CommandProgress progress;
    private final int stages;
    private final Map<String, Set<String>> retainedEvidence = new LinkedHashMap<>();

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
        return verifyRounds(findings, (candidate, view) -> prompts.verifyFinding(view.bundle(), candidate, "", view.index()),
                candidate -> Set.of(), "verification", 5, "Verifying candidate issues");
    }

    List<ReviewOutput.Decision> verifyScoped(List<ReviewOutput.Decision> candidates, Map<String, Set<String>> delivered,
                                            String folder, String outcomeRules, boolean allowBatch)
            throws IOException, InterruptedException {
        var previous = candidates.stream().collect(java.util.stream.Collectors.toMap(
                decision -> decision.verdict().candidate().id(), Function.identity()));
        var batchEligible = new LinkedHashMap<String, ReviewOutput.Decision>();
        if (allowBatch) {
            previous.forEach((id, decision) -> {
                if (decision.verdict().status() == ReviewOutput.Status.CONFIRMED && !delivered.containsKey(id))
                    batchEligible.put(id, decision);
            });
        }
        return verifyRounds(candidates.stream().map(decision -> decision.verdict().candidate()).toList(),
                (candidate, view) -> prompts.verifyFinding(view.bundle(), candidate, "", view.index())
                        + outcomeRules
                        + (delivered.containsKey(candidate.id()) ? "\nPREVIOUS DECISION — RECHECK WITH THE NEW EVIDENCE\n"
                           + PromptFactory.decisionSummary(previous.get(candidate.id()), view.index()) : ""),
                candidate -> {
                    var sources = new LinkedHashSet<>(delivered.getOrDefault(candidate.id(), Set.of()));
                    sources.addAll(citationIds(previous.get(candidate.id()).citations()));
                    return sources;
                }, folder, 5, "Independently verifying selected candidates", true, batchEligible);
    }

    Adjudication adjudicate(List<ReviewOutput.Decision> preliminary, String assessment, Set<String> requirementCandidates)
            throws IOException, InterruptedException {
        List<ReviewFinding> positives = preliminary.stream().filter(d -> d.verdict().status() == ReviewOutput.Status.CONFIRMED)
                .map(d -> d.verdict().confirmed()).toList();
        List<ReviewOutput.Decision> toGroup = positives.isEmpty()
                ? preliminary.stream().filter(d -> d.verdict().status() == ReviewOutput.Status.INSUFFICIENT_EVIDENCE
                        && !d.verdict().validationFailed()).toList()
                : preliminary;
        if (positives.isEmpty() && toGroup.size() < 2) {
            return new Adjudication(List.of(), List.of());
        }
        List<ReviewFinding> candidates = toGroup.stream().map(d -> d.verdict().candidate()).toList();
        List<List<String>> groups;
        try {
            var groupsReply = tasks(List.of(new ReviewTasks.Task("groups", prompts.groupCandidates(toGroup, session.index()),
                    reply -> ReviewOutput.candidateGroups(reply, candidates))), "adjudication", 7, "Reconciling candidate verdicts");
            groups = ReviewOutput.candidateGroups(groupsReply.get("groups"), candidates);
        } catch (ReviewStageException failure) {
            if (!positives.isEmpty()) throw failure;
            return new Adjudication(List.of(), List.of(), "\nReview-required candidates are shown individually. "
                    + failure.reportMessage(directory) + "\n");
        }
        if (positives.isEmpty()) return new Adjudication(List.of(), groups);

        Map<String, List<ReviewOutput.Decision>> related = new LinkedHashMap<>();
        Map<String, SharedAdjudication> shared = new LinkedHashMap<>();
        Set<String> deferred = new LinkedHashSet<>();
        for (List<String> ids : groups) {
            List<ReviewOutput.Decision> members = preliminary.stream().filter(d -> ids.contains(d.verdict().candidate().id())).toList();
            for (String id : ids) related.put(id, members);
            if (ids.stream().anyMatch(requirementCandidates::contains)) continue;
            var proposed = SharedAdjudication.eligible(members, evidence.scope(), session.catalog());
            if (proposed != null) {
                shared.put(proposed.candidateIds().getFirst(), proposed);
                deferred.addAll(proposed.candidateIds().subList(1, proposed.candidateIds().size()));
            }
        }
        VerificationPrompt publicationPrompt = (candidate, view) -> {
            var proposal = shared.get(candidate.id());
            return prompts.adjudicate(view.bundle(), candidate, related.get(candidate.id()), "", view.index())
                    + "\nINDEPENDENTLY AUDITED REQUIREMENT ASSESSMENT\n"
                    + "Recheck any contradiction between the proposed finding and this assessment against the source.\n" + assessment
                    + (proposal == null ? "" : "\n\n" + proposal.instructions());
        };
        Function<ReviewFinding, Set<String>> publicationEvidence = candidate -> groupEvidence(related.get(candidate.id()));
        var decisions = new LinkedHashMap<String, ReviewOutput.Decision>();
        var firstPass = positives.stream().filter(candidate -> !deferred.contains(candidate.id())).toList();
        for (var decision : verifyRounds(firstPass, publicationPrompt, publicationEvidence,
                "adjudication", 7, "Adjudicating findings before publication"))
            decisions.put(decision.verdict().candidate().id(), decision);

        var sharingNotes = new StringBuilder();
        for (var entry : shared.entrySet()) {
            var proposal = entry.getValue();
            if (!proposal.accepts(decisions.get(entry.getKey()), evidence.scope(), session.catalog())) continue;
            deferred.removeAll(proposal.candidateIds());
            sharingNotes.append("- ").append(entry.getKey()).append(" covers ")
                    .append(String.join(", ", proposal.candidateIds())).append(" in one validated publication verdict.\n");
        }
        var remaining = positives.stream().filter(candidate -> deferred.contains(candidate.id())).toList();
        for (var decision : verifyRounds(remaining, publicationPrompt, publicationEvidence,
                "adjudication", 7, "Adjudicating findings separately"))
            decisions.put(decision.verdict().candidate().id(), decision);

        var adjudicated = positives.stream().map(candidate -> decisions.get(candidate.id())).filter(java.util.Objects::nonNull).toList();
        String note = sharingNotes.isEmpty() ? "" : "\n## Shared publication adjudication\n\n" + sharingNotes;
        return new Adjudication(resolveConflicts(adjudicated, groups, OutputLanguage.parse(options.language())), groups, note);
    }

    static List<ReviewOutput.Decision> resolveConflicts(List<ReviewOutput.Decision> decisions, List<List<String>> groups,
                                                       OutputLanguage language) {
        var disputed = new java.util.HashSet<String>();
        for (List<String> group : groups) {
            var statuses = decisions.stream().filter(d -> group.contains(d.verdict().candidate().id()) && !d.verdict().validationFailed())
                    .map(d -> d.verdict().status()).collect(java.util.stream.Collectors.toSet());
            if (statuses.contains(ReviewOutput.Status.CONFIRMED) && statuses.size() > 1) disputed.addAll(group);
        }
        return decisions.stream().map(decision -> {
            var verdict = decision.verdict();
            if (verdict.validationFailed() || !disputed.contains(verdict.candidate().id())
                    || verdict.status() != ReviewOutput.Status.CONFIRMED) return decision;
            String reason = language.text("Publication decisions disagree; retained as a review-required risk, not a confirmed defect. Supporting review: ",
                    "发布裁决存在分歧，保留为需人工确认的风险，不计为确认缺陷。支持意见：") + verdict.reason();
            return new ReviewOutput.Decision(new ReviewOutput.Verification(verdict.candidate(),
                    ReviewOutput.Status.DISPUTED, reason, verdict.confirmed()), decision.relation(),
                    decision.baseline(), decision.contract(), decision.citations(), decision.support());
        }).toList();
    }

    @FunctionalInterface
    private interface VerificationPrompt { String build(ReviewFinding candidate, VerificationEvidence view); }

    private Set<String> citationIds(List<EvidenceIndex.Citation> citations) {
        var ids = new LinkedHashSet<String>();
        for (var citation : citations) {
            EvidenceSource source = session.catalog().source(citation.sourceId().isEmpty() ? citation.source() : citation.sourceId());
            if (source != null) ids.add(source.id());
        }
        return ids;
    }

    private Set<String> groupEvidence(List<ReviewOutput.Decision> group) {
        var ids = new LinkedHashSet<>(citationIds(group.stream().flatMap(d -> d.citations().stream()).toList()));
        for (var decision : group) ids.addAll(retainedEvidence.getOrDefault(decision.verdict().candidate().id(), Set.of()));
        return ids;
    }

    private List<ReviewOutput.Decision> verifyRounds(List<ReviewFinding> findings, VerificationPrompt prompt,
            Function<ReviewFinding, Set<String>> requiredSources, String folder, int stage, String description)
            throws IOException, InterruptedException {
        return verifyRounds(findings, prompt, requiredSources, folder, stage, description, false, Map.of());
    }

    private List<ReviewOutput.Decision> verifyRounds(List<ReviewFinding> findings, VerificationPrompt prompt,
            Function<ReviewFinding, Set<String>> requiredSources, String folder, int stage, String description,
            boolean newEvidenceOnly, Map<String, ReviewOutput.Decision> batchEligible)
            throws IOException, InterruptedException {
        var results = new LinkedHashMap<String, ReviewOutput.Decision>();
        var priorPrompts = new LinkedHashMap<String, String>();
        var continuations = new LinkedHashMap<String, VerificationContinuation>();
        var awaitingVerdicts = new LinkedHashSet<String>();
        OutputLanguage language = OutputLanguage.parse(options.language());
        List<ReviewFinding> pending = findings;
        int round = 1;
        while (!pending.isEmpty()) {
            String path = round == 1 ? folder : folder + "/round-" + round;
            Path taskDirectory = Files.createDirectories(directory.resolve(path));
            EvidenceCatalog available = session.catalog();
            String supplemental = session.markdown();
            var views = new LinkedHashMap<String, VerificationEvidence>();
            var tasks = new ArrayList<ReviewTasks.Task>();
            for (ReviewFinding candidate : pending) {
                var pinned = new LinkedHashSet<>(requiredSources.apply(candidate));
                pinned.addAll(retainedEvidence.getOrDefault(candidate.id(), Set.of()));
                var full = VerificationEvidence.select(evidence, available, supplemental, candidate, pinned, true);
                var view = options.verificationEvidence() == CliOptions.VerificationEvidenceMode.FULL ? full
                        : VerificationEvidence.select(evidence, available, supplemental, candidate, pinned, false);
                String basePrompt = prompt.build(candidate, view);
                boolean unchanged = basePrompt.equals(priorPrompts.put(candidate.id(), basePrompt)) && results.containsKey(candidate.id());
                boolean verdictRequired = unchanged && awaitingVerdicts.contains(candidate.id());
                boolean reuse = unchanged && !verdictRequired;
                var continuation = continuations.get(candidate.id());
                String followup = verdictRequired ? """

                        The earlier request-only reply has been processed. Return a complete <verification> block now.
                        Use INSUFFICIENT_EVIDENCE if the supplied material still leaves a decisive gap.
                        """ : "";
                String text = basePrompt + (continuation == null || reuse ? "" : continuation.markdown(view)) + followup;
                retainedEvidence.computeIfAbsent(candidate.id(), unused -> new LinkedHashSet<>())
                        .addAll(view.bundle().catalog().sources().stream().map(EvidenceSource::id).toList());
                int selectedBytes = EvidenceText.bytes(text);
                int fullBytes = view.fallback() ? selectedBytes : EvidenceText.bytes(prompt.build(candidate, full)
                        + (continuation == null || reuse ? "" : continuation.markdown(full)) + followup);
                agent.timings().prepared(taskDirectory.resolve(candidate.id()), fullBytes, selectedBytes,
                        view.bundle().catalog().sources().size(), available.sources().size());
                AtomicFiles.write(taskDirectory.resolve(candidate.id() + "-evidence.md"), view.manifest(fullBytes, selectedBytes));
                views.put(candidate.id(), view);
                // Same instructions and same actual evidence cannot justify another automatic reasoning pass.
                if (reuse) {
                    agent.timings().unchanged();
                    AtomicFiles.write(taskDirectory.resolve(candidate.id() + "-unchanged.txt"), "No new task evidence; retained the previous validated or unfinished result.\n");
                    continue;
                }
                tasks.add(new ReviewTasks.Task(candidate.id(), text, new ReviewTasks.Validator() {
                    @Override
                    public void validate(String reply) throws IOException {
                        if (newEvidenceOnly || verdictRequired || !session.requestOnly(reply))
                            ReviewGrounding.verification(reply, candidate, view.bundle(), view.index());
                    }

                    @Override
                    public String corrected(String response, IOException failure) {
                        return failure instanceof SourceQuotationException
                                ? ReviewGrounding.recoverPendingCitations(response, candidate, view.bundle(), view.index()) : response;
                    }
                }));
            }
            if (tasks.isEmpty()) break;
            var execution = runVerificationTasks(tasks, round == 1 ? batchEligible : Map.of(), views, taskDirectory,
                    stage, description + " (round " + round + ")", language);
            var replies = execution.individual();
            results.putAll(execution.shared());
            StringBuilder evidenceRequests = new StringBuilder();
            var candidateRequests = new LinkedHashMap<String, String>();
            for (ReviewFinding candidate : pending) {
                var reply = replies.get(candidate.id());
                if (reply == null) continue;
                var view = views.get(candidate.id());
                awaitingVerdicts.remove(candidate.id());
                if (!reply.valid()) {
                    results.put(candidate.id(), invalidCandidate(candidate, path, reply, language));
                    continue;
                }
                String response = reply.response();
                boolean requestOnly = session.requestOnly(response);
                if (requestOnly) awaitingVerdicts.add(candidate.id());
                results.put(candidate.id(), requestOnly
                        ? unfinishedCandidate(candidate, language.text(
                            "The reply requested evidence. A validated verification verdict is still pending.",
                            "回复仅请求了证据，尚无经过校验的复核结论。"))
                        : ReviewGrounding.verification(response, candidate, view.bundle(), view.index()));
                String requests = requestOnly ? response : ReviewOutput.requestText(response, "verification");
                candidateRequests.put(candidate.id(), EvidenceRequest.parse(requests).stream().map(EvidenceRequest::text)
                        .collect(java.util.stream.Collectors.joining("\n")));
                evidenceRequests.append(requests).append('\n');
            }
            boolean material = session.load(evidenceRequests.toString(), folder + " round " + round);
            for (String id : replies.keySet()) continuations.put(id, new VerificationContinuation(results.get(id),
                    candidateRequests.getOrDefault(id, ""), session.lastCoverage(),
                    views.get(id).bundle().catalog().sources().stream().map(EvidenceSource::id).collect(java.util.stream.Collectors.toSet())));
            var delivered = new LinkedHashSet<String>();
            for (var entry : candidateRequests.entrySet()) {
                Set<String> needed = session.delivery(entry.getValue(), views.get(entry.getKey()).bundle().catalog());
                retainedEvidence.computeIfAbsent(entry.getKey(), unused -> new LinkedHashSet<>()).addAll(needed);
                if (results.get(entry.getKey()).verdict().status() == ReviewOutput.Status.INSUFFICIENT_EVIDENCE) {
                    if (!needed.isEmpty()) delivered.add(entry.getKey());
                    if (newEvidenceOnly && !entry.getValue().isBlank())
                        session.recordFollowup(path + "/" + entry.getKey(), entry.getValue(), needed);
                    else session.recordDelivery(path + "/" + entry.getKey(), needed);
                }
            }
            saveEvidence();
            if (newEvidenceOnly ? delivered.isEmpty() : !material && delivered.isEmpty() && awaitingVerdicts.isEmpty()) break;
            pending = findings.stream().filter(candidate -> results.get(candidate.id()).verdict().status()
                    == ReviewOutput.Status.INSUFFICIENT_EVIDENCE)
                    .filter(candidate -> !newEvidenceOnly || delivered.contains(candidate.id())).toList();
            round++;
        }
        return findings.stream().map(candidate -> results.get(candidate.id())).toList();
    }

    private record VerificationReplies(Map<String, ReviewTasks.Outcome> individual, Map<String, ReviewOutput.Decision> shared) { }

    private VerificationReplies runVerificationTasks(List<ReviewTasks.Task> tasks, Map<String, ReviewOutput.Decision> eligible,
                                                        Map<String, VerificationEvidence> views, Path taskDirectory,
                                                        int stage, String description, OutputLanguage language)
            throws IOException, InterruptedException {
        boolean resume = options.resumeDirectory() != null;
        var taskIds = tasks.stream().map(ReviewTasks.Task::id).toList();
        var batches = ScopedVerificationBatch.plan(taskIds, eligible, views, language).stream()
                .filter(batch -> !resume || Files.isRegularFile(taskDirectory.resolve(batch.id() + "-response.md"))
                        || batch.candidates().stream().noneMatch(candidate ->
                                Files.isRegularFile(taskDirectory.resolve(candidate.id() + "-response.md")))).toList();
        if (batches.isEmpty()) return new VerificationReplies(ReviewTasks.runIsolated(tasks, agent, taskDirectory,
                options.reviewParallelism(), progress, stage, stages, description, resume), Map.of());

        var planned = new ArrayList<ReviewTasks.Task>();
        var grouped = new LinkedHashSet<String>();
        for (var batch : batches) {
            batch.candidates().forEach(candidate -> grouped.add(candidate.id()));
            planned.add(new ReviewTasks.Task(batch.id(), batch.prompt(), new ReviewTasks.Validator() {
                @Override public void validate(String reply) throws IOException {
                    if (batch.decisions(reply, language).isEmpty()) throw new IOException("Batch supplied no verified candidate verdicts");
                }
                @Override public boolean allowOutputCorrection() { return false; }
            }));
            int bytes = EvidenceText.bytes(batch.prompt());
            int sources = batch.view().bundle().catalog().sources().size();
            agent.timings().prepared(taskDirectory.resolve(batch.id()), bytes, bytes, sources, batch.view().totalSources());
            AtomicFiles.write(taskDirectory.resolve(batch.id() + "-evidence.md"), batch.view().manifest(bytes, bytes));
        }
        tasks.stream().filter(task -> !grouped.contains(task.id())).forEach(planned::add);
        var replies = new LinkedHashMap<>(ReviewTasks.runIsolated(planned, agent, taskDirectory,
                options.reviewParallelism(), progress, stage, stages, description, resume));
        var verified = new LinkedHashMap<String, ReviewOutput.Decision>();
        for (var batch : batches) {
            var outcome = replies.remove(batch.id());
            var accepted = outcome.valid() ? batch.decisions(outcome.response(), language) : Map.<String, ReviewOutput.Decision>of();
            verified.putAll(accepted);
            session.recordVerificationBatch(directory.relativize(taskDirectory).resolve(batch.id()).toString(),
                    batch.candidates().stream().map(ReviewFinding::id).toList(), accepted.keySet());
        }
        var retry = tasks.stream().filter(task -> grouped.contains(task.id()) && !verified.containsKey(task.id())).toList();
        if (!retry.isEmpty()) replies.putAll(ReviewTasks.runIsolated(retry, agent, taskDirectory, options.reviewParallelism(),
                progress, stage, stages, "Verifying unresolved batch candidates individually", resume));
        return new VerificationReplies(replies, verified);
    }

    private static ReviewOutput.Decision invalidCandidate(ReviewFinding candidate, String folder, ReviewTasks.Outcome outcome, OutputLanguage language) {
        String reason = language.text("Response remained invalid after bounded corrections; this candidate was not verified. See ",
                "回复经过有限纠正后仍未通过校验，该候选未完成复核。详见 ") + failureLink(folder, candidate.id());
        if (!outcome.failureKind().equals("INVALID_OUTPUT")) reason = language.text("Task did not complete (", "任务执行失败（")
                + outcome.failureKind() + language.text("); no validated decision is available. See ", "），该候选未完成复核。详见 ")
                + failureLink(folder, candidate.id());
        return unfinishedCandidate(candidate, reason);
    }

    private static ReviewOutput.Decision unfinishedCandidate(ReviewFinding candidate, String reason) {
        return new ReviewOutput.Decision(new ReviewOutput.Verification(candidate, ReviewOutput.Status.INSUFFICIENT_EVIDENCE,
                reason, null, true), ReviewOutput.Relation.UNRESOLVED, "", "", List.of());
    }

    private static String failureLink(String folder, String id) {
        String path = folder + "/" + id + "-state.properties";
        return "[" + path + "](" + path + ").";
    }

    AssessmentFindings.Plan planAssessment(AssessmentResult assessed) throws IOException {
        var plan = AssessmentFindings.plan(assessed.items(), evidence, session.index(), OutputLanguage.parse(options.language()));
        AtomicFiles.write(directory.resolve("assessment-findings.md"), AssessmentFindings.markdown(plan, List.of(), OutputLanguage.parse(options.language())));
        return plan;
    }

    List<ReviewOutput.Decision> verifyAssessment(AssessmentFindings.Plan plan) throws IOException, InterruptedException {
        return verifyRounds(plan.candidates(), (candidate, view) -> prompts.verifyAssessmentFinding(view.bundle(), candidate,
                plan.item(candidate.id()), "", view.index()), candidate -> citationIds(plan.item(candidate.id()).citations()),
                "verification/requirements", 7, "Verifying unmet requirement candidates");
    }

    AssessmentResult reconcileAssessment(AssessmentResult initial, AssessmentFindings.Plan plan,
                                         List<ReviewOutput.Decision> preliminary, List<ReviewOutput.Decision> adjudicated) throws IOException {
        if (initial.items().isEmpty()) return initial;
        OutputLanguage language = OutputLanguage.parse(options.language());
        var decisions = latest(preliminary, adjudicated);
        var items = AssessmentFindings.reconcile(initial.items(), plan, decisions, language);
        String details = ReviewReport.assessmentMarkdown(items, language);
        String summary = ReviewReport.assessmentSummary(items, language);
        if (!plan.origins().isEmpty() || !plan.unmapped().isEmpty()) {
            String link = language.text("\n\nCandidate verification: [assessment-findings.md](assessment-findings.md).\n",
                    "\n\n关联问题复核见 [assessment-findings.md](assessment-findings.md)。\n");
            details += link; summary += link;
        }
        AtomicFiles.write(directory.resolve("requirement-assessment.md"), details);
        AtomicFiles.write(directory.resolve("assessment-findings.md"), AssessmentFindings.markdown(plan, decisions, language));
        return new AssessmentResult(details, summary, items);
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
                new AssessmentValidator(requirement, index, List.of()))), "assessment", 6, "Assessing requirement behaviors");
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
                    new AssessmentValidator(requirement, currentIndex, previousItems))),
                    round == 1 ? "assessment" : "assessment/round-" + round, 6, "Auditing requirement behaviors (round " + round + ")");
            previous = audit.get("audit");
            items = ReviewGrounding.assessment(previous, requirement, currentIndex, previousItems);
            boolean material = session.load(ReviewOutput.requestText(previous, "assessment"), "Requirement assessment audit round " + round);
            saveEvidence();
            if (!material) break;
            round++;
        }
        String markdown = ReviewReport.assessmentMarkdown(items, language);
        AtomicFiles.write(directory.resolve("requirement-assessment.md"), markdown);
        AtomicFiles.write(directory.resolve("requirement-assessment-audited.md"), markdown);
        return new AssessmentResult(markdown, ReviewReport.assessmentSummary(items, language), items);
    }

    void saveEvidence() throws IOException {
        AtomicFiles.write(directory.resolve("supplemental-evidence.md"), session.markdown());
        AtomicFiles.write(directory.resolve("coverage.md"), evidence.coverage() + session.coverage());
        AtomicFiles.write(directory.resolve("evidence-index.md"), session.catalog().manifest());
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
            out.append("- ").append(verdict.candidate().id()).append(" — ")
                    .append(verdict.validationFailed() ? "NOT_COMPLETED" : verdict.status()).append(". ")
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

    static Completion completion(List<ReviewOutput.Decision> preliminary, List<ReviewOutput.Decision> adjudicated,
                                 OutputLanguage language) {
        return completion(preliminary, adjudicated, AssessmentFindings.Plan.empty(), language);
    }

    static Completion completion(List<ReviewOutput.Decision> preliminary, List<ReviewOutput.Decision> adjudicated,
                                 AssessmentFindings.Plan plan, OutputLanguage language) {
        return completion(preliminary, adjudicated, plan, List.of(), language);
    }

    static Completion completion(List<ReviewOutput.Decision> preliminary, List<ReviewOutput.Decision> adjudicated,
                                 AssessmentFindings.Plan plan, List<String> failedTasks, OutputLanguage language) {
        var invalid = latest(preliminary, adjudicated).stream().filter(d -> d.verdict().validationFailed()).toList();
        boolean complete = invalid.isEmpty() && plan.unmapped().isEmpty() && failedTasks.isEmpty();
        String notice = complete ? "" : language.text(
                "> **Review incomplete:** " + invalid.size() + " candidate checks did not produce a validated decision. "
                        + plan.unmapped().size() + " unmet requirements lack a changed-code anchor. "
                        + failedTasks.size() + " independent or required stage tasks did not complete. "
                        + "Those candidates remain unverified; this report must not be treated as approval. See [review-status.md](review-status.md).\n\n",
                "> **审查未完成：** " + invalid.size() + " 个候选未产出有效复核结果，已保留为未完成检查。"
                        + plan.unmapped().size() + " 个未满足需求无法定位到可复核的变更代码。"
                        + failedTasks.size() + " 个独立任务或必要阶段未完成。"
                        + "本报告不能视为审查通过，详见 [review-status.md](review-status.md)。\n\n");
        StringBuilder status = new StringBuilder(language.text("# Review status\n\n", "# 审查状态\n\n"))
                .append("- Status: ").append(complete ? "COMPLETE" : "INCOMPLETE").append("\n\n")
                .append(language.text("Completion describes validated review output, not code approval or runtime verification.",
                        "完成状态仅指本轮复核输出有效，不代表代码验收通过或已进行运行验证。")).append("\n\n");
        long reviewRequired = latest(preliminary, adjudicated).stream().filter(d -> !d.verdict().validationFailed()
                && (d.verdict().status() == ReviewOutput.Status.DISPUTED
                    || d.verdict().status() == ReviewOutput.Status.INSUFFICIENT_EVIDENCE)).count();
        status.append(language.text("Candidates requiring human review: ", "需人工确认的候选数：")).append(reviewRequired).append("\n\n");
        for (var decision : invalid) status.append("- ").append(decision.verdict().candidate().id()).append(": ")
                .append(decision.verdict().reason()).append('\n');
        for (var item : plan.unmapped()) status.append("- ").append(item.id()).append(": ")
                .append(language.text("No changed-code anchor; see assessment-findings.md.\n", "缺少可复核的变更代码位置，详见 assessment-findings.md。\n"));
        for (String failure : failedTasks) status.append("- NOT_COMPLETED: ").append(EvidenceText.oneLine(failure)).append('\n');
        return new Completion(complete, invalid.size(), plan.unmapped().size(), failedTasks.size(), notice, status.toString());
    }

    static List<ReviewOutput.Decision> latest(List<ReviewOutput.Decision> preliminary, List<ReviewOutput.Decision> adjudicated) {
        var latest = new LinkedHashMap<String, ReviewOutput.Decision>();
        preliminary.forEach(d -> latest.put(d.verdict().candidate().id(), d));
        adjudicated.forEach(d -> latest.put(d.verdict().candidate().id(), d));
        return List.copyOf(latest.values());
    }
}
