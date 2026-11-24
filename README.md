# repo-aware-reviewer

[简体中文](README.zh-CN.md)

Review committed Git changes through your local Claude Code setup, with optional requirements and a human-approved business knowledge base. Reports describe static findings supported by code evidence.

## First use

### 1. Prepare the CLI

Install Java 21 or later, Git and Bash, and have a working, configured `claude` command. The project to review must be available locally, including the target commit and its parent.

For a published version, download `code-review-agent.jar` from [Releases](https://github.com/graham-gc/repo-aware-reviewer/releases) and substitute its path in the examples below. To build from source, use JDK 21 or later and `curl`. The first build downloads and verifies the Java dependencies from Maven Central.

```bash
git clone https://github.com/graham-gc/repo-aware-reviewer.git
cd repo-aware-reviewer
bash scripts/build.sh
```

Check your Claude Code setup before your first review.

```bash
printf '%s\n' 'Reply with OK.' | claude -p
```

Once the check returns `OK`, the CLI can reuse your local Claude Code authentication and model routing; no separate API key setup is required. If you already have this tool's checkout, enter its directory and build it.

The examples below assume you built the JAR from source and are running from this tool's project directory. `/path/to/project` refers to the project being reviewed. Replace example paths and `abc1234`. When running elsewhere, use an absolute JAR path.

### 2. Run your first code review

```bash
java -jar build/code-review-agent.jar review \
  --repo /path/to/project \
  --language en
```

This minimal command reviews **`HEAD` against its first parent**. It does not review uncommitted changes, fetch remote code, or automatically generate or load a business knowledge base. Without a supplied requirements document, it does not perform external requirement-by-requirement acceptance assessment.

Reviews are static: the tool does not run the target project's build or tests. English is the default output language; use `--language zh-CN` for Simplified Chinese or `--language en` for English. Review prompts, model decisions and intermediate artifacts use English. The reviewer reads requirements and knowledge in their original language. A Chinese report is translated from the completed English report as the final step.

The command prints its run directory. Open `report.md` and check `review-status.md`. You can review code immediately; for business review without an existing approved knowledge base, start with scenario 1 below.

## Workflow

Use a regular review to find defects in a project change. Use a blind evaluation when deciding whether a change to the reviewer improves the quality of its reports.

```mermaid
flowchart TD
    S{"What do you want to learn?"}
    S -->|Review code| R["Choose a repository and Git range<br/>Add requirements or knowledge if useful"]
    S -->|Evaluate the reviewer| E["Choose a real change and the reviewer variants"]
    E --> C["Prepare a case from the target repository<br/>Pin inputs and draft scoring criteria"]
    C --> B["Check the criteria, then run the selected variants<br/>Use the same pinned change and documents"]

    subgraph REVIEW["Single review"]
        P1["Resolve the two Git revisions"] --> P2["Collect the diff, source and business evidence"]
        P2 --> Q{"Which review route applies?"}
        Q -->|Small Markdown-only edits| DOC["Review the complete diffs in one pass<br/>Resolve citations to Git lines"]
        Q -->|Bounded implementation with related edits| L["Review implementation, related tests and docs together<br/>Validate source citations"]
        Q -->|Other multi-file changes or risk signals| F["Review selected risk areas and the remaining diff excerpts"]
        Q -->|Deep review requested or required| P3["Initial reviewers independently propose findings"]
        F --> FC["If a confirmed candidate has a broken citation,<br/>try one correction with the supplied source"]
        FC --> FN{"Did the candidate receive all required evidence with new material?"}
        FN -->|Yes| FV["Recheck only that candidate"]
        FN -->|No| FP["Check impact and severity against the evidence<br/>Summarize the final decisions"]
        FV --> FP
        FP --> P8
        L -->|Required evidence available| T["Verify the affected candidate"]
        L -->|Broader risk or conflicting conclusions| P3
        T -->|Broader risk| P3
        L -->|Ready or no new evidence| P8
        DOC -->|Documentation evidence is sufficient| P8
        DOC -->|A concrete issue needs implementation evidence| P3
        T -->|Ready| P8
        P3 --> P4["Provide additional evidence on request"]
        P4 --> P5["Group proven duplicates and prepare candidate evidence<br/>Verify ready questions independently"]
        P5 --> P6["Assess supplied requirements"]
        P6 --> P7["Adjudicate and deduplicate findings"]
        P7 --> P8["Write the report, coverage and review status"]
    end

    R --> P1
    B -->|JAR variants| P1
    B -->|Optional PR-Agent| PA["Review the pinned diff and supplied context"]
    P8 -->|Regular review| O["One report<br/>Resume unfinished checks if needed"]
    P8 -->|Evaluation report| A["Randomly label the evaluation reports"]
    PA --> A
    C -.->|Criteria and any verified reference| J["One grader scores the anonymous reports"]
    A --> J
    J --> D["Reveal the variants and compare scores and timings"]
```

## Common scenarios

### 1. Onboard a project: generate a candidate business knowledge base

```bash
java -jar build/code-review-agent.jar knowledge generate \
  --repo /path/to/project \
  --ref HEAD \
  --language en
```

Inventory tracked files at local `HEAD` and analyze supported text in chunks to produce `candidate-knowledge.md`. The run directory defaults to `knowledge-output/`. Omitting `--ref` also selects `HEAD`.

Review and correct the candidate, save an approved knowledge file, then pass it through `--knowledge`. A candidate is never automatically approved. Skip this step if you already maintain a knowledge base.

### 2. Review a specific commit against requirements and approved knowledge

```bash
java -jar build/code-review-agent.jar review \
  --repo /path/to/project \
  --commit abc1234 \
  --requirement /path/to/review-docs/requirement.md \
  --knowledge /path/to/review-docs/knowledge.md \
  --knowledge-mode scoped \
  --language en
```

Compare `abc1234` with its first parent and assess the supplied requirements. `--knowledge` takes one human-approved UTF-8 Markdown file; `--requirement` takes a separate UTF-8 requirements file. Both use explicit paths, are optional independently, and can live outside the reviewed repository.

For code review alone, omit `--requirement`, `--knowledge`, and `--knowledge-mode`. You can also supply just requirements or just knowledge. `--knowledge-mode scoped` requires `--knowledge` and loads relevant sections; without that mode, the whole knowledge document is supplied. Omitting `--commit` selects `HEAD`.

### 3. Compare two local revisions

```bash
java -jar build/code-review-agent.jar review \
  --repo /path/to/project \
  --branch HEAD \
  --base main \
  --language en
```

Directly compare local `main` and `HEAD` snapshots, equivalent to `git diff main HEAD`. Replace `HEAD` with a development branch or commit ID and `main` with another locally available baseline. This does not update local `main`.

### 4. Compare against the latest remote baseline

```bash
java -jar build/code-review-agent.jar review \
  --repo /path/to/project \
  --branch HEAD \
  --base main \
  --base-remote origin \
  --language en
```

Fetch remote `main` from the configured `origin`, then directly compare it with local `HEAD`. The target revision must already exist locally. Fetch uses the repository's existing Git authentication; this tool does not require a separate Git token parameter.

Another baseline can be selected with `--base release/1.0`. With `--base-remote`, use the remote branch name `main`, not `origin/main`. `--branch` and `--base` must be supplied together and cannot be combined with `--commit`. Add the document options from scenario 2 for business checks.

### 5. Resume an interrupted or incomplete review

This example resumes the minimal command from first use:

```bash
java -jar build/code-review-agent.jar review \
  --repo /path/to/project \
  --language en \
  --resume /path/to/previous-run \
  --review-parallelism 1
```

Replace `/path/to/previous-run` with the earlier run directory. Preserve any commit, branch, baseline, requirements, knowledge and other input settings from the original command. Keep the resolved target commit, document contents and evidence settings unchanged; if the original command used `HEAD`, it must still point to that commit. Saved tasks are revalidated; valid matches are reused, while invalid, missing or changed prompts continue.

Use `--review-parallelism 1` to run model requests sequentially. Resume reuses the pinned remote baseline without fetching again. Start a new review to fetch a newer baseline.

Review concurrency is automatic by default. Initial discovery uses the active role count, verification uses the number of problems awaiting verification, and publication adjudication uses the number of remaining reconciliation groups with work to do. If the configured gateway advertises `max_concurrent_requests` in model metadata, the CLI caps the stage at the smaller of the work count and the advertised limit. A stage spanning several models uses the lowest advertised limit. Without this field, the work count sets the ceiling and the controller adjusts to provider rate-limit feedback. Set a numeric `--review-parallelism` for fixed scheduling, or add `--review-parallelism auto --review-max-parallelism 4` for an explicit additional cap.

### 6. Inspect scope and evidence without calling a model

```bash
java -jar build/code-review-agent.jar review \
  --repo /path/to/project \
  --commit abc1234 \
  --language en \
  --prepare-only
```

Save the scope, evidence and prompts without producing a final review verdict. `knowledge generate` also accepts `--prepare-only`.

## Results and common settings

Default output roots are relative to the **terminal's current working directory**: `review-output/` for reviews and `knowledge-output/` for candidate knowledge. Add `--output-dir /path/to/output` to change the location.

- `report.md`: findings and acceptance assessment; `review-status.md`: whether the review finished.
- `scope.md`: the actual compared commits; `coverage.md`: evidence gaps and verification coverage.
- `timings.md`: current execution calls, input bytes, stage wall time and cache reuse.
- `candidate-knowledge.md`: a draft for human review, not approved rules.

The report's Findings section separates confirmed defects, risks requiring human review, and unfinished checks. A completed review can still contain review-required items; their full evidence and decisions remain in `coverage.md`.

| Exit code | Meaning |
| --- | --- |
| `0` | Output completed, prepared only, or skipped for an empty diff; not code approval. |
| `3` | Review incomplete; inspect the report and use `--resume`. |
| `1` | Execution failed; check the terminal error and invocation logs. |
| `2` | Invalid command arguments. |

Each model call defaults to a 900-second timeout, configurable with `--agent-timeout-seconds`. Empty responses and recognized temporary errors allow at most one extra retry by default when observed process cleanup is verified; disable this with `--agent-retries 0`. Unfinished checks are distinct from business evidence gaps. Completed review does not establish that all requirements are satisfied or that tests actually ran and passed.

Review defaults to `--review-mode auto`. Small source changes with complete evidence can finish in one model call. One bounded implementation edit can enter the same route with its directly related test or documentation diffs. For example, a test in the matching module can qualify by following a naming convention or referencing the implementation in its added lines. A document can qualify by pointing to the implementation path. The local review can escalate if it identifies a broader effect.

A grouped local review starts with complete diffs and nearby implementation lines at both revisions. It can request another tracked excerpt to resolve an individual finding.

A small change limited to existing Markdown READMEs or files under `docs/` can also finish in one call. The CLI resolves cited lines to the pinned Git revision and lists model observations for human review. A specific issue that needs implementation or contract evidence can trigger deep review.

For other multi-file changes or detected risk signals, the CLI gives related files and more source context to a scoped risk check, and reviews the remaining files with bounded diff excerpts. A scoped candidate can receive one citation correction using available source. For a requested follow-up, the CLI rechecks a candidate once every required excerpt is available and at least one adds evidence. The scoped report summarizes the final decisions. A confirmed finding needs target evidence for its stated impact, and high or critical severity also needs evidence of the material effect or affected scope. See `coverage.md` for evidence gaps and publishing decisions.

Trusted baseline repository rules, external requirements, and knowledge documents use the full deep workflow in automatic mode.

Use `--review-mode scoped-rules` to try the experimental scoped path for repositories with trusted baseline rules. It reviews complete diff partitions, adds a narrow independent check for detected input parsing changes, and requests follow-up evidence per candidate. Keep the same option when resuming the experiment. A partition that cannot be assessed can fall back to deep review.

When explicit Python sibling imports link changed durable state to a changed external-effect caller, the scoped request groups those implementations and their identified dependencies for the recovery check. Other changed code stays in scope. For verified additions in that request, the new-file diff header establishes attribution and complete target source supplies the added lines. Scopes without a recognized link use the regular file grouping.

Durable-write checks cover inputs, caller guards, commit and error propagation. The request adds specific recovery questions when the relevant pinned implementation or diff shows retry, ambiguous-result handling, or replay behavior.

Initial scoped verification can send up to three candidates together when they anchor to the same file, share a decisive outcome citation in another file, and use the same pinned evidence. Each candidate receives its own verdict. Missing or invalid verdicts are verified individually.

If the default scoped plan would leave hunks for later requests, the CLI first tries to fit the complete diff and selected source context into one request. If the combined prompt exceeds its budget, the CLI reviews the remaining hunks in bounded scopes. The report lists any ranges still unreviewed and returns exit code 3. Risk checks can include unchanged Python siblings and direct Go or Java callers from the pinned revision. A scope can reuse implementation named in the candidate's evidence question. To request another round, the verifier must answer the previous evidence question and cite newly supplied evidence. Missing required excerpts are recorded for human review. Confirmed scoped candidates rated medium or higher, or supported by a premise in another target file, receive independent verification before publication.

Use `--focused-json-schema` to pilot Claude Code's schema-constrained response for initial risk checks. Use the same flag when resuming that run. A custom agent command used with this flag must accept Claude Code's `--output-format json` and `--json-schema` flags and return its JSON envelope. [Structured output and failure handling](docs/structured-output.md#json-schema-pilot).

Deep discovery separately checks normal-path correctness (R1), contract compatibility (R2), failure and recovery (R3), security and isolation (R5), and boundary inputs and data conversion (R6). R4 checks explicit requirements when a requirement or knowledge document is supplied. Each role receives the repository rules and the complete textual diff, partitioned to fit the prompt budget. Discovery concurrency is bounded by the active role count and any applicable service or explicit cap. Oversized optional source bodies can be requested by line range. Coverage records any changes that could not fit. Reviewers with a focused evidence view can omit duplicate source blocks when the diff contains the complete target body. Deep candidate verification uses relevant complete evidence blocks by default. To compare verification evidence modes, use `--review-mode deep` on both sides and add `--verification-evidence full` on the full-input side.

The CLI prefers confirmed limits from the active HTTPS gateway's model metadata. It uses the configured `ANTHROPIC_BASE_URL`, model and environment credentials to query the model record, then caches successful results for 24 hours per endpoint, model and credential. The query sends the model ID without repository evidence. If the route or metadata cannot be verified, `--max-prompt-bytes` supplies the fallback, with 320,000 UTF-8 bytes as the default. Discovered token limits produce a conservative byte planning budget with room for the response and Claude Code context. Shared discovery partitions fit the smallest active role budget. `--max-evidence-bytes` remains a separate evidence collection limit. See `model-prompt-limits.md` for the budgets used. `--prepare-only` uses the fallback locally.

Use `--batched-verification` to try sharing pinned evidence across up to three deep-verification candidates. The CLI validates each verdict independently and reruns missing or invalid members individually. Use the same flag when resuming. Deep review can publish eligible findings directly once their trigger, impact, and change attribution are verified. See [verification performance](docs/review-performance.md#additional-evidence-rounds).

Each initial reviewer first checks whether the supplied evidence can answer its assigned question. If the reviewer identifies a blocking gap, the CLI fetches evidence from the pinned revisions. It can recover a missing path by a unique exact filename in scope or by matching an Alembic predecessor's declared revision. When useful material is available, only the requesting reviewer restarts. Candidates it can substantiate proceed to verification, and unresolved requests remain in the coverage and review status.

Initial and supplemental discovery can attach a question and exact source requests to each candidate. The CLI fetches the declared evidence before verification and groups identical claims with the same question and evidence plan when direct publication is allowed. The verifier checks evidence sufficiency first. When the CLI obtains missing evidence, only the affected candidate resumes, even while other verifiers are still running.

Review-required findings can include a suggested check for the maintainer, covering the missing premise, a concrete action, and the observation that would resolve it. The CLI performs static review.

To compare model assignments, pass `--discovery-model` three times in R1, R2, R3 order, for example `--discovery-model sonnet --discovery-model opus --discovery-model haiku`. R4 uses the R2 model, and R5 and R6 use the R3 model. The assignments also apply to transport retries. Resume with the same assignments.

## Blind evaluation

An evaluation case is one committed change from any local Git repository, together with the criteria a grader uses to assess the reports. This example asks whether a reviewer finds missing database migrations. Python 3.11+ is required for the evaluation script.

```bash
python3 scripts/review-eval.py prepare \
  --repo /path/to/project --base main --head HEAD \
  --focus "Check whether the review detects missing database migrations" \
  --language en --output outputs/eval-case-01
```

Review the draft criteria in `outputs/eval-case-01/cases.json`. You can pass `--requirement` or `--knowledge` during preparation to include snapshots of those documents in both reviews. Pass `--reference /path/to/verified-findings.md` when you have verified findings for the grader. Without a reference, the tool marks the missed-defect count `N/A`. The grader can still compare how many criteria each report meets.

```bash
python3 scripts/review-eval.py run \
  --cases outputs/eval-case-01/cases.json \
  --baseline-jar /path/to/older.jar \
  --candidate-jar build/code-review-agent.jar \
  --language en --output outputs/eval-run-01
```

Add `--pr-agent pr-agent` to include a configured PR-Agent CLI as a third variant. Give the grader the anonymous reports and criteria in `outputs/eval-run-01/blind/`, plus access to the pinned change. After the grader fills in `blind/grades.csv`, reveal and summarize the results with `python3 scripts/review-eval.py summarize --output outputs/eval-run-01`. [Scoring and experiment options](docs/review-performance.md#blind-review-evaluation)

## Contributing

Open a pull request against `main`. CI runs the Java, GitLab integration and blind evaluation tests. Use public fixtures or sanitized samples when sharing review cases and logs. Keep internal knowledge bases and project-specific review reports outside this public repository.

Maintainers can push a version tag such as `v0.1.0` on a commit in `main` history. After CI passes, GitHub Actions prepares a release draft with the JAR and its SHA-256 checksum for maintainer review and publication.

## Further reading

- [GitLab CI/CD: automatic MR reviews and `/review` comments](docs/gitlab-integration.md)
- [Verification performance and comparison](docs/review-performance.md)
- [Runtime failures, retries, budgets and resume](docs/runtime-reliability.md)
- [Language coverage and dependency reading](docs/language-modules.md)
- [GitHub Actions integration example](examples/github-review.yml)
