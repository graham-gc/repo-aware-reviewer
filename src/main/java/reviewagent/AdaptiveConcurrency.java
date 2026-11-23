package reviewagent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/** Run-wide request admission. Only validated, comparable work is used to tune capacity. */
final class AdaptiveConcurrency {
    private static final Duration HISTORY_AGE = Duration.ofHours(24);
    private static final int HISTORY_RUNS = 32;
    private static final Duration DEFAULT_COOLDOWN = Duration.ofSeconds(2);
    private static final double MIN_GAIN = 1.10;

    record Workload(String key, String route, boolean cacheable) {
        Workload(String key, boolean cacheable) { this(key, key, cacheable); }
    }
    record Admission(Workload workload, long epoch, long started) { }

    private record Completed(int count, long nanos) {
        Completed plus(Completed other) { return new Completed(count + other.count, nanos + other.nanos); }
        double meanNanos() { return (double) nanos / count; }
    }

    private static final class Window {
        final String route;
        final long started;
        final Map<Workload, Completed> acceptedBySize = new HashMap<>();
        long occupied;
        int finished;
        int accepted;
        boolean backlogged;
        boolean overlappingRoute;

        Window(String route, long started) {
            this.route = route;
            this.started = started;
        }

        Map<Workload, Double> typicalCosts() {
            var costs = new HashMap<Workload, Double>();
            acceptedBySize.forEach((workload, completed) -> costs.put(workload, completed.meanNanos()));
            return costs;
        }

        double rate(long elapsed, Map<Workload, Double> costs) {
            // Unseen sizes contribute no estimated work. A trial can only win on measured inputs.
            return acceptedBySize.entrySet().stream()
                    .mapToDouble(entry -> entry.getValue().count() * costs.getOrDefault(entry.getKey(), 0.0)).sum() / elapsed;
        }
    }

    private record Observation(int limit, double rate, Map<Workload, Double> costs, boolean probeDeclined, int weakWindows) { }
    private record Probe(String route, Observation baseline, boolean measuredGain) { }

    private final int maximum;
    private final Path history;
    private final ReviewTimings timings;
    private final LongSupplier clock;
    private final boolean seedOnAdmission;
    private final int readyCeiling;
    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition changed = lock.newCondition();
    private final Map<String, Observation> observations = new HashMap<>();
    private final Map<Workload, Integer> stableLimits = new HashMap<>();
    private final Set<Workload> seeded = new HashSet<>();

    private int limit;
    private int running;
    private int draining;
    private int waiting;
    private long epoch;
    private long lastEvent;
    private long cooldownUntil;
    private Long lastThrottle;
    private boolean recovering;
    private AgentCallException fatal;
    private Window window;
    private Probe probe;
    private Map<String, Integer> recentLimits;

    AdaptiveConcurrency(int maximum, Path history, ReviewTimings timings) {
        this(maximum, history, timings, System::nanoTime);
    }

    AdaptiveConcurrency(int maximum, Path history, ReviewTimings timings, LongSupplier clock) {
        this(maximum, history, timings, clock, List.of());
    }

    static AdaptiveConcurrency forReadyWork(int maximum, Path history, ReviewTimings timings, List<Workload> ready) {
        return new AdaptiveConcurrency(maximum, history, timings, System::nanoTime, ready);
    }

    private AdaptiveConcurrency(int maximum, Path history, ReviewTimings timings, LongSupplier clock, List<Workload> ready) {
        this.maximum = maximum;
        this.history = history;
        this.timings = timings;
        this.clock = clock;
        this.seedOnAdmission = ready.isEmpty();
        this.readyCeiling = ready.stream().mapToInt(this::historicalLimit).filter(known -> known > 0)
                .min().orElse(maximum);
        int startup = Math.min(maximum, seedOnAdmission ? 2 : ready.size());
        this.limit = Math.min(startup, readyCeiling);
        this.lastEvent = this.cooldownUntil = clock.getAsLong();
        String reason = seedOnAdmission ? "COLD_START" : limit < startup ? "VERIFICATION_RECENT_STABLE_LIMIT" : "VERIFICATION_READY_TASKS";
        timings.parallelism(limit, reason + " (maximum " + maximum + ")");
    }

    void readyTasks(int count) {
        lock.lock();
        try {
            int next = Math.min(readyCeiling, count);
            if (next > limit && lastThrottle == null && observations.isEmpty() && fatal == null)
                change(next, clock.getAsLong(), "VERIFICATION_READY_TASKS");
        } finally {
            lock.unlock();
        }
    }

