package reviewagent;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;

/** Decode a schema-validated Claude Code envelope into the existing review fields. */
final class FocusedJsonResponse {
    static String schema() throws IOException {
        try (var resource = FocusedJsonResponse.class.getResourceAsStream("focused-response.schema.json")) {
            if (resource == null) throw new IOException("Missing focused response schema");
            return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static AdaptiveReview.Response parse(String response, EvidenceBundle evidence, EvidenceIndex index, String prefix) throws IOException {
        var envelope = JsonReader.object(response);
        if (!"result".equals(envelope.get("type")) || !"success".equals(envelope.get("subtype"))
                || !Boolean.FALSE.equals(envelope.get("is_error"))
                || !(envelope.get("structured_output") instanceof Map<?, ?> payload))
            throw new IOException("Claude Code did not return a successful structured output");
        Element root;
        try {
            var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
            root = document.createElement("adaptive-review");
        } catch (ParserConfigurationException unavailable) {
            throw new IOException("Cannot construct internal review fields", unavailable);
        }
        appendFields(root, payload);
        ReviewSchema.validate(root);
        return AdaptiveReview.parseFields(root, evidence, index, prefix, true);
    }

    private static void appendFields(Element parent, Map<?, ?> fields) throws IOException {
        for (var field : fields.entrySet()) {
            String name = (String) field.getKey();
            if (!ReviewSchema.allows(parent.getTagName(), name)) throw new IOException("Unexpected structured review field in " + parent.getTagName());
            Element child = append(parent, name);
            Object value = field.getValue();
            switch (name) {
                case "decisions" -> appendItems(child, value, "local-decision");
                case "premises" -> appendItems(child, value, "premise");
                case "citations" -> appendItems(child, value, "citation");
                case "finding" -> appendFields(child, object(value, name));
                case "requests" -> {
                    var requests = new StringBuilder();
                    for (Object request : array(value, name)) {
                        if (!(request instanceof String text) || text.lines().count() != 1)
                            throw new IOException("Expected one evidence request per structured array item");
                        requests.append(text).append('\n');
                    }
                    child.setTextContent(requests.toString());
                }
                case "line", "endLine" -> {
                    if (!(value instanceof BigDecimal number)) throw new IOException("Expected an integer in structured " + name);
                    try { child.setTextContent(Integer.toString(number.intValueExact())); }
                    catch (ArithmeticException invalid) { throw new IOException("Invalid structured line number", invalid); }
                }
                default -> {
                    if (!(value instanceof String text)) throw new IOException("Expected text in structured " + name);
                    child.setTextContent(text);
                }
            }
        }
    }

    private static void appendItems(Element list, Object value, String itemName) throws IOException {
        for (Object item : array(value, list.getTagName())) appendFields(append(list, itemName), object(item, itemName));
    }

    private static Element append(Element parent, String name) {
        var child = parent.getOwnerDocument().createElement(name);
        parent.appendChild(child);
        return child;
    }

    private static Map<?, ?> object(Object value, String name) throws IOException {
        if (value instanceof Map<?, ?> map) return map;
        throw new IOException("Expected an object in structured " + name);
    }

    private static List<?> array(Object value, String name) throws IOException {
        if (value instanceof List<?> list) return list;
        throw new IOException("Expected an array in structured " + name);
    }
}
