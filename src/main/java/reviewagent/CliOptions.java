package reviewagent;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

record CliOptions(Path repo, String commit, String branch, String base, String range,
                  Path requirement, Path knowledge, KnowledgeMode knowledgeMode, Path outputDir,
                  String agentCommand, List<String> agentArgs,
                  boolean prepareOnly, int maxEvidenceBytes, int maxSupplementalRequests, int maxKnowledgeQueries,
                  int reviewParallelism, Duration agentTimeout, String language) {
    enum KnowledgeMode {
        FULL, SCOPED;

        static KnowledgeMode parse(String value) {
            return switch (value) {
                case "full" -> FULL;
                case "scoped" -> SCOPED;
                default -> throw new IllegalArgumentException("--knowledge-mode must be full or scoped");
            };
        }
    }

    static CliOptions parse(String[] args) {
        if (args.length == 0 || !"review".equals(args[0])) {
            throw new IllegalArgumentException("Expected subcommand: review");
        }
        Path repo = null;
        String commit = null;
        String branch = null;
        String base = null;
        String range = null;
        Path requirement = null;
        Path knowledge = null;
        KnowledgeMode knowledgeMode = KnowledgeMode.FULL;
        Path outputDir = Path.of("review-output");
        String agentCommand = null;
        List<String> agentArgs = new ArrayList<>();
        boolean prepareOnly = false;
        int maxEvidenceBytes = 1_000_000;
        int maxSupplementalRequests = 0;
        int maxKnowledgeQueries = 0;
        int reviewParallelism = 3;
        Duration timeout = Duration.ofMinutes(15);
        String language = "en";

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--repo" -> repo = Path.of(value(args, ++i, "--repo"));
                case "--commit" -> commit = value(args, ++i, "--commit");
                case "--branch" -> branch = value(args, ++i, "--branch");
                case "--base" -> base = value(args, ++i, "--base");
                case "--range" -> range = value(args, ++i, "--range");
                case "--requirement" -> requirement = Path.of(value(args, ++i, "--requirement"));
                case "--knowledge" -> knowledge = Path.of(value(args, ++i, "--knowledge"));
                case "--knowledge-mode" -> knowledgeMode = KnowledgeMode.parse(value(args, ++i, "--knowledge-mode"));
                case "--output-dir" -> outputDir = Path.of(value(args, ++i, "--output-dir"));
                case "--agent-command" -> agentCommand = value(args, ++i, "--agent-command");
                case "--agent-arg" -> agentArgs.add(value(args, ++i, "--agent-arg"));
                case "--prepare-only" -> prepareOnly = true;
                case "--max-evidence-bytes" -> maxEvidenceBytes = Integer.parseInt(value(args, ++i, "--max-evidence-bytes"));
                case "--max-supplemental-requests" -> maxSupplementalRequests = Integer.parseInt(value(args, ++i, "--max-supplemental-requests"));
                case "--max-knowledge-queries" -> maxKnowledgeQueries = Integer.parseInt(value(args, ++i, "--max-knowledge-queries"));
                case "--review-parallelism" -> reviewParallelism = Integer.parseInt(value(args, ++i, "--review-parallelism"));
                case "--agent-timeout-seconds" -> timeout = Duration.ofSeconds(Long.parseLong(value(args, ++i, "--agent-timeout-seconds")));
                case "--language" -> language = OutputLanguage.parse(value(args, ++i, "--language")).code();
                default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }

        if (repo == null) {
            throw new IllegalArgumentException("--repo is required");
        }
        if (knowledgeMode == KnowledgeMode.SCOPED && knowledge == null) {
            throw new IllegalArgumentException("--knowledge-mode scoped requires --knowledge");
        }
        int scopes = (commit != null ? 1 : 0) + (branch != null ? 1 : 0) + (range != null ? 1 : 0);
        if (scopes > 1) {
            throw new IllegalArgumentException("Use only one of --commit, --branch, or --range");
        }
        if (!prepareOnly && agentCommand == null) {
            agentCommand = "claude";
            agentArgs.add("-p");
        }
        if (maxEvidenceBytes < 10_000) {
            throw new IllegalArgumentException("--max-evidence-bytes must be at least 10000");
        }
        if (maxSupplementalRequests < 0 || maxKnowledgeQueries < 0) {
            throw new IllegalArgumentException("Request count limits must be non-negative (0 means unlimited)");
        }
        if (reviewParallelism < 1) {
            throw new IllegalArgumentException("--review-parallelism must be at least 1");
        }
        return new CliOptions(repo.toAbsolutePath().normalize(), commit, branch, base, range,
                absolute(requirement), absolute(knowledge), knowledgeMode, outputDir.toAbsolutePath().normalize(),
                agentCommand, List.copyOf(agentArgs), prepareOnly, maxEvidenceBytes,
                maxSupplementalRequests, maxKnowledgeQueries, reviewParallelism, timeout, language);
    }

    private static Path absolute(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " requires a value");
        }
        return args[index];
    }
}

