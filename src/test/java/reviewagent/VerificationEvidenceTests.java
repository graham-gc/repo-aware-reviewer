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
        anchoredSelection();
        aliasAmbiguity();
        timings();
        supplementalRound();
        navigationAvoidsLookupRound();
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

    private static void anchoredSelection() throws Exception {
        String first = "class Payment { boolean accept() { return Counterparty.produceReceipt().ready(); } }";
        String far = "class Diagnostics { void run() { LegacyMetrics.publishMetric(); } }";
        var primary = source(PATH, "import src.ReceiptPolicy;\n" + first + "\n" + "// reserved\n".repeat(17) + far);
        var diff = EvidenceSource.diff(PATH, PATH, """
                diff --git a/src/Payment.java b/src/Payment.java
                @@ -2 +2 @@
                -class Payment { boolean accept() { return false; } }
                +class Payment { boolean accept() { return Counterparty.produceReceipt().ready(); } }
                @@ -20 +20 @@
                -class Diagnostics { void run() {} }
                +class Diagnostics { void run() { LegacyMetrics.publishMetric(); } }
                """, SCOPE, true);
        var producer = source("src/Counterparty.java", "class Counterparty { static Receipt produceReceipt() { return incomplete(); } }");
        var imported = source("src/ReceiptPolicy.java", "class ReceiptPolicy { boolean allowed() { return false; } }");
        var farDependency = source("src/LegacyMetrics.java", "class LegacyMetrics { static void publishMetric() { publish(); } }");
        var sameName = source("ui/receipt.jsx", "export function produceReceipt() { return 'receipt preview only'; }");
        var rule = EvidenceSources.contract("Repository rules at base", "rules.md", "base", 1, "Incomplete receipts must be rejected.");
        var sources = List.of(primary, diff, producer, imported, farDependency, sameName, rule);
        var catalog = new EvidenceCatalog(sources, true);
        var bundle = new EvidenceBundle(SCOPE, List.of(PATH), "", "", 20_000, null, catalog);
        var finding = new ReviewFinding("R1-1", "Receipt acceptance", ReviewFinding.Severity.MEDIUM, PATH, 2, 2,
                "An incomplete receipt", "Incorrect acceptance", "Read the producer and its guard", "Changed return behavior");
        var broad = VerificationEvidence.select(bundle, catalog, "", finding, Set.of(), false);
        var narrow = VerificationEvidence.select(bundle, catalog, "", finding, Set.of(), false, true);
        require(broad.bundle().catalog().sources().containsAll(sources)
                && narrow.bundle().catalog().sources().equals(List.of(primary, diff, producer, imported, rule)),
                "anchor hints keep the direct producer and explicit import, and drop distant or cross-language name matches");
        require(catalog.reference(producer).equals(narrow.bundle().catalog().reference(producer)),
                "anchored selection preserves citation identities");
        fails(() -> narrow.index().validate(List.of(new EvidenceIndex.Citation(sameName.id(), "return 'receipt preview only'"))));
        var condition = new ReviewFinding(finding.id(), finding.title(), finding.severity(), finding.path(), finding.line(),
                finding.endLine(), finding.trigger(), finding.impact(), "Read receipt.jsx for the intended behavior.", finding.diffRelation());
        var explicit = VerificationEvidence.select(bundle, catalog, "", condition, Set.of(farDependency.id()), false, true);
        require(explicit.bundle().catalog().sources().containsAll(sources),
                "a unique explicitly named filename can reference another language, and supplied guards are retained");
        var ambiguous = catalog.plus(new EvidenceCatalog(List.of(source("other/receipt.jsx", "export const view = 'different'")), true));
        var withoutGuess = VerificationEvidence.select(bundle, ambiguous, "", condition, Set.of(), false, true);
        require(withoutGuess.bundle().catalog().source(sameName.id()) == null,
                "a bare filename with multiple owners does not select an unrelated cross-language body");
        var outside = new ReviewFinding(finding.id(), finding.title(), finding.severity(), finding.path(), 8, 8,
                finding.trigger(), finding.impact(), finding.evidence(), finding.diffRelation());
        var unanchored = VerificationEvidence.select(bundle, catalog, "", outside, Set.of(), false, true);
        require(unanchored.bundle().catalog().sources().equals(sources), "a location outside changed hunks keeps the broad scope");
    }
    private static void timings() throws Exception {
        require(ReviewTimings.unionNanos(List.of(new ReviewTimings.Interval(0, 100), new ReviewTimings.Interval(30, 80),
                new ReviewTimings.Interval(70, 150), new ReviewTimings.Interval(200, 220))) == 170,
                "overlapping calls are not summed as wall time");
        var metrics = new ReviewTimings();
        Path root = Files.createTempDirectory("review-timing-fixture-");
        long second = 1_000_000_000L;
        metrics.stage(root.resolve("verification"), 0, 2 * second);
        metrics.call(root.resolve("verification/R1-1"), null, 0, second, 1000, "SUCCEEDED",
                new ProcessExecutor.Observations(1L, 2L, 950L, null, 975L));
        metrics.call(root.resolve("verification/R2-1-repair"), null, 0, second, 2000, "TIMEOUT",
                new ProcessExecutor.Observations(1L, 2L, null, null, null));
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
                candidate=$(printf '%s\\n' "$prompt" | sed -n '/^CANDIDATE TO VERIFY$/{n;p;}')
                case "$candidate" in
                  *'id="R1-1"'*)
                    printf x >> alpha-calls
                    case "$prompt" in
                      *'decisive_new_material'*)
                        [ -f same-path-peer-calls ] || exit 41
                        printf '<verification><status>REJECTED</status><reason>The requested producer guard disproves the claim.</reason></verification>';;
                      *) printf '<verification><status>INSUFFICIENT_EVIDENCE</status><reason>The guard implementation is not yet provided.</reason><nextCheck>Does the producer prevent the failing input?</nextCheck></verification>\\nREQUEST_FILE|target|extra.py|all|Inspect the producer guard\\n';;
                    esac;;
                  *'id="R3-1"'*) printf x >> same-path-peer-calls
                    printf '<verification><status>INSUFFICIENT_EVIDENCE</status><reason>The nearby claim has a separate unresolved precondition.</reason></verification>';;
                  *) printf x >> beta-calls
                    printf '<verification><status>INSUFFICIENT_EVIDENCE</status><reason>No decisive evidence for this separate behavior.</reason></verification>';;
                esac
                """;
        var agent = new AgentRunner(executor, "/bin/sh", List.of("-c", script), root, Duration.ofSeconds(5));
        var alpha = new ReviewFinding("R1-1", "inspect_alpha producer boundary", ReviewFinding.Severity.MEDIUM, "alpha.py", 1, 1,
                "Alpha state", "Alpha impact", "Check alpha", "Alpha change");
        var beta = new ReviewFinding("R2-1", "Beta boundary", ReviewFinding.Severity.MEDIUM, "beta.py", 1, 1,
                "Beta state", "Beta impact", "Check beta", "Beta change");
        var nearby = new ReviewFinding("R3-1", "inspect_alpha return value", ReviewFinding.Severity.MEDIUM, "alpha.py", 1, 1,
                "A different entry point", "The consumer interprets the value incorrectly", "Check the consumer", "Return value changed");
        try (var progress = new CommandProgress("scoping", new java.io.PrintStream(java.io.OutputStream.nullOutputStream()))) {
            var quality = new ReviewQuality(options, evidence, session, new PromptFactory(), agent, root, progress, 9);
            var decisions = quality.verify(List.of(alpha, beta, nearby));
            require(Files.readString(root.resolve("alpha-calls")).equals("xx") && Files.readString(root.resolve("beta-calls")).equals("x"),
                    "new material reruns its requester but does not repeat an unchanged peer prompt");
            require(Files.readString(root.resolve("same-path-peer-calls")).equals("x")
                            && decisions.get(2).verdict().status() == ReviewOutput.Status.INSUFFICIENT_EVIDENCE,
                    "a nearby candidate receives its own initial verdict before the requester can enter its follow-up");
            require(decisions.getFirst().verdict().status() == ReviewOutput.Status.REJECTED
                    && Files.readString(root.resolve("verification/round-2/R1-1-prompt.md")).contains("decisive_new_material"),
                    "requested implementation is pinned into the next task even without a lexical candidate match");
            require(!Files.exists(root.resolve("verification/round-2/R2-1-prompt.md")), "only the requester receives new evidence");
            String followup = Files.readString(root.resolve("verification/round-2/R1-1-prompt.md"));
            require(followup.contains("VERIFICATION FOLLOW-UP CONTEXT") && followup.contains("Previous model opinion: INSUFFICIENT_EVIDENCE")
                    && followup.contains("The guard implementation is not yet provided.") && followup.contains("REQUEST_FILE|target|extra.py|all")
                    && followup.contains("target extra.py all: INCLUDED") && followup.contains("Newly supplied source references")
                    && followup.contains("not evidence or an approved rule"),
                    "follow-up carries the candidate's prior gap, own request, processing outcome and newly supplied source identities without promoting old opinions");
        }
    }

    private static void navigationAvoidsLookupRound() throws Exception {
        Path root = Files.createTempDirectory("review-navigation-rounds-");
        Path repo = Files.createDirectory(root.resolve("repo"));
        git(repo, "init", "-q");
        Files.writeString(repo.resolve("alpha.py"), "def inspect_alpha(): return 0\n");
        Files.writeString(repo.resolve("README.md"), "DOCUMENT_CONTENT_MARKER: interface intent is documented here.\n");
        git(repo, "add", "--all"); git(repo, "commit", "-qm", "base");
        Files.writeString(repo.resolve("alpha.py"), "def inspect_alpha(): return 1\n");
        git(repo, "add", "--all"); git(repo, "commit", "-qm", "target");
        var options = CliOptions.parse(new String[]{"review", "--repo", repo.toString()});
        var executor = new ProcessExecutor();
        var git = new GitClient(repo, executor);
        var evidence = new EvidenceCollector(git).collect(git.resolve(options), null, null, CliOptions.KnowledgeMode.FULL, 100_000);
        require(evidence.markdown().contains("- README.md\n") && !evidence.markdown().contains("DOCUMENT_CONTENT_MARKER"),
                "initial navigation advertises document paths without loading document bodies");
        String script = """
                prompt=$(cat)
                printf x >> calls
                case "$prompt" in
                  *DOCUMENT_CONTENT_MARKER*) printf '<verification><status>REJECTED</status><reason>The supplied document resolves the allegation.</reason></verification>';;
                  *'- README.md'*) printf '<verification><status>INSUFFICIENT_EVIDENCE</status><reason>Read the listed document.</reason><nextCheck>Does the interface specification permit the change?</nextCheck></verification>\\nREQUEST_FILE|target|README.md|all|Read the interface documentation\\n';;
                  *) printf '<verification><status>INSUFFICIENT_EVIDENCE</status><reason>Locate available documentation.</reason><nextCheck>Does the interface specification permit the change?</nextCheck></verification>\\nREQUEST_FILE|target|.|index|Find documentation paths\\n';;
                esac
                """;
        for (boolean navigation : List.of(false, true)) {
            var sources = evidence.catalog().sources().stream()
                    .filter(source -> navigation || !source.title().startsWith("Related repository paths at ")).toList();
            String markdown = evidence.markdown();
            if (!navigation) for (var source : evidence.catalog().sources())
                if (source.title().startsWith("Related repository paths at ")) markdown = markdown.replace(source.markdown(), "");
            var bundle = new EvidenceBundle(evidence.scope(), evidence.changedFiles(), markdown, evidence.coverage(),
                    evidence.supplementBudget(), null, new EvidenceCatalog(sources, true));
            Path run = Files.createDirectory(root.resolve(navigation ? "with-navigation" : "without-navigation"));
            try (var progress = new CommandProgress("navigation", new java.io.PrintStream(java.io.OutputStream.nullOutputStream()))) {
                var session = new EvidenceSession(bundle, new SupplementalEvidence(git, executor, java.util.Map.of()), 0, 0);
                var agent = new AgentRunner(executor, "/bin/sh", List.of("-c", script), run, Duration.ofSeconds(5));
                var quality = new ReviewQuality(options, bundle, session, new PromptFactory(), agent, run, progress, 9);
                var findings = List.of(finding("alpha.py"));
                var expected = navigation ? ReviewOutput.Status.REJECTED : ReviewOutput.Status.INSUFFICIENT_EVIDENCE;
                require(quality.verify(findings).getFirst().verdict().status() == expected,
                        "an unresolved premise is retained when only a directory index is supplied as evidence");
                String calls = Files.readString(run.resolve("calls"));
                require(calls.equals(navigation ? "xx" : "x"), "only an actual document justifies another model call");
                var resume = CliOptions.parse(new String[]{"review", "--repo", repo.toString(), "--resume", run.toString()});
                var resumedSession = new EvidenceSession(bundle, new SupplementalEvidence(git, executor, java.util.Map.of()), 0, 0);
                var unavailable = new AgentRunner(executor, "/bin/sh", List.of("-c", "exit 89"), run, Duration.ofSeconds(5));
                var resumed = new ReviewQuality(resume, bundle, resumedSession, new PromptFactory(), unavailable, run, progress, 9);
                require(resumed.verify(findings).getFirst().verdict().status() == expected
                        && Files.readString(run.resolve("calls")).equals(calls), "follow-up context is deterministic and reuses all valid matching rounds on resume");
            }
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
