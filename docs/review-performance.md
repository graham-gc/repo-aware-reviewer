# Verification performance

Candidate verification, requirement-finding verification and publication adjudication default to `--verification-evidence scoped`. Use `--verification-evidence full` to compare the previous full-input behavior. This option is independent of `--knowledge-mode`, which controls the business knowledge document. Both modes retain independent per-candidate verification, exact quotation validation, source provenance, location coverage and publication checks. Batching and local-only final report generation are not enabled by this change.

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

After shared evidence collection, a task whose complete prompt remains unchanged is not automatically called again. Its previous validated or unfinished result is retained and `TASK-unchanged.txt` records the decision. Explicit resume can retry unfinished tasks. New related material, indexes or trusted rules change the prompt and can trigger another verification. Changing the verification-evidence mode is allowed during resume, but changed prompts invalidate their task caches; prior discovery results can still be reused when unchanged.

## Timing report

An execution that enters the model workflow writes `timings.md`, including incomplete or failed executions when artifact storage remains available. Preparation-only and empty-diff runs do not enter that workflow. The report covers this invocation only; earlier calls from a resumed directory are not added again.

It separates stage wall time, cumulative process time and longest process time. Concurrent process durations overlap, so adding them does not measure the user's waiting time. Calls include actual transport retries and output corrections; valid cache reuse and unchanged follow-up skips are counted separately. Stage wall time includes task queueing, backoff and validation. The overall model-workflow clock starts before discovery and also includes intervening local evidence collection; it excludes initial scope resolution and Git evidence preparation.

Candidate prompt comparisons include prepared tasks that may be cached or skipped, and are not a measurement of network traffic saved on those tasks. Actual launched-call input bytes appear in the phase table. UTF-8 byte counts are not model token counts and do not prove an upstream prefill cache miss.

## Comparing quality and latency

Use the same fixed target/baseline, documents, model route, concurrency and budgets in separate output directories. Compare `--verification-evidence full` with `--verification-evidence scoped`; do not use cache reuse as a latency benchmark. Check missing/false findings and human-adjudicated outcomes alongside stage wall time, call count, corrections and input bytes. Model generation and gateway variability require repeated comparisons before claiming a speedup.

Offline regression tests verify source boundaries, complete-block selection, cross-file producer context, stable aliases and locations, preservation of discovered evidence, selective follow-up rounds and non-overlapping time accounting. Saved repository evidence can quantify prompt-size differences without model calls. Neither test proves production model latency or review recall; this implementation does not claim a fixed runtime reduction.
