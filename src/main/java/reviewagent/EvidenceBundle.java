package reviewagent;

import java.util.List;

record EvidenceBundle(ReviewScope scope, List<String> changedFiles, String markdown,
                      String coverage, int supplementBudget, ScopedKnowledge scopedKnowledge, EvidenceCatalog catalog) {
    EvidenceBundle(ReviewScope scope, List<String> changedFiles, String markdown, String coverage, int supplementBudget, ScopedKnowledge knowledge) {
        this(scope, changedFiles, markdown, coverage, supplementBudget, knowledge,
                LegacyEvidenceAdapter.read(markdown, scope, LanguageRegistry.defaults()));
    }
}

