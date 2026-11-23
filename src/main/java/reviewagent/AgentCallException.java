package reviewagent;

import java.io.IOException;
import java.time.Duration;

/** A failed model invocation, distinct from invalid structured output and artifact I/O failure. */
final class AgentCallException extends IOException {
    enum Kind { EMPTY_RESPONSE, TRANSIENT, RATE_LIMIT, TIMEOUT, STDIN_CLOSED, INVALID_ENCODING, COMMAND_EXIT, STRUCTURED_OUTPUT,
        CONFIGURATION, INPUT_LIMIT, BUDGET_EXHAUSTED }
    private final Kind kind;
    private final Duration retryAfter;

    AgentCallException(Kind kind, String message) { this(kind, message, null); }
    AgentCallException(Kind kind, String message, Throwable cause) {
        this(kind, message, cause, Duration.ZERO);
    }
    AgentCallException(Kind kind, String message, Throwable cause, Duration retryAfter) {
        super(message, cause);
        this.kind = kind;
        this.retryAfter = retryAfter;
    }
    Kind kind() { return kind; }
    Duration retryAfter() { return retryAfter; }
    boolean retryable() { return kind == Kind.EMPTY_RESPONSE || kind == Kind.TRANSIENT || kind == Kind.RATE_LIMIT; }
    boolean fatal() { return kind == Kind.CONFIGURATION; }
}
