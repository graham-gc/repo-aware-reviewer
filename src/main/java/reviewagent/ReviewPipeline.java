package reviewagent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
        int totalSteps = options.prepareOnly() ? 3 : 5;
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

        PromptFactory prompts = new PromptFactory(options.language());
        String discoveryPrompt = prompts.discovery(evidence);
        write(runDirectory.resolve("discover-prompt.md"), discoveryPrompt);
        if (options.prepareOnly()) {
            progress.step(2, totalSteps, "Preparing verification prompt");
            write(runDirectory.resolve("verify-prompt-template.md"),
                    prompts.verification(evidence, "[candidate findings will be inserted here]", ""));
            progress.finish(totalSteps, "Evidence and prompts ready");
            return runDirectory;
        }

        progress.step(2, totalSteps, "Finding candidate issues");
        AgentRunner agent = new AgentRunner(executor, options.agentCommand(), options.agentArgs(),
                options.repo(), options.agentTimeout());
        String candidates = agent.invoke(discoveryPrompt);
        write(runDirectory.resolve("candidates.md"), candidates);

        progress.step(3, totalSteps, "Loading requested evidence");
        MavenEvidence maven = new MavenEvidence(git, executor, SupplementalEvidence.defaultMavenCache());
        SupplementalEvidence.Result supplemental = new SupplementalEvidence(git, maven).collect(evidence, candidates);
        write(runDirectory.resolve("supplemental-evidence.md"), supplemental.markdown());
        write(runDirectory.resolve("coverage.md"), evidence.coverage() + supplemental.coverage());

        progress.step(4, totalSteps, "Verifying findings and preparing the report");
        String verifyPrompt = prompts.verification(evidence, candidates, supplemental.markdown());
        write(runDirectory.resolve("verify-prompt.md"), verifyPrompt);
        String report = agent.invoke(verifyPrompt);
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
                - Output language: %s
                - Dynamic verification: not run
                """.formatted(options.repo(), scope.mode(), scope.requestedBase(), scope.requestedTarget(),
                scope.baseCommit(), scope.targetCommit(), scope.diffExpression(), describe(requirement), describe(knowledge),
                options.knowledgeMode().name().toLowerCase(Locale.ROOT), options.language());
    }

    private static String describe(InputDocument document) {
        return document == null ? "not provided" : "`" + document.path() + "` (SHA-256 `" + document.sha256() + "`)";
    }

    private static void write(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}

