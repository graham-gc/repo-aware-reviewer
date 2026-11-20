# Verification performance

Candidate verification, requirement-finding verification and publication adjudication default to `--verification-evidence scoped`. Use `--verification-evidence full` to compare the previous full-input behavior. This option is independent of `--knowledge-mode`, which controls the business knowledge document. Both modes retain independent per-candidate verification, exact quotation validation, source provenance, location coverage and publication checks.

## Supplied material

Scoped verification selects complete, already-collected evidence blocks rather than cropping functions or diff hunks. It includes:

- The candidate's target file, available baseline blocks and patch, including earlier paths for rename evidence.
- All collected trusted contract/rule blocks and navigation indexes. External requirements and knowledge are not further shortened by this selector.
- Explicitly mentioned paths and registered source references, plausible producer/caller and peer-test context, and untyped dependency evidence that cannot be safely assigned to one repository path.
- Material already supplied to this candidate, new material requested by it, and the supplied evidence and citations of all grouped candidates during publication adjudication, including contrary verdicts.

Related-code selection uses conservative lexical hints, not a complete call graph. An incomplete dependency path still requires supplemental evidence or an insufficient decision. A missing primary-file anchor falls back to full input. A very large primary file, contract or dependency block can still dominate the prompt; the selector does not promise a fixed prompt size.

Collector-owned EV identities, SRC aliases, quotation text, excerpt offsets and diff coordinates are preserved. SRC references are never renumbered in a subset, and an ambiguous alias is never revived. Each task receives an index containing only its actually supplied blocks; quotation corrections cannot look up omitted blocks through the global catalog. Global native-file metadata is used only to preserve strict location checks when a finding is relocated to an omitted path, not to supply omitted quotations.

Every candidate task writes `TASK-evidence.md` beside its prompt. It records full versus selected prompt bytes, provided-block counts and the actual source manifest. The global `evidence-index.md` remains an inventory of collected material, not permission to cite it in every task.

## Additional evidence rounds

A candidate's selected evidence is retained across its verification rounds and subsequent publication adjudication. Requested new material is pinned into the requester's next task even if it has no lexical match in the original allegation. This prevents a smaller publication prompt from accidentally losing a producer guard discovered during verification.

For a scoped follow-up, a file index records navigation and does not schedule another model call on its own. The reviewer can request `REQUEST_FILE|target|src/main/**/*.java|references:Service.write|Find the caller and its guard` to locate bounded excerpts directly in tracked source at the pinned revision. A literal hit helps locate code; type binding, execution, and impact still require evidence. Narrow the path or supply a file and line range when a reference search cannot resolve a premise.

After individual verification, a group of confirmed local regressions with the same code anchor and shared pinned baseline and target sources can receive one publication verdict. The adjudicator resolves provisional severity differences using the proved impact and preconditions. Sharing requires explicit coverage of every candidate ID in the independent publication response and revalidation of the common evidence. The remaining candidates run separately when the response leaves applicability uncertain. Coverage retains the individual verification records and lists which candidates shared a publication verdict.

After shared evidence collection, a task whose complete prompt remains unchanged is not automatically called again. Its previous validated or unfinished result is retained and `TASK-unchanged.txt` records the decision. Explicit resume can retry unfinished tasks. New related material, indexes or trusted rules change the prompt and can trigger another verification. Changing the verification-evidence mode is allowed during resume, but changed prompts invalidate their task caches; prior discovery results can still be reused when unchanged.

## Deep review reporting

Every independent discovery reviewer receives the available diffs, requirements and contracts. The contract and boundary reviewers can omit a standalone target block after verifying that a complete diff contains the same target lines. Context outside the diff stays in the prompt. Compare the saved `discovery/R1-prompt.md`, `R2-prompt.md` and `R3-prompt.md` to inspect the resulting views.

