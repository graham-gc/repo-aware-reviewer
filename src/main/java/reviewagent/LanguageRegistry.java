package reviewagent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.nio.charset.StandardCharsets;

final class LanguageRegistry {
    private static final class Defaults {
        static final LanguageRegistry INSTANCE = load(LanguageModule.class.getClassLoader());
    }
    private final List<LanguageModule> modules;
    private final Map<String, LanguageModule> byId = new LinkedHashMap<>();

    LanguageRegistry(List<LanguageModule> modules) {
        this.modules = List.copyOf(modules);
        for (LanguageModule module : modules) {
            if (!module.id().matches("[a-z][a-z0-9-]*") || byId.putIfAbsent(module.id(), module) != null)
                throw new IllegalArgumentException("Invalid or repeated language module ID: " + module.id());
        }
    }

    static LanguageRegistry defaults() { return Defaults.INSTANCE; }

    static LanguageRegistry load(ClassLoader loader) {
        var modules = new java.util.ArrayList<>(ServiceLoader.load(LanguageModule.class, loader).stream()
                .map(ServiceLoader.Provider::get).toList());
        Set<String> explicit = modules.stream().map(LanguageModule::id).collect(java.util.stream.Collectors.toSet());
        var profileIds = new java.util.HashSet<String>();
        var resources = new java.util.LinkedHashSet<String>();
        try {
            var catalogs = loader.getResources("META-INF/reviewagent/text-languages");
            while (catalogs.hasMoreElements()) {
                var catalog = catalogs.nextElement();
                List<String> lines;
                try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(catalog.openStream(), StandardCharsets.UTF_8))) { lines = reader.lines().toList(); }
                for (String line : lines) {
                    String path = line.strip();
                    if (path.isEmpty() || path.startsWith("#") || !resources.add(path)) continue;
                    if (path.startsWith("/") || path.contains("..") || path.contains("\\") || !path.endsWith(".properties"))
                        throw new IllegalArgumentException("Invalid language profile resource: " + path);
                    var profiles = loader.getResources(path);
                    if (!profiles.hasMoreElements()) throw new IllegalArgumentException("Missing language profile resource: " + path);
                    while (profiles.hasMoreElements()) {
                        TextLanguageModule module = TextLanguageModule.read(profiles.nextElement());
                        if (!profileIds.add(module.id())) throw new IllegalArgumentException("Repeated text language profile ID: " + module.id());
                        // An advanced provider can replace a text profile without editing the registry or catalog.
                        if (!explicit.contains(module.id())) modules.add(module);
                    }
                }
            }
        } catch (IOException error) { throw new IllegalArgumentException("Cannot load language module profiles", error); }
        return new LanguageRegistry(modules);
    }
    List<LanguageModule> modules() { return modules; }
    boolean recognizesText(String path) { return modules.stream().anyMatch(module -> module.isText(path)); }
    String capabilitySummary() {
        return modules.stream().map(module -> module.id() + "=" + module.capabilities().stream().map(Enum::name).sorted()
                        .collect(java.util.stream.Collectors.joining(",", "[", "]")))
                .collect(java.util.stream.Collectors.joining("; "));
    }

    LanguageModule module(String id) throws IOException {
        LanguageModule module = byId.get(id);
        if (module == null) throw new IOException("Language module is not registered: " + id);
        return module;
    }

    LanguageModule forPath(String path) throws IOException {
        var matching = modules.stream().filter(module -> module.supports(path)).toList();
        int priority = matching.stream().mapToInt(module -> module.sourcePriority(path)).max().orElse(-1);
        var owners = matching.stream().filter(module -> module.sourcePriority(path) == priority).toList();
        if (owners.size() != 1) throw new IOException("Expected one symbol provider for " + path + "; use a line range instead");
        return owners.getFirst();
    }

    LanguageModule legacy(String command) throws IOException {
        var matching = modules.stream().filter(module -> module.legacyRequests().contains(command)).toList();
        if (matching.size() != 1) throw new IOException("Unsupported evidence request: " + command);
        return matching.getFirst();
    }

    record Manifests(List<String> paths, List<String> gaps) { }
    Manifests manifests(LanguageModule.Repository repository, String revision, List<String> changed)
            throws InterruptedException {
        var paths = new LinkedHashSet<String>();
        var gaps = new java.util.ArrayList<String>();
        for (LanguageModule module : modules) {
            try { paths.addAll(module.manifests(repository, revision, changed)); }
            catch (IOException unavailable) { gaps.add("Language manifests " + module.id() + ": UNAVAILABLE. " + EvidenceText.oneLine(unavailable.getMessage())); }
        }
        return new Manifests(List.copyOf(paths), List.copyOf(gaps));
    }

    String instructions(List<String> changedPaths) {
        var selected = new java.util.LinkedHashSet<LanguageModule>();
        for (String path : changedPaths) {
            int priority = modules.stream().filter(module -> module.supports(path)).mapToInt(module -> module.sourcePriority(path)).max().orElse(-1);
            modules.stream().filter(module -> module.reviewAppliesTo(path) && (!module.supports(path) || module.sourcePriority(path) == priority)).forEach(selected::add);
        }
        List<LanguageModule> active = List.copyOf(selected);
        String review = active.stream().map(LanguageModule::instructions).filter(text -> !text.isBlank())
                .collect(java.util.stream.Collectors.joining("\n"));
        if (review.isEmpty()) review = "No language-specific review profile matched the changed paths. Use generic text evidence and explicit file/range requests.\n";
        String helpers = modules.stream().filter(module -> active.contains(module)
                        || module.capabilities().contains(LanguageModule.Capability.DECLARATION_LOOKUP)
                        || module.capabilities().contains(LanguageModule.Capability.DEPENDENCY_EVIDENCE))
                .map(LanguageModule::requestInstructions).filter(text -> !text.isBlank()).collect(java.util.stream.Collectors.joining("\n"));
        String navigation = modules.stream().filter(module -> module instanceof TextLanguageModule).map(LanguageModule::id)
                .collect(java.util.stream.Collectors.joining(", "));
        return review + "\nAVAILABLE EVIDENCE HELPERS\n" + helpers
                + (navigation.isEmpty() ? "" : "\nBasic file-navigation module IDs: " + navigation
                + ". REQUEST_LANGUAGE|target|module-id|tracked/source-or-project-file|repository-relative-directory|index|reason\n"
                + "Use . for the root. These helpers list filenames only and do not resolve imports or dependencies.\n");
    }

    static LanguageModule.Repository repository(GitClient git) {
        return new LanguageModule.Repository() {
            private final Map<String, Map<String, GitClient.TrackedFile>> trees = new LinkedHashMap<>();
            private final Map<String, String> blobs = new LinkedHashMap<>();
            private int cachedBytes;
            private Map<String, GitClient.TrackedFile> tree(String revision) throws IOException, InterruptedException {
                if (!trees.containsKey(revision)) {
                    var entries = new LinkedHashMap<String, GitClient.TrackedFile>();
                    for (var file : git.trackedFiles(revision)) if (file.regular()) entries.put(file.path(), file);
                    trees.put(revision, entries);
                }
                return trees.get(revision);
            }
            public List<String> paths(String revision) throws IOException, InterruptedException {
                return List.copyOf(tree(revision).keySet());
            }
            public String read(String revision, String path, int maxBytes) throws IOException, InterruptedException {
                var file = tree(revision).get(path);
                if (file == null) throw new IOException("No regular tracked file at that revision");
                if (file.size() > maxBytes) throw new IOException("File exceeds the available byte budget");
                String source = blobs.get(file.objectId());
                if (source == null) {
                    source = git.blob(file.objectId());
                    if (source.indexOf('\0') >= 0) throw new IOException("File is not text");
                    int bytes = EvidenceText.bytes(source);
                    if (bytes <= 8_000_000 - cachedBytes) { blobs.put(file.objectId(), source); cachedBytes += bytes; }
                }
                return source;
            }
        };
    }

    static LanguageModule.Environment environment(LanguageModule.Repository repository, ProcessExecutor executor, Path cache) {
        return new LanguageModule.Environment(repository, cache, (command, directory, timeout) -> {
            var result = executor.run(command, directory, null, timeout);
            return new LanguageModule.Result(result.exitCode(), result.stdout(), result.stderr());
        });
    }
}
