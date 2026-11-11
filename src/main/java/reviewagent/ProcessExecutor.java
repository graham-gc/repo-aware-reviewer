package reviewagent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

final class ProcessExecutor {
    CommandResult run(List<String> command, Path workingDirectory, String stdin, Duration timeout)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command).directory(workingDirectory.toFile());
        Process process = builder.start();
        try (var readers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<byte[]> stdoutReader = readers.submit(() -> process.getInputStream().readAllBytes());
            Future<byte[]> stderrReader = readers.submit(() -> process.getErrorStream().readAllBytes());
            Future<?> writer = readers.submit(() -> {
                try (var output = process.getOutputStream()) {
                    if (stdin != null) {
                        output.write(stdin.getBytes(StandardCharsets.UTF_8));
                    }
                }
                return null;
            });
            try {
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IOException("Command timed out after " + timeout + ": " + command.get(0));
                }
                byte[] stdout = stdoutReader.get();
                String stderr = new String(stderrReader.get(), StandardCharsets.UTF_8);
                if (process.exitValue() == 0) {
                    writer.get();
                }
                return new CommandResult(process.exitValue(), stdout, stderr);
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
}

