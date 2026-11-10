package reviewagent;

public final class AllTests {
    public static void main(String[] args) {
        testRangeParsing();
        testCliDefaults();
        testMutuallyExclusiveScopes();
        testPromptPolicy();
        testKnowledgeOptions();
        System.out.println("All tests passed");
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
        throwsArgument(() -> KnowledgeOptions.parse(new String[]{
                "knowledge", "generate", "--repo", "."
        }));
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
        equal(1_000_000, options.maxEvidenceBytes());
    }

    private static void testMutuallyExclusiveScopes() {
        throwsArgument(() -> CliOptions.parse(new String[]{
                "review", "--repo", ".", "--commit", "HEAD", "--branch", "main", "--prepare-only"
        }));
    }

    private static void testPromptPolicy() {
        ReviewScope scope = new ReviewScope(ReviewScope.Mode.COMMIT, "a", "b", "a", "b", "a..b");
        EvidenceBundle evidence = new EvidenceBundle(scope, java.util.List.of(), "evidence");
        String prompt = new PromptFactory().verification(evidence, "candidate");
        check(prompt.contains("No verified findings"));
        check(prompt.contains("INSUFFICIENT_EVIDENCE"));
        check(prompt.contains("never promoted"));
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
