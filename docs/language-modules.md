# Language modules

The review pipeline operates on Git evidence, requests, candidate decisions, and reports. Language-specific behavior lives behind the public `reviewagent.LanguageModule` interface. Java and Go provide specialized helpers. The other recognized languages and formats use independent `TextLanguageModule` profiles through the same boundary.

## Registering a language

Basic text/file-navigation support needs only a profile and catalog entry; see the next section. For a specialized provider:

1. Implement `LanguageModule` in a Java class with a public no-argument constructor, or extend `TextLanguageModule` to reuse an existing profile.
2. Add its fully qualified class name to `META-INF/services/reviewagent.LanguageModule` in the provider's resources.
3. Implement the capabilities that the language needs and add integration fixtures for them.
4. Rebuild with `bash scripts/build.sh`. The build includes service descriptors and other runtime resources.

For specialized modules maintained in this repository, the descriptor is `src/main/resources/META-INF/services/reviewagent.LanguageModule`. An external provider or profile-only JAR can also be loaded on the application's classpath:

```bash
java -cp 'build/code-review-agent.jar:/path/to/provider.jar' reviewagent.Main review --repo /path/to/repository
```

The classpath separator is `:` on macOS/Linux and `;` on Windows. `java -jar` runs the modules packaged in the agent JAR. Explicit provider IDs and text profile IDs must each be unique. An explicit provider takes precedence over a text profile with the same ID, so a future parser/dependency resolver can replace basic support without changing the registry or review pipeline.

Adding a language does not require editing `ReviewPipeline`, `ReviewQuality`, evidence-request parsing, or the report renderer. Source formats not covered by an installed module can still use ordinary text evidence and file/range requests.

## Declarative language profiles

The built-in profiles live under `src/main/resources/reviewagent/languages/` and are listed in `src/main/resources/META-INF/reviewagent/text-languages`. They cover Kotlin, Groovy, Scala, C#, F#, Python, JavaScript, TypeScript, Vue, Svelte, C/C++, Rust, Ruby, PHP, SQL, GraphQL, Protobuf, Thrift, Avro, shell, PowerShell, batch, Make, CMake, Dockerfile, documentation, configuration, and tabular data.

A new language can start with a UTF-8 profile such as `example/languages/zeta.properties`:

```properties
id=zeta
name=Zeta
source.extensions=zeta,zs
source.names=Zetafile
manifests=zeta.project,zeta.lock,project/zeta.settings
review.hints=Trace observable behavior through implementation and consumers; do not infer runtime validation from declarations.
index.enabled=true
source.implementation=true
```

Add its classpath resource name to `META-INF/reviewagent/text-languages`:

```text
example/languages/zeta.properties
```

Package these resources in the agent or an external JAR. No Java provider is required. Multiple catalogs on the classpath are merged, and duplicate profile IDs are rejected. Recognition is case-insensitive. Extensions omit the leading dot; names and manifest paths support `*` and `?` within a path component. Explicit source filename patterns take priority over extension matches, so `CMakeLists.txt` belongs to CMake rather than generic documentation. Ties remain ambiguous instead of selecting a parser arbitrarily.

`activation.manifests` can narrow metadata-only review activation separately from file recognition and metadata collection. For example, Vue/Svelte activate from their source or dedicated configuration; a shared `package.json` alone does not establish either framework. Text file-navigation APIs remain discoverable for cross-language reads.

Profiles discover root metadata and matching metadata in ancestors of changed files. An unrelated nested project's configuration is not loaded solely because it exists in the repository. Profiles also recognize their project metadata in repository onboarding. Global exclusions for sensitive environment files, vendored/generated locations, lock-file onboarding, and other excluded artifacts remain in force; adding a recognized extension does not bypass them.

`source.implementation=false` marks documentation-style source as non-implementation evidence. Actual consuming code is still needed when interpreting configuration, schema, or tabular changes. `index.enabled=false` disables automatic peer-file indexing, useful for documentation and broad configuration formats; an explicit navigation request remains available.

