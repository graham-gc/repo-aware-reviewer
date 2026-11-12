package reviewagent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

final class AssessmentFindingsTests {
    static void run() throws Exception {
        var scope = new ReviewScope(ReviewScope.Mode.COMMIT, "base", "target", "base", "target", "base..target");
        var changed = EvidenceSources.file("target", "runner.py", 290, "runner.py at target", "if success == passed:\n    allow_release()",
                scope, LanguageRegistry.defaults());
        var unchanged = EvidenceSources.file("target", "helper.py", 12, "helper.py at target", "if success == passed:\n    allow_release()",
                scope, LanguageRegistry.defaults());
        var catalog = new EvidenceCatalog(List.of(changed, unchanged), true);
        var index = new EvidenceIndex(catalog);
        var changedCitations = index.bind(List.of(new EvidenceIndex.Citation("SRC-1", changed.text())));
        var unchangedCitations = index.bind(List.of(new EvidenceIndex.Citation("SRC-2", unchanged.text())));
        var unmet = item("A41", ReviewOutput.AssessmentStatus.NOT_SATISFIED, changedCitations);
        var outside = item("A64", ReviewOutput.AssessmentStatus.NOT_SATISFIED, unchangedCitations);
        var pending = item("A68", ReviewOutput.AssessmentStatus.INSUFFICIENT_EVIDENCE, changedCitations);
        var passed = item("A1", ReviewOutput.AssessmentStatus.SATISFIED, changedCitations);
        var evidence = new EvidenceBundle(scope, List.of("runner.py"), "", "", 10_000, null, catalog);
        var items = List.of(unmet, outside, pending, passed);
        var plan = AssessmentFindings.plan(items, evidence, index, OutputLanguage.EN);
        require(plan.origins().size() == 1 && plan.candidates().getFirst().id().equals("REQ-A41")
                && plan.candidates().getFirst().path().equals("runner.py") && plan.unmapped().equals(List.of(outside)),
                "unmet items gain changed-code candidates; unmapped and non-unmet items are not silently invented");
        var candidate = plan.candidates().getFirst();
        var confirmed = new ReviewOutput.Decision(new ReviewOutput.Verification(candidate, ReviewOutput.Status.CONFIRMED, "Proved defect", candidate),
                ReviewOutput.Relation.UNMET_REQUIREMENT, "Compared scope", "Explicit rule", changedCitations);
        var accepted = AssessmentFindings.reconcile(items, plan, List.of(confirmed), OutputLanguage.EN);
        require(accepted.getFirst().status() == ReviewOutput.AssessmentStatus.NOT_SATISFIED
                && accepted.get(1).status() == ReviewOutput.AssessmentStatus.INSUFFICIENT_EVIDENCE,
                "only independently confirmed candidates retain conclusive unmet status");
        var rejected = new ReviewOutput.Decision(new ReviewOutput.Verification(candidate, ReviewOutput.Status.REJECTED, "Existing guard", null),
                ReviewOutput.Relation.UNRESOLVED, "", "", changedCitations);
        var corrected = AssessmentFindings.reconcile(items, plan, List.of(rejected), OutputLanguage.ZH_CN);
        require(corrected.getFirst().status() == ReviewOutput.AssessmentStatus.INSUFFICIENT_EVIDENCE
                && corrected.get(3).status() == ReviewOutput.AssessmentStatus.SATISFIED
                && corrected.getFirst().reasoning().contains("已驳回"), "a rejected allegation never becomes an automatic acceptance pass");
        var completion = ReviewQuality.completion(List.of(confirmed), List.of(confirmed), plan, OutputLanguage.ZH_CN);
        require(!completion.complete() && completion.unmappedRequirements() == 1 && completion.pendingChecks() == 1,
                "unmapped unmet requirements remain visible as incomplete checks");
        var invalidPeer = new ReviewOutput.Decision(new ReviewOutput.Verification(
                new ReviewFinding("R1-1", candidate.title(), candidate.severity(), candidate.path(), candidate.line(), candidate.endLine(),
                        candidate.trigger(), candidate.impact(), candidate.evidence(), candidate.diffRelation()),
                ReviewOutput.Status.INSUFFICIENT_EVIDENCE, "Invalid reply", null, true), ReviewOutput.Relation.UNRESOLVED, "", "", List.of());
        var grouped = ReviewQuality.resolveConflicts(List.of(confirmed, invalidPeer), List.of(List.of(candidate.id(), "R1-1")), OutputLanguage.EN);
        require(grouped.getFirst().verdict().status() == ReviewOutput.Status.CONFIRMED
                && !ReviewQuality.completion(grouped, List.of(), OutputLanguage.EN).complete(),
                "invalid output is not a contrary semantic verdict, while the overall incomplete state is preserved");
        lateFindingsWorkflow();
    }

