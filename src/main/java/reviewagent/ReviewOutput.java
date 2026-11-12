package reviewagent;

import static reviewagent.ReviewXml.*;

import org.w3c.dom.Element;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Structured review protocol and domain values; grounding and rendering have separate owners. */
final class ReviewOutput {
    private static final Set<String> FINDING_FIELDS = ReviewSchema.FINDING;
    private static final Set<String> ASSESSMENT_FIELDS = ReviewSchema.ASSESSMENT_ITEM;

    enum Status {
        CONFIRMED, REJECTED, INSUFFICIENT_EVIDENCE
    }

    record Verification(ReviewFinding candidate, Status status, String reason, ReviewFinding confirmed, boolean validationFailed) {
        Verification(ReviewFinding candidate, Status status, String reason, ReviewFinding confirmed) {
            this(candidate, status, reason, confirmed, false);
        }
    }

    enum Relation { INTRODUCED, EXPOSED_BY_CHANGE, UNMET_REQUIREMENT, PRE_EXISTING, UNRESOLVED }

    record Decision(Verification verdict, Relation relation, String baseline, String contract,
                    List<EvidenceIndex.Citation> citations, ReviewSupport support) {
        Decision(Verification verdict, Relation relation, String baseline, String contract, List<EvidenceIndex.Citation> citations) {
            this(verdict, relation, baseline, contract, citations, ReviewSupport.unresolved());
        }
    }

    enum AssessmentStatus { SATISFIED, NOT_SATISFIED, INSUFFICIENT_EVIDENCE }
    enum PolicyBasis { EXPLICIT, UNSPECIFIED }

    record AssessmentItem(String id, String requirement, String requirementText, String behavior, AssessmentStatus status,
                          String reasoning, String boundaries, String gap, List<EvidenceIndex.Citation> citations,
                          PolicyBasis policyBasis, String policyReason, String requirementLines) {
        AssessmentItem(String id, String requirement, String requirementText, String behavior, AssessmentStatus status,
                       String reasoning, String boundaries, String gap, List<EvidenceIndex.Citation> citations,
                       PolicyBasis policyBasis, String policyReason) {
            this(id, requirement, requirementText, behavior, status, reasoning, boundaries, gap, citations, policyBasis, policyReason, "");
        }
    }

    record Discovery(List<ReviewFinding> findings, String notes) {
    }

    static Discovery discovery(String response, String reviewer, List<String> changedFiles) throws IOException {
        XmlBlock block = xml(response, "findings");
        var findings = new ArrayList<ReviewFinding>();
        for (Element element : children(block.root(), "finding")) {
            findings.add(finding(element, reviewer + "-" + (findings.size() + 1), changedFiles, false));
        }
        return new Discovery(List.copyOf(findings), block.remainder());
    }

    static Verification verification(String response, ReviewFinding candidate, List<String> changedFiles) throws IOException {
        Element root = xml(response, "verification").root();
        Map<String, Element> fields = fields(root, ReviewSchema.VERIFICATION);
        Status status;
        try {
            status = Status.valueOf(text(fields, "status"));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Unknown verification status", invalid);
        }
        String reason = text(fields, "reason");
        ReviewFinding confirmed = null;
        if (status == Status.CONFIRMED) {
            if (!fields.containsKey("finding")) {
                throw new IOException("Confirmed verification is missing its finding");
            }
            confirmed = finding(fields.get("finding"), candidate.id(), changedFiles, true);
        } else if (fields.containsKey("finding")) {
            throw new IOException("Unconfirmed verification contains a finding");
        }
        return new Verification(candidate, status, reason, confirmed);
    }