Every basic profile supports:

```text
REQUEST_LANGUAGE|target|python|apps/api/service.py|apps/api|index|Find related filenames
REQUEST_FILE|target|apps/api/helpers.py|10:80|Read the implementation
```

The resource is a repository-relative directory, not a language import/package expression. Use `.` for the root. Navigation lists matching immediate files and is marked `INDEX_ONLY`. It never substitutes filenames for actual implementation. Basic profiles have no symbol parser, type checker, import resolver or third-party dependency loader. Unsupported semantic requests remain explicit gaps.

To upgrade a profile, register a specialized provider with the same ID. A subclass can call `super(MyProvider.class, "/reviewagent/languages/python.properties")`, reuse formats/metadata/navigation, and override `locate`, `load`/`open`, and its guidance. Declare the implemented `capabilities()` accurately. `scope.md` records these capabilities, separating basic text/file navigation from declaration and dependency helpers.

## Extension boundary

| Capability | Module method | Framework behavior |
| --- | --- | --- |
| Identify source | `supports(path)` | Dispatches `symbol:` requests to one matching provider. |
| Resolve shared source suffixes | `sourcePriority(path)` | Prioritizes explicit filenames over broad suffix matches; equal priorities remain ambiguous. |
| Classify implementation evidence | `implementationEvidence(path)` | Separates documentation from implementation; explicit build filenames can override generic text classification. |
| Declare capabilities | `capabilities()` | Records text, file-index, declaration and dependency support without inferring runtime validation. |
| Identify other text | `isText(path)` | Recognizes language configuration during repository knowledge scans. Existing exclusion rules still apply. |
| Activate review guidance | `reviewAppliesTo(path)` | Separates source recognition from ambiguous/shared manifest ownership. |
| Select manifests | `manifests(repository, revision, changedPaths)` | Loads eligible tracked manifests under the common byte budget. |
| Supply navigation context | `context(...)` | Includes bounded package/file metadata and records inclusion. |
| Locate declarations | `locate(source, symbol)` | Reads the selected pinned file and enforces the 200-line excerpt limit. |
| Supply dependency/package evidence | `load(request, environment, maxBytes)` | Dispatches a generic `REQUEST_LANGUAGE` and enforces the shared request count and byte budget. |
| Reuse parsing within a run | `open(environment)` | Creates a collector-local session returning `LanguageModule.Evidence` (Markdown and coverage status). Its default wraps `load` as `INCLUDED`; Go reuses workspace and module-graph metadata and distinguishes `INDEX_ONLY`. |
| Explain source review | `instructions()` | Adds guidance only for relevant source/configuration modules, including mixed-language changes. |
| Explain helper requests | `requestInstructions()` | Adds navigation APIs for active profiles and keeps semantic APIs discoverable for cross-language/schema changes. |
| Locate local cache | `defaultCache()` | May be overridden with `--language-cache language-id=/path`. |

The public `Repository` facade exposes only regular tracked files from the requested immutable Git revision. It inventories object IDs once and caches bounded text within a collection session. Modules must not replace this with live worktree reads when supplying repository evidence.

`Environment` supplies the repository, the module's cache directory, and a command adapter for trusted introspection tools. Providers are trusted application code. Commands embedded in repository content or model requests must never be executed. Go does not use the command adapter; Java uses JDK `javap` to inspect public class signatures. Builds, tests, generators, and repository scripts remain outside static evidence collection.

Use `LanguageModule.block(title, language, content)` to create fenced evidence. Use `index` or `metadata` as the block language for navigation material: these blocks cannot serve as implementation quotations for confirmed findings. Give implementation blocks their source path, selected revision/version, line range where applicable, and cache-content hash. Candidate descriptions are not evidence.

## Request protocol

The common protocol is:

```text
REQUEST_FILE|target|src/file.ext|symbol:Name|Why the declaration is needed
REQUEST_LANGUAGE|target|language-id|tracked/context/file|resource|selector|Why the evidence is needed
```

