package reviewagent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Cached globally is not the same as supplied to a particular verification task. */
final class EvidenceDeliveryTests {
    private static final ReviewScope SCOPE = new ReviewScope(ReviewScope.Mode.BRANCH, "base", "head", "base", "head", "base..head");
    private static final String PATH = "docs/interface.md";
    private static final String BODY = "INTERFACE_DOCUMENT_BODY: inspect this pinned reference before deciding.\nA second source line.\n";

    static void run() throws Exception {
        fileCoverage();
        cachedRecipientDelivery();
        sharedBatchBudget();
        constructorReferences();
        pipelineDeliveryAndResume();
    }

    private static EvidenceSource file(String revision, int first, String text, boolean eof) {
        var source = EvidenceSources.file(revision, PATH, first, PATH + " at " + revision + " from " + first, text, SCOPE, LanguageRegistry.defaults());
        return eof ? source.throughEndOfFile() : source;
    }
    private static Set<String> lookup(String side, String selector, List<EvidenceSource> sources) throws Exception {
        var query = EvidenceQuery.parse("REQUEST_FILE|" + side + "|" + PATH + "|" + selector + "|Inspect the contract", LanguageRegistry.defaults());
        return FileEvidenceCache.covering(query, SCOPE, new EvidenceCatalog(sources, true));
    }
    private static void fileCoverage() throws Exception {
        var whole = file("head", 1, BODY, true);
        var base = file("base", 1, "The baseline document has different text.\n", true);
        require(whole.id().equals(file("head", 1, BODY, false).id()), "file-extent metadata does not replace immutable source IDs");
        for (String selector : List.of("all", "1:1", "1:200"))
            require(lookup("target", selector, List.of(base, whole)).equals(Set.of(whole.id())), "whole native source covers its ranges and EOF-clipped requests");
        require(lookup("base", "all", List.of(base, whole)).equals(Set.of(base.id()))
                && lookup("base", "all", List.of(whole)).isEmpty(), "reuse never crosses pinned revisions");
        require(lookup("target", "3:4", List.of(whole)).isEmpty() && lookup("target", "1:201", List.of(whole)).isEmpty(),
                "out-of-file and oversized requests retain their original validation boundary");
        var first = file("head", 1, "first line of source\nsecond line of source\n", false);
        var adjacent = file("head", 3, "third line of source\nfourth line of source\n", true);
        var gap = file("head", 4, "fourth line of source\n", true);
        require(lookup("target", "all", List.of(first)).isEmpty(), "an excerpt is not a complete file just because it begins at line one");
        require(lookup("target", "all", List.of(first, adjacent)).equals(Set.of(first.id(), adjacent.id()))
                && lookup("target", "2:3", List.of(first, adjacent)).equals(Set.of(first.id(), adjacent.id())),
                "continuous native intervals can satisfy a request without inventing a merged quote");
        require(lookup("target", "all", List.of(first, gap)).isEmpty()
                && lookup("target", "2:4", List.of(first, gap)).isEmpty(), "a missing line cannot count as collected evidence");
        var firstLine = file("head", 1, "first line of source", false);
        require(FileEvidenceCache.alreadySupplied(firstLine, new EvidenceCatalog(List.of(first), true))
                && !FileEvidenceCache.alreadySupplied(first, new EvidenceCatalog(List.of(firstLine), true))
                && !FileEvidenceCache.alreadySupplied(adjacent, new EvidenceCatalog(List.of(first, gap), true)),
                "a renamed excerpt is neither new evidence nor a substitute for newly supplied lines");
        var combined = file("head", 1, first.text() + adjacent.text(), true);
        require(FileEvidenceCache.alreadySupplied(combined, new EvidenceCatalog(List.of(first, adjacent), true))
                && !FileEvidenceCache.alreadySupplied(combined, new EvidenceCatalog(List.of(first, gap), true))
                && !FileEvidenceCache.alreadySupplied(combined, new EvidenceCatalog(List.of(first, file("head", 3, adjacent.text(), false)), true)),
                "overlapping delivery adds no evidence if native slices already show every line and the same file end");
        var contract = EvidenceSources.contract("Approved rules", PATH, "head", 1, BODY).throughEndOfFile();
        var index = EvidenceSource.create("File names", "index", BODY, "head", PATH, 1,
                Set.of(EvidenceSource.Role.INDEX, EvidenceSource.Role.TARGET), true).throughEndOfFile();
        var legacy = EvidenceSource.create("Legacy file", "text", BODY, "head", PATH, 1, Set.of(EvidenceSource.Role.TARGET), false).throughEndOfFile();
        var diff = EvidenceSource.diff(PATH, null, BODY, SCOPE, false).throughEndOfFile();
        require(lookup("target", "all", List.of(contract, index, legacy, diff)).isEmpty(),
                "contracts, indexes, legacy headings and patches never masquerade as complete native files");
    }

