package reviewagent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

final class ReviewPipeline {
    private final ProcessExecutor executor;
    private final Clock clock;

    ReviewPipeline(ProcessExecutor executor, Clock clock) {
        this.executor = executor;
        this.clock = clock;
    }

    Path run(CliOptions options) throws IOException, InterruptedException {
        GitClient git = new GitClient(options.repo(), executor);
        ReviewScope scope = git.resolve(options);
        InputDocument requirement = InputDocument.read(options.requirement());
        InputDocument knowledge = InputDocument.read(options.knowledge());
        EvidenceBundle evidence = new EvidenceCollector(git).collect(
                scope, requirement, knowledge, options.maxEvidenceBytes());
        Path runDirectory = createRunDirectory(options.outputDir(), scope.targetCommit());

        write(runDirectory.resolve("scope.md"), scopeMarkdown(options, scope, requirement, knowledge));
        write(runDirectory.resolve("evidence.md"), evidence.markdown());

        PromptFactory prompts = new PromptFactory();
        String discoveryPrompt = prompts.discovery(evidence);
        write(runDirectory.resolve("discover-prompt.md"), discoveryPrompt);
        if (options.prepareOnly()) {
            write(runDirectory.resolve("verify-prompt-template.md"),
                    prompts.verification(evidence, "[candidate findings will be inserted here]"));
            return runDirectory;
        }

        AgentRunner agent = new AgentRunner(executor, options.agentCommand(), options.agentArgs(),
                options.repo(), options.agentTimeout());
        String candidates = agent.invoke(discoveryPrompt);
        write(runDirectory.resolve("candidates.md"), candidates);

        String verifyPrompt = prompts.verification(evidence, candidates);
        write(runDirectory.resolve("verify-prompt.md"), verifyPrompt);
        String report = agent.invoke(verifyPrompt);
        write(runDirectory.resolve("report.md"), report);
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
                - Dynamic verification: not run
                """.formatted(options.repo(), scope.mode(), scope.requestedBase(), scope.requestedTarget(),
                scope.baseCommit(), scope.targetCommit(), scope.diffExpression(), describe(requirement), describe(knowledge));
    }

    private static String describe(InputDocument document) {
        return document == null ? "not provided" : "`" + document.path() + "` (SHA-256 `" + document.sha256() + "`)";
    }

    private static void write(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}

