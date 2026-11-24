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
        publishFromVerification();
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

    private static String linkedProof(String trigger, String outcome) {
        return "<triggerCitations>" + trigger + "</triggerCitations><outcomeCitations>" + outcome + "</outcomeCitations>";
    }

    private static void renderingAndProof() throws Exception {
        var scope = new ReviewScope(ReviewScope.Mode.BRANCH, "base", "target", "base", "target", "base..target");
        var base = EvidenceSources.file("base", "calculator.py", 1, "calculator.py at base", BASE, scope, LanguageRegistry.defaults());
        var target = EvidenceSources.file("target", "calculator.py", 1, "calculator.py at target", TARGET, scope, LanguageRegistry.defaults());
        String callerSource = "def summarize(): return average([])";
        var caller = EvidenceSources.file("target", "caller.py", 1, "caller.py at target", callerSource,
                scope, LanguageRegistry.defaults());
        var catalog = new EvidenceCatalog(List.of(base, target, caller), true);
        var evidence = new EvidenceBundle(scope, List.of("calculator.py"), "", "", 20_000, null, catalog);
        var index = new EvidenceIndex(catalog);
        var prompts = new PromptFactory();
        String directPrompt = prompts.verifyFinding(evidence, candidate("R1-1"), "", index,
                PromptFactory.DECISIVE_FOLLOWUP, true);
        require(directPrompt.contains("<triggerCitations><citation>")
                && directPrompt.contains("<outcomeCitations><citation>")
                && directPrompt.contains("<statement>Additional necessary implementation fact, if needed</statement>")
                && directPrompt.contains("<citations><citation><source>SRC-18</source><lines>4:6</lines></citation></citations>")
                && directPrompt.contains("<nextCheck>Does the caller return early on empty input?</nextCheck>")
                && !directPrompt.contains("<statement>Concrete failure</statement>"),
                "the primary XML example links citations to the finding instead of repeating its claims");
        String originalGuard = "    if not values:\n        return 0";
        var r1 = ReviewGrounding.verification(uncertain(target.id(), base.id(), originalGuard), candidate("R1-1"), evidence, index);
        var r2 = ReviewGrounding.verification(positive("R2-1", target.id(), base.id(), originalGuard), candidate("R2-1"), evidence, index);
        var r3 = ReviewGrounding.verification(positive("R3-1", target.id(), base.id(), originalGuard), candidate("R3-1"), evidence, index);
        require(r2.verdict().status() == ReviewOutput.Status.CONFIRMED, "a locally proved failure needs no external requirement document");
        String reference = citation(target.id(), EXPRESSION);
        String linked = positive("R2-1", target.id(), base.id(), originalGuard)
                .replace("</verification>", linkedProof(reference, reference) + "</verification>");
        var direct = ReviewGrounding.verification(linked, candidate("R2-1"), evidence, index);
        require(PublicationGate.ready(direct, index, scope.targetCommit())
                && direct.verdict().confirmed().equals(r2.verdict().confirmed())
                && direct.citations().equals(r2.citations()),
                "cited trigger and outcome publish the same claim without duplicating its text");
        var pending = ReviewGrounding.verification(linked.replace("</verification>",
                "<nextCheck>Check whether an upstream guard blocks the trigger.</nextCheck></verification>"),
                candidate("R2-1"), evidence, index);
        require(!PublicationGate.ready(pending, index, scope.targetCommit()), "an explicit open check keeps adjudication");
        try {
            ReviewGrounding.verification(linked.replace("<outcomeCitations>" + reference,
                    "<outcomeCitations>" + citation(base.id(), originalGuard)), candidate("R2-1"), evidence, index);
            throw new AssertionError("Base-only evidence was accepted as proof of a target outcome");
        } catch (ReviewDecisionException expected) {
            require(expected.getMessage().contains("Unproved finding premise"), "linked proof retains target evidence validation");
        }
        require(SharedAdjudication.equivalentGroups(List.of(r1, r2, r3)).isEmpty(),
                "an unresolved peer keeps publication decisions independent");
        require(SharedAdjudication.equivalentGroups(List.of(r2, r3)).equals(List.of(List.of("R2-1", "R3-1"))),
                "identical independently verified claims and proofs can share a publication task");
        var differentBaseline = ReviewGrounding.verification(positive("R3-1", target.id(), base.id(), "def average(values):"),
                candidate("R3-1"), evidence, index);
        require(SharedAdjudication.equivalentGroups(List.of(r2, differentBaseline)).isEmpty(),
                "different baseline proof stays independent even when the changed location and source block agree");
        var callerProof = index.bind(List.of(new EvidenceIndex.Citation(caller.id(), callerSource)));
        var premises = new ArrayList<>(r3.support().premises());
        premises.add(new ReviewSupport.Premise("The caller passes an empty list.", ReviewSupport.Basis.IMPLEMENTATION, callerProof));
        var citations = new ArrayList<>(r3.citations());
        citations.addAll(callerProof);
        var wider = new ReviewFinding("S1-1", "Empty summary fails", ReviewFinding.Severity.MEDIUM,
                "calculator.py", 1, 2, "The caller requests an empty summary.",
                "An exception prevents an empty summary.", "Compare the caller with the changed guard.", "The fix removed the guard.");
        var crossFile = new ReviewOutput.Decision(new ReviewOutput.Verification(candidate("S1-1"),
                ReviewOutput.Status.CONFIRMED, "The caller reaches the changed code.", wider),
                r3.relation(), r3.baseline(), r3.contract(), List.copyOf(citations),
                new ReviewSupport(r3.support().kind(), List.copyOf(premises)));
        require(SharedAdjudication.equivalentGroups(List.of(r2, crossFile, r3)).equals(List.of(List.of("R2-1", "R3-1"))),
                "a wider, cross-file claim is judged separately from identical proofs in the same candidate group");
        var proved = withPinnedOutcome(r2, r2.verdict().confirmed(), r2.support().premises().getFirst().citations());
        var reworded = new ReviewFinding("R3-1", "Averaging an empty list throws", ReviewFinding.Severity.MEDIUM,
                "calculator.py", 2, 2, "An empty list reaches the division.", "The division raises ZeroDivisionError.",
                "The denominator is zero.", "The early return was removed.");
        var sameFailure = withPinnedOutcome(r3, reworded, r3.support().premises().getFirst().citations());
        var differentOutcome = withPinnedOutcome(r3, reworded, callerProof);
        require(PublicationGate.representative(List.of(proved, sameFailure), index, scope.targetCommit()) == proved,
                "reconciliation plus pinned trigger and outcome can publish a paraphrased duplicate once");
        var paraphrasedOutcome = new ReviewOutput.Decision(sameFailure.verdict(), sameFailure.relation(), sameFailure.baseline(),
                sameFailure.contract(), sameFailure.citations(), new ReviewSupport(sameFailure.support().kind(),
                sameFailure.support().premises().stream().map(premise -> premise.basis() == ReviewSupport.Basis.OUTCOME
                        ? new ReviewSupport.Premise("The call ends with an exception for this input.", premise.basis(), premise.citations())
                        : premise).toList()));
        var sameGroup = List.of(List.of("R2-1", "R3-1"));
        require(PublicationGate.ready(proved, index, scope.targetCommit())
                && !PublicationGate.ready(paraphrasedOutcome, index, scope.targetCommit())
                && PublicationGate.reportDecisions(List.of(proved, paraphrasedOutcome), sameGroup, index, scope.targetCommit())
                        .equals(List.of(proved)),
                "a weaker member requires adjudication while a grounded peer can publish and share the final entry");
        require(PublicationGate.representative(List.of(proved, r1), index, scope.targetCommit()) == null
                && PublicationGate.representative(List.of(proved, differentOutcome), index, scope.targetCommit()) == null
                && PublicationGate.representative(List.of(r2), index, scope.targetCommit()) == null,
                "unresolved peers, distinct outcome evidence and missing explicit outcome all retain independent adjudication");
        var highImpact = new ReviewFinding(reworded.id(), reworded.title(), ReviewFinding.Severity.HIGH,
                reworded.path(), reworded.line(), reworded.endLine(), reworded.trigger(), reworded.impact(),
                reworded.evidence(), reworded.diffRelation());
        require(PublicationGate.representative(List.of(withPinnedOutcome(r3, highImpact, callerProof)),
                index, scope.targetCommit()) == null, "high impact still requires a publication verdict");
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

    private static ReviewOutput.Decision withPinnedOutcome(ReviewOutput.Decision verified, ReviewFinding confirmed,
                                                           List<EvidenceIndex.Citation> outcome) {
        var premises = new ArrayList<>(verified.support().premises());
        premises.add(new ReviewSupport.Premise(confirmed.trigger(), ReviewSupport.Basis.IMPLEMENTATION,
                verified.support().premises().getFirst().citations()));
        premises.add(new ReviewSupport.Premise(confirmed.impact(), ReviewSupport.Basis.OUTCOME, outcome));
        return new ReviewOutput.Decision(new ReviewOutput.Verification(verified.verdict().candidate(),
                ReviewOutput.Status.CONFIRMED, verified.verdict().reason(), confirmed), verified.relation(),
                verified.baseline(), verified.contract(), verified.citations(),
                new ReviewSupport(verified.support().kind(), List.copyOf(premises)));
    }

    private static void publishFromVerification() throws Exception {
        Path fixture = Files.createTempDirectory("review-terminal-verdict-");
        try {
            Path repo = Files.createDirectory(fixture.resolve("repo"));
            git(repo, "init", "-q");
            Files.writeString(repo.resolve("calculator.py"), BASE);
            Files.writeString(repo.resolve("entrypoint.py"), "def run_summary():\n    return summarize_measurements()\n");
            git(repo, "add", "--all"); git(repo, "commit", "-qm", "base");
            Files.writeString(repo.resolve("calculator.py"), TARGET);
            git(repo, "add", "--all"); git(repo, "commit", "-qm", "target");
            Path stub = fixture.resolve("agent.sh");
            var command = List.of("review", "--repo", repo.toString(), "--review-mode", "deep", "--agent-command", "/bin/sh",
                    "--agent-arg", stub.toString(), "--agent-arg", fixture.toString(), "--output-dir", fixture.resolve("runs").toString());
            var options = options(command);
            var executor = new ProcessExecutor();
            var git = new GitClient(repo, executor);
            var evidence = new EvidenceCollector(git).collect(git.resolve(options), null, null, CliOptions.KnowledgeMode.FULL, options.maxEvidenceBytes());
            var diff = evidence.catalog().sources().stream().filter(source -> source.role(EvidenceSource.Role.DIFF)).findFirst().orElseThrow();
            var target = evidence.catalog().sources().stream().filter(source -> source.path().equals("calculator.py")
                    && !source.role(EvidenceSource.Role.DIFF) && source.role(EvidenceSource.Role.TARGET)).findFirst().orElseThrow();
            var candidate = candidate("R1-1");
            Path discoveries = fixture.resolve("findings.xml");
            String finding = ReviewOutput.findingXml(candidate);
            Files.writeString(discoveries, "<findings>" + finding + "</findings>");
            String positive = positive(candidate.id(), target.id(), diff.id(), REMOVED);
            String excerpt = citation(target.id(), EXPRESSION);
            Path response = fixture.resolve("verification.xml");
            Files.writeString(response, positive);
            Files.writeString(fixture.resolve("adjudicated.xml"), positive);
            Files.writeString(stub, """
                    fixture=$1
                    prompt=$(cat)
                    case "$prompt" in
                      'You are the discovery stage'*'Correctness and regressions.'*) cat "$fixture/findings.xml" ;;
                      'You are the discovery stage'*) printf '<findings/>' ;;
                      'You are the independent candidate verification stage'*)
                        case "$prompt" in
                          *'<title>Check the empty-input baseline</title>'*)
                            case "$prompt" in
                              *'VERIFICATION FOLLOW-UP CONTEXT'*)
                                ref=$(printf '%s\\n' "$prompt" | sed -n 's/^### \\(SRC-[0-9]*\\) .*entrypoint[.]py.*Kind: TARGET_SOURCE.*/\\1/p' | head -1)
                                [ -n "$ref" ] || exit 93
                                sed "s/ENTRYPOINT_REF/$ref/g" "$fixture/handoff.xml" ;;
                              *)
                                printf '%s\\n' '<verification><status>INSUFFICIENT_EVIDENCE</status><reason>Read the entry point before deciding.</reason><nextCheck>Does the entry point expose the calculation?</nextCheck></verification>'
                                printf '%s\\n' 'REQUEST_FILE|target|entrypoint.py|all|Read the entry point' ;;
                            esac ;;
                          *'<trigger>average([None])</trigger>'*) printf '<verification><status>REJECTED</status><reason>The proposed condition is different.</reason></verification>' ;;
                          *'<triggerCitations>'*'<outcomeCitations>'*) cat "$fixture/verification.xml" ;;
                          *) exit 91 ;;
                        esac ;;
                      'You are the candidate reconciliation'*)
                        if [ -f "$fixture/handoff.xml" ]; then
                          printf '<candidate-groups><group><id>R1-1</id><id>R1-2</id></group></candidate-groups>'
                          exit
                        fi
                        printf '<candidate-groups><group><id>R1-1</id></group>'
                        case "$prompt" in *'<finding id="R1-2">'*) printf '<group><id>R1-2</id></group>' ;; esac
                        printf '</candidate-groups>' ;;
                      'You are the independent publication adjudication'*)
                        if [ -f "$fixture/handoff.xml" ]; then
                          ref=$(printf '%s\\n' "$prompt" | sed -n 's/^### \\(SRC-[0-9]*\\) .*calculator[.]py.*Kind: BASE_SOURCE.*/\\1/p' | head -1)
                          [ -n "$ref" ] || exit 94
                          sed "s/PREFETCHED_BASE/$ref/g" "$fixture/adjudicated.xml"
                        else
                          cat "$fixture/adjudicated.xml"
                        fi ;;
                      *) exit 92 ;;
                    esac
                    """);
            var pipeline = new ReviewPipeline(executor, Clock.systemUTC());
            Path legacy = pipeline.run(options);
            require(Main.reviewExitCode(legacy) == 0 && Files.exists(legacy.resolve("adjudication/R1-1-response.md"))
                    && Files.readString(legacy.resolve("timings.md")).contains("Actual launched attempts: 8\n"),
                    "an otherwise valid legacy decision without linked trigger and outcome gets independent adjudication");
            Files.writeString(response, positive.replace("</verification>", linkedProof(excerpt, excerpt) + "</verification>"));
            Path direct = pipeline.run(options);
            require(Main.reviewExitCode(direct) == 0 && !Files.exists(direct.resolve("adjudication"))
                    && Files.readString(direct.resolve("timings.md")).contains("Actual launched attempts: 6\n")
                    && Files.readString(direct.resolve("verified-findings.md")).equals(Files.readString(legacy.resolve("verified-findings.md"))),
                    "default deep verification saves grouping and adjudication and preserves the published claim and citations");

            Files.writeString(discoveries, "<findings>" + finding.repeat(2) + "</findings>");
            Path duplicate = pipeline.run(options);
            require(Main.reviewExitCode(duplicate) == 0 && !Files.exists(duplicate.resolve("adjudication"))
                    && Files.readString(duplicate.resolve("timings.md")).contains("Actual launched attempts: 6\n")
                    && Files.readString(duplicate.resolve("coverage.md")).contains("Verification reused for R1-2 from R1-1")
                    && Files.readString(duplicate.resolve("verified-findings.md")).equals(Files.readString(direct.resolve("verified-findings.md"))),
                    "two identical claims reuse validated source proof and publish a single finding without another request");

            Files.writeString(discoveries, "<findings>" + finding
                    + finding.replace("<trigger>average([])</trigger>", "<trigger>average([None])</trigger>") + "</findings>");
            Path different = pipeline.run(options);
            require(Main.reviewExitCode(different) == 0 && Files.exists(different.resolve("verification/R1-2-response.md"))
                    && Files.readString(different.resolve("coverage.md")).contains("R1-2 — REJECTED"),
                    "a different trigger at the same location with the same repository sources is independently verified");

            Files.writeString(discoveries, "<findings>" + finding + "</findings>");
            Files.writeString(response, Files.readString(response).replace("</verification>",
                    "<nextCheck>Check whether a caller blocks empty input.</nextCheck></verification>"));
            Path pending = pipeline.run(options);
            require(Main.reviewExitCode(pending) == 0 && Files.exists(pending.resolve("adjudication/R1-1-response.md")),
                    "a linked verdict with an open premise still runs adjudication");

            Files.writeString(discoveries, "<findings>" + finding
                    + finding.replace("Empty-input behavior changed", "Check the empty-input baseline") + "</findings>");
            Files.writeString(response, positive);
            Files.writeString(fixture.resolve("handoff.xml"), """
                    <verification><status>INSUFFICIENT_EVIDENCE</status><reason>The entry point delegates to the summary. The prior guard remains open.</reason>
                    <nextCheck>Did the baseline guard return zero for empty input?</nextCheck>
                    <citations><citation><source>ENTRYPOINT_REF</source><lines>2:2</lines></citation></citations></verification>
                    REQUEST_FILE|base|calculator.py|all|Establish the prior behavior
                    """);
            Files.writeString(fixture.resolve("adjudicated.xml"), positive("R1-1", target.id(), "PREFETCHED_BASE", "return 0\n    " + EXPRESSION));
            for (var reviewOptions : List.of(options, options(command, "--batched-verification"))) {
                Path prefetch = pipeline.run(reviewOptions);
                String prompt = Files.readString(prefetch.resolve("adjudication/R1-1-prompt.md"));
                String coverage = Files.readString(prefetch.resolve("coverage.md"));
                require(Main.reviewExitCode(prefetch) == 0 && coverage.contains("the previous evidence question remains unanswered")
                        && coverage.contains("Preparing evidence for first publication adjudication")
                        && prompt.contains("INDEPENDENT VERIFICATION QUESTIONS FOR THIS ADJUDICATION")
                        && prompt.contains(EvidenceRequest.PATH_GUIDANCE)
                        && prompt.contains("R1-2: Did the baseline guard return zero for empty input?")
                        && prompt.contains("Supplied evidence for this question: SRC-")
                        && prompt.contains("(Kind: BASE_SOURCE;") && !prompt.contains("VERIFICATION TASK")
                        && !Files.exists(prefetch.resolve("adjudication/round-2"))
                        && Files.readString(prefetch.resolve("report.md")).contains("Confirmed defects: 1"),
                        "publication supplies a peer's stopped evidence request and confirms from the pinned baseline in its first call");
            }
        } finally {
            try (var paths = Files.walk(fixture)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
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
            var command = List.of("review", "--repo", repo.toString(), "--review-mode", "deep", "--agent-command", "/bin/sh", "--agent-arg", stub.toString(),
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
            var different = new ReviewFinding("unused", "Handling non-numeric items", ReviewFinding.Severity.MEDIUM, "calculator.py", 2, 2,
                    "average([None])", "Raises TypeError", "The same expression consumes the item.", "Check the input contract.");
            Files.writeString(fixture.resolve("two-findings.xml"), "<findings>" + ReviewOutput.findingXml(candidate("unused"))
                    + ReviewOutput.findingXml(different) + "</findings>");
            Files.writeString(fixture.resolve("duplicate-findings.xml"),
                    "<findings>" + ReviewOutput.findingXml(candidate("unused")).repeat(2) + "</findings>");
            var nullFinding = new ReviewFinding("CANDIDATE_ID", "Null-input behavior changed", ReviewFinding.Severity.MEDIUM, "calculator.py", 2, 2,
                    "average(None)", "The baseline returns 0; the target raises TypeError.", "Compare the pinned implementations.",
                    "The diff removes the null-input guard.");
            Files.writeString(fixture.resolve("null-finding.xml"), "<findings>" + ReviewOutput.findingXml(nullFinding) + "</findings>");
            Files.writeString(fixture.resolve("null-positive.xml"), positive("CANDIDATE_ID", source.id(), diff.id(), REMOVED)
                    .replace(ReviewOutput.findingXml(candidate("CANDIDATE_ID")), ReviewOutput.findingXml(nullFinding))
                    .replace("The target divides by len(values), which is zero for an empty list.", "The target calls sum(None), which raises TypeError."));
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
                          *'Business contracts and compatibility.'*) [ "$2" = beta ] || exit 95 ;;
                          *'Requirements compliance.'*) [ "$2" = beta ] || exit 95 ;;
                          *'Failure handling and recovery.'*|*'Security and isolation.'*|*'Boundary inputs and data conversion.'*)
                            [ "$2" = gamma ] || exit 96 ;;
                          *) exit 93 ;;
                        esac ;;
                      *) [ "$#" -eq 1 ] && [ "$1" = '--model=judge' ] || exit 97 ;;
                    esac
                    printf 'call\\n' >> "$root/calls"
                    case "$prompt" in
                      'You are the discovery stage'*)
                        case "$prompt" in
                          *'Requirements compliance.'*|*'Security and isolation.'*|*'Boundary inputs and data conversion.'*)
                            printf '<findings/>'; exit ;;
                        esac
                        if [ "$2" = gamma ] && [ -f "$root/fail-discovery" ]; then
                          printf 'REQUEST_FILE|target|calculator.py|all|Check the input guard.\n'
                        elif [ "$2" = gamma ] && [ -f "$root/empty-discovery" ]; then printf '<findings/>'
                        elif [ "$2" = beta ] && [ -f "$root/shared-publication" ]; then cat "$root/duplicate-findings.xml"
                        elif [ "$2" = gamma ] && [ -f "$root/different-claim" ]; then cat "$root/null-finding.xml"
                        elif [ -f "$root/pending-only" ]; then
                          if [ "$2" = beta ]; then cat "$root/two-findings.xml"
                          else cat "$root/discovery.xml"; fi
                        else
                          cat "$root/discovery.xml"
                          case "$prompt" in
                            *'CHANGE_SUMMARY|'*) printf '\nCHANGE_SUMMARY|The empty-input guard is removed.\n' ;;
                          esac
                        fi ;;
                      'You are the independent candidate verification'*|'You are the independent publication adjudication'*)
                        candidate=$(printf '%s\\n' "$prompt" | sed -n '/^CANDIDATE TO VERIFY$/{n;p;}')
                        if [ -f "$root/shared-publication" ]; then
                          id=$(printf '%s\\n' "$candidate" | sed -n 's/.*<finding id="\\(R[123]-[12]\\)".*/\\1/p')
                          [ -n "$id" ] || exit 98
                          case "$id:$prompt" in
                            'R3-1:'*)
                              if [ -f "$root/different-claim" ]; then sed "s/CANDIDATE_ID/$id/g" "$root/null-positive.xml"
                              else sed "s/CANDIDATE_ID/$id/g" "$root/positive.xml"; fi ;;
                            'R2-2:You are the independent candidate verification'*)
                              sed -e "s/CANDIDATE_ID/$id/g" -e 's|<severity>MEDIUM</severity>|<severity>LOW</severity>|' "$root/positive.xml" ;;
                            *) sed "s/CANDIDATE_ID/$id/g" "$root/positive.xml";;
                          esac
                          exit
                        fi
                        if [ -f "$root/pending-only" ]; then
                          case "$candidate" in
                            *'id="R2-2"'*) printf '<verification><status>INSUFFICIENT_EVIDENCE</status><reason>The input contract does not specify non-numeric items.</reason></verification>' ;;
                            *) cat "$root/uncertain.xml" ;;
                          esac
                          exit
                        fi
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
                        elif [ -f "$root/shared-publication" ]; then printf '<candidate-groups><group><id>R1-1</id><id>R2-1</id><id>R2-2</id><id>R3-1</id></group></candidate-groups>'
                        elif [ -f "$root/pending-only" ]; then
                          printf '<candidate-groups><group><id>R1-1</id><id>R2-1</id>'
                          case "$prompt" in *'<finding id="R3-1">'*) printf '<id>R3-1</id>' ;; esac
                          printf '</group><group><id>R2-2</id></group></candidate-groups>'
                        else printf '<candidate-groups><group><id>R1-1</id><id>R2-1</id><id>R3-1</id></group></candidate-groups>'; fi ;;
                      'You are the report consolidation'*) cat "$root/report.txt" ;;
                      *) exit 92 ;;
                    esac
                    """);
            Path run = new ReviewPipeline(new ProcessExecutor(), Clock.systemUTC()).run(options);
            String report = Files.readString(run.resolve("report.md"));
            require(report.contains("Confirmed defects: 0; review-required items: 1; unfinished checks: 0")
                    && report.contains("[Review required — conflicting decisions]") && report.contains("ZeroDivisionError")
                    && report.contains("Suggested check: " + NEXT_CHECK)
                    && report.contains("The empty-input guard is removed.")
                    && !Files.exists(run.resolve("report-prompt.md")),
                    "the full pipeline surfaces the playground compatibility risk in its main findings");
            require(!Files.exists(run.resolve("discovery/R1-repair-response.md"))
                    && Files.readString(run.resolve("scope.md")).contains("Discovery models (R1, R2, R3): alpha, beta, gamma"),
                    "discovery model assignment is recorded for the original request");
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

            // With supplied knowledge, the report model still cannot put pending IDs into confirmed findings.
            Path knowledge = fixture.resolve("knowledge.md");
            Files.writeString(knowledge, "# Project scope\nReview calculator compatibility.\n");
            var withKnowledge = options(command, "--knowledge", knowledge.toString(), "--output-dir", fixture.resolve("runs").toString());
            String invalidReport = Files.readString(fixture.resolve("report.txt")).replace("<finding-groups/>",
                    "<finding-groups><group><id>R2-1</id></group></finding-groups>");
            Files.writeString(fixture.resolve("report.txt"), invalidReport);
            Path partial = new ReviewPipeline(new ProcessExecutor(), Clock.systemUTC()).run(withKnowledge);
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
            var recover = options(command, "--knowledge", knowledge.toString(), "--resume", partial.toString());
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
            Files.delete(fixture.resolve("fail-groups"));
            sharedWorkflow(fixture, command, options);
            pendingWorkflow(fixture, command, options);
        } finally {
            try (var paths = Files.walk(fixture)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void sharedWorkflow(Path fixture, List<String> command, CliOptions options) throws Exception {
        Files.createFile(fixture.resolve("shared-publication"));
        Path reportReply = fixture.resolve("report.txt");
        String headings = String.join("\n\n", ReviewReport.headings(OutputLanguage.EN));
        Files.writeString(reportReply, headings + "\n<finding-groups><group><id>R1-1</id></group></finding-groups>");
        Path shared = new ReviewPipeline(new ProcessExecutor(), Clock.systemUTC()).run(options);
        String report = Files.readString(shared.resolve("report.md"));
        String coverage = Files.readString(shared.resolve("coverage.md"));
        String timings = Files.readString(shared.resolve("timings.md"));
        require(Main.reviewExitCode(shared) == 0 && report.contains("ZeroDivisionError")
                && timings.contains("|adjudication|2|")
                && coverage.contains("R1-1 is the publication task for R1-1, R2-1, R2-2, R3-1"),
                "four equivalent verifications with different provisional severities share one publication call plus grouping");
        require(List.of("R1-1", "R2-1", "R2-2", "R3-1").stream()
                .allMatch(id -> coverage.contains(id + " — CONFIRMED")
                        && Files.exists(shared.resolve("verification/" + id + "-response.md")))
                && Files.readString(shared.resolve("verification/R1-1-response.md")).contains("<severity>MEDIUM</severity>")
                && Files.readString(shared.resolve("verification/R2-2-response.md")).contains("<severity>LOW</severity>")
                && !Files.exists(shared.resolve("adjudication/R2-1-prompt.md")),
                "one publication decision preserves all four individual verification records");
        long beforeResume = Files.readAllLines(fixture.resolve("calls")).size();
        new ReviewPipeline(new ProcessExecutor(), Clock.systemUTC()).run(options(command, "--resume", shared.toString()));
        require(Files.readAllLines(fixture.resolve("calls")).size() == beforeResume
                && Files.readString(shared.resolve("report.md")).equals(report),
                "resume revalidates and reuses a shared verdict without another model call");

        Path automated = new ReviewPipeline(new ProcessExecutor(), Clock.systemUTC()).run(options(command,
                "--output-dir", fixture.resolve("automatic").toString(),
                "--review-parallelism", "auto", "--review-max-parallelism", "4"));
        require(Main.reviewExitCode(automated) == 0
                && Files.mismatch(shared.resolve("candidates.md"), automated.resolve("candidates.md")) == -1
                && Files.mismatch(shared.resolve("evidence.md"), automated.resolve("evidence.md")) == -1
                && Files.mismatch(shared.resolve("report.md"), automated.resolve("report.md")) == -1
                && Files.readString(automated.resolve("timings.md")).contains("DISCOVERY_READY_TASKS (maximum 4)"),
                "auto scheduling preserves identical discovery, evidence and publication for fixed replies");

        Files.createFile(fixture.resolve("different-claim"));
        Path separate = new ReviewPipeline(new ProcessExecutor(), Clock.systemUTC()).run(options);
        require(Main.reviewExitCode(separate) == 0
                && Files.readString(separate.resolve("timings.md")).contains("|adjudication|3|")
                && Files.readString(separate.resolve("timings.md")).contains("ADJUDICATION_READY_TASKS (maximum 1)")
                && Files.exists(separate.resolve("adjudication/R3-1-prompt.md"))
                && Files.readString(separate.resolve("coverage.md")).contains("R1-1 is the publication task for R1-1, R2-1, R2-2 ("),
                "the exact duplicates share one task and the neighboring claim is adjudicated in its own task");
        String separateReport = Files.readString(separate.resolve("report.md"));
        require(separateReport.lines().filter(line -> line.startsWith("### [MEDIUM]")).count() == 2
                && separateReport.contains("average([])") && separateReport.contains("average(None)")
                && separateReport.contains("`calculator.py:2")
                && !Files.exists(separate.resolve("report-prompt.md")),
                "the report shows both distinct defects once, with their grounded source locations");

        Files.delete(fixture.resolve("shared-publication"));
        Files.delete(fixture.resolve("different-claim"));
        Files.writeString(reportReply, headings + "\n<finding-groups/>");
    }

    private static void pendingWorkflow(Path fixture, List<String> command, CliOptions options) throws Exception {
        Files.createFile(fixture.resolve("pending-only"));
        Path recovered = new ReviewPipeline(new ProcessExecutor(), Clock.systemUTC()).run(options);
        String report = Files.readString(recovered.resolve("report.md"));
        require(Main.reviewExitCode(recovered) == 0
                && report.contains("Confirmed defects: 0; review-required items: 2; unfinished checks: 0")
                && report.contains("R1-1, R2-1, R3-1") && report.contains("Handling non-numeric items"),
                "an all-pending review groups equivalent risks and retains a distinct trigger at the same line");
        String coverage = Files.readString(recovered.resolve("coverage.md"));
        require(List.of("R1-1", "R2-1", "R2-2", "R3-1").stream()
                .allMatch(id -> coverage.contains(id + " — INSUFFICIENT_EVIDENCE"))
                && !Files.exists(recovered.resolve("adjudication/R1-1-prompt.md")),
                "grouping preserves each grounded verdict without another candidate adjudication");
        Files.createFile(fixture.resolve("fail-discovery"));
        Path incomplete = new ReviewPipeline(new ProcessExecutor(), Clock.systemUTC()).run(options);
        require(Main.reviewExitCode(incomplete) == 3
                && Files.readString(incomplete.resolve("review-status.md")).contains("discovery/R3 [INVALID_OUTPUT]")
                && Files.readString(incomplete.resolve("discovery/R3-validation.log")).contains("MISSING_RESPONSE_BLOCK")
                && Files.readString(incomplete.resolve("discovery/R3-validation.log")).split("event=VALIDATION_REJECTED", -1).length == 2
                && !Files.exists(incomplete.resolve("discovery/R3-repair-prompt.md"))
                && Files.readString(incomplete.resolve("report.md")).contains("R1-1, R2-1"),
                "a request-only discovery response fails once without discarding valid peers or calling for format repair");
        Files.delete(fixture.resolve("fail-discovery"));
        Files.createFile(fixture.resolve("empty-discovery"));
        new ReviewPipeline(new ProcessExecutor(), Clock.systemUTC()).run(options(command, "--resume", incomplete.toString()));
        require(Main.reviewExitCode(incomplete) == 0
                && Files.readString(incomplete.resolve("review-status.md")).contains("Status: COMPLETE")
                && Files.readString(incomplete.resolve("discovery/R3-response.md")).equals("<findings/>"),
                "resume only accepts an explicit valid empty result before marking discovery complete");
        Files.delete(fixture.resolve("empty-discovery"));

        Files.createFile(fixture.resolve("fail-groups"));
        Path ungrouped = new ReviewPipeline(new ProcessExecutor(), Clock.systemUTC()).run(options);
        require(Main.reviewExitCode(ungrouped) == 0
                && Files.readString(ungrouped.resolve("report.md")).contains("review-required items: 4")
                && Files.readString(ungrouped.resolve("coverage.md")).contains("Review-required candidates are shown individually.")
                && Files.readString(ungrouped.resolve("adjudication/groups-state.properties")).contains("failureKind=INVALID_OUTPUT"),
                "optional grouping failure retains each valid risk and records its checkpoint without blocking completion");
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
