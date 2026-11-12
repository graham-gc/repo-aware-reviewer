package reviewagent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** A citation correction must not damage an already valid Markdown requirement, as happened to A28. */
final class AssessmentCorrectionTests {
    private static final String ROW1 = "| Execution failed | Reject the release. |";
    private static final String ROW2 = "| Results not evaluated | Return a validation error. |";
    private static final String CLAUSE = ROW1 + "\n" + ROW2;
    private static final String DAMAGED = ROW1 + "\n| --- | --- |\n" + ROW2;
    private static final InputDocument DOCUMENT = new InputDocument(Path.of("requirements.md"), "doc-hash",
            "# Release rules\n" + CLAUSE + "\nOther explicit rule.\n");
    private static final String CODE = "String literal = \"&lt;\"; if (left < right && ready) check();";

    static void run() throws Exception {
        primaryDocumentRanges();
        stableCriteriaAndItems();
        liveCorrection();
        cachedCorrection();
    }

    private static EvidenceIndex index() {
        var source = EvidenceSource.create("Runner.java at target", "text", CODE, "target", "Runner.java", 1,
                java.util.Set.of(EvidenceSource.Role.TARGET, EvidenceSource.Role.IMPLEMENTATION), true);
        return new EvidenceIndex(new EvidenceCatalog(List.of(source), true));
    }

    private static void primaryDocumentRanges() throws Exception {
        var index = index();
        String damaged = xml(DAMAGED, "SRC-1");
        try {
            ReviewGrounding.assessment(damaged, DOCUMENT, index, List.of());
            throw new AssertionError("Invented separator must not become requirement text");
        } catch (SourceQuotationException invalid) {
            require(invalid.getMessage().contains("<requirementLines>2:3</requirementLines>"), "bad table formatting gets exact original-document coordinates");
        }
        String selected = damaged.replace("<requirement><![CDATA[" + DAMAGED + "]]></requirement>", "<requirementLines>2:3</requirementLines>");
        var items = ReviewGrounding.assessment(selected, DOCUMENT, index, List.of());
        require(items.getFirst().requirement().equals(CLAUSE), "a requirement selector extracts the actual primary document");
        var literal = ReviewGrounding.assessment(xml(CLAUSE, "SRC-1"), DOCUMENT, index, List.of());
        ReviewGrounding.assessment(selected, DOCUMENT, index, literal);
        for (String range : List.of("0:1", "3:2", "1:99", "1:201", "999999999999999:999999999999999")) {
            fails(() -> ReviewGrounding.assessment(selected.replace("2:3", range), DOCUMENT, index, List.of()));
        }
        fails(() -> ReviewGrounding.assessment(selected.replace("<requirementLines>", "<requirement>Other explicit rule.</requirement><requirementLines>"),
                DOCUMENT, index, List.of()));
        require(RequirementReference.hint(DOCUMENT, ROW1 + "\n| Results not evaluated | Allow release. |").isEmpty(),
                "a changed business rule cannot receive a selector from matching formatting alone");
    }

    private static void stableCriteriaAndItems() throws Exception {
        var validator = new AssessmentValidator(DOCUMENT, index(), List.of());
        fails(() -> validator.validate(xml(CLAUSE, "unknown-source")));
        String proposed = xml("Other explicit rule.", "SRC-1");
        String restored = validator.corrected(proposed, new SourceQuotationException("Wrong source"));
        require(ReviewOutput.assessmentItems(restored).getFirst().requirement().equals(CLAUSE), "repair cannot remap an item even to another genuine clause");
        validator.validate(restored);
        String added = restored.replace("</assessment>", "") + restored.replace("<assessment>", "").replace("A28", "A29");
        fails(() -> validator.validate(added));
        var two = new AssessmentValidator(DOCUMENT, index(), List.of());
        two.validate(added);
        fails(() -> two.validate(restored));
    }

