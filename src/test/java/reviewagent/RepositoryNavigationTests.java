package reviewagent;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class RepositoryNavigationTests {
    static void run() throws Exception {
        var paths = new ArrayList<>(List.of("README.md", "modules/payment/docs/contract.md", "modules/payment/tests/PaymentTest.java",
                "modules/payment/src/Payment.java", "modules/stock/docs/contract.md", "modules/stock/tests/StockTest.java",
                "modules/payment/.env", "modules/payment/.claude/README.md", "modules/payment/vendor/docs/private.md", "docs/master.md"));
        var repository = new LanguageModule.Repository() {
            public List<String> paths(String revision) { return paths; }
            public String read(String revision, String path, int budget) { throw new AssertionError("Navigation must not read file bodies"); }
        };
        var source = RepositoryNavigation.source(repository, "pinned-base", List.of("modules/payment/src/Payment.java"), Set.of("docs/master.md"), 2_000);
        require(source.text().contains("- README.md\n") && source.text().contains("payment/docs/contract.md")
                && source.text().contains("payment/tests/PaymentTest.java") && !source.text().contains("modules/stock")
                && !source.text().contains("private.md") && !source.text().contains(".claude") && !source.text().contains("master.md"),
                "navigation includes nearby documents/tests while respecting module boundaries, private paths and scoped knowledge exclusion");
        require(source.role(EvidenceSource.Role.INDEX) && !source.role(EvidenceSource.Role.CONTRACT)
                && !source.role(EvidenceSource.Role.IMPLEMENTATION) && source.revision().equals("pinned-base"),
                "path hints have pinned metadata provenance and no business or implementation authority");
        for (int i = 0; i < 200; i++) paths.add("modules/payment/docs/说明-" + i + ".md");
        var bounded = RepositoryNavigation.source(repository, "pinned-head", List.of("modules/payment/src/Payment.java"), Set.of(), 1_000);
        require(EvidenceText.bytes(bounded.markdown()) <= 1_000 && bounded.text().contains("PARTIAL (byte budget)")
                && bounded.text().contains("not a complete repository inventory") && bounded.text().contains("- README.md\n"),
                "navigation is bounded in UTF-8 bytes, prioritizes root documentation and never claims full repository coverage");
        require(RepositoryNavigation.source(repository, "base", List.of("file.py"), Set.of(), 100) == null,
                "tiny evidence budgets retain code space instead of overflowing navigation");

        String changed = "modules/payment/ui/invoice_app_bridge.tsx";
        String api = "modules/payment/backend/api/invoice_apps_api.py";
        String service = "modules/payment/backend/services/invoice_apps_service.py";
        String unrelated = "modules/stock/backend/api/invoice_apps_api.py";
        paths.addAll(List.of(changed, api, service, unrelated, "modules/payment/tests/test_invoice_apps_api.py"));
        var prioritized = RepositoryNavigation.source(repository, "pinned-head", List.of(changed), Set.of(), 1_000);
        require(prioritized.text().contains("- " + api + "\n") && prioritized.text().contains("- " + service + "\n")
                && !prioritized.text().contains(unrelated) && EvidenceText.bytes(prioritized.markdown()) <= 1_000,
                "known implementation paths for a changed feature receive room before related tests and docs consume the budget");
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
