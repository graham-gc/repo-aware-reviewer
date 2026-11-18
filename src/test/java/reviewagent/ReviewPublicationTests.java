package reviewagent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** The playground regression: local facts survive policy disagreement without inventing a contract. */
final class ReviewPublicationTests {
    private static final String BASE = "def average(values):\n    if not values:\n        return 0\n    return sum(values) / len(values)\n";
    private static final String TARGET = "def average(values):\n    return sum(values) / len(values)\n";
    private static final String EXPRESSION = "return sum(values) / len(values)";
    private static final String REMOVED = "-    if not values:\n-        return 0";
    private static final String GAP = "The before/after behavior is established.\n\nWhether the interface intentionally changes is not specified.";
    private static final String NEXT_CHECK = "Ask the API owner whether average([]) must still return zero. If required, the exception is a regression.";

    static void run() throws Exception {
        renderingAndProof();
        workflow();
    }

    private static ReviewFinding candidate(String id) {
        return new ReviewFinding(id, "Empty-input behavior changed", ReviewFinding.Severity.MEDIUM, "calculator.py", 2, 2,
                "average([])", "The baseline returns 0; the target raises ZeroDivisionError.", "Compare the pinned implementations.",
                "The diff removes the empty-input guard.");
    }

    private static String citation(String source, String quote) {
        return "<citation><source>" + source + "</source><quote><![CDATA[" + quote + "]]></quote></citation>";
    }

    private static String positive(String id, String target, String baseline, String removed) {
        return "<verification><status>CONFIRMED</status><reason>The local failure is established.\nNo downstream outage is claimed.</reason>"
                + "<relation>INTRODUCED</relation><baseline>The original function returned 0 for empty input.</baseline>"
                + "<contract>The existing input guard prevented this local exception.</contract><support>REACHABLE_FAILURE</support>"
                + "<premises><premise><statement>The target divides by len(values), which is zero for an empty list.</statement>"
                + "<basis>IMPLEMENTATION</basis><citations>" + citation(target, EXPRESSION) + "</citations></premise>"
                + "<premise><statement>The baseline guard returned before the division.</statement><basis>IMPLEMENTATION</basis>"
                + "<citations>" + citation(baseline, removed) + "</citations></premise></premises><citations>"
                + citation(target, EXPRESSION) + citation(baseline, removed) + "</citations>"
                + ReviewOutput.findingXml(candidate(id)) + "</verification>";
    }

    private static String uncertain(String target, String baseline, String removed) {
        return "<verification><status>INSUFFICIENT_EVIDENCE</status><reason>" + GAP + "</reason>"
                + "<nextCheck>" + NEXT_CHECK + "</nextCheck>"
                + "<baseline>Empty input changed from returning 0 to raising an exception.</baseline>"
                + "<citations>" + citation(target, EXPRESSION) + citation(baseline, removed) + "</citations></verification>";
    }

