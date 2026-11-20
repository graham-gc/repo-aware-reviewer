package reviewagent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Keep original hunk coordinates when carrying unshown changes into a bounded second scope. */
record FocusedPatch(String shown, String remaining) {
    private static final Pattern HUNK = Pattern.compile("(?m)(?=^@@ )");

    static FocusedPatch select(String patch, int limit, boolean keepAdditionHeader) {
        if (EvidenceText.bytes(patch) <= limit) return new FocusedPatch(patch, "");

        String[] parts = HUNK.split(patch);
        // A type change can contain several Git patches for a path. Keep its metadata together.
        if (parts.length < 2 || patch.contains("\ndiff --git ")) return new FocusedPatch("", patch);
        var selected = fitHunks(parts, limit);
        if (selected.shown().isEmpty() && keepAdditionHeader && parts.length == 2
                && parts[0].contains("\nnew file mode ")) {
            String header = parts[0] + parts[1].substring(0, parts[1].indexOf('\n') + 1);
            // The collector can reconcile this hunk with complete target source.
            if (EvidenceText.bytes(header) <= limit) return new FocusedPatch(header, patch);
        }
        return selected;
    }

    private static FocusedPatch fitHunks(String[] parts, int limit) {
        String header = parts[0];
        var shown = new StringBuilder(header);
        var remaining = new StringBuilder(header);
        int space = limit - EvidenceText.bytes(header);
        for (int i = 1; i < parts.length; i++) {
            int bytes = EvidenceText.bytes(parts[i]);
            if (bytes <= space) {
                shown.append(parts[i]);
                space -= bytes;
            } else remaining.append(parts[i]);
        }
        return new FocusedPatch(body(shown, header), body(remaining, header));
    }

    private static String body(StringBuilder patch, String header) {
        return patch.length() == header.length() ? "" : patch.toString();
    }

    static String ranges(String patch) {
        List<String> ranges = new ArrayList<>();
        int count = 0;
        for (String part : HUNK.split(patch)) {
            if (!part.startsWith("@@ ")) continue;
            if (++count <= 8) ranges.add(part.substring(0, part.indexOf(" @@", 3) + 3));
        }
        if (ranges.isEmpty()) return "Git change metadata";
        return String.join(", ", ranges) + (count > ranges.size() ? " (+" + (count - ranges.size()) + " hunks)" : "");
    }
}
