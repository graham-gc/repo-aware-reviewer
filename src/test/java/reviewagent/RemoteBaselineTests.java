package reviewagent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class RemoteBaselineTests {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-12T00:00:00Z"), ZoneOffset.UTC);

    static void run() throws Exception {
        Path fixture = Files.createTempDirectory("review-remote-baseline-");
        try {
            checkRemoteSnapshots(fixture);
        } finally {
            try (var paths = Files.walk(fixture)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void checkRemoteSnapshots(Path fixture) throws Exception {
        var executor = new ProcessExecutor();
        Path author = Files.createDirectory(fixture.resolve("author"));
        Path server = fixture.resolve("server.git");
        Path repo = fixture.resolve("repo");
        Path outputs = fixture.resolve("output");
        git(executor, author, "init", "-q", "--initial-branch=main");
        Files.writeString(author.resolve("Shared.java"), "class Shared {}\n");
        git(executor, author, "add", "--all");
        git(executor, author, "commit", "-qm", "initial main");
        String original = git(executor, author, "rev-parse", "HEAD");
        git(executor, fixture, "clone", "-q", "--bare", author.toString(), server.toString());
        git(executor, fixture, "clone", "-q", "--single-branch", "--branch", "main", server.toString(), repo.toString());
        git(executor, repo, "config", "protocol.file.allow", "always");
        git(executor, repo, "checkout", "-qb", "feature");
        Files.writeString(repo.resolve("Feature.java"), "class Feature {}\n");
        git(executor, repo, "add", "--all");
        git(executor, repo, "commit", "-qm", "feature work");
        String target = git(executor, repo, "rev-parse", "HEAD");
        Files.writeString(repo.resolve("Shared.java"), "Uncommitted work.\n");
        git(executor, repo, "tag", "local-only", original);
        Files.writeString(repo.resolve(".git/FETCH_HEAD"), "Existing FETCH_HEAD\n");
        git(executor, repo, "config", "fetch.prune", "true");
        git(executor, repo, "config", "fetch.pruneTags", "true");
        git(executor, repo, "config", "remote.origin.pruneTags", "true");
        Path hookSignal = fixture.resolve("hook-ran");
        executable(repo.resolve(".git/hooks/reference-transaction"),
                "#!/bin/sh\ntouch '" + hookSignal + "'\nexit 17\n");

        Files.writeString(author.resolve("REVIEW.md"), "Rules from fetched main.\n");
        git(executor, author, "add", "--all");
        git(executor, author, "commit", "-qm", "remote main advances");
        String firstBase = git(executor, author, "rev-parse", "HEAD");
        git(executor, author, "push", "-q", server.toString(), "main");
        git(executor, author, "tag", "remote-only", firstBase);
        git(executor, author, "push", "-q", server.toString(), "refs/tags/remote-only");
        var pipeline = new ReviewPipeline(executor, CLOCK);
        CliOptions options = options(repo, outputs, target, "origin", "main");
        Path firstRun = pipeline.run(options);
        String firstScope = Files.readString(firstRun.resolve("scope.md"));
        require(firstScope.contains("- Resolved base: `" + firstBase + "`")
                && firstScope.contains("- Baseline remote: `origin`")
                && firstScope.contains("- Baseline remote branch: `refs/heads/main`"), "remote source and fetched commit are recorded");
        require(Files.readString(firstRun.resolve("evidence.md")).contains("Rules from fetched main."),
                "baseline rules come from the fetched version rather than stale origin/main");
        require(git(executor, repo, "rev-parse", "origin/main").equals(original)
                && git(executor, repo, "rev-parse", "main").equals(original), "fetch does not update local or remote-tracking branches");
        require(git(executor, repo, "rev-parse", "HEAD").equals(target)
                && Files.readString(repo.resolve("Shared.java")).equals("Uncommitted work.\n"), "fetch preserves HEAD and dirty files");
        require(Files.readString(repo.resolve(".git/FETCH_HEAD")).equals("Existing FETCH_HEAD\n"), "shared FETCH_HEAD is untouched");
        require(git(executor, repo, "tag", "--list").equals("local-only") && !Files.exists(hookSignal),
                "tag pruning, tag downloads, and repository hooks are disabled");
        checkFetchLog(firstRun, "exitCode=0");

        var client = new GitClient(repo, executor);
        Path leaseOneDir = Files.createDirectory(fixture.resolve("lease-one"));
        Path leaseTwoDir = Files.createDirectory(fixture.resolve("lease-two"));
        String secondBase;
        try (var first = RemoteBaseline.open(options, executor, client, leaseOneDir)) {
            Files.writeString(author.resolve("Latest.java"), "class Latest {}\n");
            git(executor, author, "add", "--all");
            git(executor, author, "commit", "-qm", "remote main advances again");
            secondBase = git(executor, author, "rev-parse", "HEAD");
            git(executor, author, "push", "-q", server.toString(), "main");
            try (var second = RemoteBaseline.open(options, executor, client, leaseTwoDir)) {
                require(first.commit().equals(firstBase) && second.commit().equals(secondBase),
                        "independent fetches keep separate snapshots while the remote advances");
                require(git(executor, repo, "for-each-ref", "--format=%(objectname)", "refs/review-agent/baselines").lines().count() == 2,
                        "both snapshots remain pinned for their active reviews");
            }
        }
        require(git(executor, repo, "for-each-ref", "refs/review-agent/baselines").isEmpty(), "private refs are released after use");
        Path secondRun = pipeline.run(options);
        require(!firstRun.equals(secondRun)
                && Files.readString(secondRun.resolve("scope.md")).contains("- Resolved base: `" + secondBase + "`")
                && Files.readString(firstRun.resolve("scope.md")).equals(firstScope),
                "new reviews fetch the latest version without overwriting an earlier run in the same second");

        git(executor, repo, "remote", "set-url", "origin", fixture.resolve("unavailable.git").toString());
        CliOptions resume = CliOptions.parse(new String[]{"review", "--repo", repo.toString(), "--branch", target,
                "--base", "main", "--base-remote", "origin", "--prepare-only", "--resume", firstRun.toString()});
        require(pipeline.run(resume).equals(firstRun) && Files.readString(firstRun.resolve("scope.md")).equals(firstScope),
                "resume reuses the original pinned baseline without contacting an unavailable remote");
        git(executor, repo, "remote", "set-url", "origin", server.toString());

        git(executor, author, "checkout", "-qb", "release/1.0");
        Files.writeString(author.resolve("Release.java"), "class Release {}\n");
        git(executor, author, "add", "--all");
        git(executor, author, "commit", "-qm", "release baseline");
        String release = git(executor, author, "rev-parse", "HEAD");
        git(executor, author, "push", "-q", server.toString(), "release/1.0");
        git(executor, repo, "remote", "add", "upstream", server.toString());
        Path releaseRun = pipeline.run(options(repo, outputs, target, "upstream", "release/1.0"));
        require(Files.readString(releaseRun.resolve("scope.md")).contains("- Resolved base: `" + release + "`"),
                "non-main branches and explicitly selected alternate remotes work even without local tracking refs");

        git(executor, repo, "update-ref", "refs/remotes/origin/release/1.0", release);
        git(executor, author, "push", "-q", server.toString(), "--delete", "release/1.0");
        expectFailure(() -> pipeline.run(options(repo, outputs, target, "origin", "release/1.0")), "no local fallback");
        Path failedRun = findLogRun(outputs, "refs/heads/release/1.0:");
        require(!Files.readString(failedRun.resolve("baseline-fetch-stderr.log")).isBlank()
                && !Files.exists(failedRun.resolve("scope.md")), "deleted remote branches fail with logs despite cached local refs");

        Path slowUpload = fixture.resolve("slow-upload-pack");
        executable(slowUpload, "#!/bin/sh\nprintf 'slow transport diagnostic\\n' >&2\nexec sleep 10\n");
        git(executor, repo, "remote", "add", "slow", server.toUri().toString());
        git(executor, repo, "config", "remote.slow.uploadpack", slowUpload.toString());
        CliOptions timeout = CliOptions.parse(new String[]{"review", "--repo", repo.toString(), "--branch", target,
                "--base", "main", "--base-remote", "slow", "--fetch-timeout-seconds", "1", "--prepare-only",
                "--output-dir", outputs.toString()});
        expectFailure(() -> pipeline.run(timeout), "timed out");
        Path timedOut = findLogRun(outputs, "-- slow refs/heads/main:");
        checkFetchLog(timedOut, "exitCode=unavailable");
        require(Files.readString(timedOut.resolve("baseline-fetch-stderr.log")).contains("slow transport diagnostic"),
                "timed-out fetches preserve partial transport diagnostics");
        require(git(executor, repo, "for-each-ref", "refs/review-agent/baselines").isEmpty(), "failed fetches leave no private refs");

        git(executor, repo, "push", "-q", "--force", "origin", target + ":refs/heads/main");
        Path identical = pipeline.run(options);
        require(Files.readString(identical.resolve("report.md")).contains("no file differences")
                && Files.readString(identical.resolve("scope.md")).contains("- Resolved base: `" + target + "`"),
                "a fetched remote tip equal to the target is reported as an empty diff");
        require(git(executor, repo, "for-each-ref", "refs/review-agent/baselines").isEmpty(),
                "the empty-diff return also releases the fetched baseline ref");
    }

    private static CliOptions options(Path repo, Path outputs, String target, String remote, String branch) {
        return CliOptions.parse(new String[]{"review", "--repo", repo.toString(), "--branch", target,
                "--base", branch, "--base-remote", remote, "--prepare-only", "--output-dir", outputs.toString()});
    }

    private static void checkFetchLog(Path directory, String status) throws IOException {
        String log = Files.readString(directory.resolve("baseline-fetch-execution.log"));
        require(log.contains("workingDirectory=") && log.contains("startedAt=") && log.contains("timeoutSeconds=")
                && log.contains("elapsedMillis=") && log.contains(status), "fetch execution metadata is recorded");
        require(Files.exists(directory.resolve("baseline-fetch-stdout.log"))
                && Files.exists(directory.resolve("baseline-fetch-stderr.log")), "fetch output logs are retained");
    }

    private static Path findLogRun(Path outputs, String fragment) throws IOException {
        try (var paths = Files.list(outputs)) {
            for (Path path : paths.toList()) {
                Path log = path.resolve("baseline-fetch-execution.log");
                if (Files.isRegularFile(log) && Files.readString(log).contains(fragment)
                        && !Files.exists(path.resolve("scope.md"))) return path;
            }
        }
        throw new AssertionError("Missing failed fetch log: " + fragment);
    }

    @FunctionalInterface
    private interface Attempt { void run() throws Exception; }

    private static void expectFailure(Attempt attempt, String message) throws Exception {
        try {
            attempt.run();
            throw new AssertionError("Expected fetch failure: " + message);
        } catch (IOException failure) {
            require(failure.getMessage().contains(message), "fetch failure explains the cause: " + failure.getMessage());
        }
    }

    private static String git(ProcessExecutor executor, Path directory, String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("git", "-c", "user.name=Remote Test", "-c",
                "user.email=remote@example.invalid", "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null",
                "-c", "protocol.file.allow=always"));
        command.addAll(List.of(arguments));
        CommandResult result = executor.run(command, directory, null, Duration.ofSeconds(15));
        require(result.exitCode() == 0, "Git fixture: " + result.stderr());
        return result.stdout().strip();
    }

    private static void executable(Path path, String content) throws IOException {
        Files.writeString(path, content);
        require(path.toFile().setExecutable(true), "fixture script is executable");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
