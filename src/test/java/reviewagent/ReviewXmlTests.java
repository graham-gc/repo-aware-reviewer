package reviewagent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Protocol-level cases: unique delimiter recovery, opaque source text, and fail-closed ambiguity. */
final class ReviewXmlTests {
    private static final String RULE = "Incomplete snapshots must not be accepted.";

    static void run() throws Exception {
        damagedQuoteDoesNotBecomeEvidence();
        missingContainerClosers();
        opaqueTextAndEnvelope();
        rejectedStructures();
        normalizationArtifacts();
    }

    private static String damaged(String source) {
        return "<verification>\n<status>INSUFFICIENT_EVIDENCE</status>\n<reason>Recheck the contract.</reason>\n"
                + "<premises>\n<premise>\n<statement>Required defense.</statement>\n<basis>CONTRACT</basis>\n<citations>\n<citation>\n"
                + "<source>" + source + "</source>\n<quote>" + RULE + "]<]minimax[>[quote>\n"
                + "</citation>\n</citations>\n</premise>\n</premises>\n<citations>\n"
                + "<citation><source>" + source + "</source><quote>" + RULE + "</quote></citation>\n"
                + "</citations>\n<baseline>Checked the original scope.</baseline>\n</verification>";
    }

    private static void damagedQuoteDoesNotBecomeEvidence() throws Exception {
        String xml = damaged("rules");
        var parsed = ReviewXml.xml(xml, "verification");
        require(parsed.normalizations().size() == 1 && parsed.normalizations().getFirst().contains("</quote>"),
                "one unique missing quote delimiter is recorded");
        require(parsed.root().getElementsByTagName("premise").getLength() == 1
                && parsed.root().getElementsByTagName("citation").getLength() == 2, "the following citation is not swallowed into text");
        String first = parsed.root().getElementsByTagName("quote").item(0).getTextContent();
        require(first.strip().equals(RULE + "]<]minimax[>[quote>"), "corrupt content is retained verbatim, never guessed or removed");
        var scope = new ReviewScope(ReviewScope.Mode.COMMIT, "base", "target", "base", "target", "base..target");
        var source = EvidenceSources.contract("rules", "requirements.md", "document:hash", 1, RULE);
        var catalog = new EvidenceCatalog(List.of(source), true);
        var evidence = new EvidenceBundle(scope, List.of("runner.py"), "", "", 10_000, null, catalog);
        try {
            ReviewGrounding.verification(xml, candidate(), evidence, new EvidenceIndex(catalog));
            throw new AssertionError("Delimiter repair must not approve corrupt evidence");
        } catch (SourceQuotationException invalid) {
            require(invalid.getMessage().contains("minimax") && invalid.getMessage().contains("<lines>1:1</lines>"),
                    "the normal source correction handles the preserved bad quotation");
        }
    }

    private static void missingContainerClosers() throws Exception {
        String premise = "<verification>\n<status>INSUFFICIENT_EVIDENCE</status>\n<reason>More evidence needed.</reason>\n"
                + "<premises><premise><statement>Claim.</statement><basis>ASSUMPTION</basis><citations/>\n"
                + "</premises>\n</verification>";
        require(ReviewXml.xml(premise, "verification").normalizations().getFirst().contains("</premise>"), "missing premise closer is unambiguous");
        String two = "<verification><status>INSUFFICIENT_EVIDENCE</status><reason>More evidence needed.</reason>"
                + "<premises><premise><statement>Claim.</statement><basis>CONTRACT</basis><citations><citation>"
                + "<source>rules</source><quote>" + RULE + "</quote></premise></premises></verification>";
        require(ReviewXml.xml(two, "verification").normalizations().getFirst().contains("</citation></citations>"),
                "bounded adjacent container closers can be restored without creating values");
        String groups = "<candidate-groups><group><id>R1-1</id></candidate-groups>";
        require(ReviewOutput.candidateGroups(groups, List.of(candidate())).getFirst().equals(List.of("R1-1")),
                "grouping shares the same structural parser");
        String duplicate = premise.replace("</reason>", "</reason><status>CONFIRMED</status>");
        fails(() -> ReviewXml.xml(duplicate, "verification"));
    }

