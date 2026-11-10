package reviewagent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
            checkKnowledgeSnapshot(fixture);
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
        Files.writeString(repo.resolve("Stable.java"), "class Stable { int evidence = 42; }\n");
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
        EvidenceBundle evidence = new EvidenceCollector(git).collect(scope, null, null, 10_000);
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
        var supplemental = new SupplementalEvidence(git, new MavenEvidence(git, executor, cache)).collect(evidence, """
                REQUEST_FILE|base|Stable.java|1:1|Find a call contract
                REQUEST_MAVEN|target|test.contract:schema:1.2|contracts/order.proto|Check the field number
                REQUEST_MAVEN|target|test.contract:schema:1.3|contracts/order.proto|Check a nonexistent version
                REQUEST_FILE|target|../outside.txt|all|Check path restrictions
                """);
        require(supplemental.markdown().contains("evidence = 42"), "baseline source");
        require(supplemental.markdown().contains("message Order") && supplemental.markdown().contains("JAR SHA-256"),
                "precise cached proto provenance");
        require(supplemental.coverage().contains("schema:1.3 contracts/order.proto: UNAVAILABLE"), "undeclared version");
        require(supplemental.coverage().contains("../outside.txt all: UNAVAILABLE"), "unsafe path");
        require(EvidenceText.bytes(evidence.markdown() + supplemental.markdown()) <= 10_000, "combined evidence budget");
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

    private static void git(ProcessExecutor executor, Path repo, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-c", "user.name=Evidence Test", "-c",
                "user.email=evidence@example.invalid", "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null"));
        command.addAll(List.of(arguments));
        CommandResult result = executor.run(command, repo, null, Duration.ofSeconds(15));
        require(result.exitCode() == 0, "Git fixture: " + result.stderr());
    }

    private static void require(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
    }
}
