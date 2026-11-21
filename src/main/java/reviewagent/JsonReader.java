package reviewagent;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Strict JSON decoding for the CLI response envelope. */
final class JsonReader {
    private static final Pattern NUMBER = Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");
    private final String input;
    private int offset;

    private JsonReader(String input) { this.input = input; }

    static Map<?, ?> object(String input) throws IOException {
        var reader = new JsonReader(input);
        Object value = reader.value(0);
        reader.space();
        if (reader.offset != input.length() || !(value instanceof Map<?, ?> map)) throw reader.invalid();
        return map;
    }

    private Object value(int depth) throws IOException {
        space();
        if (depth > 64 || offset == input.length()) throw invalid();
        return switch (input.charAt(offset)) {
            case '{' -> fields(depth);
            case '[' -> items(depth);
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> fields(int depth) throws IOException {
        offset++;
        var fields = new LinkedHashMap<String, Object>();
        if (take('}')) return fields;
        do {
            String key = string();
            if (fields.containsKey(key) || !take(':')) throw invalid();
            fields.put(key, value(depth + 1));
        } while (take(','));
        if (!take('}')) throw invalid();
        return fields;
    }

    private List<Object> items(int depth) throws IOException {
        offset++;
        var items = new ArrayList<>();
        if (take(']')) return items;
        do {
            items.add(value(depth + 1));
        } while (take(','));
        if (!take(']')) throw invalid();
        return items;
    }

    private String string() throws IOException {
        if (!take('"')) throw invalid();
        var text = new StringBuilder();
        while (offset < input.length()) {
            char next = input.charAt(offset++);
            if (next == '"') return text.toString();
            if (next < ' ') throw invalid();
            if (next != '\\') {
                text.append(next);
                continue;
            }
            if (offset == input.length()) throw invalid();
            char escape = input.charAt(offset++);
            text.append(switch (escape) {
                case '"', '\\', '/' -> escape;
                case 'b' -> '\b';
                case 'f' -> '\f';
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case 'u' -> unicode();
                default -> throw invalid();
            });
        }
        throw invalid();
    }

    private char unicode() throws IOException {
        if (input.length() - offset < 4) throw invalid();
        try {
            char decoded = (char) HexFormat.fromHexDigits(input, offset, offset + 4);
            offset += 4;
            return decoded;
        } catch (IllegalArgumentException invalid) { throw invalid(); }
    }

    private Object literal(String literal, Object value) throws IOException {
        if (!input.startsWith(literal, offset)) throw invalid();
        offset += literal.length();
        return value;
    }

    private BigDecimal number() throws IOException {
        var match = NUMBER.matcher(input).region(offset, input.length());
        if (!match.lookingAt()) throw invalid();
        offset = match.end();
        try { return new BigDecimal(match.group()); }
        catch (NumberFormatException invalid) { throw invalid(); }
    }

    private boolean take(char expected) {
        space();
        if (offset == input.length() || input.charAt(offset) != expected) return false;
        offset++;
        return true;
    }

    private void space() {
        while (offset < input.length() && " \t\r\n".indexOf(input.charAt(offset)) >= 0) offset++;
    }

    private IOException invalid() { return new IOException("Invalid structured JSON response at character " + (offset + 1)); }
}
