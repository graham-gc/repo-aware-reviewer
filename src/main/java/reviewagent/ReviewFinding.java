package reviewagent;

record ReviewFinding(String id, String title, Severity severity, String path, int line, int endLine,
                     String trigger, String impact, String evidence, String diffRelation) {
    enum Severity {
        CRITICAL, HIGH, MEDIUM, LOW
    }

    String markdown(OutputLanguage language) {
        String level = language.text(severity.name(), switch (severity) {
            case CRITICAL -> "严重";
            case HIGH -> "高";
            case MEDIUM -> "中";
            case LOW -> "低";
        });
        String location = path + ":" + line + (endLine == line ? "" : "–" + endLine);
        return "### [" + level + "] " + EvidenceText.oneLine(title) + "\n\n"
                + "- " + language.text("Location", "位置") + " — `" + location + "`\n"
                + "- " + language.text("Trigger", "触发条件") + " — " + EvidenceText.oneLine(trigger) + "\n"
                + "- " + language.text("Impact", "影响") + " — " + EvidenceText.oneLine(impact) + "\n"
                + "- " + language.text("Evidence", "证据") + " — " + EvidenceText.oneLine(evidence) + "\n"
                + "- " + language.text("Diff relationship", "与本次变更的关系") + " — "
                + EvidenceText.oneLine(diffRelation) + "\n\n";
    }
}
