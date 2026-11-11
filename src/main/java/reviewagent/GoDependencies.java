package reviewagent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Offline source resolution from pinned main manifests and cached module metadata. */
final class GoDependencies {
    private final GoWorkspace workspace;
    private final Path cache;
    private final Map<String, String> selected = new LinkedHashMap<>();
    private final Map<String, Location> locations = new LinkedHashMap<>();
    private final Map<String, String> selectionReasons = new LinkedHashMap<>();
    private boolean graphReady;
    private record Node(String module, String version, boolean expand) { }
    private record Location(String module, String version, String localRoot) { }

    GoDependencies(GoWorkspace workspace, Path cache) { this.workspace = workspace; this.cache = cache; }

    GoPackageEvidence.Package resolve(String importPath, boolean fileRequest) throws IOException, InterruptedException {
        GoModuleFile.validateImport(importPath);
        var mainMatches = workspace.mains.stream().filter(main -> prefix(importPath, main.file().module()))
                .sorted(Comparator.comparingInt((GoWorkspace.Main main) -> main.file().module().length()).reversed()).toList();
        var mainPackages = new ArrayList<GoPackageEvidence.Package>();
        for (var main : mainMatches) {
            String directory = GoModuleFile.join(main.root(), suffix(importPath, main.file().module()));
            if (directory.endsWith("/")) directory = directory.substring(0, directory.length() - 1);
            String boundary = GoWorkspace.nearest(workspace.paths, GoModuleFile.join(directory, "context.go"), "go.mod");
            if (!main.manifest().equals(boundary)) continue;
            String packageDirectory = directory;
            boolean provided = workspace.paths.stream().anyMatch(path -> GoModuleFile.directory(path).equals(packageDirectory)
                    && (path.endsWith(".go") || (fileRequest && path.endsWith(".proto"))));
            if (provided) mainPackages.add(new GoPackageEvidence.Package(GoPackageEvidence.repository(workspace.repository, workspace.revision), directory,
                    importPath + " · pinned main module " + main.manifest() + " at " + workspace.revision));
        }
        if (mainPackages.size() > 1) throw new IOException("Ambiguous Go import: multiple pinned workspace modules provide this package");
        if (mainPackages.size() == 1) return mainPackages.getFirst();
        String vendor = workspace.vendorManifest();
        if (vendor != null) return vendored(importPath, vendor);
        if (!graphReady) {
            selected.clear();
            locations.clear();
            selectionReasons.clear();
            graph();
            graphReady = true;
        }
        var matches = selected.keySet().stream().filter(module -> prefix(importPath, module))
                .sorted(Comparator.comparingInt(String::length).reversed()).toList();
        var packages = new ArrayList<GoPackageEvidence.Package>();
        for (String module : matches) {
            String version = selected.get(module);
            Location location = location(module, version);
            String suffix = suffix(importPath, module);
            GoPackageEvidence.Tree tree;
            String directory;
            String provenance = module + "@" + version;
            if (location.localRoot() != null) {
                tree = GoPackageEvidence.repository(workspace.repository, workspace.revision);
                directory = suffix.isEmpty() ? location.localRoot() : GoModuleFile.join(location.localRoot(), suffix);
                String boundary = GoWorkspace.nearest(workspace.paths, GoModuleFile.join(directory, "context.go"), "go.mod");
                if (!GoModuleFile.join(location.localRoot(), "go.mod").equals(boundary))
                    throw new IOException("Replacement import crosses into another nested module");
                provenance += " => pinned replacement " + location.localRoot();
            } else {
                Path moduleRoot = cachedDirectory(location.module(), location.version());
                tree = GoPackageEvidence.cached(moduleRoot, location.module() + "@" + location.version(), workspace.revision);
                directory = suffix;
                if (!location.module().equals(module) || !location.version().equals(version)) provenance += " => " + location.module() + "@" + location.version();
            }
            boolean exists = fileRequest || tree.files(directory).stream().anyMatch(path -> path.endsWith(".go"));
            if (exists) packages.add(new GoPackageEvidence.Package(tree, directory,
                    importPath + " · offline MVS selection " + provenance + " · " + workspace.current.manifest()
                            + (workspace.workPath == null ? "" : " · " + workspace.workPath) + " at " + workspace.revision
                            + " · selected minimum from " + selectionReasons.get(module + "@" + version)));
        }
        if (packages.isEmpty()) throw new IOException("No selected cached module provides this package; missing dependencies are not downloaded");
        if (packages.size() > 1) throw new IOException("Ambiguous Go import: multiple selected modules provide this package");
        return packages.getFirst();
    }