When no external requirement or knowledge document is supplied, deep review renders the report locally from publication decisions, candidate groups and evidence coverage. Within a group, confirmed findings with matching locations, triggers and impacts share one entry. The first discovery task can include a short change summary. Existing results without a summary can be resumed, and the report can list the changed paths. Reviews with an external document still use the report consolidation call for the narrative and knowledge feedback.

## Timing report

An execution that enters the model workflow writes `timings.md`, including incomplete or failed executions when artifact storage remains available. Preparation-only and empty-diff runs do not enter that workflow. The report covers this invocation only; earlier calls from a resumed directory are not added again.

It separates stage wall time, cumulative process time and longest process time. Concurrent process durations overlap, so adding them does not measure the user's waiting time. Calls include actual transport retries and output corrections; valid cache reuse and unchanged follow-up skips are counted separately. Stage wall time includes task queueing, backoff and validation. The overall model-workflow clock starts before discovery and also includes intervening local evidence collection; it excludes initial scope resolution and Git evidence preparation.

The attempt table and each execution log record local offsets in milliseconds for process startup, prompt stdin closure, the first byte read from stdout and stderr, and process exit observed during the wait. Missing events appear as a dash in the report and as `unavailable` in the log. A buffered agent command may only write stdout near exit. Use these measurements to locate time spent waiting on the command and its pipes. Provider queueing, upstream retries and token generation need separate telemetry.

The local validation table includes accepted and rejected checks for both fresh and saved responses. It measures response checking separately from diagnostic file writes. A repair that invokes the model has its own attempt row and is included in the correction count.

Candidate prompt comparisons include prepared tasks that may be cached or skipped, and are not a measurement of network traffic saved on those tasks. Actual launched-call input bytes appear in the phase table. UTF-8 byte counts are not model token counts and do not prove an upstream prefill cache miss.

## Comparing quality and latency

Use the same fixed target/baseline, documents, model route, concurrency and budgets in separate output directories. When comparing `--verification-evidence full` with `--verification-evidence scoped`, select `--review-mode deep` for both runs. For adaptive routing comparisons, use a fresh run for each mode. Check missing and false findings and human-adjudicated outcomes alongside stage wall time, call count, corrections and input bytes. Model generation and gateway variability require repeated comparisons before claiming a speedup.

Offline regression tests verify source boundaries, complete-block selection, cross-file producer context, stable aliases and locations, preservation of discovered evidence, selective follow-up rounds and non-overlapping time accounting. Saved repository evidence can quantify prompt-size differences without model calls. Neither test proves production model latency or review recall; this implementation does not claim a fixed runtime reduction.

## Blind review evaluation

Run from the project root with Python 3.11+, Git, Java, and a configured Claude Code command. Choose a real change in the target repository and describe what you want to measure.

```bash
python3 scripts/review-eval.py prepare \
  --repo /path/to/project --base main --head HEAD \
  --focus "Check whether the review detects missing database migrations" \
  --output outputs/eval-case-01
```

`prepare` resolves the two local Git revisions to commit IDs and collects evidence with the current JAR's `review --prepare-only`. It calls Claude once to draft three to six case-specific scoring criteria in the selected language. Check those criteria in the generated `cases.json` before running the comparison. Use `--jar` to specify a different JAR for collecting evidence. Each preparation uses a fresh output directory and expects a nonempty Git diff.

`--requirement` and `--knowledge` save document snapshots for all review runs. If you have independently verified findings or a verified empty set, supply a nonempty Markdown reference with `--reference`. The script gives that file to the grader. Preparation also saves the rubric prompt, raw response, and evidence locally for inspection.

You can still pass a manually authored manifest, including the [starter cases](../examples/review-eval-cases.json) for this repository. Each case takes full commit IDs. Relative paths for `repo`, `requirement`, `knowledge`, and `reference` resolve from the manifest's directory. Run preparation again for another change and combine entries if you want one comparison across several cases.

```bash
python3 scripts/review-eval.py run \
  --cases outputs/eval-case-01/cases.json \
  --baseline-jar /path/to/baseline.jar \
  --candidate-jar build/code-review-agent.jar \
  --output outputs/review-eval-01
```

