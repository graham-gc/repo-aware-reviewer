package reviewagent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Holds a fetched baseline in a private ref for the lifetime of one review. */
final class RemoteBaseline implements AutoCloseable {
    private final ProcessExecutor executor;
    private final Path repository;
    private final String reference = "refs/review-agent/baselines/" + UUID.randomUUID();
    private String commit;

    private RemoteBaseline(ProcessExecutor executor, Path repository) {
        this.executor = executor;
        this.repository = repository;
    }

    static RemoteBaseline open(CliOptions options, ProcessExecutor executor, GitClient git, Path directory)
            throws IOException, InterruptedException {
        var baseline = new RemoteBaseline(executor, options.repo());
        try {
            String source = branchRef(options.base());
            baseline.git("check-ref-format", source);
            if (options.resumeDirectory() != null) {
                String saved = savedCommit(directory.resolve("scope.md"));
                baseline.commit = git.resolveCommit(saved);
                baseline.git("update-ref", baseline.reference, baseline.commit);
            } else {
                if (baseline.git("remote").lines().noneMatch(options.baseRemote()::equals)) {
                    throw new IllegalArgumentException("Git remote is not configured: " + options.baseRemote());
                }
                baseline.fetch(options, source, directory);
                baseline.commit = git.resolveCommit(baseline.reference);
                Files.writeString(directory.resolve("baseline-fetch-snapshot.log"),
                        "remote=" + options.baseRemote() + "\nbranch=" + source + "\ncommit=" + baseline.commit
                                + "\nfetchedAt=" + Instant.now() + "\n", StandardCharsets.UTF_8);
            }
            return baseline;
        } catch (IOException | InterruptedException | RuntimeException failure) {
            try {
                baseline.close();
            } catch (IOException | InterruptedException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    static String branchRef(String branch) {
        if (branch.startsWith("refs/") && !branch.startsWith("refs/heads/")) {
            throw new IllegalArgumentException("With --base-remote, --base must name a remote branch such as main or release/1.0");
        }
        return branch.startsWith("refs/heads/") ? branch : "refs/heads/" + branch;
    }

    String commit() {
        return commit;
    }

    private void fetch(CliOptions options, String source, Path directory) throws IOException, InterruptedException {
        List<String> command = List.of("git", "-c", "credential.interactive=false", "-c", "core.askPass=",
                "-c", "core.hooksPath=/dev/null", "-c", "fetch.pruneTags=false",
                "-c", "remote." + options.baseRemote() + ".pruneTags=false",
                "fetch", "--no-tags", "--no-prune", "--no-recurse-submodules", "--no-write-fetch-head",
                "--no-auto-maintenance", "--no-write-commit-graph", "--refmap=", "--",
                options.baseRemote(), source + ":" + reference);
        Path stdout = directory.resolve("baseline-fetch-stdout.log");
        Path stderr = directory.resolve("baseline-fetch-stderr.log");
        Files.writeString(stdout, "");
        Files.writeString(stderr, "");
        long started = System.nanoTime();
        Instant startedAt = Instant.now();
        Integer exitCode = null;
        Exception executionFailure = null;
        try {
            CommandResult result = executor.run(command, repository, null, options.fetchTimeout(), stdout, stderr,
                    Map.of("GIT_TERMINAL_PROMPT", "0", "GCM_INTERACTIVE", "never"));
            exitCode = result.exitCode();
            if (exitCode != 0) {
                throw new IOException("Git fetch exited with code " + exitCode);
            }
        } catch (IOException | InterruptedException failure) {
            executionFailure = failure;
            if (failure instanceof InterruptedException interrupted) throw interrupted;
            throw new IOException("Could not fetch baseline " + options.baseRemote() + "/" + options.base()
                    + "; no local fallback was used. " + failure.getMessage() + ". See " + directory
                    + "/baseline-fetch-*.log", failure);
        } finally {
            String record = "command=" + EvidenceText.oneLine(String.join(" ", command))
                    + "\nworkingDirectory=" + EvidenceText.oneLine(repository.toString())
                    + "\nstartedAt=" + startedAt + "\ntimeoutSeconds=" + options.fetchTimeout().toSeconds()
                    + "\nelapsedMillis=" + Duration.ofNanos(System.nanoTime() - started).toMillis()
                    + "\nexitCode=" + (exitCode == null ? "unavailable (execution did not complete)" : exitCode) + "\n";
            try {
                Files.writeString(directory.resolve("baseline-fetch-execution.log"), record, StandardCharsets.UTF_8);
            } catch (IOException logFailure) {
                if (executionFailure == null) throw logFailure;
                executionFailure.addSuppressed(logFailure);
            }
        }
    }

    private static String savedCommit(Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("Cannot resume without " + path);
        String prefix = "- Resolved base: `";
        List<String> matches = Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                .filter(line -> line.startsWith(prefix) && line.endsWith("`"))
                .map(line -> line.substring(prefix.length(), line.length() - 1)).toList();
        if (matches.size() != 1 || !matches.getFirst().matches("(?:[0-9a-f]{40}|[0-9a-f]{64})")) {
            throw new IOException("Cannot resume: missing or invalid pinned baseline in " + path);
        }
        return matches.getFirst();
    }

    private String git(String... arguments) throws IOException, InterruptedException {
        var command = new ArrayList<>(List.of("git", "-c", "core.hooksPath=/dev/null"));
        command.addAll(List.of(arguments));
        CommandResult result = executor.run(command, repository, null, Duration.ofSeconds(30));
        if (result.exitCode() != 0) {
            throw new IOException("Git baseline command failed: " + String.join(" ", command) + "\n" + result.stderr());
        }
        return result.stdout().strip();
    }

    @Override
    public void close() throws IOException, InterruptedException {
        // A unique private ref avoids sharing origin/* or FETCH_HEAD with concurrent fetches.
        if (commit == null) git("update-ref", "-d", reference);
        else git("update-ref", "-d", reference, commit);
    }
}
