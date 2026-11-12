package reviewagent;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only, bounded Git snapshot adapter shared by navigation and language providers. */
final class GitRepository implements LanguageModule.Repository {
    private final GitClient git;
    private final Map<String, Map<String, GitClient.TrackedFile>> trees = new LinkedHashMap<>();
    private final Map<String, String> blobs = new LinkedHashMap<>();
    private int cachedBytes;

    GitRepository(GitClient git) { this.git = git; }

    private Map<String, GitClient.TrackedFile> tree(String revision) throws IOException, InterruptedException {
        if (!trees.containsKey(revision)) {
            var entries = new LinkedHashMap<String, GitClient.TrackedFile>();
            for (var file : git.trackedFiles(revision)) if (file.regular()) entries.put(file.path(), file);
            trees.put(revision, entries);
        }
        return trees.get(revision);
    }

    public List<String> paths(String revision) throws IOException, InterruptedException { return List.copyOf(tree(revision).keySet()); }

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
}
