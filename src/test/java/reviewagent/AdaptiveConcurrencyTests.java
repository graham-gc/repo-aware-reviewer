package reviewagent;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

final class AdaptiveConcurrencyTests {
    private static final AdaptiveConcurrency.Workload PROFILE = new AdaptiveConcurrency.Workload("fixture", false);
    private static final long WAIT = Duration.ofSeconds(60).toNanos();

    static void run() throws Exception {
        var directory = Files.createTempDirectory("review-auto-concurrency-");
        try {
            options();
            throughput(directory);
            mixedInputSizes(directory);
            history(directory);
            readyVerification(directory);
            rateLimit(directory);
        } finally {
            try (var paths = Files.walk(directory)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void options() {
        var defaults = CliOptions.parse(new String[]{"review", "--repo", "."});
        require(!defaults.adaptiveParallelism() && defaults.reviewParallelism() == 3
                && defaults.verificationParallelismLimit() == null, "discovery stays fixed and deep verification uses ready work");
        var selected = CliOptions.parse(new String[]{"review", "--repo", ".", "--review-parallelism", "auto",
                "--review-max-parallelism", "4"});
        require(selected.adaptiveParallelism() && selected.reviewParallelism() == 4
                && selected.verificationParallelismLimit() == 4, "auto uses the selected hard cap");
        var fixed = CliOptions.parse(new String[]{"review", "--repo", ".", "--review-parallelism", "1"});
        require(!fixed.adaptiveParallelism() && fixed.verificationParallelismLimit() == 1,
                "an explicit fixed value applies to verification as well");
        for (String[] arguments : List.of(new String[]{"review", "--repo", ".", "--review-max-parallelism", "4"},
                new String[]{"review", "--repo", ".", "--review-parallelism", "auto", "--review-max-parallelism", "0"})) {
            try {
                CliOptions.parse(arguments);
                throw new AssertionError("invalid hard cap accepted");
            } catch (IllegalArgumentException expected) { }
        }
    }

    private static void throughput(Path directory) throws Exception {
        for (boolean improves : List.of(true, false)) {
            var clock = new AtomicLong();
            var timings = new ReviewTimings();
            var adaptive = new AdaptiveConcurrency(3, null, timings, clock::get);
            var active = new ArrayDeque<AdaptiveConcurrency.Admission>();
            active.add(adaptive.acquire(PROFILE, WAIT));
            active.add(adaptive.acquire(PROFILE, WAIT));
            for (int i = 0; i < 6; i++) cycle(adaptive, active, clock, 100);

            require(timings.markdown(directory, OutputLanguage.EN).contains("| 3 | THROUGHPUT_PROBE"),
                    "saturated validated work tests one additional slot");
            active.add(adaptive.acquire(PROFILE, WAIT));
            // Two pre-probe admissions drain. A failed trial stops after the first full window.
            for (int i = 0; i < (improves ? 14 : 8); i++) cycle(adaptive, active, clock, improves ? 60 : 125);

            String result = timings.markdown(directory, OutputLanguage.EN);
            require(result.contains(improves ? "| 3 | THROUGHPUT_GAIN" : "| 2 | NO_THROUGHPUT_GAIN"),
                    "a trial is kept or reverted only after comparable completion windows");
            for (var admission : active) adaptive.release(admission);
        }
    }

    private static void cycle(AdaptiveConcurrency adaptive, ArrayDeque<AdaptiveConcurrency.Admission> active,
                              AtomicLong clock, int elapsedMillis) throws Exception {
        cycle(adaptive, active, clock, elapsedMillis, PROFILE);
    }

    private static void mixedInputSizes(Path directory) throws Exception {
        var shortInput = new AdaptiveConcurrency.Workload("short", "same-model-and-stage", false);
        var longInput = new AdaptiveConcurrency.Workload("long", "same-model-and-stage", false);
        var unseen = new AdaptiveConcurrency.Workload("unseen", "same-model-and-stage", false);
        for (boolean comparable : List.of(true, false)) {
            var clock = new AtomicLong();
            var timings = new ReviewTimings();
            var adaptive = new AdaptiveConcurrency(3, null, timings, clock::get);
            var active = new ArrayDeque<AdaptiveConcurrency.Admission>();
            active.add(adaptive.acquire(shortInput, WAIT));
            active.add(adaptive.acquire(longInput, WAIT));
            for (int i = 0; i < 6; i++)
                cycle(adaptive, active, clock, i % 2 == 0 ? 80 : 120, i % 2 == 0 ? shortInput : longInput);

            require(timings.markdown(directory, OutputLanguage.EN).contains("| 3 | THROUGHPUT_PROBE"),
                    "alternating input sizes from the same stage can establish a baseline and probe more capacity");
            active.add(adaptive.acquire(comparable ? longInput : unseen, WAIT));
            for (int i = 0; i < (comparable ? 14 : 8); i++)
                cycle(adaptive, active, clock, 60, comparable ? (i % 2 == 0 ? shortInput : longInput) : unseen);

            String result = timings.markdown(directory, OutputLanguage.EN);
            require(result.contains(comparable ? "| 3 | THROUGHPUT_GAIN" : "| 2 | NO_THROUGHPUT_GAIN")
                    && !result.contains("PROBE_WORKLOAD_CHANGED"),
                    "measured gains may retain a mixed-size trial, while unseen sizes cannot supply comparable work");
            for (var admission : active) adaptive.release(admission);
        }
    }

    private static void cycle(AdaptiveConcurrency adaptive, ArrayDeque<AdaptiveConcurrency.Admission> active,
                              AtomicLong clock, int elapsedMillis, AdaptiveConcurrency.Workload workload) throws Exception {
        var next = new CompletableFuture<AdaptiveConcurrency.Admission>();
        Thread waiting = Thread.startVirtualThread(() -> {
            try { next.complete(adaptive.acquire(workload, WAIT)); }
            catch (Exception failure) { next.completeExceptionally(failure); }
        });
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (waiting.getState() != Thread.State.TIMED_WAITING && !next.isDone() && System.nanoTime() < deadline)
                Thread.sleep(1);
            require(waiting.getState() == Thread.State.TIMED_WAITING, "a controlled request should queue at saturation");
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(elapsedMillis));
            var completed = active.remove();
            adaptive.release(completed);
            active.add(next.get(3, TimeUnit.SECONDS));
            adaptive.finished(completed, true);
        } finally {
            waiting.interrupt();
            waiting.join();
        }
    }

    private static void history(Path directory) throws Exception {
        var profile = new AdaptiveConcurrency.Workload("a".repeat(64), true);
        Path previous = Files.createDirectory(directory.resolve("previous"));
        Path current = Files.createDirectory(directory.resolve("current"));
        Path sample = previous.resolve(".review-parallelism");
        Files.writeString(sample, profile.key() + " 5\n");
        var recent = new ReviewTimings();
        var warm = new AdaptiveConcurrency(4, current.resolve(".review-parallelism"), recent);
        var request = warm.acquire(profile, WAIT);
        warm.throttled(request, Duration.ZERO);
        warm.release(request);
        require(recent.markdown(directory, OutputLanguage.EN).contains("| 4 | RECENT_STABLE_LIMIT"), "recent history obeys the cap");

        Files.setLastModifiedTime(sample, FileTime.from(Instant.now().minus(Duration.ofDays(2))));
        var stale = new ReviewTimings();
        var cold = new AdaptiveConcurrency(4, current.resolve(".review-parallelism"), stale);
        cold.release(cold.acquire(profile, WAIT));
        require(!stale.markdown(directory, OutputLanguage.EN).contains("RECENT_STABLE_LIMIT"), "stale history is ignored");
        warm.saveHistory();
        require(Files.readString(current.resolve(".review-parallelism")).equals(profile.key() + " 2\n"),
                "the reduced capacity is persisted inside the run without creating a sibling artifact directory");
    }

    private static void readyVerification(Path directory) throws Exception {
        var timings = new ReviewTimings();
        var cold = AdaptiveConcurrency.forReadyWork(6, null, timings, List.of(PROFILE));
        cold.readyTasks(5);
        var admission = cold.acquire(PROFILE, WAIT);
        cold.throttled(admission, Duration.ZERO);
        cold.release(admission);
        cold.readyTasks(6);
        String report = timings.markdown(directory, OutputLanguage.EN);
        require(report.contains("| 5 | VERIFICATION_READY_TASKS") && report.contains("| 1 | RATE_LIMIT")
                && !report.contains("| 6 | VERIFICATION_READY_TASKS"), "new ready tasks can fill capacity until rate feedback requires recovery");

        var profile = new AdaptiveConcurrency.Workload("b".repeat(64), true);
        Files.writeString(directory.resolve("previous/.verification-parallelism"), profile.key() + " 3\n");
        var recent = new ReviewTimings();
        var warm = AdaptiveConcurrency.forReadyWork(4, directory.resolve("current/.verification-parallelism"), recent,
                List.of(PROFILE, profile, PROFILE, PROFILE));
        warm.readyTasks(5);
        require(recent.markdown(directory, OutputLanguage.EN).contains("| 3 | VERIFICATION_RECENT_STABLE_LIMIT"),
                "verification uses recent comparable history before admitting its first work");
    }

    private static void rateLimit(Path directory) throws Exception {
        String script = """
                input=$(cat)
                if [ "$input" = limited ] && [ ! -f first ]; then
                  touch first
                  printf 'HTTP 429\\nRetry-After: 3\\n' >&2
                  exit 1
                fi
                sleep 0.10
                printf valid
                """;
        var agent = new AgentRunner(new ProcessExecutor(), "/bin/sh", List.of("-c", script),
                directory, Duration.ofSeconds(10), AgentPolicy.defaults(Duration.ofSeconds(10)), 3, true, null);
        try (var progress = new CommandProgress("auto", new PrintStream(OutputStream.nullOutputStream()))) {
            var tasks = List.of(new ReviewTasks.Task("first", "limited", reply -> {}),
                    new ReviewTasks.Task("second", "ordinary", reply -> {}),
                    new ReviewTasks.Task("third", "ordinary", reply -> {}));
            var replies = ReviewTasks.run(tasks, agent, directory, 3, progress, 1, 1, "Reviewing", false);
            require(replies.values().stream().allMatch("valid"::equals), "rate feedback preserves all planned decisions");
        }
        String report = agent.timings().markdown(directory, OutputLanguage.EN);
        require(report.contains("| 1 | RATE_LIMIT") && report.contains("Actual launched attempts: 4"),
                "an observed rate limit lowers capacity while preserving the existing transport retry budget");
        Instant failure = attemptStarted(directory, "first", 1);
        Instant retry = attemptStarted(directory, "first", 2);
        require(Duration.between(failure, retry).compareTo(Duration.ofSeconds(3)) >= 0,
                "the failed request's retry respects the exposed shared cooldown");
        try (var paths = Files.walk(directory)) {
            for (Path log : paths.filter(path -> path.getFileName().toString().equals("call-execution.log")).toList()) {
                var lines = Files.readAllLines(log);
                var started = Instant.parse(field(lines, "startedAt"));
                int concurrent = Integer.parseInt(field(lines, "concurrentCallsAtStart"));
                require(concurrent <= (started.isAfter(failure.plusSeconds(1)) ? 1 : 2),
                        "actual process launches stay within the active capacity");
            }
        }
    }

    private static Instant attemptStarted(Path directory, String task, int number) throws Exception {
        Path log = directory.resolve(task + "-attempts").resolve("attempt-%04d".formatted(number)).resolve("call-execution.log");
        return Instant.parse(field(Files.readAllLines(log), "startedAt"));
    }

    private static String field(List<String> lines, String key) {
        return lines.stream().filter(line -> line.startsWith(key + "=")).findFirst().orElseThrow().substring(key.length() + 1);
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
