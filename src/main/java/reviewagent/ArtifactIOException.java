package reviewagent;

import java.io.IOException;

/** Diagnostics are storage operations, not model serialization errors eligible for correction. */
final class ArtifactIOException extends IOException {
    ArtifactIOException(IOException cause) { super("Unable to save review diagnostics", cause); }
}
