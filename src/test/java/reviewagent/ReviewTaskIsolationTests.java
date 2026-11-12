package reviewagent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Corrupt one candidate without cancelling its peers or presenting an incomplete run as approval. */
final class ReviewTaskIsolationTests {
    private static final String MALFORMED = "<verification><status>CONFIRMED</status><reason>Untrusted output.</reason>"
            + "<premises><premise><statement>Claim.</statement><basis>IMPLEMENTATION</basis><citations><citation>"
            + "<source>EV-invalid</source><quote>Source text.]<]minimax[>[quote></citation></citations></premise></premises>"
            + "<citations><citation><source>EV-other</source><quote>Later valid closing tag.</quote></citation></citations></verification>";
    private static final String REJECTED = "<verification><status>REJECTED</status><reason>An existing guard disproves the claim.</reason></verification>";

    static void run() throws Exception {
        batchIsolation();
        incompleteReportAndResume();
        completionUsesLatestVerdict();
    }

    private static void batchIsolation() throws Exception {
        Path dir = Files.createTempDirectory("review-output-isolation-");
        var candidate = finding("R1-1", "Malformed response");
        var validator = (ReviewTasks.Validator) reply -> ReviewOutput.verification(reply, candidate, List.of("runner.py"));
        List<ReviewTasks.Task> tasks = List.of(new ReviewTasks.Task("bad", "BAD_TASK", validator),
                new ReviewTasks.Task("good", "GOOD_TASK", validator));
        var agent = shell(dir, "prompt=$(cat); case \"$prompt\" in *BAD_TASK*) cat <<'BAD'\n" + MALFORMED
                + "\nBAD\n;; *) sleep 0.1; cat <<'GOOD'\n" + REJECTED + "\nGOOD\n;; esac");
        try (var progress = new CommandProgress("isolation", new java.io.PrintStream(java.io.OutputStream.nullOutputStream()))) {
            var results = ReviewTasks.runIsolated(tasks, agent, dir, 2, progress, 1, 2, "Check candidates", false);
            require(!results.get("bad").valid() && results.get("bad").response() == null && results.get("good").valid(),
                    "an invalid candidate stays untrusted while a concurrent peer completes");
            require(Files.readString(dir.resolve("bad-error.txt")).contains("bounded output correction")
                    && Files.readString(dir.resolve("bad-repair-response.md")).equals(MALFORMED + "\n"), "invalid output and error artifacts are preserved");
            var retry = shell(dir, "prompt=$(cat); case \"$prompt\" in *GOOD_TASK*) exit 87;; *) cat <<'FIXED'\n" + REJECTED + "\nFIXED\n;; esac");
            var resumed = ReviewTasks.runIsolated(tasks, retry, dir, 2, progress, 1, 2, "Resume candidates", true);
            require(resumed.values().stream().allMatch(ReviewTasks.Outcome::valid) && !Files.exists(dir.resolve("bad-error.txt")),
                    "resume reuses the valid peer, retries the invalid response and clears its error only after validation");
            fails(() -> ReviewTasks.run(List.of(tasks.getFirst()), agent, dir.resolve("strict"), 1, progress, 1, 2, "Strict phase", false));
            fails(() -> ReviewTasks.runIsolated(tasks, shell(dir, "exit 9"), dir.resolve("transport"), 1,
                    progress, 1, 2, "Transport failure", false));
        }
    }

