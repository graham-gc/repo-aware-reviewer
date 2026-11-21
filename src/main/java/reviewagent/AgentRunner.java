package reviewagent;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

final class AgentRunner {
    private final ProcessExecutor executor;
    private final List<String> command;
    private final Path workingDirectory;
    private final Duration timeout;
    private final AgentPolicy policy;
    private final Semaphore taskSlots;
    private final ThreadLocal<Budget> taskBudget = new ThreadLocal<>();
    private final AtomicInteger active = new AtomicInteger();
    private final ReviewTimings timings = new ReviewTimings();
    ReviewTimings timings() { return timings; }

    AgentRunner(ProcessExecutor executor, String executable, List<String> args, Path workingDirectory, Duration timeout) {
        this(executor, executable, args, workingDirectory, timeout, AgentPolicy.defaults(timeout));
    }

    AgentRunner(ProcessExecutor executor, String executable, List<String> args, Path workingDirectory,
                Duration timeout, AgentPolicy policy) {
        this(executor, executable, args, workingDirectory, timeout, policy, Integer.MAX_VALUE);
    }

    AgentRunner(ProcessExecutor executor, String executable, List<String> args, Path workingDirectory,
                Duration timeout, AgentPolicy policy, int taskParallelism) {
        this.executor = executor;
        this.command = new ArrayList<>();
        this.command.add(executable);
        this.command.addAll(args);
        this.workingDirectory = workingDirectory;
        this.timeout = timeout;
        this.policy = policy;
        taskSlots = new Semaphore(taskParallelism, true);
    }

    final class TaskScope implements AutoCloseable {
        private TaskScope(String model, String schema) throws InterruptedException {
            if (taskBudget.get() != null) throw new IllegalStateException("Nested agent task");
            var selected = commandForModel(model);
            if (schema != null) {
                selected = new ArrayList<>(selected);
                selected.addAll(List.of("--output-format", "json", "--json-schema", schema));
            }
            taskSlots.acquire();
            taskBudget.set(new Budget(selected));
        }
        @Override public void close() {
            taskBudget.remove();
            taskSlots.release();
        }
        int calls() { return taskBudget.get().calls; }
    }
    TaskScope task(String model, String schema) throws InterruptedException { return new TaskScope(model, schema); }

    private List<String> commandForModel(String model) {
        if (model == null) return command;
        var selected = new ArrayList<String>();
        selected.add(command.getFirst());
        for (int i = 1; i < command.size(); i++) {
            String argument = command.get(i);
            if (argument.equals("--model")) {
                i++;
            } else if (!argument.startsWith("--model=")) {
                selected.add(argument);
            }
        }
        selected.add("--model");
        selected.add(model);
        return List.copyOf(selected);
    }

    private final class Budget {
        final List<String> command;
        final long started = System.nanoTime();
        int calls;
        int retries;
        Budget(List<String> command) { this.command = command; }
        Duration remaining() throws AgentCallException {
            long remaining = policy.taskTimeout().toNanos() - (System.nanoTime() - started);
            if (remaining <= 0) throw new AgentCallException(AgentCallException.Kind.BUDGET_EXHAUSTED, "Agent task elapsed-time budget exhausted");
            return Duration.ofNanos(remaining);
        }
        Duration acquire() throws AgentCallException {
            if (calls >= policy.maxCalls()) throw new AgentCallException(AgentCallException.Kind.BUDGET_EXHAUSTED,
                    "Agent task call budget exhausted (" + policy.maxCalls() + " calls, including retries and corrections)");
            Duration remaining = remaining();
            calls++;
            return remaining.compareTo(timeout) < 0 ? remaining : timeout;
        }
    }

    String invoke(String prompt) throws IOException, InterruptedException { return invoke(prompt, null); }

    static List<String> defaultArguments() {
        return List.of("-p", "--tools", "", "--strict-mcp-config", "--mcp-config", "{\"mcpServers\":{}}");
    }

