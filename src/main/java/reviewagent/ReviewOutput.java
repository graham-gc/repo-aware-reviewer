package reviewagent;

import org.w3c.dom.Element;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class ReviewOutput {
    private static final Pattern XML_TOKEN = Pattern.compile(
            "(?s)<!--.*?-->|<!\\[CDATA\\[.*?]]>|<\\?.*?\\?>|<(?:\"[^\"]*\"|'[^']*'|[^'\"<>])*>");
    private static final Pattern XML_BLOCK = Pattern.compile(
            "(?ms)^ {0,3}(`{3,}|~{3,})[ \\t]*(?i:xml)?[ \\t]*\\R(.*?)^ {0,3}\\1[ \\t]*(?:\\R|$)");
    private static final Set<String> FINDING_FIELDS = Set.of("id", "title", "severity", "path", "line", "endLine",
            "trigger", "impact", "evidence", "diffRelation");
    private static final Set<String> ASSESSMENT_FIELDS = Set.of("id", "requirement", "requirementText", "behavior",
            "status", "reasoning", "boundaries", "gap", "citations");

    enum Status {
        CONFIRMED, REJECTED, INSUFFICIENT_EVIDENCE
    }

    record Verification(ReviewFinding candidate, Status status, String reason, ReviewFinding confirmed) {
    }

    enum Relation { INTRODUCED, EXPOSED_BY_CHANGE, UNMET_REQUIREMENT, PRE_EXISTING, UNRESOLVED }

    record Decision(Verification verdict, Relation relation, String baseline, String contract,
                    List<EvidenceIndex.Citation> citations) { }

    enum AssessmentStatus { SATISFIED, NOT_SATISFIED, INSUFFICIENT_EVIDENCE }

    record AssessmentItem(String id, String requirement, String requirementText, String behavior, AssessmentStatus status,
                          String reasoning, String boundaries, String gap, List<EvidenceIndex.Citation> citations) { }

    record Discovery(List<ReviewFinding> findings, String notes) {
    }

    private record XmlBlock(Element root, String remainder) {
    }

    private record Span(int start, int end) {
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
        Map<String, Element> fields = fields(root, Set.of("status", "reason", "finding", "relation", "baseline", "contract", "citations"));
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

    static Decision groundedVerification(String response, ReviewFinding candidate, EvidenceBundle evidence,
                                         EvidenceIndex index) throws IOException {
        Verification verdict = verification(response, candidate, evidence.changedFiles());
        Map<String, Element> fields = fields(xml(response, "verification").root(),
                Set.of("status", "reason", "finding", "relation", "baseline", "contract", "citations"));
        Relation relation = Relation.UNRESOLVED;
        if (fields.containsKey("relation")) {
            try { relation = Relation.valueOf(text(fields, "relation")); }
            catch (IllegalArgumentException invalid) { throw new IOException("Unknown finding relationship", invalid); }
        }
        List<EvidenceIndex.Citation> citations = citations(fields.get("citations"));
        index.validate(citations);
        String baseline = fields.containsKey("baseline") ? text(fields, "baseline") : "";
        String contract = fields.containsKey("contract") ? text(fields, "contract") : "";
        if (verdict.status() == Status.CONFIRMED) {
            if (!EvidenceRequest.parse(requestText(response, "verification")).isEmpty()) {
                throw new IOException("A finding with unresolved evidence requests must be INSUFFICIENT_EVIDENCE");
            }
            if (relation == Relation.UNRESOLVED || relation == Relation.PRE_EXISTING || baseline.isEmpty() || contract.isEmpty()) {
                throw new IOException("Confirmed findings require explicit change attribution, baseline comparison and applicable contract");
            }
            if (!index.hasImplementation(citations) || !index.hasTarget(citations)) {
                throw new IOException("Confirmed findings require quotations from included implementation evidence at the target revision");
            }
            if (relation != Relation.UNMET_REQUIREMENT && !index.hasBaseline(citations)) {
                throw new IOException("Introduced or exposed findings require baseline or diff evidence");
            }
            if (relation == Relation.UNMET_REQUIREMENT && !index.hasContract(citations)) {
                throw new IOException("An unmet requirement requires a quotation from a supplied requirement or trusted rule");
            }
        }
        return new Decision(verdict, relation, baseline, contract, citations);
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

    static List<AssessmentItem> assessment(String response, InputDocument requirement, EvidenceIndex index,
                                           List<AssessmentItem> previous) throws IOException {
        var items = new ArrayList<AssessmentItem>();
        var ids = new HashSet<String>();
        List<Element> elements = children(xml(response, "assessment").root(), "item");
        var quoteErrors = new ArrayList<String>();
        String original = EvidenceIndex.normalize(requirement.content());
        for (Element element : elements) {
            Map<String, Element> fields = fields(element, ASSESSMENT_FIELDS);
            String quote = text(fields, "requirement");
            if (!original.contains(EvidenceIndex.normalize(quote))) {
                quoteErrors.add(text(fields, "id") + " <requirement>: " + EvidenceText.oneLine(quote));
            }
            try {
                index.validate(citations(fields.get("citations")));
            } catch (SourceQuotationException invalid) {
                quoteErrors.add(text(fields, "id") + " " + invalid.getMessage());
            }
        }
        if (!quoteErrors.isEmpty()) {
            throw new SourceQuotationException("Assessment requirement and citation quotations must match supplied sources verbatim. "
                    + "These requirement or citation excerpts do not match their supplied sources:\n" + String.join("\n", quoteErrors)
                    + "\nRe-copy an applicable contiguous excerpt exactly from the External requirement evidence block, "
                    + "including its original conjunctions and punctuation. Put paraphrases only in requirementText. "
                    + "Correct matching requirement citations as well; do not invent or strengthen a requirement.");
        }
        for (Element element : elements) {
            Map<String, Element> fields = fields(element, ASSESSMENT_FIELDS);
            String id = text(fields, "id");
            if (!id.matches("A[1-9][0-9]*") || !ids.add(id)) throw new IOException("Invalid or repeated requirement assessment ID");
            String quote = text(fields, "requirement");
            AssessmentStatus status;
            try { status = AssessmentStatus.valueOf(text(fields, "status")); }
            catch (IllegalArgumentException invalid) { throw new IOException("Unknown atomic requirement status", invalid); }
            var citations = citations(fields.get("citations"));
            if (status != AssessmentStatus.INSUFFICIENT_EVIDENCE && (!index.hasImplementation(citations) || !index.hasTarget(citations))) {
                throw new IOException("A conclusive requirement assessment requires implementation quotations");
            }
            items.add(new AssessmentItem(id, quote, text(fields, "requirementText"), text(fields, "behavior"), status, text(fields, "reasoning"),
                    text(fields, "boundaries"), text(fields, "gap"), citations));
        }
        if (items.isEmpty()) throw new IOException("Supplied requirements require a nonempty assessment");
        for (AssessmentItem old : previous) {
            if (items.stream().noneMatch(item -> item.id().equals(old.id()) && item.requirement().equals(old.requirement()))) {
                throw new IOException("Requirement audit omitted or remapped an existing assessment item: " + old.id());
            }
        }
        if (!EvidenceRequest.parse(requestText(response, "assessment")).isEmpty() && items.stream().noneMatch(item -> item.status() == AssessmentStatus.INSUFFICIENT_EVIDENCE)) {
            throw new IOException("Unresolved requirement evidence requests require an INSUFFICIENT_EVIDENCE item");
        }
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
            var fields = fields(citation, Set.of("source", "quote"));
            String source = text(fields, "source");
            try {
                citations.add(new EvidenceIndex.Citation(source, text(fields, "quote")));
            } catch (IOException invalidQuote) {
                quoteErrors.add(EvidenceText.oneLine(source) + ": quote must contain nonempty literal source text. "
                        + "Escape XML markup or wrap the original excerpt in CDATA; do not put nested XML elements inside quote.");
            }
        }
        if (!quoteErrors.isEmpty()) throw new SourceQuotationException(String.join("\n", quoteErrors));
        return List.copyOf(citations);
    }

    static String assessmentMarkdown(List<AssessmentItem> items, OutputLanguage language) {
        if (items.isEmpty()) return language.text("No requirements document was supplied; acceptance was not assessed.",
                "未提供需求文档，未进行需求验收。") + "\n";
        StringBuilder out = new StringBuilder();
        for (AssessmentItem item : items) {
            String status = language.text(item.status().name(), switch (item.status()) {
                case SATISFIED -> "满足";
                case NOT_SATISFIED -> "未满足";
                case INSUFFICIENT_EVIDENCE -> "证据不足";
            });
            out.append("### ").append(item.id()).append(" — ").append(EvidenceText.oneLine(item.behavior())).append("\n\n")
                    .append("- ").append(language.text("Assessment", "结论")).append(": ").append(status).append('\n')
                    .append("- ").append(language.text("Requirement", "需求")).append(": ").append(EvidenceText.oneLine(item.requirementText())).append('\n')
                    .append("- ").append(language.text("Reasoning", "依据")).append(": ").append(EvidenceText.oneLine(item.reasoning())).append('\n')
                    .append("- ").append(language.text("Boundary checks", "边界核查")).append(": ").append(EvidenceText.oneLine(item.boundaries())).append('\n')
                    .append("- ").append(language.text("Gap or limitation", "缺口或限制")).append(": ").append(EvidenceText.oneLine(item.gap())).append('\n');
            for (EvidenceIndex.Citation citation : item.citations()) {
                out.append("- ").append(language.text("Source", "证据来源")).append(": ")
                        .append('`').append(EvidenceText.oneLine(citationReference(citation.source(), language))).append("`\n");
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static String citationReference(String source, OutputLanguage language) {
        if (source.startsWith("KB-")) return source.split(" — ", 2)[0];
        return source.replace("External requirement — ", language.text("Requirements: ", "需求文档："))
                .replace("Human-maintained knowledge base — ", language.text("Knowledge base: ", "主知识库："));
    }

    static String finalReport(String response, List<ReviewFinding> confirmed, OutputLanguage language,
                              String assessment) throws IOException {
        String report = finalReport(response, confirmed, language);
        var headings = headings(language);
        int start = report.indexOf("\n" + headings.get(3) + "\n") + 1 + headings.get(3).length();
        int end = report.indexOf("\n" + headings.get(4) + "\n", start) + 1;
        return report.substring(0, start) + "\n\n" + assessment.strip() + "\n\n" + report.substring(end);
    }

    static String finalReport(String response, List<ReviewFinding> confirmed, OutputLanguage language) throws IOException {
        XmlBlock block = xml(response, "finding-groups");
        var byId = new LinkedHashMap<String, ReviewFinding>();
        confirmed.forEach(finding -> byId.put(finding.id(), finding));
        var seen = new HashSet<String>();
        var representatives = new ArrayList<ReviewFinding>();
        for (Element group : children(block.root(), "group")) {
            var members = new ArrayList<ReviewFinding>();
            for (Element id : children(group, "id")) {
                String value = id.getTextContent().strip();
                if (!byId.containsKey(value) || !seen.add(value)) {
                    throw new IOException("Final grouping contains an unknown or repeated finding ID");
                }
                members.add(byId.get(value));
            }
            if (members.isEmpty()) {
                throw new IOException("Final grouping contains an empty group");
            }
            representatives.add(members.stream().min(Comparator.comparing(ReviewFinding::severity)).orElseThrow());
        }
        if (!seen.equals(byId.keySet())) {
            throw new IOException("Final grouping omitted confirmed findings");
        }
        String report = block.remainder().strip();
        List<String> headings = headings(language);
        if (!report.lines().filter(line -> line.startsWith("# ")).toList().equals(headings)) {
            throw new IOException("Final report has missing, repeated, or unexpected top-level headings");
        }
        String findingsHeading = headings.get(2);
        int start = report.indexOf("\n" + findingsHeading + "\n") + 1 + findingsHeading.length();
        int end = report.indexOf("\n" + headings.get(3) + "\n", start) + 1;
        StringBuilder findings = new StringBuilder("\n\n");
        if (representatives.isEmpty()) {
            findings.append(language.text("No verified findings.", "未发现经复核确认的问题。")).append("\n\n");
        }
        for (ReviewFinding finding : representatives.stream().sorted(Comparator.comparing(ReviewFinding::severity)).toList()) {
            findings.append(finding.markdown(language));
        }
        return report.substring(0, start) + findings + report.substring(end) + "\n";
    }

    static List<String> headings(OutputLanguage language) {
        return language == OutputLanguage.ZH_CN
                ? List.of("# 审查范围", "# 功能与业务变更", "# 问题发现", "# 需求验收", "# 知识库反馈", "# 验证限制")
                : List.of("# Review scope", "# Functional and business change", "# Findings", "# Requirement assessment",
                        "# Knowledge-base feedback", "# Verification limits");
    }

    static String emptyDiffReport(ReviewScope scope, OutputLanguage language, boolean hasRequirement) {
        List<String> content = List.of(
                language.text("Base", "基线") + ": `" + scope.baseCommit() + "`\n\n"
                        + language.text("Target", "目标") + ": `" + scope.targetCommit() + "`",
                language.text("The baseline and target contain no file differences.", "基线与目标之间没有文件差异。"),
                language.text("Skipped: there are no changes to review.", "已跳过：没有需要审查的变更。"),
                emptyDiffAssessment(language, hasRequirement),
                language.text("Skipped because there are no code changes.", "没有代码变更，未评估知识库反馈。"),
                language.text("This execution made no model calls and ran no build, tests, or static analyzer. "
                                + "Choose an earlier baseline to review a change, or use --commit <ref> to compare a commit with its first parent.",
                        "本次执行未调用模型，未执行构建、测试或静态检查。请指定更早的基线来审查变更，"
                                + "或使用 --commit <ref> 比较某次提交与其第一父提交。"));
        StringBuilder report = new StringBuilder();
        List<String> titles = headings(language);
        for (int i = 0; i < titles.size(); i++) report.append(titles.get(i)).append("\n\n").append(content.get(i)).append("\n\n");
        return report.toString();
    }

    static String emptyDiffAssessment(OutputLanguage language, boolean hasRequirement) {
        return hasRequirement
                ? language.text("Not assessed: an empty diff does not establish that the target satisfies the requirements.",
                    "未进行需求验收：空 diff 不能证明目标版本已满足需求。")
                : language.text("No requirements document was supplied; acceptance was not assessed.", "未提供需求文档，未进行需求验收。");
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

    private static Map<String, Element> fields(Element root, Set<String> allowed) throws IOException {
        var fields = new LinkedHashMap<String, Element>();
        for (Element element : children(root, null)) {
            if (!allowed.contains(element.getTagName()) || fields.putIfAbsent(element.getTagName(), element) != null) {
                throw new IOException("Unexpected or repeated review field: " + element.getTagName());
            }
        }
        return fields;
    }

    private static String text(Map<String, Element> fields, String name) throws IOException {
        Element field = fields.get(name);
        if (field == null || field.getTextContent().isBlank() || field.getElementsByTagName("*").getLength() != 0) {
            throw new IOException("Missing or invalid review field: " + name);
        }
        return field.getTextContent().strip();
    }

    private static List<Element> children(Element root, String expected) throws IOException {
        var elements = new ArrayList<Element>();
        for (var node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) {
                if (expected != null && !element.getTagName().equals(expected)) {
                    throw new IOException("Unexpected review element: " + element.getTagName());
                }
                elements.add(element);
            } else if (!node.getTextContent().isBlank()) {
                throw new IOException("Unexpected text in review structure");
            }
        }
        return elements;
    }

    private static XmlBlock xml(String response, String rootName) throws IOException {
        Span span = rootSpan(response, rootName);
        String content = response.substring(span.start(), span.end());
        int start = span.start();
        int end = span.end();
        var blocks = XML_BLOCK.matcher(response);
        while (blocks.find()) {
            if (blocks.start() <= start && blocks.end() >= end && blocks.group(2).strip().equals(content)) {
                start = blocks.start();
                end = blocks.end();
                break;
            }
        }
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler() {
                @Override
                public void fatalError(SAXParseException error) throws SAXException {
                    throw error;
                }
            });
            Element root = builder.parse(new org.xml.sax.InputSource(new StringReader(content))).getDocumentElement();
            if (!root.getTagName().equals(rootName)) {
                throw new IOException("Unexpected review response root");
            }
            return new XmlBlock(root, response.substring(0, start) + response.substring(end));
        } catch (ParserConfigurationException | SAXException invalid) {
            throw new IOException("Invalid " + rootName + " response structure", invalid);
        }
    }

    private static Span rootSpan(String response, String rootName) throws IOException {
        Pattern opening = Pattern.compile("<" + Pattern.quote(rootName) + "(?=[\\s/>]).*", Pattern.DOTALL);
        Pattern closing = Pattern.compile("</" + Pattern.quote(rootName) + "\\s*>");
        // Keep source offsets while excluding protocol examples quoted in Markdown.
        String searchable = Pattern.compile("(?m)^ {0,3}>[^\\r\\n]*").matcher(response)
                .replaceAll(match -> " ".repeat(match.group().length()));
        var tokens = XML_TOKEN.matcher(searchable);
        Span result = null;
        int start = -1;
        int depth = 0;
        while (tokens.find()) {
            String token = tokens.group();
            if (opening.matcher(token).matches()) {
                if (depth == 0) {
                    if (result != null) {
                        throw new IOException("Repeated " + rootName + " response block");
                    }
                    start = tokens.start();
                }
                depth++;
                if (token.endsWith("/>")) {
                    depth--;
                }
            } else if (closing.matcher(token).matches() && depth > 0) {
                depth--;
            } else {
                continue;
            }
            if (depth == 0) {
                result = new Span(start, tokens.end());
            }
        }
        if (result == null || depth != 0) {
            throw new IOException("Missing " + rootName + " XML response block");
        }
        return result;
    }
}