    private static void incompleteReportAndResume() throws Exception {
        Path dir = Files.createTempDirectory("review-incomplete-report-");
        Path repo = Files.createDirectory(dir.resolve("repo"));
        git(repo, "init", "-q");
        Files.writeString(repo.resolve("runner.py"), "print('base')\n");
        git(repo, "add", "--all"); git(repo, "commit", "-qm", "base");
        Files.writeString(repo.resolve("runner.py"), "print('target')\n");
        git(repo, "add", "--all"); git(repo, "commit", "-qm", "target");
        String discoveries = "<findings>" + ReviewOutput.findingXml(finding("unused", "Invalid output candidate"))
                + ReviewOutput.findingXml(finding("unused", "Disproved candidate")) + "</findings>";
        String finalReport = "<finding-groups/>\n" + String.join("\n\n", ReviewReport.headings(OutputLanguage.ZH_CN));
        String script = """
                prompt=$(cat)
                case "$prompt" in
                  'You are the discovery stage'*)
                    case "$prompt" in
                      *'Correctness and regressions.'*) cat <<'DISCOVERED'
                DISCOVERIES
                DISCOVERED
                        ;;
                      *) printf '<findings/>' ;;
                    esac ;;
                  'You are the independent candidate verification'*)
                    case "$prompt" in
                      *'<finding id="R1-1">'*) cat <<'INVALID'
                INVALID_REPLY
                INVALID
                        ;;
                      *) cat <<'REJECTED'
                REJECTED_REPLY
                REJECTED
                        ;;
                    esac ;;
                  'You are the report consolidation'*) cat <<'REPORT'
                FINAL_REPORT
                REPORT
                    ;;
                  *) exit 88 ;;
                esac
                """.replace("DISCOVERIES", discoveries).replace("INVALID_REPLY", MALFORMED)
                .replace("REJECTED_REPLY", REJECTED).replace("FINAL_REPORT", finalReport);
        var args = new ArrayList<>(List.of("review", "--repo", repo.toString(), "--language", "zh-CN", "--output-dir", dir.resolve("runs").toString(),
                "--agent-command", "/bin/sh", "--agent-arg", "-c", "--agent-arg", script));
        Path run = new ReviewPipeline(new ProcessExecutor(), Clock.systemUTC()).run(CliOptions.parse(args.toArray(String[]::new)));
        String report = Files.readString(run.resolve("report.md"));
        require(report.startsWith("> **审查未完成：**") && report.contains("R1-1") && report.contains("未完成复核")
                && !report.contains("minimax"), "the reader sees an incomplete review, not malformed model conclusions");
        require(Files.readString(run.resolve("review-status.md")).contains("Status: INCOMPLETE")
                && Files.readString(run.resolve("verified-findings.md")).isEmpty(), "failed candidates cannot become published findings");
        require(Files.isRegularFile(run.resolve("verification/R1-1-error.txt"))
                && Files.readString(run.resolve("verification/R1-2-response.md")).contains("REJECTED"), "the valid peer and failure record both survive");

        String resumeScript = "prompt=$(cat); case \"$prompt\" in 'You are the independent candidate verification'*'"
                + "<finding id=\"R1-1\">'*) cat <<'FIXED'\n" + REJECTED + "\nFIXED\n;; "
                + "'You are the report consolidation'*) cat <<'REPORT'\n" + finalReport + "\nREPORT\n;; *) exit 89;; esac";
        args.set(args.size() - 1, resumeScript);
        args.addAll(List.of("--resume", run.toString()));
        new ReviewPipeline(new ProcessExecutor(), Clock.systemUTC()).run(CliOptions.parse(args.toArray(String[]::new)));
        require(Files.readString(run.resolve("review-status.md")).contains("Status: COMPLETE")
                && !Files.readString(run.resolve("report.md")).contains("审查未完成")
                && !Files.exists(run.resolve("verification/R1-1-error.txt")), "validated resume clears the incomplete state without rerunning valid candidates");
    }

    private static void completionUsesLatestVerdict() {
        var candidate = finding("R1-1", "Candidate");
        var invalid = new ReviewOutput.Decision(new ReviewOutput.Verification(candidate, ReviewOutput.Status.INSUFFICIENT_EVIDENCE,
                "verification/R1-1-error.txt", null, true), ReviewOutput.Relation.UNRESOLVED, "", "", List.of());
        var resolved = new ReviewOutput.Decision(new ReviewOutput.Verification(candidate, ReviewOutput.Status.REJECTED,
                "Disproved", null), ReviewOutput.Relation.PRE_EXISTING, "", "", List.of());
        require(ReviewQuality.completion(List.of(resolved), List.of(invalid), OutputLanguage.EN).notice().contains("Review incomplete"),
                "publication validation failure cannot inherit a complete preliminary status");
        require(ReviewQuality.completion(List.of(invalid), List.of(resolved), OutputLanguage.EN).complete(), "a later valid verdict supersedes an old failure");
    }

    private static ReviewFinding finding(String id, String title) {
        return new ReviewFinding(id, title, ReviewFinding.Severity.MEDIUM, "runner.py", 1, 1,
                "Candidate trigger", "Unverified impact", "Candidate allegation", "Changed operation");
    }
    private static AgentRunner shell(Path dir, String script) {
        return new AgentRunner(new ProcessExecutor(), "/bin/sh", List.of("-c", script), dir, Duration.ofSeconds(5));
    }
    private static void git(Path dir, String... args) throws Exception {
        var command = new ArrayList<>(List.of("git", "-c", "user.name=Isolation Test", "-c", "user.email=test@example.invalid"));
        command.addAll(List.of(args));
        var result = new ProcessExecutor().run(command, dir, null, Duration.ofSeconds(10));
        require(result.exitCode() == 0, "synthetic Git fixture: " + result.stderr());
    }
    private static void fails(Checked action) throws Exception {
        try { action.run(); throw new AssertionError("Expected strict/transport failure"); }
        catch (IOException expected) { }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
