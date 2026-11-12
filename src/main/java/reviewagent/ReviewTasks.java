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
                    progress.step(stage, stages, description + " (" + completed + " of " + tasks.size() + " complete)");
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
        Path repairFile = directory.resolve(task.id() + "-repair-response.md");
        Path quoteRepairFile = directory.resolve(task.id() + "-quote-repair-response.md");
        Path decisionRepairFile = directory.resolve(task.id() + "-decision-repair-response.md");
        Path promptFile = directory.resolve(task.id() + "-prompt.md");
        String response = null;
        boolean samePrompt = Files.isRegularFile(promptFile)
                && Files.readString(promptFile, StandardCharsets.UTF_8).equals(task.prompt());
        // A valid old verdict is not reusable when its instructions or supplied evidence have changed.
        if (resume && samePrompt) {
            for (Path saved : List.of(responseFile, repairFile, quoteRepairFile, decisionRepairFile)) {
                if (!Files.isRegularFile(saved)) {
                    continue;
                }
                String cached = Files.readString(saved, StandardCharsets.UTF_8);
                try {
                    AgentRunner.validateResponse(cached);
                    task.validator().validate(cached);
                } catch (IOException invalid) {
                    response = cached;
                    continue;
                }
                if (!saved.equals(responseFile)) {
                    if (response != null) {
                        Files.writeString(directory.resolve(task.id() + "-invalid-response.md"), response, StandardCharsets.UTF_8);
                    }
                    Files.writeString(responseFile, cached, StandardCharsets.UTF_8);
                }
                return cached;
            }
        }
        if (response == null) {
            // Invalidate before recording the new prompt: an interrupted invocation must not associate an
            // old response (or repair) with the new evidence on the next resume.
            if (!samePrompt) {
                for (Path old : List.of(responseFile, repairFile, quoteRepairFile, decisionRepairFile, promptFile)) {
                    if (Files.isRegularFile(old)) Files.move(old,
                            old.resolveSibling(old.getFileName().toString().replace(task.id() + "-", task.id() + "-superseded-")),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Files.writeString(promptFile, task.prompt(), StandardCharsets.UTF_8);
            response = agent.invoke(task.prompt(), directory.resolve(task.id()));
            Files.writeString(responseFile, response, StandardCharsets.UTF_8);
        }
        var attempted = new java.util.HashSet<String>();
        while (true) {
            try {
                AgentRunner.validateResponse(response);
                task.validator().validate(response);
            } catch (IOException invalid) {
                boolean quotation = invalid instanceof SourceQuotationException;
                boolean decision = invalid instanceof ReviewDecisionException;
                String kind = quotation ? "quote-repair" : decision ? "decision-repair" : "repair";
                if (!attempted.add(kind)) {
                    throw new IOException("Task " + task.id() + " returned invalid structured output after bounded output correction: "
                            + invalid.getMessage(), invalid);
                }
                Files.writeString(directory.resolve(task.id() + (quotation ? "-quote-invalid-response.md" : decision ? "-decision-invalid-response.md" : "-invalid-response.md")),
                        response, StandardCharsets.UTF_8);
                String correction = decision
                        ? "DECISION EVIDENCE CORRECTION\n"
                            + "The response is structurally readable but its conclusion lacks required proof or conflicts with its policy basis. "
                            + "Re-evaluate the verdict and its premises against supplied evidence. Downgrade unsupported conclusions to "
                            + "INSUFFICIENT_EVIDENCE; request missing code when useful. Never invent a premise, policy, quote or source location. "
                        : quotation
                        ? "SOURCE QUOTATION CORRECTION\n"
                            + "The response contains quotations absent from the supplied source. Re-copy each invalid requirement "
                            + "or citation directly and exactly from the original evidence block. Fix matching quotations consistently. "
                            + "Use requirementText for paraphrases or translations. Preserve applicable requirement meaning and "
                            + "supported conclusions; do not invent a requirement or evidence, or promote an unconfirmed finding. "
                            + "If no supplied source supports a conclusion, keep it insufficient rather than manufacturing a quote. "
                        : "OUTPUT FORMAT CORRECTION\n"
                            + "The previous response failed structural validation. Correct only its serialization and comply with the response schema above. "
                            + "Preserve the evidence-based decisions and reasoning. Do not invent facts or promote an unconfirmed finding. ";
                String repairPrompt = task.prompt() + "\n\n" + correction
                        + "Return the complete corrected response.\nValidation error: " + invalid.getMessage()
                        + "\n\nPREVIOUS RESPONSE\n" + response;
                Files.writeString(directory.resolve(task.id() + "-" + kind + "-prompt.md"), repairPrompt, StandardCharsets.UTF_8);
                response = agent.invoke(repairPrompt, directory.resolve(task.id() + "-" + kind));
                Files.writeString(directory.resolve(task.id() + "-" + kind + "-response.md"), response, StandardCharsets.UTF_8);
                continue;
            }
            Files.writeString(responseFile, response, StandardCharsets.UTF_8);
            return response;
        }
    }
}
