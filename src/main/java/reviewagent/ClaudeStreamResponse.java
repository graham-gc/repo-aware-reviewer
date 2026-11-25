package reviewagent;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Extract completed Claude Code replies and time the local receipt of streaming events. */
final class ClaudeStreamResponse implements ProcessExecutor.StdoutObserver {
    static final List<String> ARGUMENTS = List.of("--output-format", "stream-json", "--verbose", "--include-partial-messages");

    record Timing(Long firstEvent, Long firstThinking, Long lastThinking, Long firstText, Long lastText,
                  Long resultReceived, Long duration, Long apiDuration, Long streamTtft, Long ttft, Long turns) {
        String properties() {
            return "firstStreamEventMillis=" + value(firstEvent)
                    + "\nfirstThinkingDeltaMillis=" + value(firstThinking) + "\nlastThinkingDeltaMillis=" + value(lastThinking)
                    + "\nfirstTextDeltaMillis=" + value(firstText) + "\nlastTextDeltaMillis=" + value(lastText)
                    + "\nstreamResultMillis=" + value(resultReceived) + "\nclaudeDurationMillis=" + value(duration)
                    + "\nclaudeApiDurationMillis=" + value(apiDuration) + "\nclaudeTtftStreamMillis=" + value(streamTtft)
                    + "\nclaudeTtftMillis=" + value(ttft) + "\nclaudeTurns=" + value(turns) + "\n";
        }

        private static String value(Long millis) { return millis == null ? "unavailable" : millis.toString(); }
    }

    private Long firstEvent;
    private Long firstThinking;
    private Long lastThinking;
    private Long firstText;
    private Long lastText;
    private Long resultReceived;
    private Map<?, ?> result = Map.of();
    private boolean malformed;

    @Override public synchronized void line(String text, long receivedMillis) {
        if (text.isBlank()) return;
        try {
            var envelope = JsonReader.object(text);
            if ("stream_event".equals(envelope.get("type")) && envelope.get("event") instanceof Map<?, ?> event) {
                observe(event, receivedMillis);
            } else if ("result".equals(envelope.get("type"))) {
                if (resultReceived != null) malformed = true;
                result = envelope;
                resultReceived = receivedMillis;
            }
        } catch (IOException invalid) {
            malformed = true;
        }
    }

    private void observe(Map<?, ?> event, long received) {
        if (firstEvent == null && !"ping".equals(event.get("type"))) firstEvent = received;
        if (!"content_block_delta".equals(event.get("type")) || !(event.get("delta") instanceof Map<?, ?> delta)) return;
        if ("thinking_delta".equals(delta.get("type"))) {
            if (firstThinking == null) firstThinking = received;
            lastThinking = received;
        } else if ("text_delta".equals(delta.get("type"))) {
            if (firstText == null) firstText = received;
            lastText = received;
        }
    }

    synchronized Timing timing() {
        return new Timing(firstEvent, firstThinking, lastThinking, firstText, lastText, resultReceived,
                number(result.get("duration_ms")), number(result.get("duration_api_ms")), number(result.get("ttft_stream_ms")),
                number(result.get("ttft_ms")), number(result.get("num_turns")));
    }

    synchronized boolean failed() { return Boolean.TRUE.equals(result.get("is_error")); }

    synchronized String errors() {
        if (!failed()) return "";
        String message = result.get("result") instanceof String text ? text : "";
        if (result.get("errors") instanceof List<?> errors)
            message += "\n" + errors.stream().filter(Objects::nonNull).filter(String.class::isInstance)
                    .map(String.class::cast).collect(Collectors.joining("\n"));
        return message;
    }

    synchronized String response() throws AgentCallException {
        if (malformed || !"success".equals(result.get("subtype")) || !Boolean.FALSE.equals(result.get("is_error"))
                || !(result.get("result") instanceof String answer)) {
            throw new AgentCallException(AgentCallException.Kind.STRUCTURED_OUTPUT,
                    "Claude Code stream did not contain one valid successful terminal result");
        }
        return answer;
    }

    private static Long number(Object value) {
        if (!(value instanceof BigDecimal number) || number.signum() < 0) return null;
        try { return number.longValueExact(); }
        catch (ArithmeticException invalid) { return null; }
    }
}
