package reviewagent;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Fault injection against real local subprocesses; no model or target project commands are used. */
final class AgentStabilityTests {
    static void run() throws Exception {
        policies();
        claudeStreaming();
        retriesAndArtifacts();
        sharedBudget();
        independentFailures();
        fatalFailureStopsQueuedCalls();
        processOwnership();
        shutdownCleanup();
        checkpointsAndStorage();
        discoveryRecovery();
        sharedStageFailure();
    }

    private static void claudeStreaming() throws Exception {
        Path dir = Files.createTempDirectory("review-claude-stream-");
        try {
            Path command = dir.resolve("claude");
            Files.writeString(command, """
                    #!/bin/sh
                    input=$(cat)
                    case " $* " in
                      *' --output-format json --json-schema '*)
                        printf '%s\n' '{"type":"result","structured_output":{"ok":true}}'
                        exit ;;
                      *' --output-format stream-json --verbose --include-partial-messages '*) ;;
                      *) exit 91 ;;
                    esac
                    printf '%s\n' '{"type":"system","subtype":"init"}'
                    printf '%s\n' '{"type":"stream_event","event":{"type":"message_start"}}'
                    printf '%s' '{"type":"stream_event","event":{"type":"content_block_delta",'
                    sleep 0.02
                    printf '%s\n' '"delta":{"type":"thinking_delta","thinking":"private diagnostic text"}}}'
                    sleep 0.02
                    printf '%s\n' '{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"ignored partial"}}}'
                    case "$input" in
                      partial) exit ;;
                      rate) printf '%s\n' '{"type":"result","subtype":"error_during_execution","is_error":true,"errors":["API Error: 429"]}'
                        exit 1 ;;
                    esac
                    printf '%s' '{"type":"result","subtype":"success","is_error":false,"result":"<findings/>","duration_ms":100,"duration_api_ms":75,"ttft_stream_ms":14,"ttft_ms":20,"num_turns":1}'
                    """);
            require(command.toFile().setExecutable(true), "can launch the local Claude fixture");
            var timeout = Duration.ofSeconds(5);
            var runner = new AgentRunner(new ProcessExecutor(), command.toString(), AgentRunner.defaultArguments(), dir, timeout,
                    new AgentPolicy(0, 1, timeout, Duration.ZERO));
            try (var task = runner.task(null, null, 2, "request", null, false)) {
                require(runner.invoke("request", dir.resolve("R5")).equals("<findings/>"), "existing validators receive the terminal text only");
            }
            String execution = Files.readString(dir.resolve("R5-execution.log"));
            require(execution.contains("claudeApiDurationMillis=75\n") && execution.contains("claudeTtftStreamMillis=14\n")
                    && execution.matches("(?s).*firstThinkingDeltaMillis=[0-9]+\\n.*firstTextDeltaMillis=[0-9]+\\n.*")
                    && Files.readString(dir.resolve("R5-stdout.log")).equals("<findings/>")
                    && runner.timings().markdown(dir, OutputLanguage.EN).contains("## Claude stream timing"),
                    "timing uses live thinking and text events without retaining thinking contents");
            for (var expected : java.util.Map.of("partial", AgentCallException.Kind.STRUCTURED_OUTPUT,
                    "rate", AgentCallException.Kind.RATE_LIMIT).entrySet()) {
                try (var task = runner.task(null, null, 2, expected.getKey(), null, false)) {
                    runner.invoke(expected.getKey(), dir.resolve(expected.getKey()));
                    throw new AssertionError("Incomplete or failed stream was accepted");
                } catch (AgentCallException failure) {
                    require(failure.kind() == expected.getValue(), "incomplete and failed streams retain their failure classification");
                }
            }
            try (var task = runner.task(null, "{}", 2, "schema", null, false)) {
                require(runner.invoke("schema").contains("\"structured_output\""), "schema tasks retain their structured stdout contract");
            }
        } finally {
            try (var paths = Files.walk(dir)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void policies() {
        var defaults = CliOptions.parse(new String[]{"review", "--repo", "."});
        require(defaults.adaptiveParallelism() && defaults.agentPolicy().maxCalls() == 5
                && defaults.agentPolicy().retries() == 1 && defaults.agentPolicy().taskTimeout().toSeconds() == 1800,
                "automatic parallelism preserves explicit call and elapsed-time budgets");
        var custom = CliOptions.parse(new String[]{"review", "--repo", ".", "--agent-retries", "0",
                "--agent-max-calls-per-task", "2", "--agent-task-timeout-seconds", "7"});
        require(custom.agentPolicy().retries() == 0 && custom.agentPolicy().maxCalls() == 2
                && custom.agentPolicy().taskTimeout().toSeconds() == 7, "review recovery is configurable");
        var knowledge = KnowledgeOptions.parse(new String[]{"knowledge", "generate", "--repo", ".", "--agent-retries", "0"});
        require(knowledge.agentPolicy().retries() == 0, "knowledge extraction uses the same retry policy");
        for (String[] option : List.of(new String[]{"--agent-retries", "-1"}, new String[]{"--agent-max-calls-per-task", "0"},
                new String[]{"--agent-task-timeout-seconds", "0"})) {
            try {
                CliOptions.parse(new String[]{"review", "--repo", ".", option[0], option[1]});
                throw new AssertionError("Invalid recovery budget accepted");
            } catch (IllegalArgumentException expected) { }
        }
    }

    private static void retriesAndArtifacts() throws Exception {
        Path dir = Files.createTempDirectory("review-agent-retries-");
        String counting = "cat >/dev/null; n=0; [ ! -f count ] || n=$(cat count); n=$((n+1)); printf '%s' \"$n\" > count; ";
        var agent = shell(dir, counting + "[ \"$n\" -eq 1 ] && exit 0; printf VALID", 1, 5, Duration.ofSeconds(2));
        require(agent.invoke("中文 prompt", dir.resolve("empty")).equals("VALID"), "one empty response can recover");
        require(Files.readString(dir.resolve("count")).equals("2"), "empty output uses only one extra attempt");
        String empty = Files.readString(dir.resolve("empty-attempts/attempt-0001/call-execution.log"));
        String resumed = Files.readString(dir.resolve("empty-attempts/attempt-0002/call-execution.log"));
        require(empty.contains("terminationReason=EMPTY_RESPONSE")
                && resumed.contains("terminationReason=SUCCEEDED")
                && Files.size(dir.resolve("empty-attempts/attempt-0001/call-stdout.log")) == 0,
                "original empty output and the successful retry remain independently inspectable");
        require(empty.contains("firstStdoutMillis=unavailable") && observed(resumed, "firstStdoutMillis")
                && observed(resumed, "processStartedMillis") && observed(resumed, "stdinClosedMillis")
                && observed(resumed, "processExitObservedMillis"),
                "process timing records each attempt independently, including missing stdout");
        require(Files.readString(dir.resolve("empty-execution.log")).contains("inputBytes=13"), "input measurement uses UTF-8 bytes");
        Files.delete(dir.resolve("count"));
        shell(dir, counting + "[ \"$n\" -ne 1 ] || { printf 'HTTP 503: unavailable' >&2; exit 1; }; printf VALID",
                1, 5, Duration.ofSeconds(2)).invoke("prompt", dir.resolve("transient"));
        require(Files.readString(dir.resolve("count")).equals("2"), "explicit temporary upstream errors can recover");
        require(observed(Files.readString(dir.resolve("transient-attempts/attempt-0001/call-execution.log")), "firstStderrMillis")
                && Files.readString(dir.resolve("transient-execution.log")).contains("firstStderrMillis=unavailable"),
                "stderr timing reflects each attempt's own output");
        for (String failure : List.of("exit 9", "printf 'HTTP 401: authentication failed' >&2; exit 1",
                "printf 'prompt is too long' >&2; exit 1")) {
            Files.delete(dir.resolve("count"));
            try {
                shell(dir, counting + failure, 1, 5, Duration.ofSeconds(2)).invoke("prompt", dir.resolve("no-retry"));
                throw new AssertionError("Expected invocation failure");
            } catch (AgentCallException expected) { }
            require(Files.readString(dir.resolve("count")).equals("1"), "unknown exits, authentication and oversized input are not retried");
        }
        try {
            shell(dir, "exit 0", 0, 1, Duration.ofSeconds(2)).invoke("x".repeat(2_000_000), dir.resolve("closed-stdin"));
            throw new AssertionError("Expected incomplete invocation");
        } catch (AgentCallException expected) {
            require(!expected.fatal() && (expected.kind() == AgentCallException.Kind.STDIN_CLOSED
                    || expected.kind() == AgentCallException.Kind.EMPTY_RESPONSE),
                    "early zero exit during a large stdin write is a call failure, not a global artifact storage failure");
        }
    }

    private static boolean observed(String execution, String key) {
        return execution.lines().anyMatch(line -> line.matches(key + "=\\d+"));
    }

    private static void sharedBudget() throws Exception {
        Path dir = Files.createTempDirectory("review-agent-budget-");
        var agent = shell(dir, "cat >/dev/null; if [ ! -f called ]; then touch called; exit 0; fi; printf INVALID",
                1, 2, Duration.ofSeconds(2));
        var task = new ReviewTasks.Task("proof", "prompt", reply -> { if (!reply.equals("VALID")) throw new IOException("Invalid format"); });
        try (var progress = progress()) {
            var result = ReviewTasks.runIsolated(List.of(task), agent, dir, 1, progress, 1, 2, "Budget", false).get("proof");
            require(!result.valid() && result.failureKind().equals("BUDGET_EXHAUSTED"), "transport and output corrections share the same call budget");
            require(Files.isDirectory(dir.resolve("proof-attempts/attempt-0002"))
                    && !Files.exists(dir.resolve("proof-repair-attempts")), "a format correction cannot reset a consumed budget");
        }
        var shortBudget = new AgentPolicy(1, 5, Duration.ofMillis(180), Duration.ZERO);
        var slow = new AgentRunner(new ProcessExecutor(), "/bin/sh", List.of("-c", "cat >/dev/null; exec sleep 5"), dir,
                Duration.ofSeconds(5), shortBudget);
        try { slow.invoke("prompt", dir.resolve("deadline")); throw new AssertionError("Expected task deadline"); }
        catch (AgentCallException expired) { require(expired.kind() == AgentCallException.Kind.TIMEOUT, "task deadline limits even the first call"); }
        require(!Files.exists(dir.resolve("deadline-attempts/attempt-0002")), "timeouts do not automatically repeat an expensive request");
        String deadline = Files.readString(dir.resolve("deadline-execution.log"));
        require(observed(deadline, "stdinClosedMillis") && deadline.contains("firstStdoutMillis=unavailable")
                && deadline.contains("processExitObservedMillis=unavailable"),
                "timeout logs distinguish input sent from missing output and an exit only caused by cleanup");
    }

    private static void independentFailures() throws Exception {
        Path dir = Files.createTempDirectory("review-agent-independent-");
        var agent = shell(dir, "prompt=$(cat); case \"$prompt\" in EMPTY) exit 0;; SLOW) exec sleep 5;; *) sleep 0.1; printf VALID;; esac",
                0, 5, Duration.ofMillis(450));
        var tasks = List.of(new ReviewTasks.Task("empty", "EMPTY", reply -> {}), new ReviewTasks.Task("slow", "SLOW", reply -> {}),
                new ReviewTasks.Task("good", "GOOD", reply -> {}));
        try (var progress = progress()) {
            var replies = ReviewTasks.runIsolated(tasks, agent, dir, 2, progress, 1, 2, "Independent", false);
            require(replies.get("good").valid() && replies.get("empty").failureKind().equals("EMPTY_RESPONSE")
                    && replies.get("slow").failureKind().equals("TIMEOUT"), "one empty call and one timeout cannot cancel the valid peer");
            require(Files.readString(dir.resolve("good-state.properties")).contains("status=SUCCEEDED")
                    && Files.readString(dir.resolve("slow-execution.log")).contains("terminationReason=TIMEOUT"), "task and process termination states stay distinct");
            var fixed = shell(dir, "prompt=$(cat); [ \"$prompt\" != GOOD ] || exit 88; printf VALID", 0, 5, Duration.ofSeconds(2));
            var resumed = ReviewTasks.runIsolated(tasks, fixed, dir, 2, progress, 1, 2, "Resume", true);
            require(resumed.values().stream().allMatch(ReviewTasks.Outcome::valid), "resume reuses the good peer and retries only missing outputs");
            require(Files.readString(dir.resolve("empty-attempts/attempt-0001/call-execution.log")).contains("EMPTY_RESPONSE")
                    && Files.isDirectory(dir.resolve("empty-attempts/attempt-0002")), "resume retains all earlier attempt logs");
            try {
                ReviewTasks.run(tasks, agent, dir.resolve("required"), 2, progress, 1, 2, "Required", false);
                throw new AssertionError("Required stage must remain incomplete");
            } catch (ReviewStageException expected) {
                require(Files.readString(dir.resolve("required/good-response.md")).equals("VALID"), "a strict batch collects independent results before reporting failure");
            }
        }
    }

    private static void processOwnership() throws Exception {
        Path dir = Files.createTempDirectory("review-owned-processes-");
        Process unrelated = new ProcessBuilder("sleep", "10").start();
        try {
            var executor = new ProcessExecutor();
            try {
                executor.run(List.of("/bin/sh", "-c", "sleep 10 & printf '%s' \"$!\" > child.pid; wait"), dir, null, Duration.ofMillis(250));
                throw new AssertionError("Expected child timeout");
            } catch (CommandTimeoutException expected) { }
            long pid = Long.parseLong(Files.readString(dir.resolve("child.pid")));
            require(ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true), "timeout reclaims the observed child before returning");
            require(unrelated.isAlive(), "unrelated user processes are untouched");
            try {
                executor.run(List.of("/bin/sh", "-c", "sleep 10 & printf '%s' \"$!\" > orphan.pid; sleep 0.15; exit 0"), dir, null, Duration.ofMillis(600));
            } catch (CommandTimeoutException expected) {
                // The exited parent can leave an inherited pipe open until the command deadline.
            }
            long orphan = Long.parseLong(Files.readString(dir.resolve("orphan.pid")));
            require(ProcessHandle.of(orphan).map(handle -> !handle.isAlive()).orElse(true), "a remembered child is reclaimed even after its parent exits");
            require(executor.cleanupStatus().equals("OBSERVED_PROCESSES_TERMINATED"), "cleanup coverage is observable rather than assumed");
        } finally { unrelated.destroyForcibly(); unrelated.waitFor(); }
    }

    private static void discoveryRecovery() throws Exception {
        Path dir = repository();
        Files.createFile(dir.resolve("fail-r1"));
        Path script = agentScript(dir);
        var args = arguments(dir, script);
        var pipeline = new ReviewPipeline(new ProcessExecutor(), Clock.systemUTC());
        Path run = pipeline.run(CliOptions.parse(args.toArray(String[]::new)));
        require(Files.readString(run.resolve("review-status.md")).contains("INCOMPLETE")
                && Files.readString(run.resolve("report.md")).startsWith("> **Review incomplete:"), "a missing discovery role cannot be presented as full review coverage");
        require(Files.isRegularFile(run.resolve("discovery/R2-response.md")) && Files.isRegularFile(run.resolve("discovery/R3-response.md"))
                && Main.reviewExitCode(run) == 3, "valid reviewers survive and the CLI indicates incomplete execution");
        require(Files.readString(run.resolve("timings.md")).contains("Actual launched attempts: 5"),
                "an incomplete run records its actual calls without counting compatibility logs twice");
        Files.delete(dir.resolve("fail-r1"));
        args.addAll(List.of("--resume", run.toString(), "--review-parallelism", "1"));
        pipeline.run(CliOptions.parse(args.toArray(String[]::new)));
        require(Main.reviewExitCode(run) == 0 && Files.readString(run.resolve("review-status.md")).contains("COMPLETE"), "validated resume clears the missing discovery role");
        require(Files.readString(run.resolve("timings.md")).contains("Actual launched attempts: 1")
                && Files.readString(run.resolve("timings.md")).contains("Valid cached tasks reused: 4"),
                "resumed timing excludes earlier calls and reports cache reuse separately");
        require(Files.readString(dir.resolve("R1-count")).equals("xx") && Files.readString(dir.resolve("R2-count")).equals("x")
                && Files.readString(dir.resolve("R3-count")).equals("x") && Files.readString(dir.resolve("R5-count")).equals("x")
                && Files.readString(dir.resolve("R6-count")).equals("x"),
                "changing parallelism does not rerun completed independent reviewers");
        Files.createFile(dir.resolve("fail-report"));
        args.remove(args.indexOf("--resume") + 1); args.remove("--resume");
        Path local = pipeline.run(CliOptions.parse(args.toArray(String[]::new)));
        require(Main.reviewExitCode(local) == 0 && !Files.exists(local.resolve("report-prompt.md"))
                && Files.readString(local.resolve("report.md")).contains("Changed paths")
                && Files.readString(local.resolve("timings.md")).contains("Actual launched attempts: 5"),
                "valid findings can produce a complete report even when the agent would fail report generation");
    }

    private static void checkpointsAndStorage() throws Exception {
        Path dir = Files.createTempDirectory("review-checkpoint-recovery-");
        var validator = (ReviewTasks.Validator) reply -> {
            if (!reply.equals("VALID")) throw new IOException("Truncated response");
        };
        var task = new ReviewTasks.Task("saved", "unchanged prompt", validator);
        Files.writeString(dir.resolve("saved-prompt.md"), task.prompt());
        Files.writeString(dir.resolve("saved-response.md"), "VALID");
        Files.writeString(dir.resolve("saved-state.properties"), "status=RUNNING\n");
        try (var progress = progress()) {
            var unavailable = shell(dir, "exit 99", 0, 5, Duration.ofSeconds(2));
            require(ReviewTasks.run(List.of(task), unavailable, dir, 1, progress, 1, 2, "Recover checkpoint", true)
                    .get("saved").equals("VALID"), "an interruption between response and success checkpoint can reuse the validated response");
            Files.writeString(dir.resolve("saved-response.md"), "VAL");
            var repair = shell(dir, "cat >/dev/null; printf x >> calls; printf VALID", 0, 1, Duration.ofSeconds(2));
            require(ReviewTasks.run(List.of(task), repair, dir, 1, progress, 1, 2, "Reject truncated checkpoint", true)
                    .get("saved").equals("VALID") && Files.readString(dir.resolve("calls")).equals("x"),
                    "a success marker never bypasses validation of a truncated response");
            String timing = repair.timings().markdown(dir, OutputLanguage.EN);
            require(timing.contains("|saved|cached|rejected|") && timing.contains("|saved|current|accepted|")
                    && !timing.contains("|saved|current|rejected|")
                    && timing.contains("|saved-repair-attempts/attempt-0001/call|SUCCEEDED|"),
                    "timing separates cached rejection, model repair and local validation of the repaired result");
            Files.createDirectory(dir.resolve("diag-normalization.log"));
            var diagnostic = new ReviewTasks.Task("diag", "log failure", reply -> ResponseDiagnostics.record("Recovered field"));
            try {
                ReviewTasks.runIsolated(List.of(diagnostic), repair, dir, 1, progress, 1, 2, "Storage failure", false);
                throw new AssertionError("Expected fatal diagnostic storage failure");
            } catch (IOException expected) {
                require(!Files.exists(dir.resolve("diag-repair-prompt.md")) && Files.readString(dir.resolve("calls")).equals("xx"),
                        "storage failure cannot launch a format correction or be isolated as a business evidence gap");
            }
        }
    }

    private static void shutdownCleanup() throws Exception {
        Path dir = Files.createTempDirectory("review-jvm-shutdown-");
        Process fixture = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), ShutdownFixture.class.getName(), dir.toString())
                .redirectOutput(dir.resolve("stdout.log").toFile()).redirectError(dir.resolve("stderr.log").toFile()).start();
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while ((!Files.isRegularFile(dir.resolve("child.pid")) || Files.size(dir.resolve("child.pid")) == 0)
                    && System.nanoTime() < deadline && fixture.isAlive()) Thread.sleep(10);
            require(Files.isRegularFile(dir.resolve("child.pid")), "shutdown fixture starts its own child");
            long child = Long.parseLong(Files.readString(dir.resolve("child.pid")));
            Thread.sleep(150); // Give the executor a chance to observe the owned descendant.
            fixture.destroy();
            require(fixture.waitFor(5, java.util.concurrent.TimeUnit.SECONDS), "JVM shutdown cleanup is bounded");
            require(ProcessHandle.of(child).map(handle -> !handle.isAlive()).orElse(true), "JVM termination cleans up its observed task processes");
        } finally { if (fixture.isAlive()) { fixture.destroyForcibly(); fixture.waitFor(); } }
    }

    public static final class ShutdownFixture {
        public static void main(String[] args) throws Exception {
            new ProcessExecutor().run(List.of("/bin/sh", "-c", "sleep 20 & printf '%s' \"$!\" > child.pid; wait"),
                    Path.of(args[0]), null, Duration.ofSeconds(30));
        }
    }

    private static void fatalFailureStopsQueuedCalls() throws Exception {
        Path dir = Files.createTempDirectory("review-fatal-queue-");
        var agent = new AgentRunner(new ProcessExecutor(), "/bin/sh", List.of("-c", """
                prompt=$(cat)
                if [ "$prompt" = RECOVER ]; then printf VALID; exit 0; fi
                printf x >> calls
                sleep 0.1
                printf 'authentication failed' >&2
                exit 1
                """), dir, Duration.ofSeconds(5), new AgentPolicy(0, 1, Duration.ofSeconds(5), Duration.ZERO), 1);
        var tasks = List.of(new ReviewTasks.Task("first", "FAIL", reply -> {}),
                new ReviewTasks.Task("second", "FAIL", reply -> {}),
                new ReviewTasks.Task("third", "FAIL", reply -> {}));
        try (var progress = progress()) {
            try {
                ReviewTasks.runIsolated(tasks, agent, dir, 3, progress, 1, 2, "Fatal configuration", false);
                throw new AssertionError("Expected fatal configuration failure");
            } catch (IOException failure) {
                require(failure.getCause() instanceof AgentCallException call
                                && call.kind() == AgentCallException.Kind.CONFIGURATION
                                && Files.readString(dir.resolve("calls")).equals("x")
                                && tasks.stream().filter(task -> Files.isDirectory(dir.resolve(task.id() + "-attempts"))).count() == 1,
                        "publishing the failure before releasing the only permit prevents queued calls from launching");
            }
            var recovery = new ReviewTasks.Task("recovery", "RECOVER", reply -> {});
            require(ReviewTasks.run(List.of(recovery), agent, dir, 1, progress, 1, 2, "Next batch", false)
                            .get("recovery").equals("VALID"),
                    "fatal batch cleanup releases the permit and leaves later batches usable");
        }
    }

    private static void sharedStageFailure() throws Exception {
        Path dir = repository();
        Path requirement = dir.resolve("requirements.md");
        Files.writeString(requirement, "The changed command must reject incomplete results.");
        var args = arguments(dir, agentScript(dir));
        args.addAll(List.of("--requirement", requirement.toString()));
        Path run = new ReviewPipeline(new ProcessExecutor(), Clock.systemUTC()).run(CliOptions.parse(args.toArray(String[]::new)));
        String report = Files.readString(run.resolve("report.md"));
        require(Main.reviewExitCode(run) == 3 && report.contains("Not completed: no reconciled requirement assessment")
                && !report.contains("No requirements document was supplied"), "a failed required assessment cannot masquerade as absent requirements or a business verdict");
        require(!Files.exists(run.resolve("assessment/audit-prompt.md")) && !Files.exists(run.resolve("report-prompt.md")),
                "stages depending on a failed draft do not run");
        require(report.contains("[assessment/draft-state.properties](assessment/draft-state.properties)")
                && Files.readString(run.resolve("review-status.md")).contains("[assessment/draft-state.properties](assessment/draft-state.properties)")
                && Files.isRegularFile(run.resolve("assessment/draft-state.properties")) && !report.contains(run.toString()),
                "an early required-stage failure references its exported checkpoint in both fallback report and status");
    }

    private static Path repository() throws Exception {
        Path dir = Files.createTempDirectory("review-stability-pipeline-");
        Path repo = Files.createDirectory(dir.resolve("repo"));
        git(repo, "init", "-q");
        Files.writeString(repo.resolve("runner.py"), "print('base')\n");
        git(repo, "add", "--all"); git(repo, "commit", "-qm", "base");
        Files.writeString(repo.resolve("runner.py"), "print('target')\n");
        git(repo, "add", "--all"); git(repo, "commit", "-qm", "target");
        return dir;
    }

    private static Path agentScript(Path dir) throws Exception {
        Path script = dir.resolve("agent.sh");
        String report = "<finding-groups/>\n" + String.join("\n\n", ReviewReport.headings(OutputLanguage.EN));
        Files.writeString(script, """
                cd "$1"
                prompt=$(cat)
                case "$prompt" in
                  'You are the discovery stage'*)
                    case "$prompt" in
                      *'Correctness and regressions.'*) role=R1;;
                      *'Business contracts and compatibility.'*) role=R2;;
                      *'Failure handling and recovery.'*) role=R3;;
                      *'Requirements compliance.'*) role=R4;;
                      *'Security and isolation.'*) role=R5;;
                      *'Boundary inputs and data conversion.'*) role=R6;;
                      *) exit 10;;
                    esac
                    printf x >> "$role-count"
                    if [ "$role" = R1 ] && [ -f fail-r1 ]; then exit 9; fi
                    sleep 0.05
                    printf '<findings/>';;
                  'You are the report consolidation'*)
                    [ ! -f fail-report ] || exit 9
                    cat <<'REPORT'
                FINAL_REPORT
                REPORT
                    ;;
                  *) exit 9;;
                esac
                """.replace("FINAL_REPORT", report));
        return script;
    }
    private static ArrayList<String> arguments(Path dir, Path script) {
        return new ArrayList<>(List.of("review", "--repo", dir.resolve("repo").toString(), "--output-dir", dir.resolve("runs").toString(),
                "--review-mode", "deep", "--agent-command", "/bin/sh", "--agent-arg", script.toString(), "--agent-arg", dir.toString(),
                "--agent-retries", "0"));
    }
    private static AgentRunner shell(Path dir, String script, int retries, int calls, Duration timeout) {
        return new AgentRunner(new ProcessExecutor(), "/bin/sh", List.of("-c", script), dir, timeout,
                new AgentPolicy(retries, calls, timeout.multipliedBy(3), Duration.ZERO));
    }
    private static CommandProgress progress() { return new CommandProgress("stability", new PrintStream(OutputStream.nullOutputStream())); }
    private static void git(Path dir, String... args) throws Exception {
        var command = new ArrayList<>(List.of("git", "-c", "user.name=Stability Test", "-c", "user.email=test@example.invalid"));
        command.addAll(List.of(args));
        var result = new ProcessExecutor().run(command, dir, null, Duration.ofSeconds(5));
        require(result.exitCode() == 0, "synthetic Git fixture: " + result.stderr());
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
