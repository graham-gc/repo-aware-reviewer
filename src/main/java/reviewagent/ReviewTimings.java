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
    private record Call(Path prefix, Interval interval, int bytes, String result) { }
    private record Stage(Path directory, Interval interval) { }
    private record Prepared(Path task, int fullBytes, int selectedBytes, int sources, int totalSources) { }
    private final long started = System.nanoTime();
    private final List<Call> calls = new ArrayList<>();
    private final List<Stage> stages = new ArrayList<>();
    private final List<Prepared> prepared = new ArrayList<>();
    private int reused;
    private int failed;
    private int unchanged;

    synchronized void call(Path prefix, long start, long end, int bytes, String result) {
        calls.add(new Call(prefix, new Interval(start, end), bytes, result));
    }
    synchronized void stage(Path directory, long start, long end) { stages.add(new Stage(directory, new Interval(start, end))); }
    synchronized void prepared(Path task, int fullBytes, int selectedBytes, int sources, int totalSources) {
        prepared.add(new Prepared(task, fullBytes, selectedBytes, sources, totalSources));
    }
    synchronized void task(boolean cached, boolean valid) { if (cached) reused++; if (!valid) failed++; }
    synchronized void unchanged() { unchanged++; }

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
        out.append(language.text("\n## Candidate prompt preparation\n\nThese compare prepared task prompts, including cached or skipped tasks; they are not measured latency savings. Corrections and retries are counted above as actual calls.\n\n",
                "\n## 候选提示词准备\n\n下表比较准备的任务提示词，包括缓存或跳过的任务，不代表实测提速。纠错和重试按上表的实际调用统计。\n\n"));
        out.append(language.text("| Task | Full bytes | Selected bytes | Provided sources |\n", "| 任务 | 全量提示词字节 | 实际选择字节 | 提供证据块数 |\n"))
                .append("| --- | ---: | ---: | ---: |\n");
        for (Prepared item : prepared) out.append('|').append(root.relativize(item.task())).append('|').append(item.fullBytes()).append('|')
                .append(item.selectedBytes()).append('|').append(item.sources()).append('/').append(item.totalSources()).append("|\n");
        out.append(language.text("\n## Attempt outcomes\n\n", "\n## 调用结果\n\n"));
        for (Call call : calls) out.append("- ").append(call.prefix() == null ? "unlogged" : root.relativize(call.prefix()))
                .append(": ").append(call.result()).append("; ").append(seconds(call.interval().end() - call.interval().start())).append("s\n");
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
