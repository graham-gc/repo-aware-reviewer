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

/** Bounded path hints at pinned revisions. */
final class RepositoryNavigation {
    private static final Set<String> DOCUMENT_DIRS = Set.of("doc", "docs", "documentation");
    private static final Set<String> TEST_DIRS = Set.of("test", "tests", "spec", "specs", "__tests__");
    private static final Set<String> MODULE_CONTAINERS = Set.of("modules", "packages", "services", "apps", "components");
    private static final Pattern DOCUMENT = Pattern.compile(".*\\.(?:md|mdx|markdown|rst|adoc|txt)$");
    private static final Pattern TEST = Pattern.compile("(?:test[_-].*|.*[_-]test\\.[^.]+|.*\\.(?:test|spec)\\.[^.]+)");
    private static final Pattern TEST_SUFFIX = Pattern.compile(".*(?:Test|Tests|Spec|Specs)\\.[^.]+");
    private enum Kind { ROOT_DOCUMENTATION, IMPLEMENTATION, RELATED }
    private record Hint(String path, Kind kind, int priority) { }

    static EvidenceSource source(LanguageModule.Repository repository, String revision, List<String> changed,
                                 Set<String> excluded, int budget) throws IOException, InterruptedException {
        if (budget < 650 || changed.isEmpty()) return null;
        var scope = new Scope(changed);
        var languages = LanguageRegistry.defaults();
        List<String> paths = repository.paths(revision).stream().filter(RepositoryPathPolicy::allowed)
                .filter(path -> !excluded.contains(path))
                .map(path -> hint(path, scope, languages)).filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(Hint::kind).thenComparingInt(Hint::priority).thenComparing(Hint::path))
                .map(Hint::path).toList();
        StringBuilder body = new StringBuilder("Related implementation, documentation and test paths (metadata only).\n"
                + "This is a scoped navigation selection, not a complete repository inventory.\n"
                + "Use the full paths exactly as listed. Read contents before drawing a conclusion.\n");
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

    private static Hint hint(String path, Scope scope, LanguageRegistry languages) {
        String lower = path.toLowerCase(Locale.ROOT);
        String[] parts = lower.split("/");
        String name = parts[parts.length - 1];
        boolean document = DOCUMENT.matcher(name).matches()
                || java.util.Arrays.stream(parts).anyMatch(DOCUMENT_DIRS::contains);
        boolean test = java.util.Arrays.stream(parts).anyMatch(TEST_DIRS::contains)
                || TEST.matcher(name).matches()
                || TEST_SUFFIX.matcher(path.substring(path.lastIndexOf('/') + 1)).matches();
        boolean sameArea = scope.sameArea(path);
        if (document || test) {
            if (parts.length == 1 || DOCUMENT_DIRS.contains(parts[0]) || scope.rootChange || sameArea || scope.sameStem(path)) {
                Kind kind = parts.length == 1 && name.startsWith("readme") ? Kind.ROOT_DOCUMENTATION : Kind.RELATED;
                return new Hint(path, kind, scope.priority(path));
            }
        } else if ((sameArea || parts.length == 1 && scope.rootChange) && !scope.changed.contains(path)
                && (scope.sameStem(path) || scope.features.contains(feature(path)))
                && languages.recognizesText(path) && EvidenceCatalog.implementation(languages, path)) {
            return new Hint(path, Kind.IMPLEMENTATION, scope.priority(path));
        }
        return null;
    }

    /** Precompute changed areas and ancestors; do not compare every tracked path with every changed file. */
    private static final class Scope {
        final Set<String> areas = new HashSet<>(), stems = new HashSet<>(), features = new HashSet<>();
        final Set<String> changed;
        final Map<String, Integer> ancestors = new HashMap<>();
        boolean rootChange;
        Scope(List<String> changed) {
            this.changed = Set.copyOf(changed);
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
                String feature = feature(path);
                if (!feature.isEmpty()) features.add(feature);
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
            int proximity = 0;
            for (int slash = path.indexOf('/'); slash >= 0; slash = path.indexOf('/', slash + 1))
                proximity = Math.max(proximity, ancestors.getOrDefault(path.substring(0, slash), 0));
            return -proximity * 10 - (sameStem(path) ? 5 : 0);
        }
    }

    private static String feature(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        if (dot >= 0) name = name.substring(0, dot);
        var words = name.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        int first = words.length > 0 && words[0].equals("test") ? 1 : 0;
        if (words.length - first < 2) return "";
        return featureWord(words[first]) + ":" + featureWord(words[first + 1]);
    }

    private static String featureWord(String word) {
        return word.length() > 3 && word.endsWith("s") ? word.substring(0, word.length() - 1) : word;
    }

    private static String stem(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return (dot < 0 ? name : name.substring(0, dot)).replaceAll("[._-]", "");
    }
}