Use a fresh output directory. The tool snapshots both JARs and any supplied documents, randomizes run order and report labels independently per trial, and retains status, end-to-end wall time, and available model metrics. Use `--repeats` for repeated trials. To compare evidence modes, add `--baseline-evidence full --candidate-evidence scoped` with the same JAR on both sides.

To add PR-Agent as a third variant, install a release that provides `--diff-file`, `--output`, and `--json-output`, and configure its model provider. Pass `--pr-agent pr-agent` or the path to the configured executable. The tool supplies PR-Agent the exact pinned base-to-head diff and regular changed files from the pinned head in an isolated directory. Supplied requirement and knowledge snapshots go into its review description. The JARs use their standard evidence collection, so the comparison holds the change and optional documents fixed while each tool selects its own context.

Use the same model and gateway for both tools when comparing routes. `--model-label` and `--gateway-label` record your settings in `private/configuration.json`. Set the actual routes in the tools' environments. `--parallelism` sets the JARs' review parallelism. Configure PR-Agent separately for chunking or output limits. The operator's private configuration records supported environment overrides for these settings. The adapter records reported PR-Agent tokens, when available, and marks clipped, omitted, or failed chunks as incomplete.

Use `--category` on `prepare` or set `category` in a case manifest to group cases, for example `small`, `cross-module`, `high-risk`, or `clean`. To measure recall against a verified reference, use `--reference-defects` with `--reference`, or set `reference_defects` beside `reference` in the manifest. A verified clean case can use a reference count of zero.

To compare initial reviewer model diversity, use the same current JAR and evidence mode on both sides. For a fixed-model control, use `--baseline-models sonnet sonnet sonnet` and compare with `--candidate-models sonnet opus haiku`, or substitute models available in your Claude Code setup. Each side still runs R1 correctness and regressions, R2 requirements and contracts, and R3 boundaries and security. The supplementary discovery, verification, assessment, and publication stages use the normal agent configuration. A custom agent command used with `--discovery-model` must accept `--model MODEL`.

Give the same human reviewer the `blind/` directory and access to the pinned project revisions for verification. The directory contains the anonymous reports, scoring criteria, any supplied reference and the grading sheet. Check the reports for identifiers that disclose the variant before handing them over. Keep `private/` with the experiment operator. It includes the mapping, inputs and original run logs. The grader records applicable counts in `grades.csv`.

- `missed_defects` counts verified in-scope defects the report missed. Case preparation disables this metric unless you supply `--reference`. The grading sheet marks it `N/A`.
- `false_positives` counts unsupported or refuted claims presented as confirmed defects.
- `misclassified_uncertainties` counts unresolved policy or behavior questions that the report classified incorrectly. Count each issue only once across the three categories.
- `unmet_criteria` counts how many rubric criteria the report failed to satisfy. Compare this count separately from the defect counts.
- `reviewer_seconds` records how long the grader spent reading and checking the report, rounded to whole seconds.

Use `N/A` when a metric cannot be assessed and `notes` to explain the judgment. Leave the counts blank for reports you skip. Existing grading sheets with the original three columns still work. A review that exits `3` can still produce a report for grading. Keep its incomplete status visible when interpreting scores. The tool makes an unavailable report visible in the private results and keeps running the remaining pairs.

```bash
python3 scripts/review-eval.py summarize --output outputs/review-eval-01
```

The summary reveals the variants and compares each pair overall and by category. The end-to-end p50 and p95 use matched runs that produced usable reports. Each quality metric uses only pairs scored on both sides for that metric. Read failures and incomplete statuses alongside those numbers.

For billing comparisons, pass `summarize --costs /path/to/costs.csv`. Use `case,trial,variant,cost_usd` as the header, with the matched provider cost per run and the variant name `baseline`, `candidate`, or `pr-agent`. Unavailable measurements remain unavailable. Keep this CSV in the operator's private records.
