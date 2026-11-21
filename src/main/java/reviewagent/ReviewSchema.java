package reviewagent;

import org.w3c.dom.Element;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared structural vocabulary. Value, identity and evidence checks belong to the domain validators. */
final class ReviewSchema {
    static final Set<String> TEXT = Set.of("id", "title", "severity", "path", "line", "endLine", "trigger", "impact", "evidence",
            "diffRelation", "status", "reason", "relation", "baseline", "contract", "requirement", "requirementText", "behavior",
            "reasoning", "boundaries", "gap", "source", "quote", "support", "statement", "basis", "policyBasis", "policyReason", "lines", "requirementLines",
            "nextCheck", "sharedCandidates", "summary", "route", "requests", "escalate");
    static final Set<String> FINDING = Set.of("id", "title", "severity", "path", "line", "endLine", "trigger", "impact", "evidence", "diffRelation");
    static final Set<String> VERIFICATION = Set.of("status", "reason", "finding", "relation", "baseline", "contract", "citations", "support", "premises",
            "nextCheck", "sharedCandidates");
    static final Set<String> ASSESSMENT_ITEM = Set.of("id", "requirement", "requirementText", "behavior", "status", "reasoning", "boundaries", "gap",
            "citations", "policyBasis", "policyReason", "requirementLines");
    private static final List<String> ADAPTIVE_FIELDS = List.of("route", "summary", "decisions");
    static final Set<String> ADAPTIVE_REVIEW = Set.copyOf(ADAPTIVE_FIELDS);
    static final Set<String> DOCUMENTATION_REVIEW = Set.of("summary", "escalate", "doc-findings");
    static final Set<String> DOCUMENTATION_FINDING = Set.of("title", "trigger", "impact", "citations");
    static final Set<String> LOCAL_DECISION = Set.of(
            "status", "reason", "nextCheck", "relation", "baseline", "contract", "support", "premises", "citations", "finding", "requests");
    static final String CANDIDATE_REQUESTS = """
            Put a candidate's <requests> and <nextCheck> directly inside its own
            <local-decision> whose <status> is INSUFFICIENT_EVIDENCE. Omit <requests>
            when no tracked evidence could resolve the gap. Use one raw request per line.
            """;
    static final String ADAPTIVE_XML = "Return only one complete <adaptive-review> XML document. Its only direct children are "
            + ADAPTIVE_FIELDS.stream().map(field -> "<" + field + ">").collect(java.util.stream.Collectors.joining(", ")) + ".\n"
            + CANDIDATE_REQUESTS;
    static final String VERIFICATION_REQUESTS = """
            Return one complete <verification> XML document. When the verdict is INSUFFICIENT_EVIDENCE
            and a tracked source can resolve it, append raw evidence request lines after </verification>.
            Keep <nextCheck> inside <verification>. Use no <requests> element or request lines for a resolved verdict.
            """;
    private static final Set<String> LISTS = Set.of("findings", "premises", "citations", "assessment", "candidate-groups",
            "finding-groups", "group", "decisions", "doc-findings", "verifications");
    private static final Map<String, Set<String>> CHILDREN = Map.ofEntries(
            Map.entry("findings", Set.of("finding")), Map.entry("finding", findingChildren()),
            Map.entry("adaptive-review", ADAPTIVE_REVIEW), Map.entry("decisions", Set.of("local-decision")),
            Map.entry("documentation-review", DOCUMENTATION_REVIEW), Map.entry("doc-findings", Set.of("doc-finding")),
            Map.entry("doc-finding", DOCUMENTATION_FINDING),
            Map.entry("local-decision", LOCAL_DECISION),
            Map.entry("verification", VERIFICATION), Map.entry("verifications", Set.of("verification")), Map.entry("premises", Set.of("premise")),
            Map.entry("premise", Set.of("statement", "basis", "citations")), Map.entry("citations", Set.of("citation")),
            Map.entry("citation", Set.of("source", "quote", "lines")), Map.entry("assessment", Set.of("item")),
            Map.entry("item", ASSESSMENT_ITEM), Map.entry("candidate-groups", Set.of("group")),
            Map.entry("finding-groups", Set.of("group")), Map.entry("group", Set.of("id")));

    private static Set<String> findingChildren() {
        var fields = new HashSet<>(FINDING); fields.add("finding"); return Set.copyOf(fields);
    }
    static boolean container(String name) { return CHILDREN.containsKey(name); }
    static boolean allows(String parent, String child) { return CHILDREN.getOrDefault(parent, Set.of()).contains(child); }

