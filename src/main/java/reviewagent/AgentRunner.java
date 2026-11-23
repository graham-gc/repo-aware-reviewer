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
import java.util.regex.Pattern;

final class AgentRunner {
    private static final Pattern RATE_LIMIT = Pattern.compile(
            "(?:\\b(?:http(?:/[0-9.]+| status)?|status(?: code)?|api error)[ :=]+429\\b|\\brate_limit_error\\b)");
    private static final Pattern RETRY_AFTER = Pattern.compile("(?m)^\\s*retry-after\\s*:\\s*([0-9]{1,8})\\b");
    private final ProcessExecutor executor;
    private final List<String> command;
    private final Path workingDirectory;
    private final Duration timeout;
    private final AgentPolicy policy;
    private final Semaphore taskSlots;
    private final AdaptiveConcurrency adaptive;
    private final ThreadLocal<Budget> taskBudget = new ThreadLocal<>();
    private final AtomicInteger active = new AtomicInteger();
    private final ReviewTimings timings = new ReviewTimings();
    private final java.util.Map<String, ModelPromptBudget.Limit> modelBudgets = new java.util.HashMap<>();
    private final java.util.Map<String, Semaphore> modelSlots = new java.util.HashMap<>();
    private ModelPromptBudget.Limit promptBudget = ModelPromptBudget.Limit.fallback(null);
    ReviewTimings timings() { return timings; }
    void promptBudget(ModelPromptBudget.Limit limit) { promptBudget(null, limit); }
    void promptBudget(String model, ModelPromptBudget.Limit limit) {
        if (model == null) promptBudget = limit;
        else modelBudgets.put(model, limit);
        var caps = limit.capabilities();
        if (caps != null && caps.maxConcurrentRequests() > 0)
            modelSlots.put(model, new Semaphore(caps.maxConcurrentRequests(), true));
        else modelSlots.remove(model);
    }
    ModelPromptBudget.Limit promptBudget() { return promptBudget; }

    AgentRunner(ProcessExecutor executor, String executable, List<String> args, Path workingDirectory, Duration timeout) {
        this(executor, executable, args, workingDirectory, timeout, AgentPolicy.defaults(timeout));
    }

    AgentRunner(ProcessExecutor executor, String executable, List<String> args, Path workingDirectory,
                Duration timeout, AgentPolicy policy) {
        this(executor, executable, args, workingDirectory, timeout, policy, Integer.MAX_VALUE);
    }

    AgentRunner(ProcessExecutor executor, String executable, List<String> args, Path workingDirectory,
                Duration timeout, AgentPolicy policy, int taskParallelism) {
        this(executor, executable, args, workingDirectory, timeout, policy, taskParallelism, false, null);
    }

    AgentRunner(ProcessExecutor executor, String executable, List<String> args, Path workingDirectory,
                Duration timeout, AgentPolicy policy, int taskParallelism, boolean automatic, Path concurrencyHistory) {
        this.executor = executor;
        this.command = new ArrayList<>();
        this.command.add(executable);
        this.command.addAll(args);
        this.workingDirectory = workingDirectory;
        this.timeout = timeout;
        this.policy = policy;
        taskSlots = new Semaphore(taskParallelism, true);
        adaptive = automatic ? new AdaptiveConcurrency(taskParallelism, concurrencyHistory, timings) : null;
    }

    final class TaskScope implements AutoCloseable {
        private boolean accepted;

        private TaskScope(String model, String schema, int stage, String prompt, AdaptiveConcurrency admission,
                          boolean retryEmptyResponse) throws InterruptedException {
            if (taskBudget.get() != null) throw new IllegalStateException("Nested agent task");
            var selected = commandForModel(model);
            if (schema != null) {
                selected = new ArrayList<>(selected);
                selected.addAll(List.of("--output-format", "json", "--json-schema", schema));
            }
            var controller = admission == null ? adaptive : admission;
            var profile = controller == null ? null : workload(selected, stage, EvidenceText.bytes(prompt));
            taskSlots.acquire();
            taskBudget.set(new Budget(selected, profile, controller, modelSlots.get(model), retryEmptyResponse));
        }
        @Override public void close() {
            var budget = taskBudget.get();
            try {
                if (budget.firstAdmission != null) budget.adaptive.finished(budget.firstAdmission, accepted);
            } finally {
                taskBudget.remove();
                taskSlots.release();
            }
        }
        int calls() { return taskBudget.get().calls; }
        boolean canRestart() {
            var budget = taskBudget.get();
            return budget.calls < policy.maxCalls() && System.nanoTime() - budget.started < policy.taskTimeout().toNanos()
                    && executor.cleanupStatus().equals("OBSERVED_PROCESSES_TERMINATED");
        }
        void accepted() { accepted = true; }
    }
    TaskScope task(String model, String schema, int stage, String prompt, AdaptiveConcurrency admission,
                   boolean retryEmptyResponse) throws InterruptedException {
        return new TaskScope(model, schema, stage, prompt, admission, retryEmptyResponse);
    }

    int phaseParallelism(CliOptions options, int work, List<String> models) {
        int maximum = Math.min(work, options.reviewParallelism());
        for (String model : models) {
            var budget = model == null ? promptBudget : modelBudgets.get(model);
            var caps = budget == null ? null : budget.capabilities();
            if (caps != null && caps.maxConcurrentRequests() > 0)
                maximum = Math.min(maximum, caps.maxConcurrentRequests());
        }
        return maximum;
    }