    static Decision decision(String response, ReviewFinding candidate, List<String> changedFiles) throws IOException {
        Verification verdict = verification(response, candidate, changedFiles);
        Map<String, Element> fields = fields(xml(response, "verification").root(),
                ReviewSchema.VERIFICATION);
        Relation relation = Relation.UNRESOLVED;
        if (fields.containsKey("relation")) {
            try { relation = Relation.valueOf(text(fields, "relation")); }
            catch (IllegalArgumentException invalid) { throw new IOException("Unknown finding relationship", invalid); }
        }
        return new Decision(verdict, relation, fields.containsKey("baseline") ? text(fields, "baseline") : "",
                fields.containsKey("contract") ? text(fields, "contract") : "", citations(fields.get("citations")), support(fields));
    }

    private static ReviewSupport support(Map<String, Element> fields) throws IOException {
        ReviewSupport.Kind kind = ReviewSupport.Kind.UNRESOLVED;
        if (fields.containsKey("support")) {
            try { kind = ReviewSupport.Kind.valueOf(text(fields, "support")); }
            catch (IllegalArgumentException invalid) { throw new IOException("Unknown finding support kind", invalid); }
        }
        var premises = new ArrayList<ReviewSupport.Premise>();
        if (fields.containsKey("premises")) for (Element element : children(fields.get("premises"), "premise")) {
            var premise = fields(element, Set.of("statement", "basis", "citations"));
            try { premises.add(new ReviewSupport.Premise(text(premise, "statement"), ReviewSupport.Basis.valueOf(text(premise, "basis")),
                    citations(premise.get("citations")))); }
            catch (IllegalArgumentException invalid) { throw new IOException("Unknown premise basis", invalid); }
        }
        return new ReviewSupport(kind, List.copyOf(premises));
    }

    static List<List<String>> candidateGroups(String response, List<ReviewFinding> candidates) throws IOException {
        var expected = candidates.stream().map(ReviewFinding::id).collect(java.util.stream.Collectors.toSet());
        var seen = new HashSet<String>();
        var groups = new ArrayList<List<String>>();
        for (Element group : children(xml(response, "candidate-groups").root(), "group")) {
            var ids = new ArrayList<String>();
            for (Element id : children(group, "id")) {
                String value = id.getTextContent().strip();
                if (!expected.contains(value) || !seen.add(value)) throw new IOException("Unknown or repeated candidate in reconciliation");
                ids.add(value);
            }
            if (ids.isEmpty()) throw new IOException("Empty candidate reconciliation group");
            groups.add(List.copyOf(ids));
        }
        if (!seen.equals(expected)) throw new IOException("Reconciliation omitted candidates, including negative verdicts");
        return List.copyOf(groups);
    }

    static List<AssessmentItem> assessmentItems(String response) throws IOException {
        var items = new ArrayList<AssessmentItem>();
        var ids = new HashSet<String>();
        for (Element element : children(xml(response, "assessment").root(), "item")) {
            Map<String, Element> fields = fields(element, ASSESSMENT_FIELDS);
            String id = text(fields, "id");
            if (!id.matches("A[1-9][0-9]*") || !ids.add(id)) throw new IOException("Invalid or repeated requirement assessment ID");
            AssessmentStatus status;
            try { status = AssessmentStatus.valueOf(text(fields, "status")); }
            catch (IllegalArgumentException invalid) { throw new IOException("Unknown atomic requirement status", invalid); }
            PolicyBasis policy;
            try { policy = PolicyBasis.valueOf(text(fields, "policyBasis")); }
            catch (IllegalArgumentException invalid) { throw new IOException("Unknown assessment policy basis", invalid); }
            if (fields.containsKey("requirement") == fields.containsKey("requirementLines")) {
                throw new IOException("Use exactly one of requirement or requirementLines");
            }
            items.add(new AssessmentItem(id, fields.containsKey("requirement") ? text(fields, "requirement") : "", text(fields, "requirementText"), text(fields, "behavior"),
                    status, text(fields, "reasoning"), text(fields, "boundaries"), text(fields, "gap"), citations(fields.get("citations")),
                    policy, text(fields, "policyReason"), fields.containsKey("requirementLines") ? text(fields, "requirementLines") : ""));
        }
        if (items.isEmpty()) throw new IOException("Supplied requirements require a nonempty assessment");
        return List.copyOf(items);
    }

