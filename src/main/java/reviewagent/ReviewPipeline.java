package reviewagent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

final class ReviewPipeline {
    private final ProcessExecutor executor;
    private final Clock clock;

    ReviewPipeline(ProcessExecutor executor, Clock clock) {
        this.executor = executor;
        this.clock = clock;
    }

    Path run(CliOptions options) throws IOException, InterruptedException {
        try (var progress = new CommandProgress("review", System.err)) {
            return run(options, progress);
        }
    }

    private Path run(CliOptions options, CommandProgress progress) throws IOException, InterruptedException {
        int totalSteps = options.prepareOnly() ? 3 : 9;
        progress.step(0, totalSteps, "Resolving Git review scope");
        GitClient git = new GitClient(options.repo(), executor);
        if (options.baseRemote() != null) {
            String target = git.resolveCommit(options.branch());
            Path runDirectory = options.resumeDirectory() == null
                    ? createRunDirectory(options.outputDir(), target) : options.resumeDirectory();
            progress.step(0, totalSteps, (options.resumeDirectory() == null ? "Fetching baseline " : "Restoring pinned baseline ")
                    + options.baseRemote() + "/" + options.base());
            try (var baseline = RemoteBaseline.open(options, executor, git, runDirectory)) {
                ReviewScope scope = new ReviewScope(ReviewScope.Mode.BRANCH, options.base(), options.branch(),
                        baseline.commit(), target, baseline.commit() + ".." + target);
                return runResolved(options, progress, totalSteps, git, scope, runDirectory);
            }
        }
        return runResolved(options, progress, totalSteps, git, git.resolve(options), null);
    }

