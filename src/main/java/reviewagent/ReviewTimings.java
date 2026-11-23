package reviewagent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** Current execution only: distinguish overlapping process durations from stage wall time. */
final class ReviewTimings {
    record Interval(long start, long end) { }
    private record Call(Path prefix, Path attempt, Interval interval, int bytes, String result,
                        ProcessExecutor.Observations observed) { }
    private record Validation(Path task, long elapsed, boolean cached, boolean accepted) { }
    private record Stage(Path directory, Interval interval) { }
    private record Prepared(Path task, int fullBytes, int selectedBytes, int sources, int totalSources) { }
    private record Parallelism(long at, int limit, String reason) { }
    private final long started = System.nanoTime();
    private final List<Call> calls = new ArrayList<>();
    private final List<Validation> validations = new ArrayList<>();
    private final List<Stage> stages = new ArrayList<>();
    private final List<Prepared> prepared = new ArrayList<>();
    private final List<Parallelism> parallelism = new ArrayList<>();
    private int reused;
    private int failed;
    private int unchanged;

    synchronized void call(Path prefix, Path attempt, long start, long end, int bytes, String result,
                           ProcessExecutor.Observations observed) {
        calls.add(new Call(prefix, attempt, new Interval(start, end), bytes, result, observed));
    }
    synchronized void validation(Path task, long elapsed, boolean cached, boolean accepted) {
        validations.add(new Validation(task, elapsed, cached, accepted));
    }
    synchronized void stage(Path directory, long start, long end) { stages.add(new Stage(directory, new Interval(start, end))); }
    synchronized void prepared(Path task, int fullBytes, int selectedBytes, int sources, int totalSources) {
        prepared.add(new Prepared(task, fullBytes, selectedBytes, sources, totalSources));
    }
    synchronized void task(boolean cached, boolean valid) { if (cached) reused++; if (!valid) failed++; }
    synchronized void unchanged() { unchanged++; }
    synchronized void parallelism(int limit, String reason) {
        parallelism.add(new Parallelism(System.nanoTime(), limit, reason));
    }

    synchronized void write(Path root, OutputLanguage language) throws IOException {
        AtomicFiles.write(root.resolve("timings.md"), markdown(root, language));
    }

