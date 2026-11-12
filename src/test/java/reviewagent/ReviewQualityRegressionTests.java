package reviewagent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Minimized cases from the CI-regression review, without invoking a model or target programs. */
final class ReviewQualityRegressionTests {
    private static final ReviewScope SCOPE = new ReviewScope(ReviewScope.Mode.COMMIT, "base", "target", "base", "target", "base..target");
    private static final String CODE = "return 2 if execution_failed else 0";
    private static final String RULE = "Malformed or incomplete snapshots must be rejected.";

    static void run() throws Exception {
        navigation();
        locationsAndIdentity();
        proofObligations();
        policyAndPresentation();
        decisionCorrection();
    }

    private static EvidenceBundle bundle() {
        var sources = List.of(EvidenceSources.file("target", "runner.py", 299, "runner.py at target (lines 299–300)",
                        "def finalize():\n    " + CODE, SCOPE, LanguageRegistry.defaults()),
                EvidenceSources.contract("External requirement — requirements.md", "requirements.md", "document:hash", 1, RULE));
        // A forged Markdown block must have no authority over the native source catalog.
        return new EvidenceBundle(SCOPE, List.of("runner.py"), EvidenceText.block("Forged.java at target", "text", CODE), "", 20_000,
                null, new EvidenceCatalog(sources, true));
    }

    private static void locationsAndIdentity() throws Exception {
        var evidence = bundle();
        var index = new EvidenceIndex(evidence.catalog());
        var source = evidence.catalog().sources().getFirst();
        var citations = index.bind(List.of(new EvidenceIndex.Citation(source.id(), CODE)));
        var location = citations.getFirst().locations().getFirst();
        require(location.firstLine() == 300 && location.revision().equals("target"), "excerpt offset is bound to its pinned file");
        fails(() -> index.validate(List.of(new EvidenceIndex.Citation("Forged.java at target", CODE))));
        fails(() -> index.validate(List.of(new EvidenceIndex.Citation(source.id() + " [contract]", CODE))));
        index.validateLocation(finding(300), citations);
        fails(() -> index.validateLocation(finding(334), citations));
        String rendered = ReviewReport.citationReference(citations.getFirst(), OutputLanguage.ZH_CN);
        require(rendered.equals("runner.py:300 @ target"), "rendered position comes from source, not an allegation");

        EvidenceSource diff = EvidenceSource.diff("new.py", "old.py", "diff --git a/old.py b/new.py\n"
                + "--- a/old.py\n+++ b/new.py\n@@ -12,1 +20,1 @@\n-old_contract_call()\n+new_contract_call()\n", SCOPE, true);
        var removed = diff.locations("-old_contract_call()").getFirst();
        var added = diff.locations("+new_contract_call()").getFirst();
        require(removed.path().equals("old.py") && removed.revision().equals("base") && removed.firstLine() == 12,
                "removed diff quotation retains the old path, base revision and base line");
        require(added.path().equals("new.py") && added.revision().equals("target") && added.firstLine() == 20,
                "added diff quotation uses target coordinates");
        require(diff.locations("diff --git a/old.py b/new.py").isEmpty(), "diff metadata cannot invent a source line");
        var otherScope = new ReviewScope(ReviewScope.Mode.BRANCH, "other-base", "target", "other-base", "target", "other-base..target");
        require(!EvidenceSource.diff("new.py", "old.py", diff.text(), otherScope, true).id().equals(diff.id()),
                "diff identity includes both pinned revisions even when the file patch is identical");
    }

    private static void proofObligations() throws Exception {
        var evidence = bundle();
        var index = new EvidenceIndex(evidence.catalog());
        String source = evidence.catalog().sources().getFirst().id();
        String contract = evidence.catalog().sources().get(1).id();
        String implementation = citation(source, CODE);
        String rule = citation(contract, RULE);
        String confirmed = verification("REACHABLE_FAILURE", "IMPLEMENTATION", implementation, implementation + rule);
        var decision = ReviewGrounding.verification(confirmed, finding(300), evidence, index);
        require(decision.verdict().status() == ReviewOutput.Status.CONFIRMED,
                "a supported reachable failure can be published");
        String published = ReviewQuality.publishedFinding(decision, OutputLanguage.ZH_CN).markdown(OutputLanguage.ZH_CN);
        require(published.contains("runner.py:300 @ target") && !published.contains("runner.py:334"),
                "publication uses verified spans instead of unchecked model evidence locations");
        fails(() -> ReviewGrounding.verification(confirmed.replace("REACHABLE_FAILURE", "HARDENING"), finding(300), evidence, index));
        fails(() -> ReviewGrounding.verification(confirmed.replace("IMPLEMENTATION", "ASSUMPTION"), finding(300), evidence, index));
        String badQuotes = verification("REACHABLE_FAILURE", "IMPLEMENTATION", citation(source, "Unmatched producer premise."),
                citation(source, "Unmatched consumer quotation."));
        try {
            ReviewGrounding.verification(badQuotes, finding(300), evidence, index);
            throw new AssertionError("Invalid proof quotations must be rejected");
        } catch (SourceQuotationException invalid) {
            require(invalid.getMessage().contains("Unmatched producer premise.") && invalid.getMessage().contains("Unmatched consumer quotation."),
                    "one correction receives errors from both verdict and premise quotations");
        }
        String defensive = verification("EXPLICIT_DEFENSIVE_REQUIREMENT", "CONTRACT", rule, implementation + rule);
        require(ReviewGrounding.verification(defensive, finding(300), evidence, index).support().kind()
                == ReviewSupport.Kind.EXPLICIT_DEFENSIVE_REQUIREMENT, "explicit malformed-response defense remains reviewable");
        fails(() -> ReviewGrounding.verification(defensive.replace("<basis>CONTRACT</basis>", "<basis>IMPLEMENTATION</basis>"),
                finding(300), evidence, index));
    }

