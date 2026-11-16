package reviewagent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Actual provided material, not a global index or a candidate allegation, must ground every quotation. */
final class VerificationEvidenceTests {
    private static final ReviewScope SCOPE = new ReviewScope(ReviewScope.Mode.COMMIT, "base", "target", "base", "target", "base..target");
    private static final String PATH = "src/Payment.java";
    private static final String CODE = "class Payment { boolean accept() { return Counterparty.produceReceipt().ready(); } }";
    static void run() throws Exception {
        selectionAndGrounding();
        aliasAmbiguity();
        timings();
        supplementalRound();
    }

    private static EvidenceSource source(String path, String text) {
        return EvidenceSources.file("target", path, 1, path + " at target", text, SCOPE, LanguageRegistry.defaults());
    }
    private static ReviewFinding finding(String path) {
        return new ReviewFinding("R1-1", "Receipt acceptance", ReviewFinding.Severity.MEDIUM, path, 1, 1,
                "An incomplete receipt", "Incorrect acceptance", "Read the producer and its guard", "Changed return behavior");
    }
    private static void selectionAndGrounding() throws Exception {
        var entries = new ArrayList<EvidenceSource>();
        for (int i = 0; i < 24; i++) entries.add(source("src/Unrelated" + i + ".java", "// unrelated material\n".repeat(500)
                + "class Unrelated" + i + " { String detail = \"unavailable-secret-marker-" + i + "\"; }"));
        var rule = EvidenceSources.contract("Repository rules at base", "rules.md", "base", 1, "Incomplete receipts must be rejected.");
        entries.add(rule);
        var implementation = source(PATH, CODE); entries.add(implementation);
        var diff = EvidenceSource.diff(PATH, PATH, "diff --git a/src/Payment.java b/src/Payment.java\n"
                + "@@ -1,1 +1,1 @@\n-class Payment { boolean accept() { return false; } }\n+" + CODE + "\n", SCOPE, true);
        entries.add(diff);
        var producer = source("src/Counterparty.java", "class Counterparty { static Receipt produceReceipt() { return Receipt.complete(); } }");
        entries.add(producer);
        var test = source("src/test/PaymentTest.java", "class PaymentTest { void requiresComplete() { new Payment().accept(); } }"); entries.add(test);
        var catalog = new EvidenceCatalog(entries, true);
        var evidence = new EvidenceBundle(SCOPE, List.of(PATH, "src/Unrelated0.java"), entries.stream().map(EvidenceSource::markdown)
                .collect(java.util.stream.Collectors.joining()), "", 30_000, null, catalog);
        var view = VerificationEvidence.select(evidence, catalog, "", finding(PATH), Set.of(), false);
        require(!view.fallback() && view.bundle().catalog().sources().containsAll(List.of(rule, implementation, diff, producer, test)),
                "whole primary blocks, producer context, peer tests and trusted rules remain available");
        require(!view.bundle().catalog().sources().contains(entries.getFirst()), "unrelated changed files are not copied into each verification");
        require(view.bundle().catalog().reference(implementation).equals(catalog.reference(implementation))
                && view.bundle().catalog().source("SRC-1") == null, "scoping does not renumber aliases or redirect an omitted SRC reference");
        var bound = view.index().bind(List.of(new EvidenceIndex.Citation(catalog.reference(implementation), CODE)));
        view.index().validateLocation(finding(PATH), bound);
        fails(() -> view.index().bind(List.of(new EvidenceIndex.Citation(entries.getFirst().id(), "unavailable-secret-marker-0"))));
        fails(() -> view.index().validateLocation(finding("src/Unrelated0.java"), bound));
        var full = VerificationEvidence.select(evidence, catalog, "", finding(PATH), Set.of(), true);
        var prompts = new PromptFactory();
        int fullBytes = EvidenceText.bytes(prompts.verifyFinding(full.bundle(), finding(PATH), "", full.index()));
        int selectedBytes = EvidenceText.bytes(prompts.verifyFinding(view.bundle(), finding(PATH), "", view.index()));
        require(selectedBytes < fullBytes / 3, "a multi-file fixture materially reduces prompt bytes without cropping the relevant code");
        System.out.println("Verification fixture prompt bytes: " + fullBytes + " -> " + selectedBytes + " (whole evidence blocks)");
        var pinned = VerificationEvidence.select(evidence, catalog, "", finding(PATH), Set.of(entries.getFirst().id()), false);
        pinned.index().validate(List.of(new EvidenceIndex.Citation(entries.getFirst().id(), "unavailable-secret-marker-0")));
        require(VerificationEvidence.select(evidence, catalog, "", finding("missing.java"), Set.of(), false).fallback(),
                "a missing primary file falls back to full evidence rather than inventing a narrow anchor");
        require(full.bundle().markdown().equals(evidence.markdown()), "explicit full mode preserves the full evidence body");
        require(prompts.verifyFinding(evidence, finding(PATH), "", new EvidenceIndex(catalog))
                .equals(prompts.verifyFinding(full.bundle(), finding(PATH), "", full.index())),
                "full-mode verification prompts remain compatible with saved unscoped tasks");
        var offset = EvidenceSources.file("target", "src/Excerpt.java", 117, "src/Excerpt.java excerpt", "return requiredReceiptGuard();", SCOPE, LanguageRegistry.defaults());
        var offsetCatalog = new EvidenceCatalog(List.of(offset), true);
        var offsetBundle = new EvidenceBundle(SCOPE, List.of(offset.path()), offset.markdown(), "", 0, null, offsetCatalog);
        var offsetView = VerificationEvidence.select(offsetBundle, offsetCatalog, "", finding(offset.path()), Set.of(), false);
        require(offsetView.index().bind(List.of(new EvidenceIndex.Citation("SRC-1", offset.text()))).getFirst().locations().getFirst().firstLine() == 117,
                "existing excerpt coordinates survive selection unchanged");
    }
    private static void aliasAmbiguity() {
        var collision = source("src/Other.java", "class Other {}");
        collision = EvidenceSource.create("SRC-2", collision.format(), collision.text(), collision.revision(), collision.path(), 1, collision.roles(), true);
        var main = source(PATH, CODE);
        var catalog = new EvidenceCatalog(List.of(collision, main), true);
        var selected = catalog.select(Set.of(main.id()));
        require(catalog.source("SRC-2") == null && selected.source("SRC-2") == null
                && selected.reference(main).equals(main.id()), "removing an ambiguous alias's other owner cannot revive it");
    }
    private static void timings() throws Exception {
        require(ReviewTimings.unionNanos(List.of(new ReviewTimings.Interval(0, 100), new ReviewTimings.Interval(30, 80),
                new ReviewTimings.Interval(70, 150), new ReviewTimings.Interval(200, 220))) == 170,
                "overlapping calls are not summed as wall time");
        var metrics = new ReviewTimings();
        Path root = Files.createTempDirectory("review-timing-fixture-");
        long second = 1_000_000_000L;
        metrics.stage(root.resolve("verification"), 0, 2 * second);
        metrics.call(root.resolve("verification/R1-1"), 0, second, 1000, "SUCCEEDED");
        metrics.call(root.resolve("verification/R2-1-repair"), 0, second, 2000, "TIMEOUT");
        metrics.task(true, true);
        metrics.unchanged();
        String report = metrics.markdown(root, OutputLanguage.EN);
        require(report.contains("|verification|2|1|2.000|2.000|1.000|3000|") && report.contains("reused: 1"),
                "summary separates stage wall time, cumulative process time, corrections, bytes and cache reuse");
        metrics.write(root, OutputLanguage.ZH_CN);
        require(Files.readString(root.resolve("timings.md")).contains("阶段墙钟"), "timing report follows the chosen language");
        require(CliOptions.parse(new String[]{"review", "--repo", "."}).verificationEvidence() == CliOptions.VerificationEvidenceMode.SCOPED,
                "candidate selection is the default");
        require(CliOptions.parse(new String[]{"review", "--repo", ".", "--verification-evidence", "full"}).verificationEvidence()
                == CliOptions.VerificationEvidenceMode.FULL, "full input remains available for controlled comparisons");
    }

