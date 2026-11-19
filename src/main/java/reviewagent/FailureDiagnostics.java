package reviewagent;

import org.xml.sax.SAXParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Shareable error categories only: never serialize exception messages, prompts, quotes or credentials. */
final class FailureDiagnostics {
    private enum Category {
        SOURCE_QUOTATION("A quotation or source reference did not match the supplied evidence."),
        DECISION_PROOF("The decision did not meet the required identity, location or proof constraints."),
        AMBIGUOUS_SOURCE_TEXT("Source text and protocol delimiters could not be distinguished safely."),
        XML_SYNTAX("The structured XML response could not be parsed."),
        MISSING_RESPONSE_BLOCK("The expected XML response block was absent or incomplete."),
        REPEATED_RESPONSE_BLOCK("More than one response block was supplied."),
        INVALID_STRUCTURE("The response did not match the structured review protocol."),
        MODEL_INVOCATION("The model command did not complete with usable output; inspect failureKind."),
        LOCAL_IO("The task failed during a local process or artifact operation.");
        final String summary;
        Category(String summary) { this.summary = summary; }
    }

    static String properties(Throwable failure, String kind) {
        if (failure == null) return "failureCategory=\nfailureSummary=\nfailureLine=\nfailureColumn=\n";
        var causes = new ArrayList<Throwable>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable item = failure; item != null && seen.add(item) && causes.size() < 32; item = item.getCause()) causes.add(item);
        Category category = Category.LOCAL_IO;
        SAXParseException location = null;
        if (causes.stream().anyMatch(SourceQuotationException.class::isInstance)) category = Category.SOURCE_QUOTATION;
        else if (causes.stream().anyMatch(ReviewDecisionException.class::isInstance)) category = Category.DECISION_PROOF;
        else if (causes.stream().anyMatch(AmbiguousTextFieldException.class::isInstance)) category = Category.AMBIGUOUS_SOURCE_TEXT;
        else if (causes.stream().anyMatch(SAXParseException.class::isInstance)) {
            category = Category.XML_SYNTAX;
            location = (SAXParseException) causes.stream().filter(SAXParseException.class::isInstance).findFirst().orElseThrow();
        } else if (causes.stream().anyMatch(AgentCallException.class::isInstance)) category = Category.MODEL_INVOCATION;
        else if (kind.equals("INVALID_OUTPUT")) {
            category = Category.INVALID_STRUCTURE;
            for (Throwable item : causes) {
                String message = item.getMessage();
                if (message != null && message.matches("Missing [A-Za-z-]+ XML response block")) category = Category.MISSING_RESPONSE_BLOCK;
                if (message != null && message.matches("Repeated [A-Za-z-]+ response block")) category = Category.REPEATED_RESPONSE_BLOCK;
            }
        }
        return "failureCategory=" + category + "\nfailureSummary=" + category.summary
                + "\nfailureLine=" + (location == null || location.getLineNumber() < 1 ? "" : location.getLineNumber())
                + "\nfailureColumn=" + (location == null || location.getColumnNumber() < 1 ? "" : location.getColumnNumber()) + "\n";
    }
}
