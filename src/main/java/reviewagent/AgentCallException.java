package reviewagent;

import java.io.IOException;

/** A failed model invocation, distinct from invalid structured output and artifact I/O failure. */
final class AgentCallException extends IOException {
    enum Kind { EMPTY_RESPONSE, TRANSIENT, TIMEOUT, STDIN_CLOSED, INVALID_ENCODING, COMMAND_EXIT, STRUCTURED_OUTPUT,
        CONFIGURATION, INPUT_LIMIT, BUDGET_EXHAUSTED }
    private final Kind kind;

    AgentCallException(Kind kind, String message) { this(kind, message, null); }
    AgentCallException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }
    Kind kind() { return kind; }
    boolean retryable() { return kind == Kind.EMPTY_RESPONSE || kind == Kind.TRANSIENT; }
    boolean fatal() { return kind == Kind.CONFIGURATION; }
}