    synchronized String markdown(Path root, OutputLanguage language) {
        StringBuilder out = new StringBuilder(language.text("# Review timing\n\n", "# 审查耗时\n\n"));
        out.append(language.text("Current execution only, starting before discovery. Preparation/Git collection and earlier resumed attempts are excluded.\n\n",
                "仅统计本次执行，从初审前开始计时。不包含前置 Git 采集和续跑前的历史调用。\n\n"));
        out.append("- ").append(language.text("Model workflow wall seconds", "模型流程墙钟秒数")).append(": ")
                .append(seconds(System.nanoTime() - started)).append('\n');
        out.append("- ").append(language.text("Actual launched attempts", "实际启动调用数")).append(": ").append(calls.size()).append('\n');
        out.append("- ").append(language.text("Valid cached tasks reused", "复用有效缓存任务数")).append(": ").append(reused).append('\n');
        out.append("- ").append(language.text("Failed tasks", "失败任务数")).append(": ").append(failed).append('\n');
        out.append("- ").append(language.text("Unchanged follow-up tasks skipped", "未新增证据而跳过的追加任务数")).append(": ").append(unchanged).append("\n\n");
        out.append(language.text("Process durations overlap under concurrency and must not be added to infer user waiting time. Stage wall time includes queueing, backoff and validation. Input bytes are not token counts.\n\n",
                "并发调用的耗时存在重叠，不能相加当作用户等待时间。阶段墙钟时间包含排队、退避和校验。输入字节数不是 token 数。\n\n"));
        out.append(language.text("| Stage | Attempts | Correction attempts | Stage wall s | Sum process s | Longest process s | Input bytes |\n",
                "| 阶段 | 调用次数 | 其中纠错调用 | 阶段墙钟秒数 | 调用累计秒数 | 最长调用秒数 | 输入字节数 |\n"));
        out.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        var groups = new LinkedHashMap<String, List<Call>>();
        for (Stage stage : stages) groups.computeIfAbsent(phase(stage.directory(), root), unused -> new ArrayList<>());
        for (Call call : calls) groups.computeIfAbsent(phase(call.prefix() == null ? null : call.prefix().getParent(), root), unused -> new ArrayList<>()).add(call);
        for (var group : groups.entrySet()) {
            List<Call> entries = group.getValue();
            List<Interval> intervals = stages.stream().filter(s -> phase(s.directory(), root).equals(group.getKey())).map(Stage::interval).toList();
            out.append('|').append(group.getKey()).append('|').append(entries.size()).append('|')
                    .append(entries.stream().filter(c -> c.prefix() != null && c.prefix().getFileName().toString().endsWith("-repair")).count()).append('|')
                    .append(seconds(unionNanos(intervals))).append('|')
                    .append(seconds(entries.stream().mapToLong(c -> c.interval().end() - c.interval().start()).sum())).append('|')
                    .append(seconds(entries.stream().mapToLong(c -> c.interval().end() - c.interval().start()).max().orElse(0))).append('|')
                    .append(entries.stream().mapToLong(Call::bytes).sum()).append("|\n");
        }
        if (!parallelism.isEmpty()) {
            out.append(language.text("\n## Adaptive concurrency\n\n", "\n## 自动并发\n\n"));
            for (var event : parallelism) {
                out.append("- ").append(seconds(event.at() - started)).append("s | ")
                        .append(event.limit()).append(" | ").append(event.reason()).append('\n');
            }
        }
        out.append(language.text("\n## Candidate prompt preparation\n\nThese compare prepared task prompts, including cached or skipped tasks; they are not measured latency savings. Corrections and retries are counted above as actual calls.\n\n",
                "\n## 候选提示词准备\n\n下表比较准备的任务提示词，包括缓存或跳过的任务，不代表实测提速。纠错和重试按上表的实际调用统计。\n\n"));
        out.append(language.text("| Task | Full bytes | Selected bytes | Provided sources |\n", "| 任务 | 全量提示词字节 | 实际选择字节 | 提供证据块数 |\n"))
                .append("| --- | ---: | ---: | ---: |\n");
        for (Prepared item : prepared) out.append('|').append(root.relativize(item.task())).append('|').append(item.fullBytes()).append('|')
                .append(item.selectedBytes()).append('|').append(item.sources()).append('/').append(item.totalSources()).append("|\n");
        out.append(language.text("\n## Attempt outcomes\n\n", "\n## 调用结果\n\n"));
        out.append(language.text("Pipe and exit observations are local offsets in milliseconds from command launch. A dash marks an unobserved event. Buffered output can arrive well after upstream generation starts.\n\n",
                "管道和退出事件以本地命令开始启动为起点，单位为毫秒。横线表示未观测到。缓冲输出可能在上游开始生成很久后才出现。\n\n"));
        out.append(language.text("| Attempt | Result | Total s | Started ms | Stdin closed ms | First stdout ms | First stderr ms | Exit observed ms |\n",
                "| 调用记录 | 结果 | 总秒数 | 启动成功毫秒 | 输入关闭毫秒 | 首次标准输出毫秒 | 首次标准错误毫秒 | 观测退出毫秒 |\n"));
        out.append("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        for (Call call : calls) {
            var observed = call.observed();
            out.append('|').append(call.attempt() == null ? "unlogged" : root.relativize(call.attempt())).append('|')
                    .append(call.result()).append('|').append(seconds(call.interval().end() - call.interval().start())).append('|')
                    .append(observedMillis(observed.processStarted())).append('|').append(observedMillis(observed.stdinClosed())).append('|')
                    .append(observedMillis(observed.firstStdout())).append('|').append(observedMillis(observed.firstStderr())).append('|')
                    .append(observedMillis(observed.exitObserved())).append("|\n");
        }
        out.append(language.text("\n## Local response validation\n\nValidation time covers local response checks. Model correction calls appear in the attempt table.\n\n",
                "\n## 本地响应校验\n\n校验耗时仅统计本地响应检查。模型纠错调用见上表。\n\n"));
        out.append(language.text("| Task | Response source | Result | Validation s |\n", "| 任务 | 响应来源 | 结果 | 校验秒数 |\n"));
        out.append("| --- | --- | --- | ---: |\n");
        for (Validation validation : validations) {
            out.append('|').append(root.relativize(validation.task())).append('|')
                    .append(validation.cached() ? language.text("cached", "缓存") : language.text("current", "本次调用")).append('|')
                    .append(validation.accepted() ? language.text("accepted", "通过") : language.text("rejected", "未通过")).append('|')
                    .append(seconds(validation.elapsed())).append("|\n");
        }
        return out.toString();
    }

    static long unionNanos(List<Interval> intervals) {
        long sum = 0, end = Long.MIN_VALUE;
        for (Interval interval : intervals.stream().sorted(Comparator.comparingLong(Interval::start)).toList()) {
            if (interval.end() <= end) continue;
            sum += interval.end() - Math.max(end, interval.start());
            end = interval.end();
        }
        return sum;
    }
    private static String observedMillis(Long millis) { return millis == null ? "—" : millis.toString(); }
    private static String seconds(long nanos) { return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000_000.0); }
    private static String phase(Path directory, Path root) {
        if (directory == null) return "unlogged";
        Path relative = root.relativize(directory);
        if (relative.toString().isEmpty()) return "report";
        if (relative.getNameCount() > 1 && relative.getName(0).toString().equals("verification")
                && relative.getName(1).toString().equals("requirements")) return "verification/requirements";
        return relative.getName(0).toString();
    }
}
