package reviewagent;

import java.time.Duration;

/** One logical task shares this budget across transport retries and output corrections. */
record AgentPolicy(int retries, int maxCalls, Duration taskTimeout, Duration retryDelay) {
    AgentPolicy {
        if (retries < 0) throw new IllegalArgumentException("--agent-retries must be non-negative");
        if (maxCalls < 1) throw new IllegalArgumentException("--agent-max-calls-per-task must be positive");
        if (taskTimeout.isZero() || taskTimeout.isNegative())
            throw new IllegalArgumentException("--agent-task-timeout-seconds must be positive");
        if (retryDelay.isNegative()) throw new IllegalArgumentException("Retry delay must be non-negative");
    }

    static AgentPolicy defaults(Duration callTimeout) {
        return new AgentPolicy(1, 5, callTimeout.multipliedBy(2), Duration.ofSeconds(1));
    }
}
