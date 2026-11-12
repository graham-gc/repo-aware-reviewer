package reviewagent;

import java.io.IOException;

/** Two interpretations could swallow sibling protocol fields; require an explicit delimiter or CDATA. */
final class AmbiguousTextFieldException extends IOException {
    AmbiguousTextFieldException(String message) { super(message); }
}