    private static void lateFindingsWorkflow() throws Exception {
        Path fixture = Files.createTempDirectory("assessment-findings-workflow-");
        Path repo = Files.createDirectory(fixture.resolve("repo"));
        git(repo, "init", "-q");
        Files.writeString(repo.resolve("runner.py"), "def validate_count(value, expected):\n    return False\n");
        git(repo, "add", "--all"); git(repo, "commit", "-qm", "base");
        String code = "def validate_count(value, expected):\n    return value == expected\n";
        Files.writeString(repo.resolve("runner.py"), code);
        git(repo, "add", "--all"); git(repo, "commit", "-qm", "target");
        String rule = "Boolean counters must be rejected.";
        String other = "Count equality must reject mismatched values.";
        Path requirements = fixture.resolve("requirements.md");
        Files.writeString(requirements, rule + "\n" + other + "\n");
        var options = CliOptions.parse(new String[]{"review", "--repo", repo.toString(), "--requirement", requirements.toString()});
        var git = new GitClient(repo, new ProcessExecutor());
        var evidence = new EvidenceCollector(git).collect(git.resolve(options), InputDocument.read(requirements), null,
                CliOptions.KnowledgeMode.FULL, options.maxEvidenceBytes());
        var implementation = evidence.catalog().sources().stream().filter(source -> source.path().equals("runner.py")
                && !source.role(EvidenceSource.Role.DIFF)).findFirst().orElseThrow();
        var contract = evidence.catalog().sources().stream().filter(source -> source.role(EvidenceSource.Role.CONTRACT)).findFirst().orElseThrow();
        String codeCitation = citation(evidence.catalog().reference(implementation), code);
        String contractCitation = citation(evidence.catalog().reference(contract), rule);
        String assessment = "<assessment>" + assessment("A1", rule, "Boolean counters", codeCitation)
                + assessment("A2", other, "Mismatched counts", codeCitation) + "</assessment>";
        var verified = new ReviewFinding("REQ-A1", "Boolean counter accepted", ReviewFinding.Severity.MEDIUM, "runner.py", 2, 2,
                "Boolean True compared with integer one", "A malformed counter is accepted", "Equality accepts bool", "Explicit new requirement");
        String confirmed = "<verification><status>CONFIRMED</status><reason>The explicit type defense is missing.</reason>"
                + "<relation>UNMET_REQUIREMENT</relation><baseline>Compared the target implementation.</baseline><contract>" + rule + "</contract>"
                + "<support>EXPLICIT_DEFENSIVE_REQUIREMENT</support><premises><premise><statement>The exact clause requires boolean rejection.</statement>"
                + "<basis>CONTRACT</basis><citations>" + contractCitation + "</citations></premise></premises><citations>"
                + codeCitation + contractCitation + "</citations>" + ReviewOutput.findingXml(verified) + "</verification>";
        String rejected = "<verification><status>REJECTED</status><reason>Equality already rejects mismatched values.</reason><citations>"
                + codeCitation + "</citations></verification>";
        String report = "<finding-groups><group><id>REQ-A1</id></group></finding-groups>\n"
                + String.join("\n\n", ReviewReport.headings(OutputLanguage.EN));
        String script = """
                prompt=$(cat)
                case "$prompt" in
                  'You are the discovery stage'*) printf '<findings/>' ;;
                  'You are the requirement assessment stage'*|'You are the independent requirement assessment auditor'*) cat <<'ASSESSMENT'
                ASSESSMENT_XML
                ASSESSMENT
                    ;;
                  'You are the independent candidate verification'*|'You are the independent publication adjudication'*)
                    case "$prompt" in
                      *'<finding id="REQ-A1">'*) cat <<'CONFIRMED'
                CONFIRMED_XML
                CONFIRMED
                        ;;
                      *'<finding id="REQ-A2">'*) cat <<'REJECTED'
                REJECTED_XML
                REJECTED
                        ;;
                      *) exit 91 ;;
                    esac ;;
                  'You are the candidate reconciliation'*) printf '<candidate-groups><group><id>REQ-A1</id></group><group><id>REQ-A2</id></group></candidate-groups>' ;;
                  'You are the report consolidation'*) cat <<'REPORT'
                REPORT_XML
                REPORT
                    ;;
                  *) exit 92 ;;
                esac
                """.replace("ASSESSMENT_XML", assessment).replace("CONFIRMED_XML", confirmed).replace("REJECTED_XML", rejected).replace("REPORT_XML", report);
        var runOptions = CliOptions.parse(new String[]{"review", "--repo", repo.toString(), "--requirement", requirements.toString(),
                "--output-dir", fixture.resolve("runs").toString(), "--agent-command", "/bin/sh", "--agent-arg", "-c", "--agent-arg", script});
        Path run = new ReviewPipeline(new ProcessExecutor(), Clock.systemUTC()).run(runOptions);
        String finalReport = Files.readString(run.resolve("report.md"));
        require(finalReport.contains("[MEDIUM] Boolean counter accepted")
                && Files.isRegularFile(run.resolve("verification/requirements/REQ-A1-response.md"))
                && Files.isRegularFile(run.resolve("adjudication/REQ-A1-response.md")),
                "a requirement defect discovered after an empty initial review reaches verification, publication and the final findings");
        String reconciled = Files.readString(run.resolve("requirement-assessment.md"));
        String initial = Files.readString(run.resolve("requirement-assessment-audited.md"));
        require(reconciled.substring(reconciled.indexOf("### A2")).contains("Assessment: INSUFFICIENT_EVIDENCE")
                && initial.substring(initial.indexOf("### A2")).contains("Assessment: NOT_SATISFIED"),
                "a disproved requirement allegation is reconciled without losing the original audit");
        String links = Files.readString(run.resolve("assessment-findings.md"));
        require(links.contains("A1 → REQ-A1: CONFIRMED") && links.contains("A2 → REQ-A2: REJECTED"), "every unmet item has a traceable outcome");
    }

