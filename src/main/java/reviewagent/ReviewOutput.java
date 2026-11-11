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

    enum Status {
        CONFIRMED, REJECTED, INSUFFICIENT_EVIDENCE
    }

    record Verification(ReviewFinding candidate, Status status, String reason, ReviewFinding confirmed) {
    }

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
        Map<String, Element> fields = fields(root, Set.of("status", "reason", "finding"));
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