    private static void cachedRecipientDelivery() throws Exception {
        Path root = Files.createTempDirectory("collected-not-delivered-");
        // This directory is deliberately not a Git repository: covered reads must not need Git.
        var git = new GitClient(root, new ProcessExecutor());
        var document = file("head", 1, BODY, true);
        var catalog = new EvidenceCatalog(List.of(document), true);
        var bundle = new EvidenceBundle(SCOPE, List.of("runner.py"), document.markdown(), "", 0, null, catalog);
        var session = new EvidenceSession(bundle, new SupplementalEvidence(git, new ProcessExecutor(), java.util.Map.of()), 1, 0);
        String all = "REQUEST_FILE|target|" + PATH + "|all|Read reference";
        String range = "REQUEST_FILE|target|" + PATH + "|1:200|Read its opening";
        require(session.requestOnly(all + "\n" + range)
                && !session.requestOnly("Some prose\n" + all)
                && !session.requestOnly("REQUEST_FILE|target|" + PATH + "|all")
                && !session.requestOnly("REQUEST_UNKNOWN|target|" + PATH + "|all|Read reference"),
                "only standalone lines with supported request syntax can defer a verification verdict");
        require(!session.load(all, "Initial request") && !session.load(range, "Another candidate"),
                "reuse adds no new global material and works even after the byte budget is exhausted");
        require(session.catalog().sources().size() == 1 && session.delivery(all, EvidenceCatalog.empty()).equals(Set.of(document.id()))
                && session.delivery(range, EvidenceCatalog.empty()).equals(Set.of(document.id())),
                "exact and differently spelled covered requests resolve to the original source for an unseen recipient");
        require(session.delivery(range, catalog).isEmpty() && session.coverage().contains("REUSED (already collected; no repository read)"),
                "a recipient that already saw the covered source does not receive a duplicate alias");
        String mixed = all + "\nREQUEST_KNOWLEDGE|Unavailable policy|Check whether the behavior is required";
        session.load(mixed, "Read local behavior and policy");
        require(session.deliverDecisive("verification/R1-1", mixed, EvidenceCatalog.empty()).equals(Set.of(document.id()))
                && session.lastCoverage().contains("Unavailable evidence for this follow-up: REQUEST_KNOWLEDGE|unavailable policy"),
                "deep follow-up delivers decisive local evidence and records the unavailable premise");
        require(session.deliverDecisive("verification/R1-1", mixed, catalog).isEmpty()
                && session.deliverScoped("focused/R1-1", mixed, EvidenceCatalog.empty()).isEmpty(),
                "an unavailable request without new material stops, and scoped checks retain their full request gate");
        var scoped = new ScopedKnowledge(new InputDocument(Path.of(PATH), "hash", "# Main rules\nPrimary business knowledge."), Set.of(PATH));
        var protectedBundle = new EvidenceBundle(SCOPE, List.of("runner.py"), "", "", 0, scoped, catalog);
        var protectedSession = new EvidenceSession(protectedBundle, new SupplementalEvidence(git, new ProcessExecutor(), java.util.Map.of()), 0, 0);
        protectedSession.load(all, "Blocked direct knowledge read");
        require(protectedSession.delivery(all, EvidenceCatalog.empty()).isEmpty(), "file reuse cannot bypass scoped knowledge access");
        var rules = new ScopedKnowledge(new InputDocument(Path.of("knowledge.md"), "rules-hash",
                "# Behavior\nZebraHandshake must preserve the documented state.\n# Errors\nZebraHandshake must report a defined failure.\n"), Set.of("knowledge.md"));
        var ruleBundle = new EvidenceBundle(SCOPE, List.of("runner.py"), "", "", 20_000, rules, EvidenceCatalog.empty());
        String absent = "REQUEST_KNOWLEDGE|UnmatchedFjord|Find its rule";
        String matched = "REQUEST_KNOWLEDGE|ZebraHandshake|Inspect its rule";
        var collector = new SupplementalEvidence(git, new ProcessExecutor(), java.util.Map.of());
        var ruleResult = collector.collect(ruleBundle, absent + "\n" + matched, 0, 0);
        require(ruleResult.resolvedSources().get(new EvidenceRequest(1, absent).key()).isEmpty()
                && !ruleResult.resolvedSources().get(new EvidenceRequest(1, matched).key()).isEmpty(),
                "a no-match knowledge query cannot inherit the sections found for a different request in its batch");
        var oneSection = ruleResult.catalog().select(Set.of(ruleResult.catalog().sources().getFirst().id()));
        require(ruleResult.catalog().sources().size() == 2 && collector.reusable(new EvidenceBundle(SCOPE, List.of("runner.py"),
                        "", "", 0, rules, oneSection), matched).isEmpty(),
                "one visible rule section cannot satisfy a lookup whose matched sections have not all reached the recipient");
        Files.delete(root);
    }

