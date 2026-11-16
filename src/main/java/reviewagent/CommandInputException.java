package reviewagent;

import java.io.IOException;

final class CommandInputException extends IOException {
    CommandInputException(Throwable cause) { super("Command closed stdin before the complete prompt was submitted", cause); }
}
