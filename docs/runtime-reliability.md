# Runtime recovery

A model process completing successfully does not establish a correct review. All existing source, location, proof and publication checks remain required. This recovery layer does not truncate evidence, change model routing or combine independent reviewers into one conversation.

## Calls, corrections and budgets

| Option | Default | Scope |
| --- | --- | --- |
| `--review-parallelism` | `3` | Concurrent independent review tasks in this CLI execution. `1` runs the same independent prompts sequentially. |
| `--agent-timeout-seconds` | `900` | Maximum time for one process, stdin writing and stdout/stderr collection. |
| `--agent-retries` | `1` | Additional recoverable invocation attempts shared by one task, across its output corrections. `0` disables them. |
| `--agent-max-calls-per-task` | `5` | All CLI-launched model calls for a task, including the initial call, retries and format/source/decision corrections. |
| `--agent-task-timeout-seconds` | Twice the process timeout | Shared deadline governing model calls, backoff and corrections. Each new call gets the smaller of its process timeout and the remaining deadline. |

A task is one reviewer or candidate in a particular stage/evidence round. Supplying new evidence creates another review task; these limits are not a maximum review duration or a global model usage cap. Process cleanup, artifact writes and local validation also take time. An explicit resume starts a fresh recovery budget while retaining earlier attempt records. Successful cache reuse does not call the model.

Empty stdout after exit code zero is recorded as `EMPTY_RESPONSE` with an unknown cause. It may retry once within the configured budget. Recognized temporary service/connection errors may also retry. Automatic retry requires successful cleanup of observed task processes; if the host denies descendant enumeration, the failure is retained for explicit resume instead. Unknown nonzero exits, premature stdin closure, timeouts, invalid encoding, access/authentication rejection and explicit input-size errors do not automatically retry. A process closing stdin before the entire prompt is sent is an unfinished invocation, not a global storage failure, even if its exit code is zero. A nonempty malformed response follows bounded output correction, not a transport retry. Storage failures are never treated as model format errors.

The CLI cannot observe every retry, model route, or billing action inside Claude Code, cc-switch, a gateway, or an upstream model service. Its budgets constrain only processes it launches. Set `--agent-retries 0` if another layer already retries or duplicate upstream work is unacceptable. Killing a local process does not prove the upstream request stopped or was not billed.

## Failure scope

- Independent discovery reviewers and candidate verification/publication tasks keep validated peer results when another call fails. Missing roles and failed checks remain visible; they cannot count as completed coverage.
- A required shared task such as candidate grouping, requirement draft/audit or final consolidation blocks dependent conclusions if no valid result is available. The CLI writes a deterministic incomplete report without asking a model to fill the missing analysis. Only findings that already passed publication adjudication can appear as confirmed defects. Completed evidence-gap decisions, publication disputes and failed candidate checks remain visible in their separate categories; preliminary positives are not promoted. Completion counts use the latest completed candidate decisions and requirement plan, even when final consolidation fails. Final deduplication may still be unfinished.
- Recognized authentication/configuration rejection and artifact-storage failures stop execution. Queued work is blocked and running task processes are cancelled. User interruption is also a cancellation, not a model timeout.
- A business verdict of insufficient evidence is distinct from a check that did not execute successfully. Failed candidate decisions carry an explicit unfinished flag; their linked acceptance items display `NOT_COMPLETED`. The model cannot emit that programmatic assessment status.

`review-status.md` records `RUNNING`, `COMPLETE`, `INCOMPLETE`, `FAILED`, `CANCELLED`, or `SKIPPED`. Review completion means required output is validated; it never means requirements are satisfied or target tests passed. CLI exit codes are `0` for completed/prepared/skipped output, `3` for incomplete review, `1` for execution failure and `2` for invalid arguments. An interrupt caught by the CLI returns `130`; operating-system signal handling may terminate the JVM directly.

## Subprocess ownership

