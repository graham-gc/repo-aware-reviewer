package reviewagent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/** A required stage has no validated result; its dependents must not publish conclusions. */
final class ReviewStageException extends IOException {
    record FailedTask(String id, String kind, Path checkpoint) {
        String reference(Path runDirectory) {
            Path root = runDirectory.toAbsolutePath().normalize();
            Path file = checkpoint.toAbsolutePath().normalize();
            if (!file.startsWith(root)) throw new IllegalArgumentException("Task checkpoint is outside the review directory");
            String relative = root.relativize(file).toString().replace('\\', '/');
            return id + " [" + kind + "]; see [" + relative + "](" + relative + ")";
        }
    }

    private final String stage;
    private final List<FailedTask> failures;

    ReviewStageException(String message) {
        super(message);
        stage = message;
        failures = List.of();
    }

    ReviewStageException(String stage, List<FailedTask> failures) {
        super(stage + " did not complete: " + failures.stream()
                .map(failure -> failure.id() + " [" + failure.kind() + "]; see " + failure.checkpoint())
                .collect(Collectors.joining("; ")));
        this.stage = stage;
        this.failures = List.copyOf(failures);
    }

    String reportMessage(Path runDirectory) {
        return failures.isEmpty() ? getMessage() : stage + " did not complete: " + failures.stream()
                .map(failure -> failure.reference(runDirectory)).collect(Collectors.joining("; "));
    }
}
