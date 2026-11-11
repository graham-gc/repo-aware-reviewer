package reviewagent;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public final class AllTests {
    public static void main(String[] args) throws Exception {
        testRangeParsing();
        testCliDefaults();
        testReviewLanguage();
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
        String prompt = chinese.verification(evidence, "candidate", "supplement");
        check(prompt.contains("Output language: Simplified Chinese (zh-CN)"));
        check(prompt.contains("# 审查范围\n") && prompt.contains("# 需求验收\n"));
        check(prompt.contains("满足、部分满足、未满足、证据不足"));
        check(prompt.contains("严重、高、中、低"));
        check(!prompt.contains("# Review scope\n"));
        check(chinese.discovery(evidence).contains("Output language: Simplified Chinese (zh-CN)"));
        String english = new PromptFactory().verification(evidence, "candidate", "");
        check(english.contains("Output language: English (en)"));
        check(english.contains("# Review scope\n") && english.contains("Translate business statements"));
    }

    private static void testPromptPolicy() {
        ReviewScope scope = new ReviewScope(ReviewScope.Mode.COMMIT, "a", "b", "a", "b", "a..b");
        EvidenceBundle evidence = new EvidenceBundle(scope, java.util.List.of(), "evidence", "coverage", 2_000, null);
        String prompt = new PromptFactory().verification(evidence, "candidate", "supplement");
        check(prompt.contains("No verified findings"));
        check(prompt.contains("INSUFFICIENT_EVIDENCE"));
        check(prompt.contains("never promoted"));
        check(prompt.contains("supplement"));
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
