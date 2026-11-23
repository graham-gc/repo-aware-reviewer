package reviewagent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Keep original hunk coordinates when carrying unshown changes into a bounded second scope. */
record FocusedPatch(String shown, String remaining) {
    private static final Pattern HUNK = Pattern.compile("(?m)(?=^@@ )");
    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@[^\\n]*\\n");

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

    /** Split at hunk boundaries first. For an oversized hunk retain both sides' source coordinates. */
    static List<String> partition(String patch, int limit) {
        var result = new ArrayList<String>();
        for (String filePatch : patch.split("(?m)(?=^diff --git )")) {
            if (EvidenceText.bytes(filePatch) <= limit) {
                result.add(filePatch);
                continue;
            }
            String[] pieces = HUNK.split(filePatch);
            if (pieces.length < 2) return List.of();
            String header = pieces[0];
            var batch = new StringBuilder(header);
            int headerBytes = EvidenceText.bytes(header);
            int bytes = headerBytes;
            for (int i = 1; i < pieces.length; i++) {
                String hunk = pieces[i];
                int size = EvidenceText.bytes(hunk);
                if (bytes + size > limit && batch.length() > header.length()) {
                    result.add(batch.toString());
                    batch = new StringBuilder(header);
                    bytes = headerBytes;
                }
                if (headerBytes + size > limit) {
                    var split = splitHunk(hunk, limit - headerBytes);
                    if (split.isEmpty()) return List.of();
                    split.forEach(part -> result.add(header + part));
                } else {
                    batch.append(hunk);
                    bytes += size;
                }
            }
            if (batch.length() > header.length()) result.add(batch.toString());
        }
        return List.copyOf(result);
    }

    private static List<String> splitHunk(String hunk, int limit) {
        var match = HUNK_HEADER.matcher(hunk);
        if (!match.find()) return List.of();
        int base = Integer.parseInt(match.group(1)) + ("0".equals(match.group(2)) ? 1 : 0);
        int target = Integer.parseInt(match.group(3)) + ("0".equals(match.group(4)) ? 1 : 0);
        // Reserve room for coordinates, including a count on each side.
        int bodyLimit = limit - 64;
        var result = new ArrayList<String>();
        var body = new StringBuilder();
        int bytes = 0, oldCount = 0, newCount = 0;
        String[] lines = hunk.substring(match.end()).split("(?<=\n)");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            char type = line.isEmpty() ? '\0' : line.charAt(0);
            if (type != '+' && type != '-' && type != ' ') return List.of();
            if (i + 1 < lines.length && lines[i + 1].startsWith("\\ ")) line += lines[++i];
            int size = EvidenceText.bytes(line);
            if (size > bodyLimit) return List.of();
            if (bytes + size > bodyLimit) {
                result.add(hunkPart(base, target, oldCount, newCount, body));
                base += oldCount;
                target += newCount;
                body.setLength(0);
                bytes = oldCount = newCount = 0;
            }
            body.append(line);
            bytes += size;
            if (type != '+') oldCount++;
            if (type != '-') newCount++;
        }
        if (!body.isEmpty()) result.add(hunkPart(base, target, oldCount, newCount, body));
        return result;
    }

    private static String hunkPart(int base, int target, int oldCount, int newCount, StringBuilder body) {
        return "@@ -" + (oldCount == 0 ? base - 1 : base) + "," + oldCount
                + " +" + (newCount == 0 ? target - 1 : target) + "," + newCount + " @@\n" + body;
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

    /** Locate complete hunks for a target-file span without rewriting the supplied patch. */
    static String atTarget(String patch, int first, int last) {
        var matched = new StringBuilder();
        for (String hunk : HUNK.split(patch)) {
            var header = HUNK_HEADER.matcher(hunk);
            if (!header.find()) continue;
            int start = Integer.parseInt(header.group(3));
            int count = header.group(4) == null ? 1 : Integer.parseInt(header.group(4));
            if (start <= last && start + Math.max(1, count) - 1 >= first) matched.append(hunk);
        }
        return matched.toString();
    }
}
