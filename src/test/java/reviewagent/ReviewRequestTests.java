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
                          *)
                            ref=$(printf '%s\\n' "$prompt" | sed -n 's/^- \\(SRC-[0-9]*\\): other.py at .*/\\1/p' | head -n 1)
                            printf '<verification><status>INSUFFICIENT_EVIDENCE</status><reason>The supplied implementation has no caller guard. The caller remains unlocated.</reason>'
                            printf '<previousPremise>REFUTED</previousPremise><citations><citation><source>%s</source><lines>1:1</lines></citation></citations>' "$ref"
                            printf '</verification>\\nREQUEST_FILE|target|queue|index|Find the caller\\n' ;;
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
                            if [ -f "$fixture/answered-producer" ]; then
                                printf '<previousPremise>SUPPORTED</previousPremise>'
                            fi
                            printf '</verification>\\nREQUEST_FILE|target|queue/consumer.py|all|Does the consumer block unapproved work?\\n'
                            if [ -f "$fixture/require-call-entry" ]; then
                                printf 'REQUEST_FILE|target|queue/entry.py|symbol:dispatch|Can an unapproved job reach the consumer?\\n'
                            fi ;;
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
            new ReviewRequestTests(Files.createDirectory(fixture.resolve("discovery-preflight"))).discoveryPreflight();
            new ReviewRequestTests(Files.createDirectory(fixture.resolve("discovery-navigation"))).discoveryNavigation();
            new ReviewRequestTests(Files.createDirectory(fixture.resolve("discovery-handoff"))).discoveryHandoff();
            new ReviewRequestTests(Files.createDirectory(fixture.resolve("verification-ready"))).verificationReady();
            new ReviewRequestTests(Files.createDirectory(fixture.resolve("discovery-budget"))).discoveryBudget();
            new ReviewRequestTests(Files.createDirectory(fixture.resolve("repository-rules"))).repositoryRules();
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
        var prompt = Files.readString(reviewed.resolve("focused/O-prompt.md"));
        check(Main.reviewExitCode(reviewed) == 0 && calls(reviewed, 1)
                && prompt.contains("large edit") && prompt.contains("second edit") && prompt.contains("third edit"),
                "the first overview packs whole hunks into one request when their total fits");

        write("a.txt", "new large edit\n".repeat(4600));
        write("b.txt", "new second edit\n".repeat(4500));
        write("c.txt", "new third edit\n".repeat(4300));
        commit("several large hunks");
        var oversized = review();
        check(Main.reviewExitCode(oversized) == 3
                && Files.readString(oversized.resolve("review-status.md")).contains("Unreviewed change"),
                "a hunk larger than a single request is still disclosed");
        String first = Files.readString(oversized.resolve("focused/O-prompt.md"));
        String next = Files.readString(oversized.resolve("focused/T-prompt.md"));
        String firstPath = first.contains("- b.txt\n") ? "b.txt" : "c.txt";
        String nextPath = firstPath.equals("b.txt") ? "c.txt" : "b.txt";
        check(first.contains("- " + firstPath + "\n") && next.contains("- " + nextPath + "\n")
                && !first.contains(nextPath) && !next.contains(firstPath)
                && !first.contains("a.txt") && !next.contains("a.txt"),
                "each packed scope gives the model only the eligible paths for that scope");
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
        var security = Files.readString(run.resolve("discovery/R5-prompt.md"));
        var conversion = Files.readString(run.resolve("discovery/R6-prompt.md"));
        check(correct.contains("### Plain.java at ") && !contract.contains("### Plain.java at ") && !boundary.contains("### Plain.java at ")
                && contract.contains("+    int value() { return 3; }") && boundary.contains("+    int value() { return 3; }")
                && contract.contains("### docs/design.md at ") && !boundary.contains("### Guard.java at ")
                && !security.contains("### Guard.java at ") && !conversion.contains("### Guard.java at ")
                && List.of(boundary, security, conversion).stream().allMatch(
                        prompt -> prompt.contains("+    boolean authorize(boolean approved) { return approved; }"))
                && contract.contains("boundaryValue = 190923") && boundary.contains("boundaryValue = 190923"),
                "focused discovery drops duplicate views but retains all changed lines and context outside the diff");
        check(!Files.exists(run.resolve("discovery/R4-prompt.md"))
                && correct.contains("returned result or persisted state")
                && contract.contains("consumer's contract") && contract.contains("compatibility aliases")
                && security.contains("identity and untrusted data") && security.contains("affected read, write or renderer")
                && boundary.contains("Failure handling and recovery.") && boundary.contains("partial success")
                && conversion.contains("Boundary inputs and data conversion.") && conversion.contains("format round trips")
                && conversion.contains("boundaryValue = 190923")
                && !boundary.contains("Boundary inputs and data conversion.") && !conversion.contains("Failure handling and recovery.")
                && !boundary.contains("Security and isolation.")
                && security.contains("Security and isolation.") && !security.contains("Failure handling and recovery."),
                "recovery and conversion are distinct roles, transferred checks have evidence instructions, and requirements are opt-in by input");
        String boundaryInstructions = boundary.substring(0, boundary.indexOf("EVIDENCE FOLLOWS"));
        check(boundaryInstructions.indexOf("REQUIRED RESPONSE") < boundaryInstructions.indexOf("SCOPE")
                && boundaryInstructions.contains("fenced <findings/> block")
                && boundaryInstructions.contains("VERIFY_QUESTION|N|")
                && boundaryInstructions.contains("set endLine equal to line")
                && boundaryInstructions.contains("Cite other participating files in evidence")
                && boundaryInstructions.contains("Java helpers: use REQUEST_FILE")
                && !boundaryInstructions.contains("Go helpers:")
                && !boundaryInstructions.contains("Use these requirement assessment labels")
                && !boundaryInstructions.contains("Each candidate must include:"),
                "the R3 prompt leads with a single response contract and advertises only relevant language helpers");
        check(boundaryInstructions.contains(EvidenceRequest.PATH_GUIDANCE)
                && boundaryInstructions.contains("`REQUEST_FILE|target|{tracked path}|{first:last}|[finding:1]")
                && boundaryInstructions.contains("`REQUEST_FILE|base|{tracked path}|all|")
                && EvidenceRequest.parse(boundaryInstructions).isEmpty(),
                "discovery shows template fields, candidate ownership, and real navigation syntax without raw sample paths");
        Path requirement = root.resolve("requirement.md");
        Files.writeString(requirement, "Return the configured limit in the public response.\n");
        var withRequirement = review("--prepare-only", "--review-mode", "deep", "--language", "zh-CN",
                "--requirement", requirement.toString());
        String requirements = Files.readString(withRequirement.resolve("discovery/R4-prompt.md"));
        check(requirements.contains("Requirements compliance.")
                && requirements.contains("Return the configured limit in the public response.")
                && requirements.contains("identify the explicit rule and the changed implementation")
                && !requirements.contains("Business contracts and compatibility.")
                && List.of(correct, contract, boundary, security, conversion, requirements).stream().allMatch(prompt ->
                        prompt.indexOf("REQUIRED RESPONSE") < prompt.indexOf("SCOPE")
                                && prompt.contains("VERIFY_QUESTION|N|")
                                && !prompt.contains("Use these requirement assessment labels"))
                && correct.contains("plain CHANGE_SUMMARY| line")
                && List.of(contract, boundary, security, conversion, requirements).stream().noneMatch(prompt -> prompt.contains("plain CHANGE_SUMMARY| line")),
                "each role has a self-contained assignment and a candidate response contract");
        String localizedBoundary = Files.readString(withRequirement.resolve("discovery/R3-prompt.md"));
        check(localizedBoundary.startsWith(boundaryInstructions)
                && localizedBoundary.contains("Return the configured limit in the public response."),
                "R3 still receives the supplied requirement and keeps its English instructions for Chinese reports");
    }

    private void discoveryPreflight() throws Exception {
        write("worker.py", "result = 0\n");
        write("policy/guard.txt", "# guard example\n".repeat(289) + "PINNED_PREFLIGHT_GUARD\nOUTSIDE_REQUESTED_RANGE\n");
        write("policy/contract.txt", "PINNED_EARLY_CONTRACT\n");
        write("policy/late.txt", "PINNED_LATE_CONTRACT\n");
        write("policy/late-extra.txt", "PINNED_SECOND_LATE_CONTRACT\n");
        commit("base");
        write("worker.py", "result = 1\n");
        commit("change worker behavior");
        write("policy/guard.txt", "WORKTREE_ONLY_GUARD\n");
        Files.writeString(model, """
                fixture=$1
                prompt=$(cat)
                request() {
                  printf '%s\\n' '<findings/>' 'DISCOVERY_NEEDS_EVIDENCE|Does the worker guard reject the input?' \\
                    'REQUEST_FILE|target|policy|index|Locate the pinned policy' \\
                    'REQUEST_FILE|target|policy/guard.txt|90:290|Read the pinned guard'
                  if [ -f "$fixture/missing" ]; then
                    printf '%s\\n' 'REQUEST_FILE|target|policy/missing.txt|all|Read the other required guard'
                  fi
                  if [ -f "$fixture/bad-request" ]; then
                    printf '%s\\n' 'REQUEST_LANGUAGE|target|python|sample/path.py|policy|index|Locate the policy' \\
                      'REQUEST_FILE|target|sample/policy|index|Locate the alternative policy' \\
                      'REQUEST_FILE|target|policy/guard.txt|all'
                  fi
                  if [ -f "$fixture/parallel" ]; then printf '%s\\n' 'CHANGE_SUMMARY|Incomplete summary' '```'; fi
                }
                case "$prompt" in
                  *'Failure handling and recovery.'*)
                    case "$prompt" in
                      *'PINNED_PREFLIGHT_GUARD'*)
                        touch "$fixture/restarted"
                        if [ -f "$fixture/early" ]; then
                          while [ ! -f "$fixture/supplement-started" ]; do sleep 0.02; done
                          printf '%s\\n' '<findings/>' 'REQUEST_FILE|target|policy/late*.txt|glob|Inspect both contracts'
                        elif [ -f "$fixture/repeat" ]; then request
                        elif [ -f "$fixture/missing" ]; then
                          printf '%s\n' '<findings><finding><title>Worker return value changes</title><severity>LOW</severity><path>worker.py</path><line>1</line><endLine>1</endLine><trigger>The worker is evaluated.</trigger><impact>The value is one.</impact><evidence>worker.py:1 sets the result.</evidence><diffRelation>The patch changes zero to one.</diffRelation></finding></findings>'
                        else printf '<findings/>'; fi ;;
                      *) request ;;
                    esac ;;
                  *'Correctness and regressions.'*)
                    if [ -f "$fixture/parallel" ]; then
                      while [ ! -f "$fixture/restarted" ]; do sleep 0.02; done
                    fi
                    printf '%s\\n' '<findings/>'
                    if [ -f "$fixture/early" ]; then
                      printf '%s\\n' 'REQUEST_FILE|target|policy/contract.txt|all|Inspect the shared contract'
                    fi ;;
                  *'Inspect the supplemental evidence'*)
                    [ -f "$fixture/early" ] || exit 90
                    case "$prompt" in
                      *'PINNED_LATE_CONTRACT'*) ;;
                      *'PINNED_EARLY_CONTRACT'*) touch "$fixture/supplement-started" ;;
                      *) exit 92 ;;
                    esac
                    printf '<findings/>' ;;
                  'You are the discovery stage'*) printf '<findings/>' ;;
                  'You are the independent candidate verification'*)
                    printf '<verification><status>REJECTED</status><reason>The return value is intentional.</reason></verification>' ;;
                  *) exit 91 ;;
                esac
                """);
        Files.createFile(root.resolve("parallel"));
        var run = pipeline.run(options(false, "--review-mode", "deep", "--agent-timeout-seconds", "6"));
        Path restarted = run.resolve("discovery/round-2/R3-prompt.md");
        String prompt = Files.readString(restarted);
        check(Main.reviewExitCode(run) == 0 && calls(run, 6)
                && prompt.contains("PINNED_PREFLIGHT_GUARD") && !prompt.contains("WORKTREE_ONLY_GUARD")
                && prompt.contains("(lines 90–290)") && !prompt.contains("OUTSIDE_REQUESTED_RANGE")
                && prompt.contains("+result = 1")
                && !Files.exists(run.resolve("discovery/round-2/R1-prompt.md"))
                && !Files.exists(run.resolve("discovery/S1-prompt.md")),
                "the same initial role restarts with the complete 201-line pinned range while another role is running");
        pipeline.run(options(false, "--review-mode", "deep", "--agent-timeout-seconds", "6", "--resume", run.toString()));
        check(calls(run, 0), "preflight and resumed initial results both reuse checkpoints");
        Files.delete(root.resolve("parallel"));

        Files.createFile(root.resolve("early"));
        var overlapped = pipeline.run(options(false, "--review-mode", "deep", "--agent-timeout-seconds", "6"));
        check(Main.reviewExitCode(overlapped) == 0 && calls(overlapped, 8)
                && Files.exists(root.resolve("supplement-started"))
                && Files.readString(overlapped.resolve("discovery/S1-prompt.md")).contains("PINNED_EARLY_CONTRACT")
                && !Files.readString(overlapped.resolve("discovery/S1-prompt.md")).contains("PINNED_LATE_CONTRACT")
                && Files.readString(overlapped.resolve("discovery/S2-prompt.md")).contains("PINNED_LATE_CONTRACT")
                && Files.readString(overlapped.resolve("discovery/S2-prompt.md")).contains("PINNED_SECOND_LATE_CONTRACT"),
                "independent supplemental discovery starts before the slow reviewer returns and later evidence includes complete matching files");
        Files.delete(root.resolve("early"));

        Files.createFile(root.resolve("missing"));
        var blocked = review("--review-mode", "deep");
        String partialPrompt = Files.readString(blocked.resolve("discovery/round-2/R3-prompt.md"));
        check(Main.reviewExitCode(blocked) == 3
                && Files.readString(blocked.resolve("review-status.md")).contains("policy/missing.txt")
                && partialPrompt.contains("Unavailable: REQUEST_FILE|target|policy/missing.txt")
                && partialPrompt.contains("PINNED_PREFLIGHT_GUARD")
                && Files.readString(blocked.resolve("candidates.md")).contains("Worker return value changes")
                && !Files.exists(blocked.resolve("discovery/S1-prompt.md")),
                "partial delivery advances the original reviewer, keeps supported candidates, and preserves the coverage gap");
        Files.delete(root.resolve("missing"));
        Files.createFile(root.resolve("bad-request"));
        var invalid = review("--review-mode", "deep");
        String coverage = Files.readString(invalid.resolve("coverage.md"));
        String status = Files.readString(invalid.resolve("review-status.md"));
        check(Main.reviewExitCode(invalid) == 3 && calls(invalid, 6)
                && coverage.contains("target policy/guard.txt 90:290: INCLUDED")
                && status.contains("context: expected a tracked Python source")
                && status.contains("path: no tracked directory at the selected revision")
                && status.contains("reason: missing required field")
                && Files.exists(invalid.resolve("discovery/round-2/R3-prompt.md")),
                "initial discovery keeps valid pinned sources and identifies malformed fields without claiming coverage");
        Files.delete(root.resolve("bad-request"));
        Files.createFile(root.resolve("repeat"));
        var repeated = review("--review-mode", "deep");
        check(Main.reviewExitCode(repeated) == 3 && calls(repeated, 6)
                && Files.readString(repeated.resolve("review-status.md")).contains("evidence already supplied")
                && !Files.exists(repeated.resolve("discovery/round-3")),
                "a repeated material request without new evidence stops the affected reviewer");

        Files.writeString(root.resolve("tool-reply.txt"), "]<]provider[>[<tool_call>\n<invoke>UNTRUSTED_TOOL_REPLY</invoke>\n</tool_call>\n");
        Files.writeString(model, """
                fixture=$1
                prompt=$(cat)
                case "$prompt" in
                  *UNTRUSTED_TOOL_REPLY*) exit 90 ;;
                  'You are the discovery stage'*'Failure handling and recovery.'*)
                    case "$prompt" in *'You have no tools. Do not call tools, emit tool calls,'*) ;; *) exit 91 ;; esac
                    if [ -f "$fixture/always-tool" ] || [ ! -f "$fixture/returned-tool" ]; then
                      touch "$fixture/returned-tool"
                      cat "$fixture/tool-reply.txt"
                    else
                      case "$prompt" in
                        *'PINNED_PREFLIGHT_GUARD'*) printf '<findings/>' ;;
                        *) printf '%s\\n' '<findings/>' 'DISCOVERY_NEEDS_EVIDENCE|Does the worker guard reject the input?' \\
                           'REQUEST_FILE|target|policy/guard.txt|all|Read the pinned guard' ;;
                      esac
                    fi ;;
                  'You are the discovery stage'*) printf '<findings/>' ;;
                  *) exit 92 ;;
                esac
                """);
        var recovered = pipeline.run(options(false, "--review-mode", "deep"));
        Path first = recovered.resolve("discovery");
        check(Main.reviewExitCode(recovered) == 0 && calls(recovered, 7)
                && Files.readString(first.resolve("R3-stdout.log")).contains("UNTRUSTED_TOOL_REPLY")
                && Files.readString(first.resolve("R3-restart-attempts/attempt-0001/call-prompt.md"))
                        .equals(Files.readString(first.resolve("R3-prompt.md")))
                && Files.readString(first.resolve("R3-response.md")).contains("DISCOVERY_NEEDS_EVIDENCE|")
                && Files.readString(first.resolve("round-2/R3-prompt.md")).contains("PINNED_PREFLIGHT_GUARD")
                && !Files.exists(first.resolve("R1-restart-attempts")),
                "only the role that returned a tool reply restarts, then uses the normal pinned evidence handoff");
        pipeline.run(options(false, "--review-mode", "deep", "--resume", recovered.toString()));
        check(Main.reviewExitCode(recovered) == 0 && calls(recovered, 0),
                "a validated restarted reviewer and its evidence round are reusable on resume");

        Files.createFile(root.resolve("always-tool"));
        var persistent = review("--review-mode", "deep");
        check(Main.reviewExitCode(persistent) == 3 && calls(persistent, 6)
                && Files.readString(persistent.resolve("review-status.md")).contains("discovery/R3 [INVALID_OUTPUT]")
                && Files.isRegularFile(persistent.resolve("discovery/R3-restart-response.md")),
                "a repeated tool reply gets one restart and leaves that reviewer incomplete");
        var atBudget = review("--review-mode", "deep", "--agent-max-calls-per-task", "1");
        check(Main.reviewExitCode(atBudget) == 3 && calls(atBudget, 5)
                && Files.readString(atBudget.resolve("review-status.md")).contains("discovery/R3 [INVALID_OUTPUT]")
                && !Files.exists(atBudget.resolve("discovery/R3-restart-attempts")),
                "the restart uses the original task call budget");
    }

    private void discoveryNavigation() throws Exception {
        write("service/worker.py", "result = 0\n");
        write("service/backend/routes.py", "def run_worker():\n    return PINNED_ROUTE_RESULT\n");
        commit("base");
        write("service/worker.py", "result = 1\n");
        commit("change worker behavior");
        write("service/backend/routes.py", "def run_worker():\n    return WORKTREE_ONLY_RESULT\n");
        Files.writeString(model, """
                fixture=$1
                prompt=$(cat)
                case "$prompt" in
                  *'Failure handling and recovery.'*)
                    case "$prompt" in
                      *'Previous material check (question only)'*)
                        case "$prompt" in *PINNED_ROUTE_RESULT*) ;; *) exit 90 ;; esac
                        location='service/backend/routes.py:1'
                        if [ -f "$fixture/wrong-line" ]; then location='service/backend/routes.py:100'; fi
                        printf '<findings><finding><title>Worker return value changes</title><severity>LOW</severity><path>service/worker.py</path><line>1</line><endLine>1</endLine><trigger>The worker is evaluated.</trigger><impact>The value is one.</impact><evidence>The caller is at %s.</evidence><diffRelation>The patch changes zero to one.</diffRelation></finding></findings>' "$location" ;;
                      *)
                        premise=1
                        if [ -f "$fixture/other-premise" ]; then premise=2; fi
                        printf '%s\\n' '<findings/>' 'DISCOVERY_NEEDS_EVIDENCE|Which entry can invoke the worker?' \\
                          'REQUEST_FILE|target|service/backend/api|index|[finding:1] [premise:1] Locate the caller' \\
                          "REQUEST_FILE|target|service/backend/api|references:run_worker|[finding:1] [premise:$premise] Find the caller definition" ;;
                    esac ;;
                  'You are the discovery stage'*) printf '<findings/>' ;;
                  'You are the independent candidate verification'*)
                    printf '<verification><status>REJECTED</status><reason>The return value is intentional.</reason></verification>' ;;
                  *) exit 91 ;;
                esac
                """);
        var recovered = review("--review-mode", "deep");
        var prompt = Files.readString(recovered.resolve("discovery/round-2/R3-prompt.md"));
        check(Main.reviewExitCode(recovered) == 0 && calls(recovered, 7)
                && prompt.contains("PINNED_ROUTE_RESULT") && !prompt.contains("WORKTREE_ONLY_RESULT")
                && prompt.contains("service/backend/api -> service/backend")
                && Files.readString(recovered.resolve("coverage.md")).contains("Unavailable directory index superseded in this review"),
                "a missing directory falls back to a pinned parent search and a cited result closes the navigation gap");
        for (String marker : List.of("wrong-line", "other-premise")) {
            Files.createFile(root.resolve(marker));
            var unresolved = review("--review-mode", "deep");
            check(Main.reviewExitCode(unresolved) == 3 && calls(unresolved, 7)
                    && Files.readString(unresolved.resolve("review-status.md")).contains("service/backend/api|index"),
                    "a source from another premise or an unprovided line cannot close the failed navigation");
            Files.delete(root.resolve(marker));
        }
    }

    private void discoveryHandoff() throws Exception {
        write("alpha.py", "result = 0\n");
        write("beta.py", "result = 0\n");
        write("delivery_policy.txt", "PINNED_POLICY: unfinished items are blocked\n");
        write("recovery/worker.py", "def resume_delivery():\n    return 'PINNED_RECOVERY'\n");
        commit("base");
        write("alpha.py", "result = 1\n");
        write("beta.py", "result = 1\n");
        commit("change two entry points");

        String finding = """
                <finding><title>%s</title><severity>MEDIUM</severity><path>%s</path><line>1</line><endLine>1</endLine>
                  <trigger>Unfinished work reaches the entry point.</trigger><impact>It may be delivered.</impact>
                  <evidence>The entry point changes its return value.</evidence><diffRelation>The return value is new.</diffRelation>
                </finding>
                """;
        String alpha = finding.formatted("First delivery concern", "alpha.py");
        String beta = finding.formatted("Separate delivery concern", "beta.py");
        String request = "REQUEST_FILE|target|delivery_policy.txt|all|";
        Files.writeString(model, """
                fixture=$1
                prompt=$(cat)
                rejected='<status>REJECTED</status><reason>The checked path blocks this scenario.</reason>'
                pending='<status>INSUFFICIENT_EVIDENCE</status><reason>The delivery rule is still unknown.</reason><nextCheck>Does the policy allow unfinished work?</nextCheck>'
                request='REQUEST_FILE|target|delivery_policy.txt|all|Read the delivery policy'
                case "$prompt" in
                  *'Correctness and regressions. Trace control flow'*) cat "$fixture/findings.xml" ;;
                  *'Inspect the supplemental evidence'*)
                    if [ -f "$fixture/bad-supplemental" ]; then printf 'No response block.'
                    else printf '<findings/>'; fi ;;
                  'You are the discovery stage'*) printf '<findings/>' ;;
                  'You are the independent candidate verification batch'*)
                    if [ -f "$fixture/recover-batch-gap" ]; then
                      printf '<verifications>'
                      for id in R1-1 R1-2; do
                        printf '<verification id="%s">%s<relation>未确定</relation><requests>%s</requests></verification>' "$id" "$pending" "$request"
                      done
                      printf '</verifications>'
                      exit
                    fi
                    printf '<verifications><verification id="R1-1">'
                    case "$prompt" in
                      *'PINNED_POLICY:'*) printf '%s' "$rejected" ;;
                      *) printf '%s<requests>%s</requests>' "$pending" "$request" ;;
                    esac
                    printf '</verification><verification id="R1-2">%s</verification></verifications>' "$rejected" ;;
                  'You are the independent candidate verification stage'*)
                    case "$prompt" in *'CHECK EVIDENCE FIRST'*'VERIFICATION TASK'*) ;; *) exit 92 ;; esac
                    case "$prompt" in
                      *'<title>Separate delivery concern</title>'*)
                        if [ ! -f "$fixture/recover-batch-gap" ]; then
                          case "$prompt" in *'PINNED_POLICY:'*) exit 91 ;; esac
                        fi
                        printf '<verification>%s</verification>' "$rejected" ;;
                      *'PINNED_POLICY:'*) printf '<verification>%s</verification>' "$rejected" ;;
                      *'REQUEST_FILE|base|retention_agreement.txt|all — unavailable:'*)
                        printf '<verification>%s</verification>' "$pending" ;;
                      *) printf '<verification>%s</verification>\\n%s\\n' "$pending" "$request" ;;
                    esac ;;
                  *) exit 91 ;;
                esac
                """);
        Path reply = root.resolve("findings.xml");
        String findings = "<findings>\n" + alpha + beta + "</findings>\n";
        Files.writeString(reply, findings + request + "Read the delivery policy\n");
        var baseline = review("--review-mode", "deep");
        check(Main.reviewExitCode(baseline) == 0 && calls(baseline, 9)
                && Files.exists(baseline.resolve("verification/round-2/R1-1-response.md")),
                "an unmarked discovery request retains the existing verification follow-up");

        String question = "Can unfinished items reach public delivery?";
        Files.writeString(reply, findings + "VERIFY_QUESTION|1|" + question + "\n"
                + request + "[finding:1] Check whether unfinished work is blocked\n"
                + "REQUEST_FILE|base|delivery_policy.txt|all|[finding:1] Compare the baseline rule\n"
                + "REQUEST_FILE|target|delivery_policy.txt|1:1|[finding:19] Invalid candidate number\n");
        var delivered = review("--review-mode", "deep");
        String firstPrompt = Files.readString(delivered.resolve("verification/R1-1-prompt.md"));
        check(Main.reviewExitCode(delivered) == 0 && calls(delivered, 8)
                && firstPrompt.contains("PINNED_POLICY:")
                && firstPrompt.contains("REQUEST_FILE|base|delivery_policy.txt|all — supplied as SRC-")
                && firstPrompt.contains("REQUEST_FILE|target|delivery_policy.txt|all — supplied as SRC-")
                && !Files.readString(delivered.resolve("verification/R1-2-prompt.md")).contains("PINNED_POLICY:")
                && !Files.exists(delivered.resolve("verification/round-2")),
                "the real pipeline sends previously collected evidence to its owner and saves one call");
        check(firstPrompt.contains(question)
                && !Files.readString(delivered.resolve("verification/R1-2-prompt.md")).contains(question),
                "the discovery question appears only in the owning verification request");
        for (var run : List.of(baseline, delivered)) {
            String coverage = Files.readString(run.resolve("coverage.md"));
            check(coverage.contains("- R1-1 — REJECTED. First delivery concern")
                    && coverage.contains("- R1-2 — REJECTED. Separate delivery concern"),
                    "both runs preserve the independent verdict for each candidate");
        }

        String planned = findings + """
                VERIFY_PREMISE|1|1|Does the entry guard block unfinished work?
                REQUEST_FILE|target|delivery_policy.txt|all|[finding:1] [premise:1] Check the entry guard
                VERIFY_PREMISE|1|2|Can a recovery path still deliver the work?
                REQUEST_FILE|target|delivery_policy.txt|all|[finding:1] [premise:2] Check the recovery rule
                REQUEST_FILE|target|recovery|references:resume_delivery|[finding:1] [premise:2] Locate the recovery behavior
                VERIFY_PREMISE|1|3|Is an external override supported?
                VERIFY_PREMISE|19|1|Unrelated premise beyond the candidate count
                """;
        Files.writeString(reply, planned);
        var byPremise = review("--review-mode", "deep");
        String plannedPrompt = Files.readString(byPremise.resolve("verification/R1-1-prompt.md"));
        String otherPrompt = Files.readString(byPremise.resolve("verification/R1-2-prompt.md"));
        int guard = plannedPrompt.indexOf("Premise 1 — Does the entry guard block unfinished work?");
        int recovery = plannedPrompt.indexOf("Premise 2 — Can a recovery path still deliver the work?");
        int override = plannedPrompt.indexOf("Premise 3 — Is an external override supported?");
        check(Main.reviewExitCode(byPremise) == 0 && calls(byPremise, 8) && guard >= 0 && recovery > guard && override > recovery
                && plannedPrompt.substring(guard, recovery).contains("REQUEST_FILE|target|delivery_policy.txt|all — supplied as SRC-")
                && plannedPrompt.substring(recovery, override).contains("REQUEST_FILE|target|delivery_policy.txt|all — supplied as SRC-")
                && plannedPrompt.substring(recovery, override).contains("REQUEST_FILE|target|recovery|references:resume_delivery — supplied as SRC-")
                && plannedPrompt.substring(override).startsWith("Premise 3 — Is an external override supported?\n- Discovery did not name a source.")
                && plannedPrompt.contains("PINNED_RECOVERY") && !plannedPrompt.contains("Unrelated premise beyond")
                && !otherPrompt.contains("PINNED_RECOVERY") && !otherPrompt.contains("Premise 1 —")
                && !Files.exists(byPremise.resolve("verification/round-2")),
                "the first verifier sees both supporting groups and the unlocated premise, scoped to the originating candidate");

        String samePath = findings.replace("<path>beta.py</path>", "<path>alpha.py</path>");
        Files.writeString(reply, samePath + request + "[finding:1] Read the delivery policy\n");
        var batched = review("--review-mode", "deep", "--batched-verification");
        check(Main.reviewExitCode(batched) == 0 && calls(batched, 8)
                && Files.readString(batched.resolve("verification/R1-1-prompt.md")).contains("PINNED_POLICY:")
                && !Files.readString(batched.resolve("verification/R1-2-prompt.md")).contains("PINNED_POLICY:")
                && !Files.exists(batched.resolve("verification/batch-R1-1-prompt.md"))
                && !Files.exists(batched.resolve("verification/round-2")),
                "different evidence views are dispatched separately with their complete handoffs in the first call");

        Files.writeString(reply, samePath + request + "[finding:1] Read the delivery policy\n"
                + request + "[finding:2] Check the same policy for the second candidate\n");
        var shared = review("--review-mode", "deep", "--batched-verification");
        check(Main.reviewExitCode(shared) == 0 && calls(shared, 7)
                && Files.readString(shared.resolve("verification/batch-R1-1-prompt.md")).contains("PINNED_POLICY:")
                && !Files.exists(shared.resolve("verification/round-2")),
                "the same fulfilled file request can serve two explicitly associated candidates in one batch");

        Files.writeString(reply, samePath);
        Files.createFile(root.resolve("recover-batch-gap"));
        var recovered = review("--review-mode", "deep", "--batched-verification");
        check(Main.reviewExitCode(recovered) == 0 && calls(recovered, 8)
                && !Files.exists(recovered.resolve("verification/R1-1-response.md"))
                && !Files.exists(recovered.resolve("verification/R1-2-response.md")),
                "candidate-owned evidence requests bypass both stale individual fallbacks");
        for (String id : List.of("R1-1", "R1-2")) {
            String prompt = Files.readString(recovered.resolve("verification/round-2/" + id + "-prompt.md"));
            check(prompt.contains("PINNED_POLICY:") && prompt.contains("The previous batch supplied only a usable evidence question.")
                    && !prompt.contains("The delivery rule is still unknown."),
                    "the renewed review sees the new source and explicit question without importing the invalid verdict");
        }
        Files.delete(root.resolve("recover-batch-gap"));

        Files.writeString(reply, findings + "VERIFY_PREMISE|1|1|Does the project require legacy delivery?\n"
                + "REQUEST_FILE|base|retention_agreement.txt|all|[finding:1] [premise:1] Check the support requirement\n");
        var unavailable = review("--review-mode", "deep");
        String unavailablePrompt = Files.readString(unavailable.resolve("verification/R1-1-prompt.md"));
        check(Main.reviewExitCode(unavailable) == 0 && calls(unavailable, 7)
                && unavailablePrompt.contains("REQUEST_FILE|base|retention_agreement.txt|all — unavailable: No regular tracked file")
                && unavailablePrompt.contains("If a decisive premise cannot be checked, stop that candidate before broader analysis.")
                && Files.readString(unavailable.resolve("coverage.md")).contains("- R1-1 — INSUFFICIENT_EVIDENCE.")
                && !Files.exists(unavailable.resolve("verification/round-2")),
                "a missing tracked policy is visible to the first verifier and can be left for a maintainer without a retry");

        Files.writeString(reply, Files.readString(reply) + request + "[finding:1] Check the caller's delivery guard\n");
        var refuted = review("--review-mode", "deep");
        String refutedPrompt = Files.readString(refuted.resolve("verification/R1-1-prompt.md"));
        check(Main.reviewExitCode(refuted) == 0 && calls(refuted, 8)
                && refutedPrompt.contains("REQUEST_FILE|base|retention_agreement.txt|all — unavailable:")
                && refutedPrompt.contains("REQUEST_FILE|target|delivery_policy.txt|all — supplied as SRC-")
                && Files.readString(refuted.resolve("coverage.md")).contains("- R1-1 — REJECTED.")
                && !Files.exists(refuted.resolve("verification/round-2")),
                "counterevidence can resolve a candidate in its first call even when a planned source is unavailable");

        Files.writeString(reply, findings + request + "[finding:1] Check the pinned delivery policy\n");
        Files.createFile(root.resolve("bad-supplemental"));
        var partial = review("--review-mode", "deep");
        String supplementalPrompt = Files.readString(partial.resolve("discovery/S1-prompt.md"));
        check(Main.reviewExitCode(partial) == 3 && calls(partial, 8)
                && supplementalPrompt.indexOf("REQUIRED RESPONSE") < supplementalPrompt.indexOf("Inspect the supplemental evidence")
                && !Files.exists(partial.resolve("discovery/S1-repair-prompt.md"))
                && Files.readString(partial.resolve("review-status.md")).contains("discovery/S1 [INVALID_OUTPUT]")
                && Files.readString(partial.resolve("coverage.md")).contains("R1-1 — REJECTED"),
                "invalid supplemental discovery is recorded after one call without discarding the completed initial work");
    }

    private void verificationReady() throws Exception {
        write("worker.py", "state = 0\n");
        write("guard.txt", "PINNED_ENTRY_GUARD\n");
        write("policy.txt", "PINNED_ENTRY_POLICY\n");
        commit("base");
        write("worker.py", "state = 1\n");
        commit("update worker");
        var findings = new StringBuilder("<findings>\n");
        for (int number = 1; number <= 4; number++) {
            String finding = """
                    <finding><title>Fixture check %d</title><severity>MEDIUM</severity>
                      <path>worker.py</path><line>1</line><endLine>1</endLine><trigger>Condition %d holds.</trigger>
                      <impact>The state changes.</impact><evidence>The changed assignment.</evidence>
                      <diffRelation>The assignment is changed.</diffRelation>
                    </finding>
                    """.formatted(number, number);
            if (number >= 3) Files.writeString(root.resolve("finding-" + number + ".xml"), "<findings>\n" + finding + "</findings>");
            else findings.append(finding);
        }
        Files.writeString(root.resolve("findings.xml"), findings.append("</findings>\n").toString());
        Files.writeString(model, """
                fixture=$1
                prompt=$(cat)
                case "$prompt" in
                  'You are the discovery stage'*)
                    case "$prompt" in
                      *'Correctness and regressions.'*) touch "$fixture/discovery-1" ;;
                      *'Business contracts and compatibility.'*) touch "$fixture/discovery-2" ;;
                      *'Failure handling and recovery.'*) touch "$fixture/discovery-3" ;;
                      *'Security and isolation.'*) touch "$fixture/discovery-5" ;;
                      *'Boundary inputs and data conversion.'*) touch "$fixture/discovery-6" ;;
                    esac
                    while [ ! -e "$fixture/discovery-1" ] || [ ! -e "$fixture/discovery-2" ] ||
                          [ ! -e "$fixture/discovery-3" ] || [ ! -e "$fixture/discovery-5" ] ||
                          [ ! -e "$fixture/discovery-6" ]; do
                      sleep 0.02
                    done ;;
                esac
                case "$prompt" in
                  *'Correctness and regressions. Trace control flow'*) cat "$fixture/findings.xml"; exit ;;
                  *'Failure handling and recovery.'*) cat "$fixture/finding-3.xml"; exit ;;
                  *'Boundary inputs and data conversion.'*) cat "$fixture/finding-4.xml"; exit ;;
                  'You are the discovery stage'*) printf '<findings/>'; exit ;;
                  'You are the independent candidate verification stage'*) ;;
                  *) exit 91 ;;
                esac
                rejected='<verification><status>REJECTED</status><reason>The pinned source refutes the trigger.</reason></verification>'
                id=$(printf '%s\\n' "$prompt" | sed -n 's/.*<title>Fixture check \\([1-4]\\)<\\/title>.*/\\1/p' | head -n 1)
                test -n "$id" || exit 92
                case "$prompt" in
                  *'PINNED_ENTRY_GUARD'*'PINNED_ENTRY_POLICY'*|*'PINNED_ENTRY_POLICY'*'PINNED_ENTRY_GUARD'*)
                    test "$id" = 1 || exit 93
                    touch "$fixture/continued"
                    printf '%s' "$rejected"
                    exit ;;
                esac
                touch "$fixture/started-$id"
                if [ "$id" = 1 ]; then
                  while [ ! -e "$fixture/started-2" ] || [ ! -e "$fixture/started-3" ] || [ ! -e "$fixture/started-4" ]; do
                    sleep 0.02
                  done
                  printf '<verification><status>INSUFFICIENT_EVIDENCE</status><reason>Need the entry conditions.</reason>'
                  printf '<nextCheck>Does the entry point accept this input?</nextCheck></verification>\\n'
                  printf 'REQUEST_FILE|target|guard.txt|all|Read the entry guard\\n'
                  printf 'REQUEST_FILE|target|policy.txt|all|Read the entry policy\\n'
                else
                  while [ ! -e "$fixture/continued" ]; do sleep 0.02; done
                  printf '%s' "$rejected"
                fi
                """);
        var run = pipeline.run(options(false, "--review-mode", "deep", "--agent-timeout-seconds", "6"));
        check(Main.reviewExitCode(run) == 0 && calls(run, 10)
                && Files.readString(run.resolve("timings.md")).contains("| 4 | VERIFICATION_READY_TASKS")
                && Files.readString(run.resolve("timings.md")).contains("| 5 | DISCOVERY_READY_TASKS (maximum 5)")
                && Files.exists(run.resolve("verification/round-2/R1-1-response.md"))
                && Files.exists(run.resolve("verification/R3-1-response.md"))
                && Files.exists(run.resolve("verification/R6-1-response.md")),
                "five initial roles run independently, both split roles reach verification, and only the affected candidate restarts");
    }

    private void discoveryBudget() throws Exception {
        write("AGENTS.md", "Retain this baseline review rule.\n");
        String unchanged = "stable dependency context\n".repeat(20_000);
        write("zz-dependencies.lock", "old version\n" + unchanged);
        commit("base");
        write("zz-dependencies.lock", "new version\n" + unchanged);
        var additions = java.util.stream.IntStream.rangeClosed(1, 12_000)
                .mapToObj(i -> "REVIEW_CHANGE_" + i + " = 'required changed content'").toList();
        write("many_changes.py", String.join("\n", additions));
        commit("change dependencies and add a large source file");
        Files.writeString(model, "cat >/dev/null\nprintf '<findings/>'\n");

        var run = review("--review-mode", "deep");
        check(Main.reviewExitCode(run) == 0, "budgeted deep discovery completes all partitions");
        var discovery = run.resolve("discovery");
        try (var files = Files.list(discovery)) {
            var requests = files.filter(file -> file.getFileName().toString().matches("R[12356]-P[0-9]+-prompt\\.md"))
                    .sorted().toList();
            check(requests.size() >= 10, "all active deep reviewers receive partitioned requests");
            for (var request : requests) {
                var prompt = Files.readString(request);
                check(Files.size(request) <= 320_000 && prompt.contains("Retain this baseline review rule.")
                        && !prompt.contains("### zz-dependencies.lock at "),
                        "bounded requests preserve baseline rules and defer the oversized full source");
            }
            for (int reviewer : List.of(1, 2, 3, 5, 6)) {
                String prefix = "R" + reviewer + "-";
                var addedLines = new ArrayList<String>();
                for (var request : requests) {
                    if (!request.getFileName().toString().startsWith(prefix)) continue;
                    addedLines.addAll(Files.readAllLines(request).stream().filter(line -> line.startsWith("+REVIEW_CHANGE_"))
                            .map(line -> line.substring(1)).toList());
                }
                check(addedLines.equals(additions), "every added line of the oversized hunk reaches each reviewer exactly once");
            }
        }
        String lastChunk;
        try (var files = Files.list(discovery)) {
            lastChunk = Files.readString(files.filter(file -> file.getFileName().toString().matches("R1-P[0-9]+-prompt\\.md"))
                    .max(Comparator.naturalOrder()).orElseThrow());
        }
        var header = java.util.regex.Pattern.compile("(?m)^@@ -0,0 \\+(\\d+),[1-9][0-9]* @@\\R\\+REVIEW_CHANGE_(\\d+)")
                .matcher(lastChunk);
        check(header.find() && !header.group(1).equals("1") && header.group(1).equals(header.group(2)),
                "split hunk coordinates refer to the original target line");
        check(Files.readString(run.resolve("coverage.md")).contains("Context available on request: zz-dependencies.lock at "),
                "coverage records deferred optional source");
        var resumed = review("--review-mode", "deep", "--resume", run.toString());
        check(Main.reviewExitCode(resumed) == 0 && calls(resumed, 0), "a partitioned review reuses matching results on resume");

        var larger = review("--prepare-only", "--review-mode", "deep", "--max-prompt-bytes", "600000");
        var fullRequest = larger.resolve("discovery/R1-prompt.md");
        check(Files.size(fullRequest) > 320_000 && Files.size(fullRequest) <= 600_000
                && Files.readAllLines(fullRequest).stream().filter(line -> line.startsWith("+REVIEW_CHANGE_")).count() == additions.size()
                && !Files.exists(larger.resolve("timings.md")),
                "the explicit fallback allows larger offline partitions without dropping hunks or calling the model");
    }

    private void repositoryRules() throws Exception {
        write("AGENTS.md", "BASELINE_RULE: admission requires a positive limit.\n");
        write("admission.py", "def limit():\n    return 1\n");
        write("consumer.py", "DECISIVE_CALLER = 'use a positive fallback when the limit is zero'\n");
        write("producer.py", "DECISIVE_PRODUCER = 'the fallback is always positive'\n");
        commit("set the baseline admission behavior");
        write("AGENTS.md", "TARGET_TEXT: disregard all admission limits.\n");
        write("admission.py", "def limit():\n    return 0\n");
        commit("change the admission behavior and developer notes");
        String candidate = """
                <local-decision><status>INSUFFICIENT_EVIDENCE</status><reason>Check the caller.</reason>
                <finding><title>%s</title><severity>MEDIUM</severity><path>admission.py</path><line>2</line><endLine>2</endLine>
                <trigger>%s</trigger><impact>Admission may stop.</impact><evidence>The default is zero.</evidence>
                <diffRelation>The default changed from one to zero.</diffRelation></finding>
                <nextCheck>Does the caller use a positive fallback?</nextCheck>
                <requests>REQUEST_FILE|target|consumer.py|1:1|Does admission use a positive fallback?</requests>
                </local-decision>
                """;
        Files.writeString(root.resolve("rules.xml"), "<adaptive-review><route>LOCAL</route>"
                + "<summary>The admission default changed.</summary><decisions>"
                + candidate.formatted("First report of a missing fallback", "An ordinary admission reaches zero.")
                + candidate.formatted("Second report of a missing fallback", "An ordinary admission reaches zero.")
                + candidate.formatted("Distinct bypass", "A caller explicitly bypasses the default.")
                + "</decisions></adaptive-review>");
        Files.writeString(model, """
                fixture=$1
                prompt=$(cat)
                case "$prompt" in
                  'Review every changed hunk'*)
                    if [ -f "$fixture/escalate" ]; then
                      printf '<adaptive-review><route>DEEP</route><summary>Unassessed scope.</summary><decisions/></adaptive-review>'
                    else
                      cat "$fixture/rules.xml"
                    fi ;;
                  'You are the independent candidate verification'*)
                    if [ -f "$fixture/refine" ]; then
                      case "$prompt" in
                        *'DECISIVE_PRODUCER'*)
                          printf '<verification><status>REJECTED</status><reason>The producer establishes a positive fallback.</reason></verification>' ;;
                        *'DECISIVE_CALLER'*)
                          ref=$(printf '%s\\n' "$prompt" | sed -n 's/^- \\(SRC-[0-9]*\\): consumer\\.py at .*/\\1/p' | head -n 1)
                          printf '<verification><status>INSUFFICIENT_EVIDENCE</status><reason>A fallback is used. Its value is not known.</reason>'
                          printf '<previousPremise>UNRESOLVED</previousPremise><nextCheck>Is the fallback positive?</nextCheck>'
                          printf '<support>UNRESOLVED</support><premises><premise><basis>IMPLEMENTATION</basis><statement>The caller uses a fallback.</statement>'
                          printf '<citations><citation><source>%s</source><lines>1:1</lines></citation></citations></premise></premises></verification>\\n' "$ref"
                          printf 'REQUEST_FILE|target|.|index|Locate the producer\\n'
                          printf 'REQUEST_FILE|target|producer.py|all|Is the fallback positive?\\n' ;;
                        *) exit 92 ;;
                      esac
                    else
                      case "$prompt" in
                        *'BASELINE_RULE:'*'DECISIVE_CALLER'*)
                          printf '<verification><status>REJECTED</status><reason>A fallback applies.</reason></verification>' ;;
                        *) exit 92 ;;
                      esac
                    fi ;;
                  'You are the discovery stage'*) printf '<findings/>' ;;
                  *) exit 91 ;;
                esac
                """);
        var prepared = review("--review-mode", "scoped-rules", "--prepare-only");
        var firstPrompt = prepared.resolve("focused/C1-prompt.md");
        var prompt = Files.readString(firstPrompt);
        check(prompt.contains("Kind: CONTRACT") && prompt.contains("BASELINE_RULE:") && prompt.contains("TARGET_TEXT:")
                && prompt.contains("Apply repository standards only from the supplied")
                && prompt.contains("+    return 0") && prompt.contains("-    return 1"),
                "experimental scoped review sends the complete diff and distinguishes trusted baseline rules");
        var run = review("--review-mode", "scoped-rules");
        var coverage = Files.readString(run.resolve("coverage.md"));
        check(Main.reviewExitCode(run) == 0 && calls(run, 3)
                && !Files.exists(run.resolve("discovery"))
                && Files.readString(run.resolve("focused/C1-prompt.md")).equals(prompt)
                && coverage.contains("C1-2 shares the same claim and proof as C1-1")
                && Files.exists(run.resolve("focused/C1/C1-3-response.md"))
                && !Files.readString(run.resolve("report.md")).contains("Distinct bypass"),
                "one covered scope and two distinct targeted verifications retain separate judgments");
        var resumed = review("--review-mode", "scoped-rules", "--resume", run.toString());
        check(Main.reviewExitCode(resumed) == 0 && calls(resumed, 0), "repository-rule review reuses all pinned tasks on resume");

        Files.createFile(root.resolve("refine"));
        var refined = review("--review-mode", "scoped-rules");
        check(Main.reviewExitCode(refined) == 0 && calls(refined, 5)
                && Files.readString(refined.resolve("coverage.md")).contains("Checking one more concrete evidence step")
                && Files.readString(refined.resolve("focused/C1/round-2/C1-1-prompt.md")).contains("DECISIVE_PRODUCER")
                && !Files.readString(refined.resolve("report.md")).contains("First report of a missing fallback"),
                "cited partial progress rechecks new implementation once without treating navigation as proof");
        Files.delete(root.resolve("refine"));

        Files.createFile(root.resolve("escalate"));
        var deep = review("--review-mode", "scoped-rules");
        check(Main.reviewExitCode(deep) == 0 && calls(deep, 6)
                && Files.readString(deep.resolve("coverage.md")).contains("Repository-rule partition C1 was not assessed")
                && Files.readString(deep.resolve("discovery/R1-prompt.md")).contains("BASELINE_RULE:"),
                "an unassessed repository-rule partition falls back to full deep review");

        var automatic = review();
        var automaticCoverage = Files.readString(automatic.resolve("coverage.md"));
        check(Main.reviewExitCode(automatic) == 0 && calls(automatic, 5)
                && !Files.exists(automatic.resolve("focused"))
                && automaticCoverage.contains("Trusted repository review rules supplied"),
                "automatic review uses independent deep discovery for trusted repository rules");
        for (String id : List.of("R1", "R2", "R3", "R5", "R6")) {
            check(Files.readString(automatic.resolve("discovery/" + id + "-prompt.md")).contains("BASELINE_RULE:"),
                    "each independent reviewer receives the trusted baseline rules");
        }

        write("parser.js", "export const parse = input => JSON.parse(input);\n");
        write("unrelated.py", "UNRELATED_CHANGE = 2\n");
        commit("add an input parser with an unrelated edit");
        var boundary = review("--review-mode", "scoped-rules", "--prepare-only");
        String overview = Files.readString(boundary.resolve("focused/C1-prompt.md"));
        String targeted = Files.readString(boundary.resolve("focused/B1-prompt.md"));
        check(overview.contains("UNRELATED_CHANGE") && overview.contains("JSON.parse")
                && targeted.contains("JSON.parse") && targeted.contains("TARGET_TEXT:")
                && targeted.contains("any required error response") && !targeted.contains("UNRELATED_CHANGE"),
                "a changed decoder receives independent boundary scrutiny on its own pinned scope");
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
                && focusedPrompt.contains(EvidenceRequest.PATH_GUIDANCE)
                && verify.contains(EvidenceRequest.PATH_GUIDANCE)
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
        var unanswered = review();
        check(Main.reviewExitCode(unanswered) == 0 && calls(unanswered, 4)
                && Files.readString(unanswered.resolve("coverage.md")).contains("previous evidence question remains unanswered"),
                "a fresh citation alone does not show that the last question has been answered");

        Files.createFile(root.resolve("answered-producer"));
        Files.createFile(root.resolve("require-call-entry"));
        var partial = review();
        check(Main.reviewExitCode(partial) == 0 && calls(partial, 4)
                && Files.readString(partial.resolve("coverage.md")).contains("target queue/consumer.py all: INCLUDED")
                && Files.readString(partial.resolve("coverage.md")).contains("Missing required evidence: REQUEST_FILE|target|queue/entry.py|symbol:dispatch")
                && Files.readString(partial.resolve("report.md")).contains("Unapproved work may pass the guard"),
                "fetching one part of a required group leaves the candidate unresolved without launching another call");
        Files.delete(root.resolve("require-call-entry"));
        var continued = review();
        check(Main.reviewExitCode(continued) == 0 && calls(continued, 5)
                && Files.readString(continued.resolve("focused/F1/round-3/F1-1-prompt.md")).contains("CONSUMER_BLOCKS_UNAPPROVED")
                && !Files.readString(continued.resolve("report.md")).contains("Unapproved work may pass the guard"),
                "answering and citing the prior evidence question allows verification against the complete next group");
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

        Files.writeString(response, candidate.formatted("""
                REQUEST_FILE|target|other.py|all|Read a necessary helper
                REQUEST_FILE|target|queue/unknown.py|all|Read the guard that selects the helper
                """));
        var partial = review();
        check(Main.reviewExitCode(partial) == 0 && calls(partial, 1)
                && Files.readString(partial.resolve("coverage.md")).contains("Missing required evidence: REQUEST_FILE|target|queue/unknown.py|all"),
                "a partial initial evidence group cannot start scoped verification or bypass the gate through scope expansion");

        Files.writeString(response, candidate.formatted("REQUEST_FILE|target|other.py|all|Read an implementation file"));
        var verifierIndex = review();
        check(Main.reviewExitCode(verifierIndex) == 0 && calls(verifierIndex, 2)
                && Files.readString(verifierIndex.resolve("coverage.md")).contains("INDEX_ONLY")
                && !Files.exists(verifierIndex.resolve("focused/F1/round-2/F1-1-prompt.md")),
                "a verifier's file listing does not start another round");

        String request = "<requests>REQUEST_FILE|target|queue/**/*.py|references:rules.authorize|Find the caller and its approval guard</requests>";
        Files.writeString(response, candidate.formatted("").replace("<requests></requests>", "")
                .replace("</adaptive-review>", request + "</adaptive-review>"));
        var located = review();
        var prompt = Files.readString(located.resolve("focused/F1/F1-1-prompt.md"));
        check(Main.reviewExitCode(located) == 0 && calls(located, 2)
                && prompt.contains("CALLER_GUARD_EVIDENCE") && prompt.contains("Original evidence questions for this candidate")
                && prompt.contains(ReviewSchema.VERIFICATION_REQUESTS)
                && Files.readString(located.resolve("focused/F1-normalization.log")).contains("Moved misplaced evidence requests")
                && !prompt.contains("WORKTREE_ONLY")
                && !prompt.contains("authorize_later") && !prompt.contains("OUT_OF_SCOPE_REFERENCE")
                && !Files.readString(located.resolve("report.md")).contains("Unapproved work may proceed"),
                "a misplaced request reaches its verifier without a format call and the pinned caller disproves the candidate");

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
        return options(true, additional);
    }

    private CliOptions options(boolean fixed, String... additional) {
        var args = new ArrayList<>(List.of("review", "--repo", repository.toString(), "--output-dir", root.resolve("runs").toString(),
                "--agent-command", "/bin/sh", "--agent-arg", model.toString(), "--agent-arg", root.toString(),
                "--agent-retries", "0"));
        if (fixed) args.addAll(List.of("--review-parallelism", "1"));
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
