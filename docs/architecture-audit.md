# Architecture and extensibility audit

Date: 2026-11-12.

Scope: the local CLI implementation, language extensions, evidence collection, review workflow, model task execution, persistence, and reporting. This inspection includes the current uncommitted XML text-field fixes on top of `e002b28`. It does not implement a refactor, expand CI, execute a reviewed repository, or call a model.

Related quality work: [review quality backlog](review-quality-backlog.md).

## Assessment

The project has a usable extension boundary for languages and a working CLI foundation. The central evidence and task contracts remain substantially string-based, and workflow, protocol validation, and artifact concerns are mixed. Targeted refactoring is justified before adding more review capabilities. A rewrite, framework migration, or multi-module build is not justified by the current size alone.

The inspected working tree contains 46 production Java files, approximately 5,876 lines, and 10 test Java files. These are size observations, not quality scores. A read-only `jdeps -verbose:class -filter:none` inspection of the built JAR, collapsing nested classes to their owning top-level classes, found 173 internal dependency edges and one multi-class dependency cycle. This measures compiled type references; it does not measure semantic coupling through strings or resources.

| Extension | Current assessment | Main reason |
| --- | --- | --- |
| Add a basic language/format | Good | Declarative profiles and merged resource catalogs. |
| Add a specialized language helper | Usable, with limits | Public ServiceLoader SPI and external-provider tests; operation semantics are still bundled into a generic request. |
| Add repository navigation | Weak | General file discovery is partly implemented by language providers and can be routed to a dependency resolver. |
| Add or change a review phase | Costly | Pipeline construction, numeric progress stages, task names, prompts, validation, and artifacts must be coordinated. |
| Add a response/report format | Costly | Protocol extraction, domain checks, and Markdown rendering share a central class. |
| Reuse task execution for onboarding | Partial | Review tasks have repair/resume behavior; knowledge generation invokes the agent directly. |
| Wrap the core in another entry point later | Partial | CLI options, stderr progress, paths, and concrete infrastructure reach the workflow directly. |

## Boundaries worth preserving

- [LanguageModule](../src/main/java/reviewagent/LanguageModule.java) is a public extension API with repository reads and explicit capability declarations. Profiles and specialized providers can be packaged outside the core JAR. Existing tests include external-package providers and profile replacement. This is a real extension seam, not merely a directory convention.
- [GitClient](../src/main/java/reviewagent/GitClient.java) and [RemoteBaseline](../src/main/java/reviewagent/RemoteBaseline.java) resolve immutable snapshots. Keep explicit baseline selection, per-run fetched pins, and source reads that do not require checking out or executing the target project.
- [AgentRunner](../src/main/java/reviewagent/AgentRunner.java) isolates command/stdin/stdout transport and timeout diagnostics. Keep Claude Code as the default harness and continue letting Git and Claude Code resolve their own authentication.
- Independent discovery, per-candidate verification, supplemental evidence rounds, publication adjudication, and a separate requirements audit are useful workflow concepts. Refactoring should preserve their distinct responsibilities.
- Prompt/evidence checks before cache reuse, source-role checks, and exact quotation validation are valuable safeguards. Splitting classes must not remove them to make a run complete more easily.

## A1: Evidence is serialized too early and reconstructed from presentation

Priority: high.

[EvidenceBundle](../src/main/java/reviewagent/EvidenceBundle.java) stores Markdown and coverage strings alongside scope and changed paths. [EvidenceCollector](../src/main/java/reviewagent/EvidenceCollector.java), [SupplementalEvidence](../src/main/java/reviewagent/SupplementalEvidence.java), language providers, and scoped knowledge emit formatted blocks. [EvidenceIndex](../src/main/java/reviewagent/EvidenceIndex.java), especially its constructor, then parses those blocks and infers revision, source kind, and implementation eligibility from titles, fence languages, filename suffixes, and registry defaults.

