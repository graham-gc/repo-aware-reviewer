package reviewagent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class AdaptiveReviewTests {
    private static final ProcessExecutor EXECUTOR = new ProcessExecutor();
    private static final String BEFORE = "def average(values):\n    if not values:\n        return 0\n    return sum(values) / len(values)\n";
    private static final String AFTER = "def average(values):\n    return sum(values) / len(values)\n";

    static void run() throws Exception {
        Path fixture = Files.createTempDirectory("adaptive-review-");
        try {
            Path repository = Files.createDirectory(fixture.resolve("repo"));
            git(repository, "init", "-q");
            git(repository, "config", "user.name", "Review fixture");
            git(repository, "config", "user.email", "fixture@example.invalid");
            Files.writeString(repository.resolve("calculator.py"), BEFORE);
            Files.writeString(repository.resolve("helper.py"), "from calculator import average\nresult = average([])\n");
            Files.writeString(repository.resolve("app.py"), "def has_permission(user):\n    return user.active\n");
            git(repository, "add", ".");
            git(repository, "commit", "-qm", "base");
            Files.writeString(repository.resolve("calculator.py"), AFTER);
            git(repository, "commit", "-qam", "remove guard");
            Path agent = fixture.resolve("agent.sh");
            Files.writeString(agent, """
                    root=$1
                    prompt=$(cat)
                    case "$prompt" in
                      'Review this small change'*) cat "$root/local.xml" ;;
                      'You are the independent candidate verification'*)
                        case "$prompt" in
                          *'result = average([])'*'THE NEW EVIDENCE'*) cat "$root/verified.xml" ;;
                          *) exit 92 ;;
                        esac ;;
                      'You are the discovery stage'*) printf '<findings/>' ;;
                      'You are the report consolidation stage'*) cat "$root/report.txt" ;;
                      *) exit 91 ;;
                    esac
                    """);
            Files.writeString(fixture.resolve("report.txt"), String.join("\n\n", ReviewReport.headings(OutputLanguage.EN))
                    + "\n<finding-groups/>\n");
            var arguments = new ArrayList<>(List.of("review", "--repo", repository.toString(), "--output-dir",
                    fixture.resolve("runs").toString(), "--agent-command", "/bin/sh", "--agent-arg", agent.toString(),
                    "--agent-arg", fixture.toString(), "--agent-retries", "0", "--review-parallelism", "1"));
            var pipeline = new ReviewPipeline(EXECUTOR, Clock.systemUTC());
            String confirmed = decision("CONFIRMED", "", "LOW");
            Files.writeString(fixture.resolve("verified.xml"), "<verification>" + confirmed + "</verification>");
            writeLocal(fixture, confirmed);
            Path local = pipeline.run(options(arguments));
            check(Main.reviewExitCode(local) == 0 && calls(local, 1) && report(local).contains("ZeroDivisionError")
                    && report(local).contains("calculator.py:2 @"), "one call publishes a grounded local finding");
            check(Files.readString(local.resolve("coverage.md")).contains("- adaptive")
                    && !Files.exists(local.resolve("adjudication")), "local route and no publication round");

            pipeline.run(options(arguments, "--resume", local.toString()));
            check(calls(local, 0) && report(local).contains("ZeroDivisionError"), "a completed local review resumes from cache");

            writeLocal(fixture, decision("INSUFFICIENT_EVIDENCE", "REQUEST_FILE|target|calculator.py|all|Already supplied", "LOW"));
            Path unresolved = pipeline.run(options(arguments));
            check(calls(unresolved, 1) && report(unresolved).contains("Review required"), "already supplied evidence leaves an explicit pending item");

            writeLocal(fixture, confirmed + "</local-decision><local-decision>" + decision("INSUFFICIENT_EVIDENCE",
                    "REQUEST_FILE|target|helper.py|all|Confirm a call", "LOW").replace("average([])", "a caller passes an empty input"));
            Path targeted = pipeline.run(options(arguments));
            check(Main.reviewExitCode(targeted) == 0 && calls(targeted, 2)
                    && Files.exists(targeted.resolve("adaptive/verification/L-2-response.md"))
                    && !Files.exists(targeted.resolve("adaptive/verification/L-1-prompt.md")),
                    "new evidence reruns only the candidate that requested it");

            writeLocal(fixture, decision("CONFIRMED", "", "LOW").replace("<lines>2:2</lines>", "<lines>1:1</lines>"));
            Path fallback = pipeline.run(options(arguments));
            check(Main.reviewExitCode(fallback) == 0 && calls(fallback, 6)
                    && Files.readString(fallback.resolve("coverage.md")).contains("Local response failed validation"),
                    "unbound finding locations cannot be published and trigger deep review");

            Path forced = pipeline.run(options(arguments, "--review-mode", "deep"));
            check(calls(forced, 4) && !Files.exists(forced.resolve("adaptive")), "explicit deep review bypasses local review");

            Files.writeString(repository.resolve("app.py"), "def has_permission(user):\n    return True\n");
            git(repository, "commit", "-qam", "change permission");
            Path risk = pipeline.run(options(arguments));
            check(calls(risk, 4) && !Files.exists(risk.resolve("adaptive"))
                    && Files.readString(risk.resolve("coverage.md")).contains("indicators of broader impact"),
                    "a single-line permission change in a generic path starts in deep review");
        } finally {
            try (var paths = Files.walk(fixture)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static String decision(String status, String requests, String severity) {
        return """
                <status>%s</status><reason>The old guard handled empty input.</reason>
                <relation>INTRODUCED</relation><baseline>Empty input returned 0.</baseline>
                <contract>Prevent a reachable local zero divisor.</contract><support>REACHABLE_FAILURE</support>
                <premises><premise><statement>Empty lists have no elements, so the divisor is zero.</statement>
                  <basis>IMPLEMENTATION</basis><citations><citation><source>SRC-2</source><lines>2:2</lines></citation></citations>
                </premise></premises>
                <citations><citation><source>SRC-3</source><lines>2:3</lines></citation></citations>
                <finding><title>Empty-input failure</title><severity>%s</severity><path>calculator.py</path><line>2</line><endLine>2</endLine>
                  <trigger>average([])</trigger><impact>Raises ZeroDivisionError.</impact><evidence>Compare both revisions.</evidence>
                  <diffRelation>The change removes the guard.</diffRelation></finding>
                """.formatted(status, severity) + (requests.isEmpty() ? "" : "<requests>" + requests + "</requests>");
    }

    private static void writeLocal(Path fixture, String decisions) throws Exception {
        Files.writeString(fixture.resolve("local.xml"), "<adaptive-review><route>LOCAL</route><summary>The guard was removed.</summary>"
                + "<decisions><local-decision>" + decisions + "</local-decision></decisions></adaptive-review>");
    }

    private static CliOptions options(List<String> arguments, String... extra) {
        var copy = new ArrayList<>(arguments);
        copy.addAll(List.of(extra));
        return CliOptions.parse(copy.toArray(String[]::new));
    }

    private static boolean calls(Path run, int count) throws Exception {
        return Files.readString(run.resolve("timings.md")).contains("Actual launched attempts: " + count + "\n");
    }

    private static String report(Path run) throws Exception { return Files.readString(run.resolve("report.md")); }

    private static void git(Path repository, String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));
        var result = EXECUTOR.run(command, repository, null, Duration.ofSeconds(10));
        check(result.exitCode() == 0, result.stderr());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
