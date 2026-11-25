package reviewagent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

final class IncrementalReviewTests {
    static void run() throws Exception {
        Path root = Files.createTempDirectory("review-incremental-");
        Path repo = Files.createDirectory(root.resolve("repo"));
        git(repo, "init", "-q");
        for (int i = 1; i <= 10; i++) write(repo, path(i), code(i, 0));
        write(repo, "configuration.yaml", "engine: original\n");
        git(repo, "add", "--all");
        git(repo, "commit", "-qm", "base");
        String base = git(repo, "rev-parse", "HEAD").strip();
        for (int i = 1; i <= 10; i++) write(repo, path(i), code(i, 1));
        commit(repo, "ten changed files");
        var first = run(root, repo, base, "--incremental");
        String firstCoverage = Files.readString(first.resolve("coverage.md"));
        check(count(firstCoverage, " — REVIEWED (R1-P") == 10 && calls(first) == 15,
                "ten file checks and the five full-scope roles ran on the initial version");

        write(repo, path(1), code(1, 2));
        write(repo, path(11), code(11, 1));
        write(repo, path(12), code(12, 1));
        commit(repo, "one update and two creations");
        var second = run(root, repo, base, "--previous-run", first.toString());
        String secondCoverage = Files.readString(second.resolve("coverage.md"));
        check(secondCoverage.contains("Paths changed since the previous head: 3.")
                && count(secondCoverage, " — REUSED (R1-P") == 9
                && count(secondCoverage, " — REVIEWED (R1-P") == 3 && calls(second) == 8,
                "nine unchanged local checks were reused; the current twelve-file scope remained covered");
        check(Files.readString(second.resolve("discovery/R7-prompt.md")).contains(path(10))
                && Files.readString(second.resolve("discovery/R7-prompt.md")).contains(path(12)),
                "cross-file correctness includes the old and newly added paths");

        write(repo, "consumer/new_caller.py", "from anchor_04 import run_anchor_04\nresult = run_anchor_04()\n");
        commit(repo, "new dependent caller");
        var third = run(root, repo, base, "--previous-run", second.toString());
        String thirdCoverage = Files.readString(third.resolve("coverage.md"));
        check(thirdCoverage.contains(path(4) + " — REVIEWED (R1-P")
                && thirdCoverage.contains(path(5) + " — REUSED (R1-P") && calls(third) == 7,
                "a new caller outside the original directory refreshes its old target while peers reuse");

        write(repo, "configuration.yaml", "engine: replacement\n");
        write(repo, path(5), code(5, 0));
        commit(repo, "a global configuration change and a reverted file");
        var fourth = run(root, repo, base, "--previous-run", third.toString());
        String fourthCoverage = Files.readString(fourth.resolve("coverage.md"));
        check(count(fourthCoverage, " — REUSED (R1-P") == 0 && calls(fourth) == 17
                && !fourthCoverage.contains(path(5) + " — REVIEWED")
                && Files.readString(fourth.resolve("discovery/R7-prompt.md")).contains("+engine: replacement"),
                "configuration invalidates the prior decisions, reverted files leave the scope, and global review receives configuration");

        var otherBase = git(repo, "rev-parse", "HEAD^").strip();
        var incompatible = run(root, repo, otherBase, "--previous-run", fourth.toString());
        check(Files.readString(incompatible.resolve("coverage.md")).contains("different repository, baseline or cache format")
                && calls(incompatible) > 5, "a changed baseline cannot use previous local decisions");
        rebindVerification(Files.createDirectory(root.resolve("proof")));
    }

    private static Path run(Path root, Path repo, String base, String... options) throws Exception {
        return runWithAgent(root, repo, base, "cat >/dev/null; printf '<findings/>'", options);
    }

