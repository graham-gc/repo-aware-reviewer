package reviewagent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

final class GoLanguageTests {
    private static final ProcessExecutor EXECUTOR = new ProcessExecutor();
    static void run() throws Exception {
        Path fixture = Files.createTempDirectory("go-language-evidence-");
        try {
            declarations();
            modules();
            extension(fixture);
            cachedDependencies(fixture);
            workspace(fixture);
            vendor(fixture);
            pipeline(fixture);
        } finally {
            try (var paths = Files.walk(fixture)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void declarations() throws Exception {
        String source = """
                //go:build linux
                package service
                import (
                    pb "example.com/Proto/api"
                    "context"
                )
                // func Fake() { }
                var text = `func Hidden() { }`
                type (
                    Request[T any] struct { ID T }
                    Handler interface { Handle(context.Context, *pb.Request) error }
                )
                type Other struct{}
                func Parse[T interface{ String() string }](value T) string {
                    text := "} /* func Wrong() { */"
                    _ = text
                    return value.String()
                }
                func (s *Request[T]) Read() T { return s.ID }
                func (o Other) Read() int { return 1 }
                func Make() struct { Value int } {
                    return struct { Value int }{1}
                }
                """;
        GoSource.Parsed parsed = GoSource.parse(source);
        require(parsed.packageName().equals("service") && parsed.imports().getFirst().alias().equals("pb"), "package and import aliases");
        require(parsed.declarations().stream().noneMatch(d -> d.name().equals("Fake") || d.name().equals("Hidden") || d.name().equals("Wrong")), "comments and literals cannot forge declarations");
        require(parsed.declarations().stream().anyMatch(d -> d.name().equals("Handler") && d.kind().equals("type")), "grouped interface declaration");
        var method = GoSource.locate(source, "Request.Read");
        require(source.lines().skip(method.first() - 1).findFirst().orElseThrow().contains("*Request[T]"), "generic pointer receiver qualification");
        require(GoSource.locate(source, "Parse").last() > GoSource.locate(source, "Parse").first(), "generic function body range");
        var make = GoSource.locate(source, "Make");
        require(make.last() - make.first() == 2, "anonymous struct return type is not mistaken for the function body");
        fails(() -> GoSource.locate(source, "Read"));
        fails(() -> GoSource.parse("package p\nfunc Broken() { /* unterminated"));
        fails(() -> GoSource.locate(source, "NotThere"));
        require(parsed.constraints().equals(List.of("//go:build linux")), "build constraints remain explicit");
    }

    private static void modules() throws Exception {
        GoModuleFile file = GoModuleFile.parse("""
                module "example.com/root"
                go 1.22
                require (
                    example.com/Proto v1.2.0 // indirect
                )
                replace example.com/Proto v1.2.0 => "./local dir"
                exclude example.com/Proto v1.1.0
                """, "svc/go.mod");
        require(file.module().equals("example.com/root") && file.requires().get("example.com/Proto").equals("v1.2.0"), "quoted module and grouped requirements");
        require(GoModuleFile.relative("svc/go.mod", file.replacements().getFirst().target()).equals("svc/local dir"), "local replacement is relative to its declaring manifest");
        fails(() -> GoModuleFile.relative("svc/go.mod", "../../outside"));
        fails(() -> GoModuleFile.parse("module example.com/root\nrequire example.com/Proto latest\n", "go.mod"));
        require(GoModuleFile.compareVersions("v1.10.0", "v1.9.9") > 0 && GoModuleFile.compareVersions("v1.0.0-rc.10", "v1.0.0-rc.2") > 0
                && GoModuleFile.compareVersions("v1.0.0", "v1.0.0-rc.10") > 0, "semantic version selection");
        require(GoDependencies.escape("example.com/Azure/Proto").equals("example.com/!azure/!proto"), "Go module cache case escaping");
        require(RepositorySnapshotCollector.isKnowledgeText("go.work") && RepositorySnapshotCollector.isKnowledgeText("go.work.sum"), "workspace configuration participates in knowledge scans");
        CliOptions options = CliOptions.parse(new String[]{"review", "--repo", ".", "--language-cache", "go=/tmp/modules"});
        require(options.languageCaches().get("go").equals(Path.of("/tmp/modules")), "generic per-language cache override");
    }

    private static void extension(Path fixture) throws Exception {
        require(LanguageRegistry.defaults().module("go") instanceof GoLanguageModule && LanguageRegistry.defaults().module("java") instanceof JavaLanguageModule,
                "built-in modules are registered through ServiceLoader");
        Path services = Files.createDirectories(fixture.resolve("extension/META-INF/services"));
        Files.writeString(services.resolve("reviewagent.LanguageModule"), "extension.FixtureLanguageModule\n");
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{fixture.resolve("extension").toUri().toURL()}, GoLanguageTests.class.getClassLoader())) {
            var providers = java.util.ServiceLoader.load(LanguageModule.class, loader).stream().map(java.util.ServiceLoader.Provider::get).toList();
            LanguageRegistry registry = new LanguageRegistry(providers);
            require(registry.module("fixture").instructions().contains("Fixture-specific"), "external-package SPI provider is loaded without core changes");
            Path repo = repository(fixture.resolve("extension-repo"));
            write(repo, "fixture.config", "configuration evidence from the extension\n");
            write(repo, ".env.fixture", "SENSITIVE_FIXTURE_VALUE_NOT_TO_INCLUDE\n");
            write(repo, "main.fixture", "entry value = 1\n"); commit(repo, "base");
            write(repo, "main.fixture", "entry value = 2\n"); commit(repo, "target");
            GitClient git = new GitClient(repo, EXECUTOR);
            ReviewScope scope = git.resolve(CliOptions.parse(new String[]{"review", "--repo", repo.toString(), "--prepare-only"}));
            EvidenceBundle evidence = new EvidenceCollector(git, registry).collect(scope, null, null, CliOptions.KnowledgeMode.FULL, 30_000);
            require(evidence.markdown().contains("configuration evidence from the extension"), "module-owned manifest discovery");
            var supplement = new SupplementalEvidence(git, EXECUTOR, Map.of(), registry).collect(evidence, """
                    REQUEST_FILE|target|main.fixture|symbol:entry|Inspect the module symbol
                    REQUEST_LANGUAGE|target|fixture|main.fixture|resource|selector|Read module evidence
                    REQUEST_FILE|target|.env.fixture|all|A provider extension must not bypass exclusions
                    """, 0, 0);
            require(supplement.markdown().contains("entry value = 2") && supplement.markdown().contains("Fixture dependency"), "new provider works through generic file and language protocols");
            require(!supplement.markdown().contains("SENSITIVE_FIXTURE_VALUE_NOT_TO_INCLUDE") && supplement.coverage().contains(".env.fixture all: UNAVAILABLE"),
                    "language format extensions cannot bypass the common sensitive-file exclusions");
        }
    }

    private static void cachedDependencies(Path fixture) throws Exception {
        Path repo = repository(fixture.resolve("cached-repo"));
        write(repo, "go.mod", "module example.com/app\ngo 1.22\nrequire example.com/Proto v1.0.0\n");
        write(repo, "api/service.go", "package api\nimport pb \"example.com/Proto/api\"\nfunc Handle() *pb.Request { return nil }\n");
        write(repo, "api/service_test.go", "package api_test\nfunc TestHandle() {}\n"); commit(repo, "base");
        write(repo, "go.mod", "module example.com/app\ngo 1.22\nrequire (\nexample.com/Proto v1.0.0\nexample.com/helper v1.0.0\n)\n");
        write(repo, "go.sum", "example.com/Proto v9.0.0 h1:unrelated-historical-entry\n");
        write(repo, "api/service.go", "package api\nimport pb \"example.com/Proto/api\"\nfunc Handle() *pb.Request { return &pb.Request{} }\n"); commit(repo, "target");
        Path cache = Files.createDirectories(fixture.resolve("go-cache"));
        cached(cache, "example.com/Proto", "v1.0.0", "module example.com/Proto\ngo 1.22\n", "api/message.pb.go", "package api\ntype Request struct { OldField string }\n");
        cached(cache, "example.com/Proto", "v1.2.0", "module example.com/Proto\ngo 1.22\n", "api/message.pb.go", "package api\ntype Request struct { CurrentField string }\n");
        write(cache, GoDependencies.escape("example.com/Proto") + "@v1.2.0/schemas/contract.proto", "syntax = \"proto3\";\nmessage RpcRequest { string tenant_id = 1; }\n");
        write(cache, GoDependencies.escape("example.com/Proto") + "@v1.2.0/api/huge.pb.go",
                "package api\ntype HugeRequest struct {\n" + java.util.stream.IntStream.range(0, 220).mapToObj(i -> "  Field" + i + " int\n").collect(java.util.stream.Collectors.joining()) + "}\n");
        cached(cache, "example.com/helper", "v1.0.0", "module example.com/helper\ngo 1.22\nrequire (\nexample.com/Proto v1.2.0\nexample.com/pruned v1.0.0\n)\n", "helper.go", "package helper\n");
        var git = new GitClient(repo, EXECUTOR);
        var options = CliOptions.parse(new String[]{"review", "--repo", repo.toString(), "--prepare-only"});
        ReviewScope scope = git.resolve(options);
        EvidenceBundle evidence = new EvidenceCollector(git).collect(scope, null, null, CliOptions.KnowledgeMode.FULL, 120_000);
        require(evidence.markdown().contains("go.mod at ") && evidence.markdown().contains("Package directory: api")
                && evidence.markdown().contains("api/service_test.go [test variant]"), "Go manifests and peer/test file inventory are supplied automatically");
        var collector = new SupplementalEvidence(git, EXECUTOR, Map.of("go", cache));
        var result = collector.collect(evidence, """
                REQUEST_FILE|target|api/service.go|symbol:Handle|Read the function
                REQUEST_LANGUAGE|target|go|api/service.go|example.com/Proto/api|symbol:Request|Read selected protobuf entity
                REQUEST_LANGUAGE|base|go|api/service.go|example.com/Proto/api|symbol:Request|Compare old protobuf entity
                REQUEST_LANGUAGE|target|go|api/service.go|example.com/app/api|index|Inspect local package declarations
                """, 0, 0);
        require(result.coverage().contains("Supplemental requests included: 4/4") && result.markdown().contains("CurrentField") && result.markdown().contains("OldField"), "base/target pinning and transitive version upgrades");
        require(result.markdown().contains("example.com/Proto@v1.2.0") && result.markdown().contains("SHA-256"), "dependency evidence has coordinate and source hash provenance");
        require(result.markdown().contains("selected minimum from example.com/helper@v1.0.0/go.mod SHA-256"), "selected version records its decisive transitive metadata source");
        require(!result.markdown().contains("v9.0.0"), "go.sum entries cannot select dependency versions");
        var proto = collector.collect(evidence, "REQUEST_LANGUAGE|target|go|api/service.go|example.com/Proto/schemas|file:contract.proto|Inspect protobuf contract", 0, 0);
        require(proto.markdown().contains("message RpcRequest") && proto.markdown().contains("```proto"), "proto-only schema directories do not require generated Go files");
        var huge = collector.collect(evidence, "REQUEST_LANGUAGE|target|go|api/service.go|example.com/Proto/api|symbol:HugeRequest|Inspect large generated type", 0, 0);
        require(huge.hasMaterial() && huge.coverage().contains("INDEX_ONLY") && huge.markdown().contains("huge.pb.go")
                && !huge.markdown().contains("  Field0 int"), "oversized declarations supply navigation, not a false claim of complete source coverage");
        require(collector.collect(evidence, "REQUEST_LANGUAGE|target|go|api/service.go|example.com/Proto/api|file:huge.pb.go:1:20|Read decisive fields", 0, 0)
                .markdown().contains("  Field0 int"), "a follow-up request can read a bounded range after index-only fallback");
        var index = new EvidenceIndex(evidence, result.markdown());
        var metadataCitation = List.of(new EvidenceIndex.Citation("Go repository package index at " + scope.targetCommit(), "api/service_test.go [test variant]"));
        index.validate(metadataCitation);
        require(!index.hasImplementation(metadataCitation) && !index.hasTarget(metadataCitation), "navigation metadata cannot confirm behavior");
        var failed = collector.collect(evidence, """
                REQUEST_LANGUAGE|target|go|api/service.go|example.com/not-required|index|Unknown dependency
                REQUEST_LANGUAGE|target|go|api/service.go|example.com/Proto/api|file:../../private.go|Reject traversal
                """, 0, 0);
        require(failed.coverage().contains("Supplemental requests included: 0/2"), "missing dependencies and unsafe selectors remain gaps");
        var importers = collector.collect(evidence, "REQUEST_LANGUAGE|target|go|go.mod|example.com/Proto|importers|Locate code consumers", 0, 0);
        require(importers.markdown().contains("api/service.go imports example.com/Proto/api alias=pb") && importers.markdown().contains("metadata only"),
                "dependency-only changes can locate actual import consumers before reading their code");
        String request = "REQUEST_LANGUAGE|target|go|api/service.go|example.com/Proto/api|symbol:Request|first";
        EvidenceSession session = new EvidenceSession(evidence, collector, 1, 0);
        require(session.load(request, "first") && !session.load(request.replace("first", "another | reason"), "repeat")
                && session.coverage().contains("Repeated requests not retried: 1"), "language requests deduplicate across rounds, including reasons containing a pipe");
        require(!session.load("REQUEST_FILE|target|api/service.go|symbol:Handle|After limit", "limited") && EvidenceText.bytes(session.markdown()) <= evidence.supplementBudget(), "Go requests share existing count and byte limits");

        // An older dependency expands its full graph, even through newer modules.
        write(cache, GoDependencies.escape("example.com/helper") + "@v1.0.0/go.mod", "module example.com/helper\ngo 1.16\nrequire example.com/pruned v1.0.0\n");
        cached(cache, "example.com/pruned", "v1.0.0", "module example.com/pruned\ngo 1.22\nrequire example.com/Proto v2.0.0+incompatible\n", "p.go", "package pruned\n");
        cached(cache, "example.com/Proto", "v2.0.0+incompatible", "module example.com/Proto\ngo 1.22\n", "api/message.pb.go", "package api\ntype Request struct { OlderGraphField string }\n");
        var expanded = new SupplementalEvidence(git, EXECUTOR, Map.of("go", cache)).collect(evidence, request, 0, 0);
        require(expanded.markdown().contains("OlderGraphField"), "Go 1.16 transitive closure is not incorrectly pruned");
        Files.delete(cache.resolve(GoDependencies.escape("example.com/helper") + "@v1.0.0/go.mod"));
        require(new SupplementalEvidence(git, EXECUTOR, Map.of("go", cache)).collect(evidence, request, 0, 0).coverage().contains("UNAVAILABLE"), "missing module metadata cannot be replaced with a guessed direct version");
    }

    private static void workspace(Path fixture) throws Exception {
        Path repo = repository(fixture.resolve("workspace-repo"));
        write(repo, "go.work", "go 1.22\nuse (\n./svc\n./lib\n./parent\n./child\n)\nreplace example.com/grpc => ./fork\n");
        write(repo, "svc/go.mod", "module example.com/svc\ngo 1.22\nrequire example.com/grpc v1.0.0\nreplace example.com/grpc => ./old\n");
        write(repo, "lib/go.mod", "module example.com/lib\ngo 1.22\nreplace example.com/grpc => ./other\n");
        write(repo, "lib/types.go", "package lib\ntype Request struct { Tenant string }\n");
        write(repo, "fork/go.mod", "module example.com/grpc\ngo 1.22\n");
        write(repo, "fork/server.go", "package grpc\ntype Server struct { WorkspaceFork string }\n");
        write(repo, "fork/server_linux.go", "//go:build linux\npackage grpc\nfunc Serve() int { return 1 }\n");
        write(repo, "fork/server_windows.go", "//go:build windows\npackage grpc\nfunc Serve() int { return 2 }\n");
        write(repo, "parent/go.mod", "module example.com/prefix\ngo 1.22\n");
        write(repo, "child/go.mod", "module example.com/prefix/sub\ngo 1.22\n");
        write(repo, "parent/sub/types.go", "package sub\ntype Shared struct { ParentCopy string }\n");
        write(repo, "child/types.go", "package sub\ntype Shared struct { ChildCopy string }\n");
        write(repo, "svc/main.go", "package main\nfunc Run() {}\n"); commit(repo, "base");
        write(repo, "svc/main.go", "package main\nfunc Run() { println(1) }\n"); commit(repo, "target");
        GitClient git = new GitClient(repo, EXECUTOR);
        ReviewScope scope = git.resolve(CliOptions.parse(new String[]{"review", "--repo", repo.toString(), "--prepare-only"}));
        EvidenceBundle evidence = new EvidenceCollector(git).collect(scope, null, null, CliOptions.KnowledgeMode.FULL, 100_000);
        require(evidence.markdown().contains("go.work at ") && evidence.markdown().contains("lib/go.mod at ") && evidence.markdown().contains("svc/go.mod at "), "workspace manifests are collected for changed nested modules");
        var collector = new SupplementalEvidence(git, EXECUTOR, Map.of("go", fixture.resolve("missing-cache")));
        var local = collector.collect(evidence, """
                REQUEST_LANGUAGE|target|go|svc/main.go|example.com/lib|symbol:Request|Read workspace type
                REQUEST_LANGUAGE|target|go|svc/main.go|example.com/grpc|symbol:Server|Read overriding local replacement
                """, 0, 0);
        require(local.markdown().contains("Tenant string") && local.markdown().contains("WorkspaceFork") && local.coverage().contains("included: 2/2"), "workspace modules and work replacements need no dependency installation");
        require(collector.collect(evidence, "REQUEST_LANGUAGE|target|go|svc/main.go|example.com/grpc|symbol:Serve|Build variants", 0, 0)
                .coverage().contains("ambiguous across files/build variants"), "Go build variants cannot be silently merged into one implementation");
        String overlapping = "REQUEST_LANGUAGE|target|go|svc/main.go|example.com/prefix/sub|symbol:Shared|Check ownership";
        require(collector.collect(evidence, overlapping, 0, 0).coverage().contains("multiple pinned workspace modules"),
                "overlapping workspace import paths cannot silently select the longest prefix");
        write(repo, "go.work", "go 1.22\nuse (\n./svc\n./lib\n./parent\n./parent/sub\n)\nreplace example.com/grpc => ./fork\n");
        write(repo, "parent/sub/go.mod", "module example.com/prefix/sub\ngo 1.22\n");
        write(repo, "parent/sub/types.go", "package sub\ntype Shared struct { NestedWorkspace string }\n"); commit(repo, "nested workspace module");
        var nestedScope = git.resolve(CliOptions.parse(new String[]{"review", "--repo", repo.toString(), "--prepare-only"}));
        var nested = new EvidenceCollector(git).collect(nestedScope, null, null, CliOptions.KnowledgeMode.FULL, 100_000);
        require(collector.collect(nested, overlapping, 0, 0).markdown().contains("NestedWorkspace"), "a selected nested module excludes its parent's copy correctly");
        write(repo, "go.work", "go 1.22\nuse (\n./svc\n./lib\n)\nreplace example.com/grpc => ../outside\n"); commit(repo, "outside replacement");
        ReviewScope next = git.resolve(CliOptions.parse(new String[]{"review", "--repo", repo.toString(), "--prepare-only"}));
        EvidenceBundle changed = new EvidenceCollector(git).collect(next, null, null, CliOptions.KnowledgeMode.FULL, 100_000);
        require(collector.collect(changed, "REQUEST_LANGUAGE|target|go|svc/main.go|example.com/grpc|symbol:Server|Outside", 0, 0)
                .coverage().contains("escapes the pinned repository"), "outside replacements cannot read the user's live filesystem");
    }

    private static void vendor(Path fixture) throws Exception {
        Path repo = repository(fixture.resolve("vendor-repo"));
        write(repo, "go.mod", "module example.com/app\ngo 1.22\nrequire example.com/dep v1.0.0\n");
        write(repo, "main.go", "package main\nfunc main() {}\n");
        write(repo, "vendor/modules.txt", "# example.com/dep v1.0.0\n## explicit; go 1.22\nexample.com/dep/api\n");
        write(repo, "vendor/example.com/dep/api/types.pb.go", "package api\ntype Request struct { Vendored string }\n"); commit(repo, "base");
        write(repo, "main.go", "package main\nfunc main() { println(1) }\n"); commit(repo, "target");
        var git = new GitClient(repo, EXECUTOR);
        var scope = git.resolve(CliOptions.parse(new String[]{"review", "--repo", repo.toString(), "--prepare-only"}));
        var evidence = new EvidenceCollector(git).collect(scope, null, null, CliOptions.KnowledgeMode.FULL, 60_000);
        var collector = new SupplementalEvidence(git, EXECUTOR, Map.of("go", fixture.resolve("no-cache")));
        String request = "REQUEST_LANGUAGE|target|go|main.go|example.com/dep/api|symbol:Request|Read vendored gRPC entity";
        var supplied = collector.collect(evidence, request, 0, 0);
        require(supplied.markdown().contains("Vendored string") && supplied.markdown().contains("pinned vendor"), "tracked vendor is read on demand, including generated protobuf files");
        require(collector.collect(evidence, "REQUEST_FILE|target|vendor/example.com/dep/api/types.pb.go|all|Use the dependency resolver", 0, 0)
                .coverage().contains("UNAVAILABLE"), "Go file recognition cannot bypass dependency source selection for vendor");
        write(repo, "vendor/modules.txt", "# example.com/dep v1.1.0\n## explicit; go 1.22\nexample.com/dep/api\n"); commit(repo, "stale vendor");
        var stale = new EvidenceCollector(git).collect(git.resolve(CliOptions.parse(new String[]{"review", "--repo", repo.toString(), "--prepare-only"})), null, null, CliOptions.KnowledgeMode.FULL, 60_000);
        require(collector.collect(stale, request, 0, 0).coverage().contains("inconsistent"), "inconsistent vendor is not silently replaced by cached source");
    }

    private static void pipeline(Path fixture) throws Exception {
        Path repo = repository(fixture.resolve("pipeline-repo"));
        write(repo, "go.mod", "module example.com/app\ngo 1.22\n");
        write(repo, "main.go", "package main\nfunc Run() int { return 1 }\n");
        write(repo, "helper.go", "package main\nfunc Parse() int { return 2 }\n"); commit(repo, "base");
        write(repo, "main.go", "package main\nfunc Run() int { return Parse() }\n"); commit(repo, "target");
        String stub = """
                set -eu
                prompt=$(cat)
                case "$prompt" in
                  'You are the discovery stage'*)
                    printf '%s\\n' '<findings/>'
                    case "$prompt" in
                      *'This pass runs after evidence collection'*)
                        case "$prompt" in *'func Parse() int { return 2 }'*) ;; *) exit 81 ;; esac ;;
                      *)
                        case "$prompt" in *'Go module:'*'helper.go'*) ;; *) exit 82 ;; esac
                        printf '%s\\n' 'REQUEST_LANGUAGE|target|go|main.go|example.com/app|symbol:Parse|Inspect the related function' ;;
                    esac ;;
                  'You are the report consolidation'*)
                    printf '%s\\n' '# Review scope' '# Functional and business change' '# Findings' '# Requirement assessment' \\
                      '# Knowledge-base feedback' '# Verification limits' '<finding-groups/>' ;;
                  *) exit 83 ;;
                esac
                """;
        var args = new ArrayList<>(List.of("review", "--repo", repo.toString(), "--output-dir", fixture.resolve("go-output").toString(),
                "--agent-command", "/bin/sh", "--agent-arg", "-c", "--agent-arg", stub));
        Path run = new ReviewPipeline(EXECUTOR, Clock.systemUTC()).run(CliOptions.parse(args.toArray(String[]::new)));
        require(Files.readString(run.resolve("supplemental-evidence.md")).contains("func Parse() int { return 2 }"), "Go language requests flow end-to-end through the CLI pipeline");
        require(Files.readString(run.resolve("coverage.md")).contains("Supplemental requests included: 1/1"), "parallel Go requests deduplicate before collection");
        String discovery = Files.readString(run.resolve("discovery/R1-prompt.md"));
        require(discovery.contains("Go module:") && !discovery.contains("Java module:"), "language instructions activate for the reviewed source");
        args.addAll(List.of("--resume", run.toString()));
        int stubIndex = args.indexOf(stub); args.set(stubIndex, "exit 84");
        new ReviewPipeline(EXECUTOR, Clock.systemUTC()).run(CliOptions.parse(args.toArray(String[]::new)));
    }

    private static void cached(Path cache, String module, String version, String mod, String path, String source) throws IOException {
        String root = GoDependencies.escape(module) + "@" + GoDependencies.escape(version);
        write(cache, root + "/go.mod", mod); write(cache, root + "/" + path, source);
    }
    private static Path repository(Path repo) throws Exception { Files.createDirectories(repo); git(repo, "init", "-q"); return repo; }
    private static void write(Path root, String path, String text) throws IOException { Path file = root.resolve(path); Files.createDirectories(file.getParent()); Files.writeString(file, text); }
    private static void commit(Path repo, String message) throws Exception { git(repo, "add", "--all"); git(repo, "commit", "-qm", message); }
    private static void git(Path repo, String... args) throws Exception {
        var command = new ArrayList<>(List.of("git", "-c", "user.name=Go Test", "-c", "user.email=test@example.invalid")); command.addAll(List.of(args));
        var result = EXECUTOR.run(command, repo, null, Duration.ofSeconds(10)); require(result.exitCode() == 0, "fixture git: " + result.stderr());
    }
    private static void fails(Check action) throws Exception { try { action.run(); throw new AssertionError("Expected missing/invalid Go evidence"); } catch (IOException expected) { } }
    @FunctionalInterface private interface Check { void run() throws Exception; }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
