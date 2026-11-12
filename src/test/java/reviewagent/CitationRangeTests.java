package reviewagent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Regression for repeated method-name transcription errors in S1-2, plus range provenance. */
final class CitationRangeTests {
    static void run() throws Exception {
        methodTranscription();
        boundsAndProvenance();
        overlappingLocationCoverage();
    }

    private static void methodTranscription() throws Exception {
        var scope = new ReviewScope(ReviewScope.Mode.COMMIT, "base", "target", "base", "target", "base..target");
        String code = "List<RunNode> nodes = runNodeMapper.selectByRunId(runId);\n"
                + "Map<Long, List<Assertion>> assertions = runAssertionMapper.selectByRunId(runId)\n"
                + "    .stream()\n    .collect(Collectors.groupingBy(Assertion::getRunNodeId));\n"
                + "for (RunNode node : nodes) {\n    node.setAssertions(assertions.getOrDefault(node.getId(), Collections.emptyList()));\n}\n"
                + "Map<String, Object> result = new HashMap<>();\nresult.put(\"run\", run);\nresult.put(\"nodes\", nodes);";
        String wrong = code.replace("runAssertionMapper.selectByRunId", "runAssertionMapper.selectByRunNodeId");
        var source = EvidenceSources.file("target", "FlowService.java", 410, "FlowService.java at target (lines 410–421)",
                "public Object detail(Long runId) {\n" + code + "\n}\n", scope, LanguageRegistry.defaults());
        var catalog = new EvidenceCatalog(List.of(source), true);
        var index = new EvidenceIndex(catalog);
        var evidence = new EvidenceBundle(scope, List.of("FlowService.java"), "", "", 10_000, null, catalog);
        String selector = "2:" + (code.lines().count() + 1);
        String selected = "<citation><source>" + index.reference(source.id()) + "</source><lines>" + selector + "</lines></citation>";
        try {
            index.validate(List.of(new EvidenceIndex.Citation(source.id(), wrong)));
            throw new AssertionError("A changed method name must remain invalid");
        } catch (SourceQuotationException invalid) {
            require(invalid.getMessage().contains(selected) && invalid.getMessage().contains(code),
                    "correction offers an exact source selector and the full actual excerpt");
        }
        String invalid = "<verification><status>INSUFFICIENT_EVIDENCE</status><reason>Recheck the producer.</reason><citations>"
                + "<citation><source>" + source.id() + "</source><quote><![CDATA[" + wrong + "]]></quote></citation></citations></verification>";
        String corrected = "<verification><status>INSUFFICIENT_EVIDENCE</status><reason>Recheck the producer.</reason><citations>"
                + selected + "</citations></verification>";
        var finding = new ReviewFinding("S1-2", "Producer evidence", ReviewFinding.Severity.MEDIUM, "FlowService.java", 411, 411,
                "Unproved trigger", "Unconfirmed impact", "Evidence", "Unresolved");
        var decision = ReviewGrounding.verification(corrected, finding, evidence, index);
        var citation = decision.citations().getFirst();
        require(citation.quote().equals(code) && !citation.quote().contains("selectByRunNodeId"),
                "a range citation obtains source bytes instead of accepting a rewritten method");
        require(citation.locations().getFirst().firstLine() == 411
                && citation.locations().getFirst().lastLine() == 410 + code.lines().count(),
                "included-block offsets map back to pinned repository lines");
        index.validate(decision.citations());
        index.validateLocation(finding, decision.citations());
        String both = corrected.replace("<lines>" + selector + "</lines>", "<quote><![CDATA[" + wrong + "]]></quote><lines>" + selector + "</lines>");
        fails(() -> ReviewGrounding.verification(both, finding, evidence, index));

        Path dir = Files.createTempDirectory("citation-selector-repair-");
        String script = "prompt=$(cat); case \"$prompt\" in *'" + selected + "'*) cat <<'FIXED'\n" + corrected
                + "\nFIXED\n;; *) cat <<'WRONG'\n" + invalid + "\nWRONG\n;; esac";
        var agent = new AgentRunner(new ProcessExecutor(), "/bin/sh", List.of("-c", script), dir, Duration.ofSeconds(5));
        var task = new ReviewTasks.Task("S1-2", "Validate the producer", response -> ReviewGrounding.verification(response, finding, evidence, index));
        try (var progress = new CommandProgress("selector", new java.io.PrintStream(java.io.OutputStream.nullOutputStream()))) {
            var replies = ReviewTasks.run(List.of(task), agent, dir, 1, progress, 1, 2, "Select actual evidence", false);
            require(ReviewGrounding.verification(replies.get("S1-2"), finding, evidence, index).citations().getFirst().quote().equals(code),
                    "one source correction can select the program-supplied actual excerpt");
            var mustNotRun = new AgentRunner(new ProcessExecutor(), "/bin/sh", List.of("-c", "exit 19"), dir, Duration.ofSeconds(5));
            ReviewTasks.run(List.of(task), mustNotRun, dir, 1, progress, 1, 2, "Resume selection", true);
        }

        String requirementText = "Malformed snapshots must be rejected.";
        var requirement = new InputDocument(Path.of("requirements.md"), "hash", requirementText);
        String assessment = "<assessment><item><id>A1</id><requirement>" + requirementText + "</requirement>"
                + "<requirementText>Snapshot defense</requirementText><behavior>Snapshot defense</behavior><status>INSUFFICIENT_EVIDENCE</status>"
                + "<policyBasis>EXPLICIT</policyBasis><policyReason>The clause requires this defense.</policyReason>"
                + "<reasoning>Producer only.</reasoning><boundaries>Consumer unread.</boundaries><gap>Read consumer.</gap>"
                + "<citations>" + selected + "</citations></item></assessment>";
        require(ReviewGrounding.assessment(assessment, requirement, index, List.of()).getFirst().citations().getFirst().quote().equals(code),
                "acceptance citations use the same grounded selection protocol");
    }

