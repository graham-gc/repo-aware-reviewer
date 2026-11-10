package reviewagent;

import java.io.PrintStream;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class CommandProgress implements AutoCloseable {
    private final String prefix;
    private final PrintStream output;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("command-progress").factory());
    private ScheduledFuture<?> heartbeat;
    private long stageStartedAt;

    CommandProgress(String command, PrintStream output) {
        this.prefix = "[" + command + "] ";
        this.output = output;
    }

    synchronized void start(String message) {
        stopHeartbeat();
        output.println(prefix + message);
        long startedAt = System.nanoTime();
        stageStartedAt = startedAt;
        heartbeat = scheduler.scheduleWithFixedDelay(() -> reportWaiting(message, startedAt),
                10, 10, TimeUnit.SECONDS);
    }

    void step(int completed, int total, String message) {
        start(bar(completed, total) + message);
    }

    synchronized void finish(int total, String message) {
        stopHeartbeat();
        output.println(prefix + bar(total, total) + message);
    }

    private synchronized void reportWaiting(String message, long startedAt) {
        if (heartbeat != null && stageStartedAt == startedAt) {
            long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedAt);
            output.println(prefix + message + " (still working, " + seconds + "s elapsed)");
        }
    }

    private static String bar(int completed, int total) {
        int filled = (int) (10L * completed / total);
        return "[" + "#".repeat(filled) + "-".repeat(10 - filled) + "] " + completed + "/" + total + " ";
    }

    private void stopHeartbeat() {
        if (heartbeat != null) {
            heartbeat.cancel(false);
            heartbeat = null;
        }
    }

    @Override
    public synchronized void close() {
        stopHeartbeat();
        scheduler.shutdownNow();
    }
}
