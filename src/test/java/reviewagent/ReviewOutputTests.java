package reviewagent;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

final class ReviewOutputTests {
    static void run() throws Exception {
        textFields();
        quotationText();
        ReviewFinding first = candidate("R1-1", "First failure", ReviewFinding.Severity.HIGH);
        ReviewFinding duplicate = candidate("R2-1", "Same failure", ReviewFinding.Severity.MEDIUM);
        ReviewFinding other = candidate("R3-1", "Independent failure", ReviewFinding.Severity.LOW);
        var parsed = ReviewOutput.discovery(xml("<findings>" + ReviewOutput.findingXml(first) + "</findings>"),
                "R1", List.of("a.cpp"));
        require(parsed.findings().size() == 1 && parsed.findings().getFirst().equals(first), "candidate protocol round trip");
        require(ReviewOutput.discovery(xml("<findings/>"), "R1", List.of()).findings().isEmpty(), "explicit empty findings");
        fails(() -> ReviewOutput.discovery("No problems.", "R1", List.of("a.cpp")));
        fails(() -> ReviewOutput.discovery(xml("<findings>" + ReviewOutput.findingXml(first) + "</findings>"), "R1", List.of("other.cpp")));
        fails(() -> ReviewOutput.discovery(xml("<findings>" + ReviewOutput.findingXml(first).replace("<line>1</line>", "<line>0</line>")
                + "</findings>"), "R1", List.of("a.cpp")));
        fails(() -> ReviewOutput.discovery(xml("<findings>unstructured issue</findings>"), "R1", List.of("a.cpp")));
        fails(() -> ReviewOutput.discovery(xml("<findings><finding></findings>"), "R1", List.of("a.cpp")));

        String verdict = "<verification><status>CONFIRMED</status><reason>Concrete source evidence.</reason>"
                + ReviewOutput.findingXml(first) + "</verification>";
        require(ReviewOutput.verification(xml(verdict), first, List.of("a.cpp")).confirmed().equals(first), "confirmed verdict");
        String firstXml = ReviewOutput.findingXml(first);
        String nested = verdict.replace(firstXml, "<finding>" + firstXml + "</finding>");
        require(ReviewOutput.verification(nested, first, List.of("a.cpp")).confirmed().equals(first),
                "a single redundant finding wrapper preserves all verified fields");
        fails(() -> ReviewOutput.verification(verdict.replace(firstXml,
                "<finding id=\"other\">" + firstXml + "</finding>"), first, List.of("a.cpp")));
        fails(() -> ReviewOutput.verification(verdict.replace(firstXml,
                "<finding><impact>Different impact</impact>" + firstXml + "</finding>"), first, List.of("a.cpp")));
        fails(() -> ReviewOutput.verification(verdict.replace(firstXml, firstXml + firstXml), first, List.of("a.cpp")));
        for (String wrapped : List.of(verdict, "Decision follows.\n" + verdict + "\nEnd.",
                "> Example <verification/>\n" + verdict,
                "```XML\n" + verdict + "\n```", "```\n" + verdict + "\n```", "~~~xml\n" + verdict + "\n~~~")) {
            require(ReviewOutput.verification(wrapped, first, List.of("a.cpp")).confirmed().equals(first),
                    "equivalent XML responses are accepted independently of Markdown wrapping");
        }
        String cdata = "<verification><status>REJECTED</status><reason><![CDATA[The literal </verification> in source is data.]]></reason></verification>";
        require(ReviewOutput.verification(cdata, first, List.of("a.cpp")).status() == ReviewOutput.Status.REJECTED,
                "XML-like source text inside CDATA does not terminate the response");
        fails(() -> ReviewOutput.verification(verdict + "\n" + verdict, first, List.of("a.cpp")));
        String localized = verdict.replace("<finding id=\"R1-1\">", "<finding><id>R1-1</id>")
                .replace("<severity>HIGH</severity>", "<severity>高</severity>");
        require(ReviewOutput.verification(localized, first, List.of("a.cpp")).confirmed().equals(first),
                "equivalent child ID and localized severity preserve the verified finding");
        fails(() -> ReviewOutput.verification(localized.replace("<id>R1-1</id>", "<id>other</id>"), first, List.of("a.cpp")));
        for (String status : List.of("REJECTED", "INSUFFICIENT_EVIDENCE")) {
            var result = ReviewOutput.verification(xml("<verification><status>" + status
                    + "</status><reason>The guard or missing contract changes the conclusion.</reason></verification>"), first, List.of("a.cpp"));
            require(result.confirmed() == null, "unconfirmed findings cannot enter the accepted list");
        }
        fails(() -> ReviewOutput.verification(xml(verdict.replace("CONFIRMED", "REJECTED")), first, List.of("a.cpp")));
        fails(() -> ReviewOutput.verification(xml("<verification><status>CONFIRMED</status><reason>Yes.</reason></verification>"), first, List.of("a.cpp")));

        String body = String.join("\n\n", ReviewReport.headings(OutputLanguage.EN));
        body = body.replace("# Findings", "# Findings\n\nUnverified fabricated issue.");
        String groups = "<finding-groups><group><id>R1-1</id><id>R2-1</id></group>"
                + "<group><id>R3-1</id></group></finding-groups>";
        String report = ReviewReport.finalReport(body + "\n\n" + xml(groups), List.of(other, duplicate, first), OutputLanguage.EN);
        require(report.equals(ReviewReport.finalReport(body + "\n\n" + groups,
                List.of(other, duplicate, first), OutputLanguage.EN)), "raw final grouping produces the same report");
        require(report.contains("[HIGH] First failure") && report.contains("[LOW] Independent failure"), "severity rendering");
        require(!report.contains("Same failure") && !report.contains("fabricated"), "duplicates and unverified model text excluded");
        require(report.indexOf("First failure") < report.indexOf("Independent failure"), "findings ordered by severity");
        String finalBody = body;
        fails(() -> ReviewReport.finalReport(finalBody + "\n" + xml(groups.replace("R3-1", "unknown")), List.of(first, duplicate, other), OutputLanguage.EN));
        fails(() -> ReviewReport.finalReport(finalBody + "\n" + xml("<finding-groups/>"), List.of(first), OutputLanguage.EN));
        fails(() -> ReviewReport.finalReport(finalBody + "\n" + xml(groups.replace("R3-1", "R1-1")), List.of(first, duplicate, other), OutputLanguage.EN));
        String empty = ReviewReport.finalReport(body + "\n" + xml("<finding-groups/>"), List.of(), OutputLanguage.EN);
        require(empty.contains("No verified findings.") && !empty.contains("fabricated"), "zero accepted findings stays empty");
        String chinese = String.join("\n\n", ReviewReport.headings(OutputLanguage.ZH_CN)) + "\n"
                + xml("<finding-groups><group><id>R1-1</id></group></finding-groups>");
        require(ReviewReport.finalReport(chinese, List.of(first), OutputLanguage.ZH_CN).contains("[高]"), "Chinese severity label");

        long started = System.nanoTime();
        try {
            new ProcessExecutor().run(List.of("/bin/sh", "-c", "sleep 10"), Path.of("."), "x".repeat(1_000_000), Duration.ofMillis(150));
            throw new AssertionError("Expected a timeout while the command ignores stdin");
        } catch (IOException timeout) {
            require(timeout.getMessage().contains("timed out"), "timeout includes blocked stdin delivery");
        }
        require(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 5, "timeout terminates the subprocess tree");
    }