    static String repairGrammar() {
        var grammar = new StringBuilder("<grammar xmlns=\"http://relaxng.org/ns/structure/1.0\"><start><choice>");
        CHILDREN.keySet().forEach(name -> appendReference(grammar, name));
        grammar.append("</choice></start>");
        TEXT.forEach(name -> appendRule(grammar, name, "<text/>"));
        CHILDREN.forEach((name, children) -> {
            boolean list = LISTS.contains(name);
            var content = new StringBuilder(list ? "<zeroOrMore><choice>" : "<interleave>");
            for (String child : children) {
                if (!list) content.append("<optional>");
                appendReference(content, child);
                if (!list) content.append("</optional>");
            }
            content.append(list ? "</choice></zeroOrMore>" : "</interleave>");
            appendRule(grammar, name, content.toString());
        });
        return grammar.append("</grammar>").toString();
    }

    private static void appendReference(StringBuilder grammar, String name) {
        grammar.append("<ref name=\"").append(name).append("\"/>");
    }

    private static void appendRule(StringBuilder grammar, String name, String content) {
        grammar.append("<define name=\"").append(name).append("\"><element name=\"").append(name)
                .append("\"><zeroOrMore><attribute><anyName/></attribute></zeroOrMore>")
                .append(content).append("</element></define>");
    }

    // Advisory text can be omitted if it has no owner. Evidence requests need an explicit recipient.
    private static final Set<String> ADVISORY = Set.of("nextCheck");

    static List<String> normalize(Element root) throws IOException {
        var notes = new LinkedHashSet<String>();
        if (root.getTagName().equals("adaptive-review")) {
            var candidates = new ArrayList<Element>();
            var misplaced = new ArrayList<>(advisories(root));
            var requests = new ArrayList<>(ReviewXml.children(root, null).stream()
                    .filter(child -> child.getTagName().equals("requests")).toList());
            for (Element list : ReviewXml.children(root, null)) {
                if (!list.getTagName().equals("decisions")) continue;
                misplaced.addAll(advisories(list));
                for (Element entry : ReviewXml.children(list, null)) {
                    if (entry.getTagName().equals("local-decision")) candidates.add(entry);
                    if (entry.getTagName().equals("requests")) requests.add(entry);
                }
            }
            for (Element candidate : candidates) normalizeFindingAdvisories(candidate, notes);
            placeAdvisories(misplaced, candidates.size() == 1 ? candidates.getFirst() : null, notes);
            if (requests.size() == 1 && placeRequests(requests.getFirst(), candidates))
                notes.add("Moved misplaced evidence requests to their uniquely identified unresolved candidates; raw response retained.");
        } else if (root.getTagName().equals("verification")) {
            normalizeFindingAdvisories(root, notes);
        }
        return List.copyOf(notes);
    }

    private record RequestOwner(Element decision, Element existing, String context) { }

    private static boolean placeRequests(Element misplaced, List<Element> candidates) throws IOException {
        List<String> lines = plainRequestLines(misplaced);
        if (lines == null || lines.isEmpty()) return false;
        var owners = new ArrayList<RequestOwner>();
        for (Element candidate : candidates) {
            var fields = ReviewXml.fields(candidate, LOCAL_DECISION);
            if (!fieldText(fields, "status").equals("INSUFFICIENT_EVIDENCE")) continue;
            Element finding = fields.get("finding");
            if (finding == null) continue;
            var findingFields = ReviewXml.fields(finding, FINDING);
            String context = String.join("\n", fieldText(fields, "reason"), fieldText(fields, "nextCheck"),
                    fieldText(findingFields, "path"), fieldText(findingFields, "evidence"));
            owners.add(new RequestOwner(candidate, fields.get("requests"), context));
        }
        var assigned = new LinkedHashMap<RequestOwner, LinkedHashSet<String>>();
        for (String line : lines) {
            EvidenceQuery query;
            try { query = EvidenceQuery.parse(line, LanguageRegistry.defaults()); }
            catch (IOException invalid) { return false; }
            List<RequestOwner> matches = candidates.size() == 1 ? owners
                    : owners.stream().filter(owner -> namesSource(owner.context(), query)).toList();
            if (matches.size() != 1) return false;
            RequestOwner owner = matches.getFirst();
            if (!assigned.containsKey(owner)) {
                List<String> existing = owner.existing() == null ? List.of() : plainRequestLines(owner.existing());
                if (existing == null) return false;
                assigned.put(owner, new LinkedHashSet<>(existing));
            }
            assigned.get(owner).add(line);
        }
        // Change the tree only once every original request has a clear recipient.
        for (var entry : assigned.entrySet()) {
            RequestOwner owner = entry.getKey();
            Element target = owner.existing() == null ? owner.decision().getOwnerDocument().createElement("requests") : owner.existing();
            target.setTextContent(String.join("\n", entry.getValue()));
            if (owner.existing() == null) owner.decision().appendChild(target);
        }
        misplaced.getParentNode().removeChild(misplaced);
        return true;
    }