    private Path runResolved(CliOptions options, CommandProgress progress, int totalSteps, GitClient git,
                             ReviewScope scope, Path preparedDirectory) throws IOException, InterruptedException {
        progress.step(1, totalSteps, "Collecting Git diff and review evidence");
        InputDocument requirement = InputDocument.read(options.requirement());
        InputDocument knowledge = InputDocument.read(options.knowledge());
        EvidenceBundle evidence = new EvidenceCollector(git).collect(
                scope, requirement, knowledge, options.knowledgeMode(), options.maxEvidenceBytes());
        String scopeText = scopeMarkdown(options, scope, requirement, knowledge);
        OutputLanguage reportLanguage = OutputLanguage.parse(options.language());
        Path runDirectory;
        boolean resume = options.resumeDirectory() != null;
        if (resume) {
            runDirectory = options.resumeDirectory();
            requireMatchingResumeFile(runDirectory.resolve("scope.md"), scopeText, true);
            requireMatchingResumeFile(runDirectory.resolve("evidence.md"), evidence.markdown(), true);
        } else {
            runDirectory = preparedDirectory == null
                    ? createRunDirectory(options.outputDir(), scope.targetCommit()) : preparedDirectory;
        }

        write(runDirectory.resolve("scope.md"), scopeText);
        if (!resume || options.prepareOnly()) {
            write(runDirectory.resolve("evidence.md"), evidence.markdown());
            write(runDirectory.resolve("evidence-index.md"), evidence.catalog().manifest());
            write(runDirectory.resolve("coverage.md"), evidence.coverage());
        }

        if (evidence.changedFiles().isEmpty()) {
            write(runDirectory.resolve("report.md"), ReviewReport.emptyDiffReport(scope, reportLanguage, requirement != null));
            write(runDirectory.resolve("review-status.md"), "Status: SKIPPED\n\nNo changes to review; acceptance was not assessed.\n");
            write(runDirectory.resolve("coverage.md"), evidence.coverage()
                    + "\nReview and requirement assessment skipped: the baseline and target have no file differences. No model was called by this execution.\n");
            if (requirement != null) {
                write(runDirectory.resolve("requirement-assessment.md"), ReviewReport.emptyDiffAssessment(OutputLanguage.EN, true) + "\n");
            }
            progress.finish(totalSteps, "No changes between baseline and target; wrote report.md without model calls");
            return runDirectory;
        }

        PromptFactory prompts = new PromptFactory(options.maxSupplementalRequests(), options.maxKnowledgeQueries());
        var discoveryFocuses = DiscoveryEvidence.Focus.active(options);
        String discoveryPrompt = prompts.discovery(evidence);
        write(runDirectory.resolve("discover-prompt.md"), discoveryPrompt);
        boolean renderLocally = requirement == null && knowledge == null;
        if (options.prepareOnly()) {
            progress.step(2, totalSteps, "Preparing verification prompt");
            var selection = AdaptiveReview.select(options, git, evidence, true);
            if (selection.route() == AdaptiveReview.Route.REPOSITORY_RULES
                    && FocusedReview.prepareWithRepositoryRules(evidence, options, git, prompts, runDirectory)) {
                progress.finish(totalSteps, "Evidence and repository-rule prompts ready");
                return runDirectory;
            }
            Path discoveryDirectory = Files.createDirectories(runDirectory.resolve("discovery"));
            var plan = DeepDiscovery.plan(evidence, options, git, prompts, java.util.Set.of());
            for (var request : discoveryTasks(plan, options, prompts, discoveryFocuses, renderLocally, discoveryDirectory)) {
                var task = request.task();
                write(discoveryDirectory.resolve(task.id() + "-prompt.md"), task.prompt());
            }
            write(runDirectory.resolve("coverage.md"), evidence.coverage() + plan.coverage()
                    + (plan.gaps().isEmpty() ? "" : "\n" + String.join("\n", plan.gaps()) + "\n"));
            ReviewFinding example = new ReviewFinding("example", "[candidate title]", ReviewFinding.Severity.MEDIUM,
                    evidence.changedFiles().stream().findFirst().orElse("[changed file]"), 1, 1,
                    "[trigger]", "[impact]", "[source evidence]", "[diff relationship]");
            write(runDirectory.resolve("verify-prompt-template.md"),
                    prompts.verifyFinding(evidence, example, "[supplemental evidence]"));
            write(runDirectory.resolve("adjudication-prompt-template.md"),
                    prompts.adjudicate(evidence, example, List.of(), "[supplemental evidence]"));
            if (requirement != null) {
                write(runDirectory.resolve("requirement-assessment-prompt-template.md"),
                        prompts.assessRequirements(evidence, "[supplemental evidence]", "[candidate verdicts]", "None", false));
                write(runDirectory.resolve("requirement-audit-prompt-template.md"),
                        prompts.assessRequirements(evidence, "[supplemental evidence]", "[candidate verdicts]", "[draft assessment]", true));
            }
            write(runDirectory.resolve("report-prompt-template.md"),
                    prompts.finalizeReport(evidence, List.of(), "[discovery notes]", "[supplemental evidence]", "[verification results]"));
            progress.finish(totalSteps, "Evidence and prompts ready");
            return runDirectory;
        }

        OutputLanguage outputLanguage = OutputLanguage.EN;
        var executionIssues = new ArrayList<String>();
        List<ReviewFinding> published = List.of();
        List<ReviewOutput.Decision> verifiedSnapshot = List.of();
        List<ReviewOutput.Decision> adjudicatedSnapshot = List.of();
        List<List<String>> groupsSnapshot = List.of();
        AssessmentFindings.Plan planSnapshot = AssessmentFindings.Plan.empty();
        String assessmentSnapshot = requirement == null ? ReviewReport.assessmentMarkdown(List.of(), outputLanguage)
                : outputLanguage.text("Not completed: no reconciled requirement assessment is available from this execution.",
                    "未完成：本次执行尚未产出经过复核和结论对齐的需求验收结果。");
        if (Files.isRegularFile(runDirectory.resolve("report.md")))
            AtomicFiles.copy(runDirectory.resolve("report.md"), runDirectory.resolve("report-previous.md"));
        write(runDirectory.resolve("review-status.md"), "Status: RUNNING\n");
        write(runDirectory.resolve("report.md"), outputLanguage.text("Review running. Previous reports do not describe this execution.\n",
                "审查进行中。此前的报告不代表本次执行结果。\n"));
        AgentRunner agent = new AgentRunner(executor, options.agentCommand(), options.agentArgs(),
                options.repo(), options.agentTimeout(), options.agentPolicy(), options.reviewParallelism());
        Throwable executionFailure = null;
        try {
            progress.step(1, totalSteps, "Resolving model prompt limits");
            var resolver = new ModelPromptBudget(clock);
            var standard = resolver.resolve(agent.commandForModel(null), options.maxPromptBytes());
            agent.promptBudget(standard);
            StringBuilder limits = new StringBuilder(promptLimit("Default model", standard));
            write(runDirectory.resolve("model-prompt-limits.md"), limits.toString());
            var selection = resume && Files.isDirectory(runDirectory.resolve("discovery"))
                    ? AdaptiveReview.Selection.deep("Resuming the recorded deep workflow")
                    : AdaptiveReview.select(options, git, evidence, !resume || !Files.isDirectory(runDirectory.resolve("focused")));
            String routeReason = selection.reason();
            if (selection.route() == AdaptiveReview.Route.REPOSITORY_RULES) {
                routeReason = FocusedReview.runWithRepositoryRules(evidence, options, git, executor, prompts, agent,
                        runDirectory, progress, totalSteps);
                if (routeReason == null) return publishReport(runDirectory, reportLanguage, agent, progress, totalSteps, resume);
            }
            if (selection.route() == AdaptiveReview.Route.FOCUSED) {
                routeReason = FocusedReview.run(evidence, options, git, executor, prompts, agent, runDirectory, progress, totalSteps);
                if (routeReason == null) return publishReport(runDirectory, reportLanguage, agent, progress, totalSteps, resume);
            }
            if (selection.route() == AdaptiveReview.Route.DOCUMENTATION) {
                routeReason = DocumentationReview.run(selection.evidence(), options, agent, runDirectory, progress, totalSteps);
                if (routeReason == null) return publishReport(runDirectory, reportLanguage, agent, progress, totalSteps, resume);
            }
            if (selection.route() == AdaptiveReview.Route.LOCAL) {
                write(runDirectory.resolve("coverage.md"), evidence.coverage()
                        + "\n## Review route\n\n- adaptive — " + routeReason + ".\n");
                routeReason = AdaptiveReview.run(selection.evidence(), options, git, executor, prompts, agent, runDirectory,
                        progress, totalSteps, routeReason);
                if (routeReason == null) return publishReport(runDirectory, reportLanguage, agent, progress, totalSteps, resume);
            }
            Path discoveryDirectory = runDirectory.resolve("discovery");
            int discoveryLimit = standard.bytes();
            if (!options.discoveryModels().isEmpty()) {
                discoveryLimit = Integer.MAX_VALUE;
                var byModel = new java.util.LinkedHashMap<String, ModelPromptBudget.Limit>();
                for (var focus : discoveryFocuses) {
                    String model = options.discoveryModels().get(focus.modelSlot);
                    var limit = byModel.get(model);
                    if (limit == null) {
                        limit = resolver.resolve(agent.commandForModel(model), options.maxPromptBytes());
                        byModel.put(model, limit);
                        agent.promptBudget(model, limit);
                    }
                    limits.append(promptLimit(focus.id, limit));
                    discoveryLimit = Math.min(discoveryLimit, limit.bytes());
                }
                write(runDirectory.resolve("model-prompt-limits.md"), limits.toString());
            }
            var plan = DeepDiscovery.plan(evidence, options, git, prompts, java.util.Set.of(), discoveryLimit);
            var deepEvidence = plan.evidence();
            int discoveryParallelism = agent.phaseParallelism(options, discoveryFocuses.size(), discoveryFocuses.stream()
                    .map(focus -> options.discoveryModels().isEmpty() ? null : options.discoveryModels().get(focus.modelSlot))
                    .distinct().toList());
            String route = "\n## Review route\n\n- deep — " + routeReason + ".\n"
                    + "- Initial discovery roles: " + discoveryFocuses.stream().map(focus -> focus.id + " " + focus.name())
                            .collect(java.util.stream.Collectors.joining(", ")) + ".\n"
                    + "- Initial discovery parallelism limit: " + discoveryParallelism + ".\n" + plan.coverage();
            write(runDirectory.resolve("coverage.md"), evidence.coverage() + route);
            write(runDirectory.resolve("evidence-index.md"), deepEvidence.catalog().manifest());
            var discoveries = discoveryTasks(plan, options, prompts, discoveryFocuses, renderLocally, discoveryDirectory);
            executionIssues.addAll(plan.gaps());
            if (discoveries.isEmpty()) throw new ReviewStageException("No deep discovery request fit the evidence budget");
            progress.step(2, totalSteps, "Independent reviewers (0 of " + discoveries.size() + " complete)");
            EvidenceSession supplemental = new EvidenceSession(deepEvidence, new SupplementalEvidence(git, executor, options.languageCaches()),
                    options.maxSupplementalRequests(), options.maxKnowledgeQueries());
            var responses = InitialDiscovery.run(discoveries, supplemental, prompts, options, agent, discoveryDirectory,
                    discoveryFocuses.size(), Math.min(discoveryLimit, options.maxEvidenceBytes()), progress, totalSteps, resume);
            var findings = new ArrayList<ReviewFinding>();
            StringBuilder discoveryNotes = new StringBuilder();
            var changeSummaries = new java.util.LinkedHashSet<String>();
            var notesByReviewer = new LinkedHashMap<String, String>();
            var requestsByCandidate = new LinkedHashMap<String, List<EvidenceRequest>>();
            var questionsByCandidate = new LinkedHashMap<String, String>();
            StringBuilder rawCandidates = new StringBuilder();
            for (var response : responses) {
                String id = response.id();
                var outcome = response.outcome();
                if (!response.gap().isEmpty()) {
                    executionIssues.add(id + " — " + response.gap());
                    continue;
                }
                if (!outcome.valid()) {
                    Path path = runDirectory.relativize(response.directory()).resolve(id);
                    executionIssues.add(path + " [" + outcome.failureKind() + "]; see " + path + "-state.properties");
                    Path saved = response.directory().resolve(id + "-response.md");
                    if (outcome.failureKind().equals("INVALID_OUTPUT") && Files.isRegularFile(saved)) {
                        String reply = Files.readString(saved);
                        if (supplemental.requestOnly(reply)) {
                            notesByReviewer.put(id, reply);
                            discoveryNotes.append("\n## Evidence requests from unfinished reviewer ")
                                    .append(id).append("\n\n").append(reply);
                        }
                    }
                    continue;
                }
                ReviewOutput.Discovery parsed = response.discovery();
                findings.addAll(parsed.findings());
                requestsByCandidate.putAll(parsed.candidateRequests());
                questionsByCandidate.putAll(parsed.candidateQuestions());
                if (renderLocally && (id.equals("R1") || id.startsWith("R1-P"))
                        && !parsed.changeSummary().isBlank()) changeSummaries.add(parsed.changeSummary());
                notesByReviewer.put(id, parsed.notes());
                discoveryNotes.append("\n## ").append(id).append("\n\n").append(parsed.notes());
                rawCandidates.append("\n## ").append(id).append("\n\n").append(outcome.response()).append('\n');
            }
            if (responses.stream().noneMatch(InitialDiscovery.Result::completed)) {
                write(runDirectory.resolve("coverage.md"), evidence.coverage() + route + supplemental.coverage());
                write(runDirectory.resolve("supplemental-evidence.md"), supplemental.markdown());
                throw new ReviewStageException("All independent discovery reviewers failed; see discovery task artifacts");
            }
            String candidates = rawCandidates.toString();

            progress.step(3, totalSteps, "Loading requested evidence");
            var preflightSources = supplemental.catalog().sources().stream().map(EvidenceSource::id)
                    .collect(java.util.stream.Collectors.toSet());
            boolean discoveryMaterial = supplemental.load(discoveryNotes.toString(), "Discovery evidence requests");
            var requestedOutsidePartition = new java.util.LinkedHashSet<String>();
            if (plan.batches().size() > 1) {
                for (var response : responses)
                    requestedOutsidePartition.addAll(supplemental.delivery(notesByReviewer.getOrDefault(response.id(), ""), response.evidence()));
            }
            write(runDirectory.resolve("scope.md"), scopeText);
            write(runDirectory.resolve("candidates.md"), candidates);
            write(runDirectory.resolve("supplemental-evidence.md"), supplemental.markdown());
            ReviewQuality quality = new ReviewQuality(options, deepEvidence, supplemental, prompts, agent, runDirectory, progress, totalSteps);
            quality.saveEvidence();

            progress.step(4, totalSteps, "Reviewing supplemental evidence");
            if (discoveryMaterial || !requestedOutsidePartition.isEmpty()) {
                EvidenceCatalog available = supplemental.catalog();
                var fresh = available.sources().stream().map(EvidenceSource::id)
                        .filter(id -> !preflightSources.contains(id)).collect(java.util.stream.Collectors.toSet());
                fresh.addAll(requestedOutsidePartition);
                var augmented = new EvidenceBundle(deepEvidence.scope(), deepEvidence.changedFiles(),
                        deepEvidence.markdown() + supplemental.markdown(), deepEvidence.coverage(), 0, deepEvidence.scopedKnowledge(), available);
                var followupPlan = DeepDiscovery.plan(augmented, options, git, prompts, fresh, standard.bytes());
                var tasks = new ArrayList<ReviewTasks.Task>();
                for (var batch : followupPlan.batches()) {
                    String id = partitionId("S1", tasks.size(), followupPlan.batches().size());
                    tasks.add(new ReviewTasks.Task(id, prompts.supplementalDiscovery(batch),
                            ReviewTasks.Validator.singleResponse(reply -> ReviewOutput.discovery(reply, id, batch.changedFiles()))));
                }
                executionIssues.addAll(followupPlan.gaps());
                if (!followupPlan.coverage().isEmpty()) route += "\nSupplemental requests:\n" + followupPlan.coverage();
                var followup = ReviewTasks.runPhase(tasks, agent, discoveryDirectory, options, tasks.size(), 4, totalSteps,
                        "SUPPLEMENTAL_DISCOVERY", "Supplemental evidence reviewer", progress, resume);
                for (var entry : followup.entrySet()) {
                    String id = entry.getKey();
                    var outcome = entry.getValue();
                    if (!outcome.valid()) {
                        executionIssues.add("discovery/" + id + " [" + outcome.failureKind() + "]; see discovery/" + id + "-state.properties");
                        continue;
                    }
                    String response = outcome.response();
                    ReviewOutput.Discovery parsed = ReviewOutput.discovery(response, id, evidence.changedFiles());
                    findings.addAll(parsed.findings());
                    requestsByCandidate.putAll(parsed.candidateRequests());
                    questionsByCandidate.putAll(parsed.candidateQuestions());
                    discoveryNotes.append("\n## ").append(id).append("\n\n").append(parsed.notes());
                    rawCandidates.append("\n## ").append(id).append("\n\n").append(response).append('\n');
                    supplemental.load(parsed.notes(), "Supplemental discovery evidence requests");
                }
                write(runDirectory.resolve("candidates.md"), rawCandidates.toString());
                quality.saveEvidence();
            }

            progress.step(5, totalSteps, findings.isEmpty() ? "No candidate findings to verify"
                    : "Verifying candidate issues (0 of " + findings.size() + " complete)");
            List<ReviewOutput.Decision> preliminary = quality.verify(findings,
                    supplemental.verificationBriefs(questionsByCandidate, requestsByCandidate));
            verifiedSnapshot = preliminary;
            String verificationCoverage = ReviewQuality.coverage("Individual candidate verification", preliminary);
            verificationCoverage += "\nCandidates: " + findings.size() + ". Confirmed before deduplication: "
                    + preliminary.stream().filter(d -> d.verdict().status() == ReviewOutput.Status.CONFIRMED).count() + ".\n";
            write(runDirectory.resolve("coverage.md"), evidence.coverage() + route + supplemental.coverage() + verificationCoverage);

            progress.step(6, totalSteps, "Assessing and independently auditing requirements");
            ReviewQuality.AssessmentResult assessed = quality.assess(requirement, verificationCoverage);

            progress.step(7, totalSteps, "Verifying unmet requirements and reconciling verdicts before publication");
            AssessmentFindings.Plan assessmentPlan = quality.planAssessment(assessed);
            planSnapshot = assessmentPlan;
            List<ReviewOutput.Decision> requirementDecisions = quality.verifyAssessment(assessmentPlan);
            preliminary = java.util.stream.Stream.concat(preliminary.stream(), requirementDecisions.stream()).toList();
            verifiedSnapshot = preliminary;
            verificationCoverage += ReviewQuality.coverage("Unmet requirement candidate verification", requirementDecisions);
            verificationCoverage += "\nCandidates including unmet requirements: " + preliminary.size() + ". Confirmed before publication: "
                    + preliminary.stream().filter(d -> d.verdict().status() == ReviewOutput.Status.CONFIRMED).count() + ".\n";
            write(runDirectory.resolve("coverage.md"), evidence.coverage() + route + supplemental.coverage() + verificationCoverage);
            var requirementCandidates = requirementDecisions.stream().map(decision -> decision.verdict().candidate().id())
                    .collect(java.util.stream.Collectors.toSet());
            ReviewQuality.Adjudication publication = quality.adjudicate(preliminary, assessed.details(), requirementCandidates);
            List<ReviewOutput.Decision> adjudicated = publication.decisions();
            adjudicatedSnapshot = adjudicated;
            groupsSnapshot = publication.groups();
            List<ReviewConcerns.Item> concerns = ReviewConcerns.collect(preliminary, adjudicated, publication.groups());
            verificationCoverage += publication.groupingNote() + ReviewQuality.coverage("Publication adjudication", adjudicated);
            write(runDirectory.resolve("coverage.md"), evidence.coverage() + route + supplemental.coverage() + verificationCoverage);
            OutputLanguage language = OutputLanguage.EN;
            var reportDecisions = PublicationGate.enabledFor(options)
                    ? PublicationGate.reportDecisions(adjudicated, publication.groups(), supplemental.index(), scope.targetCommit())
                    : adjudicated;
            List<ReviewFinding> confirmed = reportDecisions.stream()
                    .filter(d -> d.verdict().status() == ReviewOutput.Status.CONFIRMED)
                    .map(d -> ReviewQuality.publishedFinding(d, language)).toList();
            published = confirmed;
            write(runDirectory.resolve("verified-findings.md"), confirmed.stream().map(finding -> finding.markdown(language))
                    .collect(java.util.stream.Collectors.joining()));
            assessed = quality.reconcileAssessment(assessed, assessmentPlan, preliminary, adjudicated);
            String assessment = assessed.summary();
            assessmentSnapshot = assessment;
            ReviewQuality.Completion completion = ReviewQuality.completion(preliminary, adjudicated, assessmentPlan, executionIssues, language);

            progress.step(8, totalSteps, "Preparing the report");
            String report;
            if (renderLocally) {
                report = ReviewReport.deepReport(evidence, String.join(" ", changeSummaries), confirmed, publication.groups(), concerns, language);
            } else {
                String reportPrompt = prompts.finalizeReport(deepEvidence, confirmed, discoveryNotes.toString(),
                        supplemental.markdown(), verificationCoverage) + "\nAUDITED REQUIREMENT ASSESSMENT\n" + assessment
                        + "\nPROGRAMMATIC CANDIDATE COUNTS\nDiscovery: " + findings.size() + "; unmet requirements: "
                        + requirementDecisions.size() + "; total: " + preliminary.size()
                        + "\nPROGRAMMATIC REVIEW COMPLETENESS\n" + completion.markdown();
                reportPrompt += "\nPROGRAMMATIC REVIEW-REQUIRED ITEMS\n" + concerns.size()
                        + " items will be inserted by the CLI in Findings, separately from confirmed defects. "
                        + "Do not repeat candidate-by-candidate decisions elsewhere or describe these items as confirmed defects.\n";
                write(runDirectory.resolve("verify-prompt.md"), reportPrompt);
                var reportReplies = ReviewTasks.run(List.of(new ReviewTasks.Task("report", reportPrompt,
                        reply -> ReviewReport.finalReport(reply, confirmed, language, assessment, concerns))), agent, runDirectory,
                        options.reviewParallelism(), progress, 8, totalSteps, "Finalizing report", resume);
                report = ReviewReport.finalReport(reportReplies.get("report"), confirmed, language, assessment, concerns);
            }
            write(runDirectory.resolve("report.md"), completion.notice() + report);
            write(runDirectory.resolve("review-status.md"), completion.markdown());
            return publishReport(runDirectory, reportLanguage, agent, progress, totalSteps, resume);
        } catch (ReviewStageException incomplete) {
            executionIssues.add(incomplete.reportMessage(runDirectory));
            var completion = ReviewQuality.completion(verifiedSnapshot, adjudicatedSnapshot, planSnapshot, executionIssues, outputLanguage);
            var concerns = ReviewConcerns.collect(verifiedSnapshot, adjudicatedSnapshot, groupsSnapshot);
            write(runDirectory.resolve("report.md"), completion.notice()
                    + ReviewReport.incompleteReport(scope, outputLanguage, published, concerns, assessmentSnapshot, executionIssues));
            write(runDirectory.resolve("review-status.md"), completion.markdown());
            return publishReport(runDirectory, reportLanguage, agent, progress, totalSteps, resume);
        } catch (IOException | InterruptedException fatal) {
            executionFailure = fatal;
            boolean interrupted = Thread.interrupted();
            try {
                String status = fatal instanceof InterruptedException ? "CANCELLED" : "FAILED";
                write(runDirectory.resolve("review-status.md"), "Status: " + status + "\n\n" + EvidenceText.oneLine(fatal.getMessage()) + "\n");
                write(runDirectory.resolve("report.md"), outputLanguage.text("> **Review did not complete.** See review-status.md.\n\n",
                        "> **审查未完成。** 详见 review-status.md。\n\n")
                        + ReviewReport.incompleteReport(scope, outputLanguage, published,
                            ReviewConcerns.collect(verifiedSnapshot, adjudicatedSnapshot, groupsSnapshot), assessmentSnapshot, List.of(status)));
            } catch (IOException logging) { fatal.addSuppressed(logging); }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
            throw fatal;
        } finally {
            boolean interrupted = Thread.interrupted();
            try {
                agent.saveConcurrencyHistory();
                agent.timings().write(runDirectory, outputLanguage);
            }
            catch (IOException diagnostics) {
                if (executionFailure != null) executionFailure.addSuppressed(diagnostics);
                else throw diagnostics;
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }

    private static Path publishReport(Path directory, OutputLanguage language, AgentRunner agent, CommandProgress progress,
                                      int stages, boolean resume) throws IOException, InterruptedException {
        if (!ReportTranslation.publish(directory, language, agent, progress, stages, resume)) {
            progress.finish(stages, "Report translation failed. English report retained in report.md");
        } else {
            progress.finish(stages, Main.reviewExitCode(directory) == 0 ? "Wrote report.md" : "Wrote incomplete report.md");
        }
        return directory;
    }

    private static List<InitialDiscovery.Request> discoveryTasks(DeepDiscovery plan, CliOptions options, PromptFactory prompts,
                                                         List<DiscoveryEvidence.Focus> focuses, boolean summarize,
                                                         Path directory) throws IOException {
        var tasks = new ArrayList<InitialDiscovery.Request>();
        for (var focus : focuses) {
            String model = options.discoveryModels().isEmpty() ? null : options.discoveryModels().get(focus.modelSlot);
            boolean requestSummary = summarize && focus == DiscoveryEvidence.Focus.CORRECTNESS;
            for (int j = 0; j < plan.batches().size(); j++) {
                var batch = plan.batches().get(j);
                String id = partitionId(focus.id, j, plan.batches().size());
                var view = DiscoveryEvidence.forFocus(batch, focus);
                String prompt = prompts.independentDiscovery(view, focus, requestSummary);
                Path savedPrompt = directory.resolve(id + "-prompt.md");
                if (options.resumeDirectory() != null && Files.isRegularFile(savedPrompt)) {
                    // Preserve checkpoints with the former shared view or before the optional summary.
                    String saved = Files.readString(savedPrompt, StandardCharsets.UTF_8);
                    if (!saved.equals(prompt) && (view != batch && saved.equals(prompts.independentDiscovery(batch, focus,
                            requestSummary)) || requestSummary && saved.equals(prompts.independentDiscovery(batch, focus, false)))) prompt = saved;
                }
                var task = new ReviewTasks.Task(id, prompt, InitialDiscovery.validator(id, batch.changedFiles()), model);
                tasks.add(new InitialDiscovery.Request(task, view, batch.catalog(), focus, requestSummary));
            }
        }
        return tasks;
    }

    private static String partitionId(String reviewer, int index, int count) {
        return count == 1 ? reviewer : reviewer + "-P" + (index + 1);
    }

    private static String promptLimit(String role, ModelPromptBudget.Limit limit) {
        var capabilities = limit.capabilities();
        return "- " + role + ": " + limit.bytes() + " UTF-8 bytes (" + limit.source() + ")"
                + (capabilities == null || !capabilities.hasPromptLimits() ? "" : ", " + capabilities.inputTokens() + " input tokens, "
                + capabilities.outputTokens() + " output tokens reported by the service")
                + (capabilities == null || capabilities.maxConcurrentRequests() == 0 ? ""
                    : ", " + capabilities.maxConcurrentRequests() + " concurrent requests reported by the service")
                + "\n";
    }

    private static void requireMatchingResumeFile(Path path, String expected, boolean required) throws IOException {
        if (!Files.isRegularFile(path)) {
            if (required) {
                throw new IOException("Cannot resume without " + path);
            }
            return;
        }
        String previous = Files.readString(path, StandardCharsets.UTF_8);
        if (path.getFileName().toString().equals("scope.md")) {
            previous = previous.replaceAll("(?m)^- (Review|Deep candidate verification) parallelism:.*\\R?", "");
            expected = expected.replaceAll("(?m)^- (Review|Deep candidate verification) parallelism:.*\\R?", "");
            previous = previous.replaceAll("(?m)^- (Verification evidence|Output language|Report language):.*\\R?", "");
            expected = expected.replaceAll("(?m)^- (Verification evidence|Output language|Report language):.*\\R?", "");
        }
        if (!previous.equals(expected)) {
            throw new IOException("Cannot resume because " + path.getFileName()
                    + " differs from the saved run. Use the original revisions, documents, evidence settings and discovery model assignments.");
        }
    }

    private Path createRunDirectory(Path root, String targetCommit) throws IOException {
        String timestamp = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss")
                .format(OffsetDateTime.now(clock).atZoneSameInstant(ZoneId.systemDefault()));
        Files.createDirectories(root);
        String name = timestamp + "-" + targetCommit.substring(0, 12);
        for (int attempt = 1; ; attempt++) {
            Path directory = root.resolve(name + (attempt == 1 ? "" : "-" + attempt));
            try {
                return Files.createDirectory(directory);
            } catch (java.nio.file.FileAlreadyExistsException collision) {
                // Concurrent reviews of the same target must not overwrite each other's pinned scope or logs.
            }
        }
    }

    private static String scopeMarkdown(CliOptions options, ReviewScope scope,
                                        InputDocument requirement, InputDocument knowledge) {
        return """
                # Review scope

                - Repository: `%s`
                - Mode: `%s`
                - Requested base: `%s`
                - Requested target: `%s`
                - Resolved base: `%s`
                - Resolved target: `%s`
                - Diff: `%s`
                - Requirement: %s
                - Knowledge base: %s
                - Knowledge mode: %s
                - File and language request count limit: %s
                - Knowledge query count limit: %s
                - Review parallelism: %s
                - Deep candidate verification parallelism: %s
                - Verification evidence: %s
                - Report language: %s
                - Dynamic verification: not run
                """.formatted(options.repo(), scope.mode(), scope.requestedBase(), scope.requestedTarget(),
                scope.baseCommit(), scope.targetCommit(), scope.diffExpression(), describe(requirement), describe(knowledge),
                options.knowledgeMode().name().toLowerCase(Locale.ROOT),
                options.maxSupplementalRequests() == 0 ? "unlimited" : options.maxSupplementalRequests(),
                options.maxKnowledgeQueries() == 0 ? "unlimited" : options.maxKnowledgeQueries(),
                parallelismDescription(options), parallelismDescription(options),
                options.verificationEvidence().name().toLowerCase(Locale.ROOT), options.language())
                + (options.baseRemote() == null ? "" : "- Baseline remote: `" + options.baseRemote() + "`\n"
                    + "- Baseline remote branch: `" + RemoteBaseline.branchRef(options.base()) + "`\n")
                + (options.discoveryModels().isEmpty() ? "" : "- Discovery models (R1, R2, R3): "
                    + String.join(", ", options.discoveryModels()) + "\n")
                + (options.maxPromptBytes() == null ? "" : "- Configured prompt budget fallback: "
                    + options.maxPromptBytes() + " UTF-8 bytes\n")
                + (options.focusedJsonSchema() ? "- Focused risk response: JSON Schema\n" : "")
                + (options.batchedVerification() ? "- Deep verification scheduling: batched\n" : "")
                + "- Language modules: " + LanguageRegistry.defaults().modules().stream().map(LanguageModule::id)
                        .collect(java.util.stream.Collectors.joining(", ")) + "\n"
                + "- Language capabilities: " + LanguageRegistry.defaults().capabilitySummary() + "\n"
                + "- Language cache overrides: " + options.languageCaches().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                        .map(entry -> entry.getKey() + "=`" + entry.getValue() + "`")
                        .collect(java.util.stream.Collectors.joining(", ")) + "\n";
    }

    private static String parallelismDescription(CliOptions options) {
        if (!options.adaptiveParallelism()) return Integer.toString(options.reviewParallelism());
        return options.reviewParallelismLimit() == null ? "auto (service and outstanding work)"
                : "auto (maximum " + options.reviewParallelismLimit() + ")";
    }

    private static String describe(InputDocument document) {
        return document == null ? "not provided" : "`" + document.path() + "` (SHA-256 `" + document.sha256() + "`)";
    }

    private static void write(Path path, String content) throws IOException {
        AtomicFiles.write(path, content);
    }
}

