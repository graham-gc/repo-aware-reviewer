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
        repeatedContainerClosers();
        repeatedRootChildCloser();
        opaqueTextAndEnvelope();
        protocolFenceBoundaries();
        missingOpeningBracket();
        rejectedStructures();
        misplacedAdvisories();
        misplacedRequests();
        normalizationArtifacts();
    }

    private static void missingOpeningBracket() throws Exception {
        String body = "verification>\n<status>INSUFFICIENT_EVIDENCE</status>"
                + "\n<reason>The contract is unresolved.</reason>\n</verification>";
        String request = "\nREQUEST_FILE|target|README.md|all|Check the public contract\n";
        var parsed = ReviewXml.xml(body + request, "verification");
        require(parsed.normalizations().size() == 1 && parsed.remainder().equals(request)
                && parsed.root().getElementsByTagName("status").item(0).getTextContent().equals("INSUFFICIENT_EVIDENCE"),
                "restore the leading bracket while preserving the entire closed XML body and its following requests");
        fails(() -> ReviewXml.xml(body.replace("</verification>", ""), "verification"));
        fails(() -> ReviewXml.xml(body.replace("</verification>", "</findings>"), "verification"));
    }

    private static void protocolFenceBoundaries() throws Exception {
        String xml = "<finding-groups/>";
        for (String language : List.of("python", "java", "json", "sql", "xml", "")) {
            String code = language.equals("xml") ? "<example/>" : "literal code; do not rewrite";
            for (String newline : List.of("\n", "\r\n")) {
                String before = "Intro" + newline + "```" + language + newline + code + newline + "```" + newline + newline;
                String after = "Tail" + newline + "```json" + newline + "{}" + newline + "```" + newline;
                for (String[] fence : List.of(new String[]{"```xml", "```"}, new String[]{"~~~~XML", "~~~~~~"},
                        new String[]{"   ````", "  `````"})) {
                    String response = before + fence[0] + newline + "<!-- envelope -->" + newline + xml + newline + fence[1] + newline + after;
                    require(ReviewXml.xml(response, "finding-groups").remainder().equals(before + after),
                            "only the actual protocol wrapper is removed after arbitrary-language fences, preserving all other bytes");
                }
            }
        }
        String prefix = "```python\nprint('keep')\n```\n";
        require(ReviewXml.xml(prefix + "```xml\n" + xml, "finding-groups").remainder().equals(prefix),
                "an EOF-terminated Markdown wrapper does not require inventing an XML closing tag");
        String body = "<verification><status>REJECTED</status><reason><![CDATA[Source example:\n```python\nx = 1\n```\n]]></reason></verification>";
        require(ReviewXml.xml("```xml\n" + body + "\n```\n", "verification").remainder().isEmpty(),
                "fence-like content inside XML scalar data remains opaque");
        fails(() -> ReviewXml.xml("```python\n" + xml + "\n```\n", "finding-groups"));
        fails(() -> ReviewXml.xml("```xml\n<verification><status>REJECTED</status>", "verification"));
        // Preserve a deliberately empty documentation block; this is not a blanket empty-fence cleanup.
        String emptyExample = "```xml\n\n```\n";
        require(ReviewXml.xml(emptyExample + "```xml\n" + xml + "\n```\n", "finding-groups").remainder().equals(emptyExample),
                "unrelated empty XML blocks are preserved exactly");
        String report = String.join("\n\n", ReviewReport.headings(OutputLanguage.EN)).replace("# Functional and business change",
                "# Functional and business change\n\n" + prefix) + "\n```xml\n" + xml + "\n```\n";
        String rendered = ReviewReport.finalReport(report, List.of(), OutputLanguage.EN);
        require(rendered.contains(prefix) && !rendered.contains("```xml"), "the final report contains the example but no orphan protocol fence");
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
        require(ReviewXml.xml(premise, "verification").normalizations().getFirst().contains("jsoup: Inserted missing container closers </premise>"),
                "jsoup agrees with inserting the single missing premise closer");
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

    private static void repeatedContainerClosers() throws Exception {
        String xml = """
                <verification>
                  <status>INSUFFICIENT_EVIDENCE</status>
                  <reason>Trace the check a < b && c > d.</reason>
                  <premises>
                    <premise><statement>First premise.</statement><basis>ASSUMPTION</basis></premise>
                    <premise><statement>Second premise.</statement><basis>IMPLEMENTATION</basis></premise>
                  </premises>
                  <citations><citation><source>SRC-1</source><lines>1:2</lines></citation></citations>
                </verification>
                """;
        String request = "REQUEST_FILE|target|runner.py|all|Check the guard\n";
        String broken = xml.replace("</premises>", "</premise>") + request;
        var parsed = ReviewXml.xml(broken, "verification");
        var fields = ReviewXml.fields(parsed.root(), ReviewSchema.VERIFICATION);
        require(parsed.normalizations().size() == 1
                && parsed.normalizations().getFirst().contains("Replaced repeated </premise> with </premises>")
                && ReviewXml.children(fields.get("citations"), "citation").size() == 1
                && parsed.root().getElementsByTagName("premise").getLength() == 2
                && ReviewXml.text(fields, "status").equals("INSUFFICIENT_EVIDENCE")
                && ReviewXml.text(fields, "reason").equals("Trace the check a < b && c > d.")
                && parsed.remainder().equals("\n" + request),
                "preserve the verdict, two premises, root citations and adjacent file request");

        var citations = ReviewXml.xml(xml.replace("</citations>", "</citation>"), "verification");
        require(citations.normalizations().getFirst().contains("Replaced repeated </citation> with </citations>"),
                "the rule applies to other structurally equivalent containers");
        fails(() -> ReviewXml.xml(xml.replace("</premises>", "</premisse>"), "verification"));
        fails(() -> ReviewXml.xml(xml.replace("</premises>", "</premise></premises>"), "verification"));
        fails(() -> ReviewXml.xml(xml.replace("</premises>", "</premise>").replace("</verification>", ""), "verification"));
        fails(() -> ReviewXml.xml(xml.replace("</premises>", "</premise><unknown/>"), "verification"));
    }

    private static void repeatedRootChildCloser() throws Exception {
        String xml = "<verification>\n<status>INSUFFICIENT_EVIDENCE</status>\n<reason>More evidence needed.</reason>\n"
                + "<finding><id>R1-1</id></finding>\n</finding>\n"
                + "<citations><citation><source>rules</source><quote><![CDATA[if (a < b && c > d) return;]]></quote>"
                + "</citation></citations>\n</verification>";
        String request = "\nREQUEST_FILE|target|runner.py|all|Check the guard\n";
        var parsed = ReviewXml.xml(xml + request, "verification");
        var fields = ReviewXml.fields(parsed.root(), ReviewSchema.VERIFICATION);
        require(parsed.normalizations().size() == 1
                && parsed.normalizations().getFirst().contains("jsoup: Removed repeated </finding>")
                && ReviewXml.children(fields.get("finding"), "id").getFirst().getTextContent().equals("R1-1")
                && fields.get("citations").getElementsByTagName("quote").item(0).getTextContent().equals("if (a < b && c > d) return;")
                && parsed.remainder().equals(request), "keep the finding, following quotation and evidence request after the one-tag edit");
        fails(() -> ReviewXml.xml(xml.replace("</verification>", ""), "verification"));
        fails(() -> ReviewXml.xml(xml.replace("</finding>\n", "</finding></finding>\n"), "verification"));

        String foreign = xml.replace("<verification>", "<verification xmlns=\"urn:foreign-protocol\">");
        ReviewXml.strict(foreign.replace("</finding>\n</finding>", "</finding>"), "verification");
        try {
            ReviewXml.xml(foreign, "verification");
            throw new AssertionError("Jing must veto a repaired reply with a foreign protocol namespace");
        } catch (IOException invalid) {
            require(invalid.getCause() != null && java.util.Arrays.stream(invalid.getCause().getSuppressed())
                            .anyMatch(candidate -> candidate.getMessage().contains("failed RELAX NG validation")),
                    "the final RELAX NG gate can reject a candidate accepted by the existing structure check");
        }
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

    private static void misplacedAdvisories() throws Exception {
        String suggestion = "<nextCheck>Compare the pinned contract.</nextCheck>";
        String candidate = "<local-decision><status>INSUFFICIENT_EVIDENCE</status>"
                + "<reason>The policy is unspecified.</reason><finding><title>Review the contract</title></finding></local-decision>";
        String heading = "<adaptive-review><route>LOCAL</route><summary>Reviewed the change.</summary>";
        String end = "</adaptive-review>";
        for (String response : List.of(
                heading + "<decisions>" + candidate + "</decisions>" + suggestion + end,
                heading + "<decisions>" + candidate + suggestion + "</decisions>" + end,
                heading + "<decisions>" + candidate.replace("</finding>", suggestion + "</finding>") + "</decisions>" + end)) {
            var xml = ReviewXml.xml(response, "adaptive-review");
            var entries = xml.root().getElementsByTagName("local-decision");
            var fields = ReviewXml.fields((org.w3c.dom.Element) entries.item(0), ReviewSchema.LOCAL_DECISION);
            require(ReviewXml.text(fields, "nextCheck").equals("Compare the pinned contract.")
                    && xml.normalizations().size() == 1 && xml.normalizations().getFirst().contains("Moved misplaced advisory"),
                    "a plain optional suggestion with a unique owner is restored from each supported position");
        }

        String ambiguous = heading + "<decisions>" + candidate.repeat(2) + "</decisions>" + suggestion + end;
        var parsed = ReviewXml.xml(ambiguous, "adaptive-review");
        require(parsed.root().getElementsByTagName("local-decision").getLength() == 2
                && parsed.root().getElementsByTagName("nextCheck").getLength() == 0
                && parsed.normalizations().getFirst().contains("raw response retained"),
                "an unassigned suggestion does not change any candidate in a multi-candidate response");
        String own = candidate.replace("</local-decision>", "<nextCheck>Read the API policy.</nextCheck></local-decision>");
        var assigned = ReviewXml.xml(heading + "<decisions>" + own + "</decisions>" + suggestion + end, "adaptive-review");
        require(assigned.root().getElementsByTagName("nextCheck").getLength() == 1
                && assigned.root().getElementsByTagName("nextCheck").item(0).getTextContent().equals("Read the API policy."),
                "a misplaced suggestion cannot override the one assigned to the candidate");
        var confirmed = ReviewXml.xml("<verification><status>CONFIRMED</status><reason>Proved by the code.</reason>"
                + "<finding><title>One confirmed finding</title>" + suggestion + "</finding></verification>", "verification");
        require(confirmed.root().getElementsByTagName("nextCheck").getLength() == 0 && confirmed.normalizations().size() == 1,
                "a misplaced advisory cannot attach to a confirmed verdict");

        fails(() -> ReviewXml.xml(ambiguous.replace(suggestion, "<status>CONFIRMED</status>"), "adaptive-review"));
        fails(() -> ReviewXml.xml(ambiguous.replace(suggestion, "<requests>REQUEST_FILE|target|file|all|Read</requests>"), "adaptive-review"));
        fails(() -> ReviewXml.xml(ambiguous.replace(suggestion, "<citations/>"), "adaptive-review"));
    }

    private static void misplacedRequests() throws Exception {
        String auth = """
                <local-decision><status>INSUFFICIENT_EVIDENCE</status>
                  <reason>Check PolicyGuard before calling the handler.</reason>
                  <finding><path>src/Handler.java</path></finding><requests/>
                </local-decision>
                """;
        String retry = """
                <local-decision><status>INSUFFICIENT_EVIDENCE</status>
                  <reason>Check RetryClient when resuming a delivery.</reason>
                  <finding><path>src/Worker.java</path></finding>
                </local-decision>
                """;
        String policy = "REQUEST_FILE|target|src/PolicyGuard.java|all|Read the policy guard";
        String client = "REQUEST_FILE|base|src/**/*.java|references:RetryClient|Find the retry caller";
        String requests = "<requests>" + client + "\n" + policy + "</requests>";
        String start = "<adaptive-review><route>DEEP</route><summary>Two missing premises.</summary>";
        String decisions = "<decisions>" + auth + retry + "</decisions>";
        String end = "</adaptive-review>";
        for (String reply : List.of(start + requests + decisions + end, start + decisions + requests + end,
                start + decisions.replace("</decisions>", requests + "</decisions>") + end)) {
            var parsed = ReviewXml.xml(reply, "adaptive-review");
            var candidates = parsed.root().getElementsByTagName("local-decision");
            var first = ReviewXml.fields((org.w3c.dom.Element) candidates.item(0), ReviewSchema.LOCAL_DECISION);
            var second = ReviewXml.fields((org.w3c.dom.Element) candidates.item(1), ReviewSchema.LOCAL_DECISION);
            require(ReviewXml.text(first, "requests").equals(policy) && ReviewXml.text(second, "requests").equals(client)
                    && ReviewXml.text(first, "status").equals("INSUFFICIENT_EVIDENCE")
                    && parsed.normalizations().getFirst().contains("uniquely identified unresolved candidates"),
                    "plain requests are restored to their uniquely named owners without changing the verdicts");
        }
        fails(() -> ReviewXml.xml(start + decisions.replace("Check RetryClient", "Check PolicyGuard and RetryClient")
                + requests + end, "adaptive-review"));
        fails(() -> ReviewXml.xml(start + decisions.replace("INSUFFICIENT_EVIDENCE", "CONFIRMED")
                + requests + end, "adaptive-review"));
        fails(() -> ReviewXml.xml(start + decisions + requests.replace(policy, "unstructured advice") + end, "adaptive-review"));
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
