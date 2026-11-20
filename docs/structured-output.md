# Structured response handling

Each model task declares its response format in the request. Default risk prompts show where evidence requests belong and which verdict and proof values apply. The CLI validates the result against the review protocol and the pinned evidence.

## JSON Schema pilot

Structured review tasks can attach a schema to the model invocation. With `--focused-json-schema`, the initial risk checks send `--output-format json` and the packaged `focused-response.schema.json` to Claude Code. The schema puts evidence requests inside an insufficient-evidence decision. The CLI reads a successful `structured_output` envelope and applies the existing review, citation, and publication rules. If Claude Code exhausts its own structured output attempts, the reviewer records the task failure without launching another format repair.

This opt-in covers the initial risk scopes. The overview, local and documentation reviews, and independent verification use the existing XML protocol. Keep the flag on resume. For a matched comparison, review the same pinned change with and without the flag. Compare completion, citation-backed findings, CLI invocations, and Claude Code's reported `num_turns` across initial review, corrections, and verification. A CLI invocation can span multiple turns.

Each focused request lists the exact finding paths accepted by its scope validator. Deferred files stay visible in `coverage.md` and are sent with the request that reviews them. Other supplied source can support a finding at an allowed path. A candidate with an out-of-scope location gets a separate correction using the same pinned evidence. If the location remains unresolved, the report marks that check unfinished and preserves the other validated candidates.

For a focused scope, `DEEP` with an `INSUFFICIENT_EVIDENCE` decision identifies a concrete candidate that needs broader evidence. `DEEP` with empty decisions marks an unassessed scope as unfinished. An assessed scope with no candidates uses `LOCAL` and empty decisions.

## Boundaries

- `ReviewSchema` owns the scalar/container vocabulary and permitted children. It rejects unknown children and repeated single-value fields.
- `XmlTextFields` keeps prose and source code opaque, preserving CDATA, operators and literal XML-like text.
- `ReviewXml` finds one response envelope, handles XML fences and trivia, disables external XML access, and validates the parsed tree.
- `XmlStructureRepair` proposes bounded delimiter insertions or a single closing-tag name replacement after strict parsing fails. Only one structurally valid candidate may be adopted. It never creates a status, finding, requirement, source quote or closing root at EOF.
- `ReviewGrounding` independently validates identities, exact quotations, selected source ranges, provenance, finding locations and publication prerequisites.

## Recovery policy

| Input | Handling |
| --- | --- |
| Raw or fenced XML; CRLF; declarations/comments around the root | Accept the envelope and preserve adjacent request lines. |
| A protocol fence follows a Python, Java, JSON or other code block | Track every fence language and remove only the actual protocol wrapper. Preserve surrounding prose and code verbatim. |
| Backtick/tilde protocol fences, longer closing fences, or a Markdown fence closed by EOF | Recognize the wrapper only around an existing complete XML root. An XML root inside a different-language code block is rejected. Unrelated empty fences are preserved. |
| XML-like source inside CDATA | Preserve the text, including literal protocol delimiters. |
| Missing scalar closer with one valid closing-parent boundary | Insert the delimiter only when strict parsing and the full structural schema yield one candidate. Preserve all scalar text. |
| Missing adjacent container closers before an existing ancestor close | Consider a bounded insertion and require unique structural validity. |
| A child container closer is immediately repeated where its parent container should close | Try replacing the second closer's name when the parent is still open and the root already has an explicit closer. Require unique structural validity and keep the original text. |
| Two valid structural interpretations | Reject rather than choose one. |
| A text field appears to swallow sibling citations | Require an explicit closer or CDATA; do not silently accept the merged field. |
| A root is truncated or absent | Reject; do not infer the missing response. |
| Other mismatched closers, unknown fields, duplicate scalar fields | Reject. |
| Source text is corrupted inside an otherwise recovered tree | Exact-source validation still rejects it. A separate source-correction attempt may select the actual included excerpt. |

Recovery examines at most 12 candidates, each with an edit at one position. Source XML containing protocol delimiters should use CDATA when it would otherwise be structurally ambiguous.

## Citation protocol

