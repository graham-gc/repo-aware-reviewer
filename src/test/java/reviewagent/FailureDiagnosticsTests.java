package reviewagent;

import java.io.IOException;
import org.xml.sax.SAXParseException;

final class FailureDiagnosticsTests {
    static void run() throws Exception {
        String secret = "credential-value-that-must-not-be-exported";
        for (var failure : new IOException[]{new SourceQuotationException(secret), new ReviewDecisionException(secret),
                new AmbiguousTextFieldException(secret), new IOException(secret, new SAXParseException(secret, null, null, 7, 11)),
                new IOException("Missing assessment XML response block"), new IOException("Repeated verification response block"),
                new IOException(secret)}) {
            String diagnostic = FailureDiagnostics.properties(new InvalidReviewResponseException(secret, failure), "INVALID_OUTPUT");
            require(!diagnostic.contains(secret) && diagnostic.contains("failureSummary="), "raw messages must never enter shareable diagnostics");
        }
        require(FailureDiagnostics.properties(new InvalidReviewResponseException(secret, new SourceQuotationException(secret)), "INVALID_OUTPUT")
                .contains("failureCategory=SOURCE_QUOTATION"), "source mismatch is distinct from XML parsing");
        String syntax = FailureDiagnostics.properties(new IOException(secret, new SAXParseException(secret, null, null, 7, 11)), "INVALID_OUTPUT");
        require(syntax.contains("failureCategory=XML_SYNTAX") && syntax.contains("failureLine=7") && syntax.contains("failureColumn=11"),
                "safe structural error positions survive wrapping without serializing the original message");
        for (var kind : AgentCallException.Kind.values()) {
            String diagnostic = FailureDiagnostics.properties(new AgentCallException(kind, secret), kind.name());
            require(diagnostic.contains("failureCategory=MODEL_INVOCATION") && !diagnostic.contains(secret), "invocation categories preserve no backend message");
        }
        require(FailureDiagnostics.properties(null, "").equals("failureCategory=\nfailureSummary=\nfailureLine=\nfailureColumn=\n"),
                "success and pending states contain no stale failure details");
        var first = new IOException(secret);
        var second = new IOException(secret, first);
        first.initCause(second);
        require(!FailureDiagnostics.properties(first, "IO_FAILURE").contains(secret), "cyclic exception causes are bounded");
        var root = java.nio.file.Files.createTempDirectory("safe-validation-events-");
        try {
            var events = root.resolve("task-validation.log");
            ValidationEvents.rejected(events, new SourceQuotationException(secret));
            ValidationEvents.rejected(events, new IOException(secret, new SAXParseException(secret, null, null, 3, 9)));
            String history = java.nio.file.Files.readString(events);
            require(history.split("event=VALIDATION_REJECTED", -1).length == 3
                    && history.contains("failureCategory=SOURCE_QUOTATION") && history.contains("failureCategory=XML_SYNTAX")
                    && history.contains("failureLine=3") && !history.contains(secret),
                    "append-only correction history retains safe categories and parser positions without raw exception content");
        } finally {
            java.nio.file.Files.deleteIfExists(root.resolve("task-validation.log"));
            java.nio.file.Files.delete(root);
        }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
