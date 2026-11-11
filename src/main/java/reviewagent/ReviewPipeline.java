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
        int totalSteps = options.prepareOnly() ? 3 : 7;
        progress.step(0, totalSteps, "Resolving Git review scope");
        GitClient git = new GitClient(options.repo(), executor);
        ReviewScope scope = git.resolve(options);

        progress.step(1, totalSteps, "Collecting Git diff and review evidence");
        InputDocument requirement = InputDocument.read(options.requirement());
        InputDocument knowledge = InputDocument.read(options.knowledge());
        EvidenceBundle evidence = new EvidenceCollector(git).collect(
                scope, requirement, knowledge, options.knowledgeMode(), options.maxEvidenceBytes());
        Path runDirectory = createRunDirectory(options.outputDir(), scope.targetCommit());

        write(runDirectory.resolve("scope.md"), scopeMarkdown(options, scope, requirement, knowledge));
        write(runDirectory.resolve("evidence.md"), evidence.markdown());
        write(runDirectory.resolve("coverage.md"), evidence.coverage());

        PromptFactory prompts = new PromptFactory(
                options.language(), options.maxSupplementalRequests(), options.maxKnowledgeQueries());
        String discoveryPrompt = prompts.discovery(evidence);
        write(runDirectory.resolve("discover-prompt.md"), discoveryPrompt);
        List<ReviewTasks.Task> discoveries = new ArrayList<>();
        for (int i = 0; i < PromptFactory.REVIEW_FOCUSES.size(); i++) {
            discoveries.add(new ReviewTasks.Task("R" + (i + 1),
                    prompts.independentDiscovery(evidence, PromptFactory.REVIEW_FOCUSES.get(i))));
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
            write(runDirectory.resolve("report-prompt-template.md"),
                    prompts.finalizeReport(evidence, List.of(), "[discovery notes]", "[supplemental evidence]", "[verification results]"));
            progress.finish(totalSteps, "Evidence and prompts ready");
            return runDirectory;
        }

        progress.step(2, totalSteps, "Independent reviewers 0/" + discoveries.size());
        AgentRunner agent = new AgentRunner(executor, options.agentCommand(), options.agentArgs(),
                options.repo(), options.agentTimeout());
        var responses = ReviewTasks.run(discoveries, agent, runDirectory.resolve("discovery"), options.reviewParallelism(),
                progress, 2, totalSteps, "Independent reviewers");
        var findings = new ArrayList<ReviewFinding>();
        StringBuilder discoveryNotes = new StringBuilder();
        StringBuilder rawCandidates = new StringBuilder();
        for (var response : responses.entrySet()) {
            ReviewOutput.Discovery parsed = ReviewOutput.discovery(response.getValue(), response.getKey(), evidence.changedFiles());
            findings.addAll(parsed.findings());
            discoveryNotes.append("\n## ").append(response.getKey()).append("\n\n").append(parsed.notes());
            rawCandidates.append("\n## ").append(response.getKey()).append("\n\n").append(response.getValue()).append('\n');
        }
        String candidates = rawCandidates.toString();
        write(runDirectory.resolve("candidates.md"), candidates);

        progress.step(3, totalSteps, "Loading requested evidence");
        MavenEvidence maven = new MavenEvidence(git, executor, SupplementalEvidence.defaultMavenCache());
        SupplementalEvidence.Result supplemental = new SupplementalEvidence(git, maven).collect(
                evidence, candidates, options.maxSupplementalRequests(), options.maxKnowledgeQueries());
        write(runDirectory.resolve("supplemental-evidence.md"), supplemental.markdown());
        StringBuilder coverage = new StringBuilder(evidence.coverage()).append(supplemental.coverage());
        write(runDirectory.resolve("coverage.md"), coverage.toString());

        progress.step(4, totalSteps, "Reviewing supplemental evidence");
        if (supplemental.hasMaterial()) {
            EvidenceBundle augmented = new EvidenceBundle(evidence.scope(), evidence.changedFiles(),
                    evidence.markdown() + supplemental.markdown(), evidence.coverage(), 0, evidence.scopedKnowledge());
            String prompt = prompts.independentDiscovery(augmented, """
                    Inspect the supplemental evidence and newly supplied business rules for concrete regressions.
                    This pass runs after evidence collection. Use the material already supplied and record remaining
                    evidence gaps in prose. Do not issue additional evidence requests in this pass.
                    """);
            var followup = ReviewTasks.run(List.of(new ReviewTasks.Task("S1", prompt)), agent,
                    runDirectory.resolve("discovery"), options.reviewParallelism(), progress, 4, totalSteps,
                    "Supplemental evidence reviewer");
            String response = followup.get("S1");
            ReviewOutput.Discovery parsed = ReviewOutput.discovery(response, "S1", evidence.changedFiles());
            findings.addAll(parsed.findings());
            discoveryNotes.append("\n## S1\n\n").append(parsed.notes());
            rawCandidates.append("\n## S1\n\n").append(response).append('\n');
            write(runDirectory.resolve("candidates.md"), rawCandidates.toString());
            coverage.append("\nUnprocessed supplemental-discovery evidence requests: ")
                    .append(EvidenceRequest.parse(response).size()).append(".\n");
        }

        progress.step(5, totalSteps, findings.isEmpty() ? "No candidate findings to verify"
                : "Verifying candidate issues 0/" + findings.size());
        var verificationTasks = findings.stream().map(finding -> new ReviewTasks.Task(finding.id(),
                prompts.verifyFinding(evidence, finding, supplemental.markdown()))).toList();
        var verificationReplies = ReviewTasks.run(verificationTasks, agent, runDirectory.resolve("verification"),
                options.reviewParallelism(), progress, 5, totalSteps, "Verifying candidate issues");
        var confirmed = new ArrayList<ReviewFinding>();
        StringBuilder verificationCoverage = new StringBuilder("\n## Individual candidate verification\n\n");
        for (ReviewFinding finding : findings) {
            ReviewOutput.Verification result = ReviewOutput.verification(
                    verificationReplies.get(finding.id()), finding, evidence.changedFiles());
            verificationCoverage.append("- ").append(finding.id()).append(" — ").append(result.status())
                    .append(". ").append(EvidenceText.oneLine(finding.title())).append(" (`")
                    .append(EvidenceText.oneLine(finding.path())).append(':').append(finding.line()).append("`). ")
                    .append(EvidenceText.oneLine(result.reason())).append('\n');
            if (result.status() == ReviewOutput.Status.CONFIRMED) {
                confirmed.add(result.confirmed());
            }
        }
        verificationCoverage.append("\nCandidates: ").append(findings.size()).append(". Confirmed before deduplication: ")
                .append(confirmed.size()).append(".\n");
        coverage.append(verificationCoverage);
        write(runDirectory.resolve("coverage.md"), coverage.toString());
        OutputLanguage language = OutputLanguage.parse(options.language());
        write(runDirectory.resolve("verified-findings.md"), confirmed.stream().map(finding -> finding.markdown(language))
                .collect(java.util.stream.Collectors.joining()));

        progress.step(6, totalSteps, "Deduplicating findings and preparing the report");
        String reportPrompt = prompts.finalizeReport(evidence, confirmed, discoveryNotes.toString(),
                supplemental.markdown(), verificationCoverage.toString());
        write(runDirectory.resolve("verify-prompt.md"), reportPrompt);
        String response = agent.invoke(reportPrompt);
        write(runDirectory.resolve("report-response.md"), response);
        String report = ReviewOutput.finalReport(response, confirmed, language);
        write(runDirectory.resolve("report.md"), report);
        progress.finish(totalSteps, "Wrote report.md");
        return runDirectory;
    }

    private Path createRunDirectory(Path root, String targetCommit) throws IOException {
        String timestamp = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss")
                .format(OffsetDateTime.now(clock).atZoneSameInstant(ZoneId.systemDefault()));
        Path directory = root.resolve(timestamp + "-" + targetCommit.substring(0, 12));
        Files.createDirectories(directory);
        return directory;
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
                - File and Maven request count limit: %s
                - Knowledge query count limit: %s
                - Review parallelism: %s
                - Output language: %s
                - Dynamic verification: not run
                """.formatted(options.repo(), scope.mode(), scope.requestedBase(), scope.requestedTarget(),
                scope.baseCommit(), scope.targetCommit(), scope.diffExpression(), describe(requirement), describe(knowledge),
                options.knowledgeMode().name().toLowerCase(Locale.ROOT),
                options.maxSupplementalRequests() == 0 ? "unlimited" : options.maxSupplementalRequests(),
                options.maxKnowledgeQueries() == 0 ? "unlimited" : options.maxKnowledgeQueries(),
                options.reviewParallelism(), options.language());
    }

    private static String describe(InputDocument document) {
        return document == null ? "not provided" : "`" + document.path() + "` (SHA-256 `" + document.sha256() + "`)";
    }

    private static void write(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}

