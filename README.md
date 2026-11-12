# repo-aware-reviewer

[简体中文](README.zh-CN.md)

An evidence-driven CLI for reviewing committed Git changes against the code, requirements, and a human-maintained business knowledge base. Inspired by Anthropic Code Review, it uses independent reviewers, individual finding verification, and consolidation of confirmed findings.

## Build

Install JDK 21 or later, Bash, and Git. Run this from the project directory to create `build/code-review-agent.jar`.

```bash
bash scripts/build.sh
```

## Use your local Claude Code setup

Both commands default to `claude -p` and reuse your local Claude Code setup. Make sure the `claude` executable is on `PATH` and run this check in the terminal that will launch the JAR. A reply of `OK` confirms the connection.

```bash
printf '%s\n' 'Reply with OK.' | claude -p
```

The default Claude process has built-in tools and MCP tools disabled. The CLI supplies evidence from pinned Git revisions and handles requests for more material. Authentication and model routing use the local Claude Code configuration. To use another executable, pass `--agent-command` and one `--agent-arg` per argument. That command controls its own tool access, reads a UTF-8 prompt from stdin, and writes a UTF-8 reply to stdout.

## Generate a candidate knowledge base

Scan a Git revision to prepare a draft for human review. The command inventories the tracked entries and groups supported text files into evidence chunks. Symlinks and submodule pointers are recorded as skipped.

```bash
java -jar build/code-review-agent.jar knowledge generate --repo /path/to/repo
```

The default revision is `HEAD` and the default language is English. Use `--ref` to select another revision or `--language zh-CN` for Simplified Chinese. Review the resulting `candidate-knowledge.md` before passing the approved version to `review`.

## Usage

Review the latest commit with optional business inputs.

```bash
java -jar build/code-review-agent.jar review \
  --repo /path/to/repo \
  --requirement /path/outside-repo/requirement.md \
  --knowledge /path/outside-repo/knowledge.md
```

`--knowledge` accepts one UTF-8 Markdown file in any layout. `--requirement` accepts a separate UTF-8 requirements file and enables requirement assessment. Both options are optional and take explicit file paths.

Review reports default to English. Add `--language zh-CN` for Simplified Chinese or `--language en` for English. The selected language applies to report headings, explanations, severity labels, and requirement assessments. Code and source paths keep their original spelling.

Add `--knowledge-mode scoped` to load relevant sections from the supplied knowledge base. The CLI keeps Markdown sections intact and prioritizes changed repository paths, API paths, and code identifiers. It also includes sections titled `Global rules` or `全局规则`. Discovery can request more sections for specific behavior changes. The file can live outside the repository. If the same file appears in the reviewed diff, the CLI records its change in `coverage.md` and supplies its content through scoped excerpts. The default mode, `full`, supplies the whole document.

- `--commit <ref>` reviews a commit against its first parent. It defaults to `HEAD` and requires that parent to be available locally.
- `--branch <target-ref> --base <base-ref>` directly compares the specified baseline and target snapshots, equivalent to `git diff BASE TARGET`. Both accept branch names, tags, or commit IDs. These options must be supplied together and cannot be combined with `--commit`.

By default, the CLI reads locally available Git revisions. To fetch the baseline from a configured remote before a new review, add `--base-remote` and give `--base` the branch name on that remote:

```bash
java -jar build/code-review-agent.jar review \
  --repo /path/to/repo \
  --branch <target-commit-id> \
  --base main \
  --base-remote origin
```

For another baseline, use a branch such as `--base release/1.0`; `--base-remote upstream` selects another configured remote. With `--base-remote`, use `main` or `refs/heads/main`, rather than the local tracking name `origin/main`. The target revision must already be available locally. Fetch uses the repository's existing Git authentication and pins the retrieved branch tip for the whole review. This is the remote version obtained at fetch time, independent of merge direction or parent order. The comparison remains a direct diff against that snapshot, including any differences if the target has not incorporated that version. Fetch failures stop the review without falling back to stale local references.

