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
        if (stdin != null) {
            process.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        }
        process.getOutputStream().close();

        try (var readers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<byte[]> stdoutReader = readers.submit(() -> process.getInputStream().readAllBytes());
            Future<byte[]> stderrReader = readers.submit(() -> process.getErrorStream().readAllBytes());
            boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                process.waitFor();
                throw new IOException("Command timed out after " + timeout + ": " + command.get(0));
            }
            byte[] stdout = stdoutReader.get();
            String stderr = new String(stderrReader.get(), StandardCharsets.UTF_8);
            return new CommandResult(process.exitValue(), stdout, stderr);
        } catch (java.util.concurrent.ExecutionException failure) {
            throw new IOException("Unable to read command output", failure.getCause());
        }
    }
}

