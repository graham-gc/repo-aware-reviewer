package reviewagent;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Path;
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
        CommandResult result = executor.run(command, workingDirectory, prompt, timeout);
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

