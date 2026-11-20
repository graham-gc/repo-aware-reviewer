package reviewagent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class ReviewRequestTests {
    private final ProcessExecutor executor = new ProcessExecutor();
    private final ReviewPipeline pipeline = new ReviewPipeline(executor, Clock.systemUTC());
    private final Path root;
    private final Path repository;
    private final Path model;

    private ReviewRequestTests(Path root) throws Exception {
        this.root = root;
        repository = Files.createDirectory(root.resolve("repo"));
        model = root.resolve("model.sh");
        git("init", "-q");
        git("config", "user.name", "Request fixture");
        git("config", "user.email", "fixture@example.invalid");
        Files.writeString(model, """
                fixture=$1
                prompt=$(cat)
                case "$prompt" in
                  'Review the supplied scope'*)
                    case "$prompt" in
                      *'Question: Trace who can trigger'*)
                        if [ -f "$fixture/expand.xml" ]; then cat "$fixture/expand.xml"; exit; fi
                        if [ -f "$fixture/stalled-xml" ]; then cat "$fixture/stalled-xml"; exit; fi
                        if [ -f "$fixture/navigation.xml" ]; then cat "$fixture/navigation.xml"; exit; fi
                        if [ -f "$fixture/location-first.xml" ]; then cat "$fixture/location-first.xml"; exit; fi ;;
                    esac
                    printf '<adaptive-review><route>LOCAL</route><summary>Reviewed fixture.</summary><decisions/></adaptive-review>' ;;
                  'Correct the finding location for only the supplied candidate'*) cat "$fixture/location-reply.xml" ;;
                  'You are the independent candidate verification'*)
                    if [ -f "$fixture/navigation.xml" ]; then
                        case "$prompt" in
                          *'CALLER_GUARD_EVIDENCE'*)
                            printf '<verification><status>REJECTED</status><reason>The pinned caller already rejects unchecked requests.</reason></verification>' ;;
                          *) printf '<verification><status>INSUFFICIENT_EVIDENCE</status><reason>The caller remains unlocated.</reason></verification>\\nREQUEST_FILE|target|queue|index|Find the caller\\n' ;;
                        esac
                        exit
                    fi
                    if [ -f "$fixture/stalled-xml" ]; then
                        case "$prompt" in
                          *'CONSUMER_BLOCKS_UNAPPROVED'*)
                            printf '<verification><status>REJECTED</status><reason>The consumer rejects the work.</reason></verification>' ;;
                          *'PRODUCER_DELEGATES_TO_CONSUMER'*)
                            printf '<verification><status>INSUFFICIENT_EVIDENCE</status><reason>Need the consumer guard.</reason>'
                            if [ -f "$fixture/cite-producer" ]; then
                                ref=$(printf '%s\\n' "$prompt" | sed -n 's/^- \\(SRC-[0-9]*\\): queue\\/producer.py at .*/\\1/p' | head -n 1)
                                printf '<citations><citation><source>%s</source><lines>1:1</lines></citation></citations>' "$ref"
                            fi
                            printf '</verification>\\nREQUEST_FILE|target|queue/consumer.py|all|Does the consumer block unapproved work?\\n' ;;
                          *) printf '<verification><status>INSUFFICIENT_EVIDENCE</status><reason>Need the producer contract.</reason></verification>\\nREQUEST_FILE|target|queue/producer.py|all|Where does the producer send this work?\\n' ;;
                        esac
                        exit
                    fi
                    case "$prompt" in
                      *'The scheduler may still block'*'APPROVED_ONLY'*)
                        printf '<verification><status>REJECTED</status><reason>The supplied scheduler only accepts approved work.</reason></verification>' ;;
                      *) exit 91 ;;
                    esac ;;
                  *) exit 91 ;;
                esac
                """);
    }

    static void run() throws Exception {
        Path fixture = Files.createTempDirectory("review-requests-");
        try {
            new ReviewRequestTests(Files.createDirectory(fixture.resolve("followup"))).followup();
            new ReviewRequestTests(Files.createDirectory(fixture.resolve("discovery"))).discovery();
            new ReviewRequestTests(Files.createDirectory(fixture.resolve("java"))).javaCaller();
            new ReviewRequestTests(Files.createDirectory(fixture.resolve("expansion"))).expansion();
            new ReviewRequestTests(Files.createDirectory(fixture.resolve("stalled"))).stalledEvidence();
            new ReviewRequestTests(Files.createDirectory(fixture.resolve("navigation"))).navigationEvidence();
            new ReviewRequestTests(Files.createDirectory(fixture.resolve("locations"))).locations();
        } finally {
            try (var paths = Files.walk(fixture)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private void followup() throws Exception {
        write("a.txt", "before\n");
        write("b.txt", "before\n");
        write("c.txt", "before\n");
        commit("base");
        write("a.txt", "large edit\n".repeat(5700));
        write("b.txt", "second edit\n".repeat(480));
        write("c.txt", "third edit\n".repeat(480));
        commit("all remaining hunks fit together");
        var reviewed = review();
        var prompt = Files.readString(reviewed.resolve("focused/T-prompt.md"));
        check(Main.reviewExitCode(reviewed) == 0 && calls(reviewed, 1)
                && prompt.contains("large edit") && prompt.contains("second edit") && prompt.contains("third edit"),
                "remaining hunks share one request when their total fits");

        write("a.txt", "new large edit\n".repeat(4600));
        write("b.txt", "new second edit\n".repeat(4500));
        write("c.txt", "new third edit\n".repeat(4300));
        commit("several large hunks");
        var oversized = review();
        check(Main.reviewExitCode(oversized) == 3
                && Files.readString(oversized.resolve("review-status.md")).contains("Unreviewed change"),
                "a hunk larger than a single request is still disclosed");
        String first = Files.readString(oversized.resolve("focused/T-prompt.md"));
        String next = Files.readString(oversized.resolve("focused/T2-prompt.md"));
        String firstPath = first.contains("- b.txt\n") ? "b.txt" : "c.txt";
        String nextPath = firstPath.equals("b.txt") ? "c.txt" : "b.txt";
        check(first.contains("- " + firstPath + "\n") && next.contains("- " + nextPath + "\n")
                && !first.contains(nextPath) && !next.contains(firstPath)
                && !first.contains("a.txt") && !next.contains("a.txt"),
                "each follow-up gives the model only the eligible paths for that scope");
    }

    private void locations() throws Exception {
        write("auth/check.py", "def authorize(approved):\n    return approved\n");
        write("queue/schedule.py", "limit = 1\n");
        commit("base");
        write("auth/check.py", "def authorize(approved):\n    return True\n");
        write("queue/schedule.py", "limit = 2\n");
        commit("focus and overview each get different files");
        String misplaced = """
                <local-decision><status>INSUFFICIENT_EVIDENCE</status>
                  <reason>Whether downstream processing preserves the eligibility decision is unclear.</reason>
                  <finding><title>Check downstream eligibility</title><path>queue/schedule.py</path>
                  <line>1</line><endLine>1</endLine><trigger>Unapproved work is submitted</trigger>
                  <impact>Work may continue</impact><evidence>The previous guard returned the input</evidence>
                  <diffRelation>The eligibility result changes</diffRelation></finding>
                </local-decision>
                """;
        String confirmed = """
                <local-decision><status>CONFIRMED</status><reason>The guard now returns true for every input.</reason>
                  <relation>INTRODUCED</relation><baseline>Before the guard returned the approved input.</baseline>
                  <contract>The predicate tests whether the input is approved.</contract><support>REACHABLE_FAILURE</support>
                  <premises>
                    <premise><statement>The target returns true for any input.</statement><basis>IMPLEMENTATION</basis>
                      <citations><citation><source>SRC-2</source><lines>1:2</lines></citation></citations></premise>
                    <premise><statement>The predicate returns true for an unapproved request.</statement><basis>OUTCOME</basis>
                      <citations><citation><source>SRC-2</source><lines>1:2</lines></citation></citations></premise>
                  </premises>
                  <citations><citation><source>SRC-3</source><lines>2:2</lines></citation>
                    <citation><source>SRC-2</source><lines>1:2</lines></citation></citations>
                  <finding><title>Unapproved input passes the predicate</title><severity>LOW</severity><path>auth/check.py</path>
                  <line>2</line><endLine>2</endLine><trigger>The predicate receives false.</trigger>
                  <impact>The predicate returns true for an unapproved request.</impact><evidence>The target returns true.</evidence>
                  <diffRelation>The changed line replaces the approval input.</diffRelation></finding>
                </local-decision>
                """;
        Files.writeString(root.resolve("location-first.xml"), "<adaptive-review><route>LOCAL</route>"
                + "<summary>Reviewed the guard.</summary><decisions>" + misplaced + confirmed + "</decisions></adaptive-review>");
        Path correction = root.resolve("location-reply.xml");
        Files.writeString(correction, misplaced.replace("queue/schedule.py", "auth/check.py"));
        var resolved = review();
        String report = Files.readString(resolved.resolve("report.md"));
        String scope = Files.readString(resolved.resolve("focused/F1-prompt.md"));
        String repair = Files.readString(resolved.resolve("focused/F1/locations/F1-1-prompt.md"));
        check(Main.reviewExitCode(resolved) == 0 && calls(resolved, 3) && scope.contains("- auth/check.py\n")
                && !scope.contains("queue/schedule.py") && repair.contains("Check downstream eligibility")
                && !repair.contains("Unapproved input passes the predicate")
                && report.contains("Confirmed defects: 1") && report.contains("F1-1")
                && Files.readString(resolved.resolve("verified-findings.md")).contains("Unapproved input passes the predicate")
                && Files.readString(resolved.resolve("coverage.md")).contains("F1-2 — CONFIRMED"),
                "only the misplaced candidate is corrected and its confirmed peer keeps its ID and proof");
        pipeline.run(options("--resume", resolved.toString()));
        check(calls(resolved, 0), "a corrected candidate can reuse its validated checkpoint");

        Files.writeString(correction, misplaced);
        var failed = review();
        check(Main.reviewExitCode(failed) == 3 && calls(failed, 3)
                && Files.readString(failed.resolve("review-status.md")).contains("F1-1 — finding location outside")
                && Files.readString(failed.resolve("report.md")).contains("Confirmed defects: 1")
                && !Files.exists(failed.resolve("focused/F1-repair-prompt.md"))
                && !Files.exists(failed.resolve("focused/F1/locations/F1-1-repair-prompt.md")),
                "a failed location correction leaves one unfinished candidate and preserves the valid peer");

        Files.writeString(root.resolve("location-first.xml"), "<adaptive-review><route>DEEP</route>"
                + "<summary>Review the guard.</summary><decisions>"
                + confirmed.replace("auth/check.py", "queue/schedule.py") + "</decisions></adaptive-review>");
        var unresolved = review();
        check(Main.reviewExitCode(unresolved) == 3 && calls(unresolved, 3)
                && Files.readString(unresolved.resolve("review-status.md")).contains("F1-1 — finding location outside"),
                "failed DEEP location correction still leaves an unfinished candidate");

        Files.writeString(correction, confirmed);
        var correctedDeep = review();
        check(Main.reviewExitCode(correctedDeep) == 0 && calls(correctedDeep, 3)
                && Files.readString(correctedDeep.resolve("verified-findings.md")).contains("Unapproved input passes the predicate")
                && Files.readString(correctedDeep.resolve("coverage.md")).contains("F1-1: corrected the candidate location"),
                "a resolved DEEP candidate is reported without an unfinished-scope diagnostic");
    }

    private void discovery() throws Exception {
        write("Stable.java", "class Stable {\n    int value = 2;\n" + "    // deliberately stable context\n".repeat(20)
                + "    int boundaryValue = 190923;\n}\n");
        commit("base");
        write("Plain.java", "class Plain {\n    int value() { return 3; }\n}\n");
        write("Guard.java", "class Guard {\n    boolean authorize(boolean approved) { return approved; }\n}\n");
        write("docs/design.md", "# Design\nKeep the established return value for existing callers.\n");
        var stable = repository.resolve("Stable.java");
        Files.writeString(stable, Files.readString(stable).replace("value = 2", "value = 3"));
        commit("prepare different discovery evidence views");
        var run = review("--prepare-only", "--review-mode", "deep");
        var correct = Files.readString(run.resolve("discovery/R1-prompt.md"));
        var contract = Files.readString(run.resolve("discovery/R2-prompt.md"));
        var boundary = Files.readString(run.resolve("discovery/R3-prompt.md"));
        check(correct.contains("### Plain.java at ") && !contract.contains("### Plain.java at ") && !boundary.contains("### Plain.java at ")
                && contract.contains("+    int value() { return 3; }") && boundary.contains("+    int value() { return 3; }")
                && contract.contains("### docs/design.md at ") && boundary.contains("### Guard.java at ")
                && contract.contains("boundaryValue = 190923") && boundary.contains("boundaryValue = 190923"),
                "focused discovery drops duplicate views but retains all changed lines and context outside the diff");
    }

    private void javaCaller() throws Exception {
        String policy = "package policy; public class Policy { public boolean authorize(boolean allowed) { return allowed; } }\n";
        String caller = """
                package entry;
                import policy.Policy;
                public class Entry {
                    private final Policy policy = new Policy();
                    public boolean submit(boolean permitted) {
                        return policy.authorize(permitted);
                    }
                }
                """;
        write("policy/Policy.java", policy);
        write("entry/Entry.java", caller.replace("        return policy", "        if (!permitted) return false; // pinned guard\n        return policy"));
        write("entry/Fake.java", "package entry; import policy.Policy; class Fake { /* new Policy().authorize(false) */ }\n");
        write("wrong/Policy.java", "package wrong; public class Policy { public boolean authorize(boolean a) { return a; } }\n");
        write("wrong/Entry.java", caller.replace("package entry;", "package wrong;").replace("import policy.Policy;", ""));
        commit("base with a caller guard");
        write("policy/Policy.java", policy.replace("return allowed", "return true"));
        commit("change the authorization predicate");
        write("entry/Entry.java", caller + "// WORKTREE_ONLY\n");
        var guarded = review();
        var first = Files.readString(guarded.resolve("focused/F1-prompt.md"));
        check(Main.reviewExitCode(guarded) == 0 && calls(guarded, 1) && first.contains("pinned guard")
                && first.contains("policy.authorize(permitted)") && !first.contains("WORKTREE_ONLY") && !first.contains("entry/Fake.java")
                && !first.contains("wrong/Entry.java"), "only the pinned Java caller with the matching receiver type reaches the first request");

        write("entry/Entry.java", caller);
        write("policy/Policy.java", policy);
        commit("base without a caller guard");
        write("policy/Policy.java", policy.replace("return allowed", "return true"));
        commit("apply the same predicate change");
        var unguarded = review();
        var second = Files.readString(unguarded.resolve("focused/F1-prompt.md"));
        check(Main.reviewExitCode(unguarded) == 0 && calls(unguarded, 1) && second.contains("policy.authorize(permitted)")
                && !second.contains("pinned guard"), "the same Java diff carries the unguarded caller without extra initial requests");
    }

    private void expansion() throws Exception {
        write("auth/check.py", "def authorize(approved):\n    return approved\n");
        write("queue/schedule.py", "APPROVED_ONLY = True\nlimit = 1\n");
        write("work/other.py", "EXTRANEOUS_CONTEXT = 1\n");
        commit("base");
        write("auth/check.py", "def authorize(approved):\n    return True\n");
        write("queue/schedule.py", "APPROVED_ONLY = True\nlimit = 2\n");
        write("work/other.py", "EXTRANEOUS_CONTEXT = 2\n");
        commit("expand only one candidate across scopes");
        Files.writeString(root.resolve("expand.xml"), """
                <adaptive-review><route>DEEP</route><summary>The caller contract needs checking.</summary><decisions>
                  <local-decision><status>INSUFFICIENT_EVIDENCE</status>
                    <reason>Check the work admission rule in queue/schedule.py.</reason>
                    <finding><title>The scheduler may still block this request</title><path>auth/check.py</path>
                    <line>2</line><endLine>2</endLine><trigger>Unapproved work reaches authorize</trigger>
                    <impact>Rejected work could proceed</impact><evidence>The target returns true</evidence>
                    <diffRelation>The predicate changed</diffRelation></finding>
                  </local-decision>
                </decisions></adaptive-review>
                """);
        var run = review();
        var verify = Files.readString(run.resolve("focused/F1/F1-1-prompt.md"));
        var focusedPrompt = Files.readString(run.resolve("focused/F1-prompt.md"));
        check(Main.reviewExitCode(run) == 0 && calls(run, 3) && verify.contains("APPROVED_ONLY")
                && focusedPrompt.contains("Use DEEP with an INSUFFICIENT_EVIDENCE decision")
                && !focusedPrompt.contains("For DEEP, supply a summary and <decisions/>.")
                && verify.contains("A method's comment or a guard in a caller is IMPLEMENTATION evidence")
                && !verify.contains("EXTRANEOUS_CONTEXT") && !Files.exists(run.resolve("discovery"))
                && Files.readString(run.resolve("coverage.md")).contains("Expanded focused/F1/F1-1 with collected implementation: queue/schedule.py.")
                && !Files.readString(run.resolve("report.md")).contains("scheduler may still block"),
                "requested extra scope reuses relevant evidence for only one independently verified candidate");
        pipeline.run(options("--resume", run.toString()));
        check(calls(run, 0), "targeted expansion can reuse its validated checkpoint");

        var response = root.resolve("expand.xml");
        Files.writeString(response, Files.readString(response).replace("queue/schedule.py", "queue/missing.py"));
        var unavailable = review();
        check(calls(unavailable, 2) && !Files.exists(unavailable.resolve("focused/F1/F1-1-prompt.md"))
                && Files.readString(unavailable.resolve("coverage.md")).contains("scope expansion: Stopped; no new evidence")
                && Files.readString(unavailable.resolve("report.md")).contains("The scheduler may still block this request"),
                "an expansion without relevant new evidence retains the gap and does not launch a model call");

        Files.writeString(response, "<adaptive-review><route>DEEP</route>"
                + "<summary>No concrete candidate can be anchored.</summary><decisions/></adaptive-review>");
        var unassessed = review();
        check(Main.reviewExitCode(unassessed) == 3 && calls(unassessed, 2)
                && Files.readString(unassessed.resolve("review-status.md")).contains("focused/F1 reported an unassessed scope"),
                "DEEP without a concrete candidate reports unfinished coverage without extra model calls");
    }

    private void write(String relative, String content) throws Exception {
        var file = repository.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private void stalledEvidence() throws Exception {
        write("auth/check.py", "def authorize(approved):\n    return approved\n");
        write("queue/schedule.py", "LIMIT = 1\n");
        write("queue/producer.py", "PRODUCER_DELEGATES_TO_CONSUMER = True\n");
        write("queue/consumer.py", "CONSUMER_BLOCKS_UNAPPROVED = True\n");
        commit("base");
        write("auth/check.py", "def authorize(approved):\n    return True\n");
        write("queue/schedule.py", "LIMIT = 2\n");
        commit("changed authorization and scheduling");
        Files.writeString(root.resolve("stalled-xml"), """
                <adaptive-review><route>DEEP</route><summary>Review the downstream guard.</summary><decisions>
                  <local-decision><status>INSUFFICIENT_EVIDENCE</status>
                    <reason>Check the caller in queue/schedule.py.</reason>
                    <finding><title>Unapproved work may pass the guard</title><path>auth/check.py</path>
                    <line>2</line><endLine>2</endLine><trigger>Unapproved request reaches authorize</trigger>
                    <impact>Unapproved work runs</impact><evidence>The target returns true.</evidence>
                    <diffRelation>The predicate changed.</diffRelation></finding>
                  </local-decision>
                </decisions></adaptive-review>
                """);
        var stopped = review();
        check(Main.reviewExitCode(stopped) == 0 && calls(stopped, 4)
                && Files.readString(stopped.resolve("coverage.md")).contains("Stopped; the verdict cites no newly supplied evidence")
                && Files.readString(stopped.resolve("report.md")).contains("Unapproved work may pass the guard"),
                "a speculative third check preserves the known gap without launching another call");

        Files.createFile(root.resolve("cite-producer"));
        var continued = review();
        check(Main.reviewExitCode(continued) == 0 && calls(continued, 5)
                && Files.readString(continued.resolve("focused/F1/round-3/F1-1-prompt.md")).contains("CONSUMER_BLOCKS_UNAPPROVED")
                && !Files.readString(continued.resolve("report.md")).contains("Unapproved work may pass the guard"),
                "a new pinned producer citation justifies continuing to the decisive consumer evidence");
    }

    private void navigationEvidence() throws Exception {
        write("auth/check.py", "def authorize(approved):\n    return approved\n");
        write("queue/caller.py", "def schedule(approved):\n    if not approved:\n        return 'CALLER_GUARD_EVIDENCE'\n    return rules.authorize(approved)  # approval-rules.json\n");
        write("queue/near_match.py", "def schedule(approved):\n    return rules.authorize_later(approved)\n");
        write("elsewhere/unrelated.py", "def schedule(approved):\n    return rules.authorize(approved)  # OUT_OF_SCOPE_REFERENCE\n");
        write("other.py", "OTHER_MATERIAL = 1\n");
        commit("base");
        write("auth/check.py", "def authorize(approved):\n    return True\n");
        commit("change the permission check");
        write("queue/caller.py", "WORKTREE_ONLY = rules.authorize(True)\n");
        Path response = root.resolve("navigation.xml");
        String candidate = """
                <adaptive-review><route>DEEP</route><summary>Check admission.</summary><decisions>
                  <local-decision><status>INSUFFICIENT_EVIDENCE</status>
                    <reason>Check whether the caller guards unapproved work.</reason>
                    <finding><title>Unapproved work may proceed</title><path>auth/check.py</path>
                    <line>2</line><endLine>2</endLine><trigger>Unapproved work reaches authorize</trigger>
                    <impact>Unchecked work executes</impact><evidence>The target returns true.</evidence>
                    <diffRelation>The predicate changed.</diffRelation></finding>
                    <requests>%s</requests>
                  </local-decision>
                </decisions></adaptive-review>
                """;
        Files.writeString(response, candidate.formatted("REQUEST_FILE|target|queue|index|Find possible callers"));
        var index = review();
        check(Main.reviewExitCode(index) == 0 && calls(index, 1)
                && Files.readString(index.resolve("coverage.md")).contains("INDEX_ONLY")
                && Files.readString(index.resolve("report.md")).contains("Unapproved work may proceed"),
                "a file listing alone leaves the candidate pending without a model call");

        Files.writeString(response, candidate.formatted("REQUEST_FILE|target|other.py|all|Read an implementation file"));
        var verifierIndex = review();
        check(Main.reviewExitCode(verifierIndex) == 0 && calls(verifierIndex, 2)
                && Files.readString(verifierIndex.resolve("coverage.md")).contains("INDEX_ONLY")
                && !Files.exists(verifierIndex.resolve("focused/F1/round-2/F1-1-prompt.md")),
                "a verifier's file listing does not start another round");

        Files.writeString(response, candidate.formatted("REQUEST_FILE|target|queue/**/*.py|references:rules.authorize|Find the caller and its approval guard"));
        var located = review();
        var prompt = Files.readString(located.resolve("focused/F1/F1-1-prompt.md"));
        check(Main.reviewExitCode(located) == 0 && calls(located, 2)
                && prompt.contains("CALLER_GUARD_EVIDENCE") && !prompt.contains("WORKTREE_ONLY")
                && !prompt.contains("authorize_later") && !prompt.contains("OUT_OF_SCOPE_REFERENCE")
                && !Files.readString(located.resolve("report.md")).contains("Unapproved work may proceed"),
                "locally located pinned caller context can disprove a candidate in one verification request");

        Files.writeString(response, candidate.formatted("REQUEST_FILE|target|queue/**/*.py|references:approval-rules.json|Find the rule consumer"));
        var resource = review();
        check(Main.reviewExitCode(resource) == 0 && calls(resource, 2)
                && Files.readString(resource.resolve("focused/F1/F1-1-prompt.md")).contains("CALLER_GUARD_EVIDENCE"),
                "a hyphenated resource name can locate pinned source excerpts");
    }

    private void commit(String message) throws Exception {
        git("add", ".");
        git("commit", "-qm", message);
    }

    private void git(String... args) throws Exception {
        var command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        var result = executor.run(command, repository, null, Duration.ofSeconds(10));
        check(result.exitCode() == 0, result.stderr());
    }

    private Path review(String... additional) throws Exception { return pipeline.run(options(additional)); }

    private CliOptions options(String... additional) {
        var args = new ArrayList<>(List.of("review", "--repo", repository.toString(), "--output-dir", root.resolve("runs").toString(),
                "--agent-command", "/bin/sh", "--agent-arg", model.toString(), "--agent-arg", root.toString(),
                "--review-parallelism", "1", "--agent-retries", "0"));
        args.addAll(List.of(additional));
        return CliOptions.parse(args.toArray(String[]::new));
    }

    private static boolean calls(Path run, int count) throws Exception {
        return Files.readString(run.resolve("timings.md")).contains("Actual launched attempts: " + count + "\n");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