    private static void renderingAndProof() throws Exception {
        var scope = new ReviewScope(ReviewScope.Mode.BRANCH, "base", "target", "base", "target", "base..target");
        var base = EvidenceSources.file("base", "calculator.py", 1, "calculator.py at base", BASE, scope, LanguageRegistry.defaults());
        var target = EvidenceSources.file("target", "calculator.py", 1, "calculator.py at target", TARGET, scope, LanguageRegistry.defaults());
        var catalog = new EvidenceCatalog(List.of(base, target), true);
        var evidence = new EvidenceBundle(scope, List.of("calculator.py"), "", "", 20_000, null, catalog);
        var index = new EvidenceIndex(catalog);
        String originalGuard = "    if not values:\n        return 0";
        var r1 = ReviewGrounding.verification(uncertain(target.id(), base.id(), originalGuard), candidate("R1-1"), evidence, index);
        var r2 = ReviewGrounding.verification(positive("R2-1", target.id(), base.id(), originalGuard), candidate("R2-1"), evidence, index);
        var r3 = ReviewGrounding.verification(positive("R3-1", target.id(), base.id(), originalGuard), candidate("R3-1"), evidence, index);
        require(r2.verdict().status() == ReviewOutput.Status.CONFIRMED, "a locally proved failure needs no external requirement document");
        var opposite = ReviewGrounding.verification(uncertain(target.id(), base.id(), originalGuard), candidate("R3-1"), evidence, index);
        var groups = List.of(List.of("R1-1", "R2-1", "R3-1"));
        var adjudicated = ReviewQuality.resolveConflicts(List.of(r2, opposite), groups, OutputLanguage.EN);
        var dispute = adjudicated.getFirst();
        require(dispute.verdict().status() == ReviewOutput.Status.DISPUTED && dispute.verdict().confirmed().equals(r2.verdict().confirmed())
                && dispute.citations().equals(r2.citations()) && dispute.support().equals(r2.support()) && dispute.relation() == r2.relation(),
                "policy disagreement preserves candidate, provenance, implementation proof and change relationship");
        var concerns = ReviewConcerns.collect(List.of(r1, r2, r3), adjudicated, groups);
        require(concerns.size() == 1 && concerns.getFirst().members().size() == 3, "one grouped risk replaces three repeated candidate essays");
        require(concerns.getFirst().representative().equals(r2.verdict().confirmed()),
                "the corrected grounded candidate remains the summary even when later defect classification is disputed");
        var unprovedDiscovery = new ReviewFinding("R2-1", "Speculative outage", ReviewFinding.Severity.HIGH, "calculator.py", 2, 2,
                "Empty input", "Every production batch crashes", "Unproved allegation", "Removed guard");
        var policyGap = new ReviewOutput.Decision(new ReviewOutput.Verification(unprovedDiscovery, ReviewOutput.Status.INSUFFICIENT_EVIDENCE,
                GAP, null), ReviewOutput.Relation.UNRESOLVED, "Baseline handled empty input", "Intent is not specified.", r2.citations());
        var retained = ReviewConcerns.collect(List.of(r2), List.of(policyGap), List.of(List.of("R2-1")));
        require(retained.getFirst().representative().equals(r2.verdict().confirmed())
                && !ReviewConcerns.markdown(retained, OutputLanguage.EN).contains("Every production batch crashes")
                && !ReviewConcerns.markdown(retained, OutputLanguage.EN).contains("Suggested check"),
                "the old verdict without a suggested check retains the grounded behavioral wording");
        var refuted = new ReviewOutput.Decision(new ReviewOutput.Verification(candidate("R2-1"), ReviewOutput.Status.REJECTED,
                "A later source disproves this candidate.", null), ReviewOutput.Relation.PRE_EXISTING, "", "", r2.citations());
        var remaining = ReviewConcerns.collect(List.of(r2, r1), List.of(refuted), List.of(List.of("R2-1", "R1-1")));
        require(remaining.getFirst().representative().id().equals("R1-1"),
                "a superseded positive cannot be the summary after a validated rejection, even when grouped with an unresolved peer");
        for (var language : OutputLanguage.values()) {
            String response = String.join("\n\n", ReviewReport.headings(language)) + "\n<finding-groups/>";
            String report = ReviewReport.finalReport(response, List.of(), language, "No external requirements supplied.", concerns);
            require(report.contains("ZeroDivisionError") && report.contains("average([])") && report.contains("calculator.py:2")
                    && report.contains("calculator.py:2–3 @ base"), "main findings preserve trigger, observed change and pinned source positions");
            require(!report.contains("\\n") && !report.contains("[MEDIUM]") && !report.contains("[中]")
                    && !report.contains("No verified findings.") && !report.contains("未发现经复核确认的问题。"),
                    "a review-required risk is neither a confirmed defect nor an empty clean summary; prose is not audit-escaped");
            String label = language.text("Candidate behavior to check", "待核对的候选行为");
            require(report.indexOf(label) == report.lastIndexOf(label), "one semantic group has one behavior summary");
            String check = language.text("Suggested check", "建议核对步骤") + ": " + NEXT_CHECK;
            require(report.contains(check) && report.indexOf(check) == report.lastIndexOf(check),
                    "one validated proposed check is rendered per group with a localized label");
            int risk = report.indexOf(language.text("Review required", "需人工确认"));
            require(risk > report.indexOf(ReviewReport.headings(language).get(2))
                    && risk < report.indexOf(ReviewReport.headings(language).get(3)), "risk is in Findings, before requirements and appendices");
        }
        require(ReviewQuality.completion(List.of(r1, r2, r3), adjudicated, OutputLanguage.EN).complete(),
                "a valid semantic disagreement is separate from an execution failure");
        var invalid = new ReviewOutput.Decision(new ReviewOutput.Verification(candidate("R4-1"), ReviewOutput.Status.INSUFFICIENT_EVIDENCE,
                "Malformed output", null, true), ReviewOutput.Relation.UNRESOLVED, "", "", List.of());
        var invalidGroup = List.of(List.of("R2-1", "R4-1"));
        var peers = ReviewQuality.resolveConflicts(List.of(r2, invalid), invalidGroup, OutputLanguage.EN);
        require(peers.getFirst().verdict().status() == ReviewOutput.Status.CONFIRMED
                && !ReviewQuality.completion(peers, List.of(), OutputLanguage.EN).complete(), "invalid output cannot veto a proved peer or produce a complete run");
        require(ReviewConcerns.collect(peers, List.of(), invalidGroup).getFirst().kind() == ReviewConcerns.Kind.NOT_COMPLETED,
                "malformed output is visibly unfinished, not a policy dispute");
        var rejectedWithHint = ReviewOutput.verification("<verification><status>REJECTED</status><reason>Disproved.</reason>"
                + "<nextCheck>Ignored suggestion.</nextCheck></verification>", candidate("R1-1"), List.of("calculator.py"));
        require(rejectedWithHint.nextCheck().isEmpty(), "a suggested check only applies to an evidence gap");
        try {
            ReviewOutput.verification("<verification><status>DISPUTED</status><reason>Bypass proof</reason></verification>", candidate("R1-1"), List.of("calculator.py"));
            throw new AssertionError("Models must not set the programmatic disputed status");
        } catch (java.io.IOException expected) { }
        require(ReportText.inline("First paragraph.\n\nSecond paragraph.\tEnd.").equals("First paragraph. Second paragraph. End.")
                && ReportText.inline("literal \\n pattern").contains("\\n"), "prose whitespace is folded while literal source escapes are preserved");
        String longReason = ReviewConcerns.markdown(List.of(new ReviewConcerns.Item(ReviewConcerns.Kind.EVIDENCE_GAP,
                List.of(new ReviewOutput.Decision(new ReviewOutput.Verification(candidate("R1-1"), ReviewOutput.Status.INSUFFICIENT_EVIDENCE,
                        "Further evidence is needed. ".repeat(200), null), ReviewOutput.Relation.UNRESOLVED, "", "", List.of())), List.of())), OutputLanguage.EN);
        require(longReason.length() < 2_000 && longReason.contains("…") && longReason.contains("coverage.md"), "the risk summary is bounded and links to complete reasoning");
    }

