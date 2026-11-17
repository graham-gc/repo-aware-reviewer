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

        if (!resume || options.prepareOnly()) {
            write(runDirectory.resolve("scope.md"), scopeText);
            write(runDirectory.resolve("evidence.md"), evidence.markdown());
            write(runDirectory.resolve("evidence-index.md"), evidence.catalog().manifest());
            write(runDirectory.resolve("coverage.md"), evidence.coverage());
        }

        if (evidence.changedFiles().isEmpty()) {
            OutputLanguage language = OutputLanguage.parse(options.language());
            write(runDirectory.resolve("report.md"), ReviewReport.emptyDiffReport(scope, language, requirement != null));
            write(runDirectory.resolve("review-status.md"), "Status: SKIPPED\n\n"
                    + language.text("No changes to review; acceptance was not assessed.", "没有需要审查的变更，未进行需求验收。") + "\n");
            write(runDirectory.resolve("coverage.md"), evidence.coverage()
                    + "\nReview and requirement assessment skipped: the baseline and target have no file differences. No model was called by this execution.\n");
            if (requirement != null) {
                write(runDirectory.resolve("requirement-assessment.md"), ReviewReport.emptyDiffAssessment(language, true) + "\n");
            }
            progress.finish(totalSteps, "No changes between baseline and target; wrote report.md without model calls");
            return runDirectory;
        }

        PromptFactory prompts = new PromptFactory(
                options.language(), options.maxSupplementalRequests(), options.maxKnowledgeQueries());
        String discoveryPrompt = prompts.discovery(evidence);
        write(runDirectory.resolve("discover-prompt.md"), discoveryPrompt);
        List<ReviewTasks.Task> discoveries = new ArrayList<>();
        for (int i = 0; i < PromptFactory.REVIEW_FOCUSES.size(); i++) {
            String reviewer = "R" + (i + 1);
            discoveries.add(new ReviewTasks.Task(reviewer,
                    prompts.independentDiscovery(evidence, PromptFactory.REVIEW_FOCUSES.get(i)),
                    reply -> ReviewOutput.discovery(reply, reviewer, evidence.changedFiles())));
        }
        if (options.prepareOnly()) {
            progress.step(2, totalSteps, "Preparing verification prompt");
            Path discoveryDirectory = Files.createDirectories(runDirectory.resolve("discovery"));
            for (ReviewTasks.Task task : discoveries) {
                write(discoveryDirectory.resolve(task.id() + "-prompt.md"), task.prompt());
            }
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

        OutputLanguage outputLanguage = OutputLanguage.parse(options.language());
        var executionIssues = new ArrayList<String>();
        List<ReviewFinding> published = List.of();
        String assessmentSnapshot = requirement == null ? ReviewReport.assessmentMarkdown(List.of(), outputLanguage)
                : outputLanguage.text("Not completed: no reconciled requirement assessment is available from this execution.",
                    "未完成：本次执行尚未产出经过复核和结论对齐的需求验收结果。");
        if (Files.isRegularFile(runDirectory.resolve("report.md")))
            AtomicFiles.copy(runDirectory.resolve("report.md"), runDirectory.resolve("report-previous.md"));
        write(runDirectory.resolve("review-status.md"), "Status: RUNNING\n");
        write(runDirectory.resolve("report.md"), outputLanguage.text("Review running. Previous reports do not describe this execution.\n",
                "审查进行中。此前的报告不代表本次执行结果。\n"));
        AgentRunner agent = new AgentRunner(executor, options.agentCommand(), options.agentArgs(),
                options.repo(), options.agentTimeout(), options.agentPolicy());
        Throwable executionFailure = null;
        try {
            progress.step(2, totalSteps, "Independent reviewers (0 of " + discoveries.size() + " complete)");
            var responses = ReviewTasks.runIsolated(discoveries, agent, runDirectory.resolve("discovery"), options.reviewParallelism(),
                    progress, 2, totalSteps, "Independent reviewers", resume);
            var findings = new ArrayList<ReviewFinding>();
            StringBuilder discoveryNotes = new StringBuilder();
            StringBuilder rawCandidates = new StringBuilder();
            for (var response : responses.entrySet()) {
                var outcome = response.getValue();
                if (!outcome.valid()) {
                    executionIssues.add("discovery/" + response.getKey() + " [" + outcome.failureKind() + "]; see discovery/"
                            + response.getKey() + "-state.properties");
                    continue;
                }
                ReviewOutput.Discovery parsed = ReviewOutput.discovery(outcome.response(), response.getKey(), evidence.changedFiles());
                findings.addAll(parsed.findings());
                discoveryNotes.append("\n## ").append(response.getKey()).append("\n\n").append(parsed.notes());
                rawCandidates.append("\n## ").append(response.getKey()).append("\n\n").append(outcome.response()).append('\n');
            }
            if (responses.values().stream().noneMatch(ReviewTasks.Outcome::valid)) {
                throw new ReviewStageException("All independent discovery reviewers failed; see discovery task artifacts");
            }
            String candidates = rawCandidates.toString();

            progress.step(3, totalSteps, "Loading requested evidence");
            EvidenceSession supplemental = new EvidenceSession(evidence, new SupplementalEvidence(git, executor, options.languageCaches()),
                    options.maxSupplementalRequests(), options.maxKnowledgeQueries());
            boolean discoveryMaterial = supplemental.load(discoveryNotes.toString(), "Discovery evidence requests");
            write(runDirectory.resolve("scope.md"), scopeText);
            write(runDirectory.resolve("candidates.md"), candidates);
            write(runDirectory.resolve("supplemental-evidence.md"), supplemental.markdown());
            ReviewQuality quality = new ReviewQuality(options, evidence, supplemental, prompts, agent, runDirectory, progress, totalSteps);
            quality.saveEvidence();

            progress.step(4, totalSteps, "Reviewing supplemental evidence");
            if (discoveryMaterial) {
                EvidenceBundle augmented = new EvidenceBundle(evidence.scope(), evidence.changedFiles(),
                        evidence.markdown() + supplemental.markdown(), evidence.coverage(), 0, evidence.scopedKnowledge(), supplemental.catalog());
                String prompt = prompts.independentDiscovery(augmented, """
                        Inspect the supplemental evidence and newly supplied business rules for concrete regressions.
                        This pass runs after evidence collection. Use the material already supplied and record remaining
                        evidence gaps in prose. Do not issue additional evidence requests in this pass.
                        """);
                var followup = ReviewTasks.runIsolated(List.of(new ReviewTasks.Task("S1", prompt,
                        reply -> ReviewOutput.discovery(reply, "S1", evidence.changedFiles()))), agent,
                        runDirectory.resolve("discovery"), options.reviewParallelism(), progress, 4, totalSteps,
                        "Supplemental evidence reviewer", resume);
                if (!followup.get("S1").valid()) {
                    executionIssues.add("discovery/S1 [" + followup.get("S1").failureKind() + "]; see discovery/S1-state.properties");
                } else {
                    String response = followup.get("S1").response();
                    ReviewOutput.Discovery parsed = ReviewOutput.discovery(response, "S1", evidence.changedFiles());
                    findings.addAll(parsed.findings());
                    discoveryNotes.append("\n## S1\n\n").append(parsed.notes());
                    rawCandidates.append("\n## S1\n\n").append(response).append('\n');
                    write(runDirectory.resolve("candidates.md"), rawCandidates.toString());
                    supplemental.load(parsed.notes(), "Supplemental discovery evidence requests");
                    quality.saveEvidence();
                }
            }

            progress.step(5, totalSteps, findings.isEmpty() ? "No candidate findings to verify"
                    : "Verifying candidate issues (0 of " + findings.size() + " complete)");
            List<ReviewOutput.Decision> preliminary = quality.verify(findings);
            String verificationCoverage = ReviewQuality.coverage("Individual candidate verification", preliminary);
            verificationCoverage += "\nCandidates: " + findings.size() + ". Confirmed before deduplication: "
                    + preliminary.stream().filter(d -> d.verdict().status() == ReviewOutput.Status.CONFIRMED).count() + ".\n";

            progress.step(6, totalSteps, "Assessing and independently auditing requirements");
            ReviewQuality.AssessmentResult assessed = quality.assess(requirement, verificationCoverage);

            progress.step(7, totalSteps, "Verifying unmet requirements and reconciling verdicts before publication");
            AssessmentFindings.Plan assessmentPlan = quality.planAssessment(assessed);
            List<ReviewOutput.Decision> requirementDecisions = quality.verifyAssessment(assessmentPlan);
            preliminary = java.util.stream.Stream.concat(preliminary.stream(), requirementDecisions.stream()).toList();
            verificationCoverage += ReviewQuality.coverage("Unmet requirement candidate verification", requirementDecisions);
            verificationCoverage += "\nCandidates including unmet requirements: " + preliminary.size() + ". Confirmed before publication: "
                    + preliminary.stream().filter(d -> d.verdict().status() == ReviewOutput.Status.CONFIRMED).count() + ".\n";
            ReviewQuality.Adjudication publication = quality.adjudicate(preliminary, assessed.details());
            List<ReviewOutput.Decision> adjudicated = publication.decisions();
            List<ReviewConcerns.Item> concerns = ReviewConcerns.collect(preliminary, adjudicated, publication.groups());
            assessed = quality.reconcileAssessment(assessed, assessmentPlan, preliminary, adjudicated);
            String assessment = assessed.summary();
            assessmentSnapshot = assessment;
            verificationCoverage += ReviewQuality.coverage("Publication adjudication", adjudicated);
            OutputLanguage language = OutputLanguage.parse(options.language());
            List<ReviewFinding> confirmed = adjudicated.stream()
                    .filter(d -> d.verdict().status() == ReviewOutput.Status.CONFIRMED)
                    .map(d -> ReviewQuality.publishedFinding(d, language)).toList();
            published = confirmed;
            write(runDirectory.resolve("verified-findings.md"), confirmed.stream().map(finding -> finding.markdown(language))
                    .collect(java.util.stream.Collectors.joining()));

            write(runDirectory.resolve("coverage.md"), evidence.coverage() + supplemental.coverage() + verificationCoverage);
            ReviewQuality.Completion completion = ReviewQuality.completion(preliminary, adjudicated, assessmentPlan, executionIssues, language);

            progress.step(8, totalSteps, "Deduplicating findings and preparing the report");
            String reportPrompt = prompts.finalizeReport(evidence, confirmed, discoveryNotes.toString(),
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
            String response = reportReplies.get("report");
            String report = completion.notice() + ReviewReport.finalReport(response, confirmed, language, assessment, concerns);
            write(runDirectory.resolve("report.md"), report);
            write(runDirectory.resolve("review-status.md"), completion.markdown());
            progress.finish(totalSteps, completion.complete() ? "Wrote report.md" : "Wrote incomplete report.md ("
                    + completion.pendingChecks() + " checks remain unresolved)");
            return runDirectory;
        } catch (ReviewStageException incomplete) {
            executionIssues.add(incomplete.getMessage());
            var completion = ReviewQuality.completion(List.of(), List.of(), AssessmentFindings.Plan.empty(), executionIssues, outputLanguage);
            write(runDirectory.resolve("report.md"), completion.notice()
                    + ReviewReport.incompleteReport(scope, outputLanguage, published, assessmentSnapshot, executionIssues));
            write(runDirectory.resolve("review-status.md"), completion.markdown());
            progress.finish(totalSteps, "Wrote incomplete report.md (required stage did not complete; resume is available)");
            return runDirectory;
        } catch (IOException | InterruptedException fatal) {
            executionFailure = fatal;
            boolean interrupted = Thread.interrupted();
            try {
                String status = fatal instanceof InterruptedException ? "CANCELLED" : "FAILED";
                write(runDirectory.resolve("review-status.md"), "Status: " + status + "\n\n" + EvidenceText.oneLine(fatal.getMessage()) + "\n");
                write(runDirectory.resolve("report.md"), outputLanguage.text("> **Review did not complete.** See review-status.md.\n\n",
                        "> **审查未完成。** 详见 review-status.md。\n\n")
                        + ReviewReport.incompleteReport(scope, outputLanguage, published, assessmentSnapshot, List.of(status)));
            } catch (IOException logging) { fatal.addSuppressed(logging); }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
            throw fatal;
        } finally {
            boolean interrupted = Thread.interrupted();
            try { agent.timings().write(runDirectory, outputLanguage); }
            catch (IOException diagnostics) {
                if (executionFailure != null) executionFailure.addSuppressed(diagnostics);
                else throw diagnostics;
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
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
            previous = previous.replaceAll("(?m)^- Review parallelism:.*\\R?", "");
            expected = expected.replaceAll("(?m)^- Review parallelism:.*\\R?", "");
            previous = previous.replaceAll("(?m)^- Verification evidence:.*\\R?", "");
            expected = expected.replaceAll("(?m)^- Verification evidence:.*\\R?", "");
        }
        if (!previous.equals(expected)) {
            throw new IOException("Cannot resume because " + path.getFileName()
                    + " differs from the saved run. Use the original revisions, documents and evidence settings.");
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
                - Verification evidence: %s
                - Output language: %s
                - Dynamic verification: not run
                """.formatted(options.repo(), scope.mode(), scope.requestedBase(), scope.requestedTarget(),
                scope.baseCommit(), scope.targetCommit(), scope.diffExpression(), describe(requirement), describe(knowledge),
                options.knowledgeMode().name().toLowerCase(Locale.ROOT),
                options.maxSupplementalRequests() == 0 ? "unlimited" : options.maxSupplementalRequests(),
                options.maxKnowledgeQueries() == 0 ? "unlimited" : options.maxKnowledgeQueries(),
                options.reviewParallelism(), options.verificationEvidence().name().toLowerCase(Locale.ROOT), options.language())
                + (options.baseRemote() == null ? "" : "- Baseline remote: `" + options.baseRemote() + "`\n"
                    + "- Baseline remote branch: `" + RemoteBaseline.branchRef(options.base()) + "`\n")
                + "- Language modules: " + LanguageRegistry.defaults().modules().stream().map(LanguageModule::id)
                        .collect(java.util.stream.Collectors.joining(", ")) + "\n"
                + "- Language capabilities: " + LanguageRegistry.defaults().capabilitySummary() + "\n"
                + "- Language cache overrides: " + options.languageCaches().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                        .map(entry -> entry.getKey() + "=`" + entry.getValue() + "`")
                        .collect(java.util.stream.Collectors.joining(", ")) + "\n";
    }

    private static String describe(InputDocument document) {
        return document == null ? "not provided" : "`" + document.path() + "` (SHA-256 `" + document.sha256() + "`)";
    }

    private static void write(Path path, String content) throws IOException {
        AtomicFiles.write(path, content);
    }
}