Citations prefer short `SRC-1`, `SRC-2`, … references from the current evidence inventory. They map to stable `EV-` identities in `evidence-index.md`; legacy EV IDs and unique registered titles still work. Appending supplemental evidence preserves existing short references. Ambiguous aliases are disabled rather than guessed. The inventory distinguishes diffs from base/target full source files. A unique `path diff` or `path [diff]` alias refers only to the indexed patch. The role copied in a display label cannot change provenance.

Each citation contains either a literal `quote` or a `lines` selector, never both. A literal quote must occur in that included source with at least 12 normalized characters. Code should use CDATA when it contains closing protocol tags. A CLI-provided correction selector can avoid repeated transcription:

```xml
<citation><source>SRC-17</source><lines>12:24</lines></citation>
```

These are one-based positions within the included evidence block, not repository file lines. The CLI extracts the actual text; selection is limited to 200 existing lines and cannot load omitted code. Selected offsets map back to pinned file coordinates or the appropriate diff side. Literal backslash sequences are not decoded as line breaks.

Source correction may suggest other already included blocks containing the exact quotation, or an actual excerpt anchored by a complete first source line. A merely shared prefix, such as `orderedNodes.size()` versus `orderedNodes.isEmpty()`, can show diagnostic context but cannot generate a range selector. Suggestions do not automatically validate a wrong quotation, source, or verdict. A knowledge excerpt can also use its unique `KB-` ID or title without the line suffix.

Finding ranges must be covered continuously by quoted target-code spans. Adjacent or overlapping excerpts may jointly establish coverage; a gap or a removed-line citation cannot establish target coverage.