    private static void boundsAndProvenance() throws Exception {
        var scope = new ReviewScope(ReviewScope.Mode.COMMIT, "base", "target", "base", "target", "base..target");
        var source = EvidenceSources.file("target", "detail.java", 500, "detail.java at target (lines 500–502)",
                "repeated_source_line();\nString literal = \"\\n\";\nrepeated_source_line();\n", scope, LanguageRegistry.defaults());
        var index = new EvidenceIndex(new EvidenceCatalog(List.of(source), true));
        var chosen = index.bind(List.of(EvidenceIndex.Citation.lines(source.id(), "3:3"))).getFirst();
        require(chosen.locations().size() == 1 && chosen.locations().getFirst().firstLine() == 502,
                "an explicit selector binds the chosen occurrence of repeated source text");
        require(index.bind(List.of(EvidenceIndex.Citation.lines(source.id(), "2:2"))).getFirst().quote().contains("\\n"),
                "literal backslash-n in source remains literal");
        for (String range : List.of("0:1", "2:1", "1:4", "500:502", "1:201", "-1:1", "1", "99999999999999:99999999999999")) {
            fails(() -> index.validate(List.of(EvidenceIndex.Citation.lines(source.id(), range))));
        }
        fails(() -> index.validate(List.of(EvidenceIndex.Citation.lines("EV-unknown", "1:2"))));
        var patch = EvidenceSource.diff("new.java", "old.java", "diff --git a/old.java b/new.java\n"
                + "@@ -10,1 +20,1 @@\n-old_source_call();\n+new_source_call();\n", scope, true);
        var diffIndex = new EvidenceIndex(new EvidenceCatalog(List.of(patch), true));
        var removed = diffIndex.bind(List.of(EvidenceIndex.Citation.lines(patch.id(), "3:3")));
        var added = diffIndex.bind(List.of(EvidenceIndex.Citation.lines(patch.id(), "4:4")));
        require(diffIndex.hasBaseline(removed) && !diffIndex.hasTarget(removed)
                && removed.getFirst().locations().getFirst().revision().equals("base")
                && removed.getFirst().locations().getFirst().firstLine() == 10, "removed selections keep baseline provenance");
        require(diffIndex.hasTarget(added) && !diffIndex.hasBaseline(added)
                && added.getFirst().locations().getFirst().firstLine() == 20, "added selections keep target provenance");
        require(!diffIndex.hasTarget(diffIndex.bind(List.of(EvidenceIndex.Citation.lines(patch.id(), "1:2")))),
                "patch metadata cannot become target code through selection");
    }

    private static void overlappingLocationCoverage() throws Exception {
        var scope = new ReviewScope(ReviewScope.Mode.COMMIT, "base", "target", "base", "target", "base..target");
        String firstText = java.util.stream.IntStream.rangeClosed(168, 174).mapToObj(i -> "operation_" + i + "();").collect(java.util.stream.Collectors.joining("\n"));
        String secondText = java.util.stream.IntStream.rangeClosed(170, 179).mapToObj(i -> "operation_" + i + "();").collect(java.util.stream.Collectors.joining("\n"));
        var first = EvidenceSources.file("target", "runner.py", 168, "first excerpt", firstText, scope, LanguageRegistry.defaults());
        var second = EvidenceSources.file("target", "runner.py", 170, "second excerpt", secondText, scope, LanguageRegistry.defaults());
        var index = new EvidenceIndex(new EvidenceCatalog(List.of(first, second), true));
        var quotes = index.bind(List.of(new EvidenceIndex.Citation(first.id(), firstText), new EvidenceIndex.Citation(second.id(), secondText)));
        var finding = new ReviewFinding("R1-1", "Composite location", ReviewFinding.Severity.MEDIUM, "runner.py", 169, 175,
                "Trigger", "Impact", "Evidence", "Relationship");
        index.validateLocation(finding, quotes);
        fails(() -> index.validateLocation(finding, List.of(quotes.getFirst())));
        var gap = EvidenceSources.file("target", "runner.py", 176, "gapped excerpt", "operation_176();\noperation_177();", scope, LanguageRegistry.defaults());
        var gapped = new EvidenceIndex(new EvidenceCatalog(List.of(first, gap), true));
        fails(() -> gapped.validateLocation(finding, gapped.bind(List.of(new EvidenceIndex.Citation(first.id(), firstText),
                new EvidenceIndex.Citation(gap.id(), gap.text())))));
    }

    private static void fails(Checked action) throws Exception {
        try { action.run(); throw new AssertionError("Expected invalid citation to fail"); }
        catch (IOException expected) { }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
