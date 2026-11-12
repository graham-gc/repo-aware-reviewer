package reviewagent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;

final class ReviewQualityTests {
    static void run() throws Exception {
        Path fixture = Files.createTempDirectory("review-quality-test-");
        try {
            protocols(fixture);
            quotationRecovery(fixture);
            workflow(fixture);
        } finally {
            try (var paths = Files.walk(fixture)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void quotationRecovery(Path fixture) throws Exception {
        Path directory = Files.createDirectory(fixture.resolve("quotation-recovery"));
        String first = "应用与环境沿用现有配置。";
        String second = "一次调用只触发一次执行。";
        Path requirementPath = directory.resolve("requirements.md");
        String xmlExcerpt = "<plugin><groupId>example.group</groupId></plugin>";
        Files.writeString(requirementPath, first + "\n" + second + "\n" + xmlExcerpt);
        InputDocument requirement = InputDocument.read(requirementPath);
        String source = "External requirement — " + requirementPath;
        String valid = assessment(first, "INSUFFICIENT_EVIDENCE", citation(source, first), "Missing implementation")
                .replace("</assessment>", "")
                + assessment(second, "INSUFFICIENT_EVIDENCE", citation(source, second)
                    + citation(source, "<![CDATA[" + xmlExcerpt + "]]>"), "Missing implementation")
                    .replace("<assessment>", "").replace("<id>A1</id>", "<id>A2</id>");
        String invalid = valid.replace(first, first.replace("与", "和")).replace(second, "一次调用会触发两次执行。")
                .replace("<![CDATA[" + xmlExcerpt + "]]>", xmlExcerpt);
        Files.writeString(directory.resolve("valid.xml"), valid);
        Files.writeString(directory.resolve("invalid.xml"), invalid);
        var scope = new ReviewScope(ReviewScope.Mode.COMMIT, "base", "target", "base", "target", "base..target");
        String markdown = EvidenceText.block("External requirement — " + requirementPath, "markdown", requirement.content());
        var evidence = new EvidenceBundle(scope, List.of("Core.java"), markdown, "", 20_000, null);
        var index = new EvidenceIndex(evidence, "");
        var task = new ReviewTasks.Task("draft", "Assess the supplied requirements.\n" + markdown,
                response -> ReviewOutput.assessment(response, requirement, index, List.of()));
        String stub = """
                prompt=$(cat)
                printf 'call\\n' >> "$1/calls"
                case "$prompt" in
                  *'SOURCE QUOTATION CORRECTION'*) cat "$1/valid.xml" ;;
                  *'OUTPUT FORMAT CORRECTION'*) cat "$1/invalid.xml" ;;
                  *) printf '<assessment><item>broken XML' ;;
                esac
                """;
        var agent = new AgentRunner(new ProcessExecutor(), "/bin/sh", List.of("-c", stub, "quote-stub", directory.toString()),
                fixture, Duration.ofSeconds(5));
        Path artifacts = directory.resolve("artifacts");
        try (var progress = new CommandProgress("quote-test", new java.io.PrintStream(java.io.OutputStream.nullOutputStream()))) {
            var replies = ReviewTasks.run(List.of(task), agent, artifacts, 1, progress, 1, 2, "Quotation recovery", false);
            require(replies.get("draft").equals(valid), "quotation correction follows XML correction without accepting paraphrased quotes");
            String feedback = Files.readString(artifacts.resolve("draft-quote-repair-prompt.md"));
            require(feedback.contains("A1 <requirement>") && feedback.contains("A2 <requirement>")
                    && feedback.contains("A1 Citation must quote") && feedback.contains("CDATA"),
                    "source correction identifies every invalid requirement and citation quote in one response");
            require(Files.readAllLines(directory.resolve("calls")).size() == 3, "format and quotation each get one bounded correction");
            Files.delete(artifacts.resolve("draft-response.md"));
            var unavailable = new AgentRunner(new ProcessExecutor(), "/bin/sh", List.of("-c", "exit 99"), fixture, Duration.ofSeconds(5));
            ReviewTasks.run(List.of(task), unavailable, artifacts, 1, progress, 1, 2, "Resume quotation recovery", true);
            require(Files.readString(artifacts.resolve("draft-response.md")).equals(valid),
                    "a saved quotation correction is reused when the canonical response is missing");
            var changed = new ReviewTasks.Task("draft", task.prompt() + "\nChanged evidence", task.validator());
            fails(() -> ReviewTasks.run(List.of(changed), unavailable, artifacts, 1, progress, 1, 2, "Changed quotation evidence", true));
            require(Files.exists(artifacts.resolve("draft-superseded-quote-repair-response.md")),
                    "changed prompts invalidate saved source corrections");
        }
        String neverFixed = "cat >/dev/null; printf 'call\\n' >> \"$1/failed-calls\"; cat \"$1/invalid.xml\"";
        var failing = new AgentRunner(new ProcessExecutor(), "/bin/sh", List.of("-c", neverFixed, "failed-stub", directory.toString()),
                fixture, Duration.ofSeconds(5));
        try (var progress = new CommandProgress("quote-failure", new java.io.PrintStream(java.io.OutputStream.nullOutputStream()))) {
            fails(() -> ReviewTasks.run(List.of(task), failing, directory.resolve("failed"), 1, progress, 1, 2, "Invalid quotation", false));
        }
        require(Files.readAllLines(directory.resolve("failed-calls")).size() == 2,
                "unfixable source quotations still fail after one source correction rather than looping or relaxing validation");
    }

    private static void protocols(Path fixture) throws Exception {
        var scope = new ReviewScope(ReviewScope.Mode.COMMIT, "base", "target", "base", "target", "base..target");
        String code = "class Core { int value() { return Helper.parse() + 1; } }";
        String quote = "Null inputs must return a request error.";
        Path path = fixture.resolve("requirement.md");
        Files.writeString(path, quote);
        InputDocument requirement = InputDocument.read(path);
        String contractSource = "External requirement — " + path;
        String markdown = EvidenceText.block("Core.java", "diff", "new file mode 100644\n+" + code)
                + EvidenceText.block("Core.java at target", "text", code)
                + EvidenceText.block(contractSource, "markdown", quote);
        EvidenceBundle evidence = new EvidenceBundle(scope, List.of("Core.java"), markdown, "", 20_000, null);
        EvidenceIndex index = new EvidenceIndex(evidence, "");
        index.validate(List.of(new EvidenceIndex.Citation("Core.java [source]", code),
                new EvidenceIndex.Citation(contractSource + " [contract]", quote)));
        require(index.hasContract(List.of(new EvidenceIndex.Citation(contractSource + " [contract]", quote)))
                && index.hasImplementation(List.of(new EvidenceIndex.Citation("Core.java [source]", code))),
                "inventory display names resolve to their original evidence roles");
        fails(() -> index.validate(List.of(new EvidenceIndex.Citation("Core.java [contract]", code))));
        fails(() -> index.validate(List.of(new EvidenceIndex.Citation(contractSource + " [source]", quote))));
        fails(() -> index.validate(List.of(new EvidenceIndex.Citation("Invented.java [source]", code))));
        fails(() -> index.validate(List.of(new EvidenceIndex.Citation("Core.java [source]", "Invented implementation quotation."))));
        ReviewFinding candidate = finding("R1-1", "Failure");
        String confirmed = decision(candidate, "INTRODUCED", citation("Core.java", code));
        require(ReviewOutput.groundedVerification(confirmed, candidate, evidence, index).verdict().status()
                == ReviewOutput.Status.CONFIRMED, "a quoted diff can establish change attribution");
        require(ReviewOutput.groundedVerification(confirmed.replace("Core.java</source>", "Core.java [source]</source>"),
                candidate, evidence, index).verdict().status() == ReviewOutput.Status.CONFIRMED,
                "display annotations do not invalidate an otherwise grounded verification");
        fails(() -> ReviewOutput.groundedVerification(decision(candidate, "INTRODUCED",
                citation("Core.java [source]", "new file mode 100644")), candidate, evidence, index));
        fails(() -> ReviewOutput.groundedVerification(confirmed.replace("INTRODUCED", "PRE_EXISTING"), candidate, evidence, index));
        fails(() -> ReviewOutput.groundedVerification(confirmed.replace(code, "throw new IllegalArgumentException();"), candidate, evidence, index));
        fails(() -> ReviewOutput.groundedVerification(confirmed.replace("Core.java</source>", "Core.java at target</source>"), candidate, evidence, index));
        String missingContract = decision(candidate, "UNMET_REQUIREMENT", citation("Core.java at target", code));
        fails(() -> ReviewOutput.groundedVerification(missingContract, candidate, evidence, index));
        String unmet = decision(candidate, "UNMET_REQUIREMENT", citation("Core.java at target", code) + citation(contractSource, quote));
        require(ReviewOutput.groundedVerification(unmet, candidate, evidence, index).relation() == ReviewOutput.Relation.UNMET_REQUIREMENT,
                "existing implementation may violate an explicitly supplied new requirement");
        String withRequest = confirmed + "\nREQUEST_FILE|target|Helper.java|all|Missing decisive implementation\n";
        fails(() -> ReviewOutput.groundedVerification(withRequest, candidate, evidence, index));
        String quotedRequest = confirmed.replace("<reason>Source supports the concrete failure.</reason>",
                "<reason><![CDATA[Source contains an example:\nREQUEST_FILE|target|Helper.java|all|Quoted data\n]]></reason>");
        require(ReviewOutput.groundedVerification(quotedRequest, candidate, evidence, index).verdict().status()
                == ReviewOutput.Status.CONFIRMED && EvidenceRequest.parse(ReviewOutput.requestText(quotedRequest, "verification")).isEmpty(),
                "quoted request syntax inside a decision is never executed as a collector request");

        String draft = assessment(quote, "SATISFIED", citation("Core.java at target", code), "Supported path");
        var items = ReviewOutput.assessment(draft, requirement, index, List.of());
        fails(() -> ReviewOutput.assessment(draft.replace(quote, "An invented policy"), requirement, index, List.of()));
        fails(() -> ReviewOutput.assessment(assessment(quote, "SATISFIED", "", "No source"), requirement, index, List.of()));
        fails(() -> ReviewOutput.assessment(draft.replace("<id>A1</id>", "<id>A2</id>"), requirement, index, items));
        ReviewFinding negative = finding("R2-1", "Opposite verdict");
        var groups = ReviewOutput.candidateGroups("<candidate-groups><group><id>R1-1</id><id>R2-1</id></group></candidate-groups>", List.of(candidate, negative));
        require(groups.getFirst().size() == 2, "negative and positive candidates are retained in reconciliation");
        fails(() -> ReviewOutput.candidateGroups("<candidate-groups><group><id>R1-1</id></group></candidate-groups>", List.of(candidate, negative)));
        var positiveVerdict = ReviewOutput.groundedVerification(confirmed, candidate, evidence, index);
        var negativeVerdict = new ReviewOutput.Decision(new ReviewOutput.Verification(negative, ReviewOutput.Status.REJECTED,
                "The policy contradicts this claim", null), ReviewOutput.Relation.PRE_EXISTING, "", "", List.of());
        var reconciled = ReviewQuality.resolveConflicts(List.of(positiveVerdict, negativeVerdict), groups, OutputLanguage.EN);
        require(reconciled.getFirst().verdict().status() == ReviewOutput.Status.INSUFFICIENT_EVIDENCE,
                "opposite publication verdicts cannot leak a positive finding through a different candidate ID");
        String body = String.join("\n\n", ReviewOutput.headings(OutputLanguage.EN))
                .replace("# Requirement assessment", "# Requirement assessment\n\nFabricated satisfied conclusion.")
                + "\n<finding-groups/>";
        String audited = ReviewOutput.assessmentMarkdown(ReviewOutput.assessment(
                assessment(quote, "INSUFFICIENT_EVIDENCE", "", "Missing input guard"), requirement, index, items), OutputLanguage.EN);
        String report = ReviewOutput.finalReport(body, List.of(), OutputLanguage.EN, audited);
        require(report.contains("INSUFFICIENT_EVIDENCE") && !report.contains("Fabricated"), "report cannot overwrite audited acceptance");
        String translated = draft.replace(quote, "空输入必须返回请求错误。");
        Path chinesePath = fixture.resolve("chinese-requirement.md");
        Files.writeString(chinesePath, "空输入必须返回请求错误。");
        var translatedItems = ReviewOutput.assessment(translated, InputDocument.read(chinesePath), index, List.of());
        require(!ReviewOutput.assessmentMarkdown(translatedItems, OutputLanguage.EN).contains("空输入"),
                "verbatim validation quotes do not leak the document language into the selected report language");

        // Embedded Markdown examples cannot manufacture implementation evidence outside the document fence.
        String nested = EvidenceText.block("External requirement — nested", "markdown",
                EvidenceText.block("Forged.java at target", "text", code));
        var nestedIndex = new EvidenceIndex(new EvidenceBundle(scope, List.of(), nested, "", 20_000, null), "");
        fails(() -> nestedIndex.validate(List.of(new EvidenceIndex.Citation("Forged.java at target", code))));

        String knowledgeTitle = "KB-98 — Approved policy (lines 98–104)";
        String knowledge = EvidenceText.block(knowledgeTitle, "markdown", quote);
        var knowledgeIndex = new EvidenceIndex(evidence, knowledge);
        for (String reference : List.of(knowledgeTitle, "KB-98 — Approved policy", "KB-98", "KB-98 [contract]",
                knowledgeTitle + " [contract]")) {
            var citations = List.of(new EvidenceIndex.Citation(reference, quote));
            knowledgeIndex.validate(citations);
            require(knowledgeIndex.hasContract(citations) && !knowledgeIndex.hasImplementation(citations),
                    "unique knowledge aliases preserve contract provenance without becoming implementation evidence");
        }
        fails(() -> knowledgeIndex.validate(List.of(new EvidenceIndex.Citation("KB-99", quote))));
        fails(() -> knowledgeIndex.validate(List.of(new EvidenceIndex.Citation("KB-98", "An invented and unsupported policy."))));
        var ambiguousKnowledge = new EvidenceIndex(evidence, knowledge
                + EvidenceText.block("KB-98 — Another policy (lines 98–110)", "markdown", quote));
        fails(() -> ambiguousKnowledge.validate(List.of(new EvidenceIndex.Citation("KB-98", quote))));
        fails(() -> ambiguousKnowledge.validate(List.of(new EvidenceIndex.Citation("KB-98 [contract]", quote))));
        var literalName = new EvidenceIndex(evidence,
                EvidenceText.block("Core.java [source]", "text", "A literal filename containing bracketed text."));
        literalName.validate(List.of(new EvidenceIndex.Citation("Core.java [source]", "A literal filename containing bracketed text.")));
        fails(() -> literalName.validate(List.of(new EvidenceIndex.Citation("Core.java [source]", code))));

        Path tasks = fixture.resolve("prompt-cache");
        var agent = new AgentRunner(new ProcessExecutor(), "/bin/sh", List.of("-c", "cat"), fixture, Duration.ofSeconds(5));
        try (var progress = new CommandProgress("cache-test", new java.io.PrintStream(java.io.OutputStream.nullOutputStream()))) {
            ReviewTasks.run(List.of(new ReviewTasks.Task("same", "Original prompt", response -> {})), agent, tasks, 1, progress, 1, 2, "Original", false);
            ReviewTasks.run(List.of(new ReviewTasks.Task("same", "Changed evidence", response -> {})), agent, tasks, 1, progress, 1, 2, "Changed", true);
        }
        require(Files.readString(tasks.resolve("same-response.md")).equals("Changed evidence"), "changed evidence invalidates otherwise valid saved replies");
        var failedAgent = new AgentRunner(new ProcessExecutor(), "/bin/sh", List.of("-c", "exit 9"), fixture, Duration.ofSeconds(5));
        try (var progress = new CommandProgress("cache-failure", new java.io.PrintStream(java.io.OutputStream.nullOutputStream()))) {
            fails(() -> ReviewTasks.run(List.of(new ReviewTasks.Task("same", "Third version", response -> {})), failedAgent,
                    tasks, 1, progress, 1, 2, "Interrupted changed task", true));
            ReviewTasks.run(List.of(new ReviewTasks.Task("same", "Third version", response -> {})), agent,
                    tasks, 1, progress, 1, 2, "Retry changed task", true);
        }
        require(Files.readString(tasks.resolve("same-response.md")).equals("Third version"),
                "an interrupted changed prompt cannot make a previous response reusable on the next resume");
    }

    private static void workflow(Path fixture) throws Exception {
        Path repo = Files.createDirectory(fixture.resolve("repo"));
        git(repo, "init", "-q");
        Files.writeString(repo.resolve("Core.java"), "class Core { int value() { return Helper.parse(); } }\n");
        Files.writeString(repo.resolve("Helper.java"), "class Helper { static int parse() { return Limits.limit(); } }\n");
        Files.writeString(repo.resolve("Limits.java"), "class Limits { static int limit() { return 0; } }\n");
        git(repo, "add", "--all");
        git(repo, "commit", "-qm", "base");
        String code = "class Core { int value() { return Helper.parse() + 1; } }";
        Files.writeString(repo.resolve("Core.java"), code + "\n");
        git(repo, "add", "--all");
        git(repo, "commit", "-qm", "target");
        Path requirementPath = fixture.resolve("business.md");
        String requirement = "Null inputs must return a request error.";
        Files.writeString(requirementPath, requirement);

        String disputed = ReviewOutput.findingXml(finding("unused", "Disputed policy"));
        String deep = ReviewOutput.findingXml(finding("unused", "Deep failure"));
        String existing = ReviewOutput.findingXml(finding("unused", "Existing acceptance gap"));
        String positive = decision(finding("ID", "TITLE"), "INTRODUCED", citation("Core.java", code)
                + citation("Core.java", "class Core { int value() { return Helper.parse(); } }"));
        String explicit = decision(finding("R3-1", "Existing acceptance gap"), "UNMET_REQUIREMENT",
                citation("Core.java", code) + citation("External requirement — " + requirementPath, requirement));
        String draft = assessment(requirement, "SATISFIED", citation("Core.java", code), "Draft overlooked a boundary");
        String audited = assessment(requirement, "INSUFFICIENT_EVIDENCE", "", "Missing null-input handler evidence");
        String stub = """
                set -eu
                prompt=$(cat)
                case "$prompt" in
                  'You are the discovery stage'*)
                    printf '%s\\n' '<findings>'
                    case "$prompt" in
                      *'Correctness and regressions.'*) printf '%s\\n' 'DISPUTED' 'DEEP' ;;
                      *'Requirements and business contracts.'*) printf '%s\\n' 'DISPUTED' ;;
                      *) printf '%s\\n' 'EXISTING' ;;
                    esac
                    printf '%s\\n' '</findings>' ;;
                  'You are the independent candidate verification'*|'You are the independent publication adjudication'*)
                    candidate=$(printf '%s\\n' "$prompt" | sed -n '/^CANDIDATE TO VERIFY$/{n;p;}')
                    case "$candidate" in
                      *'id="R2-1"'*) printf '%s\\n' '<verification><status>REJECTED</status><reason>No explicit policy supports this claim.</reason></verification>' ;;
                      *'id="R1-1"'*)
                        case "$prompt" in
                          'You are the independent publication adjudication'*)
                            case "$prompt" in *'No explicit policy supports this claim.'*) ;; *) exit 71 ;; esac
                            printf '%s\\n' '<verification><status>INSUFFICIENT_EVIDENCE</status><reason>Opposite verdict resolved: policy is unspecified.</reason></verification>' ;;
                          *) printf '%s\\n' 'POSITIVE' | sed 's/ID/R1-1/g;s/TITLE/Disputed policy/g' ;;
                        esac ;;
                      *'id="R1-2"'*)
                        case "$prompt" in
                          *'class Limits { static int limit() { return 0; } }'*)
                            printf '%s\\n' 'POSITIVE' | sed 's/ID/R1-2/g;s/TITLE/Deep failure/g' ;;
                          *'class Helper { static int parse() { return Limits.limit(); } }'*)
                            printf '%s\\n' '<verification><status>INSUFFICIENT_EVIDENCE</status><reason>Need the transitive method.</reason></verification>' \\
                              'REQUEST_FILE|target|Limits.java|symbol:limit|Trace the next decisive method' ;;
                          *) printf '%s\\n' '<verification><status>INSUFFICIENT_EVIDENCE</status><reason>Need the called implementation.</reason></verification>' \\
                              'REQUEST_FILE|target|Helper.java|symbol:parse|Read the missing implementation' ;;
                        esac ;;
                      *) printf '%s\\n' 'EXPLICIT' ;;
                    esac ;;
                  'You are the candidate reconciliation'*)
                    printf '%s\\n' '<candidate-groups><group><id>R1-1</id><id>R2-1</id></group><group><id>R1-2</id></group><group><id>R3-1</id></group></candidate-groups>' ;;
                  'You are the requirement assessment stage'*) printf '%s\\n' 'DRAFT' ;;
                  'You are the independent requirement assessment auditor'*)
                    printf '%s\\n' 'AUDITED' 'REQUEST_FILE|target|NoHandler.java|all|Find null-input error mapping' ;;
                  'You are the report consolidation'*)
                    printf '%s\\n' '# Review scope' '# Functional and business change' '# Findings' '[VERIFIED_FINDINGS]' \\
                      '# Requirement assessment' 'Fabricated satisfied conclusion.' '# Knowledge-base feedback' '# Verification limits' \\
                      '<finding-groups><group><id>R1-2</id></group><group><id>R3-1</id></group></finding-groups>' ;;
                  *) exit 72 ;;
                esac
                """.replace("DISPUTED", disputed).replace("DEEP", deep).replace("EXISTING", existing)
                .replace("POSITIVE", positive).replace("EXPLICIT", explicit).replace("DRAFT", draft).replace("AUDITED", audited);
        CliOptions options = CliOptions.parse(new String[]{"review", "--repo", repo.toString(), "--requirement", requirementPath.toString(),
                "--output-dir", fixture.resolve("output").toString(), "--agent-command", "/bin/sh", "--agent-arg", "-c", "--agent-arg", stub});
        Path run = new ReviewPipeline(new ProcessExecutor(), Clock.systemUTC()).run(options);
        String report = Files.readString(run.resolve("report.md"));
        require(report.contains("Deep failure") && report.contains("Existing acceptance gap") && !report.contains("Disputed policy"),
                "adjudication suppresses the contradicted confirmation and preserves explicit requirement violations");
        require(Files.exists(run.resolve("verification/round-3/R1-2-response.md")), "verification follows two missing calls across successive rounds");
        require(report.contains("INSUFFICIENT_EVIDENCE") && !report.contains("Fabricated"), "independent requirement audit owns the final acceptance section");
        String coverage = Files.readString(run.resolve("coverage.md"));
        require(coverage.contains("Opposite verdict resolved") && coverage.contains("NoHandler.java all: UNAVAILABLE"), "conflicts and unavailable audit evidence remain visible");
        String supplemental = Files.readString(run.resolve("supplemental-evidence.md"));
        require(supplemental.contains("return Limits.limit();") && supplemental.contains("return 0;"), "deep implementation evidence is retained");
        CliOptions resume = CliOptions.parse(new String[]{"review", "--repo", repo.toString(), "--requirement", requirementPath.toString(),
                "--resume", run.toString(), "--agent-command", "/bin/sh", "--agent-arg", "-c", "--agent-arg", "exit 98"});
        new ReviewPipeline(new ProcessExecutor(), Clock.systemUTC()).run(resume);
        require(Files.readString(run.resolve("report.md")).equals(report), "multi-round evidence and audited decisions replay deterministically on resume");

        GitClient client = new GitClient(repo, new ProcessExecutor());
        EvidenceBundle evidence = new EvidenceCollector(client).collect(client.resolve(options), null, null, CliOptions.KnowledgeMode.FULL, 20_000);
        EvidenceSession limited = new EvidenceSession(evidence,
                new SupplementalEvidence(client, new ProcessExecutor(), java.util.Map.of("java", fixture.resolve("cache"))), 1, 0);
        require(limited.load("REQUEST_FILE|target|Helper.java|symbol:parse|First call", "first"), "first finite request supplies material");
        require(!limited.load("REQUEST_FILE|target|Limits.java|symbol:limit|Second call", "second")
                && !limited.markdown().contains("return 0;"), "finite request counts are shared across rounds");
        require(!limited.load("REQUEST_FILE|target|Helper.java|symbol:parse|Different reason", "repeat"), "the same request is never retried under a new reason");
        require(EvidenceText.bytes(limited.markdown()) <= evidence.supplementBudget(), "all rounds share one byte budget");
    }

    private static ReviewFinding finding(String id, String title) {
        return new ReviewFinding(id, title, ReviewFinding.Severity.MEDIUM, "Core.java", 1, 1,
                "Reachable input", "Observable failure", "Core.java:1 shows the changed path", "This change affects the operation");
    }

    private static String decision(ReviewFinding finding, String relation, String citations) {
        return "<verification><status>CONFIRMED</status><reason>Source supports the concrete failure.</reason><relation>" + relation
                + "</relation><baseline>Compared the base and target behavior.</baseline><contract>Explicit contract or intrinsic correctness invariant.</contract>"
                + "<citations>" + citations + "</citations>" + ReviewOutput.findingXml(finding) + "</verification>";
    }

    private static String citation(String source, String quote) {
        return "<citation><source>" + source + "</source><quote>" + quote + "</quote></citation>";
    }

    private static String assessment(String requirement, String status, String citations, String gap) {
        return "<assessment><item><id>A1</id><requirement>" + requirement + "</requirement><requirementText>Input error contract</requirementText><behavior>Input error contract</behavior><status>"
                + status + "</status><reasoning>Trace the API error path.</reasoning><boundaries>Null and malformed values need separate checks.</boundaries><gap>"
                + gap + "</gap><citations>" + citations + "</citations></item></assessment>";
    }

    private static void git(Path repo, String... arguments) throws Exception {
        var command = new java.util.ArrayList<String>(List.of("git", "-c", "user.name=Quality Test", "-c", "user.email=test@example.invalid"));
        command.addAll(List.of(arguments));
        CommandResult result = new ProcessExecutor().run(command, repo, null, Duration.ofSeconds(10));
        require(result.exitCode() == 0, "fixture git command: " + result.stderr());
    }

    private static void fails(Check check) throws Exception {
        try { check.run(); throw new AssertionError("Expected invalid quality output to be rejected"); }
        catch (IOException expected) { }
    }
    @FunctionalInterface private interface Check { void run() throws Exception; }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
