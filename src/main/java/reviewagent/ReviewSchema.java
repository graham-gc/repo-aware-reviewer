package reviewagent;

import org.w3c.dom.Element;
import java.io.IOException;
import java.util.HashSet;
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
    static final Set<String> ADAPTIVE_REVIEW = Set.of("summary", "route", "decisions");
    static final Set<String> DOCUMENTATION_REVIEW = Set.of("summary", "escalate", "doc-findings");
    static final Set<String> DOCUMENTATION_FINDING = Set.of("title", "trigger", "impact", "citations");
    static final Set<String> LOCAL_DECISION = Set.of(
            "status", "reason", "nextCheck", "relation", "baseline", "contract", "support", "premises", "citations", "finding", "requests");
    private static final Set<String> LISTS = Set.of("findings", "premises", "citations", "assessment", "candidate-groups",
            "finding-groups", "group", "decisions", "doc-findings");
    private static final Map<String, Set<String>> CHILDREN = Map.ofEntries(
            Map.entry("findings", Set.of("finding")), Map.entry("finding", findingChildren()),
            Map.entry("adaptive-review", ADAPTIVE_REVIEW), Map.entry("decisions", Set.of("local-decision")),
            Map.entry("documentation-review", DOCUMENTATION_REVIEW), Map.entry("doc-findings", Set.of("doc-finding")),
            Map.entry("doc-finding", DOCUMENTATION_FINDING),
            Map.entry("local-decision", LOCAL_DECISION),
            Map.entry("verification", VERIFICATION), Map.entry("premises", Set.of("premise")),
            Map.entry("premise", Set.of("statement", "basis", "citations")), Map.entry("citations", Set.of("citation")),
            Map.entry("citation", Set.of("source", "quote", "lines")), Map.entry("assessment", Set.of("item")),
            Map.entry("item", ASSESSMENT_ITEM), Map.entry("candidate-groups", Set.of("group")),
            Map.entry("finding-groups", Set.of("group")), Map.entry("group", Set.of("id")));

    private static Set<String> findingChildren() {
        var fields = new HashSet<>(FINDING); fields.add("finding"); return Set.copyOf(fields);
    }
    static boolean container(String name) { return CHILDREN.containsKey(name); }
    static boolean allows(String parent, String child) { return CHILDREN.getOrDefault(parent, Set.of()).contains(child); }

    static void validate(Element element) throws IOException {
        String name = element.getTagName();
        if (TEXT.contains(name)) {
            if (element.getElementsByTagName("*").getLength() != 0) throw new IOException("Nested element in review text field: " + name);
            return;
        }
        if (!container(name)) throw new IOException("Unexpected review element: " + name);
        var seen = new HashSet<String>();
        for (Element child : ReviewXml.children(element, null)) {
            if (!allows(name, child.getTagName())) throw new IOException("Unexpected review element " + child.getTagName() + " in " + name);
            if (!LISTS.contains(name) && !seen.add(child.getTagName())) {
                throw new IOException("Unexpected or repeated review field: " + child.getTagName());
            }
            validate(child);
        }
    }
}
