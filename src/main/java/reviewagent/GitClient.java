package reviewagent;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class GitClient {
    private final Path repository;
    private final ProcessExecutor executor;

    GitClient(Path repository, ProcessExecutor executor) {
        this.repository = repository;
        this.executor = executor;
    }

    ReviewScope resolve(CliOptions options) throws IOException, InterruptedException {
        requireRepository();
        if (options.range() != null) {
            String[] parts = splitRange(options.range());
            String base = commit(parts[0]);
            String target = commit(parts[1]);
            return new ReviewScope(ReviewScope.Mode.RANGE, parts[0], parts[1], base, target, base + ".." + target);
        }
        if (options.branch() != null) {
            if (options.base() == null) {
                throw new IllegalArgumentException("--branch requires --base so the branch baseline is explicit");
            }
            String target = commit(options.branch());
            String requestedBase = commit(options.base());
            String mergeBase = oneLine("merge-base", requestedBase, target);
            return new ReviewScope(ReviewScope.Mode.BRANCH, options.base(), options.branch(),
                    mergeBase, target, mergeBase + ".." + target);
        }
        String requested = options.commit() == null ? "HEAD" : options.commit();
        String target = commit(requested);
        String base = commit(target + "^1");
        return new ReviewScope(ReviewScope.Mode.COMMIT, target + "^1", requested,
                base, target, base + ".." + target);
    }

    String diff(ReviewScope scope, int maxBytes) throws IOException, InterruptedException {
        return truncate(run("diff", "--no-ext-diff", "--find-renames", "--find-copies",
                scope.baseCommit(), scope.targetCommit(), "--"), maxBytes);
    }

    List<String> changedFiles(ReviewScope scope) throws IOException, InterruptedException {
        String output = run("diff", "--name-only", "--diff-filter=ACMRTUXB",
                scope.baseCommit(), scope.targetCommit(), "--");
        return output.lines().filter(line -> !line.isBlank()).toList();
    }

    String fileAt(String commit, String path, int maxBytes) throws IOException, InterruptedException {
        CommandResult result = execute(List.of("git", "show", commit + ":" + path));
        if (result.exitCode() != 0) {
            return "[unavailable at " + commit + ": " + path + "]";
        }
        return truncate(result.stdout(), maxBytes);
    }

    boolean existsAt(String commit, String path) throws IOException, InterruptedException {
        return execute(List.of("git", "cat-file", "-e", commit + ":" + path)).exitCode() == 0;
    }

    private void requireRepository() throws IOException, InterruptedException {
        if (!"true".equals(oneLine("rev-parse", "--is-inside-work-tree"))) {
            throw new IllegalArgumentException("Not a Git work tree: " + repository);
        }
    }

    private String commit(String ref) throws IOException, InterruptedException {
        return oneLine("rev-parse", "--verify", ref + "^{commit}");
    }

    private String oneLine(String... args) throws IOException, InterruptedException {
        return run(args).trim();
    }

    private String run(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(Arrays.asList(args));
        CommandResult result = execute(command);
        if (result.exitCode() != 0) {
            throw new IOException("Git command failed: " + String.join(" ", command)
                    + System.lineSeparator() + result.stderr());
        }
        return result.stdout();
    }

    private CommandResult execute(List<String> command) throws IOException, InterruptedException {
        return executor.run(command, repository, null, Duration.ofSeconds(30));
    }

    static String[] splitRange(String value) {
        int separator = value.indexOf("..");
        if (separator <= 0 || separator + 2 >= value.length() || value.indexOf("...", separator) >= 0) {
            throw new IllegalArgumentException("--range must use BASE..TARGET");
        }
        return new String[]{value.substring(0, separator), value.substring(separator + 2)};
    }

    static String truncate(String value, int maxBytes) {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return value;
        }
        return new String(bytes, 0, maxBytes, java.nio.charset.StandardCharsets.UTF_8)
                + "\n\n[truncated after " + maxBytes + " bytes]\n";
    }
}

