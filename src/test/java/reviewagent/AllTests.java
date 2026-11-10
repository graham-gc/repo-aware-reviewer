package reviewagent;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public final class AllTests {
    public static void main(String[] args) throws Exception {
        testRangeParsing();
        testCliDefaults();
        testMutuallyExclusiveScopes();
        testPromptPolicy();
        testKnowledgeOptions();
        testAgentResponseEncoding();
        EvidenceTests.run();
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
        equal(java.util.List.of("-p"), defaultBackend.agentArgs());
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
        throwsArgument(() -> CliOptions.parse(new String[]{
                "review", "--repo", ".", "--knowledge-mode", "scoped"
        }));
    }

    private static void testMutuallyExclusiveScopes() {
        throwsArgument(() -> CliOptions.parse(new String[]{
                "review", "--repo", ".", "--commit", "HEAD", "--branch", "main", "--prepare-only"
        }));
    }

    private static void testPromptPolicy() {
        ReviewScope scope = new ReviewScope(ReviewScope.Mode.COMMIT, "a", "b", "a", "b", "a..b");
        EvidenceBundle evidence = new EvidenceBundle(scope, java.util.List.of(), "evidence", "coverage", 2_000, null);
        String prompt = new PromptFactory().verification(evidence, "candidate", "supplement");
        check(prompt.contains("No verified findings"));
        check(prompt.contains("INSUFFICIENT_EVIDENCE"));
        check(prompt.contains("never promoted"));
        check(prompt.contains("supplement"));
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
