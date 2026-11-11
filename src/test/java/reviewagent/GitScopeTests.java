package reviewagent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class GitScopeTests {
    static void run() throws Exception {
        Path fixture = Files.createTempDirectory("review-git-scope-test-");
        try {
            checkExplicitBaseline(fixture);
        } finally {
            try (var paths = Files.walk(fixture)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void checkExplicitBaseline(Path fixture) throws Exception {
        Path repo = Files.createDirectory(fixture.resolve("repo"));
        var executor = new ProcessExecutor();
        git(executor, repo, "init", "-q", "--initial-branch=main");
        Files.writeString(repo.resolve("Shared.java"), "class Shared {}\n");
        Files.writeString(repo.resolve("REVIEW.md"), "Common review rule.\n");
        git(executor, repo, "add", "--all");
        git(executor, repo, "commit", "-qm", "common ancestor");
        String ancestor = git(executor, repo, "rev-parse", "HEAD");

        Files.writeString(repo.resolve("BaseOnly.java"), "class BaseOnly {}\n");
        Files.writeString(repo.resolve("REVIEW.md"), "Approved main review rule.\n");
        git(executor, repo, "add", "--all");
        git(executor, repo, "commit", "-qm", "main advances");
        String base = git(executor, repo, "rev-parse", "HEAD");
        git(executor, repo, "update-ref", "refs/remotes/origin/main", base);

        git(executor, repo, "checkout", "-qb", "feature", ancestor);
        Files.writeString(repo.resolve("Feature.java"), "class Feature {}\n");
        git(executor, repo, "add", "--all");
        git(executor, repo, "commit", "-qm", "feature change");
        String target = git(executor, repo, "rev-parse", "HEAD");
        git(executor, repo, "tag", "review-target", target);
        Files.writeString(repo.resolve("Shared.java"), "Uncommitted worktree content.\n");
        var client = new GitClient(repo, executor);
        CliOptions options = CliOptions.parse(new String[]{"review", "--repo", repo.toString(),
                "--branch", target, "--base", "origin/main", "--prepare-only",
                "--output-dir", fixture.resolve("output").toString()});
        ReviewScope scope = client.resolve(options);
        require(scope.baseCommit().equals(base) && scope.targetCommit().equals(target),
                "explicit comparison uses the supplied baseline tip even when the histories diverge");
        require(scope.requestedBase().equals("origin/main") && scope.requestedTarget().equals(target),
                "requested references are preserved alongside pinned commits");
        List<GitClient.Change> changes = client.changes(scope);
        require(changes.stream().anyMatch(change -> change.path().equals("BaseOnly.java") && change.deleted()),
                "a baseline-only file is included as a deletion in the direct snapshot comparison");
        require(changes.stream().anyMatch(change -> change.path().equals("Feature.java") && !change.deleted()),
                "target-only additions are included");
        require(changes.stream().noneMatch(change -> change.path().equals("Shared.java")),
                "uncommitted worktree changes do not enter the comparison");

        Path output = new ReviewPipeline(executor, Clock.systemUTC()).run(options);
        String savedScope = Files.readString(output.resolve("scope.md"));
        require(savedScope.contains("- Resolved base: `" + base + "`")
                && savedScope.contains("- Resolved target: `" + target + "`"),
                "saved artifacts record the actual baseline and target");
        String evidence = Files.readString(output.resolve("evidence.md"));
        require(evidence.contains("Approved main review rule.") && !evidence.contains("Uncommitted worktree content."),
                "evidence uses the specified baseline rules and committed source snapshots");
        require(git(executor, repo, "symbolic-ref", "--short", "HEAD").equals("feature")
                && Files.readString(repo.resolve("Shared.java")).equals("Uncommitted worktree content.\n"),
                "review preparation preserves the current branch and worktree");

        for (String reference : List.of("feature", "review-target")) {
            ReviewScope named = client.resolve(comparison(repo, reference, "origin/main"));
            require(named.baseCommit().equals(base) && named.targetCommit().equals(target),
                    "branch names and tags resolve to the same explicit snapshots");
        }
        ReviewScope empty = client.resolve(comparison(repo, "origin/main", "origin/main"));
        require(client.changes(empty).isEmpty(), "equal baseline and target produce an empty diff");

        String tree = git(executor, repo, "rev-parse", target + "^{tree}");
        String unrelated = git(executor, repo, "commit-tree", tree, "-m", "unrelated root");
        ReviewScope unrelatedScope = client.resolve(comparison(repo, unrelated, "origin/main"));
        require(unrelatedScope.baseCommit().equals(base) && unrelatedScope.targetCommit().equals(unrelated)
                && client.changes(unrelatedScope).stream().anyMatch(change -> change.path().equals("BaseOnly.java") && change.deleted()),
                "explicit comparison works without a common ancestor");

        for (String[] arguments : List.of(new String[]{"review", "--repo", repo.toString()},
                new String[]{"review", "--repo", repo.toString(), "--commit", target})) {
            ReviewScope single = client.resolve(CliOptions.parse(arguments));
            require(single.mode() == ReviewScope.Mode.COMMIT && single.baseCommit().equals(ancestor)
                    && single.targetCommit().equals(target), "single-commit mode still compares with the first parent");
        }
    }

    private static CliOptions comparison(Path repo, String target, String base) {
        return CliOptions.parse(new String[]{"review", "--repo", repo.toString(),
                "--branch", target, "--base", base, "--prepare-only"});
    }

    private static String git(ProcessExecutor executor, Path repo, String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("git", "-c", "user.name=Scope Test", "-c",
                "user.email=scope@example.invalid", "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null"));
        command.addAll(List.of(arguments));
        CommandResult result = executor.run(command, repo, null, Duration.ofSeconds(15));
        require(result.exitCode() == 0, "Git fixture: " + result.stderr());
        return result.stdout().strip();
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