The executor observes descendants while its own parent process runs and retains their handles. On exit, timeout or cancellation it terminates observed children, allows a bounded grace interval, then forcibly stops survivors. It also handles a parent exiting while a known child keeps an output pipe open. It never kills processes by executable name. A JVM shutdown hook also attempts bounded cleanup of active owned processes; forced OS termination such as SIGKILL cannot run that hook. Stream collection and shutdown are bounded so a reader cannot indefinitely occupy a review worker.

`processCleanup` records whether observed task processes were terminated, cleanup failed, or host restrictions prevented descendant enumeration. Enumeration is a best-effort snapshot: a child that detaches before being observed may be unidentifiable; a restricted host may prevent tree verification entirely. The log does not claim all upstream work has stopped. A failure to terminate observed processes or close streams is a fatal cleanup/storage-side error, not a reason to launch another model call.

## Checkpoints and diagnostics

Each invocation gets a new `*-attempts/attempt-NNNN/` directory containing `call-prompt.md`, `call-stdout.log`, `call-stderr.log`, and `call-execution.log`. This applies to initial calls, retries, output corrections and resumed attempts. Top-level log names remain compatibility views of the latest attempt.

A discovery reply without a complete `<findings>` block gets one format recovery that repeats the review from the original evidence and asks for the XML block first. A second invalid result remains an unfinished discovery task.

A candidate verification reply containing only supported evidence requests can advance to evidence delivery. A validated verdict is still required. If the requests add no evidence to that candidate, one follow-up asks for the verdict using the available evidence and the request outcomes.

If an `INSUFFICIENT_EVIDENCE` reply still contains an unbound citation after source correction, verification can discard that citation and dependent premises when at least one exact citation remains. The result is validated again, and the raw reply and normalization remain in the local run directory.

Execution records include start time, working directory, prompt SHA-256 and UTF-8 byte count, process timeout, actual allowed duration, task call count, configured budgets, concurrency at launch, termination reason and process-cleanup coverage. Command arguments are recorded only as a fingerprint; credentials are not read from Claude settings for logging. Input bytes are not model token counts, and concurrency at launch is not a diagnosis of gateway overload.

Task `*-state.properties` checkpoints record `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED`, or `BLOCKED`, plus the prompt hash and failure kind. A state file alone never authorizes reuse. Resume requires the original scope and initial evidence, matches each full task prompt, and revalidates saved responses and corrections against the current validator. Changed tasks invalidate and archive their prior responses. The actual model route behind an external CLI is not a reliable cache identity; reusing a valid completed result does not establish that route is unchanged.

Failed checkpoints also contain `failureCategory` and a fixed `failureSummary`. Categories distinguish source-quotation mismatches, decision-proof failures, ambiguous text, XML syntax, missing/repeated response blocks, other protocol failures, model invocation and local I/O. `failureLine` and `failureColumn`, when present, refer to the parsed XML document, not repository lines. These fields never copy exception messages, source quotations, prompts or credentials. Running/successful checkpoints clear previous failure details; the full local `*-error.txt` remains available until a successful retry.

Reports reference these checkpoints instead of raw error files. GitHub artifact upload runs even when the review step fails or returns incomplete output, preserves the CLI's failure exit status, and includes available checkpoints, review status and timings. Exporting diagnostics does not turn a failed check into a successful review.

Required shared-stage failures retain typed task IDs, failure kinds and checkpoint paths. Both the fallback report and `review-status.md` link to those checkpoints relative to the run directory, including root-level report tasks and nested assessment rounds. Raw validator messages and temporary absolute paths are not embedded in these shared-stage summaries. Coverage is saved after completed verification and publication stages, so a later failure cannot erase their audit trail. A successful resume reuses matching validated tasks and clears the failed stage only after validation succeeds.

`*-validation.log` retains append-only validation rejection events, including after successful correction or resume. Events contain fixed failure categories/summaries and optional XML positions, never exception messages or model/source text. They count validation attempts, including cached-response revalidation, not launched model calls. GitHub exports them with task diagnostics; GitLab puts them under `diagnostics/` at their run-relative paths. Raw prompts, responses and error quotations remain outside those exports.