    private static void textFields() throws Exception {
        String description = "Creates `<testsuite>` and `<testcase>` with <failure>, <error> or <skipped>; "
                + "List<Node> values use left < right && ready. Literal </verification> and <status>CONFIRMED</status> are text.";
        ReviewFinding candidate = candidate("R1-1", "Text fields", ReviewFinding.Severity.HIGH);
        String finding = "<finding id=\"R1-1\"><title>" + description + "</title><severity>HIGH</severity>"
                + "<path>a.cpp</path><line>1</line><endLine>1</endLine><trigger>" + description + "</trigger>"
                + "<impact>" + description + "</impact><evidence>" + description + "</evidence>"
                + "<diffRelation>" + description + "</diffRelation></finding>";
        String verified = "<verification><status>CONFIRMED</status><reason>" + description + "</reason>"
                + "<relation>INTRODUCED</relation><baseline>" + description + "</baseline>"
                + "<contract>" + description + "</contract>" + finding + "</verification>";
        var verdict = ReviewOutput.verification(verified, candidate, List.of("a.cpp"));
        require(verdict.reason().equals(description) && verdict.confirmed().title().equals(description)
                && verdict.confirmed().trigger().equals(description) && verdict.confirmed().impact().equals(description)
                && verdict.confirmed().evidence().equals(description) && verdict.confirmed().diffRelation().equals(description),
                "finding descriptions preserve raw code and XML-like prose without treating it as protocol structure");
        String rejected = "<verification><status>REJECTED</status><reason>" + description + "</reason></verification>";
        require(ReviewOutput.verification(rejected, candidate, List.of("a.cpp")).status() == ReviewOutput.Status.REJECTED,
                "a status tag mentioned inside an explanation cannot change the actual verdict");

        String clause = "JUnit output must include a <skipped> element for skipped cases.";
        var requirement = new InputDocument(Path.of("requirements.md"), "hash", clause);
        var scope = new ReviewScope(ReviewScope.Mode.COMMIT, "base", "target", "base", "target", "base..target");
        String code = "ET.SubElement(case, \"skipped\")";
        var evidence = new EvidenceBundle(scope, List.of("report.py"),
                EvidenceText.block("report.py at target", "text", code), "", 20_000, null);
        var index = new EvidenceIndex(evidence, "");
        String assessment = "<assessment><item><policyBasis>EXPLICIT</policyBasis><policyReason>The supplied clause states this criterion.</policyReason><id>A1</id><requirement>" + clause + "</requirement>"
                + "<requirementText>" + description + "</requirementText><behavior>" + description + "</behavior>"
                + "<status>SATISFIED</status><reasoning>" + description + "</reasoning><boundaries>" + description
                + "</boundaries><gap>" + description + "</gap><citations><citation><source>report.py at target</source>"
                + "<quote>" + code + "</quote></citation></citations></item></assessment>";
        var item = ReviewGrounding.assessment(assessment, requirement, index, List.of()).getFirst();
        require(item.requirement().equals(clause) && item.requirementText().equals(description) && item.behavior().equals(description)
                && item.reasoning().equals(description) && item.boundaries().equals(description) && item.gap().equals(description),
                "all requirement prose fields preserve JUnit tags and source operators as literal text");
        String requests = "\nREQUEST_FILE|target|report.py|all|Need more context\n";
        require(ReviewOutput.requestText(assessment + requests, "assessment").equals(requests),
                "normalization preserves request offsets outside the assessment document");
        fails(() -> ReviewGrounding.assessment(assessment.replace("<status>SATISFIED</status>", "<status>UNRECOGNIZED</status>"),
                requirement, index, List.of()));
        fails(() -> ReviewGrounding.assessment(assessment.replace("<citations>", "<unknown>Unexpected</unknown><citations>"),
                requirement, index, List.of()));
        fails(() -> ReviewGrounding.assessment(assessment.replace("<status>SATISFIED</status>",
                "<status>SATISFIED</status><status>INSUFFICIENT_EVIDENCE</status>"), requirement, index, List.of()));
        fails(() -> ReviewGrounding.assessment(assessment.replace("<citations><citation><source>report.py at target</source><quote>"
                + code + "</quote></citation></citations>", ""), requirement, index, List.of()));
        fails(() -> ReviewGrounding.assessment(assessment.replace(clause, "Invented JUnit policy requiring every case to pass."),
                requirement, index, List.of()));
    }