Request reuse separately tracks native file coverage and EOF metadata. It preserves the original source IDs and block-relative citation offsets; cached parent blocks remain the sources supplied to the requesting candidate. A source existing in the run-wide pool does not make it citable in a task that has not received it. Diff metadata, legacy labels and scoped knowledge sections cannot be substituted for full native file coverage. See [candidate delivery](runtime-reliability.md#collected-evidence-and-candidate-delivery).

Implementation premises may cite the target, the baseline, or an applicable diff side. A genuine `new file mode` patch header can establish baseline absence; text inside an added code line cannot. These scope facts do not prove a target failure: confirmed findings still require target implementation quotations and location coverage, and reachable-failure support requires a target-backed implementation premise. Explicit defensive requirements require a contract-backed premise.

## Audit and failure handling

Model responses remain in their original artifacts. Applied delimiter recoveries are recorded in `*-normalization.log`, including the affected field and response-block line. The log is scoped to each validation attempt, including cached replies. No recovered code text is substituted silently.

Validation rejections also append safe metadata to `*-validation.log`. Fixed error categories and optional XML parser positions survive successful correction, while raw exception messages and quotations stay in local artifacts. These events describe validation attempts, not model-call counts or semantic correctness.

Individual candidate verification and publication adjudication isolate replies still invalid after bounded format/source/decision corrections. Those candidates have no confirmed finding, remain unverified, and appear in the report's incomplete banner and `review-status.md`. Other candidate tasks finish. Resume revalidates saved results, reuses valid matching tasks and retries invalid tasks.

Unfinished-check references point to the task's `*-state.properties` at the same relative path in local and exported reports. These checkpoints include a fixed failure category and summary, plus parsed XML line/column when available. Raw `*-error.txt` files remain local because validator messages may quote source text or model output. Exported categories describe the validation or invocation failure; they do not establish whether the candidate is a defect. See [diagnostic fields](runtime-reliability.md#checkpoints-and-diagnostics).

`COMPLETE` describes successfully validated review output; it is not code approval or runtime verification. `INCOMPLETE` identifies candidate checks that could not produce validated results. An empty diff is `SKIPPED`. Independent invocation failures also mark checks unfinished without cancelling their peers. Required shared-stage failures block dependent conclusions and produce a deterministic incomplete report; authentication/configuration and storage failures still stop execution. See [runtime recovery](runtime-reliability.md).

Regression coverage is in `ReviewXmlTests`, `CitationRangeTests`, `ReviewTaskIsolationTests`, and the existing output/quality suites. These tests use local fixtures and simulated model replies; they do not establish semantic precision or recall for a real model.

## Requirement-to-finding closure

Every audited `NOT_SATISFIED` item is considered for a `REQ-A...` candidate using its bound target implementation citations. Candidates need a real changed-file anchor and go through the same independent verification, supplemental evidence rounds, grouping and publication adjudication as discovery candidates. Provisional severity and location can be corrected; the original unmet label is not evidence that a defect exists.

Only a confirmed candidate retains a conclusive unmet status in the final acceptance section. Rejected or inconclusive decisions remain insufficient evidence, with their reviewer reason. Invalid or failed candidate checks are displayed as programmatic `NOT_COMPLETED` assessments, distinct from business evidence gaps. Model assessment replies cannot claim this execution status themselves. They are never automatically promoted to satisfied. Items without a changed-code anchor stay visible for manual review and make completion `INCOMPLETE`. The original audit is saved in `requirement-assessment-audited.md`; `assessment-findings.md` records item/candidate mappings and outcomes.

An invalid response is not an opposite semantic verdict and cannot veto an independently confirmed peer merely by sharing its group. It still marks the run incomplete.

## Review-required risks and publication disputes

Validated publication decisions that disagree on the same grouped issue produce a programmatic `DISPUTED` outcome for its positive members. Their corrected findings, citations, implementation premises and base-to-target attribution are retained. They are not counted as confirmed defects. A model cannot output `DISPUTED` to bypass the verification protocol.

The main Findings section separates confirmed defects, review-required risks and unfinished checks. Grouped disputes and evidence gaps are rendered once per available reconciliation group, with candidate IDs, source references, a bounded explanation and a link to the full decisions in `coverage.md`. The behavior summary prefers a candidate corrected during grounded verification, even when publication later leaves its contract unresolved; original unverified downstream allegations are not restored. Invalid model replies remain unfinished checks. A report with review-required items does not use an empty “No verified findings” summary; `COMPLETE` still describes execution, not approval or resolution of every human decision.

Multiple valid evidence-gap candidates are eligible for grouping even when the review has zero confirmed findings. If this optional grouping task returns no validated result, the report keeps each candidate visible and coverage links to the grouping checkpoint.

Verification distinguishes source-established behavior, the contract that makes it defective, and any downstream impact. A local exception can be proved from base/target implementation without an external business document. The absence of callers limits claims about application outages, not the local behavior itself. Baseline behavior alone does not establish an immutable business contract; an intentional compatibility change may remain a review-required policy question.

An `INSUFFICIENT_EVIDENCE` verification may include an optional `<nextCheck>` with a maintainer action and the observation or policy answer that would resolve the gap. The renderer uses at most one suggestion per review-required item, including grouped disputes. The field is advisory, and the evidence and publication checks determine the verdict. Older responses remain valid with the field omitted.

Human-facing prose folds actual whitespace instead of reusing audit-record escaping. Literal backslash sequences in code or source quotations are preserved. Full evidence and reviewer explanations remain unabridged in the audit artifacts. `ReviewPublicationTests` covers the empty-input playground case, contradictory decisions, source-only proof, invalid peers, both report languages and cached-response replay through the full pipeline. These checks use simulated model outputs and do not establish a real model's precision or recall.

## Stable requirement identities during correction

An assessment item can use a literal `requirement` or a `requirementLines` range, never both. The latter selects 1–200 lines from the explicitly supplied primary requirements document; it cannot reference the repository or knowledge base. The CLI extracts the original clause and uses it for identity and source validation. For malformed Markdown quotations, exact range hints can locate the original rows without accepting inserted separator rows or changed business text.

Within one assessment task, the validator remembers clauses already matched against that primary document, even if another citation in the reply failed. Subsequent format/source/decision corrections cannot remap those item IDs to a different clause or silently omit/add assessment items. A changed previously validated clause is restored from that task's trusted snapshot before full validation; statuses, reasoning, code citations and other fields are not fabricated by that restoration. Independent audit still evaluates behavior and can add new items in its initial response.

The same protection applies while revalidating cached responses under an identical prompt. A saved source correction that damaged an otherwise valid clause can be recovered without another backend call if the resulting full reply validates. Raw repair replies remain in their `*-repair-response.md` artifacts; restored projections are saved as `*-applied-response.md`, with affected item IDs in `*-normalization.log`. The canonical task response contains the effective validated reply. No mismatching literal requirement is accepted merely by discarding Markdown formatting.
