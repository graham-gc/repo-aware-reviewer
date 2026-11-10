# repo-aware-reviewer

[简体中文](README.zh-CN.md)

An evidence-driven code review CLI prototype. Inspired by Anthropic Code Review, it finds candidate issues and checks them against the evidence in a separate pass before deduplicating and ranking the findings. It also assesses individual requirements against the code and can use a human-maintained business knowledge base.

## Current capabilities

- Supports a single commit, a branch comparison, or an explicit Git revision range.
- Uses the first parent as the default baseline for a single commit.
- Uses the merge base when reviewing a branch against a baseline branch.
- Resolves and records immutable base and target commit SHAs.
- Reads the repository review instructions included in the evidence bundle from the base commit.
- Loads external requirement and human-maintained knowledge files and records their SHA-256 hashes.
- Budgets expanded Git diff context by changed file and records any partial or omitted evidence.
- Loads requested tracked source and exact cached Maven evidence between discovery and verification.
- Runs separate model calls for candidate discovery and verification.
- Collects evidence from Git and cached dependency artifacts without building or running the reviewed project.
- Supports `--prepare-only` to generate the evidence bundle and prompts without invoking an agent.

## Build

Build with JDK 21 or later by running the following command from the project directory. The script uses the JDK tools directly.

```bash
bash scripts/build.sh
```

The command creates `build/code-review-agent.jar`.

## Set up the model backend

Both commands default to `claude -p`. The `claude` executable must be available on `PATH` in the environment that launches the JAR. Verify that it accepts a prompt from standard input and prints a reply in the same terminal.

```bash
printf '%s\n' 'Reply with OK.' | claude -p
```

If it returns `OK`, the project can reuse the authentication and model routing already configured in Claude Code, whether that is a Claude subscription, an Anthropic API key, or a compatible gateway such as MiniMax. Direct API users can provide `ANTHROPIC_API_KEY` to Claude Code through the environment. For a different executable, use `--agent-command` and one `--agent-arg` per argument. The executable must read its prompt from stdin and write a plain-text response to stdout.

The CLI starts the external agent in the reviewed checkout. Its tool permissions come from its own configuration, and prompts containing collected evidence go to its configured model backend. Use `--prepare-only` to collect evidence without starting the external agent.

## Generate a candidate knowledge base

Scan a fixed Git revision to prepare a draft for human review. The command inventories every tracked file and groups supported source, documentation, test, schema, and configuration files into evidence chunks.

```bash
java -jar build/code-review-agent.jar knowledge generate \
  --repo /path/to/repo \
  --ref main \
  --language en \
  --output-dir ./knowledge-output
```

Add `--prepare-only` to write the scope, file inventory, chunks, and prompts locally.

```bash
java -jar build/code-review-agent.jar knowledge generate \
  --repo /path/to/repo \
  --ref main \
  --prepare-only
```

Review `candidate-knowledge.md` before using it as the main knowledge base. Pass the human-approved version to `review` with `--knowledge`. Use `--language en` or `--language zh-CN` to choose the language of the observations and draft. The default is English.

## Usage

Review a single commit with optional business inputs. Without an explicit target, the default scope is `HEAD^..HEAD`.

```bash
java -jar build/code-review-agent.jar review \
  --repo /path/to/repo \
  --commit HEAD \
  --requirement /path/outside-repo/requirement.md \
  --knowledge /path/outside-repo/knowledge.md
```

Both document options are optional. Keep actual review inputs outside the checkout and pass their paths explicitly. `--knowledge` takes one human-maintained UTF-8 Markdown file in any layout. `--requirement` takes a separate UTF-8 file with the requirements for this change. If you omit the requirement file, the review can still report code findings.

Review a branch from its merge base with another branch.

```bash
java -jar build/code-review-agent.jar review \
  --repo /path/to/repo \
  --branch feature/example \
  --base main
```

Review an explicit revision range.

```bash
java -jar build/code-review-agent.jar review \
  --repo /path/to/repo \
  --range origin/main..feature/example
```

Prepare evidence without invoking a model.

```bash
java -jar build/code-review-agent.jar review \
  --repo /path/to/repo \
  --commit HEAD \
  --prepare-only \
  --output-dir ./review-output
```

The discovery pass can request tracked text from the pinned base or target and individual Java types or `.proto` entries from `~/.m2/repository`. Maven requests must match a fixed direct JAR dependency in the root `pom.xml` for the selected revision. The CLI records artifact hashes, uses `javap` for public Java signatures, and performs no build or dependency download. Requested evidence is included in the prompt to the configured backend during verification.

## Output

Both commands show progress on stderr, including elapsed time during long waits. On success, stdout contains the path to the run directory. The default output directories are `review-output/` and `knowledge-output/`. Override either with `--output-dir`.

- `review` saves `scope.md`, `evidence.md`, `coverage.md`, and `discover-prompt.md`. A full review also saves `candidates.md`, `supplemental-evidence.md`, `verify-prompt.md`, and `report.md`. With `--prepare-only`, it writes `verify-prompt-template.md` instead of the model results.
- `knowledge generate` saves `scope.md`, `inventory.md`, evidence in `chunks/`, and prompts in `prompts/`. A full run also saves per-chunk observations and `candidate-knowledge.md`. With `--prepare-only`, it writes a synthesis prompt template without generating the candidate.

## Repository documentation

Root-level Markdown is reserved for filenames starting with `README`, regardless of case. Put other project documentation in a subdirectory such as `docs/`.

## Prototype limitations

- Dependency inspection covers cached direct Maven JARs only. Parent/BOM, profiles, transitive resolution, other package managers, and downloads are not implemented.
- Repository registration, knowledge-base approval history, and Skill/MCP adapters are not yet implemented.
- Agent output is currently Markdown; a structured findings schema is planned.
