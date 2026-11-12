package reviewagent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class EvidenceTests {
    static void run() throws Exception {
        Path fixture = Files.createTempDirectory("review-evidence-test-");
        try {
            checkEvidence(fixture);
            checkMultiAgentReview(fixture);
            checkRequestLimits(fixture);
            checkKnowledgeSnapshot(fixture);
            checkScopedKnowledgeReview(fixture);
        } finally {
            try (var paths = Files.walk(fixture)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    private static void checkEvidence(Path fixture) throws Exception {
        Path repo = Files.createDirectory(fixture.resolve("repo"));
        var executor = new ProcessExecutor();
        git(executor, repo, "init", "-q");
        Files.writeString(repo.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <properties><contracts.version>1.2</contracts.version></properties>
                  <dependencies><dependency>
                    <groupId>test.contract</groupId><artifactId>schema</artifactId>
                    <version>${contracts.version}</version>
                  </dependency></dependencies>
                </project>
                """);
        Files.writeString(repo.resolve("A-large.md"), "old\n");
        Files.writeString(repo.resolve("Stable.java"), """
                class Stable {
                    int evidence = 42;
                    int caller() { return parseContract(); }
                    // int parseContract() { return -1; }
                """ + "\n".repeat(230) + """
                    @Deprecated
                    int parseContract() {
                        return 73;
                    }
                    int overloaded() { return 1; }
                    int overloaded(int value) { return value; }
                }
                """);
        Files.writeString(repo.resolve("old name.md"), "Rename evidence\n");
        Files.writeString(repo.resolve("removed.txt"), "Deletion evidence\n");
        git(executor, repo, "add", "--all");
        git(executor, repo, "commit", "-qm", "base");
        Files.writeString(repo.resolve("A-large.md"), "A large change that consumes the patch budget.\n".repeat(400));
        Files.writeString(repo.resolve("Z-small.java"), "class ZSmall { int change = 7; }\n");
        Files.move(repo.resolve("old name.md"), repo.resolve("new name.md"));
        Files.delete(repo.resolve("removed.txt"));
        git(executor, repo, "add", "--all");
        git(executor, repo, "commit", "-qm", "target");

        var git = new GitClient(repo, executor);
        var options = CliOptions.parse(new String[]{"review", "--repo", repo.toString(), "--prepare-only"});
        ReviewScope scope = git.resolve(options);
        EvidenceBundle evidence = new EvidenceCollector(git).collect(scope, null, null, options.knowledgeMode(), 10_000);
        require(evidence.coverage().contains("A-large.md — diff: PARTIAL"), "large patch coverage");
        require(evidence.coverage().contains("Z-small.java — diff: INCLUDED"), "small patch after large patch");
        require(evidence.coverage().contains("removed.txt — diff: INCLUDED; target file: NOT APPLICABLE"), "deletion coverage");
        require(evidence.markdown().contains("rename from old name.md"), "rename evidence");

        Path cache = fixture.resolve("cache");
        Path artifact = Files.createDirectories(cache.resolve("test/contract/schema/1.2")).resolve("schema-1.2.jar");
        try (var archive = new ZipOutputStream(Files.newOutputStream(artifact))) {
            archive.putNextEntry(new ZipEntry("contracts/order.proto"));
            archive.write("message Order { string id = 1; }\n".getBytes(StandardCharsets.UTF_8));
            archive.closeEntry();
        }
        var supplemental = new SupplementalEvidence(git, executor, java.util.Map.of("java", cache)).collect(evidence, """
                REQUEST_FILE|base|Stable.java|1:2|Find a call contract
                REQUEST_MAVEN|target|test.contract:schema:1.2|contracts/order.proto|Check the field number
                REQUEST_MAVEN|target|test.contract:schema:1.3|contracts/order.proto|Check a nonexistent version
                REQUEST_FILE|target|../outside.txt|all|Check path restrictions
                """, 0, 0);
        require(supplemental.markdown().contains("evidence = 42"), "baseline source");
        require(supplemental.markdown().contains("message Order") && supplemental.markdown().contains("JAR SHA-256"),
                "precise cached proto provenance");
        require(supplemental.coverage().contains("schema:1.3 contracts/order.proto: UNAVAILABLE"), "undeclared version");
        require(supplemental.coverage().contains("../outside.txt all: UNAVAILABLE"), "unsafe path");
        require(EvidenceText.bytes(evidence.markdown() + supplemental.markdown()) <= 10_000, "combined evidence budget");

        var methods = new SupplementalEvidence(git, executor, java.util.Map.of("java", cache)).collect(evidence, """
                REQUEST_FILE|base|Stable.java|symbol:parseContract|Trace the methodREQUEST_FILE|target|Stable.java|symbol:overloaded|Check ambiguity
                REQUEST_FILE|target|Stable.java|symbol:missing|Check unavailable declarations
                """, 0, 0);
        require(methods.markdown().contains("@Deprecated\n    int parseContract() {\n        return 73;"),
                "method selector finds the declaration after line 200, including its body and annotation");
        require(!methods.markdown().contains("return parseContract();"), "method selector excludes the earlier call site");
        require(methods.coverage().contains("symbol:parseContract: INCLUDED (lines 235–238)"), "method source lines recorded");
        require(methods.coverage().contains("multiple declarations"), "ambiguous method request reported as unavailable");
        require(methods.coverage().contains("Java method declaration not found"), "missing method reported as unavailable");
    }

    private static void checkKnowledgeSnapshot(Path fixture) throws Exception {
        Path repo = Files.createDirectory(fixture.resolve("knowledge repo"));
        var executor = new ProcessExecutor();
        git(executor, repo, "init", "-q");
        git(executor, repo, "config", "core.quotePath", "true");
        Files.writeString(repo.resolve("需求说明.md"), "Confirmed requirements\n");
        Files.write(repo.resolve("binary.md"), new byte[]{0, 1, 2});
        git(executor, repo, "add", "--all");
        git(executor, repo, "commit", "-qm", "initial snapshot");

        var git = new GitClient(repo, executor);
        String missingCommit = "1".repeat(git.resolveCommit("HEAD").length());
        git(executor, repo, "update-index", "--add", "--cacheinfo", "160000," + missingCommit + ",module");
        git(executor, repo, "commit", "-qm", "record a submodule pointer");

        KnowledgeSnapshot snapshot = new RepositorySnapshotCollector(git).collect("HEAD", 20_000, 20_000, 10_000);
        require(snapshot.files().size() == 3, "inventory includes all three tree entries");
        require(snapshot.chunks().size() == 1
                && snapshot.chunks().getFirst().contains("===== FILE: 需求说明.md =====\nConfirmed requirements"),
                "non-ASCII tracked file is included with its original name");
        require(snapshot.files().stream().anyMatch(file -> file.path().equals("module")
                && file.status() == KnowledgeFile.Status.SKIPPED_NON_REGULAR), "submodule recorded without loading it");
        require(snapshot.files().stream().anyMatch(file -> file.path().equals("binary.md")
                && file.status() == KnowledgeFile.Status.SKIPPED_BINARY_OR_GENERATED), "binary with text extension skipped");
    }

    private static void checkMultiAgentReview(Path fixture) throws Exception {
        Path repo = fixture.resolve("repo");
        Path signals = Files.createDirectory(fixture.resolve("parallel-signals"));
        var finding = new ReviewFinding("unused", "Confirmed defect", ReviewFinding.Severity.HIGH, "Z-small.java", 1, 1,
                "A concrete input", "The operation fails", "Z-small.java:1 shows the failure", "The diff introduces the failing operation");
        String first = ReviewOutput.findingXml(finding);
        String duplicate = first.replace("Confirmed defect", "Duplicate defect").replace("HIGH", "MEDIUM");
        String rejected = first.replace("Confirmed defect", "Rejected defect");
        String insufficient = first.replace("Confirmed defect", "Unproven defect");
        String separate = first.replace("Confirmed defect", "Independent defect").replace("HIGH", "LOW");
        String stub = """
                set -eu
                prompt=$(cat)
                case "$prompt" in
                    'You are the discovery stage'*)
                        touch "$1/ready-$$"
                        attempt=0
                        while [ "$(find "$1" -name 'ready-*' -type f | wc -l)" -lt 2 ]; do
                            attempt=$((attempt + 1))
                            [ "$attempt" -lt 100 ] || exit 32
                            sleep 0.05
                        done
                        printf '%s\\n' '```xml' '<findings>'
                        case "$prompt" in
                            *'Correctness and regressions.'*) printf '%s\\n' 'FIRST_XML' 'REJECTED_XML' ;;
                            *'Requirements and business contracts.'*) printf '%s\\n' 'DUPLICATE_XML' 'INSUFFICIENT_XML' ;;
                            *) printf '%s\\n' 'SEPARATE_XML' ;;
                        esac
                        printf '%s\\n' '</findings>' '```'
                        ;;
                    'You are the independent candidate verification'*|'You are the independent publication adjudication'*)
                        case "$prompt" in
                            *'<finding id="R1-2">'*)
                                printf '%s\\n' '```xml' '<verification><status>REJECTED</status><reason>An existing guard disproves the candidate.</reason></verification>' '```'
                                ;;
                            *'<finding id="R2-2">'*)
                                printf '%s\\n' '```xml' '<verification><status>INSUFFICIENT_EVIDENCE</status><reason>The required library semantics are unavailable.</reason></verification>' '```'
                                ;;
                            *)
                                finding=$(printf '%s\\n' "$prompt" | sed -n '/^CANDIDATE TO VERIFY$/{n;p;}')
                                printf '%s\\n' '```xml' '<verification><status>CONFIRMED</status><reason>Code supports the trigger and impact.</reason>' 'GROUNDING_XML' "$finding" '</verification>' '```'
                                ;;
                        esac
                        ;;
                    'You are the candidate reconciliation'*)
                        printf '%s\\n' '<candidate-groups><group><id>R1-1</id><id>R2-1</id></group><group><id>R1-2</id></group><group><id>R2-2</id></group><group><id>R3-1</id></group></candidate-groups>'
                        ;;
                    'You are the report consolidation'*)
                        printf '%s\\n' '# Review scope' '# Functional and business change' '# Findings' \
                            'Fabricated additional finding.' '# Requirement assessment' '# Knowledge-base feedback' '# Verification limits' \
                            '```xml' '<finding-groups><group><id>R1-1</id><id>R2-1</id></group><group><id>R3-1</id></group></finding-groups>' '```'
                        ;;
                    *) exit 33 ;;
                esac
                """.replace("FIRST_XML", first).replace("REJECTED_XML", rejected).replace("DUPLICATE_XML", duplicate)
                .replace("INSUFFICIENT_XML", insufficient).replace("SEPARATE_XML", separate)
                .replace("GROUNDING_XML", grounding("Z-small.java", "class ZSmall { int change = 7; }"));
        CliOptions options = CliOptions.parse(new String[]{"review", "--repo", repo.toString(), "--review-parallelism", "2",
                "--output-dir", fixture.resolve("multi-review").toString(), "--agent-command", "/bin/sh", "--agent-arg", "-c",
                "--agent-arg", stub, "--agent-arg", "multi-review-stub", "--agent-arg", signals.toString()});
        Path run = new ReviewPipeline(new ProcessExecutor(), Clock.systemUTC()).run(options);
        String report = Files.readString(run.resolve("report.md"));
        String coverage = Files.readString(run.resolve("coverage.md"));
        require(report.contains("[HIGH] Confirmed defect") && report.contains("[LOW] Independent defect"), "confirmed independent defects retained");
        require(!report.contains("Duplicate defect") && !report.contains("Rejected defect") && !report.contains("Unproven defect")
                && !report.contains("Fabricated"), "unverified issues and duplicate reports are excluded from final findings");
        require(coverage.contains("R1-2 — REJECTED") && coverage.contains("R2-2 — INSUFFICIENT_EVIDENCE")
                && coverage.contains("Candidates: 5. Confirmed before deduplication: 3."), "individual verification outcomes are recorded");
        require(Files.exists(run.resolve("discovery/R3-response.md")) && Files.exists(run.resolve("verification/R3-1-response.md")),
                "independent reviews and individual verification artifacts are saved");

        CliOptions cached = CliOptions.parse(new String[]{"review", "--repo", repo.toString(), "--resume", run.toString(),
                "--review-parallelism", "1", "--agent-command", "/bin/sh", "--agent-arg", "-c", "--agent-arg", "exit 99"});
        Path resumed = new ReviewPipeline(new ProcessExecutor(), Clock.systemUTC()).run(cached);
        require(resumed.equals(run) && Files.readString(run.resolve("report.md")).equals(report),
                "resume reuses every valid stage without another model call and allows concurrency changes");
        Path savedReply = fixture.resolve("saved-verification.md");
        Files.copy(run.resolve("verification/R3-1-response.md"), savedReply);
        Files.delete(run.resolve("verification/R3-1-response.md"));
        CliOptions incomplete = CliOptions.parse(new String[]{"review", "--repo", repo.toString(), "--resume", run.toString(),
                "--agent-command", "/bin/sh", "--agent-arg", "-c", "--agent-arg", "cat \"$1\"",
                "--agent-arg", "resume-stub", "--agent-arg", savedReply.toString()});
        new ReviewPipeline(new ProcessExecutor(), Clock.systemUTC()).run(incomplete);
        require(Files.readString(run.resolve("report.md")).equals(report), "resume invokes only the missing verification task");
        String savedScope = Files.readString(run.resolve("scope.md"));
        try {
            CliOptions changedInput = CliOptions.parse(new String[]{"review", "--repo", repo.toString(), "--resume", run.toString(),
                    "--language", "zh-CN", "--agent-command", "/bin/sh", "--agent-arg", "-c", "--agent-arg", "exit 99"});
            new ReviewPipeline(new ProcessExecutor(), Clock.systemUTC()).run(changedInput);
            throw new AssertionError("Expected changed resume inputs to be rejected");
        } catch (java.io.IOException mismatch) {
            require(mismatch.getMessage().contains("scope.md"), "resume diagnoses changed review inputs");
        }
        require(Files.readString(run.resolve("scope.md")).equals(savedScope), "rejected resume preserves the saved scope");

        Path repairDirectory = Files.createDirectory(fixture.resolve("format-repair"));
        String repairStub = """
                prompt=$(cat)
                printf 'call\\n' >> "$1/model-calls.txt"
                case "$prompt" in
                    *'OUTPUT FORMAT CORRECTION'*) cat "$2" ;;
                    *) printf 'Verification completed without structured output.\\n' ;;
                esac
                """;
        var repairAgent = new AgentRunner(new ProcessExecutor(), "/bin/sh",
                List.of("-c", repairStub, "repair-stub", repairDirectory.toString(), savedReply.toString()), fixture, Duration.ofSeconds(5));
        ReviewFinding repairCandidate = ReviewOutput.discovery("<findings>" + separate + "</findings>",
                "R3", List.of("Z-small.java")).findings().getFirst();
        var repairTask = new ReviewTasks.Task("repair", "Verify the candidate",
                reply -> ReviewOutput.verification(reply, repairCandidate, List.of("Z-small.java")));
        try (var progress = new CommandProgress("repair-test", new java.io.PrintStream(java.io.OutputStream.nullOutputStream()))) {
            ReviewTasks.run(List.of(repairTask), repairAgent, repairDirectory, 1, progress, 1, 2, "Repairing structured output", false);
            ReviewTasks.run(List.of(repairTask), repairAgent, repairDirectory, 1, progress, 1, 2, "Reusing repaired output", true);
        }
        require(Files.readAllLines(repairDirectory.resolve("model-calls.txt")).size() == 2
                && Files.exists(repairDirectory.resolve("repair-invalid-response.md"))
                && Files.exists(repairDirectory.resolve("repair-repair-response.md")),
                "one format correction is saved and its valid result is reused without additional model calls");

        Files.writeString(repairDirectory.resolve("repair-response.md"), "<invoke>invalid tool-call output</invoke>");
        String savedRepair = Files.readString(repairDirectory.resolve("repair-repair-response.md"));
        var unavailableAgent = new AgentRunner(new ProcessExecutor(), "/bin/sh", List.of("-c", "exit 99"),
                fixture, Duration.ofSeconds(5));
        try (var progress = new CommandProgress("repair-resume-test", new java.io.PrintStream(java.io.OutputStream.nullOutputStream()))) {
            ReviewTasks.run(List.of(repairTask), unavailableAgent, repairDirectory, 1, progress, 1, 2,
                    "Reusing saved format correction", true);
        }
        require(Files.readString(repairDirectory.resolve("repair-response.md")).equals(savedRepair),
                "resume promotes a valid saved repair after the original response fails validation");
        Files.delete(repairDirectory.resolve("repair-response.md"));
        try (var progress = new CommandProgress("missing-original-test", new java.io.PrintStream(java.io.OutputStream.nullOutputStream()))) {
            ReviewTasks.run(List.of(repairTask), unavailableAgent, repairDirectory, 1, progress, 1, 2,
                    "Reusing repair with missing original", true);
        }
        require(Files.readString(repairDirectory.resolve("repair-response.md")).equals(savedRepair),
                "resume recovers from a saved repair when the original response is missing");

        CliOptions prepare = CliOptions.parse(new String[]{"review", "--repo", repo.toString(), "--prepare-only",
                "--output-dir", fixture.resolve("multi-prepare").toString()});
        Path prepared = new ReviewPipeline(new ProcessExecutor(), Clock.systemUTC()).run(prepare);
        require(Files.exists(prepared.resolve("discovery/R3-prompt.md"))
                && Files.readString(prepared.resolve("verify-prompt-template.md")).contains("independent candidate verification")
                && Files.readString(prepared.resolve("report-prompt-template.md")).contains("finding-groups"),
                "prepare-only exposes all stages without invoking an agent");

        String failureStub = """
                prompt=$(cat)
                case "$prompt" in
                    fail)
                        attempt=0
                        while [ ! -f "$1/cancelled.pid" ]; do
                            attempt=$((attempt + 1))
                            [ "$attempt" -lt 100 ] || exit 34
                            sleep 0.01
                        done
                        exit 7
                        ;;
                    block) printf '%s' "$$" > "$1/cancelled.pid"; exec sleep 10 ;;
                esac
                """;
        var failureAgent = new AgentRunner(new ProcessExecutor(), "/bin/sh",
                List.of("-c", failureStub, "cancellation-stub", signals.toString()), fixture, Duration.ofSeconds(15));
        long started = System.nanoTime();
        try (var progress = new CommandProgress("cancellation-test", new java.io.PrintStream(java.io.OutputStream.nullOutputStream()))) {
            try {
                ReviewTasks.run(List.of(new ReviewTasks.Task("fail", "fail", response -> {}),
                                new ReviewTasks.Task("block", "block", response -> {})),
                        failureAgent, fixture.resolve("failed-tasks"), 2, progress, 1, 2, "Testing failed agent", false);
                throw new AssertionError("Expected an agent failure");
            } catch (java.io.IOException expected) {
                require(expected.getMessage().contains("See task artifacts"), "agent failures identify their diagnostic directory");
            }
        }
        require(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 5, "failed stage cancels its other model calls");
        long pid = Long.parseLong(Files.readString(signals.resolve("cancelled.pid")));
        require(ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true), "cancelled model subprocess is stopped");
    }

    private static void checkRequestLimits(Path fixture) throws Exception {
        Path repo = fixture.resolve("repo");
        var executor = new ProcessExecutor();
        Files.createDirectories(repo.resolve("contracts"));
        var sourceRequests = new ArrayList<String>();
        var ruleRequests = new ArrayList<String>();
        StringBuilder rules = new StringBuilder("# Approved rules\n\n");
        for (int i = 1; i <= 12; i++) {
            String file = "contracts/related-" + i + ".h";
            Files.writeString(repo.resolve(file), "constexpr int contractValue" + i + " = " + i + ";\n");
            sourceRequests.add("REQUEST_FILE|target|" + file + "|all|Inspect a related contract");
            rules.append("## Policy ").append(i).append("\n\nPolicy detail ").append(i).append(" applies.\n\n");
            ruleRequests.add("REQUEST_KNOWLEDGE|KB-" + (3 + (i - 1) * 4) + "|Check the relevant business rule");
        }
        git(executor, repo, "add", "--all");
        git(executor, repo, "commit", "-qm", "add related contract headers");
        Files.writeString(repo.resolve("Main.cpp"), "int main() { return 0; }\n");
        git(executor, repo, "add", "--all");
        git(executor, repo, "commit", "-qm", "review a C++ entry point");
        Path knowledge = fixture.resolve("request-limit-rules.md");
        Files.writeString(knowledge, rules);

        var requests = new ArrayList<>(sourceRequests);
        requests.add("REQUEST_MAVEN|target|test.contract:schema:1.2|contracts/order.proto|Check the dependency contract");
        requests.addAll(ruleRequests);
        requests.add(sourceRequests.getFirst());
        requests.add(ruleRequests.getFirst());
        Path candidates = fixture.resolve("request-limit-candidates.txt");
        Files.writeString(candidates, String.join("\n", requests));
        String stubAgent = """
                set -eu
                prompt=$(cat)
                case "$prompt" in
                    'You are the discovery stage'*)
                        case "$prompt" in
                            *'This pass runs after evidence collection'*) ;;
                            *) cat "$1"; printf '\\n' ;;
                        esac
                        printf '%s\\n' '```xml' '<findings/>' '```'
                        ;;
                    'You are the report consolidation'*)
                        printf '%s\\n' '# Review scope' '' '# Functional and business change'
                        printf '%s\\n' "$prompt" | sed 's/^/> /'
                        printf '%s\\n' '' '# Findings' '[VERIFIED_FINDINGS]' '# Requirement assessment' \
                            '# Knowledge-base feedback' '# Verification limits' '```xml' '<finding-groups/>' '```'
                        ;;
                    *) exit 31 ;;
                esac
                """;
        var arguments = new ArrayList<>(List.of("review", "--repo", repo.toString(),
                "--knowledge", knowledge.toString(), "--knowledge-mode", "scoped",
                "--max-evidence-bytes", "200000", "--agent-command", "/bin/sh", "--agent-arg", "-c",
                "--agent-arg", stubAgent, "--agent-arg", "request-limit-stub", "--agent-arg", candidates.toString(),
                "--output-dir", fixture.resolve("unlimited-review").toString()));
        CliOptions unlimited = CliOptions.parse(arguments.toArray(String[]::new));
        Path run = new ReviewPipeline(executor, Clock.systemUTC()).run(unlimited);
        String report = Files.readString(run.resolve("report.md"));
        String coverage = Files.readString(run.resolve("coverage.md"));
        String discovery = Files.readString(run.resolve("discover-prompt.md"));
        require(report.contains("constexpr int contractValue12 = 12;") && report.contains("Policy detail 12 applies."),
                "default CLI flow supplies more than eight source excerpts and rule sections to verification");
        require(discovery.contains("File and language requests have no count limit.")
                && discovery.contains("Knowledge queries have no count limit."), "discovery reflects the unlimited defaults");
        require(coverage.contains("Behavior queries processed: 12") && coverage.contains("Skipped by query limit: 0")
                && coverage.contains("Supplemental rule excerpts supplied: 12")
                && coverage.contains("Skipped by request limit: 0"), "all distinct requests are considered by default");
        require(Files.readString(run.resolve("scope.md")).contains("File and language request count limit: unlimited"),
                "scope records the effective request limit");

        var git = new GitClient(repo, executor);
        ReviewScope scope = git.resolve(unlimited);
        EvidenceBundle evidence = new EvidenceCollector(git).collect(scope, null, InputDocument.read(knowledge),
                CliOptions.KnowledgeMode.SCOPED, unlimited.maxEvidenceBytes());
        var collector = new SupplementalEvidence(git, executor, java.util.Map.of("java", fixture.resolve("cache")));
        var mixed = collector.collect(evidence, String.join("\n", requests), 0, 0);
        require(mixed.coverage().contains("Supplemental requests included: 13/13"),
                "file and dependency requests both follow the unlimited default and are deduplicated");
        EvidenceBundle smallBudget = new EvidenceBundle(scope, List.of(), "", "", 2_000, null);
        var small = collector.collect(smallBudget, String.join("\n", sourceRequests), 0, 0);
        require(small.coverage().contains("Supplemental requests included: 12/12")
                && EvidenceText.bytes(small.markdown()) <= smallBudget.supplementBudget(),
                "many small excerpts can use the available budget without a minimum per-request share blocking them");

        arguments.addAll(List.of("--max-supplemental-requests", "2", "--max-knowledge-queries", "2",
                "--output-dir", fixture.resolve("limited-review").toString()));
        Path limitedRun = new ReviewPipeline(executor, Clock.systemUTC()).run(CliOptions.parse(arguments.toArray(String[]::new)));
        String limitedCoverage = Files.readString(limitedRun.resolve("coverage.md"));
        String limitedReport = Files.readString(limitedRun.resolve("report.md"));
        String limitedPrompt = Files.readString(limitedRun.resolve("discover-prompt.md"));
        require(limitedCoverage.contains("Supplemental requests included: 2/2")
                && limitedCoverage.contains("Skipped by request limit: 11")
                && limitedCoverage.contains("Behavior queries processed: 2")
                && limitedCoverage.contains("Skipped by query limit: 10"), "explicit limits apply and skipped counts are recorded");
        require(limitedReport.contains("constexpr int contractValue2 = 2;") && limitedReport.contains("Policy detail 2 applies.")
                && !limitedReport.contains("constexpr int contractValue3 = 3;") && !limitedReport.contains("Policy detail 3 applies."),
                "verification receives only the requested number of excerpts under finite limits");
        require(limitedPrompt.contains("The CLI attempts at most 2 file or language requests.")
                && limitedPrompt.contains("The CLI processes at most 2 knowledge queries."), "configured limits reach the model prompt");
    }

    private static void checkScopedKnowledgeReview(Path fixture) throws Exception {
        Path repo = Files.createDirectory(fixture.resolve("shared-contract"));
        var executor = new ProcessExecutor();
        git(executor, repo, "init", "-q");
        Path contract = Files.createDirectories(repo.resolve("contracts")).resolve("shared.proto");
        Files.writeString(contract, "message SharedEvent { optional string owner_key = 1; }\n");
        git(executor, repo, "add", "--all");
        git(executor, repo, "commit", "-qm", "base");
        Files.writeString(contract, "message SharedEvent { string owner_key = 1; }\n");
        Files.writeString(repo.resolve("README.md"), "RuntimeOnlyRule documents 运行 and 状态.\n");
        git(executor, repo, "add", "--all");
        git(executor, repo, "commit", "-qm", "change field presence");

        Path knowledge = fixture.resolve("approved-rules.md");
        Files.writeString(knowledge, """
                # 主知识库

                ## 全局规则
                所有事件按租户隔离。

                ## 事件契约
                contracts/shared.proto 需要保持字段存在语义。

                ~~~text
                ## 字段例子
                ~~~

                ### 例外
                内部迁移可临时保留兼容分支。

                ## 所有者标识
                SharedEvent 必须保留历史所有者标识。

                ## 旧客户端缺省值
                旧客户端依赖缺省值时，字段缺失必须保留原来的回退路径。

                ## 已保存流程解析
                已保存流程解析事件时，历史记录继续按原格式解码。

                ## 回调确认
                `/events/ack` 必须允许重复确认。

                ## 运行状态
                RuntimeOnlyRule 的 events ack 状态记录保留一天。

                发票结算
                --------
                发票在月末结算。

                ## 账务审计超限
                """ + "此段是超限证据标记。\n".repeat(1_000));
        String stubAgent = """
                set -eu
                prompt=$(cat)
                case "$prompt" in
                    'You are the discovery stage'*)
                        case "$prompt" in
                            *'This pass runs after evidence collection'*)
                                case "$prompt" in
                                    *'保留原来的回退路径'*'历史记录继续按原格式解码'*) ;;
                                    *) exit 23 ;;
                                esac
                                printf '%s\\n' '```xml' '<findings><finding>' \
                                    '<title>Field presence no longer distinguishes omitted values.</title><severity>HIGH</severity>' \
                                    '<path>contracts/shared.proto</path><line>1</line><endLine>1</endLine>' \
                                    '<trigger>Older clients omit the owner field.</trigger><impact>The fallback loses field presence.</impact>' \
                                    '<evidence>contracts/shared.proto:1 removes optional; the supplied legacy-client rule requires presence.</evidence>' \
                                    '<diffRelation>The diff removes optional from owner_key.</diffRelation>' '</finding></findings>' '```'
                                exit 0
                                ;;
                        esac
                        case "$prompt" in
                            *'所有事件按租户隔离'*'## 字段例子'*'内部迁移可临时保留兼容分支'*'必须保留历史所有者标识'*) ;;
                            *) exit 21 ;;
                        esac
                        case "$prompt" in
                            *'保留原来的回退路径'*|*'历史记录继续按原格式解码'*|*'发票在月末结算'*|*'状态记录保留一天'*) exit 22 ;;
                        esac
                        printf '%s\\n' 'No candidate findings.' \
                            'REQUEST_KNOWLEDGE|旧客户端 缺省值|Removing field presence affects older consumersREQUEST_KNOWLEDGE|已保存流程解析|Saved flows can deserialize historical eventsREQUEST_KNOWLEDGE|malformed' \
                            'REQUEST_KNOWLEDGE|数字签名验签|Check a potential consumer with an unknown policy' \
                            'REQUEST_KNOWLEDGE|账务审计超限|Check a rule that exceeds the section budget' \
                            'REQUEST_KNOWLEDGE|运行 状态 /events/ack|Check the acknowledgment contract' \
                            'REQUEST_KNOWLEDGE|运行 状态|Generic terms alone do not establish coverage'
                        printf '%s\\n' '```xml' '<findings/>' '```'
                        ;;
                    'You are the independent candidate verification'*|'You are the independent publication adjudication'*)
                        case "$prompt" in
                            *'保留原来的回退路径'*'历史记录继续按原格式解码'*) ;;
                            *) exit 23 ;;
                        esac
                        case "$prompt" in
                            *'发票在月末结算'*|*'此段是超限证据标记'*|*'状态记录保留一天'*) exit 24 ;;
                        esac
                        finding=$(printf '%s\\n' "$prompt" | sed -n '/^CANDIDATE TO VERIFY$/{n;p;}')
                        printf '%s\\n' '```xml' '<verification><status>CONFIRMED</status>' \
                            '<reason>The changed contract conflicts with the supplied presence rule.</reason>' 'GROUNDING_XML' \
                            "$finding" '</verification>' '```'
                        ;;
                    'You are the candidate reconciliation'*)
                        printf '%s\\n' '<candidate-groups><group><id>S1-1</id></group></candidate-groups>'
                        ;;
                    'You are the report consolidation'*)
                        printf '%s\\n' '# Review scope' '# Functional and business change' '# Findings' \
                            '[VERIFIED_FINDINGS]' '# Requirement assessment' '# Knowledge-base feedback' \
                            '# Verification limits' '```xml' '<finding-groups><group><id>S1-1</id></group></finding-groups>' '```'
                        ;;
                    *) exit 25 ;;
                esac
                """.replace("GROUNDING_XML", grounding("contracts/shared.proto",
                        "-message SharedEvent { optional string owner_key = 1; }\n+message SharedEvent { string owner_key = 1; }"));
        var options = CliOptions.parse(new String[]{"review", "--repo", repo.toString(),
                "--knowledge", knowledge.toString(), "--knowledge-mode", "scoped",
                "--output-dir", fixture.resolve("scoped-review").toString(),
                "--agent-command", "/bin/sh", "--agent-arg", "-c", "--agent-arg", stubAgent});
        Path run = new ReviewPipeline(executor, Clock.systemUTC()).run(options);
        String coverage = Files.readString(run.resolve("coverage.md"));
        require(coverage.contains("Changed files: 2"), "includes the contract and the README change");
        require(coverage.contains("Match: changed path contracts/shared.proto"), "initial rule found by path");
        require(coverage.contains("Match: changed identifier sharedevent"), "initial rule found by identifier");
        require(coverage.contains("数字签名验签: NO MATCH. Business rule coverage insufficient"),
                "unmatched behavior is recorded");
        require(coverage.lines().anyMatch(line -> line.contains("账务审计超限")
                && line.contains("OMITTED (byte limit)")), "oversized section is omitted whole");
        require(coverage.contains("Behavior queries processed: 6. Without supplied rules: 3. Invalid: 1"),
                "joined valid queries recovered and the malformed query counted separately");
        require(coverage.contains("Invalid knowledge request on discovery output line "), "invalid query location recorded");
        require(coverage.contains("Match: exact endpoint /events/ack") && coverage.contains("运行 状态: NO MATCH"),
                "endpoint match outranks generic term overlap");
        require(coverage.contains("Supplemental rule excerpts supplied: 3"), "three applicable rule sections found");
        require(Files.readString(run.resolve("report.md")).contains("Field presence no longer distinguishes"),
                "verification can surface a business finding discovered after retrieval");

        Path tracked = Files.createDirectories(repo.resolve("docs")).resolve("approved-rules.md");
        Files.copy(knowledge, tracked);
        git(executor, repo, "add", "--all");
        git(executor, repo, "commit", "-qm", "add tracked rule input");
        var git = new GitClient(repo, executor);
        ReviewScope scope = git.resolve(options);
        var collector = new EvidenceCollector(git);
        InputDocument input = InputDocument.read(tracked);
        EvidenceBundle scoped = collector.collect(scope, null, input, CliOptions.KnowledgeMode.SCOPED, 160_000);
        require(scoped.markdown().contains("所有事件按租户隔离") && !scoped.markdown().contains("发票在月末结算"),
                "tracked knowledge is only supplied through scoped excerpts");
        require(scoped.coverage().contains("docs/approved-rules.md — diff: OMITTED (scoped knowledge document)"
                + "; target file: OMITTED (scoped knowledge document)"), "excluded change remains in the coverage inventory");
        var supplemental = new SupplementalEvidence(git, executor, java.util.Map.of("java", fixture.resolve("cache")))
                .collect(scoped, "REQUEST_FILE|target|docs/approved-rules.md|all|Try reading the tracked knowledge file", 0, 0);
        require(supplemental.coverage().contains("Use a knowledge query for the scoped knowledge document")
                && !supplemental.markdown().contains("发票在月末结算"), "raw follow-up read cannot bypass scoped selection");

        EvidenceBundle full = collector.collect(scope, null, input, CliOptions.KnowledgeMode.FULL, 160_000);
        require(full.markdown().contains("发票在月末结算"), "full mode still supplies the complete document");

        Path renamed = Files.move(tracked, repo.resolve("docs/renamed-rules.md"));
        git(executor, repo, "add", "--all");
        git(executor, repo, "commit", "-qm", "rename the knowledge input");
        EvidenceBundle afterRename = collector.collect(git.resolve(options), null, InputDocument.read(renamed),
                CliOptions.KnowledgeMode.SCOPED, 160_000);
        var oldPath = new SupplementalEvidence(git, executor, java.util.Map.of("java", fixture.resolve("cache")))
                .collect(afterRename, "REQUEST_FILE|base|docs/approved-rules.md|all|Try reading the previous path", 0, 0);
        require(oldPath.coverage().contains("Use a knowledge query for the scoped knowledge document"),
                "base file path stays scoped after a rename");
    }

    private static void git(ProcessExecutor executor, Path repo, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-c", "user.name=Evidence Test", "-c",
                "user.email=evidence@example.invalid", "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null"));
        command.addAll(List.of(arguments));
        CommandResult result = executor.run(command, repo, null, Duration.ofSeconds(15));
        require(result.exitCode() == 0, "Git fixture: " + result.stderr());
    }

    private static String grounding(String source, String quote) {
        return "<relation>INTRODUCED</relation><baseline>The diff adds the failing behavior.</baseline>"
                + "<contract>The operation must preserve its invariant.</contract><citations><citation><source>"
                + source + "</source><quote>" + quote + "</quote></citation></citations>"
                + "<support>REACHABLE_FAILURE</support><premises><premise><statement>The changed operation executes this path.</statement>"
                + "<basis>IMPLEMENTATION</basis><citations><citation><source>" + source + "</source><quote>" + quote
                + "</quote></citation></citations></premise></premises>";
    }

    private static void require(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
    }
}
