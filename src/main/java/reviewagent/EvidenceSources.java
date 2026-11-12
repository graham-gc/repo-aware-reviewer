package reviewagent;

import java.util.EnumSet;

/** Factories for native collectors; provenance comes from the read operation, never a heading. */
final class EvidenceSources {
    static EvidenceSource file(String revision, String path, int first, String title, String content,
                               ReviewScope scope, LanguageRegistry languages) {
        var roles = EnumSet.noneOf(EvidenceSource.Role.class);
        if (revision.equals(scope.baseCommit())) roles.add(EvidenceSource.Role.BASELINE);
        if (revision.equals(scope.targetCommit())) roles.add(EvidenceSource.Role.TARGET);
        if (EvidenceCatalog.implementation(languages, path)) roles.add(EvidenceSource.Role.IMPLEMENTATION);
        return EvidenceSource.create(title, "text", content, revision, path, first, roles, true);
    }

    static EvidenceSource contract(String title, String path, String revision, int first, String content) {
        return EvidenceSource.create(title, "markdown", content, revision, path, first,
                java.util.Set.of(EvidenceSource.Role.CONTRACT), true);
    }
}
