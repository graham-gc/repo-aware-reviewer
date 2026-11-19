package reviewagent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class ProcessExecutor {
    private static final java.util.concurrent.ConcurrentMap<Process, java.util.Set<ProcessHandle>> ACTIVE = new java.util.concurrent.ConcurrentHashMap<>();
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (var entry : ACTIVE.entrySet()) {
                rememberChildren(entry.getKey(), entry.getValue());
                try { terminate(entry.getKey(), entry.getValue()); }
                catch (IOException ignored) { /* The JVM is terminating; never target unrelated processes. */ }
            }
        }, "review-command-cleanup"));
    }
    record Observations(Long processStarted, Long stdinClosed, Long firstStdout, Long firstStderr, Long exitObserved) { }

    private static final class Timing {
        private final long started = System.nanoTime();
        private Long processStarted;
        private volatile Long stdinClosed;
        private volatile Long firstStdout;
        private volatile Long firstStderr;
        private Long exitObserved;

        long elapsedMillis() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }

        Observations snapshot() {
            return new Observations(processStarted, stdinClosed, firstStdout, firstStderr, exitObserved);
        }
    }

    private final ThreadLocal<Timing> timing = new ThreadLocal<>();
    private final ThreadLocal<String> cleanupStatus = ThreadLocal.withInitial(() -> "NOT_STARTED");
    String cleanupStatus() { return cleanupStatus.get(); }
    Observations observations() { return timing.get().snapshot(); }
    CommandResult run(List<String> command, Path workingDirectory, String stdin, Duration timeout)
            throws IOException, InterruptedException {
        return run(command, workingDirectory, stdin, timeout, null, null);
    }

    CommandResult run(List<String> command, Path workingDirectory, String stdin, Duration timeout,
                      Path stdoutLog, Path stderrLog) throws IOException, InterruptedException {
        return run(command, workingDirectory, stdin, timeout, stdoutLog, stderrLog, java.util.Map.of());
    }

    CommandResult run(List<String> command, Path workingDirectory, String stdin, Duration timeout,
                      Path stdoutLog, Path stderrLog, java.util.Map<String, String> environment)
            throws IOException, InterruptedException {
        var current = new Timing();
        timing.set(current);
        cleanupStatus.set("NOT_STARTED");
        ProcessBuilder builder = new ProcessBuilder(command).directory(workingDirectory.toFile());
        builder.environment().putAll(environment);
        long timeoutNanos = timeout.toNanos();
        Process process;
        try { process = builder.start(); }
        catch (IOException failure) { throw new CommandStartException("Cannot start command: " + command.getFirst(), failure); }
        current.processStarted = current.elapsedMillis();
        long deadline = System.nanoTime() + timeoutNanos;
        java.util.Set<ProcessHandle> children = java.util.concurrent.ConcurrentHashMap.newKeySet();
        ACTIVE.put(process, children);
        boolean treeAvailable = true;
        var readers = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<byte[]> stdoutReader = readers.submit(() ->
                    capture(process.getInputStream(), stdoutLog, () -> current.firstStdout = current.elapsedMillis()));
            Future<byte[]> stderrReader = readers.submit(() ->
                    capture(process.getErrorStream(), stderrLog, () -> current.firstStderr = current.elapsedMillis()));
            Future<?> writer = readers.submit(() -> {
                try (var output = process.getOutputStream()) {
                    if (stdin != null) {
                        output.write(stdin.getBytes(StandardCharsets.UTF_8));
                    }
                }
                current.stdinClosed = current.elapsedMillis();
                return null;
            });
            try {
                while (true) {
                    treeAvailable &= rememberChildren(process, children);
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) throw new TimeoutException();
                    if (process.waitFor(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(25)), TimeUnit.NANOSECONDS)) break;
                }
                current.exitObserved = current.elapsedMillis();
                byte[] stdout = stdoutReader.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                String stderr = new String(stderrReader.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS),
                        StandardCharsets.UTF_8);
                if (process.exitValue() == 0) {
                    try { writer.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
                    catch (java.util.concurrent.ExecutionException inputFailure) {
                        throw new CommandInputException(inputFailure.getCause());
                    }
                }
                return new CommandResult(process.exitValue(), stdout, stderr);
            } catch (TimeoutException expired) {
                throw new CommandTimeoutException("Command timed out after " + timeout.toMillis() + "ms: " + command.getFirst(), expired);
            } catch (java.util.concurrent.ExecutionException failure) {
                throw new IOException("Unable to read or write command data", failure.getCause());
            } finally {
                try {
                    treeAvailable &= rememberChildren(process, children);
                    terminate(process, children);
                    cleanupStatus.set(treeAvailable ? "OBSERVED_PROCESSES_TERMINATED" : "DESCENDANT_ENUMERATION_UNAVAILABLE");
                } catch (IOException cleanup) {
                    cleanupStatus.set("FAILED");
                    throw cleanup;
                } finally {
                    writer.cancel(true);
                    stdoutReader.cancel(true);
                    stderrReader.cancel(true);
                    readers.submit(() -> close(process.getOutputStream()));
                    readers.submit(() -> close(process.getInputStream()));
                    readers.submit(() -> close(process.getErrorStream()));
                }
            }
        } finally {
            // ExecutorService.close waits indefinitely; a stuck inherited pipe must not consume a worker forever.
            readers.shutdownNow();
            boolean interrupted = Thread.interrupted();
            boolean stopped = false;
            try { stopped = readers.awaitTermination(1, TimeUnit.SECONDS); }
            catch (InterruptedException cancelled) { interrupted = true; }
            if (interrupted) Thread.currentThread().interrupt();
            if (!process.isAlive() && children.stream().noneMatch(ProcessHandle::isAlive)) ACTIVE.remove(process);
            if (!stopped && !interrupted) throw new IOException("Command streams did not close after process cleanup");
        }
    }

    private static boolean rememberChildren(Process process, java.util.Set<ProcessHandle> children) {
        try (var descendants = process.descendants()) { descendants.forEach(children::add); return true; }
        catch (RuntimeException unavailable) { return false; }
    }

    private static void terminate(Process process, java.util.Set<ProcessHandle> children) throws IOException {
        // Keep handles observed while the parent was alive, even if it exits before a child closes its pipes.
        boolean interrupted = Thread.interrupted();
        children.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroy);
        long reap = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100);
        while (process.isAlive() && children.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < reap) {
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            if (Thread.interrupted()) interrupted = true;
        }
        if (process.isAlive()) process.destroy();
        long grace = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200);
        while (System.nanoTime() < grace && (process.isAlive() || children.stream().anyMatch(ProcessHandle::isAlive))) {
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            if (Thread.interrupted()) interrupted = true;
        }
        children.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) process.destroyForcibly();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while ((process.isAlive() || children.stream().anyMatch(ProcessHandle::isAlive)) && System.nanoTime() < deadline) {
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            if (Thread.interrupted()) interrupted = true;
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (process.isAlive() || children.stream().anyMatch(ProcessHandle::isAlive))
            throw new IOException("Command cleanup could not confirm termination of all observed task processes");
    }

    private static void close(java.io.Closeable stream) {
        try { stream.close(); } catch (IOException ignored) { /* Preserve the original command failure. */ }
    }

    private static byte[] capture(InputStream input, Path log, Runnable observeFirstByte) throws IOException {
        try (OutputStream output = log == null ? OutputStream.nullOutputStream() : Files.newOutputStream(log)) {
            var captured = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count > 0 && captured.size() == 0) observeFirstByte.run();
                output.write(buffer, 0, count);
                output.flush();
                captured.write(buffer, 0, count);
            }
            return captured.toByteArray();
        }
    }
}

