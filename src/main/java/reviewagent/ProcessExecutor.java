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
    CommandResult run(List<String> command, Path workingDirectory, String stdin, Duration timeout)
            throws IOException, InterruptedException {
        return run(command, workingDirectory, stdin, timeout, null, null);
    }

    CommandResult run(List<String> command, Path workingDirectory, String stdin, Duration timeout,
                      Path stdoutLog, Path stderrLog) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command).directory(workingDirectory.toFile());
        long timeoutNanos = timeout.toNanos();
        Process process = builder.start();
        long deadline = System.nanoTime() + timeoutNanos;
        try (var readers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<byte[]> stdoutReader = readers.submit(() -> capture(process.getInputStream(), stdoutLog));
            Future<byte[]> stderrReader = readers.submit(() -> capture(process.getErrorStream(), stderrLog));
            Future<?> writer = readers.submit(() -> {
                try (var output = process.getOutputStream()) {
                    if (stdin != null) {
                        output.write(stdin.getBytes(StandardCharsets.UTF_8));
                    }
                }
                return null;
            });
            try {
                if (!process.waitFor(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                    throw new TimeoutException();
                }
                byte[] stdout = stdoutReader.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                String stderr = new String(stderrReader.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS),
                        StandardCharsets.UTF_8);
                if (process.exitValue() == 0) {
                    writer.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                }
                return new CommandResult(process.exitValue(), stdout, stderr);
            } catch (TimeoutException expired) {
                throw new IOException("Command timed out after " + timeout.toSeconds() + " seconds: " + command.getFirst(), expired);
            } catch (java.util.concurrent.ExecutionException failure) {
                throw new IOException("Unable to read or write command data", failure.getCause());
            } finally {
                if (process.isAlive()) {
                    try {
                        process.descendants().forEach(child -> child.destroyForcibly());
                    } catch (RuntimeException unavailable) {
                        // Some sandboxes and runtimes prohibit process-tree enumeration.
                    }
                    process.destroyForcibly();
                }
                writer.cancel(true);
                stdoutReader.cancel(true);
                stderrReader.cancel(true);
                process.getOutputStream().close();
                process.getInputStream().close();
                process.getErrorStream().close();
            }
        }
    }

    private static byte[] capture(InputStream input, Path log) throws IOException {
        try (OutputStream output = log == null ? OutputStream.nullOutputStream() : Files.newOutputStream(log)) {
            var captured = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                output.flush();
                captured.write(buffer, 0, count);
            }
            return captured.toByteArray();
        }
    }
}

