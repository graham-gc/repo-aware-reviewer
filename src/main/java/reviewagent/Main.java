package reviewagent;

import java.time.Clock;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        try {
            CliOptions options = CliOptions.parse(args);
            var output = new ReviewPipeline(new ProcessExecutor(), Clock.systemDefaultZone()).run(options);
            System.out.println(output);
        } catch (IllegalArgumentException error) {
            System.err.println("Invalid arguments: " + error.getMessage());
            System.exit(2);
        } catch (Exception error) {
            System.err.println("Review failed: " + error.getMessage());
            System.exit(1);
        }
    }
}