    private static void supplementalRound() throws Exception {
        Path root = Files.createTempDirectory("review-selective-round-");
        Path repo = Files.createDirectory(root.resolve("repo"));
        git(repo, "init", "-q");
        Files.writeString(repo.resolve("alpha.py"), "def inspect_alpha(): return 0\n");
        Files.writeString(repo.resolve("beta.py"), "def inspect_beta(): return 0\n");
        Files.writeString(repo.resolve("extra.py"), "def hidden_guard(): return True  # decisive_new_material\n");
        git(repo, "add", "--all"); git(repo, "commit", "-qm", "base");
        Files.writeString(repo.resolve("alpha.py"), "def inspect_alpha(): return 1\n");
        Files.writeString(repo.resolve("beta.py"), "def inspect_beta(): return 1\n");
        git(repo, "add", "--all"); git(repo, "commit", "-qm", "target");
        var options = CliOptions.parse(new String[]{"review", "--repo", repo.toString()});
        var executor = new ProcessExecutor();
        var git = new GitClient(repo, executor);
        var scope = git.resolve(options);
        var evidence = new EvidenceCollector(git).collect(scope, null, null, CliOptions.KnowledgeMode.FULL, 100_000);
        var session = new EvidenceSession(evidence, new SupplementalEvidence(git, executor, java.util.Map.of()), 0, 0);
        String script = """
                prompt=$(cat)
                case "$prompt" in
                  *'<path>alpha.py</path>'*)
                    printf x >> alpha-calls
                    case "$prompt" in
                      *'decisive_new_material'*) printf '<verification><status>REJECTED</status><reason>The requested producer guard disproves the claim.</reason></verification>';;
                      *) printf '<verification><status>INSUFFICIENT_EVIDENCE</status><reason>The guard implementation is not yet provided.</reason></verification>\\nREQUEST_FILE|target|extra.py|all|Inspect the producer guard\\n';;
                    esac;;
                  *) printf x >> beta-calls
                    printf '<verification><status>INSUFFICIENT_EVIDENCE</status><reason>No decisive evidence for this separate behavior.</reason></verification>';;
                esac
                """;
        var agent = new AgentRunner(executor, "/bin/sh", List.of("-c", script), root, Duration.ofSeconds(5));
        var alpha = new ReviewFinding("R1-1", "Alpha boundary", ReviewFinding.Severity.MEDIUM, "alpha.py", 1, 1,
                "Alpha state", "Alpha impact", "Check alpha", "Alpha change");
        var beta = new ReviewFinding("R2-1", "Beta boundary", ReviewFinding.Severity.MEDIUM, "beta.py", 1, 1,
                "Beta state", "Beta impact", "Check beta", "Beta change");
        try (var progress = new CommandProgress("scoping", new java.io.PrintStream(java.io.OutputStream.nullOutputStream()))) {
            var quality = new ReviewQuality(options, evidence, session, new PromptFactory(), agent, root, progress, 9);
            var decisions = quality.verify(List.of(alpha, beta));
            require(Files.readString(root.resolve("alpha-calls")).equals("xx") && Files.readString(root.resolve("beta-calls")).equals("x"),
                    "new material reruns its requester but does not repeat an unchanged peer prompt");
            require(decisions.getFirst().verdict().status() == ReviewOutput.Status.REJECTED
                    && Files.readString(root.resolve("verification/round-2/R1-1-prompt.md")).contains("decisive_new_material"),
                    "requested implementation is pinned into the next task even without a lexical candidate match");
            require(Files.isRegularFile(root.resolve("verification/round-2/R2-1-unchanged.txt")), "skipping unchanged evidence is recorded");
        }
    }
    private static void git(Path directory, String... args) throws Exception {
        var command = new ArrayList<>(List.of("git", "-c", "user.name=Evidence Test", "-c", "user.email=test@example.invalid"));
        command.addAll(List.of(args));
        var result = new ProcessExecutor().run(command, directory, null, Duration.ofSeconds(5));
        require(result.exitCode() == 0, "synthetic Git fixture: " + result.stderr());
    }
    private static void fails(Checked action) throws Exception {
        try { action.run(); throw new AssertionError("Expected omitted or ungrounded evidence to be rejected"); }
        catch (IOException expected) { }
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