    static String requestText(String response, String rootName) throws IOException {
        // Quoted source inside structured decisions is evidence, not a request to the collector.
        return xml(response, rootName).remainder();
    }

    private static List<EvidenceIndex.Citation> citations(Element element) throws IOException {
        if (element == null) return List.of();
        var citations = new ArrayList<EvidenceIndex.Citation>();
        var quoteErrors = new ArrayList<String>();
        for (Element citation : children(element, "citation")) {
            var fields = fields(citation, Set.of("source", "quote", "lines"));
            String source = text(fields, "source");
            try {
                if (fields.containsKey("quote") == fields.containsKey("lines")) {
                    throw new IOException("Use exactly one of quote or lines");
                }
                citations.add(fields.containsKey("lines") ? EvidenceIndex.Citation.lines(source, text(fields, "lines"))
                        : new EvidenceIndex.Citation(source, text(fields, "quote")));
            } catch (IOException invalidQuote) {
                quoteErrors.add(EvidenceText.oneLine(source) + ": use exactly one nonempty quote or block-relative lines selector. "
                        + "A quote must contain literal source text; escape XML markup or use CDATA. "
                        + "For a CLI-provided excerpt selector, omit quote and copy <lines>first:last</lines>.");
            }
        }
        if (!quoteErrors.isEmpty()) throw new SourceQuotationException(String.join("\n", quoteErrors));
        return List.copyOf(citations);
    }

    static String findingXml(ReviewFinding finding) {
        return "<finding id=\"" + finding.id() + "\">"
                + element("title", finding.title()) + element("severity", finding.severity().name())
                + element("path", finding.path()) + element("line", Integer.toString(finding.line()))
                + element("endLine", Integer.toString(finding.endLine())) + element("trigger", finding.trigger())
                + element("impact", finding.impact()) + element("evidence", finding.evidence())
                + element("diffRelation", finding.diffRelation()) + "</finding>";
    }

    private static String element(String name, String value) {
        return "<" + name + ">" + value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                + "</" + name + ">";
    }

    private static ReviewFinding finding(Element element, String id, List<String> changedFiles, boolean checkCandidateId)
            throws IOException {
        List<Element> contents = children(element, null);
        while (contents.size() == 1 && contents.getFirst().getTagName().equals("finding")) {
            if (element.getAttributes().getLength() > (element.hasAttribute("id") ? 1 : 0)) {
                throw new IOException("Unexpected attributes in a finding wrapper");
            }
            String wrapperId = element.getAttribute("id");
            if (checkCandidateId && !wrapperId.isEmpty() && !wrapperId.equals(id)) {
                throw new IOException("Finding wrapper ID does not match the candidate being processed");
            }
            element = contents.getFirst();
            contents = children(element, null);
        }
        Map<String, Element> fields = fields(element, FINDING_FIELDS);
        String attributeId = element.getAttribute("id");
        if (checkCandidateId && ((!attributeId.isEmpty() && !attributeId.equals(id))
                || (fields.containsKey("id") && !text(fields, "id").equals(id)))) {
            throw new IOException("Finding ID does not match the candidate being processed");
        }
        String path = text(fields, "path");
        if (!changedFiles.contains(path)) {
            throw new IOException("Finding location is outside the changed files: " + EvidenceText.oneLine(path));
        }
        try {
            int first = Integer.parseInt(text(fields, "line"));
            int last = Integer.parseInt(text(fields, "endLine"));
            if (first < 1 || last < first) {
                throw new IllegalArgumentException("Invalid finding line range");
            }
            return new ReviewFinding(id, text(fields, "title"), ReviewFinding.Severity.parse(text(fields, "severity")),
                    path, first, last, text(fields, "trigger"), text(fields, "impact"), text(fields, "evidence"),
                    text(fields, "diffRelation"));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Finding has an invalid severity or line range", invalid);
        }
    }


}
