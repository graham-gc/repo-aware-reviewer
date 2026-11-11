package reviewagent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class RepositorySnapshotCollector {
    private static final List<String> SKIPPED_SEGMENTS = List.of(
            "/node_modules/", "/vendor/", "/third_party/", "/third-party/", "/generated/",
            "/dist/", "/build/", "/target/", "/.next/", "/coverage/");

    private final GitClient git;
    private final LanguageRegistry languages;

    RepositorySnapshotCollector(GitClient git) {
        this(git, LanguageRegistry.defaults());
    }

    RepositorySnapshotCollector(GitClient git, LanguageRegistry languages) {
        this.git = git;
        this.languages = languages;
    }

    KnowledgeSnapshot collect(String ref, int chunkBytes, long maxTotalBytes, int maxFileBytes)
            throws IOException, InterruptedException {
        String commit = git.resolveCommit(ref);
        List<KnowledgeFile> inventory = new ArrayList<>();
        List<String> chunks = new ArrayList<>();
        StringBuilder chunk = new StringBuilder();
        long included = 0;
        long repositoryBytes = 0;

        for (GitClient.TrackedFile file : git.trackedFiles(commit)) {
            String path = file.path();
            long size = file.size();
            repositoryBytes += size;
            if (!file.regular()) {
                inventory.add(new KnowledgeFile(path, size, KnowledgeFile.Status.SKIPPED_NON_REGULAR,
                        "symlink or submodule entry"));
                continue;
            }
            if (!allowedTextLocation(path) || !languages.recognizesText(path)) {
                inventory.add(new KnowledgeFile(path, size,
                        KnowledgeFile.Status.SKIPPED_BINARY_OR_GENERATED, "binary, generated, vendored, or unsupported text type"));
                continue;
            }
            if (size > maxFileBytes) {
                inventory.add(new KnowledgeFile(path, size, KnowledgeFile.Status.SKIPPED_TOO_LARGE,
                        "file exceeds --max-file-bytes"));
                continue;
            }
            String content = git.blob(file.objectId());
            if (content.indexOf('\0') >= 0) {
                inventory.add(new KnowledgeFile(path, size, KnowledgeFile.Status.SKIPPED_BINARY_OR_GENERATED,
                        "blob contains binary data"));
                continue;
            }
            if (included + size > maxTotalBytes) {
                throw new IllegalArgumentException("Selected repository text exceeds --max-total-bytes at " + EvidenceText.oneLine(path)
                        + "; raise the limit to avoid a partial knowledge scan");
            }
            String section = "\n\n===== FILE: " + EvidenceText.oneLine(path) + " =====\n" + content;
            int sectionBytes = section.getBytes(StandardCharsets.UTF_8).length;
            if (!chunk.isEmpty() && chunk.toString().getBytes(StandardCharsets.UTF_8).length + sectionBytes > chunkBytes) {
                chunks.add(chunk.toString());
                chunk = new StringBuilder();
            }
            chunk.append(section);
            included += size;
            inventory.add(new KnowledgeFile(path, size, KnowledgeFile.Status.INCLUDED, "selected repository text"));
        }
        if (!chunk.isEmpty()) {
            chunks.add(chunk.toString());
        }
        return new KnowledgeSnapshot(commit, List.copyOf(inventory), List.copyOf(chunks), included, repositoryBytes);
    }

    static boolean isKnowledgeText(String path) {
        if (!allowedTextLocation(path)) return false;
        return LanguageRegistry.defaults().recognizesText(path);
    }

    static boolean allowedTextLocation(String path) {
        String normalized = "/" + path.toLowerCase(Locale.ROOT).replace('\\', '/');
        for (String segment : SKIPPED_SEGMENTS) {
            if (normalized.contains(segment)) {
                return false;
            }
        }
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (name.equals(".env") || (name.startsWith(".env.") && !name.equals(".env.example"))) {
            return false;
        }
        if (name.endsWith(".lock") || name.endsWith(".min.js") || name.endsWith(".map")) {
            return false;
        }
        return true;
    }
}