    private static void quotationText() throws Exception {
        ReviewFinding candidate = candidate("R1-1", "Quoted source", ReviewFinding.Severity.HIGH);
        var scope = new ReviewScope(ReviewScope.Mode.COMMIT, "base", "target", "base", "target", "base..target");
        for (String code : List.of(
                "List<BrickFlowNode> nodes = load(); Map<Long, List<Assertion>> values = new HashMap<>();",
                "std::vector<Node> nodes; if (left < right && ready) parse<Map<int, Node>>(nodes);",
                "<plugin><groupId>example.group</groupId><artifactId>example-plugin</artifactId></plugin>",
                "String marker = \"</verification><verification>\"; // literal protocol tags",
                "boolean enabled = value > 0 && count < 10; // raw ]]> is also source text")) {
            String evidenceText = EvidenceText.block("a.cpp", "diff", "new file mode 100644\n+" + code)
                    + EvidenceText.block("a.cpp at target", "text", code);
            var evidence = new EvidenceBundle(scope, List.of("a.cpp"), evidenceText, "", 20_000, null);
            var index = new EvidenceIndex(evidence, "");
            String escaped = code.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            for (String body : List.of(code, escaped, "<![CDATA[" + code.replace("]]>", "]]]]><![CDATA[>") + "]]>")) {
                String response = "<verification><status>CONFIRMED</status><reason>Quoted source supports the decision.</reason>"
                        + "<relation>INTRODUCED</relation><baseline>New file in the diff.</baseline><contract>Intrinsic correctness.</contract>"
                        + "<citations><citation><source>a.cpp</source><quote>new file mode 100644</quote></citation>"
                        + "<citation><source>a.cpp at target</source><quote>" + body + "</quote></citation></citations>"
                        + "<support>REACHABLE_FAILURE</support><premises><premise><statement>Literal source syntax is retained.</statement>"
                        + "<basis>IMPLEMENTATION</basis><citations><citation><source>a.cpp at target</source><quote>" + body
                        + "</quote></citation></citations></premise></premises>"
                        + ReviewOutput.findingXml(candidate) + "</verification>";
                var parsed = ReviewGrounding.verification(response, candidate, evidence, index);
                require(parsed.citations().get(1).quote().equals(code) && parsed.verdict().confirmed().equals(candidate),
                        "raw, escaped, and CDATA source quotations preserve exact text and finding fields");
                require(ReviewOutput.requestText(response, "verification").isBlank(), "source protocol tags do not leak into the request remainder");
                fails(() -> ReviewGrounding.verification(response.replace("<finding id=\"R1-1\">", "<finding id=\"other\">"),
                        candidate, evidence, index));
            }
        }
        String literal = "Source includes </quote> and &lt; literally, rather than as protocol markup.";
        String payload = "<verification><status>REJECTED</status><reason>Source data.</reason><citations><citation>"
                + "<source>a.cpp at target</source><quote><![CDATA[" + literal + "]]></quote></citation></citations></verification>";
        var evidence = new EvidenceBundle(scope, List.of("a.cpp"), EvidenceText.block("a.cpp at target", "text", literal), "", 20_000, null);
        var decision = ReviewGrounding.verification(payload, candidate, evidence, new EvidenceIndex(evidence, ""));
        require(decision.citations().getFirst().quote().equals(literal), "CDATA preserves literal quote closing tags and entity spellings");
        String malformed = "<verification>\n<status>REJECTED</status>\n<reason>Wrong closing tag</wrong>\n</verification>";
        try {
            ReviewOutput.verification(malformed, candidate, List.of("a.cpp"));
            throw new AssertionError("Expected malformed protocol structure");
        } catch (IOException error) {
            require(error.getMessage().contains("line 3") && error.getMessage().contains("column"),
                    "structural repair feedback includes the parser location and cause");
        }
        fails(() -> ReviewOutput.verification(payload.replace("</quote>", ""), candidate, List.of("a.cpp")));
        fails(() -> ReviewOutput.verification("<verification><status>CONFIRMED</status><reason>Nested data cannot replace a finding.</reason>"
                + "<citations><citation><source>a.cpp</source><quote>" + ReviewOutput.findingXml(candidate)
                + "</quote></citation></citations></verification>", candidate, List.of("a.cpp")));
    }

    private static ReviewFinding candidate(String id, String title, ReviewFinding.Severity severity) {
        return new ReviewFinding(id, title, severity, "a.cpp", 1, 1, "Reachable input", "Observable failure",
                "a.cpp:1 shows the failure path", "The diff removed the guard");
    }

    private static String xml(String content) {
        return "```xml\n" + content + "\n```\n";
    }

    private static void fails(Check check) throws Exception {
        try {
            check.run();
            throw new AssertionError("Expected invalid review output to be rejected");
        } catch (IOException expected) {
            // A malformed or incomplete response must fail the review.
        }
    }

    @FunctionalInterface
    private interface Check {
        void run() throws Exception;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