    private static void opaqueTextAndEnvelope() throws Exception {
        for (String literal : List.of("List<Node> nodes; if (a < b && c > d) run();", "</citation>\n<citation><source>literal</source></citation>",
                "String marker = \"</verification><verification>\";", "A literal </quote> is source text.", "raw ]]> content")) {
            String body = "<![CDATA[" + literal.replace("]]>", "]]]]><![CDATA[>") + "]]>";
            String xml = "<verification><status>INSUFFICIENT_EVIDENCE</status><reason>Source data.</reason><citations><citation>"
                    + "<source>rules</source><quote>" + body + "</quote></citation></citations></verification>";
            var parsed = ReviewXml.xml(xml, "verification");
            require(parsed.normalizations().isEmpty() && parsed.root().getElementsByTagName("quote").item(0).getTextContent().equals(literal),
                    "protocol tags, operators and CDATA terminators inside source remain opaque");
        }
        String xml = "<verification><status>REJECTED</status><reason>Existing guard.</reason></verification>";
        for (String fence : List.of("```xml", "~~~~XML")) {
            String close = fence.startsWith("`") ? "```" : "~~~~";
            String prefix = "REQUEST_FILE|target|before.py|all|Before\r\n";
            String suffix = "REQUEST_FILE|base|after.py|all|After\r\n";
            var parsed = ReviewXml.xml(prefix + fence + "\r\n<?xml version=\"1.0\"?>\r\n<!-- protocol envelope -->\r\n"
                    + xml.replace("<status>", "<!-- status comment --><?review harmless?><status>")
                    + "\r\n" + close + "\r\n" + suffix, "verification");
            require(parsed.remainder().equals(prefix + suffix), "CRLF/fence normalization preserves request boundaries exactly");
        }
        String missing = "REQUEST_FILE|target|before.py|all|Before\n" + damaged("rules") + "\nREQUEST_FILE|base|after.py|all|After\n";
        require(EvidenceRequest.parse(ReviewXml.xml(missing, "verification").remainder()).size() == 2,
                "delimiter insertion does not consume adjacent evidence requests");
    }

    private static void rejectedStructures() throws Exception {
        String xml = "<verification><status>REJECTED</status><reason>Existing guard.</reason></verification>";
        fails(() -> ReviewXml.xml(xml + xml, "verification"));
        fails(() -> ReviewXml.xml(xml.replace("</verification>", ""), "verification"));
        fails(() -> ReviewXml.xml(xml.replace("</reason>", "</wrong>"), "verification"));
        fails(() -> ReviewXml.xml(xml.replace("</reason>", "</reason><unknown/>"), "verification"));
        fails(() -> ReviewXml.xml(xml.replace("</status>", "</status><status>CONFIRMED</status>"), "verification"));
        fails(() -> ReviewXml.xml(damaged("rules").replace("<baseline>Checked the original scope.</baseline>\n", ""), "verification"));
        fails(() -> ReviewOutput.verification(xml.replace("<status>REJECTED</status>", ""), candidate(), List.of("runner.py")));
        String swallowed = "<verification>\n<status>INSUFFICIENT_EVIDENCE</status><reason>Ambiguous text.</reason><citations>\n"
                + "<citation><source>rules</source><quote>First literal text.\n</citation>\n"
                + "<citation><source>rules</source><quote>Second literal text.</quote></citation>\n</citations></verification>";
        try { ReviewXml.xml(swallowed, "verification"); throw new AssertionError("Sibling swallowing must be detected"); }
        catch (AmbiguousTextFieldException expected) { require(expected.getMessage().contains("CDATA"), "ambiguous source markup needs explicit encoding"); }
    }

    private static void normalizationArtifacts() throws Exception {
        var scope = new ReviewScope(ReviewScope.Mode.COMMIT, "base", "target", "base", "target", "base..target");
        var source = EvidenceSources.contract("rules", "requirements.md", "document:hash", 1, RULE);
        var catalog = new EvidenceCatalog(List.of(source), true);
        var evidence = new EvidenceBundle(scope, List.of("runner.py"), "", "", 10_000, null, catalog);
        String corrected = "<verification><status>INSUFFICIENT_EVIDENCE</status><reason>Rechecked actual contract.</reason>"
                + "<citations><citation><source>" + source.id() + "</source><lines>1:1</lines></citation></citations></verification>";
        String script = "prompt=$(cat); case \"$prompt\" in *'SOURCE QUOTATION CORRECTION'*) cat <<'FIXED'\n" + corrected
                + "\nFIXED\n;; *) cat <<'DAMAGED'\n" + damaged(source.id()) + "\nDAMAGED\n;; esac";
        Path dir = Files.createTempDirectory("xml-normalization-artifacts-");
        var agent = new AgentRunner(new ProcessExecutor(), "/bin/sh", List.of("-c", script), dir, Duration.ofSeconds(5));
        var task = new ReviewTasks.Task("R1-1", "Recheck the source", response -> ReviewGrounding.verification(response, candidate(), evidence, new EvidenceIndex(catalog)));
        try (var progress = new CommandProgress("xml", new java.io.PrintStream(java.io.OutputStream.nullOutputStream()))) {
            ReviewTasks.run(List.of(task), agent, dir, 1, progress, 1, 2, "Parser recovery", false);
        }
        require(Files.readString(dir.resolve("R1-1-normalization.log")).contains("Inserted missing </quote>")
                && Files.readString(dir.resolve("R1-1-quote-invalid-response.md")).contains("minimax"),
                "delimiter changes and the original bad response are recorded separately");
        require(!Files.exists(dir.resolve("R1-1-repair-response.md")) && Files.isRegularFile(dir.resolve("R1-1-quote-repair-response.md")),
                "unique syntax recovery leaves the independent source correction available");
    }

    private static ReviewFinding candidate() {
        return new ReviewFinding("R1-1", "Protocol test", ReviewFinding.Severity.MEDIUM, "runner.py", 1, 1,
                "Unverified", "Unverified", "Source", "Unresolved");
    }
    private static void fails(Checked action) throws Exception {
        try { action.run(); throw new AssertionError("Expected invalid protocol to remain rejected"); }
        catch (IOException expected) { }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
