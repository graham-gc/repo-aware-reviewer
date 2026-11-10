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

    record Change(String path, String previousPath, boolean deleted, String patch) {
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
            changes.add(new Change(path.path(), path.previousPath(), path.status() == 'D', patch));
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

    String resolveCommit(String ref) throws IOException, InterruptedException {
        requireRepository();
        return commit(ref);
    }

    record TrackedFile(String path, String objectId, long size, boolean regular) {
    }

    List<TrackedFile> trackedFiles(String commit) throws IOException, InterruptedException {
        String output = run("ls-tree", "--full-tree", "-r", "-l", "-z", commit);
        List<TrackedFile> files = new ArrayList<>();
        for (String entry : output.split("\0")) {
            if (entry.isEmpty()) {
                continue;
            }
            int tab = entry.indexOf('\t');
            String[] fields = tab < 0 ? new String[0] : entry.substring(0, tab).split("\\s+");
            if (fields.length != 4) {
                throw new IOException("Unexpected Git tree entry format");
            }
            boolean regular = "blob".equals(fields[1]) && ("100644".equals(fields[0]) || "100755".equals(fields[0]));
            long size = "-".equals(fields[3]) ? 0 : Long.parseLong(fields[3]);
            files.add(new TrackedFile(entry.substring(tab + 1), fields[2], size, regular));
        }
        return List.copyOf(files);
    }

    String blob(String objectId) throws IOException, InterruptedException {
        return run("cat-file", "blob", objectId);
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
        String content = blob(oid);
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

}