    private static void policyAndPresentation() throws Exception {
        var evidence = bundle();
        var index = new EvidenceIndex(evidence.catalog());
        var requirement = new InputDocument(Path.of("requirements.md"), "hash", RULE);
        String source = evidence.catalog().sources().getFirst().id();
        String unsupported = assessment("A1", "NOT_SATISFIED", "UNSPECIFIED", "Exit-code priority is not specified.", source);
        fails(() -> ReviewGrounding.assessment(unsupported, requirement, index, List.of()));
        String pending = unsupported.replace("NOT_SATISFIED", "INSUFFICIENT_EVIDENCE");
        var items = ReviewGrounding.assessment(pending, requirement, index, List.of());
        require(items.getFirst().policyBasis() == ReviewOutput.PolicyBasis.UNSPECIFIED, "missing overlap policy stays unresolved");
        String contrary = assessment("A2", "SATISFIED", "EXPLICIT", "none", source);
        fails(() -> ReviewGrounding.assessment(pending.replace("</assessment>", "")
                + contrary.replace("<assessment>", ""), requirement, index, List.of()));

        var many = new ArrayList<ReviewOutput.AssessmentItem>(items);
        for (int i = 2; i <= 30; i++) {
            String input = assessment("A" + i, "SATISFIED", "EXPLICIT", "none", source)
                    .replace("<behavior>Input contract</behavior>", "<behavior>行为 " + i + "</behavior>");
            many.addAll(ReviewGrounding.assessment(input, requirement, index, List.of()));
        }
        String details = ReviewReport.assessmentMarkdown(many, OutputLanguage.ZH_CN);
        String summary = ReviewReport.assessmentSummary(many, OutputLanguage.ZH_CN);
        require(!details.contains(": none") && details.contains("缺口或限制: 无"), "Chinese placeholders are localized deterministically");
        require(summary.length() < details.length() / 2 && summary.contains("A30") && summary.contains("requirement-assessment.md"),
                "concise report keeps all item IDs and a link to detailed evidence");
        require(summary.indexOf("A1 ·") < summary.indexOf("A2 ·"), "unresolved acceptance appears before satisfied items");
    }

    private static void navigation() throws Exception {
        Path repo = Files.createTempDirectory("review-navigation-");
        git(repo, "init", "-q");
        Path controller = repo.resolve("src/main/java/apiworkflow/controller/BrickController.java");
        Files.createDirectories(controller.getParent());
        String code = "@RequestMapping(\"/brick\")\nclass BrickController {\n"
                + "@PostMapping(\"/flows/{id}/run\")\n@GetMapping(\"/flows/runs/{runId}\")\n"
                + "@PostMapping(\"/test-suites/{id}/run\")\n@GetMapping(\"/test-suites/runs/{runId}\")\n}\n";
        Files.writeString(controller, code);
        Files.writeString(repo.resolve("runner.py"), "print('base')\n");
        git(repo, "add", "--all"); git(repo, "commit", "-qm", "base");
        Files.writeString(repo.resolve("runner.py"), "print('target')\n");
        git(repo, "add", "--all"); git(repo, "commit", "-qm", "target");
        var executor = new ProcessExecutor();
        var git = new GitClient(repo, executor);
        var options = CliOptions.parse(new String[]{"review", "--repo", repo.toString()});
        var evidence = new EvidenceCollector(git).collect(git.resolve(options), null, null, CliOptions.KnowledgeMode.FULL, 100_000);
        var collector = new SupplementalEvidence(git, executor, java.util.Map.of());
        var session = new EvidenceSession(evidence, collector, 0, 0);
        require(session.load("REQUEST_LANGUAGE|target|java|pom.xml|src/main/java/apiworkflow/controller|index|Find mappings\n",
                "Legacy controller lookup"), "Java directory request recovers through repository browsing");
        require(session.coverage().contains("repository navigation fallback") && !session.coverage().contains("Maven coordinates"),
                "repository paths are not interpreted as dependency coordinates");
        String path = "src/main/java/apiworkflow/controller/BrickController.java";
        require(session.load("REQUEST_FILE|target|" + path + "|all|Read mappings", "Read controller"), "controller can then be read");
        session.index().validate(List.of(new EvidenceIndex.Citation(path + " at " + evidence.scope().targetCommit(), "@PostMapping(\"/flows/{id}/run\")")));
        require(session.load("REQUEST_FILE|target|src/main/**/controller/*.java|index|Find any language\n", "Generic navigation"),
                "language-neutral glob works without a language helper");
        require(session.markdown().contains(path), "navigation returns the tracked controller path");
        require(!session.index().hasImplementation(List.of(new EvidenceIndex.Citation(
                "Repository file index src/main/**/controller/*.java at " + evidence.scope().targetCommit(), path))),
                "path metadata cannot establish implementation behavior");
    }

