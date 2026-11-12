package reviewagent;

import java.io.IOException;
import java.util.List;
import java.util.Set;

final class EvidenceReferenceTests {
    static void run() throws Exception {
        var first = EvidenceSource.create("first.java", "text", "first_operation();", "target", "first.java", 10,
                Set.of(EvidenceSource.Role.IMPLEMENTATION, EvidenceSource.Role.TARGET), true);
        var second = EvidenceSources.contract("rules", "rules.md", "document:hash", 1, "Incomplete results must not pass.");
        var catalog = new EvidenceCatalog(List.of(first, second), true);
        require(catalog.reference(first).equals("SRC-1") && catalog.reference(second).equals("SRC-2"), "short references are easy to copy");
        var index = new EvidenceIndex(catalog);
        for (String name : List.of("SRC-1", first.id(), first.title())) {
            var citations = index.bind(List.of(new EvidenceIndex.Citation(name, "first_operation();")));
            require(citations.getFirst().sourceId().equals(first.id()) && index.hasTarget(citations) && !index.hasContract(citations),
                    "short, legacy and title aliases all bind the same immutable identity and role");
        }
        fails(() -> index.validate(List.of(new EvidenceIndex.Citation("SRC-1 [contract]", "first_operation();"))));
        fails(() -> index.validate(List.of(new EvidenceIndex.Citation("SRC-9", "first_operation();"))));
        var extra = EvidenceSource.create("later.py", "text", "later_operation();", "target", "later.py", 1,
                Set.of(EvidenceSource.Role.IMPLEMENTATION, EvidenceSource.Role.TARGET), true);
        var expanded = catalog.plus(new EvidenceCatalog(List.of(first, extra), true));
        require(expanded.reference(first).equals("SRC-1") && expanded.reference(second).equals("SRC-2")
                && expanded.reference(extra).equals("SRC-3"), "appending and deduplicating supplemental sources never renumbers existing references");
        var collision = EvidenceSource.create("SRC-1", "text", "literal_collision();", "target", "SRC-1", 1,
                Set.of(EvidenceSource.Role.IMPLEMENTATION, EvidenceSource.Role.TARGET), true);
        var ambiguous = catalog.plus(new EvidenceCatalog(List.of(collision), true));
        require(ambiguous.reference(first).equals(first.id()), "an ambiguous short reference falls back to the stable identity");
        fails(() -> new EvidenceIndex(ambiguous).validate(List.of(new EvidenceIndex.Citation("SRC-1", "first_operation();"))));

        String invented = "if (orderedNodes.size() != nodesById.size()) {\n    throw new IllegalStateException(\"Flow contains a cycle\");\n}";
        var actual = EvidenceSource.create("service.java", "text", "if (orderedNodes.isEmpty()) {\n"
                + "    throw new IllegalStateException(\"Flow has no executable nodes\");\n}\n", "target", "service.java", 408,
                Set.of(EvidenceSource.Role.IMPLEMENTATION, EvidenceSource.Role.TARGET), true);
        try {
            new EvidenceIndex(new EvidenceCatalog(List.of(actual), true)).validate(List.of(new EvidenceIndex.Citation(actual.id(), invented)));
            throw new AssertionError("An invented cycle condition must not match an empty-node guard");
        } catch (SourceQuotationException invalid) {
            require(invalid.getMessage().contains("orderedNodes.isEmpty()") && !invalid.getMessage().contains("<lines>"),
                    "a generic shared prefix may show actual code but cannot offer an unrelated range selector");
        }
    }
    private static void fails(Checked action) throws Exception {
        try { action.run(); throw new AssertionError("Expected invalid reference to be rejected"); }
        catch (IOException expected) { }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
