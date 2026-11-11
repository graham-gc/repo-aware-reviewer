package reviewagent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

final class KnowledgePipeline {
    private final ProcessExecutor executor;
    private final Clock clock;

    KnowledgePipeline(ProcessExecutor executor, Clock clock) {
        this.executor = executor;
        this.clock = clock;
    }

    Path run(KnowledgeOptions options) throws IOException, InterruptedException {
        try (var progress = new CommandProgress("knowledge", System.err)) {
            return run(options, progress);
        }
    }

    private Path run(KnowledgeOptions options, CommandProgress progress) throws IOException, InterruptedException {
        progress.start("Scanning Git revision " + options.ref());
        GitClient git = new GitClient(options.repo(), executor);
        KnowledgeSnapshot snapshot = new RepositorySnapshotCollector(git).collect(
                options.ref(), options.chunkBytes(), options.maxTotalBytes(), options.maxFileBytes());
        int chunks = snapshot.chunks().size();
        int totalSteps = chunks + 1;
        progress.start("Scanned " + snapshot.files().size() + " tracked files and prepared " + chunks
                + " evidence chunks. Writing the scan inventory");
        Path runDir = createRunDirectory(options.outputDir(), snapshot.commit());
        Path chunksDir = Files.createDirectories(runDir.resolve("chunks"));
        Path promptsDir = Files.createDirectories(runDir.resolve("prompts"));
        Path observationsDir = Files.createDirectories(runDir.resolve("observations"));
        String inventory = inventory(snapshot);
        write(runDir.resolve("scope.md"), scope(options, snapshot));
        write(runDir.resolve("inventory.md"), inventory);

        KnowledgePromptFactory prompts = new KnowledgePromptFactory();
        List<String> observations = new ArrayList<>();
        AgentRunner agent = options.prepareOnly() ? null : new AgentRunner(executor,
                options.agentCommand(), options.agentArgs(), options.repo(), options.agentTimeout());
        for (int i = 0; i < chunks; i++) {
            String label = "chunk " + (i + 1) + " of " + chunks;
            progress.step(i, totalSteps, (agent == null ? "Preparing " : "Analyzing ") + label);
            String number = "%04d".formatted(i + 1);
            String chunk = snapshot.chunks().get(i);
            String prompt = prompts.extract(i + 1, chunks, snapshot.commit(), chunk,
                    options.language());
            write(chunksDir.resolve("chunk-" + number + ".txt"), chunk);
            write(promptsDir.resolve("extract-" + number + ".md"), prompt);
            if (agent != null) {
                String observation = agent.invoke(prompt);
                observations.add("\n\n## Chunk " + (i + 1) + "\n\n" + observation);
                write(observationsDir.resolve("observation-" + number + ".md"), observation);
            }
        }

        if (options.prepareOnly()) {
            progress.step(chunks, totalSteps, "Preparing synthesis prompt");
            write(promptsDir.resolve("synthesis-template.md"), prompts.synthesize(snapshot, inventory,
                    "[chunk observations will be inserted here]", options.language()));
            progress.finish(totalSteps, "Evidence and prompts ready");
            return runDir;
        }
        progress.step(chunks, totalSteps, "Synthesizing the candidate knowledge base");
        String combined = String.join("", observations);
        String synthesisPrompt = prompts.synthesize(snapshot, inventory, combined, options.language());
        write(promptsDir.resolve("synthesis.md"), synthesisPrompt);
        String candidate = agent.invoke(synthesisPrompt);
        write(runDir.resolve("candidate-knowledge.md"), candidate);
        progress.finish(totalSteps, "Wrote candidate-knowledge.md");
        return runDir;
    }

    private Path createRunDirectory(Path root, String commit) throws IOException {
        String timestamp = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss")
                .format(OffsetDateTime.now(clock).atZoneSameInstant(ZoneId.systemDefault()));
        Path directory = root.resolve(timestamp + "-" + commit.substring(0, 12));
        Files.createDirectories(directory);
        return directory;
    }

    private static String inventory(KnowledgeSnapshot snapshot) {
        StringBuilder out = new StringBuilder("# Repository file inventory\n\n");
        out.append("| Status | Bytes | Path | Reason |\n|---|---:|---|---|\n");
        for (KnowledgeFile file : snapshot.files()) {
            out.append('|').append(file.status()).append('|').append(file.size()).append("|`")
                    .append(EvidenceText.oneLine(file.path()).replace("|", "\\|")).append("`|")
                    .append(file.reason()).append("|\n");
        }
        return out.toString();
    }

    private static String scope(KnowledgeOptions options, KnowledgeSnapshot snapshot) {
        long includedFiles = snapshot.files().stream().filter(
                file -> file.status() == KnowledgeFile.Status.INCLUDED).count();
        return """
                # Knowledge scan scope

                - Repository: `%s`
                - Requested ref: `%s`
                - Resolved commit: `%s`
                - Tracked files: %d
                - Included text files: %d
                - Evidence chunks: %d
                - Included text bytes: %d
                - Total tracked bytes: %d
                - Repository code executed: no
                - Output language: `%s`
                - Candidate status: unconfirmed until human review
                """.formatted(options.repo(), options.ref(), snapshot.commit(), snapshot.files().size(),
                includedFiles, snapshot.chunks().size(), snapshot.includedBytes(), snapshot.repositoryBytes(),
                options.language());
    }

    private static void write(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
