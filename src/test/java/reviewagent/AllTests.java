package reviewagent;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Comparator;
import java.time.Duration;
import java.util.List;

public final class AllTests {
    public static void main(String[] args) throws Exception {
        testRangeParsing();
        testCliDefaults();
        testRequestLimits();
        testReviewLanguage();
        testMutuallyExclusiveScopes();
        testPromptPolicy();
        testKnowledgeOptions();
        testAgentResponseEncoding();
        testCommandDiagnostics();
        testProgressStageElapsed();
        ReviewOutputTests.run();
        EvidenceTests.run();
        ReviewQualityTests.run();
        System.out.println("All tests passed");
    }

    private static void testAgentResponseEncoding() throws Exception {
        String markdown = "> **性质说明**：供人工评审的候选知识库 `&#x662F;`\n";
        equal(markdown, shellAgent("cat").invoke(markdown));
        expectAgentOutputError("printf '\\303'", "invalid UTF-8");
        expectAgentOutputError("printf '\\357\\277\\275'", "U+FFFD");
    }

    private static AgentRunner shellAgent(String command) {
        return new AgentRunner(new ProcessExecutor(), "/bin/sh", List.of("-c", command),
                Path.of("."), Duration.ofSeconds(5));
    }

    private static void expectAgentOutputError(String command, String message) throws Exception {
        try {
            shellAgent(command).invoke("");
            throw new AssertionError("Expected agent response encoding error: " + message);
        } catch (IOException error) {
            check(error.getMessage().contains(message));
        }
    }

    private static void testKnowledgeOptions() {
        KnowledgeOptions options = KnowledgeOptions.parse(new String[]{
                "knowledge", "generate", "--repo", ".", "--prepare-only"
        });
        equal("HEAD", options.ref());
        equal(250_000, options.chunkBytes());
        equal("en", options.language());
        KnowledgeOptions chinese = KnowledgeOptions.parse(new String[]{
                "knowledge", "generate", "--repo", ".", "--prepare-only", "--language", "zh-CN"
        });
        equal("zh-CN", chinese.language());
        KnowledgeOptions defaultBackend = KnowledgeOptions.parse(new String[]{
                "knowledge", "generate", "--repo", "."
        });
        equal("claude", defaultBackend.agentCommand());
        List<String> expected = List.of("-p", "--tools", "", "--strict-mcp-config", "--mcp-config", "{\"mcpServers\":{}}");
        equal(expected, defaultBackend.agentArgs());
        equal(expected, CliOptions.parse(new String[]{"review", "--repo", "."}).agentArgs());
        equal(List.of("-p"), CliOptions.parse(new String[]{"review", "--repo", ".", "--agent-command", "claude",
                "--agent-arg", "-p"}).agentArgs());
    }