Fetching does not check out or merge code, update local or remote-tracking branches, or overwrite `FETCH_HEAD`. A private ref protects each active review's baseline and is removed when the review ends. Fetch progress uses the same updating line. The default fetch timeout is 120 seconds, configurable with `--fetch-timeout-seconds`. The run directory retains `baseline-fetch-stdout.log`, `baseline-fetch-stderr.log`, `baseline-fetch-execution.log` (command, working directory, start time, timeout, elapsed time, and exit status), and `baseline-fetch-snapshot.log` (remote, branch, pinned commit, and fetch time). `scope.md` records the remote, branch, and resolved commits. `--resume` reuses the saved baseline without fetching again; start a new review to fetch a newer version.

The evidence bundle takes repository review rules from the specified baseline commit, or the target's first parent in single-commit mode.

An empty diff produces a local report marking review and requirement assessment as skipped, without calling the model. This can happen when the target is already the remote baseline tip. To review that commit's own changes, use `--commit <ref>`, or supply an earlier explicit baseline for a larger change.

Three independent reviewers examine correctness and regressions, requirements and business contracts, and boundary conditions and security. Their evidence requests are combined and deduplicated. When new evidence is supplied, another pass checks it for additional candidates. Each candidate then receives separate verification and can request further code, dependency evidence, or knowledge excerpts when a decisive link is missing.

Before publication, candidates describing the same issue are grouped together, including rejected and inconclusive candidates. Each preliminary confirmation is independently adjudicated against the group's evidence and contrary verdicts. Continuing disagreement between publication verdicts keeps the issue unconfirmed. Confirmations must identify how the issue relates to this change and quote included implementation evidence. Newly introduced or exposed failures also need baseline/diff support; an unmet explicit requirement needs a trusted contract quotation. An unrelated pre-existing defect is excluded from this change's findings.

Confirmed findings must also classify their support and list evidence-backed premises. Assumptions about a producer's response, deployment, or reused output directory cannot support a confirmed failure. An explicit requirement to reject malformed input can still support a defensive finding. Native file quotations carry stable evidence IDs, pinned revisions, and actual source spans; finding anchors must lie inside a quoted target span. The citation inventory distinguishes diffs from base/target source files. Source-correction feedback identifies included blocks containing the exact quote, while still requiring the intended file, revision, and source type to match. Repository file discovery uses language-neutral directory/glob requests, with a fallback for older Java directory requests, rather than treating a directory as a dependency coordinate.

When `--requirement` is supplied, observable behaviors receive a separate assessment and independent audit. The audit checks the original requirement wording, implementation evidence, applicable boundaries, and missing components. The CLI renders the audited acceptance section and confirmed findings, so report consolidation cannot substitute its own verdicts. Verbatim quotation checks establish that evidence was supplied; they do not prove the model's reasoning is correct. All conclusions remain static judgments.

Audited unmet behaviors become `REQ-A...` candidates and receive independent verification and publication adjudication. The final acceptance section is reconciled with those verdicts; a rejected or inconclusive allegation remains insufficient evidence and is never automatically marked satisfied. The original audit and item-to-candidate outcomes remain available separately.

Each acceptance item states whether its criterion is explicit or unspecified in the requirements. Unspecified policy cannot receive a conclusive verdict. The audit challenges strengthened paraphrases, numeric priorities, and overlapping failure states. `report.md` shows a concise acceptance summary, placing unmet and unresolved items first; `requirement-assessment.md` retains every item's reasoning, boundaries, policy basis, and verified source references.

`--review-parallelism` controls concurrent review and verification calls and defaults to `3`. Set it to `1` to run the same tasks sequentially. Each call uses the configured agent command and timeout. Review time and model usage grow with the number of candidates, including unmet requirement candidates, publication adjudications, and evidence rounds. A supplied requirements document also adds assessment and audit calls. Further evidence rounds stop when no new material is supplied, requests repeat, or the shared byte budget or configured request counts are exhausted. Unresolved candidates stay unconfirmed and are listed in the report.

