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
    record Task(String id, String prompt) {
    }

    private record Reply(String id, String response) {
    }

    static Map<String, String> run(List<Task> tasks, AgentRunner agent, Path directory, int parallelism,
                                   CommandProgress progress, int stage, int stages, String description)
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
                    Files.writeString(directory.resolve(task.id() + "-prompt.md"), task.prompt(), StandardCharsets.UTF_8);
                    futures.add(completions.submit(() -> {
                        try {
                            String response = agent.invoke(task.prompt());
                            Files.writeString(directory.resolve(task.id() + "-response.md"), response, StandardCharsets.UTF_8);
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
                throw new IOException(description + " failed. See task artifacts in " + directory, failure.getCause());
            } finally {
                futures.forEach(future -> future.cancel(true));
                workers.shutdownNow();
            }
        }
    }
}
