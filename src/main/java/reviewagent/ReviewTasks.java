package reviewagent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class ReviewTasks {
    @FunctionalInterface
    interface Validator {
        void validate(String response) throws IOException;
    }

    record Task(String id, String prompt, Validator validator) {
    }

    private record Reply(String id, String response) {
    }

    static Map<String, String> run(List<Task> tasks, AgentRunner agent, Path directory, int parallelism,
                                   CommandProgress progress, int stage, int stages, String description, boolean resume)
            throws IOException, InterruptedException {
        Files.createDirectories(directory);
        if (tasks.isEmpty()) {
            return Map.of();
        }
        try (var workers = Executors.newFixedThreadPool(Math.min(parallelism, tasks.size()), Thread.ofVirtual().factory())) {
            var completions = new ExecutorCompletionService<Reply>(workers);
            var futures = new ArrayList<Future<Reply>>();
            try {
                for (Task task : tasks) {
                    futures.add(completions.submit(() -> {
                        try {
                            String response = invoke(task, agent, directory, resume);
                            Files.deleteIfExists(directory.resolve(task.id() + "-error.txt"));
                            return new Reply(task.id(), response);
                        } catch (IOException failure) {
                            Files.writeString(directory.resolve(task.id() + "-error.txt"), failure.getMessage(), StandardCharsets.UTF_8);
                            throw failure;
                        }
                    }));
                }
                var replies = new LinkedHashMap<String, String>();
                for (int completed = 1; completed <= tasks.size(); completed++) {
                    Reply reply = completions.take().get();
                    replies.put(reply.id(), reply.response());
                    progress.step(stage, stages, description + " " + completed + "/" + tasks.size());
                }
                var ordered = new LinkedHashMap<String, String>();
                tasks.forEach(task -> ordered.put(task.id(), replies.get(task.id())));
                return ordered;
            } catch (ExecutionException failure) {
                throw new IOException(description + " failed: " + failure.getCause().getMessage()
                        + ". See task artifacts in " + directory, failure.getCause());
            } finally {
                futures.forEach(future -> future.cancel(true));
                workers.shutdownNow();
            }
        }
    }

    private static String invoke(Task task, AgentRunner agent, Path directory, boolean resume)
            throws IOException, InterruptedException {
        Path responseFile = directory.resolve(task.id() + "-response.md");
        String response;
        if (resume && Files.isRegularFile(responseFile)) {
            response = Files.readString(responseFile, StandardCharsets.UTF_8);
        } else {
            Files.writeString(directory.resolve(task.id() + "-prompt.md"), task.prompt(), StandardCharsets.UTF_8);
            response = agent.invoke(task.prompt(), directory.resolve(task.id()));
            Files.writeString(responseFile, response, StandardCharsets.UTF_8);
        }
        try {
            AgentRunner.validateResponse(response);
            task.validator().validate(response);
            return response;
        } catch (IOException invalid) {
            Files.writeString(directory.resolve(task.id() + "-invalid-response.md"), response, StandardCharsets.UTF_8);
            String repairPrompt = task.prompt() + "\n\nOUTPUT FORMAT CORRECTION\n"
                    + "The previous response failed structural validation. Correct only its serialization and comply with the response schema above. "
                    + "Preserve the evidence-based decisions and reasoning. Do not invent facts or promote an unconfirmed finding. "
                    + "Return the complete corrected response.\nValidation error: " + EvidenceText.oneLine(invalid.getMessage())
                    + "\n\nPREVIOUS RESPONSE\n" + response;
            Files.writeString(directory.resolve(task.id() + "-repair-prompt.md"), repairPrompt, StandardCharsets.UTF_8);
            String repaired = agent.invoke(repairPrompt, directory.resolve(task.id() + "-repair"));
            Files.writeString(directory.resolve(task.id() + "-repair-response.md"), repaired, StandardCharsets.UTF_8);
            try {
                task.validator().validate(repaired);
            } catch (IOException stillInvalid) {
                throw new IOException("Task " + task.id() + " returned invalid structured output after format correction: "
                        + stillInvalid.getMessage(), stillInvalid);
            }
            Files.writeString(responseFile, repaired, StandardCharsets.UTF_8);
            return repaired;
        }
    }
}