Each model process has a default timeout of 900 seconds, configurable with `--agent-timeout-seconds`. Review task artifacts include `*-stdout.log`, `*-stderr.log`, and `*-execution.log` with elapsed time and exit status. Output logs are preserved when a command times out. Progress elapsed time covers the current stage across completed subtasks.

Model replies get bounded correction attempts for serialization, source quotations, and unsupported decisions. Parser recovery is limited to unambiguous closing delimiters and preserves field text; applied changes are saved in `*-normalization.log` alongside the original replies. Exact-source and verdict checks still apply. Citation correction keeps validated requirement clauses and item IDs fixed. Primary-document line selectors can replace retyped clauses, with raw corrections and applied-restoration artifacts saved separately. See [structured response handling](docs/structured-output.md) for the parser rules, citation protocol, and failure limits. To continue an interrupted run, repeat its review command with `--resume /path/to/run-directory`. Saved original and corrected replies are checked for a reusable result before making another model call. Missing or invalid tasks are continued. The resolved scope and initial evidence must match the saved run. Each saved task is reusable only when its prompt, including supplied evidence, still matches; changed tasks are rerun. Concurrency may be changed when resuming. Older runs whose evidence format differs require a new run.

## Language support

Every recognized source language and text format now has a registered module. Source/configuration filenames select the relevant review guidance automatically, including mixed-language changes. `--language` continues to select the report's human language.

| Modules | Available support |
| --- | --- |
| Java, Go | File evidence, declaration lookup, and their documented dependency helpers; Go also provides package/import navigation. |
| Kotlin, Groovy, Scala, C#, F#, Python, JavaScript, TypeScript, Vue, Svelte, C/C++, Rust, Ruby, PHP | Text-based static review, related project metadata, peer-file navigation, and bounded source reads. |
| SQL, GraphQL, Protobuf, Thrift, Avro | Schema/query/contract review guidance and text/file navigation. |
| Shell, PowerShell, batch, Make, CMake, Dockerfile | Script/build review guidance and text/file navigation. |
| Documentation, structured configuration, tabular data | Separate format modules and evidence roles. |

The text/navigation modules do not provide declaration parsers, compiler/type checking, package/import resolution, or third-party dependency loading. Missing decisive evidence stays a review gap. `scope.md` records the registered modules and their declared capabilities. The collector prioritizes explicit filenames such as `CMakeLists.txt` over generic `.txt` recognition and keeps navigation metadata separate from implementation evidence.

Go support adds relevant `go.mod`, `go.sum`, `go.work`, and vendor metadata, package/file indexes, function/type/receiver-method lookup, and on-demand import-consumer lookup. It can read related workspace packages, pinned local replacements, consistent tracked vendor, and version-selected module-cache source, including generated protobuf entities. Missing metadata or unresolved versions remain evidence gaps. Go support runs without an installed Go toolchain and does not download dependencies or run Go commands.

The Go cache defaults to exported `GOMODCACHE`, the first exported `GOPATH` entry's `pkg/mod`, or `~/go/pkg/mod`. Override it with `--language-cache go=/path/to/pkg/mod`. For modules that use a cache, this generic option also accepts registered IDs such as `--language-cache java=/path/to/maven-cache`.

Go declaration extraction is lexical; compiler type checking, cgo expansion, standard-library source resolution, and build-tag/platform selection are not implemented. Package indexes keep test and platform variants visible without assuming they compile together. Basic language modules are independent `.properties` profiles registered in `META-INF/reviewagent/text-languages`; adding one needs no Java code. Specialized helpers implement the public `LanguageModule` interface and register through Java ServiceLoader. A specialized provider can replace a basic profile with the same ID. See [the module guide](docs/language-modules.md) for extension instructions, capability boundaries, and Go resolution limits.

