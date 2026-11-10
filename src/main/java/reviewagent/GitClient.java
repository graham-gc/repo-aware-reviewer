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

    record Change(String path, boolean deleted, String patch) {
    }

    private record ChangedPath(char status, String previousPath, String path) {
    }

    List<Change> changes(ReviewScope scope) throws IOException, InterruptedException {
        String listing = reviewDiff(scope, List.of(), "--name-status", "-z");
        if (listing.isEmpty()) {
            return List.of();
        }
        String[] fields = listing.split("\0");
        List<ChangedPath> paths = new ArrayList<>();
        for (int i = 0; i < fields.length; ) {
            char status = fields[i++].charAt(0);
            String path = fields[i++];
            if (status == 'R' || status == 'C') {
                paths.add(new ChangedPath(status, path, fields[i++]));
            } else {
                paths.add(new ChangedPath(status, null, path));
            }
        }
        String[] patches = reviewDiff(scope, List.of(), "--patch", "--unified=8").split("(?m)(?=^diff --git )");
        // Git can emit separate deletion and addition patches for one file type change.
        boolean needsPathLookup = paths.size() != patches.length || paths.stream().anyMatch(path -> path.status() == 'T');
        List<Change> changes = new ArrayList<>(paths.size());
        for (int i = 0; i < paths.size(); i++) {
            ChangedPath path = paths.get(i);
            String patch = needsPathLookup ? reviewDiff(scope, path.previousPath() == null ? List.of(path.path())
                    : List.of(path.previousPath(), path.path()), "--patch", "--unified=8") : patches[i];
            changes.add(new Change(path.path(), path.status() == 'D', patch));
        }
        return List.copyOf(changes);
    }

    private String reviewDiff(ReviewScope scope, List<String> paths, String... flags) throws IOException, InterruptedException {
        List<String> args = new ArrayList<>(List.of("--literal-pathspecs", "diff", "--no-ext-diff", "--no-textconv",
                "--no-color", "--no-relative", "--submodule=short", "--ignore-submodules=none", "--find-renames"));
        args.addAll(List.of(flags));
        args.addAll(List.of(scope.baseCommit(), scope.targetCommit(), "--"));
        args.addAll(paths);
        return run(args.toArray(String[]::new));
    }

    String fileAt(String commit, String path, int maxBytes) throws IOException, InterruptedException {
        CommandResult result = execute(List.of("git", "show", commit + ":" + path));
        if (result.exitCode() != 0) {
            return "[unavailable at " + commit + ": " + path + "]";
        }
        return truncate(result.stdout(), maxBytes);
    }

    String resolveCommit(String ref) throws IOException, InterruptedException {
        requireRepository();
        return commit(ref);
    }

    List<String> trackedFiles(String commit) throws IOException, InterruptedException {
        String output = run("ls-tree", "-r", "--name-only", commit);
        return output.lines().filter(line -> !line.isBlank()).sorted().toList();
    }

    long fileSizeAt(String commit, String path) throws IOException, InterruptedException {
        String value = oneLine("cat-file", "-s", commit + ":" + path);
        return Long.parseLong(value);
    }

    boolean existsAt(String commit, String path) throws IOException, InterruptedException {
        return execute(List.of("git", "cat-file", "-e", commit + ":" + path)).exitCode() == 0;
    }

    String regularBlobAt(String commit, String path, int maxBytes) throws IOException, InterruptedException {
        String entry = run("--literal-pathspecs", "ls-tree", "--full-tree", "-z", commit, "--", path);
        int tab = entry.indexOf('\t');
        if (tab < 0 || !entry.substring(tab + 1).equals(path + "\0")
                || !(entry.startsWith("100644 blob ") || entry.startsWith("100755 blob "))) {
            throw new IOException("No regular tracked file at that revision");
        }
        String oid = entry.substring("100644 blob ".length(), tab);
        if (Long.parseLong(oneLine("cat-file", "-s", oid)) > maxBytes) {
            throw new IOException("File exceeds the available byte budget");
        }
        String content = run("cat-file", "blob", oid);
        if (content.indexOf('\0') >= 0) {
            throw new IOException("File is not text");
        }
        return content;
    }

    private void requireRepository() throws IOException, InterruptedException {
        if (!"true".equals(oneLine("rev-parse", "--is-inside-work-tree"))) {
            throw new IllegalArgumentException("Not a Git work tree: " + repository);
        }
    }

    private String commit(String ref) throws IOException, InterruptedException {
        return oneLine("rev-parse", "--verify", "--end-of-options", ref + "^{commit}");
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