This makes presentation part of the validation contract. The recent role-suffix and knowledge-title lookup failures are direct examples. The current source record also has no verified file-line span, so the validator cannot ground the line numbers written in finding prose.

Introduce a typed evidence catalog before rendering. A source/excerpt should retain at least:

- Stable source ID and content digest.
- Kind, such as repository source, diff, metadata, human knowledge, or external requirement.
- Repository identity, pinned revision, path, and original line span where applicable.
- Diff side/hunk mapping when the material is a patch.
- Raw content and inclusion/truncation status.

Knowledge and external requirements need document identity, hash, and section/span information instead of a fabricated Git revision. Render Markdown from this catalog for the model and artifacts; resolve citations against the catalog directly. Display names and compatibility aliases belong at the protocol boundary, not in the authority model.

This supports RQ-004 and gives semantic review gates better inputs, but it cannot itself prove that model reasoning is correct.

## A2: Repository discovery and language dependency requests share an overloaded path

Priority: high.

[EvidenceQuery](../src/main/java/reviewagent/EvidenceQuery.java) expresses operations through several loosely related strings. [SupplementalEvidence](../src/main/java/reviewagent/SupplementalEvidence.java) sends every non-file language request to `LanguageModule.Session.load`. In [JavaLanguageModule](../src/main/java/reviewagent/JavaLanguageModule.java), `load` always delegates to Maven evidence. In contrast, basic text modules use the same request shape for directory indexes, and Go interprets it for package and dependency operations. The capability enum is descriptive; it does not select a typed operation in this router.

Consequently, asking the Java helper to discover a controller directory produces a Maven-coordinate error. Adding more languages will not solve this underlying mismatch.

Introduce language-neutral repository browsing and explicit operations, for example:

```text
FindFiles(revision, directory-or-pattern)
ReadFile(revision, path, selector)
LocateDeclaration(revision, path, symbol)
ResolveDependency(revision, language, context, dependency, selector)
QueryKnowledge(document, query)
```

The exact names are illustrative. Map the existing text protocol to these operations through an adapter, rather than breaking every existing prompt and external provider at once. Route language-specific work only when the requested capability is implemented. Return typed reasons such as unsupported capability, missing tracked path, unresolved dependency, or exhausted budget. Keep alternative reads distinct from repeated identical requests.

## A3: A concrete language-to-scanner dependency cycle exists

Priority: high; small initial extraction.

Compiled dependencies and source references confirm:

```text
LanguageRegistry
  -> TextLanguageModule
  -> RepositorySnapshotCollector
  -> LanguageRegistry
```

The registry constructs profiles; `TextLanguageModule` calls `RepositorySnapshotCollector.allowedTextLocation` while navigating; the snapshot collector uses the registry for file recognition. [SupplementalEvidence](../src/main/java/reviewagent/SupplementalEvidence.java) also depends on the snapshot collector's policy helpers. This is a type-reference and layering cycle, not evidence of an infinite runtime call or a current crash.

Extract a small shared path/evidence eligibility policy, preserving the current exclusions. Both repository scanning and file navigation should depend on that policy; language modules should not depend on the onboarding scanner. Do not scatter duplicate exclusion lists into providers.

[LanguageRegistry](../src/main/java/reviewagent/LanguageRegistry.java) also creates repository caches and command adapters and checks `instanceof TextLanguageModule` when advertising navigation. Move these adapters out gradually and prefer declared capabilities over concrete provider classes.

## A4: Task execution mixes infrastructure with review-specific correction policy

Priority: high.

[ReviewTasks](../src/main/java/reviewagent/ReviewTasks.java) owns concurrency, subprocess invocation, artifact names, cache reuse, response validation, and correction prompts. Its correction text knows about `requirementText`, unconfirmed findings, and source quotations. Its `Validator` returns `void`; successful replies remain strings and are parsed again in [ReviewQuality](../src/main/java/reviewagent/ReviewQuality.java) and [ReviewPipeline](../src/main/java/reviewagent/ReviewPipeline.java).

