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
        default String corrected(String response, IOException failure) throws IOException { return response; }
        default String correctionInstructions(IOException failure) { return ""; }
        default boolean allowOutputCorrection() { return true; }
    }

    record Task(String id, String prompt, Validator validator, String model) {
        Task(String id, String prompt, Validator validator) { this(id, prompt, validator, null); }
    }

    record Outcome(String response, String error, String failureKind) {
        Outcome(String response, String error) { this(response, error, error == null ? "" : "INVALID_OUTPUT"); }
        boolean valid() { return error == null; }
    }

    private record Reply(String id, Outcome outcome) {
    }

    static Map<String, String> run(List<Task> tasks, AgentRunner agent, Path directory, int parallelism,
                                   CommandProgress progress, int stage, int stages, String description, boolean resume)
            throws IOException, InterruptedException {
        var outcomes = runBatch(tasks, agent, directory, parallelism, progress, stage, stages, description, resume, false);
        var responses = new LinkedHashMap<String, String>();
        outcomes.forEach((id, outcome) -> responses.put(id, outcome.response()));
        return responses;
    }

    static Map<String, Outcome> runIsolated(List<Task> tasks, AgentRunner agent, Path directory, int parallelism,
                                           CommandProgress progress, int stage, int stages, String description, boolean resume)
            throws IOException, InterruptedException {
        return runBatch(tasks, agent, directory, parallelism, progress, stage, stages, description, resume, true);
    }

    private static Map<String, Outcome> runBatch(List<Task> tasks, AgentRunner agent, Path directory, int parallelism,
                                                CommandProgress progress, int stage, int stages, String description, boolean resume,
                                                boolean isolateInvalidOutput) throws IOException, InterruptedException {
        Files.createDirectories(directory);
        if (tasks.isEmpty()) {
            return Map.of();
        }
        long batchStarted = System.nanoTime();
        try (var workers = Executors.newFixedThreadPool(Math.min(parallelism, tasks.size()), Thread.ofVirtual().factory())) {
            var completions = new ExecutorCompletionService<Reply>(workers);
            var futures = new ArrayList<Future<Reply>>();
            var globalFailure = new java.util.concurrent.atomic.AtomicBoolean();
            try {
                for (Task task : tasks) {
                    futures.add(completions.submit(() -> {
                        if (globalFailure.get()) {
                            state(directory, task, "BLOCKED", "GLOBAL_FAILURE");
                            return new Reply(task.id(), new Outcome(null, "Blocked by a global failure", "GLOBAL_FAILURE"));
                        }
                        state(directory, task, "RUNNING", "");
                        try (var budget = agent.task(task.model())) {
                            String response = invoke(task, agent, directory, resume);
                            Files.deleteIfExists(directory.resolve(task.id() + "-error.txt"));
                            state(directory, task, "SUCCEEDED", "");
                            agent.timings().task(budget.calls() == 0, true);
                            return new Reply(task.id(), new Outcome(response, null));
                        } catch (IOException failure) {
                            agent.timings().task(false, false);
                            boolean isolated = failure instanceof InvalidReviewResponseException
                                    || failure instanceof AgentCallException call && !call.fatal();
                            if (!isolated) globalFailure.set(true);
                            AtomicFiles.write(directory.resolve(task.id() + "-error.txt"), failure.getMessage());
                            String kind = failure instanceof AgentCallException call ? call.kind().name()
                                    : failure instanceof InvalidReviewResponseException ? "INVALID_OUTPUT" : "IO_FAILURE";
                            state(directory, task, "FAILED", kind, failure);
                            if (isolated) {
                                return new Reply(task.id(), new Outcome(null, failure.getMessage(), kind));
                            }
                            throw failure;
                        } catch (InterruptedException cancelled) {
                            agent.timings().task(false, false);
                            boolean interrupted = Thread.interrupted();
                            try { state(directory, task, "CANCELLED", "CANCELLED"); }
                            finally { if (interrupted) Thread.currentThread().interrupt(); }
                            throw cancelled;
                        }
                    }));
                }
                var replies = new LinkedHashMap<String, Outcome>();
                int invalidOutputs = 0;
                for (int completed = 1; completed <= tasks.size(); completed++) {
                    Reply reply = completions.take().get();
                    replies.put(reply.id(), reply.outcome());
                    if (!reply.outcome().valid()) invalidOutputs++;
                    progress.step(stage, stages, description + " (" + completed + " of " + tasks.size() + " complete"
                            + (invalidOutputs == 0 ? "" : "; " + invalidOutputs + " failed checks") + ")");
                }
                var ordered = new LinkedHashMap<String, Outcome>();
                tasks.forEach(task -> ordered.put(task.id(), replies.get(task.id())));
                if (!isolateInvalidOutput && ordered.values().stream().anyMatch(outcome -> !outcome.valid())) {
                    throw new ReviewStageException(description, ordered.entrySet().stream()
                            .filter(entry -> !entry.getValue().valid())
                            .map(entry -> new ReviewStageException.FailedTask(entry.getKey(), entry.getValue().failureKind(),
                                    directory.resolve(entry.getKey() + "-state.properties"))).toList());
                }
                return ordered;
            } catch (ExecutionException failure) {
                throw new IOException(description + " failed: " + failure.getCause().getMessage()
                        + ". See task artifacts in " + directory, failure.getCause());
            } finally {
                futures.forEach(future -> future.cancel(true));
                workers.shutdownNow();
            }
        } finally { agent.timings().stage(directory, batchStarted, System.nanoTime()); }
    }

    private static void state(Path directory, Task task, String status, String failure) throws IOException {
        state(directory, task, status, failure, null);
    }

    private static void state(Path directory, Task task, String status, String failure, IOException cause) throws IOException {
        AtomicFiles.write(directory.resolve(task.id() + "-state.properties"), "status=" + status
                + "\npromptSha256=" + InputDocument.sha256(task.prompt().getBytes(StandardCharsets.UTF_8))
                + "\nfailureKind=" + failure + "\nupdatedAt=" + java.time.Instant.now() + "\n"
                + FailureDiagnostics.properties(cause, failure));
    }

    private static String invoke(Task task, AgentRunner agent, Path directory, boolean resume)
            throws IOException, InterruptedException {
        Path responseFile = directory.resolve(task.id() + "-response.md");
        Path repairFile = directory.resolve(task.id() + "-repair-response.md");
        Path quoteRepairFile = directory.resolve(task.id() + "-quote-repair-response.md");
        Path decisionRepairFile = directory.resolve(task.id() + "-decision-repair-response.md");
        Path promptFile = directory.resolve(task.id() + "-prompt.md");
        String response = null;
        IOException cachedFailure = null;
        boolean samePrompt = Files.isRegularFile(promptFile)
                && Files.readString(promptFile, StandardCharsets.UTF_8).equals(task.prompt());
        // A valid old verdict is not reusable when its instructions or supplied evidence have changed.
        if (resume && samePrompt) {
            for (Path saved : List.of(responseFile, repairFile, quoteRepairFile, decisionRepairFile)) {
                if (!Files.isRegularFile(saved)) {
                    continue;
                }
                String raw = Files.readString(saved, StandardCharsets.UTF_8);
                String cached = cachedFailure == null ? raw : corrected(task, raw, cachedFailure, directory, saved.getFileName().toString().replace("-response.md", "-applied-response.md"));
                try {
                    validate(task, cached, directory, agent.timings(), true);
                } catch (IOException invalid) {
                    if (invalid instanceof ArtifactIOException) throw invalid;
                    response = cached;
                    cachedFailure = invalid;
                    continue;
                }
                if (!saved.equals(responseFile) || !cached.equals(raw)) {
                    if (response != null) {
                        AtomicFiles.write(directory.resolve(task.id() + "-invalid-response.md"), response);
                    }
                    AtomicFiles.write(responseFile, cached);
                }
                return cached;
            }
        }
        boolean responseFromCache = response != null;
        if (response == null) {
            // Invalidate before recording the new prompt: an interrupted invocation must not associate an
            // old response (or repair) with the new evidence on the next resume.
            if (!samePrompt) {
                for (Path old : List.of(responseFile, repairFile, quoteRepairFile, decisionRepairFile, promptFile)) {
                    if (Files.isRegularFile(old)) Files.move(old,
                            old.resolveSibling(old.getFileName().toString().replace(task.id() + "-", task.id() + "-superseded-" + java.util.UUID.randomUUID() + "-")),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            AtomicFiles.write(promptFile, task.prompt());
            response = agent.invoke(task.prompt(), directory.resolve(task.id()));
            AtomicFiles.write(responseFile, response);
        }
        var attempted = new java.util.HashSet<String>();
        while (true) {
            try {
                validate(task, response, directory, agent.timings(), responseFromCache);
            } catch (IOException invalid) {
                if (invalid instanceof ArtifactIOException) throw invalid;
                boolean quotation = invalid instanceof SourceQuotationException;
                boolean decision = invalid instanceof ReviewDecisionException;
                String kind = quotation ? "quote-repair" : decision ? "decision-repair" : "repair";
                boolean correct = task.validator().allowOutputCorrection();
                if (!correct || !attempted.add(kind)) {
                    throw new InvalidReviewResponseException("Task " + task.id() + " returned invalid structured output"
                            + (correct ? " after bounded output correction" : "") + ": "
                            + invalid.getMessage(), invalid);
                }
                AtomicFiles.write(directory.resolve(task.id() + (quotation ? "-quote-invalid-response.md" : decision ? "-decision-invalid-response.md" : "-invalid-response.md")),
                        response);
                String correction = decision
                        ? "DECISION EVIDENCE CORRECTION\n"
                            + "The response is structurally readable but its conclusion lacks required proof or conflicts with its policy basis. "
                            + "Re-evaluate the verdict and its premises against supplied evidence. Downgrade unsupported conclusions to "
                            + "INSUFFICIENT_EVIDENCE; request missing code when useful. Never invent a premise, policy, quote or source location. "
                        : quotation
                        ? "SOURCE QUOTATION CORRECTION\n"
                            + "The response contains quotations absent from the supplied source. Re-copy each invalid requirement "
                            + "or citation directly and exactly from the original evidence block. Fix matching quotations consistently. "
                            + "A quote may belong to a different included source: diff metadata and +/- lines require a DIFF ID, "
                            + "while unchanged lines may require a TARGET_SOURCE or BASE_SOURCE ID. Use the exact-match reference "
                            + "candidates in the validation feedback only after checking their file, revision and kind; copy their short SRC reference. "
                            + "For a near-matching quotation, feedback may supply an exact <source> plus <lines> citation. "
                            + "After rechecking the claim against its actual excerpt, copy that selector and omit quote to avoid "
                            + "transcription errors. Do not preserve reasoning that depends on a mistyped method or value. "
                            + "Use requirementText for paraphrases or translations. Preserve applicable requirement meaning and "
                            + "supported conclusions; do not invent a requirement or evidence, or promote an unconfirmed finding. "
                            + "If no supplied source supports a conclusion, keep it insufficient rather than manufacturing a quote. "
                        : "OUTPUT FORMAT CORRECTION\n"
                            + "The previous response failed structural validation. Correct only its serialization and comply with the response schema above. "
                            + "Preserve the evidence-based decisions and reasoning. Do not invent facts or promote an unconfirmed finding. ";
                String repairPrompt = ReviewXml.missingResponseBlock(invalid, "findings")
                        ? retryDiscoveryPrompt(task.prompt())
                        : task.prompt() + "\n\n" + correction + task.validator().correctionInstructions(invalid)
                            + "Return the complete corrected response.\nValidation error: " + invalid.getMessage()
                            + "\n\nPREVIOUS RESPONSE\n" + response;
                AtomicFiles.write(directory.resolve(task.id() + "-" + kind + "-prompt.md"), repairPrompt);
                String raw = agent.invoke(repairPrompt, directory.resolve(task.id() + "-" + kind));
                AtomicFiles.write(directory.resolve(task.id() + "-" + kind + "-response.md"), raw);
                response = corrected(task, raw, invalid, directory, task.id() + "-" + kind + "-applied-response.md");
                responseFromCache = false;
                continue;
            }
            AtomicFiles.write(responseFile, response);
            return response;
        }
    }

    private static String retryDiscoveryPrompt(String originalPrompt) {
        return """
                DISCOVERY RESPONSE RECOVERY
                Run the original review again using the evidence below. Start the reply with exactly one complete
                fenced XML block with the root <findings>. Close every element. If the review yields no candidates,
                return <findings/> in that block. Put any necessary evidence requests or review notes after the block,
                using the original request syntax. Include any evidence-supported candidates you find.

                ORIGINAL REVIEW
                """ + originalPrompt;
    }

    private static void validate(Task task, String response, Path directory, ReviewTimings timings, boolean cached)
            throws IOException {
        try (var diagnostics = ResponseDiagnostics.capture()) {
            try {
                long started = System.nanoTime();
                boolean accepted = false;
                try {
                    AgentRunner.validateResponse(response);
                    task.validator().validate(response);
                    accepted = true;
                } finally {
                    long elapsed = System.nanoTime() - started;
                    timings.validation(directory.resolve(task.id()), elapsed, cached, accepted);
                }
            } catch (IOException invalid) {
                if (!(invalid instanceof ArtifactIOException)) ValidationEvents.rejected(directory.resolve(task.id() + "-validation.log"), invalid);
                throw invalid;
            } finally {
                try {
                    if (!diagnostics.notes().isEmpty()) Files.writeString(directory.resolve(task.id() + "-normalization.log"),
                            String.join("\n", diagnostics.notes()) + "\n", StandardCharsets.UTF_8,
                            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                } catch (IOException storage) { throw new ArtifactIOException(storage); }
            }
        }
    }

    private static String corrected(Task task, String raw, IOException failure, Path directory, String artifact) throws IOException {
        try (var diagnostics = ResponseDiagnostics.capture()) {
            String corrected = task.validator().corrected(raw, failure);
            if (!corrected.equals(raw)) AtomicFiles.write(directory.resolve(artifact), corrected);
            if (!diagnostics.notes().isEmpty()) Files.writeString(directory.resolve(task.id() + "-normalization.log"),
                    String.join("\n", diagnostics.notes()) + "\n", StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            return corrected;
        }
    }
}
