package reviewagent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

record GoModuleFile(String module, String goVersion, Map<String, String> requires,
                    List<Replacement> replacements, List<String> uses, Set<String> excludes) {
    record Replacement(String module, String version, String target, String targetVersion, String origin) {
        boolean local() { return target.equals(".") || target.equals("..") || target.startsWith("./") || target.startsWith("../")
                || target.startsWith("/") || target.contains("\\") || target.matches("[A-Za-z]:.*"); }
        boolean matches(String path, String selected) { return module.equals(path) && (version.isEmpty() || version.equals(selected)); }
    }

    static GoModuleFile parse(String text, String origin) throws IOException {
        String module = "";
        String go = "1.16";
        var requires = new LinkedHashMap<String, String>();
        var replacements = new ArrayList<Replacement>();
        var uses = new ArrayList<String>();
        var excludes = new LinkedHashSet<String>();
        String block = "";
        boolean comment = false;
        for (String raw : text.lines().toList()) {
            StringBuilder clean = new StringBuilder();
            char quote = 0;
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (comment) {
                    if (c == '*' && i + 1 < raw.length() && raw.charAt(i + 1) == '/') { comment = false; i++; clean.append(' '); }
                } else if (quote != 0) {
                    clean.append(c);
                    if (c == '\\' && quote == '"' && i + 1 < raw.length()) clean.append(raw.charAt(++i));
                    else if (c == quote) quote = 0;
                } else if (c == '"' || c == '`') { quote = c; clean.append(c); }
                else if (c == '/' && i + 1 < raw.length() && raw.charAt(i + 1) == '/') break;
                else if (c == '/' && i + 1 < raw.length() && raw.charAt(i + 1) == '*') { comment = true; i++; clean.append(' '); }
                else clean.append(c);
            }
            if (quote != 0) throw new IOException("Unterminated quoted Go module directive in " + origin);
            List<String> words = words(clean.toString());
            if (words.isEmpty()) continue;
            if (words.equals(List.of(")"))) { if (block.isEmpty()) throw new IOException("Unmatched Go module block"); block = ""; continue; }
            String directive = block.isEmpty() ? words.getFirst() : block;
            List<String> args = block.isEmpty() ? words.subList(1, words.size()) : words;
            if (args.equals(List.of("("))) { if (!block.isEmpty()) throw new IOException("Nested Go module block"); block = directive; continue; }
            switch (directive) {
                case "module" -> { requireSize(args, 1, origin); validateImport(args.getFirst()); module = args.getFirst(); }
                case "go" -> { requireSize(args, 1, origin); go = args.getFirst(); if (!go.matches("[1-9][0-9]{0,3}\\.[0-9]{1,4}(?:\\.[0-9]{1,4})?")) throw new IOException("Invalid Go language version"); }
                case "require", "exclude" -> {
                    requireSize(args, 2, origin); validateImport(args.getFirst()); validateVersion(args.get(1));
                    if (directive.equals("require")) {
                        String previous = requires.putIfAbsent(args.getFirst(), args.get(1));
                        if (previous != null && !previous.equals(args.get(1))) throw new IOException("Conflicting Go requirements in " + origin);
                    } else excludes.add(args.getFirst() + "@" + args.get(1));
                }
                case "replace" -> {
                    int arrow = args.indexOf("=>");
                    if ((arrow != 1 && arrow != 2) || args.size() < arrow + 2 || args.size() > arrow + 3) throw new IOException("Invalid Go replacement in " + origin);
                    String path = args.getFirst();
                    validateImport(path);
                    String version = arrow == 2 ? args.get(1) : "";
                    if (!version.isEmpty()) validateVersion(version);
                    String target = args.get(arrow + 1);
                    String targetVersion = args.size() == arrow + 3 ? args.getLast() : "";
                    Replacement replacement = new Replacement(path, version, target, targetVersion, origin);
                    if (replacement.local()) { if (!targetVersion.isEmpty()) throw new IOException("Local replacements cannot specify versions"); }
                    else { validateImport(target); validateVersion(targetVersion); }
                    replacements.add(replacement);
                }
                case "use" -> { requireSize(args, 1, origin); uses.add(args.getFirst()); }
                case "toolchain", "godebug", "retract", "tool", "ignore" -> { /* Does not select package source. */ }
                default -> throw new IOException("Unsupported Go module directive in " + origin + ": " + directive);
            }
        }
        if (comment || !block.isEmpty()) throw new IOException("Unterminated Go module comment or directive block");
        return new GoModuleFile(module, go, java.util.Collections.unmodifiableMap(requires), List.copyOf(replacements), List.copyOf(uses), Set.copyOf(excludes));
    }

    private static List<String> words(String line) throws IOException {
        var words = new ArrayList<String>();
        for (int i = 0; i < line.length();) {
            if (Character.isWhitespace(line.charAt(i))) { i++; continue; }
            int start = i;
            char c = line.charAt(i++);
            if (c == '(' || c == ')') { words.add(Character.toString(c)); continue; }
            if (c == '"' || c == '`') {
                while (i < line.length() && line.charAt(i) != c) { if (line.charAt(i) == '\\') i++; i++; }
                if (i >= line.length()) throw new IOException("Unterminated Go module string");
                words.add(GoSource.unquote(line.substring(start, ++i)));
            } else {
                while (i < line.length() && !Character.isWhitespace(line.charAt(i)) && line.charAt(i) != '(' && line.charAt(i) != ')') i++;
                words.add(line.substring(start, i));
            }
        }
        return List.copyOf(words);
    }

    static void validateImport(String path) throws IOException {
        if (!path.matches("[A-Za-z0-9_~.-]+(?:/[A-Za-z0-9_~.-]+)*") || path.startsWith("-")
                || java.util.Arrays.stream(path.split("/")).anyMatch(part -> part.equals(".") || part.equals("..") || part.startsWith(".")))
            throw new IOException("Invalid or unsupported Go import/module path");
    }

    static void validateVersion(String version) throws IOException {
        if (!version.matches("v(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)(?:-[0-9A-Za-z.-]+)?(?:\\+incompatible)?"))
            throw new IOException("Expected an exact canonical Go module version");
        String prerelease = prerelease(version);
        if (!prerelease.isEmpty()) for (String part : prerelease.split("\\.", -1)) {
            if (part.isEmpty() || (part.matches("[0-9]+") && part.length() > 1 && part.startsWith("0")))
                throw new IOException("Invalid canonical Go prerelease version");
        }
    }

    static int compareVersions(String first, String second) {
        String[] a = first.substring(1).split("[.+-]", 4), b = second.substring(1).split("[.+-]", 4);
        for (int i = 0; i < 3; i++) {
            int comparison = new java.math.BigInteger(a[i]).compareTo(new java.math.BigInteger(b[i]));
            if (comparison != 0) return comparison;
        }
        String ap = prerelease(first), bp = prerelease(second);
        if (ap.isEmpty() || bp.isEmpty()) return ap.isEmpty() == bp.isEmpty() ? 0 : ap.isEmpty() ? 1 : -1;
        String[] aa = ap.split("\\."), bb = bp.split("\\.");
        for (int i = 0; i < Math.min(aa.length, bb.length); i++) {
            int comparison;
            if (aa[i].matches("[0-9]+") && bb[i].matches("[0-9]+")) comparison = new java.math.BigInteger(aa[i]).compareTo(new java.math.BigInteger(bb[i]));
            else if (aa[i].matches("[0-9]+") != bb[i].matches("[0-9]+")) comparison = aa[i].matches("[0-9]+") ? -1 : 1;
            else comparison = aa[i].compareTo(bb[i]);
            if (comparison != 0) return comparison;
        }
        return Integer.compare(aa.length, bb.length);
    }

    private static String prerelease(String version) {
        int dash = version.indexOf('-');
        return dash < 0 ? "" : version.substring(dash + 1).split("\\+", 2)[0];
    }

    static boolean pruned(String goVersion) {
        String[] fields = goVersion.split("\\.");
        return Integer.parseInt(fields[0]) > 1 || Integer.parseInt(fields[1]) >= 17;
    }

    static String directory(String path) { int slash = path.lastIndexOf('/'); return slash < 0 ? "" : path.substring(0, slash); }
    static String join(String directory, String file) { return directory.isEmpty() ? file : directory + "/" + file; }
    static String relative(String origin, String path) throws IOException {
        if (path.contains("\\") || path.indexOf('\0') >= 0 || Path.of(path).isAbsolute() || path.matches("[A-Za-z]:.*"))
            throw new IOException("Go workspace/replacement path is outside the pinned repository");
        Path resolved = Path.of(directory(origin)).resolve(path).normalize();
        String result = resolved.toString().replace('\\', '/');
        if (result.equals("..") || result.startsWith("../")) throw new IOException("Go workspace/replacement path escapes the pinned repository");
        return result.equals(".") ? "" : result;
    }

    private static void requireSize(List<String> words, int expected, String origin) throws IOException {
        if (words.size() != expected) throw new IOException("Invalid Go module directive in " + origin);
    }
}