Use a narrow typed task boundary such as `Task<T>` with a response codec and a task-specific validation/repair policy. Return the validated value plus raw-response provenance. Keep transport, bounded scheduling, and execution logging separate from domain rules. Parse once per validated response where practical.

The existing [AgentRunner](../src/main/java/reviewagent/AgentRunner.java) should remain a CLI transport adapter. A small model-task port can make deterministic tests simpler; it does not require adding an HTTP model backend now. [KnowledgePipeline](../src/main/java/reviewagent/KnowledgePipeline.java) can reuse execution/logging facilities while retaining a different output contract and workflow.

## A5: Workflow stages, CLI configuration, progress, and artifact layout are coupled

Priority: medium.

[ReviewPipeline](../src/main/java/reviewagent/ReviewPipeline.java) constructs infrastructure, resolves/fetches snapshots, reads documents, prepares evidence, selects reviewers, calls every phase, manages resume checks, writes artifacts, and updates progress. [ReviewQuality](../src/main/java/reviewagent/ReviewQuality.java) directly references numeric stages 5, 6, and 7 and writes files itself. Adding a phase requires coordinating these positions with the pipeline's total stage count.

Introduce a review request/context independent of `CliOptions`, named stage identities, and progress events. Let a small workflow service coordinate domain steps through supplied collaborators. CLI parsing and terminal rendering should stay at the entry boundary. This can be ordinary Java construction; no DI container, general workflow DSL, or user-configurable DAG is necessary.

Separate pure policy decisions, such as whether a verdict is publishable or contradictory, from the loop that executes model tasks and reads more evidence. This makes the quality backlog testable without shell agents.

## A6: Protocol, semantic validation, and report rendering have become one change surface

Priority: high for quality work; extract gradually.

[ReviewOutput](../src/main/java/reviewagent/ReviewOutput.java) currently owns response extraction, secure XML parsing, field validation, candidate decoding, evidence grounding, requirement identity checks, grouping, localization, and Markdown rendering. [PromptFactory](../src/main/java/reviewagent/PromptFactory.java) repeats the output schema in prompt strings; [XmlTextFields](../src/main/java/reviewagent/XmlTextFields.java) maintains the scalar-field vocabulary separately.

Split transport codecs from domain result records, evidence/requirement validators, publication policy, and report rendering. Establish one explicit schema definition, or consistency checks between the declared schema, codecs, and text-field handling. Moving prompt strings into files alone does not establish this boundary.

Keep the present wire format during the first extraction. Switching to JSON does not by itself resolve embedded-code escaping, hallucinated quotations, strengthened requirements, or unsupported causal claims.

Render standard labels and empty values in code. Keep a concise report distinct from a detailed acceptance artifact. This addresses RQ-005 without weakening individual behavior coverage.

## A7: Persistence and resume contracts are implicit

Priority: medium.

Artifact writes are spread across `AgentRunner`, `RemoteBaseline`, `ReviewTasks`, `ReviewQuality`, `ReviewPipeline`, and `KnowledgePipeline`. Review resume checks compare rendered `scope.md` and `evidence.md`; remote-baseline resume extracts a commit from a Markdown line. Task cache reuse compares prompt text and revalidates replies, which is useful, but there is no explicit versioned run/task manifest.

Extract an artifact/run store with explicit immutable scope, input/evidence digests, stage/task identities, and schema/policy versions. Human-readable Markdown should be a view of that state. Define version compatibility and invalidation rules before migrating old runs. Keep model execution details traceable without persisting secrets; deciding whether a backend change invalidates a valid cached result should be an explicit policy rather than an accidental side effect.

A shared store also avoids different run-directory allocation behavior in review and knowledge generation.

## A8: Scoped knowledge combines document parsing, retrieval, delivery state, and formatting

