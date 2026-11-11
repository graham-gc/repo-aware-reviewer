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
- `--branch <ref> --base <ref>` reviews the branch from its merge base with the baseline.
- `--range BASE..TARGET` compares two explicit revisions.

The evidence bundle takes repository review rules from the resolved base commit.

Three independent reviewers examine correctness and regressions, requirements and business contracts, and boundary conditions and security. Their evidence requests are combined and deduplicated. When new evidence is supplied, another pass checks it for additional candidates. Each candidate then receives separate verification and can request further code, dependency evidence, or knowledge excerpts when a decisive link is missing.

Before publication, candidates describing the same issue are grouped together, including rejected and inconclusive candidates. Each preliminary confirmation is independently adjudicated against the group's evidence and contrary verdicts. Continuing disagreement between publication verdicts keeps the issue unconfirmed. Confirmations must identify how the issue relates to this change and quote included implementation evidence. Newly introduced or exposed failures also need baseline/diff support; an unmet explicit requirement needs a trusted contract quotation. An unrelated pre-existing defect is excluded from this change's findings.

When `--requirement` is supplied, observable behaviors receive a separate assessment and independent audit. The audit checks the original requirement wording, implementation evidence, applicable boundaries, and missing components. The CLI renders the audited acceptance section and confirmed findings, so report consolidation cannot substitute its own verdicts. Verbatim quotation checks establish that evidence was supplied; they do not prove the model's reasoning is correct. All conclusions remain static judgments.

`--review-parallelism` controls concurrent review and verification calls and defaults to `3`. Set it to `1` to run the same tasks sequentially. Each call uses the configured agent command and timeout. Review time and model usage grow with the number of candidates, publication adjudications, and evidence rounds. A supplied requirements document also adds assessment and audit calls. Further evidence rounds stop when no new material is supplied, requests repeat, or the shared byte budget or configured request counts are exhausted. Unresolved candidates stay unconfirmed and are listed in the report.

Each model process has a default timeout of 900 seconds, configurable with `--agent-timeout-seconds`. Review task artifacts include `*-stdout.log`, `*-stderr.log`, and `*-execution.log` with elapsed time and exit status. Output logs are preserved when a command times out. Progress elapsed time covers the current stage across completed subtasks.

Structured replies accept raw XML and fenced XML. A redundant wrapper around a single finding is normalized before field and identity validation. Invalid structured replies get one format-correction attempt, with the original and corrected responses saved. To continue an interrupted run, repeat its review command with `--resume /path/to/run-directory`. Saved original and corrected replies are checked for a reusable result before making another model call. Missing or invalid tasks are continued. The resolved scope and initial evidence must match the saved run. Each saved task is reusable only when its prompt, including supplied evidence, still matches; changed tasks are rerun. Concurrency may be changed when resuming. Older runs whose evidence format differs require a new run.

## GitHub pull request reviews

Copy the [example workflow](examples/github-review.yml) into `.github/workflows/` in the repository to review. Set the `ANTHROPIC_API_KEY` Actions secret and point the action reference at a trusted revision. The workflow runs on new or updated same-repository pull requests and on `/review` comments from users with write access. It posts the report and uploads the report, scope, and coverage as an Actions artifact.

Set the `REVIEW_KNOWLEDGE_PATH` repository variable to a tracked file such as `docs/business-knowledge.md` to enable scoped knowledge. The action reads that file from the PR base commit. Use the optional `requirement-path` input for a requirements file from the same commit, or `publish-comment: 'false'` for artifact-only reviews. The workflow fetches the base and head Git objects and runs the CLI with an empty worktree and Claude Code tools disabled.

The Action's `language` input defaults to `en`. The example workflow reads it from the optional `REVIEW_LANGUAGE` repository variable, which also accepts `zh-CN`.

## Output and limits

Progress uses one updating line on an interactive stderr and plain log lines when redirected. Both commands print the run directory on stdout on success. The defaults are `review-output/` and `knowledge-output/`, configurable with `--output-dir`. Add `--prepare-only` to either command to save the local evidence and prompt templates without making model calls.

- `review` writes `report.md`. Use `scope.md` to check resolved commits, input hashes, and concurrency settings, `evidence.md` to inspect supplied material, and `coverage.md` to see evidence gaps and each candidate's verification status. `discovery/`, `verification/`, `adjudication/`, and `assessment/` save individual prompts and responses, with subdirectories for additional rounds. `supplemental-evidence.md` records material loaded across those rounds. When requirements are supplied, `requirement-assessment.md` contains the audited acceptance section. `verified-findings.md` contains candidates that passed publication adjudication, before deduplication, and `report-response.md` preserves the consolidation response. Replies that still fail structural validation after correction stop the run.
- `knowledge generate` writes `candidate-knowledge.md`. Use `inventory.md` to check which files were included or skipped. Both modes save their supporting evidence locally.

During discovery, verification, publication adjudication, and requirement assessment, the collector can load requested tracked text from either pinned commit. For Java, it can locate a unique method declaration by name and records the lines it supplies. Dependency evidence is limited to public Java signatures and `.proto` files from cached, fixed-version direct JAR dependencies in the selected revision's root `pom.xml`. Evidence collection does not run the target project's build or tests. For large inputs, `review` exposes `--max-evidence-bytes` and `knowledge generate` exposes `--max-file-bytes` and `--max-total-bytes`.

Supplemental file/dependency requests and knowledge queries default to unlimited counts. Knowledge excerpts also have no section-count limit. Evidence byte budgets still apply. Set `--max-supplemental-requests <count>` to cap combined file and Maven requests, or `--max-knowledge-queries <count>` to cap scoped knowledge queries. Both default to `0`, meaning unlimited. Positive limits count distinct attempted requests across all stages and rounds, including unavailable results. Repeated requests are not retried, and all supplemental material shares the evidence byte budget. `scope.md` records the limits and `coverage.md` records skipped counts.

## Repository documentation

Root-level Markdown is reserved for filenames starting with `README`, regardless of case. Put other project documentation in a subdirectory such as `docs/`.
