package reviewagent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable evidence identity and provenance, independent of Markdown headings. */
record EvidenceSource(String id, String title, String format, String text, String revision, String path,
                      int firstLine, Set<Role> roles, boolean authoritative, String baselineRevision, String baselinePath) {
    enum Role { IMPLEMENTATION, BASELINE, TARGET, CONTRACT, INDEX, DIFF }
    enum Side { FILE, ADDED, REMOVED, CONTEXT }
    record Location(String sourceId, String path, String revision, int firstLine, int lastLine, Side side) {
        String reference() { return path + ":" + firstLine + (lastLine == firstLine ? "" : "–" + lastLine) + " at " + revision; }
    }
    record Excerpt(String text, List<Location> locations) { }

    EvidenceSource { roles = Set.copyOf(roles); }

    static EvidenceSource create(String title, String format, String text, String revision, String path, int firstLine,
                                 Set<Role> roles, boolean authoritative) {
        return create(title, format, text, revision, path, firstLine, roles, authoritative, "", "");
    }

    private static EvidenceSource create(String title, String format, String text, String revision, String path, int firstLine,
                                         Set<Role> roles, boolean authoritative, String baselineRevision, String baselinePath) {
        String key = revision + "\0" + path + "\0" + baselineRevision + "\0" + baselinePath + "\0" + firstLine + "\0" + title
                + "\0" + format + "\0" + roles.stream().sorted().toList() + "\0" + text;
        try {
            String id = "EV-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
            return new EvidenceSource(id, title, format, text, revision, path, firstLine, roles, authoritative, baselineRevision, baselinePath);
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    static EvidenceSource diff(String path, String previousPath, String patch, ReviewScope scope, boolean implementation) {
        var roles = java.util.EnumSet.of(Role.DIFF, Role.BASELINE, Role.TARGET);
        if (implementation) roles.add(Role.IMPLEMENTATION);
        return create(path, "diff", patch, scope.targetCommit(), path, 1, roles, true,
                scope.baseCommit(), previousPath == null ? path : previousPath);
    }

    String markdown() { return EvidenceText.block(title, format, text); }
    boolean role(Role role) { return roles.contains(role); }

    Excerpt excerpt(String selector) throws IOException {
        if (!selector.matches("[1-9][0-9]{0,6}:[1-9][0-9]{0,6}")) {
            throw new IOException("Expected <lines>first:last</lines> with positive block-relative line numbers");
        }
        String[] endpoints = selector.split(":");
        int from = Integer.parseInt(endpoints[0]), to = Integer.parseInt(endpoints[1]);
        List<String> lines = text.lines().toList();
        if (to < from || to - from >= 200 || to > lines.size()) {
            throw new IOException("Citation range must contain 1–200 lines entirely inside the included block ("
                    + lines.size() + " lines); these are block-relative, not repository file line numbers");
        }
        List<Location> locations = path.isEmpty() || revision.isEmpty() ? List.of()
                : role(Role.DIFF) ? diffLocations(lines, from, to)
                : firstLine < 1 ? List.of() : List.of(new Location(id, path, revision,
                    firstLine + from - 1, firstLine + to - 1, Side.FILE));
        return new Excerpt(String.join("\n", lines.subList(from - 1, to)), locations);
    }

    List<Location> locations(String quotation) {
        if (path.isEmpty() || revision.isEmpty() || firstLine < 1 && !role(Role.DIFF)) return List.of();
        StringBuilder normalized = new StringBuilder();
        var lines = new ArrayList<Integer>();
        int line = 1;
        boolean space = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                if (!space && !normalized.isEmpty()) { normalized.append(' '); lines.add(line); }
                space = true;
            } else { normalized.append(c); lines.add(line); space = false; }
            if (c == '\n') line++;
        }
        String wanted = normalize(quotation);
        if (wanted.isEmpty()) return List.of();
        List<String> rawLines = text.lines().toList();
        var found = new ArrayList<Location>();
        int offset = 0;
        while ((offset = normalized.indexOf(wanted, offset)) >= 0 && found.size() < 32) {
            int from = lines.get(offset), to = lines.get(offset + wanted.length() - 1);
            if (role(Role.DIFF)) found.addAll(diffLocations(rawLines, from, to));
            else found.add(new Location(id, path, revision, firstLine + from - 1, firstLine + to - 1, Side.FILE));
            offset++;
        }
        return found.stream().distinct().toList();
    }

    private List<Location> diffLocations(List<String> lines, int from, int to) {
        Pattern hunk = Pattern.compile("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*");
        int base = -1, target = -1;
        var mapped = new java.util.LinkedHashMap<Side, int[]>();
        for (int i = 0; i < Math.min(to, lines.size()); i++) {
            String text = lines.get(i);
            var header = hunk.matcher(text);
            if (header.matches()) { base = Integer.parseInt(header.group(1)); target = Integer.parseInt(header.group(2)); continue; }
            if (base < 0 || target < 0 || text.isEmpty()) continue;
            char marker = text.charAt(0);
            Side side = marker == '+' ? Side.ADDED : marker == '-' ? Side.REMOVED : marker == ' ' ? Side.CONTEXT : null;
            if (side == null) continue;
            int original = side == Side.REMOVED ? base : target;
            if (i + 1 >= from && original > 0) {
                int[] span = mapped.computeIfAbsent(side, ignored -> new int[]{original, original});
                span[1] = original;
            }
            if (marker != '+') base++;
            if (marker != '-') target++;
        }
        return mapped.entrySet().stream().map(entry -> new Location(id,
                entry.getKey() == Side.REMOVED && !baselinePath.isEmpty() ? baselinePath : path,
                entry.getKey() == Side.REMOVED && !baselineRevision.isEmpty() ? baselineRevision : revision,
                entry.getValue()[0], entry.getValue()[1], entry.getKey())).toList();
    }

    static String normalize(String text) { return text.replaceAll("(?U)\\s+", " ").strip(); }
}