    private static void testCommandDiagnostics() throws Exception {
        Path directory = Files.createTempDirectory("review-command-log-test-");
        try {
            Path prefix = directory.resolve("slow-agent");
            var runner = new AgentRunner(new ProcessExecutor(), "/bin/sh",
                    List.of("-c", "printf 'partial output\\n'; printf 'diagnostic message\\n' >&2; exec sleep 10"),
                    directory, Duration.ofMillis(150));
            try {
                runner.invoke("prompt", prefix);
                throw new AssertionError("Expected a model-command timeout");
            } catch (IOException timeout) {
                check(timeout.getMessage().contains("timed out"));
            }
            equal("partial output\n", Files.readString(directory.resolve("slow-agent-stdout.log")));
            equal("diagnostic message\n", Files.readString(directory.resolve("slow-agent-stderr.log")));
            check(Files.readString(directory.resolve("slow-agent-execution.log")).contains("exitCode=unavailable"));
            Path completed = directory.resolve("completed-agent");
            equal("done", shellAgent("cat").invoke("done", completed));
            check(Files.readString(directory.resolve("completed-agent-execution.log")).contains("exitCode=0"));
            equal("done", Files.readString(directory.resolve("completed-agent-stdout.log")));
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        throwsArgument(() -> CliOptions.parse(new String[]{"review", "--repo", ".", "--agent-timeout-seconds", "0"}));
        throwsArgument(() -> KnowledgeOptions.parse(new String[]{"knowledge", "generate", "--repo", ".", "--agent-timeout-seconds", "-1"}));
    }

    private static void testProgressStageElapsed() throws Exception {
        var captured = new ByteArrayOutputStream();
        try (var output = new PrintStream(captured, true, StandardCharsets.UTF_8);
             var progress = new CommandProgress("elapsed-test", output)) {
            progress.step(2, 7, "Reviewers 0/3");
            Thread.sleep(1100);
            progress.step(2, 7, "Reviewers 1/3");
            String text = captured.toString(StandardCharsets.UTF_8);
            check(text.matches("(?s).*(\\([1-9][0-9]*s\\) Reviewers 1/3|Reviewers 1/3 \\(still working, [1-9][0-9]*s elapsed\\)).*"));
            int previousLength = captured.size();
            progress.step(3, 7, "Collecting evidence");
            String nextStage = captured.toString(StandardCharsets.UTF_8).substring(previousLength);
            check(nextStage.contains("Collecting evidence") && !nextStage.contains("s elapsed"));
        }
    }

    private static void testRangeParsing() {
        String[] parts = GitClient.splitRange("main..feature");
        equal("main", parts[0]);
        equal("feature", parts[1]);
        throwsArgument(() -> GitClient.splitRange("main...feature"));
    }

    private static void testCliDefaults() {
        CliOptions options = CliOptions.parse(new String[]{
                "review", "--repo", ".", "--prepare-only"
        });
        equal(null, options.commit());
        equal(CliOptions.KnowledgeMode.FULL, options.knowledgeMode());
        equal(1_000_000, options.maxEvidenceBytes());
        equal(0, options.maxSupplementalRequests());
        equal(0, options.maxKnowledgeQueries());
        equal(null, options.resumeDirectory());
        equal(Path.of("saved-run").toAbsolutePath().normalize(), CliOptions.parse(new String[]{
                "review", "--repo", ".", "--resume", "saved-run"}).resumeDirectory());
        throwsArgument(() -> CliOptions.parse(new String[]{
                "review", "--repo", ".", "--knowledge-mode", "scoped"
        }));
    }

    private static void testRequestLimits() {
        CliOptions options = CliOptions.parse(new String[]{
                "review", "--repo", ".", "--max-supplemental-requests", "3", "--max-knowledge-queries", "5"
        });
        equal(3, options.maxSupplementalRequests());
        equal(5, options.maxKnowledgeQueries());
        for (String option : List.of("--max-supplemental-requests", "--max-knowledge-queries")) {
            CliOptions unlimited = CliOptions.parse(new String[]{"review", "--repo", ".", option, "0"});
            equal(0, unlimited.maxSupplementalRequests());
            equal(0, unlimited.maxKnowledgeQueries());
            throwsArgument(() -> CliOptions.parse(new String[]{"review", "--repo", ".", option, "-1"}));
            throwsArgument(() -> CliOptions.parse(new String[]{"review", "--repo", ".", option}));
        }
    }

    private static void testMutuallyExclusiveScopes() {
        throwsArgument(() -> CliOptions.parse(new String[]{
                "review", "--repo", ".", "--commit", "HEAD", "--branch", "main", "--prepare-only"
        }));
    }

    private static void testReviewLanguage() {
        equal("en", CliOptions.parse(new String[]{"review", "--repo", "."}).language());
        equal("zh-CN", CliOptions.parse(new String[]{
                "review", "--repo", ".", "--language", "zh-CN"
        }).language());
        equal("en", CliOptions.parse(new String[]{
                "review", "--repo", ".", "--language", "EN-US"
        }).language());
        throwsArgument(() -> CliOptions.parse(new String[]{
                "review", "--repo", ".", "--language", "fr"
        }));
        var scope = new ReviewScope(ReviewScope.Mode.COMMIT, "a", "b", "a", "b", "a..b");
        var evidence = new EvidenceBundle(scope, List.of(), "中文需求原文", "coverage", 2_000, null);
        var chinese = new PromptFactory("zh-CN");
        String prompt = chinese.finalizeReport(evidence, List.of(), "candidate", "supplement", "coverage");
        check(prompt.contains("Output language: Simplified Chinese (zh-CN)"));
        check(prompt.contains("# 审查范围\n") && prompt.contains("# 需求验收\n"));
        check(prompt.contains("满足、部分满足、未满足、证据不足"));
        check(prompt.contains("严重、高、中、低"));
        check(!prompt.contains("# Review scope\n"));
        check(chinese.discovery(evidence).contains("Output language: Simplified Chinese (zh-CN)"));
        String english = new PromptFactory().finalizeReport(evidence, List.of(), "candidate", "", "coverage");
        check(english.contains("Output language: English (en)"));
        check(english.contains("# Review scope\n") && english.contains("Translate business statements"));
    }

    private static void testPromptPolicy() {
        ReviewScope scope = new ReviewScope(ReviewScope.Mode.COMMIT, "a", "b", "a", "b", "a..b");
        EvidenceBundle evidence = new EvidenceBundle(scope, java.util.List.of(), "evidence", "coverage", 2_000, null);
        String prompt = new PromptFactory().finalizeReport(evidence, List.of(), "candidate", "supplement", "coverage");
        check(prompt.contains("No verified findings"));
        check(prompt.contains("INSUFFICIENT_EVIDENCE"));
        check(prompt.contains("never promoted"));
        check(prompt.contains("supplement"));
        equal(3, CliOptions.parse(new String[]{"review", "--repo", "."}).reviewParallelism());
        equal(1, CliOptions.parse(new String[]{"review", "--repo", ".", "--review-parallelism", "1"}).reviewParallelism());
        throwsArgument(() -> CliOptions.parse(new String[]{"review", "--repo", ".", "--review-parallelism", "0"}));
        String discovery = new PromptFactory().discovery(evidence);
        for (String stage : List.of(discovery, prompt)) {
            check(stage.contains("UI counts, lists, navigation and confirmation"));
            check(stage.contains("absent from the evidence, mark that behavior INSUFFICIENT_EVIDENCE"));
            check(stage.contains("syntactically valid null or wrong-shaped data"));
            check(stage.contains("only when every\nrequired behavior is supported"));
        }
    }

    private static void equal(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }

    private static void check(boolean condition) {
        if (!condition) {
            throw new AssertionError("Condition was false");
        }
    }

    private static void throwsArgument(Runnable runnable) {
        try {
            runnable.run();
            throw new AssertionError("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
