package reviewagent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Recognizes a small implementation edit and directly related supporting diffs. */
record LocalChangeGroup(String implementation, Hunk hunk, List<EvidenceSource> companions) {
    private static final Pattern TEST_NAME = Pattern.compile(
            "(?:test_(.+)|(.+?)(?:_test|\\.test|\\.spec|Tests?|Specs?))");
    private static final Pattern REGULAR_EDIT = Pattern.compile("(?m)^index [a-f0-9]+\\.\\.[a-f0-9]+ 100644$");
    private static final Pattern NEW_REGULAR = Pattern.compile("(?m)^new file mode 100644$");
    private static final Pattern HUNK = Pattern.compile("(?m)^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@");
    private static final int EXTRA_SOURCE_LINES = 16;
    private static final List<String> TEST_DIRECTORIES = List.of("test", "tests", "__tests__");
    private static final Set<String> SOURCE_EXTENSIONS = Set.of(".java", ".py", ".go", ".js", ".jsx",
            ".ts", ".tsx", ".cjs", ".mjs", ".cts", ".mts");

    static LocalChangeGroup match(EvidenceBundle evidence, int diffBudget) {
        var diffs = evidence.catalog().sources().stream().filter(source -> source.authoritative()
                && source.role(EvidenceSource.Role.DIFF) && source.endOfFile()
                && source.path().equals(source.baselinePath())).toList();
        if (!diffs.stream().map(EvidenceSource::path).toList().equals(evidence.changedFiles())
                || diffs.stream().mapToLong(diff -> EvidenceText.bytes(diff.text())).sum() > diffBudget
                || diffs.stream().anyMatch(diff -> !smallRegularPatch(diff.text()))) return null;
        var implementation = diffs.stream().filter(diff -> SOURCE_EXTENSIONS.contains(extension(diff.path()))
                && !isTest(diff.path())).toList();
        if (implementation.size() != 1) return null;
        var primary = implementation.getFirst();
        if (!REGULAR_EDIT.matcher(header(primary.text())).find()) return null;
        var range = HUNK.matcher(primary.text());
        if (!range.find()) return null;
        var companions = new ArrayList<>(diffs);
        companions.remove(primary);
        if (companions.stream().anyMatch(diff -> !relatedTest(primary.path(), diff) && !relatedDocument(primary.path(), diff)))
            return null;
        var hunk = new Hunk(Integer.parseInt(range.group(1)), hunkLength(range.group(2)),
                Integer.parseInt(range.group(3)), hunkLength(range.group(4)));
        return new LocalChangeGroup(primary.path(), hunk, List.copyOf(companions));
    }

    private record Hunk(int baseStart, int baseLength, int targetStart, int targetLength) { }

    private static int hunkLength(String count) { return count == null ? 1 : Integer.parseInt(count); }

    List<EvidenceSource> implementationContext(EvidenceSource target, EvidenceSource base, ReviewScope scope) {
        return List.of(window(target, scope, hunk.targetStart(), hunk.targetLength()),
                window(base, scope, hunk.baseStart(), hunk.baseLength()));
    }

    private static EvidenceSource window(EvidenceSource source, ReviewScope scope, int start, int length) {
        var lines = source.text().lines().toList();
        int first = Math.max(1, start - EXTRA_SOURCE_LINES);
        int last = Math.min(lines.size(), start + length - 1 + EXTRA_SOURCE_LINES);
        if (first == 1 && last == lines.size()) return source;
        return EvidenceSources.file(source.revision(), source.path(), first,
                source.title() + " (lines " + first + "–" + last + ")",
                String.join("\n", lines.subList(first - 1, last)), scope, LanguageRegistry.defaults());
    }

    private static boolean smallRegularPatch(String patch) {
        int hunk = patch.indexOf("\n@@ ");
        if (hunk < 0 || patch.indexOf("\n@@ ", hunk + 1) >= 0) return false;
        String header = header(patch);
        return REGULAR_EDIT.matcher(header).find() || NEW_REGULAR.matcher(header).find();
    }

    private static String header(String patch) {
        return patch.substring(0, patch.indexOf("\n@@ "));
    }

    private static boolean isTest(String path) {
        return TEST_NAME.matcher(stem(path)).matches() || java.util.Arrays.stream(path.split("/"))
                .anyMatch(TEST_DIRECTORIES::contains);
    }

    private static boolean relatedTest(String implementation, EvidenceSource support) {
        String test = support.path();
        if (!isTest(test) || !extension(implementation).equals(extension(test))) return false;
        String sourceDirectory = directory(implementation);
        String testDirectory = directory(test);
        if (!(sourceDirectory.equals(testDirectory) || sourceDirectory.equals(productionDirectory(testDirectory))))
            return false;
        var name = TEST_NAME.matcher(stem(test));
        String sourceName = stem(implementation);
        // Exact mirrored names are conventional. A differently named suite needs
        // a direct reference in the changed lines, rather than a reference elsewhere in the file.
        if (name.matches() && (sourceName.equals(name.group(1)) || sourceName.equals(name.group(2)))) return true;
        var reference = Pattern.compile("(?<![\\w$])" + Pattern.quote(sourceName) + "(?![\\w$])");
        return addedLines(support.text()).anyMatch(line -> reference.matcher(line).find());
    }

    private static boolean relatedDocument(String implementation, EvidenceSource support) {
        if (!DocumentationReview.isOrdinaryDocument(support.path())) return false;
        String relative = Path.of(directory(support.path())).relativize(Path.of(implementation)).toString().replace('\\', '/');
        return addedLines(support.text()).anyMatch(line -> line.contains("`" + implementation + "`")
                || line.contains("](" + implementation + ")") || line.contains("](" + relative + ")"));
    }

    private static java.util.stream.Stream<String> addedLines(String patch) {
        return patch.substring(patch.indexOf("\n@@ ")).lines().filter(line -> line.startsWith("+"));
    }

    static List<String> changedLines(String patch) {
        return patch.substring(patch.indexOf("\n@@ ")).lines()
                .filter(line -> line.startsWith("+") || line.startsWith("-"))
                .map(line -> line.substring(1)).toList();
    }

    private static String productionDirectory(String directory) {
        String[] parts = directory.split("/");
        for (int i = 0; i < parts.length; i++) {
            if (!TEST_DIRECTORIES.contains(parts[i])) continue;
            var mirrored = new ArrayList<>(List.of(parts));
            if (i > 0 && parts[i - 1].equals("src") && parts[i].equals("test")) mirrored.set(i, "main");
            else mirrored.remove(i);
            return String.join("/", mirrored);
        }
        return directory;
    }

    private static String directory(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static String stem(String path) {
        String file = path.substring(path.lastIndexOf('/') + 1);
        int dot = file.lastIndexOf('.');
        return dot < 0 ? file : file.substring(0, dot);
    }

    private static String extension(String path) {
        int dot = path.lastIndexOf('.');
        return dot > path.lastIndexOf('/') ? path.substring(dot) : "";
    }
}
