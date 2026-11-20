package reviewagent;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class FocusedBehavior {
    private static final String IDENTIFIER = "[a-zA-Z_][a-zA-Z0-9_]*";
    private static final Pattern SIBLING_IMPORT = Pattern.compile("(?m)^from \\.(" + IDENTIFIER + ") import ([^#\\r\\n]+)");
    private static final Pattern BINDING = Pattern.compile("(" + IDENTIFIER + ")(?:\\s+as\\s+(" + IDENTIFIER + "))?");
    private static final Pattern NEW_FILE_MODE = Pattern.compile("(?m)^new file mode 100(?:644|755)$");
    private static final Pattern NEW_FILE_HUNK = Pattern.compile("(?m)^@@ -0,0 \\+1(?:,(\\d+))? @@\\n");

    /** Recognize explicit Python package links between a changed side-effect caller and changed durable state. */
    static Set<String> durableEffect(Set<String> durable, Set<String> effects, Map<String, EvidenceSource> targets,
                                    Map<String, EvidenceSource> baselines) {
        var roots = new LinkedHashSet<String>();
        var reached = new LinkedHashSet<String>();
        for (String caller : effects) {
            var target = targets.get(caller);
            if (target == null || !caller.endsWith(".py")) continue;
            var imports = SIBLING_IMPORT.matcher(target.text());
            while (imports.find()) {
                String dependency = sibling(caller, imports.group(1));
                if (durable.contains(dependency) && targets.containsKey(dependency)
                        && callsBinding(target.text(), imports.group(2))) {
                    roots.add(caller);
                    reached.add(dependency);
                }
            }
        }
        if (roots.isEmpty() || !reached.containsAll(durable)) return Set.of();
        roots.addAll(reached);

        // Include changed callers of the state or effect code and their dependencies at both revisions.
        var chain = new LinkedHashSet<>(roots);
        for (String path : targets.keySet()) {
            if (dependencies(targets.get(path)).stream().anyMatch(roots::contains)
                    || dependencies(baselines.get(path)).stream().anyMatch(roots::contains)) chain.add(path);
        }
        var pending = new ArrayDeque<>(chain);
        while (!pending.isEmpty()) {
            String path = pending.removeFirst();
            addDependencies(targets.get(path), targets.keySet(), chain, pending);
            addDependencies(baselines.get(path), targets.keySet(), chain, pending);
        }
        return chain;
    }

    static Set<String> dependencies(EvidenceSource source) {
        if (source == null || !source.path().endsWith(".py")) return Set.of();
        var paths = new LinkedHashSet<String>();
        var imports = SIBLING_IMPORT.matcher(source.text());
        while (imports.find()) paths.add(sibling(source.path(), imports.group(1)));
        return paths;
    }

    private static void addDependencies(EvidenceSource source, Set<String> candidates, Set<String> chain,
                                        ArrayDeque<String> pending) {
        for (String path : dependencies(source)) {
            if (candidates.contains(path) && chain.add(path)) pending.addLast(path);
        }
    }

    static String importerLiteral(String path) {
        return "from ." + path.substring(path.lastIndexOf('/') + 1, path.length() - 3) + " import ";
    }

    private static boolean callsBinding(String source, String bindings) {
        for (String part : bindings.split(",")) {
            var binding = BINDING.matcher(part.strip());
            if (!binding.matches()) continue;
            String name = binding.group(2) == null ? binding.group(1) : binding.group(2);
            if (Pattern.compile("(?<![\\w.])" + name + "\\h*\\(").matcher(source).find()) return true;
        }
        return false;
    }

    private static String sibling(String path, String module) {
        return path.substring(0, path.lastIndexOf('/') + 1) + module + ".py";
    }

    /** Keep absence-at-base and hunk metadata while showing identical added lines once, as target source. */
    static EvidenceSource compactAddition(EvidenceSource diff, String patch, EvidenceSource target, ReviewScope scope) {
        if (target == null || !diff.path().equals(diff.baselinePath())) return diff;
        var hunk = NEW_FILE_HUNK.matcher(patch);
        if (!hunk.find() || hunk.start() != patch.indexOf("\n@@ ") + 1
                || patch.indexOf("\ndiff --git ") >= 0) return diff;
        String header = patch.substring(0, hunk.end());
        if (!header.contains("\n--- /dev/null\n") || !NEW_FILE_MODE.matcher(header).find()
                || !diff.text().startsWith(header)) return diff;

        var lines = target.text().lines().toList();
        String count = hunk.group(1) == null ? "1" : hunk.group(1);
        if (!count.equals(Integer.toString(lines.size()))) return diff;
        var expected = new StringBuilder();
        for (String line : lines) expected.append('+').append(line).append('\n');
        if (!target.text().endsWith("\n")) expected.append("\\ No newline at end of file\n");
        if (!patch.substring(hunk.end()).contentEquals(expected)) return diff;
        return EvidenceSource.diff(diff.path(), null, header, scope, diff.role(EvidenceSource.Role.IMPLEMENTATION));
    }
}
