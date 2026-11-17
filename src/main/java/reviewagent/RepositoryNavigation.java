package reviewagent;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.regex.Pattern;

/** Bounded path hints at pinned revisions. Never reads document/test bodies or assigns business authority. */
final class RepositoryNavigation {
    private static final Set<String> DOCUMENT_DIRS = Set.of("doc", "docs", "documentation");
    private static final Set<String> TEST_DIRS = Set.of("test", "tests", "spec", "specs", "__tests__");
    private static final Set<String> MODULE_CONTAINERS = Set.of("modules", "packages", "services", "apps", "components");
    private static final Pattern DOCUMENT = Pattern.compile(".*\\.(?:md|mdx|markdown|rst|adoc|txt)$");
    private static final Pattern TEST = Pattern.compile("(?:test[_-].*|.*[_-]test\\.[^.]+|.*\\.(?:test|spec)\\.[^.]+)");
    private static final Pattern TEST_SUFFIX = Pattern.compile(".*(?:Test|Tests|Spec|Specs)\\.[^.]+");
    private record Hint(String path, int priority) { }

    static EvidenceSource source(LanguageModule.Repository repository, String revision, List<String> changed,
                                 Set<String> excluded, int budget) throws IOException, InterruptedException {
        if (budget < 650 || changed.isEmpty()) return null;
        var scope = new Scope(changed);
        List<String> paths = repository.paths(revision).stream().filter(RepositoryPathPolicy::allowed)
                .filter(path -> !excluded.contains(path) && relevant(path, scope))
                .map(path -> new Hint(path, scope.priority(path)))
                .sorted(Comparator.comparingInt(Hint::priority).thenComparing(Hint::path)).map(Hint::path).toList();
        StringBuilder body = new StringBuilder("Related documentation and test paths (metadata only).\n"
                + "This is a scoped navigation selection, not a complete repository inventory.\n"
                + "Read contents before relying on a document or test. Paths do not establish business rules or test results.\n");
        int listed = 0;
        for (String path : paths) {
            String line = "- " + path + "\n";
            if (EvidenceText.bytes(body + line) > budget - 300) continue;
            body.append(line);
            listed++;
        }
        body.append("Selected paths listed: ").append(listed).append('/').append(paths.size())
                .append(". Selection coverage: ").append(listed == paths.size() ? "COMPLETE" : "PARTIAL (byte budget)")
                .append(". Other paths can still be requested with repository navigation.\n");
        EvidenceSource source = EvidenceSource.create("Related repository paths at " + revision, "index", body.toString(),
                revision, "", 0, Set.of(EvidenceSource.Role.INDEX), true);
        if (EvidenceText.bytes(source.markdown()) > budget) return null;
        return source;
    }

    private static boolean relevant(String path, Scope scope) {
        String lower = path.toLowerCase(Locale.ROOT);
        String[] parts = lower.split("/");
        String name = parts[parts.length - 1];
        boolean document = DOCUMENT.matcher(name).matches()
                || java.util.Arrays.stream(parts).anyMatch(DOCUMENT_DIRS::contains);
        boolean test = java.util.Arrays.stream(parts).anyMatch(TEST_DIRS::contains)
                || TEST.matcher(name).matches()
                || TEST_SUFFIX.matcher(path.substring(path.lastIndexOf('/') + 1)).matches();
        if (!document && !test) return false;
        return parts.length == 1 || DOCUMENT_DIRS.contains(parts[0])
                || scope.rootChange || scope.sameArea(path) || scope.sameStem(path);
    }

    /** Precompute changed areas and ancestors; do not compare every tracked path with every changed file. */
    private static final class Scope {
        final Set<String> areas = new HashSet<>(), stems = new HashSet<>();
        final Map<String, Integer> ancestors = new HashMap<>();
        boolean rootChange;
        Scope(List<String> changed) {
            for (String path : changed) {
                int first = path.indexOf('/');
                if (first < 0) rootChange = true;
                else {
                    int second = path.indexOf('/', first + 1);
                    int end = second > 0 && MODULE_CONTAINERS.contains(path.substring(0, first).toLowerCase(Locale.ROOT)) ? second : first;
                    areas.add(path.substring(0, end));
                }
                String name = stem(path);
                if (name.length() >= 4) stems.add(name);
                int depth = 0;
                for (int slash = path.indexOf('/'); slash >= 0; slash = path.indexOf('/', slash + 1))
                    ancestors.put(path.substring(0, slash), ++depth);
            }
        }
        boolean sameArea(String path) {
            int first = path.indexOf('/');
            if (first < 0) return false;
            int second = path.indexOf('/', first + 1);
            return areas.contains(path.substring(0, first)) || second > 0 && areas.contains(path.substring(0, second));
        }
        boolean sameStem(String path) {
            String name = stem(path);
            if (stems.contains(name)) return true;
            if (name.startsWith("test") && stems.contains(name.substring(4))) return true;
            for (String suffix : List.of("test", "tests", "spec"))
                if (name.endsWith(suffix) && stems.contains(name.substring(0, name.length() - suffix.length()))) return true;
            return false;
        }
        int priority(String path) {
            if (path.indexOf('/') < 0 && path.toLowerCase(Locale.ROOT).startsWith("readme")) return -1000;
            int proximity = 0;
            for (int slash = path.indexOf('/'); slash >= 0; slash = path.indexOf('/', slash + 1))
                proximity = Math.max(proximity, ancestors.getOrDefault(path.substring(0, slash), 0));
            return -proximity * 10 - (sameStem(path) ? 5 : 0);
        }
    }

    private static String stem(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return (dot < 0 ? name : name.substring(0, dot)).replaceAll("[._-]", "");
    }
}
