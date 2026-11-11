package reviewagent;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

record KnowledgeOptions(Path repo, String ref, Path outputDir, String agentCommand,
                        List<String> agentArgs, boolean prepareOnly, int chunkBytes,
                        long maxTotalBytes, int maxFileBytes, Duration agentTimeout,
                        String language) {
    static KnowledgeOptions parse(String[] args) {
        Path repo = null;
        String ref = "HEAD";
        Path outputDir = Path.of("knowledge-output");
        String agentCommand = null;
        List<String> agentArgs = new ArrayList<>();
        boolean prepareOnly = false;
        int chunkBytes = 250_000;
        long maxTotalBytes = 20_000_000L;
        int maxFileBytes = 1_000_000;
        Duration timeout = Duration.ofMinutes(15);
        String language = "en";

        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--repo" -> repo = Path.of(value(args, ++i, "--repo"));
                case "--ref" -> ref = value(args, ++i, "--ref");
                case "--output-dir" -> outputDir = Path.of(value(args, ++i, "--output-dir"));
                case "--agent-command" -> agentCommand = value(args, ++i, "--agent-command");
                case "--agent-arg" -> agentArgs.add(value(args, ++i, "--agent-arg"));
                case "--prepare-only" -> prepareOnly = true;
                case "--chunk-bytes" -> chunkBytes = Integer.parseInt(value(args, ++i, "--chunk-bytes"));
                case "--max-total-bytes" -> maxTotalBytes = Long.parseLong(value(args, ++i, "--max-total-bytes"));
                case "--max-file-bytes" -> maxFileBytes = Integer.parseInt(value(args, ++i, "--max-file-bytes"));
                case "--agent-timeout-seconds" -> timeout = Duration.ofSeconds(
                        Long.parseLong(value(args, ++i, "--agent-timeout-seconds")));
                case "--language" -> language = OutputLanguage.parse(value(args, ++i, "--language")).code();
                default -> throw new IllegalArgumentException("Unknown knowledge option: " + args[i]);
            }
        }
        if (repo == null) {
            throw new IllegalArgumentException("--repo is required");
        }
        if (!prepareOnly && agentCommand == null) {
            agentCommand = "claude";
            agentArgs.addAll(AgentRunner.defaultArguments());
        }
        if (chunkBytes < 20_000 || maxFileBytes < 10_000 || maxTotalBytes < chunkBytes) {
            throw new IllegalArgumentException("Invalid scan byte limits");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("--agent-timeout-seconds must be positive");
        }
        return new KnowledgeOptions(repo.toAbsolutePath().normalize(), ref,
                outputDir.toAbsolutePath().normalize(), agentCommand, List.copyOf(agentArgs),
                prepareOnly, chunkBytes, maxTotalBytes, maxFileBytes, timeout, language);
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " requires a value");
        }
        return args[index];
    }
}

