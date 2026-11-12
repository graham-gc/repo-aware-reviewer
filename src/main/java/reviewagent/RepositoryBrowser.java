package reviewagent;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

/** Language-neutral discovery of regular tracked paths at an immutable revision. */
final class RepositoryBrowser {
    private final LanguageModule.Repository repository;

    RepositoryBrowser(LanguageModule.Repository repository) { this.repository = repository; }

    boolean directory(String revision, String path) throws IOException, InterruptedException {
        String prefix = directoryPrefix(path);
        return repository.paths(revision).stream().anyMatch(file -> file.startsWith(prefix) && RepositoryPathPolicy.allowed(file));
    }

    String index(String revision, String query, int budget) throws IOException, InterruptedException {
        return source(revision, query, budget).markdown();
    }

    EvidenceSource source(String revision, String query, int budget) throws IOException, InterruptedException {
        String prefix = directoryPrefix(query);
        Pattern pattern = query.contains("*") || query.contains("?") ? glob(query) : null;
        List<String> matching = repository.paths(revision).stream().filter(RepositoryPathPolicy::allowed)
                .filter(path -> pattern == null ? path.startsWith(prefix) : pattern.matcher(path).matches()).sorted().toList();
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

    private static String directoryPrefix(String query) throws IOException {
        if (query.equals(".")) return "";
        String path = query.endsWith("/") ? query.substring(0, query.length() - 1) : query;
        if (!RepositoryPathPolicy.allowed(path)) throw new IOException("Invalid or excluded repository navigation path");
        return path + "/";
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
