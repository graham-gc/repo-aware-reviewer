package reviewagent;

import java.io.IOException;

final class CommandStartException extends IOException {
    CommandStartException(String message, Throwable cause) { super(message, cause); }
}
