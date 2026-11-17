package reviewagent;

/** Human-facing prose must not use the escaping intended for audit records and source quotations. */
final class ReportText {
    static String inline(String value) {
        return value.replaceAll("\\s+", " ").strip();
    }

    static String summary(String value, int maximum) {
        String text = inline(value);
        if (text.codePointCount(0, text.length()) <= maximum) return text;
        return text.substring(0, text.offsetByCodePoints(0, maximum)).stripTrailing() + "…";
    }
}
