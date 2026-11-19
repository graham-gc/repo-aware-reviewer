package reviewagent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Append-only producer-owned metadata: successful correction must not erase the rejection history. */
final class ValidationEvents {
    static void rejected(Path path, IOException failure) throws IOException {
        String fields = "event=VALIDATION_REJECTED\nrecordedAt=" + java.time.Instant.now() + "\n"
                + FailureDiagnostics.properties(failure, "INVALID_OUTPUT") + "\n";
        try {
            Files.writeString(path, fields, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException storage) { throw new ArtifactIOException(storage); }
    }
}
