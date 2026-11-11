package reviewagent;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/** Declarative text evidence, project metadata, and file navigation for one language or format. */
public class TextLanguageModule implements LanguageModule {
    private final String id;
    private final String name;
    private final Set<String> extensions;
    private final List<Pattern> sourceNames;
    private final List<ManifestPattern> manifests;
    private final List<ManifestPattern> activationManifests;
    private final String hints;
    private final boolean initialIndex;
    private final boolean implementationEvidence;

    private record ManifestPattern(Pattern pattern) {
        String anchor(String path) {
            var matcher = pattern.matcher(path);
            return matcher.matches() ? matcher.group(1) == null ? "" : matcher.group(1) : null;
        }
    }

    public TextLanguageModule(Properties properties) {
        id = required(properties, "id");
        name = required(properties, "name");
        if (!id.matches("[a-z][a-z0-9-]*")) throw new IllegalArgumentException("Invalid text language ID: " + id);
        extensions = Set.copyOf(values(properties, "source.extensions").stream().map(value -> value.toLowerCase(Locale.ROOT)).toList());
        sourceNames = values(properties, "source.names").stream().map(value -> Pattern.compile("^" + glob(value) + "$", Pattern.CASE_INSENSITIVE)).toList();
        manifests = values(properties, "manifests").stream().map(value -> new ManifestPattern(
                Pattern.compile("^(?:(.*)/)?" + glob(value) + "$", Pattern.CASE_INSENSITIVE))).toList();
        activationManifests = properties.containsKey("activation.manifests") ? values(properties, "activation.manifests").stream()
                .map(value -> new ManifestPattern(Pattern.compile("^(?:(.*)/)?" + glob(value) + "$", Pattern.CASE_INSENSITIVE))).toList() : manifests;
        hints = properties.getProperty("review.hints", "").strip();
        initialIndex = Boolean.parseBoolean(properties.getProperty("index.enabled", "true"));
        implementationEvidence = Boolean.parseBoolean(properties.getProperty("source.implementation", "true"));
        if (extensions.isEmpty() && sourceNames.isEmpty()) throw new IllegalArgumentException("Text language has no source formats: " + id);
    }

    protected TextLanguageModule(Class<?> owner, String resource) {
        this(readResource(owner, resource));
    }

    static TextLanguageModule read(URL resource) throws IOException {
        Properties properties = new Properties();
        try (var reader = new InputStreamReader(resource.openStream(), StandardCharsets.UTF_8)) { properties.load(reader); }
        return new TextLanguageModule(properties);
    }

    private static Properties readResource(Class<?> owner, String resource) {
        URL url = owner.getResource(resource);
        if (url == null) throw new IllegalArgumentException("Missing language profile: " + resource);
        try {
            Properties properties = new Properties();
            try (var reader = new InputStreamReader(url.openStream(), StandardCharsets.UTF_8)) { properties.load(reader); }
            return properties;
        } catch (IOException error) { throw new IllegalArgumentException("Cannot read language profile: " + resource, error); }
    }

    public String id() { return id; }
    public Set<Capability> capabilities() { return Set.of(Capability.TEXT_EVIDENCE, Capability.FILE_INDEX); }
    public boolean supports(String path) {
        String leaf = leaf(path);
        int dot = leaf.lastIndexOf('.');
        return (dot >= 0 && extensions.contains(leaf.substring(dot + 1).toLowerCase(Locale.ROOT)))
                || sourceNames.stream().anyMatch(pattern -> pattern.matcher(leaf).matches());
    }
    public int sourcePriority(String path) {
        return sourceNames.stream().anyMatch(pattern -> pattern.matcher(leaf(path)).matches()) ? 20 : supports(path) ? 10 : -1;
    }
    public boolean implementationEvidence(String path) { return implementationEvidence && supports(path); }
    public boolean isText(String path) { return supports(path) || manifestAnchor(path) != null; }
    public boolean reviewAppliesTo(String path) {
        return supports(path) || activationManifests.stream().anyMatch(pattern -> pattern.anchor(path) != null);
    }

    public List<String> manifests(Repository repository, String revision, List<String> changedPaths) throws IOException, InterruptedException {
        var anchors = new LinkedHashSet<String>();
        anchors.add("");
        for (String changed : changedPaths) if (isText(changed)) {
            String directory = directory(changed);
            while (true) {
                anchors.add(directory);
                if (directory.isEmpty()) break;
                directory = directory(directory);
            }
        }
        return repository.paths(revision).stream().filter(path -> {
            String anchor = manifestAnchor(path);
            return anchor != null && anchors.contains(anchor);
        }).toList();
    }

    public String context(Repository repository, String revision, List<String> changedPaths, int maxBytes) throws IOException, InterruptedException {
        if (!initialIndex) return "";
        var directories = changedPaths.stream().filter(this::supports).map(TextLanguageModule::directory).distinct().toList();
        if (directories.isEmpty()) return "";
        return index(repository, revision, directories, maxBytes);
    }