Canonical responses, prompts, reports and state checkpoints use complete temporary writes followed by an atomic rename in the same directory. Task success is recorded after response validation and publication of its checkpoint. Filesystems without atomic replacement support fail explicitly rather than publish a partially written checkpoint. Previous final reports are kept as `report-previous.md`; the current report is marked running before new model work begins. A hard process termination may leave that running marker and a hidden temporary file; neither is treated as a completed result.

## Validation limits

`AgentStabilityTests` injects empty responses, temporary and permanent exits, timeouts, inherited pipes and required-stage failures into local subprocesses. It checks peer survival, shared recovery budgets, owned-child cleanup, report completeness and selective resume. Process-tree assertions require an OS environment that permits enumeration; the macOS Codex sandbox may require running the test suite outside that restriction. Tests never call a real model or run a reviewed project's programs.

These tests establish local recovery behavior, not the capacity of a particular Claude/gateway route. Establishing that requires a small controlled comparison using the same prompts and configuration at different concurrency levels. A single successful serial review does not establish causality.

## Navigation and evidence follow-ups

Initial evidence includes a bounded selection of related document and test **paths** at the pinned base and target. Navigation uses at most 5% of the remaining initial evidence budget, capped at 8,000 bytes across the revisions; it never loads document/test bodies. It respects private-path exclusions, scoped knowledge exclusions and nearby module paths. The index explicitly distinguishes selected-path coverage from complete repository coverage. Documents remain references to inspect, not an automatically approved business knowledge base. Other paths are still available through explicit evidence requests.

When new supplied material changes a pending candidate's evidence, its follow-up also receives the prior model opinion/open question, that candidate's previous requests, the processing outcomes for that shared request batch and newly supplied source references. Context is bounded and marked as non-authoritative; only included pinned sources can ground a quotation or verdict. A previous failed check is not presented as a validated opinion. Unchanged-task detection compares the underlying candidate/evidence prompt before adding this history, so history alone cannot manufacture another reasoning pass. There is no new round or request-count cap, and publication adjudication remains per positive candidate.

`RepositoryNavigationTests` checks metadata-only selection, module/private/knowledge boundaries and byte limits. `VerificationEvidenceTests` includes a controlled fake-model comparison in which path navigation removes one filename-discovery round (three calls become two), preserves the simulated verdict, and resumes without backend calls. This establishes routing behavior, not real-model latency or semantic quality. Initial evidence changes mean runs created before navigation was introduced may fail resume evidence matching; start a new review instead of rewriting their saved evidence.

## Collected evidence and candidate delivery

The run-wide pool and each candidate's supplied sources are tracked separately. Native file coverage is keyed by the pinned revision, path and actual file-line span. Only native readers can mark an excerpt as reaching EOF. A whole-file read can satisfy a covered line request, including one extending past EOF; continuous adjacent excerpts can jointly satisfy a range. A gap, another revision, a diff, an index, a legacy heading or a knowledge excerpt cannot stand in for a complete native file. Existing source identities and quote/range coordinates remain unchanged. Reuse selects existing complete evidence blocks rather than cropping or renaming their text.

Successful requests retain the source IDs they resolved to. A later candidate can request those sources even if the same request was already deduplicated globally. Cached material needs no further repository read or additional collected-evidence bytes. The next verification input pins only sources resolved for that candidate's requests, instead of every source collected for its peers. Scoped knowledge lookups retain their query-specific section matches; direct file reads still cannot bypass knowledge scoping.

Follow-up continues when new global material is collected **or** a pending candidate needs already collected material it has not received. If its input already covers the request, changing `all` to a range does not create a new source or another reasoning pass. `coverage.md` distinguishes `REUSED` requests from candidate-specific queued source IDs. Unfinished or semantically unresolved decisions are retained; delivery itself never creates a verdict.

`EvidenceDeliveryTests` covers same-batch reuse, revision isolation, EOF and gap handling, globally cached but hidden documents, exact and ranged re-delivery, a differently spelled covered request after delivery, peer isolation, knowledge-query isolation and zero-call resume. These local fixtures establish data routing and source integrity, not a fixed real-model speedup.