    String invoke(String prompt, Path logPrefix) throws IOException, InterruptedException {
        Budget budget = taskBudget.get();
        if (budget == null) budget = new Budget(command);
        while (true) {
            Duration allowed = budget.acquire();
            try {
                return once(prompt, logPrefix, allowed, budget.calls, budget.command);
            } catch (AgentCallException failure) {
                if (!failure.retryable() || budget.retries >= policy.retries() || budget.calls >= policy.maxCalls()) throw failure;
                if (!executor.cleanupStatus().equals("OBSERVED_PROCESSES_TERMINATED")) {
                    throw new AgentCallException(failure.kind(), failure.getMessage()
                            + "; automatic retry suppressed because descendant cleanup could not be verified", failure);
                }
                Duration remaining = budget.remaining();
                if (remaining.compareTo(policy.retryDelay()) <= 0) throw failure;
                budget.retries++;
                Thread.sleep(policy.retryDelay());
            }
        }
    }

    private String once(String prompt, Path prefix, Duration allowed, int callNumber, List<String> command)
            throws IOException, InterruptedException {
        Path attempt = attemptPrefix(prefix);
        if (attempt != null) AtomicFiles.write(Path.of(attempt + "-prompt.md"), prompt);
        Path stdout = attempt == null ? null : Path.of(attempt + "-stdout.log");
        Path stderr = attempt == null ? null : Path.of(attempt + "-stderr.log");
        long started = System.nanoTime();
        Instant startedAt = Instant.now();
        Integer exit = null;
        String reason = "IO_FAILURE";
        int concurrent = active.incrementAndGet();
        try {
            CommandResult result = executor.run(command, workingDirectory, prompt, allowed, stdout, stderr);
            exit = result.exitCode();
            if (exit != 0) throw commandFailure(result, command.contains("--json-schema"));
            String response;
            try { response = result.strictUtf8Stdout(); }
            catch (CharacterCodingException invalid) {
                throw new AgentCallException(AgentCallException.Kind.INVALID_ENCODING, "Agent command returned invalid UTF-8 on stdout", invalid);
            }
            if (response.indexOf('\uFFFD') >= 0) throw new AgentCallException(AgentCallException.Kind.INVALID_ENCODING,
                    "Agent response contains U+FFFD (Unicode replacement character)");
            if (response.isBlank()) throw new AgentCallException(AgentCallException.Kind.EMPTY_RESPONSE, "Agent command returned no output (cause unknown)");
            reason = "SUCCEEDED";
            return response;
        } catch (CommandTimeoutException expired) {
            reason = "TIMEOUT";
            throw new AgentCallException(AgentCallException.Kind.TIMEOUT, expired.getMessage(), expired);
        } catch (CommandStartException unavailable) {
            reason = "CONFIGURATION";
            throw new AgentCallException(AgentCallException.Kind.CONFIGURATION, unavailable.getMessage(), unavailable);
        } catch (CommandInputException closed) {
            reason = "STDIN_CLOSED";
            throw new AgentCallException(AgentCallException.Kind.STDIN_CLOSED, closed.getMessage(), closed);
        } catch (AgentCallException failure) {
            reason = failure.kind().name();
            throw failure;
        } catch (InterruptedException cancelled) {
            reason = "CANCELLED";
            throw cancelled;
        } finally {
            boolean interrupted = Thread.interrupted();
            try {
                active.decrementAndGet();
                var observed = executor.observations();
                timings.call(prefix, attempt, started, System.nanoTime(), EvidenceText.bytes(prompt), reason, observed);
                if (attempt != null) {
                    byte[] input = prompt.getBytes(StandardCharsets.UTF_8);
                    String record = "executable=" + EvidenceText.oneLine(command.getFirst())
                            + "\nworkingDirectory=" + EvidenceText.oneLine(workingDirectory.toString())
                            + "\nstartedAt=" + startedAt + "\ntimeoutSeconds=" + timeout.toSeconds()
                            + "\nallowedMillis=" + allowed.toMillis()
                            + "\nelapsedMillis=" + Duration.ofNanos(System.nanoTime() - started).toMillis()
                            + "\nprocessStartedMillis=" + observedMillis(observed.processStarted())
                            + "\nstdinClosedMillis=" + observedMillis(observed.stdinClosed())
                            + "\nfirstStdoutMillis=" + observedMillis(observed.firstStdout())
                            + "\nfirstStderrMillis=" + observedMillis(observed.firstStderr())
                            + "\nprocessExitObservedMillis=" + observedMillis(observed.exitObserved())
                            + "\nexitCode=" + (exit == null ? "unavailable" : exit)
                            + "\nterminationReason=" + reason + "\nprocessCleanup=" + executor.cleanupStatus() + "\ninputBytes=" + input.length
                            + "\npromptSha256=" + InputDocument.sha256(input)
                            + "\ncommandFingerprint=" + InputDocument.sha256(String.join("\0", command).getBytes(StandardCharsets.UTF_8))
                            + "\ntaskCall=" + callNumber + "\nconcurrentCallsAtStart=" + concurrent
                            + "\nattemptDirectory=" + attempt.getParent().getFileName() + "\n";
                    record += "maxCallsPerTask=" + policy.maxCalls() + "\ntaskTimeoutSeconds=" + policy.taskTimeout().toSeconds()
                            + "\ntransportRetriesPerTask=" + policy.retries() + "\nupstreamRetryPolicy=unknown (controlled by agent command)\n";
                    AtomicFiles.write(Path.of(attempt + "-execution.log"), record);
                    // Compatibility views point to the latest attempt; all attempt directories remain immutable.
                    for (String suffix : List.of("-stdout.log", "-stderr.log", "-execution.log")) {
                        Path saved = Path.of(attempt + suffix);
                        if (Files.isRegularFile(saved)) AtomicFiles.copy(saved, Path.of(prefix + suffix));
                    }
                }
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }

    private static String observedMillis(Long millis) {
        return millis == null ? "unavailable" : millis.toString();
    }

    private static Path attemptPrefix(Path prefix) throws IOException {
        if (prefix == null) return null;
        Path root = Files.createDirectories(Path.of(prefix + "-attempts"));
        for (int number = 1; ; number++) {
            try { return Files.createDirectory(root.resolve("attempt-%04d".formatted(number))).resolve("call"); }
            catch (java.nio.file.FileAlreadyExistsException collision) { /* Keep all earlier attempts, including resumed calls. */ }
        }
    }

    private static AgentCallException commandFailure(CommandResult result, boolean structured) {
        if (structured) {
            try {
                var envelope = JsonReader.object(result.strictUtf8Stdout());
                if ("result".equals(envelope.get("type")) && Boolean.TRUE.equals(envelope.get("is_error"))
                        && "error_max_structured_output_retries".equals(envelope.get("subtype")))
                    return new AgentCallException(AgentCallException.Kind.STRUCTURED_OUTPUT,
                            "The model command exhausted its structured output attempts; see stdout log");
            } catch (IOException unreadable) {
                // Preserve normal command diagnostics when there is no recognized failure envelope.
            }
        }
        String text = result.stderr().toLowerCase(java.util.Locale.ROOT);
        AgentCallException.Kind kind;
        if (text.matches("(?s).*(?:\\bhttp(?: status)?[ :]+(?:401|403)\\b|invalid[_ ]api[_ ]key|authentication failed|not authenticated).*"))
            kind = AgentCallException.Kind.CONFIGURATION;
        else if (text.matches("(?s).*(?:prompt is too long|maximum context length|context window exceeded|input too long).*"))
            kind = AgentCallException.Kind.INPUT_LIMIT;
        else if (text.matches("(?s).*(?:\\b(?:http(?: status)?|status(?: code)?)[ :=]+(?:429|502|503|504)\\b|econnreset|eai_again|connection reset by peer|temporarily unavailable).*"))
            kind = AgentCallException.Kind.TRANSIENT;
        else kind = AgentCallException.Kind.COMMAND_EXIT;
        return new AgentCallException(kind, "Agent command failed with exit code " + result.exitCode() + " (" + kind + "); see stderr log");
    }

    static String validateResponse(String response) throws IOException {
        if (response.indexOf('\uFFFD') >= 0) throw new IOException("Agent response contains U+FFFD (Unicode replacement character)");
        if (response.isBlank()) throw new IOException("Agent command returned no output");
        return response;
    }
}
