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

final class LanguageModuleTests {
    private static final ProcessExecutor EXECUTOR = new ProcessExecutor();
    static void run() throws Exception {
        Path fixture = Files.createTempDirectory("language-modules-");
        try {
            recognition();
            metadataAndNavigation(fixture);
            declarativeExtension(fixture);
            semanticReplacement(fixture);
            pipeline(fixture);
        } finally {
            try (var paths = Files.walk(fixture)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void recognition() throws Exception {
        LanguageRegistry registry = LanguageRegistry.defaults();
        Map<String, String> families = Map.ofEntries(
                Map.entry("Main.kt", "kotlin"), Map.entry("build.gradle", "groovy"), Map.entry("App.scala", "scala"),
                Map.entry("Main.cs", "csharp"), Map.entry("App.fsx", "fsharp"), Map.entry("main.pyi", "python"),
                Map.entry("app.jsx", "javascript"), Map.entry("app.tsx", "typescript"), Map.entry("App.vue", "vue"), Map.entry("App.svelte", "svelte"),
                Map.entry("main.cpp", "c-cpp"), Map.entry("header.h", "c-cpp"), Map.entry("lib.rs", "rust"), Map.entry("app.rb", "ruby"),
                Map.entry("index.php", "php"), Map.entry("change.sql", "sql"), Map.entry("api.graphql", "graphql"), Map.entry("api.proto", "protobuf"),
                Map.entry("api.thrift", "thrift"), Map.entry("api.avsc", "avro"), Map.entry("run.fish", "shell"), Map.entry("run.ps1", "powershell"),
                Map.entry("run.cmd", "batch"), Map.entry("Makefile", "make"), Map.entry("CMakeLists.txt", "cmake"), Map.entry("Dockerfile", "dockerfile"),
                Map.entry("README.md", "documents"), Map.entry("app.yaml", "configuration"));
        for (var entry : families.entrySet()) {
            require(registry.forPath(entry.getKey()).id().equals(entry.getValue()), "one source owner for " + entry.getKey());
            require(RepositorySnapshotCollector.isKnowledgeText(entry.getKey()), "knowledge scan retains " + entry.getKey());
        }
        // This is the pre-modularization file-format contract, including case-insensitive text recognition.
        for (String extension : ("java kt kts groovy scala cs fs fsx py pyi js jsx ts tsx mjs cjs vue svelte c h cc cpp cxx hpp hxx go rs rb php "
                + "sql graphql proto thrift avsc xml json json5 yaml yml toml ini conf properties md mdx rst txt adoc csv tsv sh bash zsh fish ps1 bat cmd "
                + "gradle cmake dockerfile gitignore gitattributes").split(" ")) {
            require(RepositorySnapshotCollector.isKnowledgeText("src/file." + extension)
                    && RepositorySnapshotCollector.isKnowledgeText("src/FILE." + extension.toUpperCase(java.util.Locale.ROOT)), "format parity: " + extension);
        }
        for (String name : List.of("dockerfile", "makefile", "rakefile", "gemfile", "procfile", "jenkinsfile", "pom.xml", "package.json", "requirements.txt",
                "pyproject.toml", "cargo.toml", "go.mod", "go.sum", "readme", "license", "agents.md", "claude.md", "review.md", ".dockerignore", ".env.example", ".editorconfig", ".npmrc")) {
            require(RepositorySnapshotCollector.isKnowledgeText(name), "special filename parity: " + name);
        }
        for (String path : List.of(".env", ".env.py", "node_modules/app.js", "vendor/lib.py", "generated/message.proto", "Cargo.lock", "app.min.js", "app.js.map"))
            require(!RepositorySnapshotCollector.isKnowledgeText(path), "common exclusion retained: " + path);
        require(!registry.module("python").capabilities().contains(LanguageModule.Capability.DECLARATION_LOOKUP)
                && registry.module("go").capabilities().contains(LanguageModule.Capability.DEPENDENCY_EVIDENCE), "capabilities distinguish text navigation and semantic helpers");
        fails(() -> registry.forPath("service.py").locate("def service(): pass", "service"));
        String docs = registry.instructions(List.of("README.md"));
        require(docs.contains("Documentation module") && !docs.contains("Python module") && !docs.contains("Rust module")
                && docs.contains("Go helpers:") && docs.contains("Java helpers:"), "documentation activates its own guidance and retains discoverable semantic APIs");
        String mixed = registry.instructions(List.of("main.py", "client.ts"));
        require(mixed.contains("Python module") && mixed.contains("TypeScript module") && !mixed.contains("Ruby module"), "mixed changes activate only relevant language guidance");
        require(registry.instructions(List.of("unknown.custom")).contains("No language-specific review profile matched"), "unknown formats do not enable every language profile");
        String packageJson = registry.instructions(List.of("package.json"));
        require(packageJson.contains("JavaScript module") && !packageJson.contains("Vue module") && !packageJson.contains("Svelte module")
                && !packageJson.contains("TypeScript module"), "a shared package manifest does not establish a frontend framework or TypeScript project");
        require(registry.instructions(List.of("svelte.config.js")).contains("Svelte module"), "framework-specific metadata activates its profile");
    }

    private static void metadataAndNavigation(Path fixture) throws Exception {
        Path repo = repository(fixture.resolve("metadata-repo"));
        write(repo, "pyproject.toml", "[project]\nname = 'root'\n");
        write(repo, "apps/api/pyproject.toml", "[project]\nname = 'api'\n");
        write(repo, "apps/other/pyproject.toml", "[project]\nname = 'unrelated'\n");
        write(repo, "apps/api/service.py", "def handle():\n    return 1\n");
        write(repo, "apps/api/helpers.py", "def helper():\n    return 2\n");
        write(repo, "apps/api/.env.py", "SECRET_FIXTURE_NOT_FOR_EVIDENCE\n");
        write(repo, "apps/web/package.json", "{\"name\":\"web\"}\n");
        write(repo, "apps/web/app.ts", "export const result = 1;\n");
        write(repo, "dotnet/App.csproj", "<Project />\n"); commit(repo, "base");
        write(repo, "apps/api/service.py", "def handle():\n    return helper()\n");
        write(repo, "apps/web/app.ts", "export const result = 2;\n"); commit(repo, "target");
        GitClient git = new GitClient(repo, EXECUTOR);
        var scope = git.resolve(CliOptions.parse(new String[]{"review", "--repo", repo.toString(), "--prepare-only"}));
        var python = LanguageRegistry.defaults().module("python");
        var manifests = python.manifests(LanguageRegistry.repository(git), scope.targetCommit(), List.of("apps/api/service.py"));
        require(manifests.contains("pyproject.toml") && manifests.contains("apps/api/pyproject.toml") && !manifests.contains("apps/other/pyproject.toml"), "nearest project metadata without unrelated nested projects");
        var csharp = LanguageRegistry.defaults().module("csharp").manifests(LanguageRegistry.repository(git), scope.targetCommit(), List.of("dotnet/Program.cs"));
        require(csharp.contains("dotnet/App.csproj"), "wildcard project files are discovered within the relevant project");
        var evidence = new EvidenceCollector(git).collect(scope, null, null, CliOptions.KnowledgeMode.FULL, 80_000);
        require(evidence.markdown().contains("apps/api/pyproject.toml at ") && evidence.markdown().contains("apps/web/package.json at ")
                && evidence.markdown().contains("apps/api/helpers.py") && !evidence.markdown().contains("name = 'unrelated'"), "independent profile metadata and peer navigation");
        var supplemental = new SupplementalEvidence(git, EXECUTOR, Map.of()).collect(evidence, """
                REQUEST_LANGUAGE|target|python|apps/api/service.py|apps/api|index|Find related files
                REQUEST_FILE|target|apps/api/helpers.py|1:2|Read implementation
                REQUEST_FILE|target|apps/api/.env.py|all|Keep global exclusions
                REQUEST_LANGUAGE|target|python|apps/api/service.py|../outside|index|Reject traversal
                REQUEST_LANGUAGE|target|python|apps/api/service.py|package|symbol:helper|Unsupported semantic helper
                """, 0, 0);
        require(supplemental.coverage().contains("INDEX_ONLY") && supplemental.markdown().contains("def helper():")
                && !supplemental.markdown().contains("SECRET_FIXTURE_NOT_FOR_EVIDENCE"), "navigation is separate from actual bounded source reads");
        require(supplemental.coverage().contains("Supplemental requests included: 2/5"), "unsupported capabilities and invalid paths remain explicit gaps");
        var index = new EvidenceIndex(evidence, supplemental.markdown());
        var citation = List.of(new EvidenceIndex.Citation("Python file index at " + scope.targetCommit(), "apps/api/helpers.py"));
        index.validate(citation);
        require(!index.hasImplementation(citation) && !index.hasTarget(citation), "profile navigation cannot serve as implementation proof");
        String classified = EvidenceText.block("CMakeLists.txt at " + scope.targetCommit(), "text", "set(FEATURE_ENABLED ON)")
                + EvidenceText.block("README.md at " + scope.targetCommit(), "text", "The feature is enabled.")
                + EvidenceText.block("values.csv at " + scope.targetCommit(), "text", "name,value\nfeature,enabled");
        var roles = new EvidenceIndex(evidence, classified);
        require(roles.hasImplementation(List.of(new EvidenceIndex.Citation("CMakeLists.txt at " + scope.targetCommit(), "set(FEATURE_ENABLED ON)")))
                && !roles.hasImplementation(List.of(new EvidenceIndex.Citation("README.md at " + scope.targetCommit(), "The feature is enabled.")))
                && roles.hasImplementation(List.of(new EvidenceIndex.Citation("values.csv at " + scope.targetCommit(), "name,value\nfeature,enabled"))),
                "specific build filenames override generic text while documentation and runtime data retain distinct evidence roles");
        var snapshot = new RepositorySnapshotCollector(git).collect("HEAD", 20_000, 100_000, 20_000);
        require(snapshot.chunks().stream().anyMatch(chunk -> chunk.contains("dotnet/App.csproj")), "module metadata formats participate in repository onboarding");
    }

    private static void declarativeExtension(Path fixture) throws Exception {
        Path resources = fixture.resolve("profile-resources");
        write(resources, "META-INF/reviewagent/text-languages", "custom/zeta.properties\n");
        write(resources, "custom/zeta.properties", "id=zeta\nname=Zeta\nsource.extensions=zeta\nmanifests=zeta.project\nreview.hints=Zeta-specific contracts need source evidence.\n");
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{resources.toUri().toURL()}, LanguageModuleTests.class.getClassLoader())) {
            var registry = LanguageRegistry.load(loader);
            require(registry.forPath("main.zeta").id().equals("zeta") && registry.instructions(List.of("main.zeta")).contains("Zeta-specific"), "a resource-only module needs no Java provider or framework branch");
            Path repo = repository(fixture.resolve("profile-repo"));
            write(repo, "zeta.project", "Zeta project metadata\n"); write(repo, "main.zeta", "entry = old\n"); commit(repo, "base");
            write(repo, "main.zeta", "entry = new\n"); commit(repo, "target");
            GitClient git = new GitClient(repo, EXECUTOR);
            var scope = git.resolve(CliOptions.parse(new String[]{"review", "--repo", repo.toString(), "--prepare-only"}));
            var evidence = new EvidenceCollector(git, registry).collect(scope, null, null, CliOptions.KnowledgeMode.FULL, 30_000);
            require(evidence.markdown().contains("Zeta project metadata"), "declarative manifest discovery works through the generic collector");
            require(new RepositorySnapshotCollector(git, registry).collect("HEAD", 20_000, 40_000, 20_000).chunks().stream()
                    .anyMatch(chunk -> chunk.contains("main.zeta")), "declarative formats work in repository knowledge scans");
        }
    }

    private static void semanticReplacement(Path fixture) throws Exception {
        Path resources = fixture.resolve("advanced-resources");
        write(resources, "META-INF/services/reviewagent.LanguageModule", "extension.AdvancedPythonFixture\n");
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{resources.toUri().toURL()}, LanguageModuleTests.class.getClassLoader())) {
            var registry = LanguageRegistry.load(loader);
            require(registry.modules().stream().filter(module -> module.id().equals("python")).count() == 1, "advanced SPI provider replaces the text profile by ID");
            require(registry.forPath("main.py").locate("def main():\n    pass", "main").first() == 2, "the semantic provider owns declaration lookup");
            require(registry.instructions(List.of("README.md")).contains("Advanced Python fixture declaration helper"), "semantic helpers remain discoverable even for cross-language changes");
        }
    }

    private static void pipeline(Path fixture) throws Exception {
        Path repo = repository(fixture.resolve("pipeline-repo"));
        write(repo, "pyproject.toml", "[project]\nname='demo'\n");
        write(repo, "service.py", "def handle(): return 1\n"); write(repo, "helpers.py", "def helper(): return 2\n"); commit(repo, "base");
        write(repo, "service.py", "def handle(): return helper()\n"); commit(repo, "target");
        String stub = """
                set -eu
                prompt=$(cat)
                case "$prompt" in
                  'You are the discovery stage'*)
                    case "$prompt" in *'Python module [python]'*) ;; *) exit 91 ;; esac
                    printf '%s\\n' '<findings/>'
                    case "$prompt" in *'This pass runs after evidence collection'*) ;;
                      *) printf '%s\\n' 'REQUEST_LANGUAGE|target|python|service.py|.|index|Find the helper file' 'REQUEST_FILE|target|helpers.py|all|Inspect implementation' ;; esac ;;
                  'You are the report consolidation'*)
                    printf '%s\\n' '# Review scope' '# Functional and business change' '# Findings' '# Requirement assessment' '# Knowledge-base feedback' '# Verification limits' '<finding-groups/>' ;;
                  *) exit 92 ;;
                esac
                """;
        var args = new ArrayList<>(List.of("review", "--repo", repo.toString(), "--output-dir", fixture.resolve("profile-output").toString(),
                "--agent-command", "/bin/sh", "--agent-arg", "-c", "--agent-arg", stub));
        Path run = new ReviewPipeline(EXECUTOR, Clock.systemUTC()).run(CliOptions.parse(args.toArray(String[]::new)));
        require(Files.readString(run.resolve("supplemental-evidence.md")).contains("def helper(): return 2"), "text language requests work end-to-end");
        String scope = Files.readString(run.resolve("scope.md"));
        require(scope.contains("python=[FILE_INDEX,TEXT_EVIDENCE]") && scope.contains("java=[DECLARATION_LOOKUP,DEPENDENCY_EVIDENCE,TEXT_EVIDENCE]"), "scope records the actual capability tier");
        args.addAll(List.of("--resume", run.toString())); args.set(args.indexOf(stub), "exit 93");
        new ReviewPipeline(EXECUTOR, Clock.systemUTC()).run(CliOptions.parse(args.toArray(String[]::new)));
    }

    private static Path repository(Path path) throws Exception { Files.createDirectories(path); git(path, "init", "-q"); return path; }
    private static void write(Path root, String path, String text) throws IOException { Path file = root.resolve(path); Files.createDirectories(file.getParent()); Files.writeString(file, text); }
    private static void commit(Path repo, String message) throws Exception { git(repo, "add", "--all"); git(repo, "commit", "-qm", message); }
    private static void git(Path repo, String... args) throws Exception {
        var command = new ArrayList<>(List.of("git", "-c", "user.name=Profile Test", "-c", "user.email=test@example.invalid")); command.addAll(List.of(args));
        var result = EXECUTOR.run(command, repo, null, Duration.ofSeconds(10)); require(result.exitCode() == 0, "fixture git: " + result.stderr());
    }
    private static void fails(Check action) throws Exception { try { action.run(); throw new AssertionError("Expected unsupported evidence request"); } catch (IOException expected) { } }
    @FunctionalInterface private interface Check { void run() throws Exception; }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
