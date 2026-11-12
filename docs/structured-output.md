# Structured response handling

The CLI treats model output as untrusted serialization. A readable XML tree does not establish source accuracy or review correctness.

## Boundaries

- `ReviewSchema` owns the scalar/container vocabulary and permitted children. It rejects unknown children and repeated single-value fields.
- `XmlTextFields` keeps prose and source code opaque, preserving CDATA, operators and literal XML-like text.
- `ReviewXml` finds one response envelope, handles XML fences and trivia, disables external XML access, and validates the parsed tree.
- `XmlStructureRepair` proposes bounded closing-delimiter insertions after strict parsing fails. Only one structurally valid candidate may be adopted. It never creates a status, finding, requirement, source quote or closing root at EOF.
- `ReviewGrounding` independently validates identities, exact quotations, selected source ranges, provenance, finding locations and publication prerequisites.

## Recovery policy

| Input | Handling |
| --- | --- |
| Raw or fenced XML; CRLF; declarations/comments around the root | Accept the envelope and preserve adjacent request lines. |
| XML-like source inside CDATA | Preserve the text, including literal protocol delimiters. |
| Missing scalar closer with one valid closing-parent boundary | Insert the delimiter only when strict parsing and the full structural schema yield one candidate. Preserve all scalar text. |
| Missing adjacent container closers before an existing ancestor close | Consider a bounded insertion; require unique structural validity. |
| Two valid structural interpretations | Reject rather than choose one. |
| A text field appears to swallow sibling citations | Require an explicit closer or CDATA; do not silently accept the merged field. |
| A root is truncated or absent | Reject; do not infer the missing response. |
| Unknown/mismatched closers, unknown fields, duplicate scalar fields | Reject; do not rename or discard them. |
| Source text is corrupted inside an otherwise recovered tree | Exact-source validation still rejects it. A separate source-correction attempt may select the actual included excerpt. |

Recovery is deliberately narrow. It examines at most 12 insertion candidates and does not combine arbitrary edits at different positions. Source XML containing protocol delimiters should use CDATA when it would otherwise be structurally ambiguous.

## Citation protocol

Citations prefer short `SRC-1`, `SRC-2`, … references from the current evidence inventory. They map to stable `EV-` identities in `evidence-index.md`; legacy EV IDs and unique registered titles still work. Appending supplemental evidence preserves existing short references. Ambiguous aliases are disabled rather than guessed. The inventory distinguishes diffs from base/target full source files. A unique `path diff` or `path [diff]` alias refers only to the indexed patch. The role copied in a display label cannot change provenance.

Each citation contains either a literal `quote` or a `lines` selector, never both. A literal quote must occur in that included source with at least 12 normalized characters. Code should use CDATA when it contains closing protocol tags. A CLI-provided correction selector can avoid repeated transcription:

```xml
<citation><source>SRC-17</source><lines>12:24</lines></citation>
```

These are one-based positions within the included evidence block, not repository file lines. The CLI extracts the actual text; selection is limited to 200 existing lines and cannot load omitted code. Selected offsets map back to pinned file coordinates or the appropriate diff side. Literal backslash sequences are not decoded as line breaks.

Source correction may suggest other already included blocks containing the exact quotation, or an actual excerpt anchored by a complete first source line. A merely shared prefix, such as `orderedNodes.size()` versus `orderedNodes.isEmpty()`, can show diagnostic context but cannot generate a range selector. Suggestions do not automatically validate a wrong quotation, source, or verdict. A knowledge excerpt can also use its unique `KB-` ID or title without the line suffix.

Finding ranges must be covered continuously by quoted target-code spans. Adjacent or overlapping excerpts may jointly establish coverage; a gap or a removed-line citation cannot establish target coverage.

Implementation premises may cite the target, the baseline, or an applicable diff side. A genuine `new file mode` patch header can establish baseline absence; text inside an added code line cannot. These scope facts do not prove a target failure: confirmed findings still require target implementation quotations and location coverage, and reachable-failure support requires a target-backed implementation premise. Explicit defensive requirements require a contract-backed premise.

## Audit and failure handling

Model responses remain in their original artifacts. Applied delimiter recoveries are recorded in `*-normalization.log`, including the affected field and response-block line. The log is scoped to each validation attempt, including cached replies. No recovered code text is substituted silently.

Individual candidate verification and publication adjudication isolate replies still invalid after bounded format/source/decision corrections. Those candidates have no confirmed finding, remain unverified, and appear in the report's incomplete banner and `review-status.md`. Other candidate tasks finish. Resume revalidates saved results, reuses valid matching tasks and retries invalid tasks.

`COMPLETE` describes successfully validated review output; it is not code approval or runtime verification. `INCOMPLETE` identifies candidate checks that could not produce validated results. An empty diff is `SKIPPED`. Transport failures, storage failures and failures in necessary shared review stages still stop the run.

Regression coverage is in `ReviewXmlTests`, `CitationRangeTests`, `ReviewTaskIsolationTests`, and the existing output/quality suites. These tests use local fixtures and simulated model replies; they do not establish semantic precision or recall for a real model.

## Requirement-to-finding closure

Every audited `NOT_SATISFIED` item is considered for a `REQ-A...` candidate using its bound target implementation citations. Candidates need a real changed-file anchor and go through the same independent verification, supplemental evidence rounds, grouping and publication adjudication as discovery candidates. Provisional severity and location can be corrected; the original unmet label is not evidence that a defect exists.

Only a confirmed candidate retains a conclusive unmet status in the final acceptance section. Rejected, invalid or inconclusive candidates remain insufficient evidence, with their reviewer reason. They are never automatically promoted to satisfied. Items without a changed-code anchor stay visible for manual review and make completion `INCOMPLETE`. The original audit is saved in `requirement-assessment-audited.md`; `assessment-findings.md` records item/candidate mappings and outcomes.

An invalid response is not an opposite semantic verdict and cannot veto an independently confirmed peer merely by sharing its group. It still marks the run incomplete. Genuine opposite validated verdicts retain the existing publication-conflict safeguard.

## Stable requirement identities during correction

An assessment item can use a literal `requirement` or a `requirementLines` range, never both. The latter selects 1–200 lines from the explicitly supplied primary requirements document; it cannot reference the repository or knowledge base. The CLI extracts the original clause and uses it for identity and source validation. For malformed Markdown quotations, exact range hints can locate the original rows without accepting inserted separator rows or changed business text.

Within one assessment task, the validator remembers clauses already matched against that primary document, even if another citation in the reply failed. Subsequent format/source/decision corrections cannot remap those item IDs to a different clause or silently omit/add assessment items. A changed previously validated clause is restored from that task's trusted snapshot before full validation; statuses, reasoning, code citations and other fields are not fabricated by that restoration. Independent audit still evaluates behavior and can add new items in its initial response.

The same protection applies while revalidating cached responses under an identical prompt. A saved source correction that damaged an otherwise valid clause can be recovered without another backend call if the resulting full reply validates. Raw repair replies remain in their `*-repair-response.md` artifacts; restored projections are saved as `*-applied-response.md`, with affected item IDs in `*-normalization.log`. The canonical task response contains the effective validated reply. No mismatching literal requirement is accepted merely by discarding Markdown formatting.
