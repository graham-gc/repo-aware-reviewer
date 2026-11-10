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
- Collects the Git diff, target-version contents of changed files, and dependency manifests as review evidence.
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

```bash
java -jar build/code-review-agent.jar knowledge generate \
  --repo /path/to/repo \
  --ref main \
  --language en \
  --output-dir ./knowledge-output \
  --agent-command claude \
  --agent-arg -p
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

## Usage

Review a single commit. The default scope is `HEAD^..HEAD`:

```bash
java -jar build/code-review-agent.jar review \
  --repo /path/to/repo \
  --commit HEAD \
  --requirement /path/to/requirement.md \
  --knowledge /path/to/knowledge.md \
  --agent-command claude \
  --agent-arg -p
```

Review a branch from its merge base with another branch:

```bash
java -jar build/code-review-agent.jar review \
  --repo /path/to/repo \
  --branch feature/example \
  --base main \
  --agent-command claude \
  --agent-arg -p
```

Review an explicit revision range:

```bash
java -jar build/code-review-agent.jar review \
  --repo /path/to/repo \
  --range origin/main..feature/example \
  --agent-command claude \
  --agent-arg -p
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

## Output

Each run creates a separate directory containing:

- `scope.md`: resolved review scope and input summary;
- `evidence.md`: diff, target files, instructions, requirements, and knowledge evidence;
- `discover-prompt.md`: candidate discovery prompt;
- `candidates.md`: candidate findings;
- `verify-prompt.md`: independent verification prompt;
- `report.md`: final review report.

## Prototype limitations

- The prototype identifies Java imports and Maven coordinates, but does not yet implement precise, symbol-driven dependency source loading.
- It does not yet run builds, tests, or static analyzers.
- Repository registration, knowledge-base approval history, and Skill/MCP adapters are not yet implemented.
- Agent output is currently Markdown; a structured findings schema is planned.