Priority: medium, after the evidence contract.

[ScopedKnowledge](../src/main/java/reviewagent/ScopedKnowledge.java) parses Markdown sections, tokenizes Chinese and English, matches paths/symbols/endpoints, ranks queries, tracks supplied sections, applies byte budgets, and emits Markdown and coverage. This is still a manageable implementation, but changing retrieval or section identity touches delivery and presentation.

Separate section parsing/indexing, retrieval strategy, per-run selection state, and rendering around typed sections. Preserve human-supplied knowledge as the authority and keep inferred onboarding output a candidate. This does not require introducing a vector database or building knowledge maintenance into the agent.

## Suggested sequence

1. Preserve minimized, sanitized protocol and review-quality regression cases; record expected failure categories and policy outcomes. Existing format tests and shell integration fixtures are useful but do not measure semantic review accuracy.
2. Extract shared path policy to remove A3's cycle, and add generic repository navigation with explicit operation/result contracts for A2.
3. Introduce the typed evidence catalog and verified spans from A1 behind current Markdown renderers. Keep legacy request/provider adapters during migration.
4. Extract codecs, domain validators, publication policy, and renderers from A6. Use these in typed tasks from A4, with an explicit run store from A7.
5. Simplify orchestration into named stages and a CLI-independent request/context. Split scoped knowledge responsibilities where its contract now needs to change.
6. Revisit report presentation and additional language capabilities against these boundaries. Do not make new wrappers or CI expansion the driver of this work.

For each slice, preserve CLI behavior, fixed Git snapshots, static-only defaults, evidence budgets, source roles, external language-provider loading, bounded corrections, and cache revalidation. Compare generated artifacts through the intended compatibility policy. A package rename alone is not evidence of improved extensibility, and these architecture changes do not replace the semantic quality work in RQ-001/RQ-002.

## Initial refactor implemented

The measurements above describe the pre-refactor baseline. The initial implementation separates the highest-impact boundaries without changing CLI flags or the public `LanguageModule` SPI:

| Responsibility | Owner |
| --- | --- |
| Common eligible-path policy | `RepositoryPathPolicy` |
| Immutable Git file adapter | `GitRepository` |
| Language-neutral directory/glob navigation | `RepositoryBrowser`, typed `EvidenceQuery.Operation` |
| Evidence identity, role, revision and span binding | `EvidenceSource`, `EvidenceCatalog`, `EvidenceIndex` |
| Legacy provider Markdown compatibility | `LegacyEvidenceAdapter` |
| XML transport/scalar decoding | `ReviewXml`, `XmlTextFields` |
| Structured protocol/domain values | `ReviewOutput`, `ReviewSupport` |
| Publication and acceptance grounding rules | `ReviewGrounding` |
| Deterministic detailed/concise presentation | `ReviewReport` |

Native collectors construct provenance directly from read operations. Supplemental rounds retain catalogs rather than reconstructing source roles from the accumulated Markdown. Existing language providers may continue returning Markdown; their adapter explicitly records the legacy boundary and does not claim native location authority. `TextLanguageModule` no longer refers to `RepositorySnapshotCollector`, removing the reported language/scanner type-reference cycle. Registry construction of the Git adapter is now a factory delegation.

Semantic decision failures have a separate bounded correction category from XML formatting and source-quotation failures. Corrections and cache invalidation preserve task artifacts; changed prompts cannot reuse prior verdicts. Reports retain the detailed independent assessment for publication adjudication while using a concise summary for readers.

Deferred work remains: generic typed task results, a dedicated artifact store, named pipeline stages, a versioned resume manifest, and smaller knowledge-selection components. These are follow-up boundaries, not prerequisites for the evidence/navigation fixes above. No claim is made that adding interfaces eliminates reasoning errors. See [review-quality-backlog.md](review-quality-backlog.md) for regression coverage and remaining semantic limitations.
