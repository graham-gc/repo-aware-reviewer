package reviewagent;

import java.time.Clock;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        try {
            if (args.length >= 2 && "knowledge".equals(args[0]) && "generate".equals(args[1])) {
                KnowledgeOptions options = KnowledgeOptions.parse(args);
                var output = new KnowledgePipeline(new ProcessExecutor(), Clock.systemDefaultZone()).run(options);
                System.out.println(output);
                return;
            }
            CliOptions options = CliOptions.parse(args);
            var output = new ReviewPipeline(new ProcessExecutor(), Clock.systemDefaultZone()).run(options);
            System.out.println(output);
            if (reviewExitCode(output) != 0) System.exit(3);
        } catch (IllegalArgumentException error) {
            System.err.println("Invalid arguments: " + error.getMessage());
            System.exit(2);
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            System.err.println("Review cancelled");
            System.exit(130);
        } catch (Exception error) {
            System.err.println("Review failed: " + error.getMessage());
            System.exit(1);
        }
    }

    static int reviewExitCode(java.nio.file.Path output) throws java.io.IOException {
        var status = output.resolve("review-status.md");
        if (!java.nio.file.Files.isRegularFile(status)) return 0;
        return java.nio.file.Files.readString(status).lines().anyMatch(line -> line.equals("- Status: INCOMPLETE")
                || line.equals("Status: INCOMPLETE")) ? 3 : 0;
    }
}