    Admission acquire(Workload workload, long allowedNanos) throws AgentCallException, InterruptedException {
        long deadline = clock.getAsLong() + allowedNanos;
        lock.lockInterruptibly();
        waiting++;
        try {
            while (true) {
                if (fatal != null) throw fatal;
                long now = clock.getAsLong();
                long remaining = deadline - now;
                if (remaining <= 0) {
                    throw new AgentCallException(AgentCallException.Kind.BUDGET_EXHAUSTED,
                            "Agent task budget expired while waiting for an automatic concurrency slot");
                }
                long cooling = cooldownUntil - now;
                if (cooling <= 0 && running < limit) {
                    advance(now);
                    if (probe != null && !probe.route().equals(workload.route())) {
                        int previous = probe.baseline().limit();
                        probe = null;
                        change(previous, now, "PROBE_WORKLOAD_CHANGED");
                        if (running >= limit) continue;
                    }
                    if (seedOnAdmission && running == 0 && lastThrottle == null && probe == null && seeded.add(workload)) {
                        int known = historicalLimit(workload);
                        if (known > 0 && known != limit) change(known, now, "RECENT_STABLE_LIMIT");
                    }
                    if (window == null || running == 0 && (!window.route.equals(workload.route()) || window.overlappingRoute)) {
                        window = new Window(workload.route(), now);
                    } else if (!window.route.equals(workload.route())) {
                        window.overlappingRoute = true;
                    }
                    running++;
                    window.backlogged |= waiting > 1 && running == limit;
                    return new Admission(workload, epoch, now);
                }
                if (window != null && running >= limit) window.backlogged = true;
                changed.awaitNanos(cooling > 0 ? Math.min(remaining, cooling) : remaining);
            }
        } finally {
            waiting--;
            lock.unlock();
        }
    }

