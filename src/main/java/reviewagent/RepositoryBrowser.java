package reviewagent;

import java.io.IOException;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Language-neutral discovery of regular tracked paths at an immutable revision. */
final class RepositoryBrowser {
    private final LanguageModule.Repository repository;

    static final class MissingDirectoryException extends IOException {
        MissingDirectoryException() { super("path: no tracked directory at the selected revision"); }
    }

    RepositoryBrowser(LanguageModule.Repository repository) { this.repository = repository; }

    boolean directory(String revision, String path) throws IOException, InterruptedException {
        String prefix = directoryPrefix(path);
        return repository.paths(revision).stream().anyMatch(file -> file.startsWith(prefix) && RepositoryPathPolicy.allowed(file));
    }

    String index(String revision, String query, int budget) throws IOException, InterruptedException {
        return source(revision, query, budget).markdown();
    }

    EvidenceSource source(String revision, String query, int budget) throws IOException, InterruptedException {
        var matches = pathsMatching(query);
        List<String> matching = repository.paths(revision).stream().filter(matches).sorted().toList();
        if (matching.isEmpty() && !query.equals(".") && !isGlob(query))
            throw new MissingDirectoryException();
        matching = matching.stream().filter(RepositoryPathPolicy::allowed).toList();
        StringBuilder body = new StringBuilder("Tracked repository files (metadata only; all languages).\n");
        int listed = 0;
        for (String path : matching) {
            String line = "- " + path + "\n";
            if (EvidenceText.bytes(body + line) > budget - 350) break;
            body.append(line); listed++;
        }
        body.append("Files listed: ").append(listed).append("/").append(matching.size())
                .append(". Coverage: ").append(listed == matching.size() ? "COMPLETE" : "PARTIAL (byte budget)")
                .append(". Read file contents before deciding a finding.\n");
        EvidenceSource source = EvidenceSource.create("Repository file index " + query + " at " + revision, "index",
                body.toString(), revision, "", 0, java.util.Set.of(EvidenceSource.Role.INDEX), true);
        String block = source.markdown();
        if (EvidenceText.bytes(block) > budget) throw new IOException("Repository navigation exceeds the evidence budget");
        return source;
    }

    String parentOfMissingScope(String revision, String scope) throws IOException, InterruptedException {
        if (scope.equals(".") || isGlob(scope)) return "";
        String prefix = directoryPrefix(scope);
        String path = prefix.substring(0, prefix.length() - 1);
        var tracked = repository.paths(revision);
        if (tracked.stream().anyMatch(file -> file.equals(path) || file.startsWith(prefix))) return "";
        for (int slash = path.lastIndexOf('/'); slash >= 0; slash = path.lastIndexOf('/', slash - 1)) {
            String parent = path.substring(0, slash);
            String parentPrefix = parent + "/";
            if (tracked.stream().anyMatch(file -> file.startsWith(parentPrefix) && RepositoryPathPolicy.allowed(file)))
                return parent;
        }
        return "";
    }

    static Predicate<String> pathsMatching(String query) throws IOException {
        String prefix = directoryPrefix(query);
        if (!isGlob(query)) return path -> path.startsWith(prefix);
        Pattern pattern = glob(query);
        return path -> pattern.matcher(path).matches();
    }

    static String searchPathspec(String query) {
        int wildcard = query.indexOf('*');
        int question = query.indexOf('?');
        if (wildcard < 0 || question >= 0 && question < wildcard) wildcard = question;
        String prefix = wildcard < 0 ? query : query.substring(0, query.lastIndexOf('/', wildcard) + 1);
        return prefix.isEmpty() || prefix.equals(".") ? ":(top,glob)**" : ":(top,literal)" + prefix;
    }

    private static String directoryPrefix(String query) throws IOException {
        if (query.equals(".")) return "";
        String path = query.endsWith("/") ? query.substring(0, query.length() - 1) : query;
        if (!RepositoryPathPolicy.allowed(path)) throw new IOException("path: invalid or excluded repository navigation path");
        return path + "/";
    }

    static boolean isGlob(String query) {
        return query.contains("*") || query.contains("?");
    }

    private static Pattern glob(String query) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);
            if (c == '*' && i + 1 < query.length() && query.charAt(i + 1) == '*') {
                i++;
                if (i + 1 < query.length() && query.charAt(i + 1) == '/') { i++; regex.append("(?:.*/)?"); }
                else regex.append(".*");
            } else if (c == '*') regex.append("[^/]*");
            else if (c == '?') regex.append("[^/]");
            else regex.append(Pattern.quote(String.valueOf(c)));
        }
        return Pattern.compile(regex.append('$').toString());
    }
}