    private void graph() throws IOException, InterruptedException {
        Set<String> rootPaths = new LinkedHashSet<>();
        Set<String> mainPaths = new LinkedHashSet<>();
        boolean pruned = workspace.mains.stream().allMatch(main -> GoModuleFile.pruned(main.file().goVersion()));
        var queue = new ArrayDeque<Node>();
        for (var main : workspace.mains) {
            mainPaths.add(main.file().module());
            rootPaths.addAll(main.file().requires().keySet());
            main.file().requires().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(requirement -> {
                        queue.add(new Node(requirement.getKey(), requirement.getValue(), !pruned));
                        selectionReasons.putIfAbsent(requirement.getKey() + "@" + requirement.getValue(), main.manifest() + " at " + workspace.revision);
                    });
        }
        var seen = new LinkedHashSet<Node>();
        int metadataBytes = 0;
        while (!queue.isEmpty()) {
            Node node = queue.removeFirst();
            if (mainPaths.contains(node.module())) continue;
            if (workspace.excluded(node.module(), node.version())) throw new IOException("Excluded Go version requires an explicit updated module graph: " + node.module() + "@" + node.version());
            String previous = selected.get(node.module());
            if (previous == null || GoModuleFile.compareVersions(node.version(), previous) > 0) {
                selected.put(node.module(), node.version());
                if (rootPaths.contains(node.module()) && previous != null) queue.add(new Node(node.module(), node.version(), !pruned));
            }
            if (!seen.add(node)) continue;
            if (seen.size() > 2048) throw new IOException("Offline Go module metadata graph exceeds the inspection limit");
            if (!node.expand() && !rootPaths.contains(node.module())) continue;
            Location location = location(node.module(), node.version());
            String metadata;
            String origin;
            if (location.localRoot() != null) {
                origin = GoModuleFile.join(location.localRoot(), "go.mod");
                metadata = workspace.repository.read(workspace.revision, origin, 256_000);
            } else {
                origin = location.module() + "@" + location.version() + "/go.mod";
                Path file = cacheFile(location.module(), location.version());
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 256_000)
                    throw new IOException("Missing cached Go module metadata; version selection remains unresolved: " + origin);
                metadata = readCache(file, 256_000);
            }
            metadataBytes += EvidenceText.bytes(metadata);
            if (metadataBytes > 4_000_000) throw new IOException("Offline Go module graph metadata byte budget exhausted");
            GoModuleFile parsed = GoModuleFile.parse(metadata, origin);
            if (!parsed.module().equals(node.module()) && !parsed.module().equals(location.module()))
                throw new IOException("Cached/replacement go.mod declares a different module identity");
            boolean expand = node.expand() || !GoModuleFile.pruned(parsed.goVersion());
            String originProof = origin + " SHA-256 " + GoPackageEvidence.sha256(metadata);
            parsed.requires().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(requirement -> {
                queue.add(new Node(requirement.getKey(), requirement.getValue(), expand));
                selectionReasons.putIfAbsent(requirement.getKey() + "@" + requirement.getValue(), originProof);
            });
        }
    }

    private Location location(String module, String version) throws IOException {
        String key = module + "@" + version;
        if (locations.containsKey(key)) return locations.get(key);
        var replacement = workspace.replacement(module, version);
        Location result = replacement == null ? new Location(module, version, null) : replacement.local()
                ? new Location(module, version, GoModuleFile.relative(replacement.origin(), replacement.target()))
                : new Location(replacement.target(), replacement.targetVersion(), null);
        locations.put(key, result);
        return result;
    }

    private GoPackageEvidence.Package vendored(String importPath, String manifest) throws IOException, InterruptedException {
        String text = workspace.repository.read(workspace.revision, manifest, 1_000_000);
        var versions = new LinkedHashMap<String, String>();
        var explicit = new LinkedHashSet<String>();
        var replacements = new LinkedHashMap<String, String>();
        var packages = new LinkedHashMap<String, String>();
        String module = "";
        for (String raw : text.lines().toList()) {
            String line = raw.strip();
            if (line.startsWith("# ")) {
                String[] fields = line.substring(2).split("\\s+");
                if (fields.length < 2) throw new IOException("Invalid pinned vendor manifest");
                module = fields[0];
                GoModuleFile.validateImport(module);
                if (!fields[1].equals("=>")) { GoModuleFile.validateVersion(fields[1]); versions.put(module, fields[1]); }
                int arrow = java.util.Arrays.asList(fields).indexOf("=>");
                if (arrow >= 0) replacements.put(module, String.join(" ", java.util.Arrays.copyOfRange(fields, arrow + 1, fields.length)));
            } else if (line.startsWith("## ")) {
                if (line.substring(3).split(";", 2)[0].strip().equals("explicit")) explicit.add(module);
            } else if (!line.isEmpty() && !line.startsWith("#")) packages.put(line, module);
        }
        var required = new LinkedHashMap<String, String>();
        for (var main : workspace.mains) for (var requirement : main.file().requires().entrySet()) {
            required.merge(requirement.getKey(), requirement.getValue(), (first, second) ->
                    GoModuleFile.compareVersions(first, second) >= 0 ? first : second);
        }
        for (var requirement : required.entrySet()) {
            boolean isMain = workspace.mains.stream().anyMatch(other -> other.file().module().equals(requirement.getKey()));
            if (!isMain && (!requirement.getValue().equals(versions.get(requirement.getKey())) || !explicit.contains(requirement.getKey())))
                throw new IOException("Pinned vendor/modules.txt is inconsistent with main module requirements");
        }
        for (String modulePath : explicit) if (!required.containsKey(modulePath))
            throw new IOException("Pinned vendor manifest has stale explicit module entries");
        for (var version : versions.entrySet()) {
            if (workspace.excluded(version.getKey(), version.getValue())) throw new IOException("Vendored Go version is excluded by the selected modules");
            var replacement = workspace.replacement(version.getKey(), version.getValue());
            String expected = replacement == null ? null : replacement.target() + (replacement.targetVersion().isEmpty() ? "" : " " + replacement.targetVersion());
            if (!java.util.Objects.equals(expected, replacements.get(version.getKey())))
                throw new IOException("Pinned vendor replacement differs from the selected workspace/module replacement");
        }
        String owner = packages.get(importPath);
        if (owner == null || !versions.containsKey(owner)) throw new IOException("Package is absent from the pinned vendor manifest");
        String root = GoModuleFile.directory(manifest);
        return new GoPackageEvidence.Package(GoPackageEvidence.repository(workspace.repository, workspace.revision),
                GoModuleFile.join(root, importPath), importPath + " · pinned vendor " + owner + "@" + versions.get(owner)
                        + " · " + manifest + " at " + workspace.revision);
    }

    private Path cachedDirectory(String module, String version) throws IOException {
        if (cache == null || !Files.isDirectory(cache)) throw new IOException("Go module cache is unavailable");
        Path root = cache.toRealPath();
        Path directory = root.resolve(escape(module) + "@" + escape(version));
        if (!Files.isDirectory(directory) || Files.isSymbolicLink(directory) || !directory.toRealPath().startsWith(root))
            throw new IOException("Exact selected Go module source is not cached");
        return directory;
    }

    private Path cacheFile(String module, String version) throws IOException {
        if (cache == null || !Files.isDirectory(cache)) throw new IOException("Go module cache is unavailable");
        Path root = cache.toRealPath();
        Path downloaded = root.resolve("cache/download/" + escape(module) + "/@v/" + escape(version) + ".mod");
        if (Files.isRegularFile(downloaded, LinkOption.NOFOLLOW_LINKS)) return downloaded;
        return cachedDirectory(module, version).resolve("go.mod");
    }

    private String readCache(Path file, int limit) throws IOException {
        Path root = cache.toRealPath();
        if (Files.isSymbolicLink(file) || !file.toRealPath().startsWith(root)) throw new IOException("Go metadata resolves outside the module cache");
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException("Go module metadata exceeds the byte budget");
            String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            if (text.indexOf('\0') >= 0 || text.indexOf('\ufffd') >= 0) throw new IOException("Invalid Go module metadata text");
            return text;
        }
    }

    static String escape(String value) {
        StringBuilder escaped = new StringBuilder();
        for (char c : value.toCharArray()) { if (c >= 'A' && c <= 'Z') escaped.append('!').append(Character.toLowerCase(c)); else escaped.append(c); }
        return escaped.toString();
    }
    private static boolean prefix(String path, String module) { return path.equals(module) || path.startsWith(module + "/"); }
    private static String suffix(String path, String module) { return path.equals(module) ? "" : path.substring(module.length() + 1); }
}
