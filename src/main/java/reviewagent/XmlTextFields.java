package reviewagent;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

/** Protocol text fields carry literal prose and source syntax, not nested protocol elements. */
final class XmlTextFields {
    static final Pattern TOKEN = Pattern.compile(
            "(?s)<!--.*?-->|<!\\[CDATA\\[.*?]]>|<\\?.*?\\?>|<(?:\"[^\"]*\"|'[^']*'|[^'\"<>])*>");
    private static final Set<String> NAMES = ReviewSchema.TEXT;
    private static final Pattern OPEN = Pattern.compile("<(" + String.join("|", NAMES) + ")\\s*>");
    private static final Map<String, Pattern> CLOSE = NAMES.stream().collect(Collectors.toUnmodifiableMap(
            name -> name, name -> Pattern.compile("</" + Pattern.quote(name) + "\\s*>")));
    private static final Pattern RAW_AMPERSAND = Pattern.compile(
            "&(?!(?:amp|lt|gt|apos|quot|#(?:[0-9]+|x[0-9a-fA-F]+));)");

    record End(int contentEnd, int end) { }

    static String opening(String token) {
        var match = OPEN.matcher(token);
        return match.matches() ? match.group(1) : null;
    }

    static End end(String text, int contentStart, String field) throws IOException {
        int cursor = contentStart;
        while (true) {
            var closing = CLOSE.get(field).matcher(text);
            boolean found = closing.find(cursor);
            int cdata = text.indexOf("<![CDATA[", cursor);
            if (cdata >= 0 && (!found || cdata < closing.start())) {
                int cdataEnd = text.indexOf("]]>", cdata + 9);
                if (cdataEnd < 0) throw error(text, cdata, "Unterminated CDATA in " + field);
                cursor = cdataEnd + 3;
                continue;
            }
            if (!found) throw error(text, contentStart, "Missing </" + field + "> in text field; encode literal closing tags with CDATA");
            return new End(closing.start(), closing.end());
        }
    }

    static String protect(String xml) throws IOException {
        var tokens = TOKEN.matcher(xml);
        StringBuilder result = new StringBuilder(xml.length());
        int copied = 0;
        while (tokens.find()) {
            String field = opening(tokens.group());
            if (field == null) continue;
            End end = end(xml, tokens.end(), field);
            result.append(xml, copied, tokens.end());
            appendText(result, xml.substring(tokens.end(), end.contentEnd()));
            result.append(xml, end.contentEnd(), end.end());
            copied = end.end();
            tokens.region(copied, xml.length());
        }
        return result.append(xml, copied, xml.length()).toString();
    }

    private static void appendText(StringBuilder output, String text) throws IOException {
        int cursor = 0;
        while (cursor < text.length()) {
            int cdata = text.indexOf("<![CDATA[", cursor);
            int rawEnd = cdata < 0 ? text.length() : cdata;
            output.append(RAW_AMPERSAND.matcher(text.substring(cursor, rawEnd)).replaceAll("&amp;")
                    .replace("<", "&lt;").replace(">", "&gt;"));
            if (cdata < 0) break;
            int end = text.indexOf("]]>", cdata + 9);
            if (end < 0) throw error(text, cdata, "Unterminated CDATA in text field");
            output.append(text, cdata, end + 3);
            cursor = end + 3;
        }
    }

    private static IOException error(String text, int position, String message) {
        int line = 1;
        for (int i = 0; i < position; i++) if (text.charAt(i) == '\n') line++;
        int column = position - text.lastIndexOf('\n', position - 1);
        return new IOException(message + " at line " + line + ", column " + column);
    }
}
