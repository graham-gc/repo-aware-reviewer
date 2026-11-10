package reviewagent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class SupplementalEvidence {
    private static final int MAX_REQUESTS = 8;
    private static final int MAX_SOURCE_BYTES = 1_000_000;
    private static final Set<String> BLOCKED_COMPONENTS = Set.of("", ".", "..", ".git", ".claude", ".idea", ".aws", ".ssh");

    record Result(String markdown, String coverage) {
    }

    private final GitClient git;
    private final MavenEvidence maven;

    SupplementalEvidence(GitClient git, MavenEvidence maven) {
        this.git = git;
        this.maven = maven;
    }

    Result collect(EvidenceBundle evidence, String candidates) throws InterruptedException {
        var requests = new LinkedHashMap<List<String>, String[]>();
        int invalid = 0;
        for (String line : candidates.lines().toList()) {
            String trimmed = line.strip();
            if (!trimmed.startsWith("REQUEST_FILE|") && !trimmed.startsWith("REQUEST_MAVEN|")) {
                continue;
            }
            String[] fields = trimmed.split("\\|", 5);
            if (fields.length != 5 || !(fields[1].equals("base") || fields[1].equals("target"))
                    || fields[2].isBlank() || fields[3].isBlank() || fields[4].isBlank() || trimmed.length() > 700) {
                invalid++;
                continue;
            }
            requests.putIfAbsent(List.of(fields[0], fields[1], fields[2], fields[3]), fields);
        }

        ScopedKnowledge.Result knowledge = evidence.scopedKnowledge() == null
                ? new ScopedKnowledge.Result("", "")
                : evidence.scopedKnowledge().supplemental(candidates, evidence.supplementBudget() / 2);
        String heading = "\n## Supplemental evidence requested during discovery\n\n";
        StringBuilder coverage = new StringBuilder(heading);
        StringBuilder prompt = new StringBuilder(heading);
        int remaining = evidence.supplementBudget() - EvidenceText.bytes(heading + knowledge.markdown()) - 220;
        int processed = 0;
        int total = Math.min(requests.size(), MAX_REQUESTS);
        int included = 0;
        for (String[] fields : requests.values()) {
            if (processed == total) {
                break;
            }
            int quota = remaining / (total - processed++);
            String revision = fields[1].equals("base") ? evidence.scope().baseCommit() : evidence.scope().targetCommit();
            String label = fields[1] + " " + EvidenceText.oneLine(fields[2]) + " " + EvidenceText.oneLine(fields[3]);
            String status;
            try {
                if (quota < 256) {
                    throw new IOException("No room in the supplemental evidence budget");
                }
                String block = fields[0].equals("REQUEST_FILE")
                        ? repositoryFile(revision, fields[2], fields[3], quota)
                        : maven.load(revision, fields[2], fields[3], quota);
                if (EvidenceText.bytes(block) > quota) {
                    throw new IOException("Requested material exceeds the supplemental evidence budget");
                }
                prompt.append(block);
                remaining -= EvidenceText.bytes(block);
                included++;
                status = "INCLUDED";
            } catch (IOException unavailable) {
                status = "UNAVAILABLE (" + EvidenceText.oneLine(unavailable.getMessage()) + ")";
                String note = "- " + label + ": " + status + "\n";
                if (EvidenceText.bytes(note) <= quota) {
                    prompt.append(note);
                    remaining -= EvidenceText.bytes(note);
                }
            }
            coverage.append("- ").append(label).append(": ").append(status).append(". Reason: ")
                    .append(EvidenceText.oneLine(fields[4])).append('\n');
        }
        String summary = "\nSupplemental requests included: " + included + "/" + processed + ". Invalid: " + invalid
                + ". Skipped by request limit: " + (requests.size() - processed) + ". See `coverage.md` for details.\n";
        return new Result(prompt.append(summary).append(knowledge.markdown()).toString(),
                coverage.append(summary).append(knowledge.coverage()).toString());
    }

    private String repositoryFile(String revision, String path, String range, int quota)
            throws IOException, InterruptedException {
        if (!eligiblePath(path)) {
            throw new IOException("Path is not eligible tracked source, test, schema, or documentation");
        }
        String content;
        String title = path + " at " + revision;
        if (range.equals("all")) {
            content = git.regularBlobAt(revision, path, quota);
        } else {
            if (!range.matches("[1-9][0-9]{0,6}:[1-9][0-9]{0,6}")) {
                throw new IOException("Expected all or a line range such as 10:90");
            }
            String[] endpoints = range.split(":");
            int from = Integer.parseInt(endpoints[0]);
            int to = Integer.parseInt(endpoints[1]);
            if (to < from || to - from >= 200) {
                throw new IOException("Requested range must contain between 1 and 200 lines");
            }
            List<String> lines = git.regularBlobAt(revision, path, MAX_SOURCE_BYTES).lines()
                    .skip(from - 1L).limit(to - from + 1L).toList();
            if (lines.isEmpty()) {
                throw new IOException("Requested range is beyond the end of the file");
            }
            title += " (lines " + from + "–" + (from + lines.size() - 1) + ")";
            content = String.join("\n", lines);
        }
        return EvidenceText.block(title, "text", content);
    }

    private static boolean eligiblePath(String path) {
        if (path.indexOf('\\') >= 0 || path.chars().anyMatch(Character::isISOControl)) {
            return false;
        }
        String[] parts = path.toLowerCase(Locale.ROOT).split("/", -1);
        for (String part : parts) {
            if (BLOCKED_COMPONENTS.contains(part)) {
                return false;
            }
        }
        String name = parts[parts.length - 1];
        return !name.equals(".npmrc") && !name.equals("credentials.json") && !name.startsWith("secrets.")
                && RepositorySnapshotCollector.isKnowledgeText(path);
    }

    static Path defaultMavenCache() {
        return Path.of(System.getProperty("user.home"), ".m2", "repository");
    }
}
