package reviewagent;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

record CliOptions(Path repo, String commit, String branch, String base, String range,
                  Path requirement, Path knowledge, Path outputDir,
                  String agentCommand, List<String> agentArgs,
                  boolean prepareOnly, int maxEvidenceBytes, Duration agentTimeout) {

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
        Path outputDir = Path.of("review-output");
        String agentCommand = null;
        List<String> agentArgs = new ArrayList<>();
        boolean prepareOnly = false;
        int maxEvidenceBytes = 1_000_000;
        Duration timeout = Duration.ofMinutes(15);

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--repo" -> repo = Path.of(value(args, ++i, "--repo"));
                case "--commit" -> commit = value(args, ++i, "--commit");
                case "--branch" -> branch = value(args, ++i, "--branch");
                case "--base" -> base = value(args, ++i, "--base");
                case "--range" -> range = value(args, ++i, "--range");
                case "--requirement" -> requirement = Path.of(value(args, ++i, "--requirement"));
                case "--knowledge" -> knowledge = Path.of(value(args, ++i, "--knowledge"));
                case "--output-dir" -> outputDir = Path.of(value(args, ++i, "--output-dir"));
                case "--agent-command" -> agentCommand = value(args, ++i, "--agent-command");
                case "--agent-arg" -> agentArgs.add(value(args, ++i, "--agent-arg"));
                case "--prepare-only" -> prepareOnly = true;
                case "--max-evidence-bytes" -> maxEvidenceBytes = Integer.parseInt(value(args, ++i, "--max-evidence-bytes"));
                case "--agent-timeout-seconds" -> timeout = Duration.ofSeconds(Long.parseLong(value(args, ++i, "--agent-timeout-seconds")));
                default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }

        if (repo == null) {
            throw new IllegalArgumentException("--repo is required");
        }
        int scopes = (commit != null ? 1 : 0) + (branch != null ? 1 : 0) + (range != null ? 1 : 0);
        if (scopes > 1) {
            throw new IllegalArgumentException("Use only one of --commit, --branch, or --range");
        }
        if (!prepareOnly && agentCommand == null) {
            throw new IllegalArgumentException("--agent-command is required unless --prepare-only is used");
        }
        if (maxEvidenceBytes < 10_000) {
            throw new IllegalArgumentException("--max-evidence-bytes must be at least 10000");
        }
        return new CliOptions(repo.toAbsolutePath().normalize(), commit, branch, base, range,
                absolute(requirement), absolute(knowledge), outputDir.toAbsolutePath().normalize(),
                agentCommand, List.copyOf(agentArgs), prepareOnly, maxEvidenceBytes, timeout);
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

