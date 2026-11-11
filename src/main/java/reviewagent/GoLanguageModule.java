package reviewagent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class GoLanguageModule implements LanguageModule {
    public String id() { return "go"; }
    public boolean supports(String path) { return path.endsWith(".go"); }
    public Set<Capability> capabilities() { return Set.of(Capability.TEXT_EVIDENCE, Capability.FILE_INDEX, Capability.DECLARATION_LOOKUP, Capability.DEPENDENCY_EVIDENCE); }
    public boolean isText(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT);
        return supports(path.toLowerCase(java.util.Locale.ROOT)) || Set.of("go.mod", "go.sum", "go.work", "go.work.sum").contains(name);
    }
    public Lines locate(String source, String symbol) throws IOException { return GoSource.locate(source, symbol); }
    public Path defaultCache() {
        String explicit = System.getenv("GOMODCACHE");
        if (explicit != null && !explicit.isBlank()) return Path.of(explicit).toAbsolutePath().normalize();
        String gopath = System.getenv("GOPATH");
        Path root = gopath == null || gopath.isBlank() ? Path.of(System.getProperty("user.home"), "go")
                : Path.of(gopath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator), -1)[0]);
        return root.resolve("pkg/mod").toAbsolutePath().normalize();
    }

    public List<String> manifests(Repository repository, String revision, List<String> changed) throws IOException, InterruptedException {
        Set<String> tracked = Set.copyOf(repository.paths(revision));
        var result = new LinkedHashSet<String>();
        var contexts = new LinkedHashSet<>(changed);
        if (tracked.contains("go.mod")) contexts.add("go.mod");
        if (tracked.contains("go.work")) contexts.add("go.work");
        for (String path : contexts) {
            if (!isText(path)) continue;
            String mod = GoWorkspace.nearest(tracked, path, "go.mod");
            String work = GoWorkspace.nearest(tracked, path, "go.work");
            if (work != null) {
                result.add(work); result.add(work + ".sum");
                result.add(GoModuleFile.join(GoModuleFile.directory(work), "vendor/modules.txt"));
                try {
                    var parsed = GoModuleFile.parse(repository.read(revision, work, 256_000), work);
                    for (String use : parsed.uses()) addModule(result, GoModuleFile.join(GoModuleFile.relative(work, use), "go.mod"));
                } catch (IOException unresolved) { /* The raw work file is still supplied for review. */ }
            }
            if (mod != null) addModule(result, mod);
        }
        return result.stream().filter(tracked::contains).toList();
    }

    private static void addModule(Set<String> result, String mod) {
        result.add(mod);
        result.add(GoModuleFile.join(GoModuleFile.directory(mod), "go.sum"));
        result.add(GoModuleFile.join(GoModuleFile.directory(mod), "vendor/modules.txt"));
    }

    public String context(Repository repository, String revision, List<String> changed, int maxBytes) throws IOException, InterruptedException {
        Set<String> paths = Set.copyOf(repository.paths(revision));
        var seen = new LinkedHashSet<String>();
        StringBuilder index = new StringBuilder("Go package file index (metadata only; no type checking or build selection).\n");
        boolean partial = false;
        var configuredRoots = new LinkedHashSet<String>();
        for (String path : changed) {
            if (!isText(path) || supports(path)) continue;
            String manifest = GoWorkspace.nearest(paths, path, "go.mod");
            if (manifest != null) configuredRoots.add(GoModuleFile.directory(manifest));
            String work = GoWorkspace.nearest(paths, path, "go.work");
            if (work != null) try {
                for (String use : GoModuleFile.parse(repository.read(revision, work, 256_000), work).uses()) configuredRoots.add(GoModuleFile.relative(work, use));
            } catch (IOException unavailable) { partial = true; }
        }
        for (String root : configuredRoots) {
            seen.add("module:" + root);
            for (String path : paths.stream().filter(file -> supports(file) && (root.isEmpty() || file.startsWith(root + "/")))
                    .filter(file -> !file.contains("/vendor/") && !file.startsWith("vendor/")).sorted().toList()) {
                String line = "- Module consumer candidate: " + path + (path.endsWith("_test.go") ? " [test variant]" : "") + "\n";
                if (EvidenceText.bytes(index + line) > maxBytes - 300) { partial = true; break; }
                index.append(line);
            }
        }
        for (String path : changed) {
            if (!supports(path) || !paths.contains(path)) continue;
            String directory = GoModuleFile.directory(path);
            if (seen.add(directory)) {
                String label = "Package directory: " + (directory.isEmpty() ? "." : directory) + "\n";
                if (EvidenceText.bytes(index + label) > maxBytes - 300) { partial = true; break; }
                index.append(label);
                for (String peer : paths.stream().filter(file -> supports(file) && GoModuleFile.directory(file).equals(directory)).sorted().toList()) {
                    String line = "- " + peer + (peer.endsWith("_test.go") ? " [test variant]" : "") + "\n";
                    if (EvidenceText.bytes(index + line) > maxBytes - 300) { partial = true; break; }
                    index.append(line);
                }
            }
            try {
                GoSource.Parsed parsed = GoSource.parse(repository.read(revision, path, 256_000));
                String imports = "Changed file " + path + " [package " + parsed.packageName() + "] constraints: " + parsed.constraints()
                        + "\nImports: " + parsed.imports() + "\n";
                if (EvidenceText.bytes(index + imports) > maxBytes - 300) { partial = true; break; }
                index.append(imports);
            } catch (IOException unavailable) {
                String note = "- " + path + ": declaration/import index unavailable; request a bounded file excerpt.\n";
                if (EvidenceText.bytes(index + note) <= maxBytes - 300) index.append(note); else partial = true;
            }
        }
        if (seen.isEmpty()) return "";
        index.append("Index coverage: ").append(partial ? "PARTIAL (byte budget)" : "changed package directories inventoried")
                .append(". Build tags, platform suffixes and test packages remain separate candidates, not proof of deployed behavior.\n");
        String block = EvidenceText.block("Go repository package index at " + revision, "index", index.toString());
        return EvidenceText.bytes(block) <= maxBytes ? block : "";
    }

    public String load(Request request, Environment environment, int maxBytes) throws IOException, InterruptedException {
        GoWorkspace workspace = GoWorkspace.open(environment.repository(), request.revision(), request.context());
        if (request.selector().equals("importers")) return importers(workspace, request.resource(), maxBytes);
        var pkg = new GoDependencies(workspace, environment.moduleCache()).resolve(request.resource(), request.selector().startsWith("file:"));
        return GoPackageEvidence.render(pkg, request.selector(), maxBytes);
    }

    public Session open(Environment environment) {
        var contexts = new java.util.LinkedHashMap<String, GoDependencies>();
        return (request, maxBytes) -> {
            String key = request.revision() + "\n" + request.context();
            if (request.selector().equals("importers")) return new Evidence(importers(
                    GoWorkspace.open(environment.repository(), request.revision(), request.context()), request.resource(), maxBytes), "INDEX_ONLY (import consumers; implementation not supplied)");
            GoDependencies dependencies = contexts.get(key);
            if (dependencies == null) {
                dependencies = new GoDependencies(GoWorkspace.open(environment.repository(), request.revision(), request.context()), environment.moduleCache());
                contexts.put(key, dependencies);
            }
            return GoPackageEvidence.evidence(dependencies.resolve(request.resource(), request.selector().startsWith("file:")), request.selector(), maxBytes);
        };
    }

    private static String importers(GoWorkspace workspace, String importPath, int maxBytes) throws IOException, InterruptedException {
        GoModuleFile.validateImport(importPath);
        Set<String> manifests = workspace.mains.stream().map(GoWorkspace.Main::manifest).collect(java.util.stream.Collectors.toSet());
        StringBuilder output = new StringBuilder("Import consumer index for " + importPath + " (metadata only):\n");
        boolean partial = false;
        int remaining = 8_000_000;
        int matches = 0;
        for (String path : workspace.paths.stream().filter(file -> file.endsWith(".go") && !file.contains("/vendor/") && !file.startsWith("vendor/")).sorted().toList()) {
            if (!manifests.contains(GoWorkspace.nearest(workspace.paths, path, "go.mod"))) continue;
            if (remaining < 256) { partial = true; break; }
            try {
                String source = workspace.repository.read(workspace.revision, path, Math.min(remaining, 1_000_000));
                remaining -= EvidenceText.bytes(source);
                var parsed = GoSource.parse(source);
                for (var imported : parsed.imports()) {
                    if (!(imported.path().equals(importPath) || imported.path().startsWith(importPath + "/"))) continue;
                    String line = "- " + path + " imports " + imported.path() + " alias=" + imported.alias()
                            + (path.endsWith("_test.go") ? " [test variant]" : "") + " constraints=" + parsed.constraints() + "\n";
                    if (EvidenceText.bytes(output + line) > maxBytes - 350) { partial = true; break; }
                    output.append(line); matches++;
                }
            } catch (IOException unavailable) { partial = true; }
            if (EvidenceText.bytes(output.toString()) > maxBytes - 350) { partial = true; break; }
        }
        output.append("Matches: ").append(matches).append(". Coverage: ").append(partial ? "PARTIAL; absence of more matches is not established" : "workspace source files inspected")
                .append(". Read matched code before deciding correctness.\n");
        return GoPackageEvidence.bounded(EvidenceText.block("Go import consumers at " + workspace.revision, "index", output.toString()), maxBytes);
    }

    public String instructions() {
        return """
                Go module: lexical declaration, package navigation and offline dependency helpers are available.
                Check applicable Go concerns: error propagation/wrapping, typed nil interfaces, context cancellation,
                goroutine/channel lifetime, shared state, deferred cleanup, and protobuf presence/zero-value contracts.
                Do not invent failures without a reachable trigger and source evidence. Package indexes may contain
                platform/test variants; source selection needs build context. Compiler type checking, standard-library
                source resolution and cgo expansion are not provided.
                """;
    }

    public String requestInstructions() {
        return """
                Go helpers: REQUEST_FILE|target|path/file.go|symbol:Name|reason
                Name can be a function, type, variable, constant, or Receiver.Method (pointer receivers omit *).
                Symbol extraction is lexical, not type checking; ambiguous names require a qualified method/file.
                REQUEST_LANGUAGE|target|go|path/to/context.go|example.com/module/pkg|index|reason
                The context is a tracked source file or go.mod that selects its nearest pinned module/workspace.
                Resource is the exact imported package path (or a known schema directory for .proto files);
                selector is index, symbol:Name, symbol:Receiver.Method,
                file:name.go, file:name.go:10:90, file:contract.proto, importers, or all. Use importers to find tracked
                workspace consumers of a package/module path after a dependency-only change. The module resolves local packages,
                pinned workspace modules/replacements, consistent tracked vendor, and exact cached dependencies.
                Start with index when the filename is unknown, then request decisive declarations or source ranges.
                A context in an external dependency still uses the original repository context file.
                Offline module metadata is used for version selection; go.sum is not a version lock. Missing cache,
                unsupported/outside-repository replacements, or unresolved selection remain evidence gaps.
                Package indexes are navigation metadata, never proof of behavior. Go source includes build-tag,
                GOOS/GOARCH filename and _test variants; do not assume all indexed files compile together.
                No Go command or dependency download is run. Standard-library source resolution and cgo expansion are not provided.
                """;
    }
}
