# repo-aware-reviewer

[简体中文](README.zh-CN.md)

A read-only, evidence-driven code review CLI prototype. Its review pipeline follows the core ideas behind Anthropic Code Review: discover candidate issues, independently verify them, deduplicate the surviving findings, and rank them by severity. It extends that workflow with requirement-by-requirement assessment and repository-specific business knowledge.

## Current capabilities

- Supports a single commit, a branch comparison, or an explicit Git revision range.
- Uses the first parent as the default baseline for a single commit.
- Uses the merge base when reviewing a branch against a baseline branch.
- Resolves and records immutable base and target commit SHAs.
- Reads repository review instructions from the base commit so the reviewed change cannot rewrite its own rules.
- Loads external requirement and human-maintained knowledge files and records their SHA-256 hashes.
- Budgets expanded Git diff context by changed file and records any partial or omitted evidence.
- Loads requested tracked source and exact cached Maven evidence between discovery and verification.
- Runs two agent stages: candidate discovery followed by independent verification and final reporting.
- Does not build, test, execute, or modify the reviewed repository by default.
- Supports `--prepare-only` to generate the evidence bundle and prompts without invoking an agent.

## Build

JDK 21 or later is required. Maven and Gradle are not required:

```bash
bash scripts/build.sh
```

The command creates `build/code-review-agent.jar`.

## Generate a candidate knowledge base

Repository onboarding starts with a read-only scan of a fixed Git revision. The command inventories every tracked file, groups supported source, documentation, test, schema, and configuration files into evidence chunks, and asks the configured agent to produce a candidate knowledge base for human review:

Claude Code CLI is the default reasoning harness. Authentication and model routing are resolved by Claude Code itself, including its environment and the `env` block in `~/.claude/settings.json`. For a direct Anthropic API setup, a dedicated key can be exposed through the environment:

```bash
export ANTHROPIC_API_KEY='your-api-key'
```

```bash
java -jar build/code-review-agent.jar knowledge generate \
  --repo /path/to/repo \
  --ref main \
  --language en \
  --output-dir ./knowledge-output
```

Use `--prepare-only` to create the immutable scope, complete file inventory, evidence chunks, and prompts without invoking an agent:

```bash
java -jar build/code-review-agent.jar knowledge generate \
  --repo /path/to/repo \
  --ref main \
  --prepare-only
```

The generated `candidate-knowledge.md` is explicitly unconfirmed. The CLI does not approve, update, or publish a human-maintained knowledge base.

Use `--language en` or `--language zh-CN` to select the language of chunk observations and the generated knowledge base. English is the default.

Use `--agent-command` and repeated `--agent-arg` options only when overriding the default Claude Code harness with another CLI or a local model. repo-aware-reviewer does not read, copy, log, or store credentials from Claude settings. The actual provider and model may differ from Anthropic Claude when `ANTHROPIC_BASE_URL`, `ANTHROPIC_AUTH_TOKEN`, or model overrides route Claude Code through a compatible gateway.

## Usage

Review a single commit. The default scope is `HEAD^..HEAD`:

```bash
java -jar build/code-review-agent.jar review \
  --repo /path/to/repo \
  --commit HEAD \
  --requirement /path/to/requirement.md \
  --knowledge /path/to/knowledge.md
```

Review a branch from its merge base with another branch:

```bash
java -jar build/code-review-agent.jar review \
  --repo /path/to/repo \
  --branch feature/example \
  --base main
```

Review an explicit revision range:

```bash
java -jar build/code-review-agent.jar review \
  --repo /path/to/repo \
  --range origin/main..feature/example
```

Prepare evidence without invoking a model:

```bash
java -jar build/code-review-agent.jar review \
  --repo /path/to/repo \
  --commit HEAD \
  --prepare-only \
  --output-dir ./review-output
```

The agent command must read its prompt from standard input and write a plain-text response to standard output. The CLI does not invoke a shell: `--agent-command` and every `--agent-arg` value are passed directly to `ProcessBuilder` as separate arguments.

The discovery pass can request tracked text from the pinned base or target and individual Java types or `.proto` entries from `~/.m2/repository`. Maven requests must match a fixed direct JAR dependency in the root `pom.xml` for the selected revision. The CLI records artifact hashes, uses `javap` for public Java signatures, and performs no build or dependency download. Requested evidence is included in the prompt to the configured backend during verification.

## Output

Each run creates a separate directory containing:

- `scope.md`: resolved review scope and input summary;
- `evidence.md`: diff, target files, instructions, requirements, and knowledge evidence;
- `coverage.md`: per-file patch coverage and the outcome of supplemental requests;
- `discover-prompt.md`: candidate discovery prompt;
- `candidates.md`: candidate findings;
- `supplemental-evidence.md`: additional evidence supplied to verification;
- `verify-prompt.md`: independent verification prompt;
- `report.md`: final review report.

## Prototype limitations

- Dependency inspection covers cached direct Maven JARs only. Parent/BOM, profiles, transitive resolution, other package managers, and downloads are not implemented.
- It does not yet run builds, tests, or static analyzers.
- Repository registration, knowledge-base approval history, and Skill/MCP adapters are not yet implemented.
- Agent output is currently Markdown; a structured findings schema is planned.