    private static void sharedBatchBudget() throws Exception {
        Path repo = Files.createTempDirectory("evidence-batch-budget-");
        try {
            git(repo, "init", "-q");
            Files.writeString(repo.resolve("worker.py"), "# original implementation\n".repeat(35));
            Files.writeString(repo.resolve("caller.py"), "result = worker.submit(payload)\n");
            git(repo, "add", "--all");
            git(repo, "commit", "-qm", "base");
            String target = "# expanded implementation\n".repeat(105) + "result = record_delivery()\n";
            Files.writeString(repo.resolve("worker.py"), target);
            git(repo, "commit", "-qam", "target");
            var executor = new ProcessExecutor();
            var git = new GitClient(repo, executor);
            var scope = git.resolve(CliOptions.parse(new String[]{"review", "--repo", repo.toString()}));
            var collector = new SupplementalEvidence(git, executor, java.util.Map.of());
            String requested = """
                    REQUEST_FILE|base|worker.py|all|Inspect baseline behavior
                    REQUEST_FILE|target|worker.py|all|Inspect target behavior
                    REQUEST_FILE|target|caller.py|1:1|Inspect the call site
                    """;
            var evidence = new EvidenceBundle(scope, List.of("worker.py"), "", "", 4_800, null, EvidenceCatalog.empty());
            var result = collector.collect(evidence, requested, 3, 0);
            var supplied = result.catalog().sources();
            require(result.coverage().contains("Supplemental requests included: 3/3")
                    && result.coverage().contains("used remaining batch budget")
                    && supplied.stream().anyMatch(source -> source.path().equals("caller.py"))
                    && supplied.stream().anyMatch(source -> source.role(EvidenceSource.Role.BASELINE) && source.endOfFile())
                    && supplied.stream().anyMatch(source -> source.role(EvidenceSource.Role.TARGET)
                            && source.path().equals("worker.py") && source.endOfFile() && source.text().equals(target))
                    && EvidenceText.bytes(result.markdown()) <= evidence.supplementBudget(),
                    "the small caller and both pinned revisions fit a single batch without clipping a deferred file");

            var tight = new EvidenceBundle(scope, evidence.changedFiles(), "", "", 3_200, null, EvidenceCatalog.empty());
            var partial = collector.collect(tight, requested, 3, 0);
            require(partial.coverage().contains("Supplemental requests included: 2/3")
                    && partial.coverage().contains("target worker.py all: UNAVAILABLE")
                    && partial.catalog().sources().stream().anyMatch(source -> source.path().equals("caller.py"))
                    && partial.catalog().sources().stream().noneMatch(source -> source.path().equals("worker.py")
                            && source.role(EvidenceSource.Role.TARGET))
                    && EvidenceText.bytes(partial.markdown()) <= tight.supplementBudget(),
                    "a whole file that genuinely cannot fit leaves a gap and preserves other evidence");
        } finally {
            try (var paths = Files.walk(repo)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void constructorReferences() throws Exception {
        Path repo = Files.createTempDirectory("constructor-evidence-");
        try {
            git(repo, "init", "-q");
            Files.createDirectories(repo.resolve("src"));
            Files.writeString(repo.resolve("src/Worker.java"), """
                    class Worker {
                        Worker() { this(1); }
                        Worker(int capacity) { consume(capacity); }
                        void consume(int capacity) {}
                    }
                    """);
            Files.writeString(repo.resolve("src/ACallers.java"), ("var worker = new Worker(1);\n" + "\n".repeat(21)).repeat(7));
            Files.writeString(repo.resolve("src/ZCaller.java"), "var guarded = new Worker (5);\n");
            Files.writeString(repo.resolve("src/NearMatch.java"), "var other = new WorkerFactory();\n");
            Files.writeString(repo.resolve("Unrelated.java"), "var outside = new Worker(1);\n");
            git(repo, "add", "--all");
            git(repo, "commit", "-qm", "base");
            Files.writeString(repo.resolve("src/ZCaller.java"), "var guarded = new Worker (2);\n");
            git(repo, "commit", "-qam", "target");
            Files.writeString(repo.resolve("src/ZCaller.java"), "var dirty = new Worker (0);\n");
            var executor = new ProcessExecutor();
            var git = new GitClient(repo, executor);
            var scope = git.resolve(CliOptions.parse(new String[]{"review", "--repo", repo.toString()}));
            var evidence = new EvidenceBundle(scope, List.of("src/ZCaller.java"), "", "", 24_000, null, EvidenceCatalog.empty());
            var collector = new SupplementalEvidence(git, executor, java.util.Map.of());
            var session = new EvidenceSession(evidence, collector, 0, 0);
            String callers = "REQUEST_FILE|target|src/*.java|references:new  Worker(|Find production constructors";
            String decl = "REQUEST_FILE|target|src/Worker.java|symbol:Worker|Inspect constructor declarations";
            require(session.load(callers + "\n" + decl, "Candidate evidence requests"), "constructor declarations and callers share one evidence batch");
            var sources = session.catalog().sources();
            require(sources.stream().anyMatch(source -> source.path().equals("src/Worker.java")
                    && source.text().contains("Worker()") && source.text().contains("Worker(int capacity)"))
                    && sources.stream().anyMatch(source -> source.path().equals("src/ZCaller.java") && source.text().contains("guarded"))
                    && sources.stream().noneMatch(source -> source.text().contains("dirty") || source.path().equals("Unrelated.java")
                            || source.path().equals("src/NearMatch.java"))
                    && session.coverage().contains("partial search coverage"),
                    "reference excerpts cover distinct files before repeated hits, report limits, and use scoped pinned content");
            require(!session.load(callers.replace("new  Worker(", "new Worker"), "Equivalent request"),
                    "equivalent constructor searches do not reread or justify a fresh check");
        } finally {
            try (var paths = Files.walk(repo)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void pipelineDeliveryAndResume() throws Exception {
        Path root = Files.createTempDirectory("candidate-file-reuse-");
        Path repo = Files.createDirectory(root.resolve("repo"));
        git(repo, "init", "-q");
        Files.createDirectories(repo.resolve("docs"));
        Files.writeString(repo.resolve(PATH), BODY);
        Files.writeString(repo.resolve("alpha.py"), "def alpha(): return 0\n");
        Files.writeString(repo.resolve("beta.py"), "def beta(): return 0\n");
        git(repo, "add", "--all"); git(repo, "commit", "-qm", "base");
        Files.writeString(repo.resolve("alpha.py"), "def alpha(): return 1\n");
        Files.writeString(repo.resolve("beta.py"), "def beta(): return 1\n");
        git(repo, "add", "--all"); git(repo, "commit", "-qm", "target");
        var executor = new ProcessExecutor();
        var git = new GitClient(repo, executor);
        var options = CliOptions.parse(new String[]{"review", "--repo", repo.toString()});
        var evidence = new EvidenceCollector(git).collect(git.resolve(options), null, null, CliOptions.KnowledgeMode.FULL, 100_000);
        var batch = new SupplementalEvidence(git, executor, java.util.Map.of()).collect(evidence,
                "REQUEST_FILE|target|" + PATH + "|all|Read document\nREQUEST_FILE|target|" + PATH + "|1:200|Read its opening", 0, 0);
        var loaded = batch.catalog().sources().stream().filter(source -> source.path().equals(PATH)).toList();
        require(loaded.size() == 1 && loaded.getFirst().endOfFile() && batch.coverage().contains("REUSED")
                && batch.resolvedSources().values().stream().allMatch(ids -> ids.equals(Set.of(loaded.getFirst().id()))),
                "all and a covered range in one batch share a single native read and original source identity");
        for (String mode : List.of("all", "1:200", "repeat-after-delivery", "request-only", "unavailable", "repeat-unavailable", "unbound-quote")) {
            Path run = Files.createDirectory(root.resolve(mode.replace(':', '-')));
            boolean missingFile = mode.endsWith("unavailable");
            boolean requestOnly = mode.equals("request-only") || missingFile;
            boolean unboundQuote = mode.equals("unbound-quote");
            String selector = mode.equals("all") ? "all" : "1:200";
            String request = "REQUEST_FILE|target|" + (missingFile ? "missing.md" : PATH) + "|" + selector + "|Read the interface reference";
            var session = new EvidenceSession(evidence, new SupplementalEvidence(git, executor, java.util.Map.of()), 0, 0);
            session.load("REQUEST_FILE|target|" + PATH + "|all|Initial discovery read", "Discovery");
            var source = session.catalog().sources().stream().filter(s -> s.path().equals(PATH)).findFirst().orElseThrow();
            int sourcesBefore = session.catalog().sources().size();
            String resolved = mode.equals("repeat-after-delivery")
                    ? "<verification><status>INSUFFICIENT_EVIDENCE</status><reason>The policy remains undecided.</reason></verification>\n"
                        + "REQUEST_FILE|target|" + PATH + "|1:1|Ask a differently spelled covered range"
                    : "<verification><status>REJECTED</status><reason>The requested reference was examined.</reason></verification>";
            if (unboundQuote) {
                resolved = """
                    <verification><status>INSUFFICIENT_EVIDENCE</status><reason>The policy is unresolved.</reason>
                    <premises><premise><statement>Unsupported premise.</statement><basis>IMPLEMENTATION</basis>
                    <citations><citation><source>%s</source><quote>A made-up continuation of the document.</quote></citation></citations>
                    </premise></premises>
                    <citations><citation><source>%s</source><quote>INTERFACE_DOCUMENT_BODY: inspect this pinned reference before deciding.</quote></citation></citations>
                    </verification>
                    """.formatted(source.id(), source.id());
            }
            String pendingReply = requestOnly ? request : "<verification><status>INSUFFICIENT_EVIDENCE</status>"
                    + "<reason>Inspect the interface reference.</reason>"
                    + "<nextCheck>Does the pinned reference allow the change?</nextCheck></verification>\n" + request;
            String unavailableReply = mode.equals("repeat-unavailable") ? request
                    : "<verification><status>INSUFFICIENT_EVIDENCE</status><reason>The requested reference is unavailable.</reason></verification>";
            String script = """
                    prompt=$(cat)
                    case "$prompt" in
                      *'<path>alpha.py</path>'*)
                        printf x >> alpha-calls
                        case "$prompt" in
                          *INTERFACE_DOCUMENT_BODY*) cat <<'RESOLVED'
                    __RESOLVED_RESPONSE__
                    RESOLVED
                            ;;
                          *'The earlier request-only reply has been processed'*) cat <<'UNAVAILABLE'
                    __UNAVAILABLE_RESPONSE__
                    UNAVAILABLE
                            ;;
                          *) cat <<'PENDING'
                    __PENDING_RESPONSE__
                    PENDING
                            ;;
                        esac;;
                      *) printf x >> beta-calls; printf '<verification><status>INSUFFICIENT_EVIDENCE</status><reason>A different unresolved question.</reason></verification>';;
                    esac
                    """.replace("__PENDING_RESPONSE__", pendingReply).replace("__UNAVAILABLE_RESPONSE__", unavailableReply)
                    .replace("__RESOLVED_RESPONSE__", resolved);
            try (var progress = new CommandProgress("reuse", new java.io.PrintStream(java.io.OutputStream.nullOutputStream()))) {
                var findings = List.of(finding("R1-1", "alpha.py"), finding("R2-1", "beta.py"));
                var agent = new AgentRunner(executor, "/bin/sh", List.of("-c", script), run, Duration.ofSeconds(5));
                var quality = new ReviewQuality(options, evidence, session, new PromptFactory(), agent, run, progress, 9);
                var decisions = quality.verify(findings);
                require(Files.readString(run.resolve("alpha-calls")).equals(mode.equals("repeat-unavailable") || unboundQuote ? "xxx" : "xx")
                        && Files.readString(run.resolve("beta-calls")).equals("x"),
                        "a globally known source reaches its requester without rerunning an unrelated candidate or repeating already supplied coverage");
                String followup = Files.readString(run.resolve("verification/round-2/R1-1-prompt.md"));
                String numberedBody = EvidenceText.numberedLines(BODY.lines().toList());
                if (missingFile) {
                    require(followup.contains("The earlier request-only reply has been processed")
                            && !followup.contains(numberedBody)
                            && decisions.getFirst().verdict().status() == ReviewOutput.Status.INSUFFICIENT_EVIDENCE
                            && decisions.getFirst().verdict().validationFailed() == mode.equals("repeat-unavailable"),
                            "unavailable evidence gets one verdict-only follow-up, and request-only output cannot finish a review");
                    continue;
                }
                require(followup.contains(numberedBody) && followup.contains(source.id()) && !followup.contains("(lines 1–"),
                        "follow-up uses the existing immutable source instead of a new presentation-only range identity");
                require(session.catalog().sources().size() == sourcesBefore, "covered requests do not append duplicate global evidence");
                require(session.coverage().contains("Fulfilled requests from verification/R1-1: queued for this candidate: " + source.id()),
                        "coverage records which existing source was queued for the requesting candidate");
                var expected = mode.equals("repeat-after-delivery") || unboundQuote
                        ? ReviewOutput.Status.INSUFFICIENT_EVIDENCE : ReviewOutput.Status.REJECTED;
                require(decisions.getFirst().verdict().status() == expected, "evidence delivery never fabricates a semantic verdict");
                if (mode.equals("request-only"))
                    require(!Files.exists(run.resolve("verification/R1-1-repair-prompt.md"))
                            && !decisions.getFirst().verdict().validationFailed(),
                            "a valid standalone request uses the follow-up directly without a format call");
                if (unboundQuote) {
                    var verdict = decisions.getFirst();
                    Path repair = run.resolve("verification/round-2/R1-1-quote-repair-response.md");
                    require(!verdict.verdict().validationFailed() && verdict.citations().size() == 1
                            && verdict.support().premises().isEmpty()
                            && Files.readString(repair).contains("A made-up continuation")
                            && !Files.readString(repair.resolveSibling("R1-1-response.md")).contains("A made-up continuation"),
                            "the bounded source correction preserves raw output, then omits only unsupported evidence from the unresolved verdict");
                }
                if (mode.equals("all") || mode.equals("request-only")) {
                    var resumedSession = new EvidenceSession(evidence, new SupplementalEvidence(git, executor, java.util.Map.of()), 0, 0);
                    resumedSession.load("REQUEST_FILE|target|" + PATH + "|all|Initial discovery read", "Discovery");
                    var resumedOptions = CliOptions.parse(new String[]{"review", "--repo", repo.toString(), "--resume", run.toString()});
                    var unavailable = new AgentRunner(executor, "/bin/sh", List.of("-c", "exit 89"), run, Duration.ofSeconds(5));
                    var resumed = new ReviewQuality(resumedOptions, evidence, resumedSession, new PromptFactory(), unavailable, run, progress, 9);
                    require(resumed.verify(findings).getFirst().verdict().status() == expected,
                            "cached delivery and follow-up prompts are deterministic during resume with an unavailable backend");
                }
            }
        }
    }

    private static ReviewFinding finding(String id, String path) {
        return new ReviewFinding(id, "Interface question", ReviewFinding.Severity.MEDIUM, path, 1, 1,
                "A boundary input", "Unknown impact", "Read the relevant reference", "Changed return behavior");
    }
    private static void git(Path repo, String... args) throws Exception {
        var command = new ArrayList<>(List.of("git", "-c", "user.name=Evidence Test", "-c", "user.email=test@example.invalid"));
        command.addAll(List.of(args));
        var result = new ProcessExecutor().run(command, repo, null, Duration.ofSeconds(10));
        require(result.exitCode() == 0, "Synthetic Git fixture failed: " + result.stderr());
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