    private static void workflow() throws Exception {
        Path fixture = Files.createTempDirectory("review-publication-playground-");
        try {
            Path repo = Files.createDirectory(fixture.resolve("repo"));
            git(repo, "init", "-q");
            Files.writeString(repo.resolve("calculator.py"), BASE);
            git(repo, "add", "--all"); git(repo, "commit", "-qm", "base");
            Files.writeString(repo.resolve("calculator.py"), TARGET);
            git(repo, "add", "--all"); git(repo, "commit", "-qm", "target");
            Path stub = fixture.resolve("agent.sh");
            var command = List.of("review", "--repo", repo.toString(), "--agent-command", "/bin/sh", "--agent-arg", stub.toString(),
                    "--agent-arg", "--model=judge", "--discovery-model", "alpha", "--discovery-model", "beta",
                    "--discovery-model", "gamma");
            var options = options(command, "--output-dir", fixture.resolve("runs").toString());
            var git = new GitClient(repo, new ProcessExecutor());
            var evidence = new EvidenceCollector(git).collect(git.resolve(options), null, null, CliOptions.KnowledgeMode.FULL, options.maxEvidenceBytes());
            var diff = evidence.catalog().sources().stream().filter(source -> source.role(EvidenceSource.Role.DIFF)).findFirst().orElseThrow();
            var source = evidence.catalog().sources().stream().filter(s -> s.path().equals("calculator.py") && !s.role(EvidenceSource.Role.DIFF)).findFirst().orElseThrow();
            Files.writeString(fixture.resolve("positive.xml"), positive("CANDIDATE_ID", source.id(), diff.id(), REMOVED));
            Files.writeString(fixture.resolve("uncertain.xml"), uncertain(source.id(), diff.id(), REMOVED));
            Files.writeString(fixture.resolve("discovery.xml"), "<findings>" + ReviewOutput.findingXml(candidate("unused")) + "</findings>");
            Files.writeString(fixture.resolve("report.txt"), String.join("\n\n", ReviewReport.headings(OutputLanguage.EN)) + "\n<finding-groups/>");
            Files.writeString(stub, """
                    set -eu
                    root=$(dirname "$0")
                    prompt=$(cat)
                    case "$prompt" in
                      'You are the discovery stage'*)
                        [ "$#" -eq 2 ] && [ "$1" = '--model' ] || exit 93
                        case "$prompt" in
                          *'Correctness and regressions.'*) [ "$2" = alpha ] || exit 94 ;;
                          *'Requirements and business contracts.'*) [ "$2" = beta ] || exit 95 ;;
                          *'Boundary conditions and security.'*) [ "$2" = gamma ] || exit 96 ;;
                        esac ;;
                      *) [ "$#" -eq 1 ] && [ "$1" = '--model=judge' ] || exit 97 ;;
                    esac
                    printf 'call\\n' >> "$root/calls"
                    case "$prompt" in
                      'You are the discovery stage'*)
                        case "$2:$prompt" in
                          alpha:*'OUTPUT FORMAT CORRECTION'*) cat "$root/discovery.xml" ;;
                          alpha:*) printf 'initial malformed discovery' ;;
                          *) cat "$root/discovery.xml" ;;
                        esac ;;
                      'You are the independent candidate verification'*|'You are the independent publication adjudication'*)
                        candidate=$(printf '%s\\n' "$prompt" | sed -n '/^CANDIDATE TO VERIFY$/{n;p;}')
                        case "$candidate" in
                          *'id="R1-1"'*) cat "$root/uncertain.xml" ;;
                          *'id="R2-1"'*) sed 's/CANDIDATE_ID/R2-1/g' "$root/positive.xml" ;;
                          *'id="R3-1"'*)
                            case "$prompt" in
                              'You are the independent publication adjudication'*) cat "$root/uncertain.xml" ;;
                              *) sed 's/CANDIDATE_ID/R3-1/g' "$root/positive.xml" ;;
                            esac ;;
                          *) exit 91 ;;
                        esac ;;
                      'You are the candidate reconciliation'*)
                        if [ -f "$root/fail-groups" ]; then printf '<candidate-groups><group><id>unknown</id></group></candidate-groups>'
                        else printf '<candidate-groups><group><id>R1-1</id><id>R2-1</id><id>R3-1</id></group></candidate-groups>'; fi ;;
                      'You are the report consolidation'*) cat "$root/report.txt" ;;
                      *) exit 92 ;;
                    esac
                    """);
            Path run = new ReviewPipeline(new ProcessExecutor(), Clock.systemUTC()).run(options);
            String report = Files.readString(run.resolve("report.md"));
            require(report.contains("Confirmed defects: 0; review-required items: 1; unfinished checks: 0")
                    && report.contains("[Review required — conflicting decisions]") && report.contains("ZeroDivisionError")
                    && report.contains("Suggested check: " + NEXT_CHECK),
                    "the full pipeline surfaces the playground compatibility risk in its main findings");
            require(Files.isRegularFile(run.resolve("discovery/R1-repair-response.md"))
                    && Files.readString(run.resolve("scope.md")).contains("Discovery models (R1, R2, R3): alpha, beta, gamma"),
                    "discovery model assignment is recorded and stays in effect during format correction");
            require(Files.readString(run.resolve("coverage.md")).contains("R2-1 — DISPUTED")
                    && Files.readString(run.resolve("review-status.md")).contains("Status: COMPLETE"),
                    "audit retains the disagreement and complete describes execution, not approval");
            long calls = Files.readAllLines(fixture.resolve("calls")).size();
            var resume = options(command, "--resume", run.toString());
            new ReviewPipeline(new ProcessExecutor(), Clock.systemUTC()).run(resume);
            require(Files.readAllLines(fixture.resolve("calls")).size() == calls && Files.readString(run.resolve("report.md")).equals(report),
                    "resume recomputes publication policy from validated saved outputs without another model call");
            var differentRoute = new ArrayList<>(command);
            differentRoute.set(differentRoute.indexOf("alpha"), "another-model");
            try {
                new ReviewPipeline(new ProcessExecutor(), Clock.systemUTC()).run(options(differentRoute, "--resume", run.toString()));
                throw new AssertionError("Changing a discovery model must require a fresh review");
            } catch (java.io.IOException expected) {
                require(expected.getMessage().contains("discovery model assignments")
                        && Files.readAllLines(fixture.resolve("calls")).size() == calls,
                        "resume rejects a changed discovery route before launching or reusing tasks");
            }

            // A report model must not put pending IDs into the empty set of confirmed findings.
            String invalidReport = Files.readString(fixture.resolve("report.txt")).replace("<finding-groups/>",
                    "<finding-groups><group><id>R2-1</id></group></finding-groups>");
            Files.writeString(fixture.resolve("report.txt"), invalidReport);
            Path partial = new ReviewPipeline(new ProcessExecutor(), Clock.systemUTC()).run(options);
            String fallback = Files.readString(partial.resolve("report.md"));
            String partialStatus = Files.readString(partial.resolve("review-status.md"));
            require(Main.reviewExitCode(partial) == 3 && fallback.contains("[Review required — conflicting decisions]")
                    && fallback.contains("Confirmed defects: 0; review-required items: 1; unfinished checks: 0")
                    && fallback.contains("ZeroDivisionError") && fallback.contains("R1-1, R2-1, R3-1"),
                    "invalid final grouping cannot erase completed grouped risks or promote them to confirmed defects");
            require(partialStatus.contains("Candidates requiring human review: 3")
                    && partialStatus.contains("Status: INCOMPLETE") && Files.readString(partial.resolve("verified-findings.md")).isEmpty(),
                    "fallback completion uses the latest real candidate decisions instead of an empty result set");
            String diagnostic = "[report-state.properties](report-state.properties)";
            require(fallback.contains(diagnostic) && partialStatus.contains(diagnostic)
                    && Files.isRegularFile(partial.resolve("report-state.properties"))
                    && Files.readString(partial.resolve("report-state.properties")).contains("failureKind=INVALID_OUTPUT")
                    && !fallback.contains(partial.toString()) && !partialStatus.contains(partial.toString()),
                    "shared report failures reference the exported checkpoint without embedding temporary paths");
            require(Files.readString(partial.resolve("report-error.txt")).contains("unknown or repeated finding ID"),
                    "the malformed grouping is still rejected and its full local error retained");
            Files.writeString(fixture.resolve("report.txt"), String.join("\n\n", ReviewReport.headings(OutputLanguage.EN)) + "\n<finding-groups/>");
            long beforeRecovery = Files.readAllLines(fixture.resolve("calls")).size();
            var recover = options(command, "--resume", partial.toString());
            new ReviewPipeline(new ProcessExecutor(), Clock.systemUTC()).run(recover);
            require(Main.reviewExitCode(partial) == 0 && Files.readString(partial.resolve("review-status.md")).contains("Candidates requiring human review: 3")
                    && Files.readAllLines(fixture.resolve("calls")).size() == beforeRecovery + 1
                    && !Files.exists(partial.resolve("report-error.txt")),
                    "recovery retries only the invalid report and retains the already completed review decisions");

            Files.createFile(fixture.resolve("fail-groups"));
            Path blocked = new ReviewPipeline(new ProcessExecutor(), Clock.systemUTC()).run(options);
            String blockedReport = Files.readString(blocked.resolve("report.md"));
            require(Main.reviewExitCode(blocked) == 3 && blockedReport.contains("[Review required — evidence gap]")
                    && blockedReport.contains("Confirmed defects: 0; review-required items: 1; unfinished checks: 0")
                    && Files.readString(blocked.resolve("review-status.md")).contains("Candidates requiring human review: 1"),
                    "a grouping failure retains completed evidence-gap decisions without publishing preliminary positives");
            require(Files.readString(blocked.resolve("coverage.md")).contains("R2-1 — CONFIRMED")
                    && !Files.exists(blocked.resolve("report-prompt.md"))
                    && blockedReport.contains("[adjudication/groups-state.properties](adjudication/groups-state.properties)"),
                    "completed verification remains auditable even when required publication grouping blocks dependent stages");
        } finally {
            try (var paths = Files.walk(fixture)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static CliOptions options(List<String> command, String... extra) {
        var arguments = new ArrayList<>(command);
        arguments.addAll(List.of(extra));
        return CliOptions.parse(arguments.toArray(String[]::new));
    }

    private static void git(Path repo, String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("git", "-c", "user.name=Review Test", "-c", "user.email=test@example.invalid"));
        command.addAll(List.of(arguments));
        var result = new ProcessExecutor().run(command, repo, null, Duration.ofSeconds(10));
        require(result.exitCode() == 0, "Synthetic repository setup failed: " + result.stderr());
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