    private static String assessment(String id, String rule, String behavior, String citations) {
        return "<item><id>" + id + "</id><requirement>" + rule + "</requirement><requirementText>" + rule + "</requirementText>"
                + "<behavior>" + behavior + "</behavior><status>NOT_SATISFIED</status><policyBasis>EXPLICIT</policyBasis>"
                + "<policyReason>Exact clause.</policyReason><reasoning>Potential missing guard.</reasoning><boundaries>Input shape.</boundaries>"
                + "<gap>A possible validation gap.</gap><citations>" + citations + "</citations></item>";
    }
    private static String citation(String source, String quote) {
        return "<citation><source>" + source + "</source><quote><![CDATA[" + quote + "]]></quote></citation>";
    }
    private static void git(Path repo, String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("git", "-c", "user.name=Assessment Test", "-c", "user.email=test@example.invalid"));
        command.addAll(List.of(arguments));
        var result = new ProcessExecutor().run(command, repo, null, Duration.ofSeconds(10));
        require(result.exitCode() == 0, "synthetic Git fixture: " + result.stderr());
    }
    private static ReviewOutput.AssessmentItem item(String id, ReviewOutput.AssessmentStatus status, List<EvidenceIndex.Citation> citations) {
        return new ReviewOutput.AssessmentItem(id, "Boolean counters must be rejected.", "Counter type", "Boolean flow counters", status,
                "Equality accepts booleans.", "Checked counter comparisons.", "Strict type validation is missing.", citations,
                ReviewOutput.PolicyBasis.EXPLICIT, "The supplied clause states the counter type.");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
