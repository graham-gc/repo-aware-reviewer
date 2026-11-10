# repo-aware-reviewer

[简体中文](README.zh-CN.md)

An evidence-driven CLI for reviewing committed Git changes against the code, requirements, and a human-maintained business knowledge base. Inspired by Anthropic Code Review, it uses two model calls and asks the second pass to verify, deduplicate, and rank the first pass's findings against the evidence.

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

The CLI starts the external agent in the reviewed checkout. Its configuration controls tool permissions and where prompts containing collected evidence are sent. To use another executable, pass `--agent-command` and one `--agent-arg` per argument. It must read a UTF-8 prompt from stdin and write a UTF-8 reply to stdout.

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

Add `--knowledge-mode scoped` to load relevant sections from the supplied knowledge base. The CLI uses Markdown headings to keep each section intact, matches changed paths and identifiers, and includes sections titled `Global rules` or `全局规则`. Discovery then describes observable behavior changes and requests rules for those impacts. The CLI looks up the requests locally and supplies matching sections for verification within the evidence budget. `coverage.md` records selected sections and gaps. The default mode, `full`, supplies the whole document.

- `--commit <ref>` reviews a commit against its first parent. It defaults to `HEAD` and requires that parent to be available locally.
- `--branch <ref> --base <ref>` reviews the branch from its merge base with the baseline.
- `--range BASE..TARGET` compares two explicit revisions.

The evidence bundle takes repository review rules from the resolved base commit.

## GitHub pull request reviews

Copy the [example workflow](examples/github-review.yml) into `.github/workflows/` in the repository to review. Set the `ANTHROPIC_API_KEY` Actions secret and point the action reference at a trusted revision. The workflow runs on new or updated same-repository pull requests and on `/review` comments from users with write access. It posts the report and uploads the report, scope, and coverage as an Actions artifact.

Set the `REVIEW_KNOWLEDGE_PATH` repository variable to a tracked file such as `docs/business-knowledge.md` to enable scoped knowledge. The action reads that file from the PR base commit. Use the optional `requirement-path` input for a requirements file from the same commit, or `publish-comment: 'false'` for artifact-only reviews. The workflow fetches the base and head Git objects and runs the CLI with an empty worktree and Claude Code tools disabled.

The Action's `language` input defaults to `en`. The example workflow reads it from the optional `REVIEW_LANGUAGE` repository variable, which also accepts `zh-CN`.

## Output and limits

Progress uses one updating line on an interactive stderr and plain log lines when redirected. Both commands print the run directory on stdout on success. The defaults are `review-output/` and `knowledge-output/`, configurable with `--output-dir`. Add `--prepare-only` to either command to save the local evidence and prompt templates without making model calls.

- `review` writes the model's `report.md`. Use `scope.md` to check the resolved commits and input hashes, `evidence.md` to inspect the supplied material, and `coverage.md` to see truncated or omitted changes and failed evidence requests.
- `knowledge generate` writes `candidate-knowledge.md`. Use `inventory.md` to check which files were included or skipped. Both modes save their supporting evidence locally.

Before verification, the collector can load requested tracked text from either pinned commit. Dependency evidence is limited to public Java signatures and `.proto` files from cached, fixed-version direct JAR dependencies in the selected revision's root `pom.xml`. Evidence collection does not run the target project's build or tests. For large inputs, `review` exposes `--max-evidence-bytes` and `knowledge generate` exposes `--max-file-bytes` and `--max-total-bytes`.

## Repository documentation

Root-level Markdown is reserved for filenames starting with `README`, regardless of case. Put other project documentation in a subdirectory such as `docs/`.