`base` and `target` are resolved by the framework. The provider defines its resource and selector syntax. All file and language requests share `--max-supplemental-requests` and the supplemental byte budget across stages and rounds. Repeated requests are not retried merely because their reason changes. Unavailable material remains a recorded gap. Java retains `REQUEST_MAVEN` as a legacy alias.

## Go behavior

Go support is implemented in Java and does not require an installed Go toolchain. It provides lexical declaration extraction, package/file indexes, import-consumer lookup, and offline dependency source evidence. It does not provide compiler type checking, automatic call-graph construction, cgo expansion, or standard-library source resolution.

Examples:

```text
REQUEST_FILE|target|internal/service/service.go|symbol:Service.Handle|Trace the changed method
REQUEST_LANGUAGE|target|go|internal/service/service.go|example.com/project/internal/orders|index|Find related declarations
REQUEST_LANGUAGE|target|go|internal/service/service.go|example.com/project/internal/orders|symbol:Order|Read the local entity
REQUEST_LANGUAGE|target|go|internal/service/service.go|example.com/protocol/api|symbol:Request|Read the protobuf entity
REQUEST_LANGUAGE|base|go|internal/service/service.go|example.com/protocol/api|file:message.pb.go:20:90|Compare the old entity
REQUEST_LANGUAGE|target|go|go.mod|example.com/protocol|importers|Find consumers after a dependency update
```

The context selects its nearest tracked `go.mod` and enclosing tracked `go.work`. Workspace `use` modules and local replacements are read only from the pinned repository. Outside-repository paths, unselected nested modules, conflicting replacements, and symlinked source are rejected. Workspace replacements take precedence over module replacements.

When automatic vendoring applies, a tracked `vendor/modules.txt` is checked against the selected main-module requirements and replacement declarations before package source is supplied. Inconsistent vendor content is reported; the agent does not silently fall back to another copy. Explicit compiler flags or external workspace configuration may change production's source selection and need separate evidence.

Otherwise, Go reads cached module metadata to perform offline minimal version selection, including graph pruning for newer modules and full transitive expansion where older modules require it. Missing metadata, excluded-version changes requiring further resolution, or metadata inspection limits produce an evidence gap. It does not select a convenient cached version or interpret `go.sum` as a lock file. Selected dependency evidence records the version, decisive requirement metadata hash, and source-file hash. Cache contents are observed locally; the agent does not authenticate them against a remote checksum service or execute `go mod verify`.

Metadata inspection is bounded to 2,048 graph states and 4 MB per context; package declaration and import-consumer inspection each have an 8 MB source-read ceiling, with a 1 MB per-file ceiling. These are read safeguards, not limits on the number of evidence requests. The shared evidence byte budget still controls what reaches the model. Oversized or incomplete reads are reported as gaps or partial indexes.

The default module-cache location follows exported `GOMODCACHE`, then the first exported `GOPATH` entry's `pkg/mod`, then `~/go/pkg/mod`. Persisted `go env -w` settings are not queried. Use `--language-cache go=/actual/cache/path` when needed. No dependency is automatically downloaded.

Go package files, including generated protobuf files and test/platform variants, are candidates for inspection; dot- and underscore-prefixed filenames are excluded from package reads. Build constraints are shown but not evaluated. Ambiguous symbols across files are rejected; request a particular file/range and establish the applicable build context before making a claim. Package and import indexes identify where to read next and do not prove runtime behavior.

If a requested symbol is ambiguous, missing, or spans more than 200 lines, the Go module attempts to supply a bounded navigation index instead. Coverage marks this `INDEX_ONLY`; the declaration body has not been supplied. This lets a subsequent evidence round request the exact file/range without treating navigation as verification. If even the index cannot fit, the request remains unavailable. File selectors accept a filename in the resource directory; choose the schema directory as the resource when requesting a `.proto` file that has no generated Go files beside it.

Go module semantics are described in the [official Go Modules Reference](https://go.dev/ref/mod), including version selection, workspaces, vendoring, and cache layout.
