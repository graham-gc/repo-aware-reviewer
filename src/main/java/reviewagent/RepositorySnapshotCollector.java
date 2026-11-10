package reviewagent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class RepositorySnapshotCollector {
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "java", "kt", "kts", "groovy", "scala", "cs", "fs", "fsx",
            "py", "pyi", "js", "jsx", "ts", "tsx", "mjs", "cjs", "vue", "svelte",
            "c", "h", "cc", "cpp", "cxx", "hpp", "hxx", "go", "rs", "rb", "php",
            "sql", "graphql", "proto", "thrift", "avsc",
            "xml", "json", "json5", "yaml", "yml", "toml", "ini", "conf", "properties",
            "md", "mdx", "rst", "txt", "adoc", "csv", "tsv",
            "sh", "bash", "zsh", "fish", "ps1", "bat", "cmd",
            "gradle", "cmake", "dockerfile", "gitignore", "gitattributes");
    private static final Set<String> SPECIAL_NAMES = Set.of(
            "dockerfile", "makefile", "rakefile", "gemfile", "procfile", "jenkinsfile",
            "pom.xml", "package.json", "requirements.txt", "pyproject.toml", "cargo.toml",
            "go.mod", "go.sum", "readme", "license", "agents.md", "claude.md", "review.md",
            ".dockerignore", ".env.example", ".editorconfig", ".npmrc");
    private static final List<String> SKIPPED_SEGMENTS = List.of(
            "/node_modules/", "/vendor/", "/third_party/", "/third-party/", "/generated/",
            "/dist/", "/build/", "/target/", "/.next/", "/coverage/");

    private final GitClient git;

    RepositorySnapshotCollector(GitClient git) {
        this.git = git;
    }

    KnowledgeSnapshot collect(String ref, int chunkBytes, long maxTotalBytes, int maxFileBytes)
            throws IOException, InterruptedException {
        String commit = git.resolveCommit(ref);
        List<KnowledgeFile> inventory = new ArrayList<>();
        List<String> chunks = new ArrayList<>();
        StringBuilder chunk = new StringBuilder();
        long included = 0;
        long repositoryBytes = 0;

        for (String path : git.trackedFiles(commit)) {
            long size = git.fileSizeAt(commit, path);
            repositoryBytes += size;
            if (!isKnowledgeText(path)) {
                inventory.add(new KnowledgeFile(path, size,
                        KnowledgeFile.Status.SKIPPED_BINARY_OR_GENERATED, "binary, generated, vendored, or unsupported text type"));
                continue;
            }
            if (size > maxFileBytes) {
                inventory.add(new KnowledgeFile(path, size, KnowledgeFile.Status.SKIPPED_TOO_LARGE,
                        "file exceeds --max-file-bytes"));
                continue;
            }
            if (included + size > maxTotalBytes) {
                throw new IllegalArgumentException("Selected repository text exceeds --max-total-bytes at " + path
                        + "; raise the limit to avoid a partial knowledge scan");
            }
            String content = git.fileAt(commit, path, maxFileBytes + 1);
            String section = "\n\n===== FILE: " + path + " =====\n" + content;
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
        if (SPECIAL_NAMES.contains(name)) {
            return true;
        }
        int dot = name.lastIndexOf('.');
        return dot >= 0 && TEXT_EXTENSIONS.contains(name.substring(dot + 1));
    }
}