    private static void liveCorrection() throws Exception {
        Path dir = Files.createTempDirectory("assessment-criterion-live-");
        String prefix = "REQUEST_FILE|target|before.java|all|Before\n";
        String suffix = "\nREQUEST_FILE|base|after.java|all|After\n";
        // Requests are tested only at the serialization layer: grounding requires an insufficient item, as supplied here.
        String initial = prefix + xml(CLAUSE, "unknown-source") + suffix;
        String badRepair = prefix + xml(DAMAGED, "SRC-1") + suffix;
        String script = "prompt=$(cat); case \"$prompt\" in *'SOURCE QUOTATION CORRECTION'*) cat <<'REPAIRED'\n" + badRepair
                + "\nREPAIRED\n;; *) cat <<'INITIAL'\n" + initial + "\nINITIAL\n;; esac";
        var agent = shell(dir, script);
        var task = new ReviewTasks.Task("audit", "Original audit prompt", new AssessmentValidator(DOCUMENT, index(), List.of()));
        try (var progress = progress()) {
            var results = ReviewTasks.run(List.of(task), agent, dir, 1, progress, 1, 2, "Correct audit", false);
            var items = ReviewGrounding.assessment(results.get("audit"), DOCUMENT, index(), List.of());
            require(items.getFirst().requirement().equals(CLAUSE) && items.getFirst().citations().getFirst().quote().equals(CODE),
                    "citation corrections survive while the primary criterion and literal source entities stay exact");
            require(ReviewOutput.requestText(results.get("audit"), "assessment").strip().equals((prefix + suffix).strip()),
                    "field restoration preserves the envelope and adjacent request lines");
        }
        require(Files.readString(dir.resolve("audit-quote-repair-response.md")).contains(DAMAGED)
                && Files.isRegularFile(dir.resolve("audit-quote-repair-applied-response.md"))
                && Files.readString(dir.resolve("audit-normalization.log")).contains("A28"), "raw correction and applied restoration are separately auditable");
    }

    private static void cachedCorrection() throws Exception {
        Path dir = Files.createTempDirectory("assessment-criterion-cached-");
        Files.writeString(dir.resolve("audit-prompt.md"), "Same audit prompt");
        Files.writeString(dir.resolve("audit-response.md"), xml(CLAUSE, "unknown-source"));
        Files.writeString(dir.resolve("audit-quote-repair-response.md"), xml(DAMAGED, "SRC-1"));
        var task = new ReviewTasks.Task("audit", "Same audit prompt", new AssessmentValidator(DOCUMENT, index(), List.of()));
        try (var progress = progress()) {
            var results = ReviewTasks.run(List.of(task), shell(dir, "exit 97"), dir, 1, progress, 1, 2, "Resume audit", true);
            require(ReviewGrounding.assessment(results.get("audit"), DOCUMENT, index(), List.of()).getFirst().requirement().equals(CLAUSE),
                    "resume restores a previously valid criterion and reuses the corrected citations without a backend call");
        }
        require(Files.readString(dir.resolve("audit-quote-repair-response.md")).contains(DAMAGED), "cached model output remains unchanged");
    }

    private static String xml(String clause, String source) {
        return "<assessment><item><id>A28</id><requirement><![CDATA[" + clause + "]]></requirement>"
                + "<requirementText>Overlap policy</requirementText><behavior>Overlapping failure states</behavior><status>INSUFFICIENT_EVIDENCE</status>"
                + "<policyBasis>UNSPECIFIED</policyBasis><policyReason>No priority stated.</policyReason><reasoning>Needs clarification.</reasoning>"
                + "<boundaries>Both states.</boundaries><gap>Priority is unstated.</gap><citations><citation><source>" + source
                + "</source><quote><![CDATA[" + CODE + "]]></quote></citation></citations></item></assessment>";
    }
    private static CommandProgress progress() { return new CommandProgress("criterion", new java.io.PrintStream(java.io.OutputStream.nullOutputStream())); }
    private static AgentRunner shell(Path dir, String script) { return new AgentRunner(new ProcessExecutor(), "/bin/sh", List.of("-c", script), dir, Duration.ofSeconds(5)); }
    private static void fails(Checked action) throws Exception { try { action.run(); throw new AssertionError("Expected invalid assessment to fail"); } catch (IOException expected) { } }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
