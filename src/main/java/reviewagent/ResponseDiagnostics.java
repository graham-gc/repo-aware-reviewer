package reviewagent;

import java.util.LinkedHashSet;
import java.util.List;

/** Per-validation diagnostics, scoped to a task thread and independent of response serialization. */
final class ResponseDiagnostics {
    private static final ThreadLocal<LinkedHashSet<String>> ACTIVE = new ThreadLocal<>();
    static Capture capture() { return new Capture(); }
    static void record(String note) { if (ACTIVE.get() != null) ACTIVE.get().add(note); }
    static final class Capture implements AutoCloseable {
        private final LinkedHashSet<String> previous = ACTIVE.get();
        private final LinkedHashSet<String> notes = new LinkedHashSet<>();
        Capture() { ACTIVE.set(notes); }
        List<String> notes() { return List.copyOf(notes); }
        @Override public void close() { if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous); }
    }
}
