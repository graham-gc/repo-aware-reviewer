package reviewagent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.List;

/** Failure artifacts remain reachable without converting a failed review to a successful CI step. */
final class GithubReviewScriptTests {
    static void run() throws Exception {
        Path script = Path.of("scripts/github-review.sh").toAbsolutePath();
        Path root = Files.createTempDirectory("github-review-artifacts-");
        try {
            Path action = Files.createDirectories(root.resolve("action/scripts"));
            Files.writeString(action.resolve("build.sh"), "printf '%s\\n' /unused/reviewer.jar\n");
            Path bin = Files.createDirectory(root.resolve("bin"));
            Files.writeString(bin.resolve("claude"), "#!/bin/sh\nexit 0\n");
            Files.writeString(bin.resolve("java"), "#!/bin/sh\nif [ \"$STUB_EXIT\" != 1 ]; then printf '%s\\n' \"$REVIEW_ROOT/output/run\"; fi\nexit \"$STUB_EXIT\"\n");
            bin.resolve("claude").toFile().setExecutable(true);
            bin.resolve("java").toFile().setExecutable(true);
            for (String scenario : List.of("complete", "incomplete", "failed", "absent", "ambiguous")) {
                int exit = scenario.equals("complete") ? 0 : scenario.equals("incomplete") ? 3 : 1;
                Path workspace = Files.createDirectories(root.resolve(scenario));
                Path run = workspace.resolve("output/run");
                if (!scenario.equals("absent")) {
                    Files.createDirectories(run);
                    Files.writeString(run.resolve("review-status.md"), "Status: " + (exit == 0 ? "COMPLETE" : exit == 3 ? "INCOMPLETE" : "FAILED"));
                }
                if (scenario.equals("ambiguous")) {
                    Path other = Files.createDirectories(workspace.resolve("output/other"));
                    Files.writeString(other.resolve("review-status.md"), "Status: FAILED");
                }
                Path output = Files.createFile(workspace.resolve("github-output"));
                var environment = Map.of("GITHUB_ACTION_PATH", action.getParent().toString(), "GITHUB_OUTPUT", output.toString(),
                        "REVIEW_ROOT", workspace.toString(), "REVIEW_BASE", "base", "REVIEW_HEAD", "target", "REVIEW_KNOWLEDGE_MODE", "full",
                        "STUB_EXIT", Integer.toString(exit), "PATH", bin + ":" + System.getenv("PATH"));
                var result = new ProcessExecutor().run(List.of("bash", script.toString(), "review"), workspace, null,
                        Duration.ofSeconds(10), null, null, environment);
                require(result.exitCode() == exit, "the original CLI failure code is preserved: " + result.stderr());
                String expected = scenario.equals("absent") || scenario.equals("ambiguous") ? "" : "directory=" + run + "\n";
                require(Files.readString(output).equals(expected), "export only available, unambiguous run diagnostics: " + scenario);
            }
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
