package reviewagent;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class CommandProgress implements AutoCloseable {
    private final String prefix;
    private final PrintStream output;
    private final int terminalColumns;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("command-progress").factory());
    private ScheduledFuture<?> heartbeat;
    private long stageStartedAt;
    private boolean lineOpen;

    CommandProgress(String command, PrintStream output) {
        this.prefix = "[" + command + "] ";
        this.output = output;
        terminalColumns = stderrTerminalColumns();
    }

    void start(String message) {
        start("", message);
    }

    void step(int completed, int total, String message) {
        start(bar(completed, total), message);
    }

    private synchronized void start(String bar, String message) {
        stopHeartbeat();
        render(bar + message, false);
        long startedAt = System.nanoTime();
        stageStartedAt = startedAt;
        int interval = terminalColumns > 0 ? 1 : 10;
        heartbeat = scheduler.scheduleWithFixedDelay(() -> reportWaiting(bar, message, startedAt),
                interval, interval, TimeUnit.SECONDS);
    }

    synchronized void finish(int total, String message) {
        stopHeartbeat();
        render(bar(total, total) + message, true);
    }

    private synchronized void reportWaiting(String bar, String message, long startedAt) {
        if (heartbeat != null && stageStartedAt == startedAt) {
            long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedAt);
            String status = terminalColumns > 0 ? "(" + seconds + "s) " + message
                    : message + " (still working, " + seconds + "s elapsed)";
            render(bar + status, false);
        }
    }

    private void render(String message, boolean finished) {
        String line = prefix + message;
        if (terminalColumns == 0) {
            output.println(line);
            return;
        }
        line = line.replaceAll("\\p{Cntrl}", "?");
        output.print("\r\u001b[2K" + line.substring(0, Math.min(line.length(), terminalColumns - 1)));
        lineOpen = !finished;
        if (finished) {
            output.println();
        }
        output.flush();
    }

    private static int stderrTerminalColumns() {
        if ("dumb".equals(System.getenv("TERM"))) {
            return 0;
        }
        try {
            // The console API checks other streams. Test stderr even when stdout is redirected.
            Process probe = new ProcessBuilder("/bin/sh", "-c",
                    "[ -t 2 ] && { stty size <&2 2>/dev/null || printf '0 0\\n'; }")
                    .redirectError(ProcessBuilder.Redirect.INHERIT).start();
            try (var dimensions = probe.getInputStream()) {
                String size = new String(dimensions.readAllBytes(), StandardCharsets.US_ASCII).strip();
                if (probe.waitFor() != 0) {
                    return 0;
                }
                int columns = Integer.parseInt(size.substring(size.lastIndexOf(' ') + 1));
                return columns > 0 ? columns : 80;
            }
        } catch (IOException | NumberFormatException unavailable) {
            return 0;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return 0;
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
        if (lineOpen) {
            output.println();
        }
    }
}