    public Session open(Environment environment) {
        return (request, maxBytes) -> new Evidence(load(request, environment, maxBytes), "INDEX_ONLY (file navigation; implementation not supplied)");
    }

    public String load(Request request, Environment environment, int maxBytes) throws IOException, InterruptedException {
        if (!request.selector().equals("index")) throw new IOException(name + " has text/file-index support; use REQUEST_FILE with all or a line range for implementation. Declaration and dependency resolution are unavailable.");
        List<String> tracked = environment.repository().paths(request.revision());
        if (!tracked.contains(request.context()) || !isText(request.context())) throw new IOException("Expected a tracked " + name + " source or project metadata file as context");
        String requested = request.resource().equals(".") ? "" : request.resource();
        if (!requested.isEmpty() && !safeDirectory(requested)) throw new IOException("Expected a repository-relative directory, without parent traversal");
        return index(environment.repository(), request.revision(), List.of(requested), maxBytes);
    }

    private String index(Repository repository, String revision, List<String> directories, int maxBytes) throws IOException, InterruptedException {
        List<String> paths = repository.paths(revision);
        StringBuilder body = new StringBuilder(name + " file navigation (metadata only; no declaration parsing or dependency resolution).\n");
        boolean partial = false;
        int included = 0;
        outer: for (String directory : directories) {
            String heading = "Directory: " + (directory.isEmpty() ? "." : directory) + "\n";
            if (EvidenceText.bytes(body + heading) > maxBytes - 400) { partial = true; break; }
            body.append(heading);
            for (String path : paths) {
                if (!directory(path).equals(directory) || !supports(path) || !RepositorySnapshotCollector.allowedTextLocation(path)) continue;
                String line = "- " + path + "\n";
                if (EvidenceText.bytes(body + line) > maxBytes - 400) { partial = true; break outer; }
                body.append(line); included++;
            }
        }
        body.append("Files listed: ").append(included).append(". Coverage: ").append(partial ? "PARTIAL (byte budget)" : "requested directories inventoried")
                .append(". Read file contents before making a finding. No build or tests were run.\n");
        String output = EvidenceText.block(name + " file index at " + revision, "index", body.toString());
        if (EvidenceText.bytes(output) > maxBytes) throw new IOException("File navigation exceeds the evidence byte budget");
        return output;
    }

    public String instructions() {
        String declarations = capabilities().contains(Capability.DECLARATION_LOOKUP) ? "Declaration lookup is supplied by the provider. " : "No symbol locator is available; use all or line ranges. ";
        String dependencies = capabilities().contains(Capability.DEPENDENCY_EVIDENCE) ? "Dependency evidence is supplied by the provider. " : "No package/import resolver or third-party dependency loader is available. ";
        return name + " module [" + id + "] — text evidence and file navigation.\n" + hints + "\n"
                + "Use REQUEST_FILE for implementation. " + declarations + dependencies
                + "Source inspection does not establish compiler/type checking, installed dependencies, or passing tests.\n";
    }

    public String requestInstructions() {
        return "File navigation for " + name + ": REQUEST_LANGUAGE|target|" + id
                + "|tracked/context/file|repository/relative/directory|index|Why these filenames are needed\n"
                + "Use . for the repository root. This lists filenames only; follow with REQUEST_FILE for implementation.\n";
    }

    private String manifestAnchor(String path) {
        for (ManifestPattern manifest : manifests) {
            String anchor = manifest.anchor(path);
            if (anchor != null) return anchor;
        }
        return null;
    }

    private static String leaf(String path) { return path.substring(path.lastIndexOf('/') + 1); }
    private static String directory(String path) { int slash = path.lastIndexOf('/'); return slash < 0 ? "" : path.substring(0, slash); }
    private static boolean safeDirectory(String path) {
        return !path.startsWith("/") && !path.contains("\\") && !path.chars().anyMatch(Character::isISOControl)
                && java.util.Arrays.stream(path.split("/", -1)).noneMatch(part -> part.isBlank() || part.equals(".") || part.equals("..") || part.equals(".git"));
    }
    private static List<String> values(Properties properties, String key) {
        return java.util.Arrays.stream(properties.getProperty(key, "").split(",")).map(String::strip).filter(value -> !value.isEmpty()).toList();
    }
    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key, "").strip();
        if (value.isEmpty()) throw new IllegalArgumentException("Missing text language property: " + key);
        return value;
    }
    private static String glob(String value) {
        StringBuilder regex = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (c == '*') regex.append("[^/]*");
            else if (c == '?') regex.append("[^/]");
            else regex.append(Pattern.quote(String.valueOf(c)));
        }
        return regex.toString();
    }
}
