package reviewagent;

import static reviewagent.ReviewXml.*;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A task-local correction may fix citations, but cannot remap an already validated requirement or drop items. */
final class AssessmentValidator implements ReviewTasks.Validator {
    private final InputDocument document;
    private final String normalizedDocument;
    private final EvidenceIndex index;
    private final List<ReviewOutput.AssessmentItem> previous;
    private final Map<String, String> trustedRequirements = new LinkedHashMap<>();
    private final Set<String> expectedIds = new LinkedHashSet<>();
    private boolean observedItems;

    AssessmentValidator(InputDocument document, EvidenceIndex index, List<ReviewOutput.AssessmentItem> previous) {
        this.document = document; this.index = index; this.previous = List.copyOf(previous);
        this.normalizedDocument = EvidenceIndex.normalize(document.content());
        for (var item : previous) {
            expectedIds.add(item.id());
            if (RequirementReference.contains(normalizedDocument, item.requirement())) trustedRequirements.put(item.id(), item.requirement());
        }
    }

    @Override public void validate(String response) throws IOException {
        var parsed = ReviewOutput.assessmentItems(response);
        Set<String> ids = parsed.stream().map(ReviewOutput.AssessmentItem::id).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (!observedItems) { expectedIds.addAll(ids); observedItems = true; }
        for (var raw : parsed) {
            try {
                var item = RequirementReference.resolve(raw, document);
                if (expectedIds.contains(item.id()) && RequirementReference.contains(normalizedDocument, item.requirement())) {
                    trustedRequirements.putIfAbsent(item.id(), item.requirement());
                }
            } catch (IOException untrusted) { /* Invalid clauses still fail full grounding below. */ }
        }
        if (!ids.equals(expectedIds)) throw new ReviewDecisionException("Correction must preserve the assessment item set. Expected: "
                + expectedIds + "; received: " + ids + ". Do not omit or add items during correction.");
        for (var item : parsed) {
            String trusted = trustedRequirements.get(item.id());
            if (trusted == null) continue;
            ReviewOutput.AssessmentItem actual;
            try { actual = RequirementReference.resolve(item, document); }
            catch (IOException invalid) { throw new SourceQuotationException(item.id() + " <requirementLines>: " + invalid.getMessage()); }
            if (!EvidenceIndex.normalize(trusted).equals(EvidenceIndex.normalize(actual.requirement()))) {
                throw new SourceQuotationException(item.id() + ": correction must preserve its already validated requirement. "
                        + "Original clause: " + EvidenceText.oneLine(trusted));
            }
        }
        ReviewGrounding.assessment(response, document, index, previous);
    }

    @Override public String corrected(String response, IOException failure) throws IOException {
        try { return restoreRequirements(response); }
        catch (IOException unreadable) { return response; } // The normal validator/correction budget handles unreadable replies.
    }

    private String restoreRequirements(String response) throws IOException {
        XmlBlock block;
        try { block = xml(response, "assessment"); }
        catch (IOException unreadable) { return response; }
        boolean changed = false;
        var notes = new java.util.ArrayList<String>();
        for (var element : children(block.root(), "item")) {
            Map<String, org.w3c.dom.Element> fields = fields(element, ReviewSchema.ASSESSMENT_ITEM);
            String id = text(fields, "id");
            String trusted = trustedRequirements.get(id);
            if (trusted == null) continue;
            String proposed = "";
            try {
                if (fields.containsKey("requirement") && !fields.containsKey("requirementLines")) proposed = text(fields, "requirement");
                else if (fields.containsKey("requirementLines") && !fields.containsKey("requirement")) {
                    String selector = text(fields, "requirementLines");
                    proposed = EvidenceSources.contract("requirement", document.path().toString(), "document:" + document.sha256(), 1,
                            document.content()).excerpt(selector).text();
                }
            } catch (IOException invalidReference) { /* Restore only the already validated clause below. */ }
            if (EvidenceIndex.normalize(trusted).equals(EvidenceIndex.normalize(proposed))) continue;
            if (fields.containsKey("requirementLines")) element.removeChild(fields.get("requirementLines"));
            var field = fields.get("requirement");
            if (field == null) { field = element.getOwnerDocument().createElement("requirement"); element.appendChild(field); }
            field.setTextContent(trusted);
            changed = true;
            notes.add("Preserved already validated primary requirement for " + id
                    + "; correction cannot remap the criterion. Model reply retained separately.");
        }
        if (!changed) return response;
        String corrected = ReviewXml.replaceRoot(response, "assessment", block.root());
        notes.forEach(ResponseDiagnostics::record);
        return corrected;
    }

    @Override public String correctionInstructions(IOException failure) {
        return "Keep every assessment item ID and its already validated requirement unchanged while correcting other citations. "
                + "Do not insert Markdown table header/separator rows into quotations. For an invalid requirement excerpt, "
                + "a supplied <requirementLines>first:last</requirementLines> selector may replace requirement; these are lines "
                + "of the explicit primary requirements document only. The CLI extracts the original text. Use exactly one form.\n";
    }
}
