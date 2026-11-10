package reviewagent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class EvidenceCollector {
    private static final List<String> BASELINE_RULE_FILES = List.of("AGENTS.md", "CLAUDE.md", "REVIEW.md");
    private static final List<String> DEPENDENCY_FILES = List.of(
            "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
            "package.json", "package-lock.json", "pnpm-lock.yaml", "yarn.lock",
            "requirements.txt", "pyproject.toml", "poetry.lock", "Pipfile.lock",
            "*.csproj", "packages.lock.json", "vcpkg.json", "conanfile.txt");

    private final GitClient git;

    EvidenceCollector(GitClient git) {
        this.git = git;
    }

    EvidenceBundle collect(ReviewScope scope, InputDocument requirement, InputDocument knowledge,
                           int maxEvidenceBytes) throws IOException, InterruptedException {
        List<String> changed = git.changedFiles(scope);
        StringBuilder out = new StringBuilder();
        out.append("# Review evidence\n\n");
        out.append("## Immutable scope\n\n")
                .append("- Mode: `").append(scope.mode()).append("`\n")
                .append("- Base commit: `").append(scope.baseCommit()).append("`\n")
                .append("- Target commit: `").append(scope.targetCommit()).append("`\n")
                .append("- Diff: `").append(scope.diffExpression()).append("`\n\n");

        appendDocument(out, "External requirement", requirement);
        appendDocument(out, "Human-maintained knowledge base", knowledge);

        out.append("## Review instructions from trusted base\n\n");
        boolean foundRule = false;
        for (String file : BASELINE_RULE_FILES) {
            if (git.existsAt(scope.baseCommit(), file)) {
                foundRule = true;
                appendCode(out, file + " at base", git.fileAt(scope.baseCommit(), file, 100_000));
            }
        }
        if (!foundRule) {
            out.append("No repository review instruction file was found at the base commit.\n\n");
        }

        out.append("## Changed files\n\n");
        changed.forEach(file -> out.append("- `").append(file).append("`\n"));
        out.append("\n## Git diff\n\n```diff\n")
                .append(git.diff(scope, maxEvidenceBytes / 2)).append("\n```\n\n");

        int perFile = Math.max(20_000, maxEvidenceBytes / Math.max(1, changed.size() + 2));
        out.append("## Target-version contents of changed files\n\n");
        for (String file : changed) {
            appendCode(out, file, git.fileAt(scope.targetCommit(), file, perFile));
        }

        out.append("## Dependency manifests\n\n");
        boolean foundDependency = false;
        for (String file : DEPENDENCY_FILES) {
            if (!file.contains("*") && git.existsAt(scope.targetCommit(), file)) {
                foundDependency = true;
                appendCode(out, file, git.fileAt(scope.targetCommit(), file, 100_000));
            }
        }
        if (!foundDependency) {
            out.append("No supported root dependency manifest was found.\n\n");
        }
        out.append("## Verification status\n\n")
                .append("No build, test, static analyzer, code generator, or repository program was run. ")
                .append("Dependency manifests are evidence only in this prototype; dependency source loading is not yet implemented.\n");

        return new EvidenceBundle(scope, new ArrayList<>(changed),
                GitClient.truncate(out.toString(), maxEvidenceBytes));
    }

    private static void appendDocument(StringBuilder out, String title, InputDocument document) {
        out.append("## ").append(title).append("\n\n");
        if (document == null) {
            out.append("Not provided. Do not claim requirement acceptance or knowledge-base compliance.\n\n");
            return;
        }
        out.append("- Path: `").append(document.path()).append("`\n")
                .append("- SHA-256: `").append(document.sha256()).append("`\n\n")
                .append(document.content()).append("\n\n");
    }

    private static void appendCode(StringBuilder out, String title, String content) {
        out.append("### ").append(title).append("\n\n```text\n")
                .append(content).append("\n```\n\n");
    }
}