    private static void rebindVerification(Path root) throws Exception {
        Path repo = Files.createDirectory(root.resolve("repo"));
        git(repo, "init", "-q");
        String path = "component/worker.py";
        String before = "    return 'expected response token'";
        String after = "    return 'unexpected response token'";
        write(repo, path, "def reviewed_worker():\n" + before + "\n");
        write(repo, "unrelated/heartbeat.py", "pulse = 0\n");
        commit(repo, "baseline");
        String base = git(repo, "rev-parse", "HEAD").strip();
        write(repo, path, "def reviewed_worker():\n" + after + "\n");
        write(repo, "unrelated/heartbeat.py", "pulse = 1\n");
        commit(repo, "initial review");
        String finding = "<title>Worker returns an unexpected token</title><severity>MEDIUM</severity>"
                + "<path>" + path + "</path><line>2</line><endLine>2</endLine>"
                + "<trigger>A caller invokes the worker.</trigger><impact>The result contains the unexpected token.</impact>"
                + "<evidence>The pinned code returns a different token.</evidence><diffRelation>The patch changes the returned token.</diffRelation>";
        String changed = "<citation><source>DIFF_REF</source><quote><![CDATA[+" + after + "]]></quote></citation>";
        String old = changed.replace("+" + after, "-" + before);
        String verdict = "<verification><status>CONFIRMED</status><reason>The return value changed.</reason>"
                + "<relation>INTRODUCED</relation><baseline>The old token was expected.</baseline>"
                + "<contract>The return value is externally observable.</contract><support>REACHABLE_FAILURE</support>"
                + "<triggerCitations>" + changed + "</triggerCitations><outcomeCitations>" + changed + "</outcomeCitations>"
                + "<citations>" + old + changed + "</citations><finding id=\"CANDIDATE_ID\">" + finding + "</finding></verification>";
        String script = """
                prompt=$(cat)
                case "$prompt" in
                  'You are the independent candidate verification'*)
                    id=$(printf '%s\\n' "$prompt" | sed -n 's/^.*<finding id="\\([^"]*\\)">.*$/\\1/p' | head -1)
                    ref=$(printf '%s\\n' "$prompt" | awk '/^-[[:space:]]+SRC-[0-9]+: component\\/worker\\.py \\[source\\]$/ {getline; sub(/^.*evidence-id: /, ""); print; exit}')
                    test -n "$id" && test -n "$ref" || exit 91
                    sed -e "s/DIFF_REF/$ref/g" -e "s/CANDIDATE_ID/$id/g" <<'CHECKED'
                VERDICT_XML
                CHECKED
                    ;;
                  *'This task covers the assigned changed file.'*'def reviewed_worker'*)
                    cat <<'DISCOVERED'
                FINDINGS_XML
                DISCOVERED
                    ;;
                  *) printf '<findings/>' ;;
                esac
                """.replace("VERDICT_XML", verdict).replace("FINDINGS_XML", "<findings><finding>" + finding + "</finding></findings>");
        var first = runWithAgent(root, repo, base, script, "--incremental");
        check(Files.readString(first.resolve("report.md")).contains("Worker returns an unexpected token"),
                "the original independently verified result is published");
        write(repo, "unrelated/heartbeat.py", "pulse = 2\n");
        commit(repo, "unchanged worker");
        var second = runWithAgent(root, repo, base, script, "--previous-run", first.toString());
        String timings = Files.readString(second.resolve("timings.md"));
        check(timings.contains("- Tasks reused from a previous review: 2")
                && !timings.contains("|verification|1|")
                && Files.readString(second.resolve("report.md")).contains("Worker returns an unexpected token")
                && Files.readString(second.resolve("coverage.md")).contains(git(repo, "rev-parse", "HEAD").strip()),
                "the previous discovery and verification are reused and the report binds them to the current head");
    }

    private static Path runWithAgent(Path root, Path repo, String base, String script, String... options) throws Exception {
        var args = new ArrayList<>(List.of("review", "--repo", repo.toString(), "--output-dir", root.resolve("reviews").toString(),
                "--base", base, "--branch", "HEAD", "--agent-command", "/bin/sh", "--agent-arg", "-c", "--agent-arg",
                script));
        args.addAll(List.of(options));
        Path result = new ReviewPipeline(new ProcessExecutor(), Clock.systemUTC()).run(CliOptions.parse(args.toArray(String[]::new)));
        check(Main.reviewExitCode(result) == 0, "incremental review has complete current coverage");
        return result;
    }

    private static int calls(Path result) throws Exception {
        String line = Files.readString(result.resolve("timings.md")).lines().filter(s -> s.startsWith("- Actual launched attempts: "))
                .findFirst().orElseThrow();
        return Integer.parseInt(line.substring(line.lastIndexOf(' ') + 1));
    }

    private static long count(String text, String part) {
        return text.lines().filter(line -> line.contains(part)).count();
    }

    private static String path(int i) { return "module_" + i + "/anchor_%02d.py".formatted(i); }
    private static String code(int i, int value) { return "def run_anchor_%02d():\n    return %d\n".formatted(i, value); }

    private static void write(Path repo, String name, String text) throws Exception {
        Path path = repo.resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
    }

    private static void commit(Path repo, String message) throws Exception {
        git(repo, "add", "--all");
        git(repo, "commit", "-qm", message);
    }

    private static String git(Path repo, String... args) throws Exception {
        var command = new ArrayList<>(List.of("git", "-c", "user.name=Incremental Test", "-c", "user.email=incremental@example.com"));
        command.addAll(List.of(args));
        var result = new ProcessExecutor().run(command, repo, null, Duration.ofSeconds(15));
        check(result.exitCode() == 0, "Git fixture setup succeeded");
        return result.strictUtf8Stdout();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