## GitHub pull request reviews

Copy the [example workflow](examples/github-review.yml) into `.github/workflows/` in the repository to review. Set the `ANTHROPIC_API_KEY` Actions secret and point the action reference at a trusted revision. The workflow runs on new or updated same-repository pull requests and on `/review` comments from users with write access. It posts the report and uploads the report, scope, and coverage as an Actions artifact.

Set the `REVIEW_KNOWLEDGE_PATH` repository variable to a tracked file such as `docs/business-knowledge.md` to enable scoped knowledge. The action reads that file from the PR base commit. Use the optional `requirement-path` input for a requirements file from the same commit, or `publish-comment: 'false'` for artifact-only reviews. The workflow fetches the base and head Git objects and runs the CLI with an empty worktree and Claude Code tools disabled.

The Action's `language` input defaults to `en`. The example workflow reads it from the optional `REVIEW_LANGUAGE` repository variable, which also accepts `zh-CN`.

## Output and limits

Progress shows the percentage of completed stages, using one updating line on an interactive stderr and plain log lines when redirected. While a model is running, elapsed time updates without advancing the percentage. Build and test scripts use the same percentage display. Both commands print the run directory on stdout on success. The defaults are `review-output/` and `knowledge-output/`, configurable with `--output-dir`. Add `--prepare-only` to either command to save the local evidence and prompt templates without making model calls.

- `review` writes `report.md`. Use `scope.md` to check resolved commits, input hashes, and concurrency settings, `evidence.md` to inspect supplied material, `evidence-index.md` to trace source IDs, roles, revisions, and available locations, and `coverage.md` to see evidence gaps and each candidate's verification status. `discovery/`, `verification/`, `adjudication/`, and `assessment/` save individual prompts and responses, with subdirectories for additional rounds. `supplemental-evidence.md` records material loaded across those rounds. When requirements are supplied, `requirement-assessment.md` contains the final acceptance details behind the concise report summary. `requirement-assessment-audited.md` preserves the original independent audit, and `assessment-findings.md` links its unmet items to candidate verdicts. `verified-findings.md` contains candidates that passed publication adjudication, before deduplication, and `report-response.md` preserves the consolidation response. An individual verification or publication candidate still invalid after bounded corrections remains unverified while peers finish. The report is marked `INCOMPLETE`, and `review-status.md` lists failed checks. Invocation failures and invalid outputs in required shared stages still stop the run.
- `knowledge generate` writes `candidate-knowledge.md`. Use `inventory.md` to check which files were included or skipped. Both modes save their supporting evidence locally.

During discovery, verification, publication adjudication, and requirement assessment, the collector can load requested tracked text from either pinned commit. Declaration lookup and dependency evidence are supplied by registered language modules. The Java module reads public signatures and `.proto` files from cached, fixed-version direct JAR dependencies in the selected revision's root `pom.xml`; the Go module reads package source using the rules above. Evidence collection does not run the target project's build or tests. For large inputs, `review` exposes `--max-evidence-bytes` and `knowledge generate` exposes `--max-file-bytes` and `--max-total-bytes`.

Supplemental file/dependency requests and knowledge queries default to unlimited counts. Knowledge excerpts also have no section-count limit. Evidence byte budgets still apply. Set `--max-supplemental-requests <count>` to cap combined file and language-module requests (including legacy Maven requests), or `--max-knowledge-queries <count>` to cap scoped knowledge queries. Both default to `0`, meaning unlimited. Positive limits count distinct attempted requests across all stages and rounds, including unavailable results. Repeated requests are not retried, and all supplemental material shares the evidence byte budget. `scope.md` records the limits and `coverage.md` records skipped counts.

## Repository documentation

Root-level Markdown is reserved for filenames starting with `README`, regardless of case. Put other project documentation in a subdirectory such as `docs/`.
