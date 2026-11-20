package reviewagent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class AdaptiveReviewTests {
    private static final ProcessExecutor EXECUTOR = new ProcessExecutor();
    private static final String BEFORE = "def average(values):\n    if not values:\n        return 0\n    return sum(values) / len(values)\n";
    private static final String AFTER = "def average(values):\n    return sum(values) / len(values)\n";

    static void run() throws Exception {
        scopedProof();
        Path fixture = Files.createTempDirectory("adaptive-review-");
        try {
            Path repository = Files.createDirectory(fixture.resolve("repo"));
            git(repository, "init", "-q");
            git(repository, "config", "user.name", "Review fixture");
            git(repository, "config", "user.email", "fixture@example.invalid");
            Files.writeString(repository.resolve("calculator.py"), BEFORE);
            Files.writeString(repository.resolve("helper.py"), "from calculator import average\nresult = average([])\n");
            Files.writeString(repository.resolve("README.md"), "# Guide\nRun the checked example.\n");
            Files.writeString(repository.resolve("README.zh-CN.md"), "# 指南\n运行已核对的示例。\n");
            Files.writeString(repository.resolve("app.py"), "def has_permission(user):\n    return user.active\n");
            Files.writeString(repository.resolve("review_worker.py"), """
                    def execute_review(actor):
                        if not authorize(actor):
                            return "skipped"
                        return run_review(actor)
                    """);
            git(repository, "add", ".");
            git(repository, "commit", "-qm", "base");
            Files.writeString(repository.resolve("calculator.py"), AFTER);
            git(repository, "commit", "-qam", "remove guard");
            Path agent = fixture.resolve("agent.sh");
            Files.writeString(agent, """
                    root=$1
                    prompt=$(cat)
                    case "$prompt" in
                      'Review this small change'*) cat "$root/local.xml" ;;
                      'Review these Markdown edits'*)
                        if [ -f "$root/documentation-first.txt" ]; then cat "$root/documentation-first.txt"
                        else cat "$root/documentation.xml"; fi ;;
                      'DOCUMENTATION RESPONSE RECOVERY'*) cat "$root/documentation.xml" ;;
                      'ADAPTIVE RESPONSE RECOVERY'*)
                        case "$prompt" in
                          *'before the effect'*) printf '<adaptive-review><route>LOCAL</route><summary>Checked the recovered scope.</summary><decisions/></adaptive-review>' ;;
                          *) cat "$root/focused-first.txt" ;;
                        esac ;;
                      'Review the supplied scope'*)
                        if [ -f "$root/focused-first.txt" ]; then cat "$root/focused-first.txt"; exit; fi
                        case "$prompt" in
                          *'## Linked durable-state and external-effect evidence'*) cat "$root/persistence.xml" ;;
                          *'<basis>OUTCOME</basis>'*'queue.schedule(actor)'*) cat "$root/scoped.xml" ;;
                          *) printf '<adaptive-review><route>LOCAL</route><summary>Checked the supplied scope.</summary><decisions/></adaptive-review>' ;;
                        esac ;;
                      'You are the independent candidate verification'*)
                        case "$prompt" in
                          *'result = average([])'*'THE NEW EVIDENCE'*) cat "$root/verified.xml" ;;
                          *'Persisted claim blocks dispatch replay'*) cat "$root/persistence-verification.xml" ;;
                          *'Attempts to queue a job after the initial check'*) cat "$root/queue-verification.xml" ;;
                          *'Rejects a permitted actor'*)
                            printf '<verification><status>REJECTED</status><reason>The permitted branch queues the job.</reason></verification>' ;;
                          *'return "skipped"'*'<basis>OUTCOME</basis>'*'THE NEW EVIDENCE'*)
                            printf '<verification><status>REJECTED</status><reason>The worker checks authorization again.</reason></verification>' ;;
                          *) exit 92 ;;
                        esac ;;
                      'You are the discovery stage'*) printf '<findings/>' ;;
                      'You are the report consolidation stage'*) cat "$root/report.txt" ;;
                      *) exit 91 ;;
                    esac
                    """);
            Files.writeString(fixture.resolve("report.txt"), String.join("\n\n", ReviewReport.headings(OutputLanguage.EN))
                    + "\n<finding-groups/>\n");
            var arguments = new ArrayList<>(List.of("review", "--repo", repository.toString(), "--output-dir",
                    fixture.resolve("runs").toString(), "--agent-command", "/bin/sh", "--agent-arg", agent.toString(),
                    "--agent-arg", fixture.toString(), "--agent-retries", "0", "--review-parallelism", "1"));
            var pipeline = new ReviewPipeline(EXECUTOR, Clock.systemUTC());
            String confirmed = decision("CONFIRMED", "", "LOW");
            Files.writeString(fixture.resolve("verified.xml"), "<verification>" + confirmed + "</verification>");
            writeLocal(fixture, confirmed);
            Path local = pipeline.run(options(arguments));
            check(Main.reviewExitCode(local) == 0 && calls(local, 1) && report(local).contains("ZeroDivisionError")
                    && report(local).contains("calculator.py:2 @"), "one call publishes a grounded local finding");
            check(Files.readString(local.resolve("coverage.md")).contains("- adaptive")
                    && !Files.exists(local.resolve("adjudication")), "local route and no publication round");

            pipeline.run(options(arguments, "--resume", local.toString()));
            check(calls(local, 0) && report(local).contains("ZeroDivisionError"), "a completed local review resumes from cache");

            writeLocal(fixture, decision("INSUFFICIENT_EVIDENCE", "REQUEST_FILE|target|calculator.py|all|Already supplied", "LOW")
                    .replace("<support>REACHABLE_FAILURE</support>", "<support>INSUFFICIENT_EVIDENCE</support>"));
            Path unresolved = pipeline.run(options(arguments));
            check(calls(unresolved, 1) && report(unresolved).contains("Review required"), "already supplied evidence leaves an explicit pending item");
            check(Files.readString(unresolved.resolve("coverage.md")).contains("Stopped; no new evidence for this question")
                    && Files.readString(unresolved.resolve("coverage.md")).contains("Already supplied"), "the decision to stop retains the evidence question");

            writeLocal(fixture, confirmed + "</local-decision><local-decision>" + decision("INSUFFICIENT_EVIDENCE",
                    "REQUEST_FILE|target|helper.py|all|Confirm a call", "LOW").replace("average([])", "a caller passes an empty input"));
            Path targeted = pipeline.run(options(arguments));
            check(Main.reviewExitCode(targeted) == 0 && calls(targeted, 2)
                    && Files.exists(targeted.resolve("adaptive/verification/L-2-response.md"))
                    && !Files.exists(targeted.resolve("adaptive/verification/L-1-prompt.md")),
                    "new evidence reruns only the candidate that requested it");

            Files.writeString(fixture.resolve("verified.xml"), """
                    <verification><status>INSUFFICIENT_EVIDENCE</status><reason>No compatibility contract is supplied.</reason>
                    <nextCheck>Ask whether empty input must return zero.</nextCheck></verification>
                    REQUEST_FILE|target|helper.py|all|Recheck the same caller
                    """);
            Path stopped = pipeline.run(options(arguments));
            check(Main.reviewExitCode(stopped) == 0 && calls(stopped, 2)
                    && !Files.exists(stopped.resolve("adaptive/verification/round-2"))
                    && Files.readString(stopped.resolve("coverage.md")).contains("Recheck the same caller"),
                    "repeat requests with no new evidence leave a recorded gap and stop");

            writeLocal(fixture, decision("CONFIRMED", "", "LOW").replace("<lines>2:2</lines>", "<lines>1:1</lines>"));
            Path fallback = pipeline.run(options(arguments));
            check(Main.reviewExitCode(fallback) == 0 && calls(fallback, 5)
                    && Files.readString(fallback.resolve("coverage.md")).contains("Local response failed validation"),
                    "unbound finding locations cannot be published and trigger deep review");

            Path forced = pipeline.run(options(arguments, "--review-mode", "deep"));
            check(calls(forced, 3) && !Files.exists(forced.resolve("adaptive")), "explicit deep review bypasses local review");

            Files.writeString(repository.resolve("README.md"), "# Guide\nRun the missing example.\n");
            Files.writeString(repository.resolve("README.zh-CN.md"), "# 指南\n运行缺失的示例。\n");
            git(repository, "commit", "-qam", "update both guides");
            String docsResponse = """
                    <documentation-review><summary>Updated two guides.</summary><doc-findings><doc-finding>
                    <title>Incorrect example</title><trigger>Following the guide</trigger><impact>The example is missing.</impact>
                    <citations><citation><source>SRC-1</source><lines>6,8</lines></citation></citations>
                    </doc-finding></doc-findings></documentation-review>
                    """;
            String mixedRanges = docsResponse.replace("6,8", "6-6,8");
            Files.writeString(fixture.resolve("documentation.xml"), mixedRanges);
            Path documentation = pipeline.run(options(arguments));
            check(Main.reviewExitCode(documentation) == 0 && calls(documentation, 1)
                    && report(documentation).contains("README.md:2")
                    && report(documentation).contains("[Review required — evidence gap] Incorrect example")
                    && Files.readString(documentation.resolve("verified-findings.md")).isEmpty(),
                    "two documentation edits use one call and bind an observation without promoting it to a confirmed finding");
            String documentationPrompt = Files.readString(documentation.resolve("documentation/review-prompt.md"));
            check(documentationPrompt.contains("README.zh-CN.md") && documentationPrompt.contains("Do not call or request any tools.")
                    && Files.readString(documentation.resolve("documentation/review-normalization.log"))
                            .contains("Normalized an evidence line selector."),
                    "the full diff is available without tools and dashed block ranges are validated locally");
            pipeline.run(options(arguments, "--resume", documentation.toString()));
            check(calls(documentation, 0), "documentation reviews resume from their validated checkpoint");

            Path firstDocumentationReply = fixture.resolve("documentation-first.txt");
            Files.writeString(firstDocumentationReply, "Tool request instead of a review.");
            Path recoveredDocumentation = pipeline.run(options(arguments));
            String recoveryPrompt = Files.readString(recoveredDocumentation.resolve("documentation/review-repair-prompt.md"));
            check(Main.reviewExitCode(recoveredDocumentation) == 0 && calls(recoveredDocumentation, 2)
                    && recoveryPrompt.startsWith("DOCUMENTATION RESPONSE RECOVERY")
                    && !recoveryPrompt.contains("Tool request instead of a review.")
                    && report(recoveredDocumentation).contains("README.md:2"),
                    "a missing review block gets a fresh evidence-based attempt and a grounded report");
            Files.delete(firstDocumentationReply);

            Files.writeString(fixture.resolve("documentation.xml"), docsResponse.replace("6,8", "6-500"));
            Path outOfRange = pipeline.run(options(arguments));
            check(Main.reviewExitCode(outOfRange) == 3 && calls(outOfRange, 2) && !report(outOfRange).contains("Incorrect example"),
                    "dashed citation ranges must still be inside the supplied evidence block");

            Files.writeString(fixture.resolve("documentation.xml"), docsResponse.replace("6,8", "7"));
            Path removed = pipeline.run(options(arguments));
            check(Main.reviewExitCode(removed) == 3 && calls(removed, 2) && !report(removed).contains("Incorrect example"),
                    "removed lines alone cannot verify a target finding");

            Files.writeString(fixture.resolve("documentation.xml"),
                    "<documentation-review><summary>Updated two guides.</summary><doc-findings/>"
                            + "<escalate>Check the example against the CLI implementation.</escalate></documentation-review>");
            Path expanded = pipeline.run(options(arguments));
            check(Main.reviewExitCode(expanded) == 0 && calls(expanded, 4)
                    && Files.readString(expanded.resolve("coverage.md")).contains("Documentation review requested broader evidence"),
                    "a documentation issue that needs implementation evidence can request full review");
            Path deepDocumentation = pipeline.run(options(arguments, "--review-mode", "deep"));
            check(calls(deepDocumentation, 3) && !Files.exists(deepDocumentation.resolve("documentation")),
                    "explicit deep review bypasses the documentation route");

            String distantContext = "\n# Unrelated detail.\n".repeat(50) + "# FAR_FROM_EDIT\n";
            Files.writeString(repository.resolve("calculator.py"), BEFORE + distantContext);
            git(repository, "commit", "-qam", "restore local behavior");
            Files.writeString(repository.resolve("calculator.py"), AFTER + distantContext);
            Files.createDirectories(repository.resolve("tests"));
            Files.createDirectories(repository.resolve("docs"));
            Files.writeString(repository.resolve("tests/test_calculator.py"), "from calculator import average\nassert average([2]) == 2\n");
            Files.writeString(repository.resolve("docs/average.md"), "# Average\nSee the [implementation](../calculator.py).\n");
            git(repository, "add", ".");
            git(repository, "commit", "-qm", "edit one implementation and its supporting material");
            writeLocal(fixture, confirmed);
            Path grouped = pipeline.run(options(arguments));
            String groupedPrompt = Files.readString(grouped.resolve("adaptive/local-prompt.md"));
            check(Main.reviewExitCode(grouped) == 0 && calls(grouped, 1)
                    && groupedPrompt.contains("tests/test_calculator.py") && groupedPrompt.contains("docs/average.md")
                    && !groupedPrompt.contains("FAR_FROM_EDIT")
                    && report(grouped).contains("calculator.py:2 @"),
                    "one implementation and its related test and documentation share one grounded local call");
            pipeline.run(options(arguments, "--resume", grouped.toString()));
            check(calls(grouped, 0), "a grouped local review reuses its validated response");

            String oversized = "\n# Distant implementation detail.\n".repeat(600);
            Files.writeString(repository.resolve("calculator.py"), AFTER + oversized);
            git(repository, "commit", "-qam", "expand the file outside the reviewed code");
            Files.writeString(repository.resolve("calculator.py"), BEFORE + oversized);
            Files.writeString(repository.resolve("tests/test_calculator.py"), "from calculator import average\nassert average([]) == 0\n");
            git(repository, "commit", "-qam", "edit a large file and its test");
            Path largeGroup = pipeline.run(options(arguments));
            check(Files.exists(largeGroup.resolve("focused/O-prompt.md")) && !Files.exists(largeGroup.resolve("discovery")),
                    "a group outside the local evidence budget follows its normal focused route");

            Files.writeString(repository.resolve("calculator.py"), AFTER + oversized);
            Files.createDirectories(repository.resolve("tests/unrelated"));
            Files.writeString(repository.resolve("tests/unrelated/test_calculator.py"), "assert 1 == 1\n");
            git(repository, "add", ".");
            git(repository, "commit", "-qm", "change implementation and a same-named test in another folder");
            Path unrelated = pipeline.run(options(arguments));
            check(!Files.exists(unrelated.resolve("adaptive")) && Files.exists(unrelated.resolve("focused/O-prompt.md")),
                    "a same-named test in an unrelated module cannot qualify for the local route");

            Files.writeString(repository.resolve("app.py"), "def has_permission(user):\n    return True\n");
            git(repository, "commit", "-qam", "change permission");
            Path risk = pipeline.run(options(arguments));
            check(calls(risk, 1) && !Files.exists(risk.resolve("adaptive"))
                    && Files.readString(risk.resolve("focused/F1-prompt.md")).contains("where authorization is enforced"),
                    "a single-line permission change in a generic path starts in a focused risk check");

            Files.writeString(repository.resolve("app.py"), "def has_permission(user):\n    return False\n");
            Files.writeString(repository.resolve("tests/test_app.py"), "from app import has_permission\nassert not has_permission(None)\n");
            git(repository, "add", ".");
            git(repository, "commit", "-qm", "permission implementation and matching test");
            Path groupedRisk = pipeline.run(options(arguments));
            check(!Files.exists(groupedRisk.resolve("adaptive")) && calls(groupedRisk, 2)
                    && Files.readString(groupedRisk.resolve("focused/F1-prompt.md")).contains("where authorization is enforced"),
                    "the matching test does not bypass a production risk check");

            Path runtime = Files.createDirectory(repository.resolve("runtime"));
            Files.writeString(runtime.resolve("store.py"), "def save(db):\n    db.execute('INSERT INTO events VALUES (1)')\n");
            Files.writeString(runtime.resolve("worker.py"), "def run(db, service):\n    save(db)\n    return service.trigger()\n");
            Path operations = Files.createDirectory(repository.resolve("ops"));
            Files.writeString(operations.resolve("deploy.yaml"), "database_path: /data/events.sqlite3\n");
            Files.writeString(repository.resolve("notes.md"), "Update deployment notes.\n");
            git(repository, "add", ".");
            git(repository, "commit", "-qm", "add a persistent trigger and release notes");
            Path focused = pipeline.run(options(arguments));
            String riskPrompt = Files.readString(focused.resolve("focused/F1-prompt.md"));
            String overview = Files.readString(focused.resolve("focused/O-prompt.md"));
            String attemptOrder = Files.readString(focused.resolve("timings.md"));
            int firstRiskCall = attemptOrder.indexOf("|focused/F1-attempts/");
            int overviewCall = attemptOrder.indexOf("|focused/O-attempts/");
            check(firstRiskCall >= 0 && overviewCall > firstRiskCall,
                    "serial focused reviews start the targeted risk checks before the overview");
            check(Main.reviewExitCode(focused) == 0 && calls(focused, 2)
                    && riskPrompt.contains("before the effect") && riskPrompt.contains("runtime/store.py")
                    && riskPrompt.contains("runtime/worker.py") && !riskPrompt.contains("notes.md")
                    && !riskPrompt.contains("## Linked durable-state and external-effect evidence")
                    && riskPrompt.contains("+    return service.trigger()")
                    && overview.contains("notes.md") && overview.contains("ops/deploy.yaml") && !overview.contains("runtime/worker.py")
                    && !Files.exists(focused.resolve("discovery")),
                    "multi-file risk checking retains the producer and side effect together while keeping unrelated scope separate");
            check(riskPrompt.contains("Do not call or request any tools."),
                    "focused requests ask for a complete review from the supplied evidence");

            Path malformedScope = fixture.resolve("focused-first.txt");
            Files.writeString(malformedScope, "<tool_call>No review content</tool_call>");
            Path partialFocused = pipeline.run(options(arguments));
            String scopeRecovery = Files.readString(partialFocused.resolve("focused/F1-repair-prompt.md"));
            check(Main.reviewExitCode(partialFocused) == 3 && calls(partialFocused, 4)
                    && scopeRecovery.startsWith("ADAPTIVE RESPONSE RECOVERY")
                    && scopeRecovery.contains("runtime/worker.py") && !scopeRecovery.contains("No review content")
                    && Files.readString(partialFocused.resolve("focused/F1-state.properties")).contains("status=SUCCEEDED")
                    && Files.readString(partialFocused.resolve("focused/O-state.properties")).contains("status=FAILED")
                    && report(partialFocused).contains("unfinished checks: 1."),
                    "a tool-only reply retries the review from its evidence and an unrecovered scope counts as unfinished");
            Files.delete(malformedScope);

            Path outbound = Files.createDirectory(repository.resolve("outbound"));
            Files.writeString(outbound.resolve("driver.py"), """
                    from .repo import claim as reserve
                    from .sender import client
                    def dispatch(db):
                        if not reserve(db):
                            return "skipped"
                        return client.post()
                    """);
            Files.writeString(outbound.resolve("repo.py"), """
                    def claim(db):
                        return db.execute("INSERT INTO jobs VALUES (1) ON CONFLICT DO NOTHING").rowcount
                    """);
            Files.writeString(outbound.resolve("sender.py"), """
                    class Sender:
                        def post(self):
                            return "sent"
                    client = Sender()
                    """);
            Files.writeString(outbound.resolve("status.py"), "import subprocess\nsubprocess.run(['uptime'])\n");
            Files.writeString(repository.resolve("notes.md"), "The operation is retried.\n");
            git(repository, "add", ".");
            git(repository, "commit", "-qm", "add a replayable dispatch and an independent status operation");
            String persistence = """
                    <local-decision>
                    <status>CONFIRMED</status><reason>The persisted claim blocks replay.</reason>
                    <relation>INTRODUCED</relation><baseline>This entry point is new.</baseline>
                    <contract>Retry must dispatch a job whose initial dispatch was interrupted.</contract>
                    <support>REACHABLE_FAILURE</support><premises>
                    <premise><statement>The claim is persisted before the send.</statement><basis>IMPLEMENTATION</basis>
                    <citations><citation><source>SRC-4</source><lines>2:2</lines></citation></citations></premise>
                    <premise><statement>The retried job is skipped.</statement><basis>OUTCOME</basis>
                    <citations><citation><source>SRC-2</source><lines>4:5</lines></citation></citations></premise>
                    </premises><citations><citation><source>SRC-1</source><lines>2:2</lines></citation></citations>
                    <finding><title>Persisted claim blocks dispatch replay</title><severity>MEDIUM</severity>
                    <path>outbound/driver.py</path><line>5</line><endLine>5</endLine>
                    <trigger>Dispatch is interrupted after claiming the job.</trigger>
                    <impact>The retried job is skipped.</impact><evidence>Duplicate claims return zero.</evidence>
                    <diffRelation>The patch introduces the dispatch entry point.</diffRelation></finding>
                    </local-decision>
                    """;
            Files.writeString(fixture.resolve("persistence.xml"), "<adaptive-review><route>LOCAL</route>"
                    + "<summary>Checked retry behavior.</summary><decisions>" + persistence + "</decisions></adaptive-review>");
            Files.writeString(fixture.resolve("persistence-verification.xml"), scopedVerification(persistence, "F1-1"));
            Path linked = pipeline.run(options(arguments));
            String linkedPrompt = Files.readString(linked.resolve("focused/F1-prompt.md"));
            int linkedStart = linkedPrompt.indexOf("## Linked durable-state and external-effect evidence");
            int remainingStart = linkedPrompt.indexOf("## Other changed implementation in this scope");
            check(linkedStart >= 0 && remainingStart > linkedStart
                    && linkedPrompt.substring(linkedStart, remainingStart).contains("outbound/driver.py")
                    && linkedPrompt.substring(linkedStart, remainingStart).contains("outbound/repo.py")
                    && linkedPrompt.substring(linkedStart, remainingStart).contains("outbound/sender.py")
                    && !linkedPrompt.substring(linkedStart, remainingStart).contains("outbound/status.py")
                    && linkedPrompt.substring(remainingStart).contains("outbound/status.py")
                    && linkedPrompt.contains("For other external effects:")
                    && linkedPrompt.contains("new file mode 100644") && !linkedPrompt.contains("+        return \"skipped\"")
                    && linkedPrompt.contains("5 |         return \"skipped\"")
                    && Files.readString(linked.resolve("focused/O-prompt.md")).contains("notes.md"),
                    "the behavior, its linked sources and independent changed code are covered without copying new file bodies");
            String linkedReport = report(linked);
            check(Main.reviewExitCode(linked) == 0 && calls(linked, 3)
                    && linkedReport.contains("Persisted claim blocks dispatch replay")
                    && linkedReport.contains("`outbound/driver.py:5`"),
                    "compact diff headers and intact target sources still ground a published cross-file finding\n"
                            + linkedReport);

            Files.writeString(repository.resolve("entrypoint.py"), """
                    from .authorization import request_review
                    def route(actor, queue):
                        if not actor.can_review:
                            return "blocked by caller"
                        return request_review(actor, queue)
                    """);
            git(repository, "add", "entrypoint.py");
            git(repository, "commit", "-qm", "record the existing calling context");
            Files.writeString(repository.resolve("authorization.py"), """
                    def request_review(actor, queue):
                        if authorize(actor):
                            queue.schedule(actor)
                    """);
            git(repository, "add", "authorization.py");
            git(repository, "commit", "-qm", "schedule approved requests");
            String outcome = "<premise><statement>The scheduling call is made.</statement><basis>OUTCOME</basis>"
                    + "<citations><citation><source>SRC-2</source><lines>3:3</lines></citation></citations></premise>";
            String queue = scopedFinding("CONFIRMED", "Attempts to queue a job after the initial check", "The scheduling call is made.", outcome);
            String publication = scopedFinding("CONFIRMED", "Publishes a review after revocation",
                    "A review comment is published.", outcome);
            String opposite = scopedFinding("CONFIRMED", "Rejects a permitted actor", "A permitted actor is rejected.",
                    outcome.replace("The scheduling call is made.", "A permitted actor is rejected."));
            String execution = scopedFinding("INSUFFICIENT_EVIDENCE", "Runs a review after revocation",
                    "A review job runs.", "").replace("<severity>HIGH</severity>", "").replace("</local-decision>",
                    "<requests>REQUEST_FILE|target|review_worker.py|all|Check whether the worker authorizes again</requests></local-decision>");
            Files.writeString(fixture.resolve("queue-verification.xml"), scopedVerification(queue, "F1-1")
                    .replace("<finding", "<citations><citation><source>SRC-1</source><lines>2:2</lines></citation></citations><finding"));
            Files.writeString(fixture.resolve("scoped.xml"), "<adaptive-review><route>LOCAL</route>"
                    + "<summary>The worker stops before the claim is written and the report is published after revocation.</summary>"
                    + "<decisions>" + queue + publication + execution + opposite
                    + "</decisions><nextCheck>Root advice without a candidate.</nextCheck></adaptive-review>");
            Path limited = pipeline.run(options(arguments));
            String finalReport = report(limited);
            String summary = finalReport.substring(0, finalReport.indexOf("# Findings"));
            String published = Files.readString(limited.resolve("verified-findings.md"));
            String coverage = Files.readString(limited.resolve("coverage.md"));
            check(Main.reviewExitCode(limited) == 0 && calls(limited, 4)
                    && summary.contains("Confirmed defects: 1; review-required items: 1; unfinished checks: 0")
                    && summary.contains("Attempts to queue a job after the initial check") && !summary.contains("published after revocation")
                    && !summary.contains("before the claim")
                    && published.contains("### [MEDIUM] Attempts to queue a job after the initial check")
                    && !published.contains("Publishes a review") && finalReport.contains("stated impact has no explicit supporting outcome citation")
                    && coverage.contains("Severity limited to MEDIUM") && coverage.contains("The worker checks authorization again.")
                    && coverage.contains("The permitted branch queues the job.") && !published.contains("Rejects a permitted actor")
                    && Files.readString(limited.resolve("focused/F1-prompt.md")).contains("return \"blocked by caller\"")
                    && Files.readString(limited.resolve("focused/F1-normalization.log")).contains("without a model severity")
                    && Files.readString(limited.resolve("focused/F1-normalization.log")).contains("Omitted misplaced advisory")
                    && !finalReport.contains("Root advice without a candidate."),
                    "scoped decisions remain grounded with an unrated candidate or an unassigned advisory");

            String comments = ("# original text for an early hunk large enough to exceed a small review budget\n").repeat(100);
            String separator = "# unchanged context\n".repeat(24);
            Path ratios = repository.resolve("ratios.py");
            Path labels = repository.resolve("labels.py");
            Files.writeString(ratios, comments + separator + BEFORE);
            Files.writeString(labels, "LABEL = 'old'\n");
            git(repository, "add", ".");
            git(repository, "commit", "-qm", "add two independent implementations");
            Files.writeString(ratios, comments.replace("original", "modified") + separator
                    + AFTER.replace("len(values)", "len(values)  # late guard removal"));
            Files.writeString(labels, "LABEL = 'new'\n");
            git(repository, "commit", "-qam", "large early diff and small late regression");
            Path complete = pipeline.run(options(arguments));
            Path clipped = pipeline.run(options(arguments, "--max-evidence-bytes", "10000"));
            check(Main.reviewExitCode(complete) == 0 && calls(complete, 2)
                    && Files.readString(complete.resolve("focused/O-prompt.md")).contains("# late guard removal")
                    && Files.readString(complete.resolve("focused/T-prompt.md")).contains("modified text for an early hunk")
                    && !Files.readString(complete.resolve("focused/T-prompt.md")).contains("# late guard removal"),
                    "separate diff hunks reach one of the two model requests without overlap");
            check(Main.reviewExitCode(clipped) == 3 && calls(clipped, 1)
                    && report(clipped).contains("## Unreviewed changes") && report(clipped).contains("ratios.py — @@ -1,108 +1,108 @@"),
                    "unshown diff hunks are explicit in an incomplete report at a low budget");
        } finally {
            try (var paths = Files.walk(fixture)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void scopedProof() throws Exception {
        var scope = new ReviewScope(ReviewScope.Mode.BRANCH, "base", "target", "base", "target", "base..target");
        var diff = EvidenceSource.diff("calculator.py", null, """
                diff --git a/calculator.py b/calculator.py
                new file mode 100644
                --- /dev/null
                +++ b/calculator.py
                @@ -0,0 +1,2 @@
                +def average(values):
                +    return sum(values) / len(values)
                """, scope, true);
        var target = EvidenceSources.file("target", "calculator.py", 1, "calculator.py at target",
                AFTER, scope, LanguageRegistry.defaults()).throughEndOfFile();
        var catalog = new EvidenceCatalog(List.of(diff, target), true);
        var bundle = new EvidenceBundle(scope, List.of("calculator.py"), "", "", 10000, null, catalog);
        String direct = decision("CONFIRMED", "", "LOW").replace(
                "<citations><citation><source>SRC-3</source><lines>2:3</lines></citation></citations>", "");
        String unsupported = direct.replace("<lines>2:2</lines>", "<lines>1:1</lines>");
        String report = "<adaptive-review><route>LOCAL</route><summary>Added an implementation.</summary>"
                + "<decisions><local-decision>" + direct + "</local-decision><local-decision>" + unsupported
                + "</local-decision></decisions></adaptive-review>";
        var response = AdaptiveReview.parse(report, bundle, new EvidenceIndex(catalog), "F1", true);
        check(response.candidates().getFirst().decision().verdict().status() == ReviewOutput.Status.CONFIRMED
                && response.candidates().getFirst().decision().citations().stream()
                        .anyMatch(citation -> citation.quote().equals("new file mode 100644"))
                && response.candidates().get(1).decision().verdict().status() == ReviewOutput.Status.INSUFFICIENT_EVIDENCE,
                "the scope grounds new-file attribution from Git and retains separately validated findings when a peer lacks proof");

        String invalidRange = direct.replace("<lines>2:2</lines>", "<lines>2:4</lines>");
        var repairs = AdaptiveReview.parse(report.replace(unsupported, invalidRange), bundle, new EvidenceIndex(catalog), "F1", true);
        check(repairs.candidates().getFirst().citationRepair() == null && repairs.candidates().get(1).citationRepair() != null,
                "only the originally confirmed candidate with a broken citation is eligible for correction");
        Path fixture = Files.createTempDirectory("scoped-citation-repair-");
        try (var progress = new CommandProgress("repair", new java.io.PrintStream(java.io.OutputStream.nullOutputStream()))) {
            var options = CliOptions.parse(new String[]{"review", "--repo", fixture.toString()});
            String correction = "<citations><citation><source>SRC-2</source><lines>2:2</lines></citation></citations>";
            String script = """
                    prompt=$(cat)
                    case "$prompt" in
                      *'Retained citations for context:'*'new file mode 100644'*'Invalid source: SRC-2 lines 2:4'*'2 |     return sum(values) / len(values)'*)
                        printf '%s' "$1" ;;
                      *) exit 92 ;;
                    esac
                    """;
            var agent = new AgentRunner(EXECUTOR, "/bin/sh", List.of("-c", script, "repair", correction), fixture, Duration.ofSeconds(5));
            var coverage = new StringBuilder();
            var corrected = ScopedCitationRepair.run(repairs.candidates(), bundle, options, agent, fixture.resolve("success"), progress, 9, coverage);
            check(corrected.size() == 1 && corrected.getFirst().verdict().status() == ReviewOutput.Status.CONFIRMED
                    && corrected.getFirst().verdict().confirmed().id().equals("F1-2")
                    && coverage.toString().contains("Passed the original proof checks")
                    && !Files.exists(fixture.resolve("success/F1-1-prompt.md")),
                    "one candidate correction can restore the confirmed outcome without touching its peer");
            var invalidAgent = new AgentRunner(EXECUTOR, "/bin/sh", List.of("-c", script, "repair", correction.replace("2:2", "2:3")),
                    fixture, Duration.ofSeconds(5));
            check(ScopedCitationRepair.run(repairs.candidates(), bundle, options, invalidAgent, fixture.resolve("invalid"), progress, 9,
                    new StringBuilder()).isEmpty() && !Files.exists(fixture.resolve("invalid/F1-2-quote-repair-prompt.md")),
                    "a correction outside the offered source stays unresolved without another attempt");
            var decline = new AgentRunner(EXECUTOR, "/bin/sh", List.of("-c", script, "repair", "<citations/>"), fixture, Duration.ofSeconds(5));
            var declinedCoverage = new StringBuilder();
            check(ScopedCitationRepair.run(repairs.candidates(), bundle, options, decline, fixture.resolve("decline"), progress, 9,
                    declinedCoverage).isEmpty() && declinedCoverage.toString().contains("No supported replacement proposed."),
                    "declining to replace an unsupported citation preserves the unresolved decision");
        } finally {
            try (var paths = Files.walk(fixture)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static String scopedFinding(String status, String title, String impact, String extraPremises) {
        return """
                <local-decision><status>%s</status><reason>Check the review triggered by a permission change.</reason>
                <relation>INTRODUCED</relation><baseline>The approval file did not exist.</baseline>
                <contract>Respect authorization for review operations.</contract><support>REACHABLE_FAILURE</support>
                <premises><premise><statement>A job is scheduled after checking the actor.</statement><basis>IMPLEMENTATION</basis>
                  <citations><citation><source>SRC-2</source><lines>2:3</lines></citation></citations>
                </premise>%s</premises>
                <finding><title>%s</title><severity>HIGH</severity><path>authorization.py</path><line>3</line><endLine>3</endLine>
                  <trigger>Access is revoked after the first check.</trigger><impact>%s</impact>
                  <evidence>The queue call follows the permission check.</evidence><diffRelation>A new scheduling entry point.</diffRelation></finding>
                </local-decision>
                """.formatted(status, extraPremises, title, impact);
    }

    private static String scopedVerification(String decision, String id) {
        return decision.replace("<local-decision>", "<verification>").replace("</local-decision>", "</verification>")
                .replace("<finding>", "<finding id=\"" + id + "\">");
    }

    private static String decision(String status, String requests, String severity) {
        return """
                <status>%s</status><reason>The old guard handled empty input.</reason>
                <relation>INTRODUCED</relation><baseline>Empty input returned 0.</baseline>
                <contract>Prevent a reachable local zero divisor.</contract><support>REACHABLE_FAILURE</support>
                <premises><premise><statement>Empty lists have no elements, so the divisor is zero.</statement>
                  <basis>IMPLEMENTATION</basis><citations><citation><source>SRC-2</source><lines>2:2</lines></citation></citations>
                </premise></premises>
                <citations><citation><source>SRC-3</source><lines>2:3</lines></citation></citations>
                <finding><title>Empty-input failure</title><severity>%s</severity><path>calculator.py</path><line>2</line><endLine>2</endLine>
                  <trigger>average([])</trigger><impact>Raises ZeroDivisionError.</impact><evidence>Compare both revisions.</evidence>
                  <diffRelation>The change removes the guard.</diffRelation></finding>
                """.formatted(status, severity) + (requests.isEmpty() ? "" : "<requests>" + requests + "</requests>");
    }

    private static void writeLocal(Path fixture, String decisions) throws Exception {
        Files.writeString(fixture.resolve("local.xml"), "<adaptive-review><route>LOCAL</route><summary>The guard was removed.</summary>"
                + "<decisions><local-decision>" + decisions + "</local-decision></decisions></adaptive-review>");
    }

    private static CliOptions options(List<String> arguments, String... extra) {
        var copy = new ArrayList<>(arguments);
        copy.addAll(List.of(extra));
        return CliOptions.parse(copy.toArray(String[]::new));
    }

    private static boolean calls(Path run, int count) throws Exception {
        return Files.readString(run.resolve("timings.md")).contains("Actual launched attempts: " + count + "\n");
    }

    private static String report(Path run) throws Exception { return Files.readString(run.resolve("report.md")); }

    private static void git(Path repository, String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));
        var result = EXECUTOR.run(command, repository, null, Duration.ofSeconds(10));
        check(result.exitCode() == 0, result.stderr());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