    private static List<String> plainRequestLines(Element field) {
        if (field.hasAttributes() || field.getElementsByTagName("*").getLength() != 0) return null;
        String text = field.getTextContent();
        var lines = text.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
        return lines.equals(EvidenceRequest.parse(text).stream().map(EvidenceRequest::text).toList()) ? lines : null;
    }

    private static boolean namesSource(String context, EvidenceQuery query) {
        if (query.operation() == EvidenceQuery.Operation.FIND_REFERENCES)
            return VerificationEvidence.mentioned(context, query.reference().replaceFirst("^new ", ""));
        if (query.operation() == EvidenceQuery.Operation.FIND_FILES) return false;
        String file = query.resource();
        if (VerificationEvidence.mentioned(context, file)) return true;
        String name = file.substring(file.lastIndexOf('/') + 1);
        int extension = name.lastIndexOf('.');
        return extension > 0 && VerificationEvidence.mentioned(context, name.substring(0, extension));
    }

    private static String fieldText(Map<String, Element> fields, String name) {
        Element field = fields.get(name);
        return field == null ? "" : field.getTextContent().strip();
    }

    private static List<Element> advisories(Element parent) throws IOException {
        return ReviewXml.children(parent, null).stream().filter(child -> ADVISORY.contains(child.getTagName())).toList();
    }

    private static void normalizeFindingAdvisories(Element owner, Set<String> notes) throws IOException {
        var misplaced = new ArrayList<Element>();
        var findings = owner.getElementsByTagName("finding");
        for (int i = 0; i < findings.getLength(); i++) misplaced.addAll(advisories((Element) findings.item(i)));
        placeAdvisories(misplaced, owner, notes);
    }

    private static void placeAdvisories(List<Element> misplaced, Element owner, Set<String> notes) throws IOException {
        if (misplaced.isEmpty()) return;
        var fields = owner == null ? List.<Element>of() : ReviewXml.children(owner, null);
        var statuses = fields.stream().filter(field -> field.getTagName().equals("status")).toList();
        boolean unresolved = statuses.size() == 1 && statuses.getFirst().getTextContent().strip().equals("INSUFFICIENT_EVIDENCE");
        for (String name : ADVISORY) {
            var suggestions = misplaced.stream().filter(field -> field.getTagName().equals(name)).toList();
            if (suggestions.isEmpty()) continue;
            Element suggestion = suggestions.getFirst();
            boolean canMove = unresolved && suggestions.size() == 1
                    && fields.stream().noneMatch(field -> field.getTagName().equals(name))
                    && !suggestion.hasAttributes() && suggestion.getElementsByTagName("*").getLength() == 0
                    && !suggestion.getTextContent().isBlank();
            if (canMove) {
                owner.appendChild(suggestion);
                notes.add("Moved misplaced advisory <" + name + "> to its unresolved candidate; raw response retained.");
            } else {
                for (Element field : suggestions) field.getParentNode().removeChild(field);
                notes.add("Omitted misplaced advisory <" + name + "> without a unique unresolved recipient; raw response retained.");
            }
        }
    }

    static void validate(Element element) throws IOException {
        String name = element.getTagName();
        if (TEXT.contains(name)) {
            if (element.getElementsByTagName("*").getLength() != 0) throw new IOException("Nested element in review text field: " + name);
            return;
        }
        if (!container(name)) throw new IOException("Unexpected review element: " + name);
        var seen = new HashSet<String>();
        for (Element child : ReviewXml.children(element, null)) {
            if (!allows(name, child.getTagName())) {
                String message = "Unexpected review element " + child.getTagName() + " in " + name;
                if (child.getTagName().equals("requests") && (name.equals("adaptive-review") || name.equals("decisions")))
                    message += ". " + CANDIDATE_REQUESTS.strip();
                throw new IOException(message);
            }
            if (!LISTS.contains(name) && !seen.add(child.getTagName())) {
                throw new IOException("Unexpected or repeated review field: " + child.getTagName());
            }
            validate(child);
        }
    }
}
