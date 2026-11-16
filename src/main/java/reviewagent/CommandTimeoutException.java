package reviewagent;

import java.io.IOException;

final class CommandTimeoutException extends IOException {
    CommandTimeoutException(String message, Throwable cause) { super(message, cause); }
}