    record PhaseConcurrency(int maximum, AdaptiveConcurrency admission) implements AutoCloseable {
        @Override public void close() {
            if (admission != null) admission.saveHistory();
        }
    }

    PhaseConcurrency phaseConcurrency(List<ReviewTasks.Job> jobs, int work, CliOptions options, int stage, String phase, Path history) {
        int maximum = phaseParallelism(options, work, jobs.stream().map(job -> job.task().model()).distinct().toList());
        if (!options.adaptiveParallelism() || jobs.isEmpty()) return new PhaseConcurrency(maximum, null);
        var ready = jobs.stream().map(ReviewTasks.Job::task).map(task ->
                workload(commandForModel(task.model()), stage, EvidenceText.bytes(task.prompt()))).toList();
        return new PhaseConcurrency(maximum, AdaptiveConcurrency.forReadyWork(maximum, history, timings, ready, phase));
    }
    void saveConcurrencyHistory() { if (adaptive != null) adaptive.saveHistory(); }

    List<String> commandForModel(String model) {
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
        final AdaptiveConcurrency.Workload workload;
        final AdaptiveConcurrency adaptive;
        final Semaphore modelSlots;
        final boolean retryEmptyResponse;
        long started = System.nanoTime();
        AdaptiveConcurrency.Admission firstAdmission;
        int calls;
        int retries;
        Budget(List<String> command, AdaptiveConcurrency.Workload workload, AdaptiveConcurrency adaptive, Semaphore modelSlots,
               boolean retryEmptyResponse) {
            this.command = command;
            this.workload = workload;
            this.adaptive = adaptive;
            this.modelSlots = modelSlots;
            this.retryEmptyResponse = retryEmptyResponse;
        }
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
        if (budget == null) budget = new Budget(command,
                adaptive == null ? null : workload(command, 0, EvidenceText.bytes(prompt)), adaptive, modelSlots.get(null), true);
        while (true) {
            try {
                return request(prompt, logPrefix, budget);
            } catch (AgentCallException failure) {
                if (failure.kind() == AgentCallException.Kind.EMPTY_RESPONSE && !budget.retryEmptyResponse) throw failure;
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

    private String request(String prompt, Path prefix, Budget budget) throws IOException, InterruptedException {
        var adaptive = budget.adaptive;
        if (adaptive == null) {
            Duration allowed = budget.acquire();
            return invokeWithinModelLimit(prompt, prefix, allowed, budget);
        }
        boolean first = budget.calls == 0;
        long waitBudget = (first ? policy.taskTimeout() : budget.remaining()).toNanos();
        var admission = adaptive.acquire(budget.workload, waitBudget);
        try {
            if (first) {
                budget.started = System.nanoTime();
                budget.firstAdmission = admission;
            }
            Duration allowed = budget.acquire();
            return invokeWithinModelLimit(prompt, prefix, allowed, budget);
        } catch (AgentCallException failure) {
            if (failure.kind() == AgentCallException.Kind.RATE_LIMIT) adaptive.throttled(admission, failure.retryAfter());
            if (failure.fatal()) adaptive.abort(failure);
            throw failure;
        } finally {
            adaptive.release(admission);
        }
    }

    private String invokeWithinModelLimit(String prompt, Path prefix, Duration allowed, Budget budget)
            throws IOException, InterruptedException {
        if (budget.modelSlots == null) return once(prompt, prefix, allowed, budget.calls, budget.command);
        long started = System.nanoTime();
        if (!budget.modelSlots.tryAcquire(allowed.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS))
            throw new AgentCallException(AgentCallException.Kind.BUDGET_EXHAUSTED, "Agent task expired while waiting for a model slot");
        try {
            long remaining = allowed.toNanos() - (System.nanoTime() - started);
            if (remaining <= 0)
                throw new AgentCallException(AgentCallException.Kind.BUDGET_EXHAUSTED, "Agent task expired while waiting for a model slot");
            return once(prompt, prefix, Duration.ofNanos(remaining), budget.calls, budget.command);
        } finally {
            budget.modelSlots.release();
        }
    }

    private static AdaptiveConcurrency.Workload workload(List<String> invocation, int stage, int inputBytes) {
        boolean claude = Path.of(invocation.getFirst()).getFileName().toString().equals("claude");
        String endpoint = claude ? System.getenv("ANTHROPIC_BASE_URL") : null;
        String model = claude ? System.getenv("ANTHROPIC_MODEL") : null;
        for (int i = 1; i < invocation.size(); i++) {
            String argument = invocation.get(i);
            if (argument.equals("--model") && i + 1 < invocation.size()) model = invocation.get(++i);
            else if (argument.startsWith("--model=")) model = argument.substring("--model=".length());
        }
        boolean knownRoute = endpoint != null && !endpoint.isBlank() && model != null && !model.isBlank();
        String route = invocation.getFirst() + "\0" + endpoint + "\0" + model + "\0" + stage + "\0";
        String key = InputDocument.sha256((route + Integer.highestOneBit(inputBytes)).getBytes(StandardCharsets.UTF_8));
        return new AdaptiveConcurrency.Workload(key, InputDocument.sha256(route.getBytes(StandardCharsets.UTF_8)), knownRoute);
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
        else if (RATE_LIMIT.matcher(text).find()) {
            var after = RETRY_AFTER.matcher(text);
            Duration delay = after.find() ? Duration.ofSeconds(Long.parseLong(after.group(1))) : Duration.ZERO;
            return new AgentCallException(AgentCallException.Kind.RATE_LIMIT,
                    "Agent command was rate limited (exit code " + result.exitCode() + "); see stderr log", null, delay);
        }
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
