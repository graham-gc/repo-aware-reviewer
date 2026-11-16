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
- A required shared task such as candidate grouping, requirement draft/audit or final consolidation blocks dependent conclusions if no valid result is available. The CLI writes a deterministic incomplete report without asking a model to fill the missing analysis. Only candidates that already passed publication adjudication can appear in that fallback; final deduplication may still be unfinished.
- Recognized authentication/configuration rejection and artifact-storage failures stop execution. Queued work is blocked and running task processes are cancelled. User interruption is also a cancellation, not a model timeout.
- A business verdict of insufficient evidence is distinct from a check that did not execute successfully. Failed candidate decisions carry an explicit unfinished flag; their linked acceptance items display `NOT_COMPLETED`. The model cannot emit that programmatic assessment status.

`review-status.md` records `RUNNING`, `COMPLETE`, `INCOMPLETE`, `FAILED`, `CANCELLED`, or `SKIPPED`. Review completion means required output is validated; it never means requirements are satisfied or target tests passed. CLI exit codes are `0` for completed/prepared/skipped output, `3` for incomplete review, `1` for execution failure and `2` for invalid arguments. An interrupt caught by the CLI returns `130`; operating-system signal handling may terminate the JVM directly.

## Subprocess ownership

The executor observes descendants while its own parent process runs and retains their handles. On exit, timeout or cancellation it terminates observed children, allows a bounded grace interval, then forcibly stops survivors. It also handles a parent exiting while a known child keeps an output pipe open. It never kills processes by executable name. A JVM shutdown hook also attempts bounded cleanup of active owned processes; forced OS termination such as SIGKILL cannot run that hook. Stream collection and shutdown are bounded so a reader cannot indefinitely occupy a review worker.

`processCleanup` records whether observed task processes were terminated, cleanup failed, or host restrictions prevented descendant enumeration. Enumeration is a best-effort snapshot: a child that detaches before being observed may be unidentifiable; a restricted host may prevent tree verification entirely. The log does not claim all upstream work has stopped. A failure to terminate observed processes or close streams is a fatal cleanup/storage-side error, not a reason to launch another model call.

## Checkpoints and diagnostics

Each invocation gets a new `*-attempts/attempt-NNNN/` directory containing `call-prompt.md`, `call-stdout.log`, `call-stderr.log`, and `call-execution.log`. This applies to initial calls, retries, output corrections and resumed attempts. Top-level log names remain compatibility views of the latest attempt.

Execution records include start time, working directory, prompt SHA-256 and UTF-8 byte count, process timeout, actual allowed duration, task call count, configured budgets, concurrency at launch, termination reason and process-cleanup coverage. Command arguments are recorded only as a fingerprint; credentials are not read from Claude settings for logging. Input bytes are not model token counts, and concurrency at launch is not a diagnosis of gateway overload.

Task `*-state.properties` checkpoints record `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED`, or `BLOCKED`, plus the prompt hash and failure kind. A state file alone never authorizes reuse. Resume requires the original scope and initial evidence, matches each full task prompt, and revalidates saved responses and corrections against the current validator. Changed tasks invalidate and archive their prior responses. The actual model route behind an external CLI is not a reliable cache identity; reusing a valid completed result does not establish that route is unchanged.

Canonical responses, prompts, reports and state checkpoints use complete temporary writes followed by an atomic rename in the same directory. Task success is recorded after response validation and publication of its checkpoint. Filesystems without atomic replacement support fail explicitly rather than publish a partially written checkpoint. Previous final reports are kept as `report-previous.md`; the current report is marked running before new model work begins. A hard process termination may leave that running marker and a hidden temporary file; neither is treated as a completed result.

## Validation limits

`AgentStabilityTests` injects empty responses, temporary and permanent exits, timeouts, inherited pipes and required-stage failures into local subprocesses. It checks peer survival, shared recovery budgets, owned-child cleanup, report completeness and selective resume. Process-tree assertions require an OS environment that permits enumeration; the macOS Codex sandbox may require running the test suite outside that restriction. Tests never call a real model or run a reviewed project's programs.

These tests establish local recovery behavior, not the capacity of a particular Claude/gateway route. Establishing that requires a small controlled comparison using the same prompts and configuration at different concurrency levels. A single successful serial review does not establish causality.
