package reviewagent;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

final class AgentRunner {
    private final ProcessExecutor executor;
    private final List<String> command;
    private final Path workingDirectory;
    private final Duration timeout;

    AgentRunner(ProcessExecutor executor, String executable, List<String> args,
                Path workingDirectory, Duration timeout) {
        this.executor = executor;
        this.command = new ArrayList<>();
        this.command.add(executable);
        this.command.addAll(args);
        this.workingDirectory = workingDirectory;
        this.timeout = timeout;
    }

    String invoke(String prompt) throws IOException, InterruptedException {
        return invoke(prompt, null);
    }

    static List<String> defaultArguments() {
        return List.of("-p", "--tools", "", "--strict-mcp-config", "--mcp-config", "{\"mcpServers\":{}}");
    }

    String invoke(String prompt, Path logPrefix) throws IOException, InterruptedException {
        Path stdout = logPrefix == null ? null : Path.of(logPrefix + "-stdout.log");
        Path stderr = logPrefix == null ? null : Path.of(logPrefix + "-stderr.log");
        long started = System.nanoTime();
        CommandResult result;
        try {
            result = executor.run(command, workingDirectory, prompt, timeout, stdout, stderr);
        } catch (IOException | InterruptedException failure) {
            try {
                recordExecution(logPrefix, null, started);
            } catch (IOException logFailure) {
                failure.addSuppressed(logFailure);
            }
            throw failure;
        }
        recordExecution(logPrefix, result.exitCode(), started);
        if (result.exitCode() != 0) {
            throw new IOException("Agent command failed with exit code " + result.exitCode()
                    + System.lineSeparator() + result.stderr());
        }
        String response;
        try {
            response = result.strictUtf8Stdout();
        } catch (CharacterCodingException error) {
            throw new IOException("Agent command returned invalid UTF-8 on stdout", error);
        }
        return validateResponse(response);
    }

    private void recordExecution(Path prefix, Integer exitCode, long started) throws IOException {
        if (prefix == null) {
            return;
        }
        String record = "executable=" + EvidenceText.oneLine(command.getFirst())
                + "\ntimeoutSeconds=" + timeout.toSeconds()
                + "\nelapsedMillis=" + Duration.ofNanos(System.nanoTime() - started).toMillis()
                + "\nexitCode=" + (exitCode == null ? "unavailable (execution did not complete)" : exitCode) + "\n";
        Files.writeString(Path.of(prefix + "-execution.log"), record, StandardCharsets.UTF_8);
    }

    static String validateResponse(String response) throws IOException {
        if (response.indexOf('\uFFFD') >= 0) {
            throw new IOException("Agent response contains U+FFFD (Unicode replacement character)");
        }
        if (response.isBlank()) {
            throw new IOException("Agent command returned no output");
        }
        return response;
    }
}

