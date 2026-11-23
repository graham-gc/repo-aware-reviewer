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
    private enum Followups { STANDARD, SCOPED, SCOPED_REFINEMENT, DECISIVE }

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
        return verify(findings, Map.of());
    }

    List<ReviewOutput.Decision> verify(List<ReviewFinding> findings, Map<String, EvidenceSession.VerificationBrief> briefs)
            throws IOException, InterruptedException {
        boolean publicationProof = PublicationGate.enabledFor(options) || options.batchedVerification();
        Function<ReviewFinding, EvidenceSession.VerificationBrief> brief =
                candidate -> briefs.getOrDefault(candidate.id(), EvidenceSession.VerificationBrief.EMPTY);
        VerificationPrompt individual = (candidate, view) -> prompts.verifyFinding(view.bundle(), candidate, "", view.index(),
                PromptFactory.DECISIVE_FOLLOWUP + brief.apply(candidate).instructions(view.bundle().catalog()), publicationProof);
        return verifyDeep(findings, individual, (candidates, view) -> prompts.verifyBatch(view, candidates,
                        candidate -> brief.apply(candidate).instructions(view.bundle().catalog()), ""),
                candidate -> Set.of(), "verification", 5, "Verifying candidate issues", true, briefs);
    }

    private Map<String, List<ReadyCheck>> verificationAliases(List<ReadyCheck> checks,
                                                               Map<String, EvidenceSession.VerificationBrief> briefs) {
        record Input(ReviewFinding claim, EvidenceSession.VerificationBrief brief) { }
        var representatives = new LinkedHashMap<Input, String>();
        var aliases = new LinkedHashMap<String, List<ReadyCheck>>();
        for (var check : checks) {
            var finding = check.candidate;
            if (finding.severity().compareTo(ReviewFinding.Severity.MEDIUM) < 0) continue;
            var input = new Input(finding.withId(""), briefs.getOrDefault(finding.id(), EvidenceSession.VerificationBrief.EMPTY));
            String representative = representatives.putIfAbsent(input, finding.id());
            if (representative != null)
                aliases.computeIfAbsent(representative, unused -> new ArrayList<>()).add(check);
        }
        return aliases;
    }

    private List<ReviewTasks.Job> releaseAliases(ReadyCheck check, List<ReadyCheck> aliases,
                                                   Map<String, EvidenceSession.VerificationBrief> briefs) throws IOException {
        if (aliases.isEmpty()) return List.of();
        var first = check.candidate;
        var decision = check.decision;
        var confirmed = decision.verdict().confirmed();
        if (!PublicationGate.ready(decision, session.index(), evidence.scope().targetCommit())
                || !confirmed.path().equals(first.path()) || confirmed.line() != first.line() || confirmed.endLine() != first.endLine()
                || !confirmed.trigger().equals(first.trigger()) || !confirmed.impact().equals(first.impact())) {
            var jobs = new ArrayList<ReviewTasks.Job>();
            for (var alias : aliases) {
                alias.additions = check.additions;
                alias.pinned.addAll(sourceIds(check.additions));
                jobs.add(alias.prepare(initialMember(alias, briefs)));
            }
            return jobs;
        }
        for (var alias : aliases) {
            var finding = alias.candidate;
            var verdict = new ReviewOutput.Verification(finding, ReviewOutput.Status.CONFIRMED,
                    decision.verdict().reason(), confirmed.withId(finding.id()));
            alias.decision = new ReviewOutput.Decision(verdict, decision.relation(), decision.baseline(),
                    decision.contract(), decision.citations(), decision.support());
            retainedEvidence.put(finding.id(), new LinkedHashSet<>(retainedEvidence.getOrDefault(first.id(), Set.of())));
            session.recordVerificationReuse(finding.id(), first.id());
        }
        return List.of();
    }

    List<ReviewOutput.Decision> verifyScoped(List<ReviewOutput.Decision> candidates, Map<String, Set<String>> delivered,
                                            String folder, String outcomeRules, boolean allowBatch)
            throws IOException, InterruptedException {
        return verifyScoped(candidates, delivered, folder, outcomeRules, allowBatch, false);
    }

    List<ReviewOutput.Decision> verifyScoped(List<ReviewOutput.Decision> candidates, Map<String, Set<String>> delivered,
                                            String folder, String outcomeRules, boolean allowBatch, boolean allowRefinement)
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
                        + """

                        Before asking for more evidence, decide whether a required trigger is contradicted by the pinned
                        source. If it is, reject this candidate and finish; a different hypothetical trigger is a different
                        candidate. If the supplied sources prove the trigger, changed behavior and outcome, confirm and finish.
                        When replying to a prior evidence question, include one <previousPremise> field directly inside
                        <verification>. Allowed values are SUPPORTED, REFUTED and UNRESOLVED. In <reason>, explain
                        what the new excerpts establish about that exact question and cite them. Ask another question
                        only after answering that premise. If it remains unresolved, return INSUFFICIENT_EVIDENCE
                        without further requests and name the missing fact for the maintainer.
                        """
                        + EvidenceRequest.SCOPED_GUIDANCE
                        + (delivered.containsKey(candidate.id()) ? "\nPREVIOUS DECISION — RECHECK WITH THE NEW EVIDENCE\n"
                           + PromptFactory.decisionSummary(previous.get(candidate.id()), view.index())
                           + EvidenceText.block("Original evidence questions for this candidate", "text",
                                EvidenceText.linePrefix(session.initialRequests(candidate.id()), 1_600)) : ""),
                candidate -> {
                    var sources = new LinkedHashSet<>(delivered.getOrDefault(candidate.id(), Set.of()));
                    sources.addAll(citationIds(previous.get(candidate.id()).citations()));
                    return sources;
                }, folder, 5, "Independently verifying selected candidates",
                allowRefinement ? Followups.SCOPED_REFINEMENT : Followups.SCOPED, batchEligible, delivered);
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
        boolean gated = PublicationGate.enabledFor(options);
        EvidenceIndex index = session.index();
        String target = evidence.scope().targetCommit();
        if (gated && toGroup.stream().allMatch(decision -> PublicationGate.ready(decision, index, target))) {
            var equivalents = toGroup.size() == 1
                    ? List.of(List.of(toGroup.getFirst().verdict().candidate().id()))
                    : SharedAdjudication.equivalentGroups(toGroup);
            if (equivalents.size() == 1 && equivalents.getFirst().size() == toGroup.size()) {
                return new Adjudication(toGroup, equivalents, directPublicationNote(toGroup.getFirst(), equivalents.getFirst()));
            }
        }
        List<List<String>> groups;
        try {
            var groupsReply = tasks(List.of(new ReviewTasks.Task("groups", prompts.groupCandidates(toGroup, index),
                    reply -> ReviewOutput.candidateGroups(reply, candidates))), "adjudication", 7, "Reconciling candidate verdicts");
            groups = ReviewOutput.candidateGroups(groupsReply.get("groups"), candidates);
        } catch (ReviewStageException failure) {
            if (!positives.isEmpty()) throw failure;
            return new Adjudication(List.of(), List.of(), "\nReview-required candidates are shown individually. "
                    + failure.reportMessage(directory) + "\n");
        }
        if (positives.isEmpty()) return new Adjudication(List.of(), groups);

        Map<String, List<ReviewOutput.Decision>> related = new LinkedHashMap<>();
        Set<String> covered = new LinkedHashSet<>();
        var published = new ArrayList<ReviewOutput.Decision>();
        var directNotes = new StringBuilder();
        var sharingNotes = new StringBuilder();
        for (List<String> ids : groups) {
            List<ReviewOutput.Decision> members = preliminary.stream().filter(d -> ids.contains(d.verdict().candidate().id())).toList();
            for (String id : ids) related.put(id, members);
            if (ids.stream().anyMatch(requirementCandidates::contains)) continue;
            if (gated && members.stream().allMatch(member -> member.verdict().status() == ReviewOutput.Status.CONFIRMED)) {
                for (var member : members) {
                    if (!PublicationGate.ready(member, index, target)) continue;
                    published.add(member);
                    var id = member.verdict().candidate().id();
                    covered.add(id);
                    directNotes.append(directPublicationNote(member, List.of(id)));
                }
            }
            for (List<String> equivalent : SharedAdjudication.equivalentGroups(members)) {
                var awaiting = equivalent.stream().filter(id -> !covered.contains(id)).toList();
                if (awaiting.size() < 2) continue;
                String representative = awaiting.getFirst();
                covered.addAll(awaiting.subList(1, awaiting.size()));
                sharingNotes.append("- ").append(representative).append(" is the publication task for ")
                        .append(String.join(", ", awaiting)).append(" (identical independently verified claim and proof).\n");
            }
        }

        String assessedEvidence = "\nINDEPENDENTLY AUDITED REQUIREMENT ASSESSMENT\n"
                + "Recheck any contradiction between the proposed finding and this assessment against the source.\n" + assessment;
        VerificationPrompt publicationPrompt = (candidate, view) ->
                prompts.adjudicate(view.bundle(), candidate, related.get(candidate.id()), "", view.index(),
                        gated || options.batchedVerification()) + assessedEvidence;
        VerificationBatch.Prompt publicationBatch = (members, view) -> prompts.verifyBatch(view, members,
                candidate -> "\n" + PromptFactory.publicationContext(related.get(candidate.id()), view.index()),
                PromptFactory.PUBLICATION_INSTRUCTIONS + assessedEvidence + "\n\n");
        Function<ReviewFinding, Set<String>> publicationEvidence = candidate -> groupEvidence(related.get(candidate.id()));
        var tasks = positives.stream().filter(candidate -> !covered.contains(candidate.id())).toList();
        published.addAll(verifyDeep(tasks, publicationPrompt, publicationBatch, publicationEvidence,
                "adjudication", 7, "Adjudicating findings before publication", false, Map.of()));
        String note = (directNotes.isEmpty() ? "" : "\n## Publication from independent verification\n\n" + directNotes)
                + (sharingNotes.isEmpty() ? "" : "\n## Shared publication adjudication\n\n" + sharingNotes);
        return new Adjudication(resolveConflicts(published, groups, OutputLanguage.EN), groups, note);
    }

    private static String directPublicationNote(ReviewOutput.Decision decision, List<String> ids) {
        return "- Published " + decision.verdict().confirmed().id() + " from its grounded independent verification for "
                + String.join(", ", ids) + ".\n";
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

    private List<ReviewOutput.Decision> verifyDeep(List<ReviewFinding> findings, VerificationPrompt individual,
            VerificationBatch.Prompt render, Function<ReviewFinding, Set<String>> requiredSources,
            String folder, int stage, String description, boolean candidateVerification,
            Map<String, EvidenceSession.VerificationBrief> briefs) throws IOException, InterruptedException {
        boolean batchEnabled = options.batchedVerification();
        if (!candidateVerification && !batchEnabled)
            return verifyRounds(findings, individual, requiredSources, folder, stage, description,
                    Followups.DECISIVE, Map.of(), Map.of());
        var root = Files.createDirectories(directory.resolve(folder));
        var available = session.catalog();
        String supplemental = session.markdown();
        var checks = new LinkedHashMap<String, ReadyCheck>();
        for (var finding : findings)
            checks.put(finding.id(), new ReadyCheck(finding, individual, requiredSources.apply(finding), available, supplemental, root));

        var aliases = candidateVerification && PublicationGate.enabledFor(options)
                ? verificationAliases(List.copyOf(checks.values()), briefs) : Map.<String, List<ReadyCheck>>of();
        var held = aliases.values().stream().flatMap(List::stream).collect(java.util.stream.Collectors.toSet());
        var representatives = checks.values().stream().filter(check -> !held.contains(check)).toList();

        var batches = new LinkedHashMap<String, VerificationBatch.Validation>();
        var members = new LinkedHashMap<String, VerificationBatch.Member>();
        var grouped = new LinkedHashSet<String>();
        var initial = new ArrayList<ReviewTasks.Job>();
        int maximum = Math.min(options.maxEvidenceBytes(), DeepDiscovery.MAX_PROMPT_BYTES);
        if (batchEnabled) {
            var paths = new LinkedHashMap<String, List<ReadyCheck>>();
            for (var check : representatives)
                paths.computeIfAbsent(check.candidate.path(), unused -> new ArrayList<>()).add(check);
            for (var checksOnPath : paths.values()) {
                var plan = VerificationBatch.plan(checksOnPath.stream().map(check -> check.propose(false)).toList(), render, maximum);
                if (candidateVerification && options.verificationEvidence() != CliOptions.VerificationEvidenceMode.FULL) {
                    var narrower = VerificationBatch.plan(checksOnPath.stream().map(check -> check.propose(true)).toList(), render, maximum);
                    if (narrower.improvesOn(plan)) {
                        plan = narrower;
                        checksOnPath.forEach(check -> check.compact = true);
                    }
                }
                if (!briefs.isEmpty()) {
                    var withHandoff = plan.members().stream().map(member ->
                            withHandoff(checks.get(member.candidate().id()), member, briefs, maximum)).toList();
                    if (!withHandoff.equals(plan.members())) {
                        var enriched = VerificationBatch.plan(withHandoff, render, maximum);
                        // A possible saving in a later round cannot justify extra first-round requests.
                        if (enriched.requests() <= plan.requests()) plan = enriched;
                    }
                }
                for (var member : plan.members()) members.put(member.candidate().id(), member);
                for (var batch : plan.batches()) {
                    batches.put(batch.id(), new VerificationBatch.Validation(batch));
                    batch.members().forEach(member -> grouped.add(member.candidate().id()));
                }
            }
        }

        // Keep the original queue priority: a batch occupies its first member's slot.
        for (var check : representatives) {
            String id = check.candidate.id();
            var member = batchEnabled ? members.get(id) : initialMember(check, briefs);
            var job = check.prepare(member);
            var validation = batches.get("batch-" + id);
            if (validation != null) {
                var batch = validation.batch;
                var view = member.view();
                int bytes = EvidenceText.bytes(batch.prompt());
                agent.timings().prepared(root.resolve(batch.id()), bytes, bytes,
                        view.bundle().catalog().sources().size(), view.totalSources());
                AtomicFiles.write(root.resolve(batch.id() + "-evidence.md"), view.manifest(bytes, bytes));
                initial.add(new ReviewTasks.Job(root, new ReviewTasks.Task(batch.id(), batch.prompt(), validation)));
            } else if (!grouped.contains(id)) {
                initial.add(job);
            }
        }

        boolean automatic = candidateVerification && (options.adaptiveParallelism() || options.verificationParallelismLimit() == null);
        int parallelism = options.reviewParallelism();
        AdaptiveConcurrency admission = null;
        if (automatic && !initial.isEmpty()) {
            Integer cap = options.verificationParallelismLimit();
            parallelism = cap == null ? findings.size() : Math.min(findings.size(), cap);
            admission = agent.verificationAdmission(initial, parallelism, stage,
                    directory.toAbsolutePath().resolve(".verification-parallelism"));
        }

        var refined = new LinkedHashSet<String>();
        OutputLanguage language = OutputLanguage.EN;
        try {
            ReviewTasks.runReady(initial, agent, root, parallelism, progress, stage, stages, description,
                    options.resumeDirectory() != null, admission, (job, outcome) -> {
                        var validation = batches.get(job.task().id());
                        if (validation == null) {
                            var check = checks.get(job.task().id());
                            var next = check.complete(outcome, refined, language);
                            return next == null ? releaseAliases(check, aliases.getOrDefault(check.candidate.id(), List.of()), briefs)
                                    : List.of(next);
                        }
                        var batch = validation.batch;
                        var replies = outcome.valid() ? validation.replies() : new VerificationBatch.Replies(Map.of(), Map.of());
                        var accepted = replies.accepted();
                        session.recordVerificationBatch(folder + "/" + batch.id(),
                                batch.members().stream().map(member -> member.candidate().id()).toList(), accepted.keySet());
                        var ready = new ArrayList<ReviewTasks.Job>();
                        for (var member : batch.members()) {
                            var check = checks.get(member.candidate().id());
                            String response = accepted.get(member.candidate().id());
                            if (response != null) {
                                var next = check.complete(new ReviewTasks.Outcome(response, null), refined, language);
                                if (next != null) ready.add(next);
                                else ready.addAll(releaseAliases(check, aliases.getOrDefault(check.candidate.id(), List.of()), briefs));
                            } else {
                                var next = check.collectBatchGap(replies.evidenceGaps().get(member.candidate().id()));
                                ready.add(next != null ? next : new ReviewTasks.Job(job.directory(), member.task()));
                            }
                        }
                        return ready;
                    });
        } finally {
            if (admission != null) admission.saveHistory();
        }
        saveEvidence();
        return findings.stream().map(finding -> checks.get(finding.id()).decision).toList();
    }

    private VerificationBatch.Member initialMember(ReadyCheck check, Map<String, EvidenceSession.VerificationBrief> briefs) {
        return withHandoff(check, check.propose(false), briefs,
                Math.min(options.maxEvidenceBytes(), DeepDiscovery.MAX_PROMPT_BYTES));
    }

    private VerificationBatch.Member withHandoff(ReadyCheck check, VerificationBatch.Member member,
            Map<String, EvidenceSession.VerificationBrief> briefs, int maximum) {
        var sources = briefs.getOrDefault(check.candidate.id(), EvidenceSession.VerificationBrief.EMPTY).sources();
        if (sources.isEmpty() || sourceIds(member.view().bundle().catalog()).containsAll(sources)) return member;
        var enriched = check.propose(check.compact, sources);
        return EvidenceText.bytes(enriched.task().prompt()) <= maximum ? enriched : member;
    }

    /** A continuation sees its initial evidence plus the blocks delivered to its own question. */
    private final class ReadyCheck {
        private final ReviewFinding candidate;
        private final VerificationPrompt prompt;
        private final EvidenceCatalog original;
        private final String supplemental;
        private final Path root;
        private boolean compact;
        private final Set<String> pinned;
        private EvidenceCatalog additions = EvidenceCatalog.empty();
        private Set<String> delivered = Set.of();
        private int round = 1;
        private ReviewOutput.Decision decision;
        private VerificationContinuation continuation;
        private VerificationEvidence view;
        private boolean needsVerdict;

        ReadyCheck(ReviewFinding candidate, VerificationPrompt prompt, Set<String> required, EvidenceCatalog original,
                   String supplemental, Path root) {
            this.candidate = candidate;
            this.prompt = prompt;
            this.original = original;
            this.supplemental = supplemental;
            this.root = root;
            this.pinned = new LinkedHashSet<>(required);
        }

        private Path path() { return round == 1 ? root : root.resolve("round-" + round); }

        private EvidenceCatalog available() {
            return additions.sources().isEmpty() ? original : original.plus(additions);
        }

        private String supplemental() {
            return supplemental + additions.sources().stream().map(EvidenceSource::markdown)
                    .collect(java.util.stream.Collectors.joining());
        }

        ReviewTasks.Job prepare(VerificationBatch.Member member) throws IOException {
            Path path = Files.createDirectories(path());
            view = member.view();
            Set<String> offered = sourceIds(view.bundle().catalog());
            pinned.addAll(offered);
            retainedEvidence.computeIfAbsent(candidate.id(), unused -> new LinkedHashSet<>()).addAll(offered);
            int selectedBytes = EvidenceText.bytes(member.task().prompt());
            int fullBytes = view.fallback() ? selectedBytes : EvidenceText.bytes(promptText(
                    VerificationEvidence.select(evidence, available(), supplemental(), candidate, pinned, true)));
            agent.timings().prepared(path.resolve(candidate.id()), fullBytes, selectedBytes, offered.size(), view.totalSources());
            AtomicFiles.write(path.resolve(candidate.id() + "-evidence.md"), view.manifest(fullBytes, selectedBytes));
            return new ReviewTasks.Job(path, member.task());
        }

        private String promptText(VerificationEvidence supplied) {
            String instruction = needsVerdict ? """

                    The earlier request-only reply has been processed. Return a complete <verification> block now.
                    Use INSUFFICIENT_EVIDENCE if the supplied material still leaves a decisive gap.
                    """ : "";
            return prompt.build(candidate, supplied) + (continuation == null ? "" : continuation.markdown(supplied)) + instruction;
        }

        VerificationBatch.Member propose(boolean narrow) {
            return propose(narrow, Set.of());
        }

        VerificationBatch.Member propose(boolean narrow, Set<String> discoverySources) {
            var selected = pinned;
            if (!discoverySources.isEmpty()) {
                selected = new LinkedHashSet<>(pinned);
                selected.addAll(discoverySources);
            }
            var supplied = VerificationEvidence.select(evidence, available(), supplemental(), candidate, selected,
                    options.verificationEvidence() == CliOptions.VerificationEvidenceMode.FULL, narrow);
            boolean first = round == 1;
            var task = new ReviewTasks.Task(candidate.id(), promptText(supplied), new ReviewTasks.Validator() {
                @Override public void validate(String reply) throws IOException {
                    if (!(first && session.requestOnly(reply) && EvidenceRequest.parse(reply).size() == 1))
                        ReviewGrounding.verification(reply, candidate, supplied.bundle(), supplied.index());
                }

                @Override public String corrected(String response, IOException failure) throws IOException {
                    return failure instanceof SourceQuotationException
                            ? ReviewGrounding.recoverPendingCitations(response, candidate, supplied.bundle(), supplied.index())
                            : response;
                }
            });
            return new VerificationBatch.Member(candidate, supplied, task);
        }

        ReviewTasks.Job complete(ReviewTasks.Outcome outcome, Set<String> refined, OutputLanguage language)
                throws IOException, InterruptedException {
            String folder = directory.relativize(path()).toString().replace('\\', '/');
            if (!outcome.valid()) {
                decision = invalidCandidate(candidate, folder, outcome, language);
                return null;
            }
            String response = outcome.response();
            boolean requestOnly = session.requestOnly(response);
            decision = requestOnly ? unfinishedCandidate(candidate, language.text(
                    "The reply requested evidence. A validated verification verdict is still pending.",
                    "回复仅请求了证据，尚无经过校验的复核结论。"))
                    : ReviewGrounding.verification(response, candidate, view.bundle(), view.index());
            if (decision.verdict().status() != ReviewOutput.Status.INSUFFICIENT_EVIDENCE) return null;
            var followup = requestOnly ? new ReviewOutput.Followup(response, "") : ReviewOutput.verificationFollowup(response);
            String task = folder + "/" + candidate.id();
            String requests = eligibleRequests(decision, followup, Followups.DECISIVE, requestOnly,
                    round > 1 || !session.initialRequests(candidate.id()).isBlank(), delivered, refined, task);
            if (EvidenceRequest.parse(requests).isEmpty() && !requestOnly) return null;
            session.load(requests, task + " evidence");
            var previousView = view.bundle().catalog();
            delivered = options.batchedVerification() ? session.deliverDecisive(task, requests, previousView)
                    : session.deliverScoped(task, requests, previousView);
            if (delivered.isEmpty() && !requestOnly) return null;

            continuation = new VerificationContinuation(decision, requests, session.lastCoverage(), sourceIds(previousView));
            needsVerdict = requestOnly && delivered.isEmpty();
            return continueWithEvidence();
        }

        ReviewTasks.Job collectBatchGap(VerificationBatch.EvidenceGap gap) throws IOException, InterruptedException {
            if (gap == null || !session.requestOnly(gap.requests())) return null;
            String task = directory.relativize(path()).resolve(candidate.id()).toString();
            session.load(gap.requests(), task + " evidence from an unvalidated batch verdict");
            var previousView = view.bundle().catalog();
            Set<String> sources = session.deliverScoped(task, gap.requests(), previousView);
            if (sources.isEmpty()) return null;
            delivered = sources;
            continuation = new VerificationContinuation(null, gap.requests(), session.lastCoverage(), sourceIds(previousView), gap.question());
            return continueWithEvidence();
        }

        private ReviewTasks.Job continueWithEvidence() throws IOException {
            additions = additions.plus(session.catalog().select(delivered));
            pinned.addAll(delivered);
            round++;
            return prepare(propose(compact));
        }
    }

    private static Set<String> sourceIds(EvidenceCatalog catalog) {
        return catalog.sources().stream().map(EvidenceSource::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private List<ReviewOutput.Decision> verifyRounds(List<ReviewFinding> findings, VerificationPrompt prompt,
            Function<ReviewFinding, Set<String>> requiredSources, String folder, int stage, String description)
            throws IOException, InterruptedException {
        return verifyRounds(findings, prompt, requiredSources, folder, stage, description,
                Followups.STANDARD, Map.of(), Map.of());
    }

    private List<ReviewOutput.Decision> verifyRounds(List<ReviewFinding> findings, VerificationPrompt prompt,
            Function<ReviewFinding, Set<String>> requiredSources, String folder, int stage, String description,
            Followups followups, Map<String, ReviewOutput.Decision> batchEligible, Map<String, Set<String>> initialDelivery)
            throws IOException, InterruptedException {
        boolean newEvidenceOnly = followups != Followups.STANDARD;
        var results = new LinkedHashMap<String, ReviewOutput.Decision>();
        var priorPrompts = new LinkedHashMap<String, String>();
        var continuations = new LinkedHashMap<String, VerificationContinuation>();
        var awaitingVerdicts = new LinkedHashSet<String>();
        var refinedPremises = new LinkedHashSet<String>();
        Map<String, Set<String>> lastDelivery = initialDelivery;
        OutputLanguage language = OutputLanguage.EN;
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
                var selected = options.verificationEvidence() == CliOptions.VerificationEvidenceMode.FULL ? full
                        : VerificationEvidence.select(evidence, available, supplemental, candidate, pinned, false);
                var view = selected;
                String basePrompt = prompt.build(candidate, view);
                boolean unchanged = basePrompt.equals(priorPrompts.put(candidate.id(), basePrompt)) && results.containsKey(candidate.id());
                boolean waitingForVerdict = awaitingVerdicts.contains(candidate.id());
                boolean verdictRequired = unchanged && waitingForVerdict;
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
                        boolean standalone = session.requestOnly(reply);
                        boolean legacyRequest = followups == Followups.DECISIVE && continuation == null
                                && standalone && EvidenceRequest.parse(reply).size() == 1;
                        if (!legacyRequest && (newEvidenceOnly || verdictRequired || !standalone))
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
                var followup = requestOnly ? new ReviewOutput.Followup(response, "")
                        : ReviewOutput.verificationFollowup(response);
                String requests = eligibleRequests(results.get(candidate.id()), followup, followups, requestOnly,
                        round > 1 || !session.initialRequests(candidate.id()).isBlank(),
                        lastDelivery.getOrDefault(candidate.id(), Set.of()), refinedPremises, path + "/" + candidate.id());
                candidateRequests.put(candidate.id(), EvidenceRequest.parse(requests).stream().map(EvidenceRequest::text)
                        .collect(java.util.stream.Collectors.joining("\n")));
                evidenceRequests.append(requests).append('\n');
            }
            boolean material = session.load(evidenceRequests.toString(), folder + " round " + round);
            for (String id : replies.keySet()) continuations.put(id, new VerificationContinuation(results.get(id),
                    candidateRequests.getOrDefault(id, ""), session.lastCoverage(),
                    views.get(id).bundle().catalog().sources().stream().map(EvidenceSource::id).collect(java.util.stream.Collectors.toSet())));
            var delivered = new LinkedHashMap<String, Set<String>>();
            for (var entry : candidateRequests.entrySet()) {
                if (entry.getValue().isBlank()) continue;
                var supplied = views.get(entry.getKey()).bundle().catalog();
                Set<String> needed = newEvidenceOnly
                        ? session.deliverScoped(path + "/" + entry.getKey(), entry.getValue(), supplied)
                        : session.delivery(entry.getValue(), supplied);
                retainedEvidence.computeIfAbsent(entry.getKey(), unused -> new LinkedHashSet<>()).addAll(needed);
                if (results.get(entry.getKey()).verdict().status() == ReviewOutput.Status.INSUFFICIENT_EVIDENCE) {
                    if (!needed.isEmpty()) delivered.put(entry.getKey(), needed);
                    if (!newEvidenceOnly) session.recordDelivery(path + "/" + entry.getKey(), needed);
                }
            }
            saveEvidence();
            boolean needsVerdict = (followups == Followups.DECISIVE || !newEvidenceOnly) && !awaitingVerdicts.isEmpty();
            if (delivered.isEmpty() && !needsVerdict && (newEvidenceOnly || !material)) break;
            lastDelivery = delivered;
            pending = findings.stream().filter(candidate -> results.get(candidate.id()).verdict().status()
                    == ReviewOutput.Status.INSUFFICIENT_EVIDENCE)
                    .filter(candidate -> !newEvidenceOnly || delivered.containsKey(candidate.id())
                            || needsVerdict && awaitingVerdicts.contains(candidate.id())).toList();
            round++;
        }
        return findings.stream().map(candidate -> results.get(candidate.id())).toList();
    }

    private String eligibleRequests(ReviewOutput.Decision decision, ReviewOutput.Followup followup, Followups mode,
                                    boolean requestOnly, boolean answering, Set<String> supplied,
                                    Set<String> refined, String task) {
        String requests = followup.requests();
        if (mode == Followups.STANDARD || requests.isBlank()) return requests;
        if (decision.verdict().status() != ReviewOutput.Status.INSUFFICIENT_EVIDENCE) return "";
        if (mode == Followups.DECISIVE && !requestOnly && decision.verdict().nextCheck().isBlank()) {
            session.recordFollowup(task, requests, Set.of(),
                    "Stopped; the verdict did not identify a premise that further evidence could resolve.");
            return "";
        }
        if (!answering) return requests;
        if (decision.citations().stream().noneMatch(citation -> supplied.contains(citation.sourceId()))) {
            session.recordFollowup(task, requests, Set.of(),
                    "Stopped; the verdict cites no newly supplied evidence for the remaining premise.");
            return "";
        }
        if (followup.answeredPreviousPremise()) return requests;
        String concrete = EvidenceRequest.parse(requests).stream().filter(request -> !request.navigationOnly())
                .map(EvidenceRequest::text).collect(java.util.stream.Collectors.joining("\n"));
        boolean allowed = mode == Followups.DECISIVE || mode == Followups.SCOPED_REFINEMENT;
        if (!allowed || !followup.previousPremise().equals("UNRESOLVED") || concrete.isBlank()
                || decision.verdict().nextCheck().isBlank() || !provedNewImplementation(decision, supplied)
                || !refined.add(decision.verdict().candidate().id())) {
            session.recordFollowup(task, requests, Set.of(), "Stopped; the previous evidence question remains unanswered.");
            return "";
        }
        session.recordFollowup(task, requests, Set.of(),
                "New source supports part of the previous premise. Checking one more concrete evidence step.");
        return concrete;
    }

    private static boolean provedNewImplementation(ReviewOutput.Decision decision, Set<String> supplied) {
        return decision.support().premises().stream().filter(premise -> premise.basis() == ReviewSupport.Basis.IMPLEMENTATION)
                .flatMap(premise -> premise.citations().stream()).anyMatch(citation -> supplied.contains(citation.sourceId())
                        && !citation.locations().isEmpty());
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
        var plan = AssessmentFindings.plan(assessed.items(), evidence, session.index(), OutputLanguage.EN);
        AtomicFiles.write(directory.resolve("assessment-findings.md"), AssessmentFindings.markdown(plan, List.of(), OutputLanguage.EN));
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
        OutputLanguage language = OutputLanguage.EN;
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
        OutputLanguage language = OutputLanguage.EN;
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
        String trigger = EvidenceIndex.normalize(finding.trigger());
        String impact = EvidenceIndex.normalize(finding.impact());
        String premises = decision.support().premises().stream().map(ReviewSupport.Premise::statement)
                .filter(statement -> {
                    String normalized = EvidenceIndex.normalize(statement);
                    return !normalized.equals(trigger) && !normalized.equals(impact);
                })
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
