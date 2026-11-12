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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** XML envelope and scalar decoding only; no review policy or report rendering. */
final class ReviewXml {
    private static final Pattern XML_BLOCK = Pattern.compile(
            "(?ms)^ {0,3}(`{3,}|~{3,})[ \\t]*(?i:xml)?[ \\t]*\\R(.*?)^ {0,3}\\1[ \\t]*(?:\\R|$)");
    record XmlBlock(Element root, String remainder) {
    }

    record Span(int start, int end) {
    }

    static Map<String, Element> fields(Element root, Set<String> allowed) throws IOException {
        var fields = new LinkedHashMap<String, Element>();
        for (Element element : children(root, null)) {
            if (!allowed.contains(element.getTagName()) || fields.putIfAbsent(element.getTagName(), element) != null) {
                throw new IOException("Unexpected or repeated review field: " + element.getTagName());
            }
        }
        return fields;
    }

    static String text(Map<String, Element> fields, String name) throws IOException {
        Element field = fields.get(name);
        if (field == null || field.getTextContent().isBlank() || field.getElementsByTagName("*").getLength() != 0) {
            throw new IOException("Missing or invalid review field: " + name);
        }
        return field.getTextContent().strip();
    }

    static List<Element> children(Element root, String expected) throws IOException {
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

    static XmlBlock xml(String response, String rootName) throws IOException {
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
            Element root = builder.parse(new org.xml.sax.InputSource(new StringReader(XmlTextFields.protect(content)))).getDocumentElement();
            if (!root.getTagName().equals(rootName)) {
                throw new IOException("Unexpected review response root");
            }
            return new XmlBlock(root, response.substring(0, start) + response.substring(end));
        } catch (SAXParseException invalid) {
            throw new IOException("Invalid " + rootName + " response structure at line " + invalid.getLineNumber()
                    + ", column " + invalid.getColumnNumber() + ": " + invalid.getMessage(), invalid);
        } catch (ParserConfigurationException | SAXException invalid) {
            throw new IOException("Invalid " + rootName + " response structure", invalid);
        }
    }

    static Span rootSpan(String response, String rootName) throws IOException {
        Pattern opening = Pattern.compile("<" + Pattern.quote(rootName) + "(?=[\\s/>]).*", Pattern.DOTALL);
        Pattern closing = Pattern.compile("</" + Pattern.quote(rootName) + "\\s*>");
        // Keep source offsets while excluding protocol examples quoted in Markdown.
        String searchable = Pattern.compile("(?m)^ {0,3}>[^\\r\\n]*").matcher(response)
                .replaceAll(match -> " ".repeat(match.group().length()));
        var tokens = XmlTextFields.TOKEN.matcher(searchable);
        Span result = null;
        int start = -1;
        int depth = 0;
        while (tokens.find()) {
            String token = tokens.group();
            String field = XmlTextFields.opening(token);
            if (depth > 0 && field != null) {
                tokens.region(XmlTextFields.end(response, tokens.end(), field).end(), searchable.length());
                continue;
            }
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
