package reviewagent;

import java.io.IOException;

/** A required stage has no validated result; its dependents must not publish conclusions. */
final class ReviewStageException extends IOException {
    ReviewStageException(String message) { super(message); }
}
