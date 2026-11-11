package reviewagent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class SupplementalEvidence {
    private static final int MAX_SOURCE_BYTES = 1_000_000;
    private static final Set<String> BLOCKED_COMPONENTS = Set.of("", ".", "..", ".git", ".claude", ".idea", ".aws", ".ssh");

    record Result(String markdown, String coverage, boolean hasMaterial) {
    }

    private record Excerpt(String markdown, String status) {
    }

    private final GitClient git;
    private final MavenEvidence maven;

    SupplementalEvidence(GitClient git, MavenEvidence maven) {
        this.git = git;
        this.maven = maven;
    }

    Result collect(EvidenceBundle evidence, String candidates, int maxRequests, int maxKnowledgeQueries)
            throws InterruptedException {
        var requests = new LinkedHashMap<List<String>, String[]>();
        var invalidLines = new ArrayList<Integer>();
        List<EvidenceRequest> parsed = EvidenceRequest.parse(candidates);
        for (EvidenceRequest request : parsed) {
            String line = request.text();
            if (!line.startsWith("REQUEST_FILE|") && !line.startsWith("REQUEST_MAVEN|")) {
                continue;
            }
            String[] fields = line.split("\\|", 5);
            if (fields.length != 5 || !(fields[1].equals("base") || fields[1].equals("target"))
                    || fields[2].isBlank() || fields[3].isBlank() || fields[4].isBlank() || line.length() > 700) {
                invalidLines.add(request.line());
                continue;
            }
            requests.putIfAbsent(List.of(fields[0], fields[1], fields[2], fields[3]), fields);
        }

        ScopedKnowledge.Result knowledge = evidence.scopedKnowledge() == null
                ? new ScopedKnowledge.Result("", "", 0)
                : evidence.scopedKnowledge().supplemental(parsed, evidence.supplementBudget() / 2, maxKnowledgeQueries);
        String heading = "\n## Supplemental evidence requested during discovery\n\n";
        StringBuilder coverage = new StringBuilder(heading);
        StringBuilder prompt = new StringBuilder(heading);
        int remaining = evidence.supplementBudget() - EvidenceText.bytes(heading + knowledge.markdown()) - 220;
        int processed = 0;
        int total = maxRequests == 0 ? requests.size() : Math.min(requests.size(), maxRequests);
        int included = 0;
        for (String[] fields : requests.values()) {
            if (processed == total) {
                break;
            }
            int quota = Math.min(remaining, Math.max(256, remaining / (total - processed++)));
            String revision = fields[1].equals("base") ? evidence.scope().baseCommit() : evidence.scope().targetCommit();
            String label = fields[1] + " " + EvidenceText.oneLine(fields[2]) + " " + EvidenceText.oneLine(fields[3]);
            String status;
            try {
                if (quota < 256) {
                    throw new IOException("No room in the supplemental evidence budget");
                }
                Excerpt excerpt;
                if (fields[0].equals("REQUEST_FILE")) {
                    if (evidence.scopedKnowledge() != null && evidence.scopedKnowledge().isSourcePath(fields[2])) {
                        throw new IOException("Use a knowledge query for the scoped knowledge document");
                    }
                    excerpt = repositoryFile(revision, fields[2], fields[3], quota);
                } else {
                    excerpt = new Excerpt(maven.load(revision, fields[2], fields[3], quota), "INCLUDED");
                }
                int bytes = EvidenceText.bytes(excerpt.markdown());
                if (bytes > quota) {
                    throw new IOException("Requested material exceeds the supplemental evidence budget");
                }
                prompt.append(excerpt.markdown());
                remaining -= bytes;
                included++;
                status = excerpt.status();
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
        for (int line : invalidLines) {
            coverage.append("- Invalid file or Maven request on discovery output line ").append(line).append(".\n");
        }
        String summary = "\nSupplemental requests included: " + included + "/" + processed + ". Invalid: " + invalidLines.size()
                + ". Skipped by request limit: " + (requests.size() - processed) + ". See `coverage.md` for details.\n";
        return new Result(prompt.append(summary).append(knowledge.markdown()).toString(),
                coverage.append(summary).append(knowledge.coverage()).toString(), included > 0 || knowledge.suppliedSections() > 0);
    }

    private Excerpt repositoryFile(String revision, String path, String selector, int quota)
            throws IOException, InterruptedException {
        if (!eligiblePath(path)) {
            throw new IOException("Path is not eligible tracked source, test, schema, or documentation");
        }
        String title = path + " at " + revision;
        if (selector.equals("all")) {
            return new Excerpt(EvidenceText.block(title, "text", git.regularBlobAt(revision, path, quota)), "INCLUDED");
        }
        int from;
        int to;
        String source;
        boolean bySymbol = selector.startsWith("symbol:");
        if (bySymbol) {
            if (!path.endsWith(".java")) {
                throw new IOException("Method lookup is available for Java source; request a line range instead");
            }
            source = git.regularBlobAt(revision, path, MAX_SOURCE_BYTES);
            JavaMethodEvidence.Lines lines = JavaMethodEvidence.locate(source, selector.substring("symbol:".length()));
            from = lines.first();
            to = lines.last();
        } else {
            if (!selector.matches("[1-9][0-9]{0,6}:[1-9][0-9]{0,6}")) {
                throw new IOException("Expected all, a line range such as 10:90, or symbol:methodName for Java");
            }
            String[] endpoints = selector.split(":");
            from = Integer.parseInt(endpoints[0]);
            to = Integer.parseInt(endpoints[1]);
            if (to < from || to - from >= 200) {
                throw new IOException("Requested range must contain between 1 and 200 lines");
            }
            source = git.regularBlobAt(revision, path, MAX_SOURCE_BYTES);
        }
        if (bySymbol && to - from >= 200) {
            throw new IOException("Method spans more than 200 lines; request the relevant line range instead");
        }
        List<String> lines = source.lines().skip(from - 1L).limit(to - from + 1L).toList();
        if (lines.isEmpty()) {
            throw new IOException("Requested range is beyond the end of the file");
        }
        String location = "lines " + from + "–" + (from + lines.size() - 1);
        String block = EvidenceText.block(title + " (" + location + ")", "text", String.join("\n", lines));
        return new Excerpt(block, bySymbol ? "INCLUDED (" + location + ")" : "INCLUDED");
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
