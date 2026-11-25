package reviewagent;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Resolve a failed file read inside the same Git snapshot, using explicit identity checks. */
final class EvidencePathRecovery {
    private static final Pattern PREDECESSOR = Pattern.compile(
            "(?i)\\b(?:predecessor|down_revision|(?:previous|parent) (?:migration|revision))\\b");
    private static final Pattern EXPECTED_PREDECESSOR = Pattern.compile(
            "(?i)\\bdown_revision\\b[\\s`'\"=:()\\[\\]-]*([A-Za-z0-9_][A-Za-z0-9_-]*)");
    private static final Pattern MULTILINE_STRING = Pattern.compile("(?s)\"\"\".*?\"\"\"|'''.*?'''");
    private static final Pattern ALEMBIC_IMPORT = Pattern.compile("(?m)^(?:from alembic import|import alembic\\b)");
    private static final Pattern REVISION = assignment("revision");
    private static final Pattern DOWN_REVISION = assignment("down_revision");
    private static final Pattern ID = Pattern.compile("(['\"])([A-Za-z0-9_-]+)\\1\\h*(?:#.*)?");

    record Resolution(String path, String basis) { }

    private final GitClient git;
    private final LanguageModule.Repository repository;
    private final int sourceLimit;

    EvidencePathRecovery(GitClient git, LanguageModule.Repository repository, int sourceLimit) {
        this.git = git;
        this.repository = repository;
        this.sourceLimit = sourceLimit;
    }

    Resolution resolve(String revision, EvidenceQuery query, List<EvidenceRequest> requests, EvidenceCatalog available,
                       Predicate<String> eligible) throws IOException, InterruptedException {
        List<String> tracked = repository.paths(revision);
        String wanted = query.resource();
        String parent = parent(wanted);
        String name = name(wanted);
        var exact = tracked.stream().filter(path -> name(path).equals(name)).limit(2).toList();
        String root = existingParent(parent, tracked);
        if (exact.size() == 1 && (parent.isEmpty() || !root.isEmpty()) && within(exact.getFirst(), root) && eligible.test(exact.getFirst()))
            return new Resolution(exact.getFirst(), "unique exact filename within the pinned repository");

        var predecessor = predecessor(revision, query, requests, available, eligible);
        if (predecessor != null) return predecessor;

        int extension = name.lastIndexOf('.');
        String stem = extension < 0 ? name : name.substring(0, extension);
        String suffix = extension < 0 ? "" : name.substring(extension);
        var candidates = tracked.stream().filter(path -> within(path, root) && eligible.test(path))
                .filter(path -> name(path).equals(name) || !parent.isEmpty() && parent(path).equals(parent)
                        && name(path).startsWith(stem + "_") && name(path).endsWith(suffix))
                .limit(5).toList();
        if (candidates.isEmpty()) throw new GitClient.MissingTrackedFileException();
        throw new IOException("No regular tracked file at that revision. Pinned path candidates (verify identity): "
                + String.join(", ", candidates));
    }

    private Resolution predecessor(String revision, EvidenceQuery query, List<EvidenceRequest> requests,
                                   EvidenceCatalog available, Predicate<String> eligible) throws IOException, InterruptedException {
        String directory = parent(query.resource());
        if (directory.isEmpty() || !query.resource().endsWith(".py") || !PREDECESSOR.matcher(query.reason()).find()) return null;
        String prefix = "REQUEST_FILE|" + query.side() + "|";
        int owner = new EvidenceRequest(0, prefix + query.resource() + "|all|" + query.reason()).discoveryOwner();
        var anchors = new LinkedHashSet<String>();
        for (var request : requests) {
            if (!request.text().startsWith(prefix) || request.navigationOnly()) continue;
            String path = request.text().split("\\|", 5)[2];
            if (!path.endsWith(".py") || path.equals(query.resource()) || !parent(path).equals(directory)
                    || !eligible.test(path)) continue;
            if (owner > 0 ? request.discoveryOwner() == owner : query.reason().contains(path)) anchors.add(path);
        }
        var visible = available.sources().stream().filter(source -> source.authoritative()
                && !source.role(EvidenceSource.Role.INDEX) && source.revision().equals(revision)).map(EvidenceSource::path)
                .collect(java.util.stream.Collectors.toSet());
        anchors.retainAll(visible);
        var reference = EXPECTED_PREDECESSOR.matcher(query.reason());
        if (reference.find()) {
            String identity = reference.group(1);
            available.sources().stream().filter(source -> source.authoritative()
                    && source.role(EvidenceSource.Role.DIFF) && source.revision().equals(revision))
                    .map(EvidenceSource::path).filter(path -> !path.equals(query.resource()) && parent(path).equals(directory)
                            && path.endsWith(".py") && eligible.test(path)).forEach(anchors::add);
            var corroborated = new LinkedHashSet<String>();
            for (String path : anchors) {
                try {
                    String code = declarations(repository.read(revision, path, sourceLimit));
                    if (identity.equals(literal(DOWN_REVISION, code))) corroborated.add(path);
                } catch (GitClient.MissingTrackedFileException removed) {
                    // A deleted migration cannot corroborate a predecessor in this revision.
                }
            }
            anchors.retainAll(corroborated);
        }
        if (anchors.size() != 1) return null;
        String anchor = anchors.iterator().next();
        String successor = declarations(repository.read(revision, anchor, sourceLimit));
        String expected = literal(DOWN_REVISION, successor);
        String successorId = literal(REVISION, successor);
        if (expected.isEmpty() || successorId.isEmpty() || successorId.equals(expected)) return null;

        var matches = new LinkedHashSet<String>();
        var tracked = new java.util.HashSet<>(repository.paths(revision));
        for (String path : git.filesContaining(revision, directory, List.of(expected))) {
            if (path.equals(anchor) || !tracked.contains(path) || !path.endsWith(".py") || !parent(path).equals(directory)
                    || !eligible.test(path)) continue;
            if (expected.equals(literal(REVISION, declarations(repository.read(revision, path, sourceLimit))))) {
                matches.add(path);
                if (matches.size() > 1)
                    throw new IOException("Ambiguous Alembic revision " + expected + " at the selected revision: " + String.join(", ", matches));
            }
        }
        return matches.size() == 1 ? new Resolution(matches.iterator().next(),
                "Alembic down_revision in " + anchor + " matches revision " + expected) : null;
    }

    private static String declarations(String source) {
        String code = MULTILINE_STRING.matcher(source).replaceAll("");
        return ALEMBIC_IMPORT.matcher(code).find() ? code : "";
    }

    private static Pattern assignment(String name) {
        return Pattern.compile("(?m)^" + name + "\\h*(?::[^=\\r\\n]+)?=\\h*(.*)$");
    }

    private static String literal(Pattern declaration, String code) {
        var assignment = declaration.matcher(code);
        if (!assignment.find()) return "";
        var value = ID.matcher(assignment.group(1).strip());
        return value.matches() && !assignment.find() ? value.group(2) : "";
    }

    private static String existingParent(String path, List<String> tracked) {
        while (!path.isEmpty()) {
            String prefix = path + "/";
            if (tracked.stream().anyMatch(file -> file.startsWith(prefix))) break;
            path = parent(path);
        }
        return path;
    }

    private static String parent(String path) { return path.substring(0, Math.max(0, path.lastIndexOf('/'))); }
    private static String name(String path) { return path.substring(path.lastIndexOf('/') + 1); }
    private static boolean within(String path, String directory) { return directory.isEmpty() || path.startsWith(directory + "/"); }
}
