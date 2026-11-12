package reviewagent;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Shared eligibility rules; independent of scanning, languages, and presentation. */
final class RepositoryPathPolicy {
    private static final List<String> EXCLUDED = List.of("/node_modules/", "/vendor/", "/third_party/",
            "/third-party/", "/generated/", "/dist/", "/build/", "/target/", "/.next/", "/coverage/");
    private static final Set<String> PRIVATE = Set.of(".git", ".claude", ".idea", ".aws", ".ssh");

    static boolean allowed(String path) {
        if (path.isBlank() || path.indexOf('\\') >= 0 || path.chars().anyMatch(Character::isISOControl)) return false;
        String normalized = "/" + path.toLowerCase(Locale.ROOT);
        for (String part : normalized.substring(1).split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || PRIVATE.contains(part)) return false;
        }
        for (String segment : EXCLUDED) if (normalized.contains(segment)) return false;
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        return !(name.equals(".env") || name.startsWith(".env.") && !name.equals(".env.example")
                || name.equals(".npmrc") || name.equals("credentials.json") || name.startsWith("secrets.")
                || name.endsWith(".lock") || name.endsWith(".min.js") || name.endsWith(".map"));
    }
}
