package reviewagent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class EvidenceCollector {
    private static final List<String> BASELINE_RULE_FILES = List.of("AGENTS.md", "CLAUDE.md", "REVIEW.md");
    private static final String SCOPED_DOCUMENT = "OMITTED (scoped knowledge document)";
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
                           CliOptions.KnowledgeMode knowledgeMode, int maxEvidenceBytes)
            throws IOException, InterruptedException {
        List<GitClient.Change> changes = git.changes(scope);
        int supplementBudget = maxEvidenceBytes / 5;
        int initialBudget = maxEvidenceBytes - supplementBudget;
        StringBuilder out = new StringBuilder();
        StringBuilder coverage = new StringBuilder("# Review coverage\n\n## Initial evidence by changed file\n\n");
        out.append("# Review evidence\n\n");
        out.append("## Immutable scope\n\n")
                .append("- Mode: `").append(scope.mode()).append("`\n")
                .append("- Base commit: `").append(scope.baseCommit()).append("`\n")
                .append("- Target commit: `").append(scope.targetCommit()).append("`\n")
                .append("- Diff: `").append(scope.diffExpression()).append("`\n\n");

        appendDocument(out, "External requirement", requirement);
        ScopedKnowledge scopedKnowledge;
        Set<String> scopedPaths = Set.of();
        if (knowledgeMode == CliOptions.KnowledgeMode.SCOPED) {
            scopedPaths = git.repositoryPaths(knowledge.path());
            // A rename patch contains both versions. Keep both paths out of raw file evidence.
            for (GitClient.Change change : changes) {
                if (change.previousPath() != null
                        && (scopedPaths.contains(change.path()) || scopedPaths.contains(change.previousPath()))) {
                    scopedPaths.add(change.path());
                    scopedPaths.add(change.previousPath());
                }
            }
            scopedKnowledge = new ScopedKnowledge(knowledge, scopedPaths);
        } else {
            scopedKnowledge = null;
            appendDocument(out, "Human-maintained knowledge base", knowledge);
        }

        out.append("## Review instructions from trusted base\n\n");
        boolean foundRule = false;
        for (String file : BASELINE_RULE_FILES) {
            if (!scopedPaths.contains(file) && git.existsAt(scope.baseCommit(), file)) {
                foundRule = true;
                out.append(EvidenceText.block(file + " at base", "text",
                        git.regularBlobAt(scope.baseCommit(), file, initialBudget)));
            }
        }
        if (!foundRule) {
            out.append("No base-version repository review instructions were supplied.\n\n");
        }

        List<GitClient.Change> reviewChanges = new ArrayList<>();
        StringBuilder scopedChanges = new StringBuilder();
        for (GitClient.Change change : changes) {
            if (scopedPaths.contains(change.path())) {
                scopedChanges.append("- ").append(EvidenceText.oneLine(change.path())).append(" — ")
                        .append(SCOPED_DOCUMENT).append('\n');
            } else {
                reviewChanges.add(change);
            }
        }
        int remaining = initialBudget - EvidenceText.bytes(out.toString() + scopedChanges) - 512;
        if (remaining < 512) {
            throw new IllegalArgumentException("Required documents and baseline rules leave too little room for code evidence; "
                    + "increase --max-evidence-bytes or use smaller input documents");
        }
        String knowledgeCoverage = "";
        if (scopedKnowledge != null) {
            ScopedKnowledge.Result selection = scopedKnowledge.initial(reviewChanges, remaining / 4);
            out.append(selection.markdown());
            knowledgeCoverage = selection.coverage();
            remaining -= EvidenceText.bytes(selection.markdown());
        }
        out.append("## Dependency manifests\n\n");
        int manifestBudget = Math.min(remaining / 8, 48_000);
        List<String> omittedManifests = new ArrayList<>();
        for (String file : DEPENDENCY_FILES) {
            if (!scopedPaths.contains(file) && !file.contains("*") && git.existsAt(scope.targetCommit(), file)) {
                String block;
                try {
                    block = readBlock(scope.targetCommit(), file, manifestBudget);
                } catch (IOException unavailable) {
                    omittedManifests.add(file);
                    continue;
                }
                out.append(block);
                manifestBudget -= EvidenceText.bytes(block);
                remaining -= EvidenceText.bytes(block);
            }
        }
        if (!omittedManifests.isEmpty()) {
            coverage.append("Manifest omitted by size or file type: ")
                    .append(String.join(", ", omittedManifests)).append(".\n\n");
        }
        out.append("## Git diff by changed file\n\n");
        out.append(scopedChanges);
        if (!scopedChanges.isEmpty()) {
            out.append('\n');
        }
        int diffBudget = (int) (remaining * 2L / 3);
        int diffComplete = 0;
        int diffPartial = 0;
        int diffOmitted = changes.size() - reviewChanges.size();
        List<String> diffStatuses = new ArrayList<>();
        for (int i = 0; i < reviewChanges.size(); i++) {
            GitClient.Change change = reviewChanges.get(i);
            String title = EvidenceText.oneLine(change.path());
            int quota = diffBudget / (reviewChanges.size() - i);
            int overhead = EvidenceText.bytes(EvidenceText.block(title, "diff", ""));
            String patch = EvidenceText.linePrefix(change.patch(), quota - overhead);
            if (patch.isEmpty()) {
                diffOmitted++;
                diffStatuses.add("OMITTED (byte budget)");
                continue;
            }
            out.append(EvidenceText.block(title, "diff", patch));
            int bytes = overhead + EvidenceText.bytes(patch);
            diffBudget -= bytes;
            remaining -= bytes;
            if (!patch.equals(change.patch())) {
                diffPartial++;
                diffStatuses.add("PARTIAL (byte budget)");
            } else if (binaryPatch(patch)) {
                diffPartial++;
                diffStatuses.add("METADATA ONLY (binary)");
            } else {
                diffComplete++;
                diffStatuses.add("INCLUDED");
            }
        }

        out.append("## Target-version changed-file context\n\n");
        int index = 0;
        for (GitClient.Change change : changes) {
            String file = change.path();
            if (scopedPaths.contains(file)) {
                coverage.append("- ").append(EvidenceText.oneLine(file)).append(" — diff: ")
                        .append(SCOPED_DOCUMENT).append("; target file: ").append(SCOPED_DOCUMENT).append('\n');
                continue;
            }
            String context;
            if (binaryPatch(change.patch())) {
                context = "OMITTED (binary)";
            } else if (change.deleted()) {
                context = "NOT APPLICABLE (deleted)";
            } else {
                try {
                    String block = readBlock(scope.targetCommit(), file, remaining / (reviewChanges.size() - index));
                    out.append(block);
                    remaining -= EvidenceText.bytes(block);
                    context = "INCLUDED";
                } catch (IOException unavailable) {
                    context = "OMITTED (byte budget or no regular text blob)";
                }
            }
            coverage.append("- ").append(EvidenceText.oneLine(file)).append(" — diff: ")
                    .append(diffStatuses.get(index++)).append("; target file: ").append(context).append('\n');
        }
        String summary = "## Evidence coverage and verification status\n\n"
                + "Changed files: " + changes.size() + ". Complete diff: " + diffComplete
                + ". Partial or metadata only: " + diffPartial + ". Omitted diff: " + diffOmitted + ".\n"
                + "`coverage.md` lists file-level inclusion. Missing evidence does not establish correct behavior.\n"
                + "No build, test, static analyzer, code generator, or repository program was run by the CLI.\n";
        out.append(summary);
        coverage.append('\n').append(summary).append(knowledgeCoverage);
        return new EvidenceBundle(scope, changes.stream().map(GitClient.Change::path).toList(),
                out.toString(), coverage.toString(), supplementBudget, scopedKnowledge);
    }

    private String readBlock(String commit, String file, int quota) throws IOException, InterruptedException {
        String title = EvidenceText.oneLine(file);
        int room = quota - EvidenceText.bytes(EvidenceText.block(title, "text", ""));
        if (room <= 0) {
            throw new IOException("No room in the evidence budget");
        }
        String content = git.regularBlobAt(commit, file, room);
        String block = EvidenceText.block(title, "text", content);
        if (EvidenceText.bytes(block) > quota) {
            throw new IOException("Decoded text exceeds the evidence budget");
        }
        return block;
    }

    private static boolean binaryPatch(String patch) {
        return patch.contains("\nBinary files ") || patch.contains("\nGIT binary patch\n");
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

}

