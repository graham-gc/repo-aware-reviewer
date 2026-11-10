package reviewagent;

import java.util.Locale;

enum OutputLanguage {
    EN("en", "English (en)"),
    ZH_CN("zh-CN", "Simplified Chinese (zh-CN)");

    private final String code;
    private final String description;

    OutputLanguage(String code, String description) {
        this.code = code;
        this.description = description;
    }

    static OutputLanguage parse(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "en", "en-us", "english" -> EN;
            case "zh", "zh-cn", "chinese", "简体中文" -> ZH_CN;
            default -> throw new IllegalArgumentException("--language must be en or zh-CN");
        };
    }

    String code() {
        return code;
    }

    String description() {
        return description;
    }

    String text(String english, String chinese) {
        return this == ZH_CN ? chinese : english;
    }

    String instructions() {
        return """
                Output language: %s
                Write every heading, explanation, finding, checklist, and assessment in this language,
                even when source documents use another language. Translate business statements and source
                document titles when discussing them, and cite their paths or section IDs.
                Preserve exact code, paths, API names, identifiers, hashes, and evidence-request protocol
                keywords. Write request reasons in the selected output language. Knowledge lookup keywords
                may use the source document's language.
                Output raw Markdown with a space after heading markers.

                """.formatted(description);
    }
}