    void release(Admission admission) {
        lock.lock();
        try {
            long now = clock.getAsLong();
            advance(now);
            running--;
            // Calls already in flight can finish after a limit change, but their occupancy belongs to the old limit.
            if (admission.epoch() != epoch && --draining == 0 && window != null)
                window = new Window(window.route, now);
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    void finished(Admission admission, boolean accepted) {
        lock.lock();
        try {
            if (fatal != null || draining != 0 || admission.epoch() != epoch || window == null
                    || !admission.workload().route().equals(window.route)) return;
            long now = clock.getAsLong();
            advance(now);
            window.finished++;
            if (accepted) {
                window.accepted++;
                window.acceptedBySize.merge(admission.workload(), new Completed(1, Math.max(1, now - admission.started())), Completed::plus);
            }
            if (window.finished < Math.max(6, 2L * limit)) return;

            // Separate models and stages. Within one route, normalize by the measured cost of each input size.
            Window sample = window;
            window = new Window(sample.route, now);
            long elapsed = now - sample.started;
            if (sample.overlappingRoute || elapsed <= 0 || sample.occupied < elapsed * 0.65) return;
            boolean healthy = sample.accepted >= sample.finished * 0.9;
            if (probe != null && probe.route().equals(sample.route)) {
                evaluateProbe(sample, now, elapsed, healthy);
            } else if (probe == null && healthy) {
                observeStable(sample, now, elapsed, sample.backlogged && running + waiting >= limit);
            }
        } finally {
            lock.unlock();
        }
    }

    void throttled(Admission admission, Duration retryAfter) {
        lock.lock();
        try {
            long now = clock.getAsLong();
            long pause = Math.max(DEFAULT_COOLDOWN.toNanos(), retryAfter.toNanos());
            if (cooldownUntil - now < pause) cooldownUntil = now + pause;
            if (lastThrottle == null || admission.started() >= lastThrottle) {
                lastThrottle = now;
                recovering = true;
                probe = null;
                observations.clear();
                stableLimits.clear();
                // A full verification queue can start above an unknown service limit. Test recovery
                // with one request before admitting concurrent retries from the same rejected wave.
                int reduced = seedOnAdmission ? Math.max(1, limit / 2) : 1;
                stableLimits.put(admission.workload(), reduced);
                change(reduced, now, "RATE_LIMIT");
            }
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    void abort(AgentCallException failure) {
        lock.lock();
        try {
            fatal = failure;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void advance(long now) {
        if (window != null && running >= limit) window.occupied += now - lastEvent;
        lastEvent = now;
    }

    private void observeStable(Window sample, long now, long elapsed, boolean demand) {
        boolean previouslyRecovering = recovering;
        recovering = false;
        Observation prior = observations.get(sample.route);
        double comparable = prior == null || !prior.costs().keySet().containsAll(sample.acceptedBySize.keySet())
                ? Double.NaN : sample.rate(elapsed, prior.costs());
        if (prior != null && prior.limit() == limit && comparable < prior.rate() * 0.8 && limit > 1) {
            if (prior.weakWindows() == 0) {
                observations.put(sample.route, new Observation(limit, prior.rate(), prior.costs(), prior.probeDeclined(), 1));
                return;
            }
            remember(sample, limit - 1);
            change(limit - 1, now, "THROUGHPUT_DROP");
            return;
        }
        boolean declined = prior != null && prior.limit() == limit && prior.probeDeclined()
                && Math.abs(comparable - prior.rate()) <= prior.rate() * 0.2;
        var costs = sample.typicalCosts();
        var baseline = new Observation(limit, sample.rate(elapsed, costs), costs, declined, 0);
        observations.put(sample.route, baseline);
        remember(sample, limit);
        if (!previouslyRecovering && demand && !declined && limit < maximum) {
            probe = new Probe(sample.route, baseline, false);
            change(limit + 1, now, "THROUGHPUT_PROBE");
        }
    }

    private void evaluateProbe(Window sample, long now, long elapsed, boolean healthy) {
        var baseline = probe.baseline();
        double rate = sample.rate(elapsed, baseline.costs());
        boolean improved = healthy && rate >= baseline.rate() * MIN_GAIN;
        if (improved && !probe.measuredGain()) {
            probe = new Probe(sample.route, baseline, true);
            return;
        }
        probe = null;
        if (improved) {
            for (Workload workload : sample.acceptedBySize.keySet()) {
                if (baseline.costs().containsKey(workload)) stableLimits.put(workload, limit);
            }
            observations.put(sample.route, new Observation(limit, rate, baseline.costs(), false, 0));
            timings.parallelism(limit, "THROUGHPUT_GAIN");
        } else {
            observations.put(sample.route, new Observation(baseline.limit(), baseline.rate(), baseline.costs(), true, 0));
            change(baseline.limit(), now, "NO_THROUGHPUT_GAIN");
        }
    }

    private void remember(Window sample, int capacity) {
        sample.acceptedBySize.keySet().forEach(workload -> stableLimits.put(workload, capacity));
    }

    private void change(int next, long now, String reason) {
        advance(now);
        limit = next;
        window = null;
        draining = running;
        epoch++;
        timings.parallelism(limit, reason);
        changed.signalAll();
    }

    private int historicalLimit(Workload workload) {
        if (!workload.cacheable() || history == null) return 0;
        if (recentLimits == null) recentLimits = loadHistory();
        return Math.min(maximum, recentLimits.getOrDefault(workload.key(), 0));
    }

    private Map<String, Integer> loadHistory() {
        var limits = new HashMap<String, Integer>();
        long oldest = System.currentTimeMillis() - HISTORY_AGE.toMillis();
        Path root = history.getParent().getParent();
        try (var runs = Files.list(root)) {
            for (Path file : runs.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .map(run -> run.resolve(history.getFileName()))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator.reverseOrder()).limit(HISTORY_RUNS).toList()) {
                readHistory(file, oldest, limits);
            }
        } catch (IOException | java.io.UncheckedIOException unavailable) {
            // History is optional and only influences the initial limit.
        }
        return limits;
    }

    private static void readHistory(Path file, long oldest, Map<String, Integer> limits) {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || Files.getLastModifiedTime(file).toMillis() < oldest || Files.size(file) > 65_536) return;
            for (String line : Files.readAllLines(file)) {
                String[] fields = line.split(" ", 2);
                if (fields.length != 2 || !fields[0].matches("[0-9a-f]{64}") || !fields[1].matches("[0-9]{1,10}")) continue;
                long value = Long.parseLong(fields[1]);
                if (value > 0 && value <= Integer.MAX_VALUE) limits.putIfAbsent(fields[0], (int) value);
            }
        } catch (IOException unavailable) {
            // A missing, unavailable or interrupted history file has no effect on review.
        }
    }

    void saveHistory() {
        if (history == null) return;
        Map<Workload, Integer> snapshot;
        lock.lock();
        try { snapshot = Map.copyOf(stableLimits); }
        finally { lock.unlock(); }
        String contents = snapshot.entrySet().stream().filter(entry -> entry.getKey().cacheable())
                .map(entry -> entry.getKey().key() + " " + entry.getValue() + "\n").sorted()
                .collect(java.util.stream.Collectors.joining());
        if (contents.isEmpty()) return;
        try {
            AtomicFiles.write(history, contents);
        } catch (IOException unavailable) {
            // Concurrency history is a hint. A review remains usable with a read-only cache.
        }
    }
}
