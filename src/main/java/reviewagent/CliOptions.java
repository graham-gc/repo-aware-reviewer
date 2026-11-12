package reviewagent;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

record CliOptions(Path repo, String commit, String branch, String base, String baseRemote, Duration fetchTimeout,
                  Path requirement, Path knowledge, KnowledgeMode knowledgeMode, Path outputDir, Path resumeDirectory,
                  String agentCommand, List<String> agentArgs,
                  boolean prepareOnly, int maxEvidenceBytes, int maxSupplementalRequests, int maxKnowledgeQueries,
                  int reviewParallelism, Duration agentTimeout, String language, java.util.Map<String, Path> languageCaches) {
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
        String baseRemote = null;
        Duration fetchTimeout = Duration.ofMinutes(2);
        boolean fetchTimeoutSpecified = false;
        Path requirement = null;
        Path knowledge = null;
        KnowledgeMode knowledgeMode = KnowledgeMode.FULL;
        Path outputDir = Path.of("review-output");
        Path resumeDirectory = null;
        String agentCommand = null;
        List<String> agentArgs = new ArrayList<>();
        boolean prepareOnly = false;
        int maxEvidenceBytes = 1_000_000;
        int maxSupplementalRequests = 0;
        int maxKnowledgeQueries = 0;
        int reviewParallelism = 3;
        Duration timeout = Duration.ofMinutes(15);
        String language = "en";
        var languageCaches = new java.util.LinkedHashMap<String, Path>();

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--repo" -> repo = Path.of(value(args, ++i, "--repo"));
                case "--commit" -> commit = value(args, ++i, "--commit");
                case "--branch" -> branch = value(args, ++i, "--branch");
                case "--base" -> base = value(args, ++i, "--base");
                case "--base-remote" -> baseRemote = value(args, ++i, "--base-remote");
                case "--fetch-timeout-seconds" -> {
                    fetchTimeout = Duration.ofSeconds(Long.parseLong(value(args, ++i, "--fetch-timeout-seconds")));
                    fetchTimeoutSpecified = true;
                }
                case "--requirement" -> requirement = Path.of(value(args, ++i, "--requirement"));
                case "--knowledge" -> knowledge = Path.of(value(args, ++i, "--knowledge"));
                case "--knowledge-mode" -> knowledgeMode = KnowledgeMode.parse(value(args, ++i, "--knowledge-mode"));
                case "--output-dir" -> outputDir = Path.of(value(args, ++i, "--output-dir"));
                case "--resume" -> resumeDirectory = Path.of(value(args, ++i, "--resume"));
                case "--agent-command" -> agentCommand = value(args, ++i, "--agent-command");
                case "--agent-arg" -> agentArgs.add(value(args, ++i, "--agent-arg"));
                case "--prepare-only" -> prepareOnly = true;
                case "--max-evidence-bytes" -> maxEvidenceBytes = Integer.parseInt(value(args, ++i, "--max-evidence-bytes"));
                case "--max-supplemental-requests" -> maxSupplementalRequests = Integer.parseInt(value(args, ++i, "--max-supplemental-requests"));
                case "--max-knowledge-queries" -> maxKnowledgeQueries = Integer.parseInt(value(args, ++i, "--max-knowledge-queries"));
                case "--review-parallelism" -> reviewParallelism = Integer.parseInt(value(args, ++i, "--review-parallelism"));
                case "--agent-timeout-seconds" -> timeout = Duration.ofSeconds(Long.parseLong(value(args, ++i, "--agent-timeout-seconds")));
                case "--language" -> language = OutputLanguage.parse(value(args, ++i, "--language")).code();
                case "--language-cache" -> {
                    String[] cache = value(args, ++i, "--language-cache").split("=", 2);
                    if (cache.length != 2 || !cache[0].matches("[a-z][a-z0-9-]*") || cache[1].isBlank())
                        throw new IllegalArgumentException("--language-cache requires language-id=/path/to/cache");
                    languageCaches.put(cache[0], Path.of(cache[1]).toAbsolutePath().normalize());
                }
                default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }

        if (repo == null) {
            throw new IllegalArgumentException("--repo is required");
        }
        if (knowledgeMode == KnowledgeMode.SCOPED && knowledge == null) {
            throw new IllegalArgumentException("--knowledge-mode scoped requires --knowledge");
        }
        if (commit != null && branch != null) {
            throw new IllegalArgumentException("Use only one of --commit or --branch");
        }
        if (branch != null && base == null) {
            throw new IllegalArgumentException("--branch requires --base");
        }
        if (base != null && branch == null) {
            throw new IllegalArgumentException("--base requires --branch; use --branch <target-ref> --base <base-ref> to compare revisions");
        }
        if (baseRemote != null && branch == null) {
            throw new IllegalArgumentException("--base-remote requires --branch and --base");
        }
        if (baseRemote != null && !baseRemote.matches("[A-Za-z0-9][A-Za-z0-9._/-]*")) {
            throw new IllegalArgumentException("--base-remote must be a configured Git remote name, such as origin");
        }
        if (fetchTimeoutSpecified && baseRemote == null) {
            throw new IllegalArgumentException("--fetch-timeout-seconds requires --base-remote");
        }
        if (fetchTimeout.isZero() || fetchTimeout.isNegative()) {
            throw new IllegalArgumentException("--fetch-timeout-seconds must be positive");
        }
        if (!prepareOnly && agentCommand == null) {
            agentCommand = "claude";
            agentArgs.addAll(AgentRunner.defaultArguments());
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
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("--agent-timeout-seconds must be positive");
        }
        return new CliOptions(repo.toAbsolutePath().normalize(), commit, branch, base, baseRemote, fetchTimeout,
                absolute(requirement), absolute(knowledge), knowledgeMode, outputDir.toAbsolutePath().normalize(), absolute(resumeDirectory),
                agentCommand, List.copyOf(agentArgs), prepareOnly, maxEvidenceBytes,
                maxSupplementalRequests, maxKnowledgeQueries, reviewParallelism, timeout, language,
                java.util.Collections.unmodifiableMap(languageCaches));
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