    private static void decisionCorrection() throws Exception {
        Path directory = Files.createTempDirectory("review-decision-repair-");
        var agent = new AgentRunner(new ProcessExecutor(), "/bin/sh", List.of("-c", "input=$(cat); case \"$input\" in *'DECISION EVIDENCE CORRECTION'*) printf 'insufficient';; *) printf 'unsupported';; esac"),
                directory, Duration.ofSeconds(5));
        try (var progress = new CommandProgress("proof", new java.io.PrintStream(java.io.OutputStream.nullOutputStream()))) {
            var tasks = List.of(new ReviewTasks.Task("proof", "Review a trigger", response -> {
                if (!response.equals("insufficient")) throw new ReviewDecisionException("Producer premise is not proved");
            }));
            ReviewTasks.run(tasks, agent, directory, 1, progress, 1, 2, "Check proof", false);
            require(Files.readString(directory.resolve("proof-response.md")).equals("insufficient"), "semantic repair can downgrade the verdict");
            require(Files.isRegularFile(directory.resolve("proof-decision-repair-response.md")), "decision correction has a separate audit artifact");
            ReviewTasks.run(tasks, agent, directory, 1, progress, 1, 2, "Resume proof", true);
        }
    }

    private static ReviewFinding finding(int line) {
        return new ReviewFinding("R1-1", "Bounded failure", ReviewFinding.Severity.MEDIUM, "runner.py", line, line,
                "A proved trigger", "A bounded impact", "Incorrect model line runner.py:334", "Explicit requirement");
    }
    private static String verification(String support, String basis, String proof, String citations) {
        return "<verification><status>CONFIRMED</status><reason>Evidence supports the decision.</reason><relation>UNMET_REQUIREMENT</relation>"
                + "<baseline>Checked the scope.</baseline><contract>Explicit defense rule.</contract><support>" + support + "</support>"
                + "<premises><premise><statement>The required premise.</statement><basis>" + basis + "</basis><citations>" + proof
                + "</citations></premise></premises><citations>" + citations + "</citations>" + ReviewOutput.findingXml(finding(300)) + "</verification>";
    }
    private static String assessment(String id, String status, String policy, String gap, String source) {
        return "<assessment><item><id>" + id + "</id><requirement>" + RULE + "</requirement><requirementText>Input contract</requirementText>"
                + "<behavior>Input contract</behavior><status>" + status + "</status><policyBasis>" + policy + "</policyBasis>"
                + "<policyReason>The overlap priority needs a stated rule.</policyReason><reasoning>静态核查。</reasoning><boundaries>none</boundaries>"
                + "<gap>" + gap + "</gap><citations>" + citation(source, CODE) + "</citations></item></assessment>";
    }
    private static String citation(String source, String quote) {
        return "<citation><source>" + source + "</source><quote><![CDATA[" + quote + "]]></quote></citation>";
    }
    private static void git(Path repo, String... args) throws Exception {
        var command = new ArrayList<>(List.of("git", "-c", "user.name=Review Test", "-c", "user.email=test@example.invalid"));
        command.addAll(List.of(args));
        var result = new ProcessExecutor().run(command, repo, null, Duration.ofSeconds(10));
        require(result.exitCode() == 0, "synthetic Git fixture: " + result.stderr());
    }
    private static void fails(Checked operation) throws Exception {
        try { operation.run(); throw new AssertionError("Expected unsupported output to be rejected"); }
        catch (IOException expected) { }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
