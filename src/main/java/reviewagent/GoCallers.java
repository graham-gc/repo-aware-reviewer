package reviewagent;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded candidates for direct calls to changed Go package functions. */
record GoCallers(List<String> paths, String directory, String packageName, String importPath, Set<String> functions) {
    static GoCallers find(GitClient git, String revision, List<String> seeds, Map<String, EvidenceSource> targets,
                          Map<String, EvidenceSource> baselines) throws IOException, InterruptedException {
        if (seeds.isEmpty()) return null;
        String packageName = "";
        var functions = new LinkedHashSet<String>();
        for (String path : seeds) {
            var baseline = baselines.get(path);
            if (baseline == null) continue;
            var target = targets.get(path);
            var parsed = GoSource.parse(target.text());
            if (!packageName.isEmpty() && !packageName.equals(parsed.packageName())) return null;
            packageName = parsed.packageName();
            var before = GoSource.parse(baseline.text());
            var oldLines = baseline.text().lines().toList();
            var newLines = target.text().lines().toList();
            for (var declaration : parsed.declarations()) {
                if (!declaration.kind().equals("function")) continue;
                boolean unchanged = before.declarations().stream().anyMatch(old -> old.kind().equals("function")
                        && old.name().equals(declaration.name()) && body(oldLines, old).equals(body(newLines, declaration)));
                if (!unchanged) functions.add(declaration.name());
            }
        }
        if (packageName.isEmpty() || functions.isEmpty() || functions.size() > 16) return null;

        String directory = GoModuleFile.directory(seeds.getFirst());
        var workspace = GoWorkspace.open(LanguageRegistry.repository(git), revision, seeds.getFirst());
        String root = workspace.current.root();
        String relative = root.isEmpty() ? directory : directory.equals(root) ? "" : directory.substring(root.length() + 1);
        String importPath = relative.isEmpty() ? workspace.current.file().module() : workspace.current.file().module() + "/" + relative;
        GoModuleFile.validateImport(importPath);
        var paths = new LinkedHashSet<String>();
        if (functions.stream().anyMatch(GoCallers::exported)) {
            paths.addAll(git.filesMatching(revision, List.of("\"" + importPath + "\"", "\u0060" + importPath + "\u0060"),
                    ":(top,glob)**/*.go"));
        }
        paths.addAll(git.filesContaining(revision, directory.isEmpty() ? "." : directory, List.copyOf(functions)).stream()
                .filter(path -> GoModuleFile.directory(path).equals(directory)).toList());
        List<String> local = paths.stream().filter(path -> path.endsWith(".go") && !path.endsWith("_test.go")
                && !("/" + path).contains("/vendor/") && !("/" + path).contains("/testdata/")
                && workspace.current.manifest().equals(GoWorkspace.nearest(workspace.paths, path, "go.mod"))).toList();
        return new GoCallers(local, directory, packageName, importPath, functions);
    }

    boolean matches(EvidenceSource source) {
        try {
            var parsed = GoSource.parse(source.text());
            var qualifiers = new LinkedHashSet<String>();
            boolean dotImport = false;
            for (var imported : parsed.imports()) {
                if (!imported.path().equals(importPath)) continue;
                switch (imported.alias()) {
                    case "." -> dotImport = true;
                    case "_" -> { }
                    case "" -> qualifiers.add(packageName);
                    default -> qualifiers.add(imported.alias());
                }
            }
            boolean samePackage = GoModuleFile.directory(source.path()).equals(directory) && parsed.packageName().equals(packageName);
            var tokens = GoSource.tokens(source.text());
            for (int i = 0; i + 1 < tokens.size(); i++) {
                String name = tokens.get(i).text();
                if (!functions.contains(name) || !tokens.get(i + 1).text().equals("(")) continue;
                String previous = i == 0 ? "" : tokens.get(i - 1).text();
                if (previous.equals(".")) {
                    if (i >= 2 && exported(name) && qualifiers.contains(tokens.get(i - 2).text())) return true;
                } else if (!previous.equals("func") && (samePackage || dotImport && exported(name))) return true;
            }
        } catch (IOException unavailable) {
            // Navigation hints cannot establish calls in source we could not parse.
        }
        return false;
    }

    private static List<String> body(List<String> lines, GoSource.Declaration declaration) {
        return lines.subList(declaration.first() - 1, declaration.last());
    }

    private static boolean exported(String name) { return Character.isUpperCase(name.codePointAt(0)); }
}
