package reviewagent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class ReviewRequestTests {
    private final ProcessExecutor executor = new ProcessExecutor();
    private final ReviewPipeline pipeline = new ReviewPipeline(executor, Clock.systemUTC());
    private final Path root;
    private final Path repository;
    private final Path model;

    private ReviewRequestTests(Path root) throws Exception {
        this.root = root;
        repository = Files.createDirectory(root.resolve("repo"));
        model = root.resolve("model.sh");
        git("init", "-q");
        git("config", "user.name", "Request fixture");
        git("config", "user.email", "fixture@example.invalid");
        Files.writeString(model, """
                fixture=$1
                prompt=$(cat)
                case "$prompt" in
                  'Review the supplied scope'*)
                    case "$prompt" in
                      *'Question: Trace who can trigger'*)
                        if [ -f "$fixture/expand.xml" ]; then cat "$fixture/expand.xml"; exit; fi ;;
                    esac
                    printf '<adaptive-review><route>LOCAL</route><summary>Reviewed fixture.</summary><decisions/></adaptive-review>' ;;
                  'You are the independent candidate verification'*)
                    case "$prompt" in
                      *'The scheduler may still block'*'APPROVED_ONLY'*)
                        printf '<verification><status>REJECTED</status><reason>The supplied scheduler only accepts approved work.</reason></verification>' ;;
                      *) exit 91 ;;
                    esac ;;
                  *) exit 91 ;;
                esac
                """);
    }

    static void run() throws Exception {
        Path fixture = Files.createTempDirectory("review-requests-");
        try {
            new ReviewRequestTests(Files.createDirectory(fixture.resolve("followup"))).followup();
            new ReviewRequestTests(Files.createDirectory(fixture.resolve("discovery"))).discovery();
            new ReviewRequestTests(Files.createDirectory(fixture.resolve("java"))).javaCaller();
            new ReviewRequestTests(Files.createDirectory(fixture.resolve("expansion"))).expansion();
        } finally {
            try (var paths = Files.walk(fixture)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private void followup() throws Exception {
        write("a.txt", "before\n");
        write("b.txt", "before\n");
        write("c.txt", "before\n");
        commit("base");
        write("a.txt", "large edit\n".repeat(5700));
        write("b.txt", "second edit\n".repeat(480));
        write("c.txt", "third edit\n".repeat(480));
        commit("all remaining hunks fit together");
        var reviewed = review();
        var prompt = Files.readString(reviewed.resolve("focused/T-prompt.md"));
        check(Main.reviewExitCode(reviewed) == 0 && calls(reviewed, 1)
                && prompt.contains("large edit") && prompt.contains("second edit") && prompt.contains("third edit"),
                "remaining hunks share one request when their total fits");

        write("a.txt", "new large edit\n".repeat(4600));
        write("b.txt", "new second edit\n".repeat(4500));
        write("c.txt", "new third edit\n".repeat(4300));
        commit("several large hunks");
        var oversized = review();
        check(Main.reviewExitCode(oversized) == 3
                && Files.readString(oversized.resolve("review-status.md")).contains("Unreviewed change"),
                "a hunk larger than a single request is still disclosed");
    }

    private void discovery() throws Exception {
        write("Stable.java", "class Stable {\n    int value = 2;\n" + "    // deliberately stable context\n".repeat(20)
                + "    int boundaryValue = 190923;\n}\n");
        commit("base");
        write("Plain.java", "class Plain {\n    int value() { return 3; }\n}\n");
        write("Guard.java", "class Guard {\n    boolean authorize(boolean approved) { return approved; }\n}\n");
        write("docs/design.md", "# Design\nKeep the established return value for existing callers.\n");
        var stable = repository.resolve("Stable.java");
        Files.writeString(stable, Files.readString(stable).replace("value = 2", "value = 3"));
        commit("prepare different discovery evidence views");
        var run = review("--prepare-only", "--review-mode", "deep");
        var correct = Files.readString(run.resolve("discovery/R1-prompt.md"));
        var contract = Files.readString(run.resolve("discovery/R2-prompt.md"));
        var boundary = Files.readString(run.resolve("discovery/R3-prompt.md"));
        check(correct.contains("### Plain.java at ") && !contract.contains("### Plain.java at ") && !boundary.contains("### Plain.java at ")
                && contract.contains("+    int value() { return 3; }") && boundary.contains("+    int value() { return 3; }")
                && contract.contains("### docs/design.md at ") && boundary.contains("### Guard.java at ")
                && contract.contains("boundaryValue = 190923") && boundary.contains("boundaryValue = 190923"),
                "focused discovery drops duplicate views but retains all changed lines and context outside the diff");
    }

    private void javaCaller() throws Exception {
        String policy = "package policy; public class Policy { public boolean authorize(boolean allowed) { return allowed; } }\n";
        String caller = """
                package entry;
                import policy.Policy;
                public class Entry {
                    private final Policy policy = new Policy();
                    public boolean submit(boolean permitted) {
                        return policy.authorize(permitted);
                    }
                }
                """;
        write("policy/Policy.java", policy);
        write("entry/Entry.java", caller.replace("        return policy", "        if (!permitted) return false; // pinned guard\n        return policy"));
        write("entry/Fake.java", "package entry; import policy.Policy; class Fake { /* new Policy().authorize(false) */ }\n");
        write("wrong/Policy.java", "package wrong; public class Policy { public boolean authorize(boolean a) { return a; } }\n");
        write("wrong/Entry.java", caller.replace("package entry;", "package wrong;").replace("import policy.Policy;", ""));
        commit("base with a caller guard");
        write("policy/Policy.java", policy.replace("return allowed", "return true"));
        commit("change the authorization predicate");
        write("entry/Entry.java", caller + "// WORKTREE_ONLY\n");
        var guarded = review();
        var first = Files.readString(guarded.resolve("focused/F1-prompt.md"));
        check(Main.reviewExitCode(guarded) == 0 && calls(guarded, 1) && first.contains("pinned guard")
                && first.contains("policy.authorize(permitted)") && !first.contains("WORKTREE_ONLY") && !first.contains("entry/Fake.java")
                && !first.contains("wrong/Entry.java"), "only the pinned Java caller with the matching receiver type reaches the first request");

        write("entry/Entry.java", caller);
        write("policy/Policy.java", policy);
        commit("base without a caller guard");
        write("policy/Policy.java", policy.replace("return allowed", "return true"));
        commit("apply the same predicate change");
        var unguarded = review();
        var second = Files.readString(unguarded.resolve("focused/F1-prompt.md"));
        check(Main.reviewExitCode(unguarded) == 0 && calls(unguarded, 1) && second.contains("policy.authorize(permitted)")
                && !second.contains("pinned guard"), "the same Java diff carries the unguarded caller without extra initial requests");
    }

    private void expansion() throws Exception {
        write("auth/check.py", "def authorize(approved):\n    return approved\n");
        write("queue/schedule.py", "APPROVED_ONLY = True\nlimit = 1\n");
        write("work/other.py", "EXTRANEOUS_CONTEXT = 1\n");
        commit("base");
        write("auth/check.py", "def authorize(approved):\n    return True\n");
        write("queue/schedule.py", "APPROVED_ONLY = True\nlimit = 2\n");
        write("work/other.py", "EXTRANEOUS_CONTEXT = 2\n");
        commit("expand only one candidate across scopes");
        Files.writeString(root.resolve("expand.xml"), """
                <adaptive-review><route>DEEP</route><summary>The caller contract needs checking.</summary><decisions>
                  <local-decision><status>INSUFFICIENT_EVIDENCE</status>
                    <reason>Check the work admission rule in queue/schedule.py.</reason>
                    <finding><title>The scheduler may still block this request</title><path>auth/check.py</path>
                    <line>2</line><endLine>2</endLine><trigger>Unapproved work reaches authorize</trigger>
                    <impact>Rejected work could proceed</impact><evidence>The target returns true</evidence>
                    <diffRelation>The predicate changed</diffRelation></finding>
                  </local-decision>
                </decisions></adaptive-review>
                """);
        var run = review();
        var verify = Files.readString(run.resolve("focused/F1/F1-1-prompt.md"));
        check(Main.reviewExitCode(run) == 0 && calls(run, 3) && verify.contains("APPROVED_ONLY")
                && verify.contains("A method's comment or a guard in a caller is IMPLEMENTATION evidence")
                && !verify.contains("EXTRANEOUS_CONTEXT") && !Files.exists(run.resolve("discovery"))
                && Files.readString(run.resolve("coverage.md")).contains("Expanded focused/F1/F1-1 with collected implementation: queue/schedule.py.")
                && !Files.readString(run.resolve("report.md")).contains("scheduler may still block"),
                "requested extra scope reuses relevant evidence for only one independently verified candidate");
        pipeline.run(options("--resume", run.toString()));
        check(calls(run, 0), "targeted expansion can reuse its validated checkpoint");

        var response = root.resolve("expand.xml");
        Files.writeString(response, Files.readString(response).replace("queue/schedule.py", "queue/missing.py"));
        var unavailable = review();
        check(calls(unavailable, 2) && !Files.exists(unavailable.resolve("focused/F1/F1-1-prompt.md"))
                && Files.readString(unavailable.resolve("coverage.md")).contains("scope expansion: Stopped; no new evidence")
                && Files.readString(unavailable.resolve("report.md")).contains("The scheduler may still block this request"),
                "an expansion without relevant new evidence retains the gap and does not launch a model call");
    }

    private void write(String relative, String content) throws Exception {
        var file = repository.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private void commit(String message) throws Exception {
        git("add", ".");
        git("commit", "-qm", message);
    }

    private void git(String... args) throws Exception {
        var command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        var result = executor.run(command, repository, null, Duration.ofSeconds(10));
        check(result.exitCode() == 0, result.stderr());
    }

    private Path review(String... additional) throws Exception { return pipeline.run(options(additional)); }

    private CliOptions options(String... additional) {
        var args = new ArrayList<>(List.of("review", "--repo", repository.toString(), "--output-dir", root.resolve("runs").toString(),
                "--agent-command", "/bin/sh", "--agent-arg", model.toString(), "--agent-arg", root.toString(),
                "--review-parallelism", "1", "--agent-retries", "0"));
        args.addAll(List.of(additional));
        return CliOptions.parse(args.toArray(String[]::new));
    }

    private static boolean calls(Path run, int count) throws Exception {
        return Files.readString(run.resolve("timings.md")).contains("Actual launched attempts: " + count + "\n");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
