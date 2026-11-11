package reviewagent;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

final class ReviewOutputTests {
    static void run() throws Exception {
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

        String body = String.join("\n\n", ReviewOutput.headings(OutputLanguage.EN));
        body = body.replace("# Findings", "# Findings\n\nUnverified fabricated issue.");
        String groups = "<finding-groups><group><id>R1-1</id><id>R2-1</id></group>"
                + "<group><id>R3-1</id></group></finding-groups>";
        String report = ReviewOutput.finalReport(body + "\n\n" + xml(groups), List.of(other, duplicate, first), OutputLanguage.EN);
        require(report.equals(ReviewOutput.finalReport(body + "\n\n" + groups,
                List.of(other, duplicate, first), OutputLanguage.EN)), "raw final grouping produces the same report");
        require(report.contains("[HIGH] First failure") && report.contains("[LOW] Independent failure"), "severity rendering");
        require(!report.contains("Same failure") && !report.contains("fabricated"), "duplicates and unverified model text excluded");
        require(report.indexOf("First failure") < report.indexOf("Independent failure"), "findings ordered by severity");
        String finalBody = body;
        fails(() -> ReviewOutput.finalReport(finalBody + "\n" + xml(groups.replace("R3-1", "unknown")), List.of(first, duplicate, other), OutputLanguage.EN));
        fails(() -> ReviewOutput.finalReport(finalBody + "\n" + xml("<finding-groups/>"), List.of(first), OutputLanguage.EN));
        fails(() -> ReviewOutput.finalReport(finalBody + "\n" + xml(groups.replace("R3-1", "R1-1")), List.of(first, duplicate, other), OutputLanguage.EN));
        String empty = ReviewOutput.finalReport(body + "\n" + xml("<finding-groups/>"), List.of(), OutputLanguage.EN);
        require(empty.contains("No verified findings.") && !empty.contains("fabricated"), "zero accepted findings stays empty");
        String chinese = String.join("\n\n", ReviewOutput.headings(OutputLanguage.ZH_CN)) + "\n"
                + xml("<finding-groups><group><id>R1-1</id></group></finding-groups>");
        require(ReviewOutput.finalReport(chinese, List.of(first), OutputLanguage.ZH_CN).contains("[高]"), "Chinese severity label");

        long started = System.nanoTime();
        try {
            new ProcessExecutor().run(List.of("/bin/sh", "-c", "sleep 10"), Path.of("."), "x".repeat(1_000_000), Duration.ofMillis(150));
            throw new AssertionError("Expected a timeout while the command ignores stdin");
        } catch (IOException timeout) {
            require(timeout.getMessage().contains("timed out"), "timeout includes blocked stdin delivery");
        }
        require(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 5, "timeout terminates the subprocess tree");
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
