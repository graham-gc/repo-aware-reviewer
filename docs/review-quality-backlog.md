# Review quality backlog

## Playground follow-up — 2026-11-17

A one-file playground change removed an empty-input guard from `average(values)`. Reviewers correctly identified that `average([])` changed from returning `0` to raising `ZeroDivisionError`, but disputed whether preserving the old behavior was a required contract. Publication collapsed the conflict into insufficient evidence and the report led with an empty-findings statement. Repeated candidate essays and audit-escaped newlines obscured the actionable compatibility question.

Publication now preserves this disagreement as a distinct programmatic `DISPUTED` outcome, retaining its source-grounded candidate and proof. The main Findings section displays grouped review-required risks separately from confirmed defects and execution failures. Each reconciled group uses one behavior summary, preferring a candidate corrected during grounded verification over the original unverified impact allegations. Every member ID and full decision remain in coverage; prose summaries are bounded and actual newlines are not rendered as literal escapes. Prompts separate local behavior, compatibility intent and downstream impact, and limit unrelated UI/integration boilerplate.

The minimized regression replays the original three-candidate/two-positive disagreement through verification, publication, rendering and resume using a fake model. It verifies a prominent review-required item without promoting missing business policy to a confirmed defect. Fresh real-model runs remain necessary to assess semantic quality and runtime; these tests do not establish a speedup or overall detection rate.

### Export and envelope follow-up — 2026-11-17

Two general Agent defects were reproduced independently of model reasoning:

- Incomplete reports referred to task `*-error.txt` files that the GitLab adapter did not export before deleting its temporary run. Task checkpoints now carry fixed failure categories/summaries and optional XML parser positions; reports reference those checkpoints, and exports preserve their relative paths. Raw errors, source quotations and model streams remain local. GitHub also preserves available diagnostics when the CLI returns incomplete or failed output, without masking its exit code.
- A regex recognized only XML/unlabelled fences. The closing fence of an earlier Python/Java/JSON example could be mistaken for an opener, leaving an empty XML fence after extracting the protocol root. Stateful fence tracking now removes the actual protocol envelope and preserves all other code and prose. EOF-closed Markdown wrappers still require a complete XML root; source validation is unchanged.

Other observations from local run `repo-aware-gitlab-20261117-190928` remain qualified:

| Observation | Agent/model boundary and current conclusion | Evidence needed before another behavior change |
| --- | --- | --- |
| A confirmed finding and a review-required group overlap | Same file/line does not establish identical behavior: the pending group also includes a different input case. Grouping is model-provided; each positive member is independently adjudicated, so an erroneous group cannot silently discard a distinct positive. Semantic overlap is still a presentation limitation, not a reproduced status-routing failure here. | A general case with identical trigger, behavior and contract whose validated verdict is duplicated, lost or routed to the wrong status. Do not merge by path/line or reviewer majority. |
| A pending candidate alleges production impact without callers | The report labels it as a candidate to check and explicitly limits verified impact. That does not make the allegation established. Existing tests preserve a previously grounded correction and exclude a later rejected candidate from the summary. A never-confirmed candidate can still be overbroad; phrase quality alone does not prove the Agent fabricated a finding. | A validated correction that the Agent replaces with the original allegation, or a pending allegation rendered as a confirmed fact. Such a reproduction warrants a general publication fix. |
| Eighteen calls for a one-file change take about six minutes | At parallelism 1, the recorded 381.574 s workflow includes 380.990 s inside launched model processes. This includes CLI startup, routing/network and service response, not just model inference. Five correction calls account for 87.907 s; one unchanged follow-up is already skipped. The small local-overhead remainder does not prove that all calls are necessary, nor that they are redundant. | Trace which initial, supplemental, correction and adjudication calls could be removed while retaining independent verification and evidence coverage; compare identical inputs/configuration. Invocation count and prompt size are Agent-controlled, while backend latency and semantic mistakes are not fully controllable. |

The old export lacks the raw failing response/error, so the exact cause of its unfinished candidate cannot be recovered retrospectively. Offline tests establish the new export paths, protocol boundaries and safe metadata, not a real-model precision or latency improvement. No playground-specific production rule, heuristic verdict promotion or new model retry was added for these observations.

### Final consolidation failure — 2026-11-17

Local run `repo-aware-gitlab-20261117-205629` completed verification and publication adjudication. Its final confirmed-finding set was empty; one candidate was disputed and two remained insufficient. The report model supplied an invalid finding group even after correction. Rejecting that group was correct. However, the required-stage fallback then recomputed completion from empty decision lists, omitted the completed review-required risks and reported zero candidates requiring human review. The shared-stage error also retained a temporary container path instead of linking to the exported root-level task checkpoint.

The pipeline now retains the latest completed verification, publication grouping and requirement-plan snapshots for fallback rendering and completion counts. Confirmed findings, evidence gaps, publication disputes and failed checks use the same deterministic presentation in normal and fallback reports. Only publication-approved positives can appear as confirmed defects; a failure earlier in grouping cannot promote initial positive decisions. Coverage is saved at completed stage boundaries. Required-stage exceptions carry typed failed-task references, rendered as run-relative checkpoint links without raw error quotations or temporary absolute paths.

Offline regression cases exercise final grouping rejection with zero confirmed findings, retained grouped disputes and counts, a successful selective resume, earlier grouping/assessment failures, mixed confirmed/uncertain/disputed/rejected/failed outcomes in both languages, and root/nested multi-task diagnostic links. The invalid group still produces `INCOMPLETE` and CLI exit 3. These changes preserve validated work and diagnostics; they neither approve a malformed response nor settle an unspecified business contract.

Recorded: 2026-11-12. These are observations about this review agent, not approved business rules for a reviewed repository.

Reference case: local run `20261112-134821-c21dbab92fd4`, comparing `80507ae77a035aca11550670f23de607f354d676` with `c21dbab92fd42799fb6cb6ed7400ef3bd5b1eb41`. Generated artifacts are local, ignored files under `review-output/`; they are not included in this document. No target build or tests were run for this assessment.

## RQ-001: Match finding certainty and severity to proved premises

- Priority: high. Status: safeguards implemented; semantic evaluation remains required.
- Observation: the highest-severity finding establishes that the client cannot independently verify the complete expected node set. Its publication verdict then treats a successful but incomplete server response as a proved reachable failure without closing that premise against the producer's execution and persistence path.
- Evidence: reference report lines 43–49; publication adjudication for `R1-2`.
- Required behavior: distinguish a demonstrated regression, an explicit unmet defensive requirement, a possible hardening gap, and an unresolved producer contract. Do not infer reachability from the absence of a separate completeness field. Preserve the assumptions needed for stale-report findings as well, including directory reuse and the consuming tool's treatment of exit status.
- Acceptance: a regression case must reject or qualify an unsupported trigger even if multiple reviewers repeat it; explicit requirements to defend against malformed responses must still be reviewed.

## RQ-002: Do not strengthen requirements or publish contradictory acceptance decisions

- Priority: high. Status: safeguards implemented; semantic evaluation remains required.
- Observation: item `A17` requires exit code 2 to take precedence when execution failure overlaps with unevaluated business results. The supplied requirements do not specify that exact numeric priority. The report later says the overlap policy needs clarification.
- Evidence: reference report lines 229–237 and 703.
- Required behavior: retain the original requirement's strength. Separate policy questions from failures of an explicit contract, and reconcile acceptance conclusions with their stated limitations and finding verdicts before publication.
- Acceptance: an unspecified priority is reported as a question or evidence gap, not a confirmed violation. Independent audit must challenge strengthened paraphrases even when the attached verbatim quotation is genuine.

## RQ-003: Recover from failed repository navigation

- Priority: high. Status: implemented with regression coverage.
- Observation: controller-directory navigation was sent through the Java dependency resolver, which expected Maven coordinates. The review stopped at an API wiring evidence gap although the four mappings are available in the tracked `BrickController.java`.
- Evidence: reference `coverage.md`, discovery evidence requests; report `A40`, lines 453–461.
- Required behavior: provide language-neutral tracked-file discovery, distinguish repository navigation from dependency lookup, and make capability failures actionable. A failed method must allow another supported read path without repeatedly attempting the same unsupported request.
- Acceptance: a mixed Python/Java change locates and reads the relevant controller mappings without running repository commands or installing dependencies. A genuinely external frontend remains an explicit evidence gap.

## RQ-004: Bind cited locations to the pinned source

- Priority: high. Status: implemented for native collectors; legacy-provider location coverage remains limited.
- Observation: the first finding cites `scripts/brick_run.py:334–341` for the final exit-code decision, but at the target revision those lines create JUnit cases; `finalize()` is at lines 299–309. The nonempty-node check and two report writes also have imprecise locations.
- Evidence: reference report lines 45–56; target file at the recorded commit.
- Required behavior: retain file, revision, source role, and actual line spans with each excerpt. Validate finding locations and derive citation locations from the selected evidence instead of relying on model-written prose.
- Acceptance: a genuine quote attached to an incorrect line span fails location validation or is rendered with the verified span. Diff locations and target-file locations remain distinct.

## RQ-005: Make the report concise and consistently localized

- Priority: medium. Status: concise/localized presentation implemented; semantic duplicate reduction remains an audit responsibility.
- Observation: the reference report has 713 lines and 61 acceptance items, with substantial overlap; `none` appears 52 times in a Chinese report.
- Required behavior: group repeated behavior coverage, lead with unresolved and unmet items, retain a detailed audit artifact, and render standard empty-gap/status values in the requested language.
- Acceptance: the concise report and detailed assessment retain consistent item identities and evidence references, with no loss of distinct requirements. Standard Chinese report values do not contain the English placeholder `none`.

## Follow-up order

First establish typed evidence and request contracts that support RQ-003 and RQ-004. Then strengthen RQ-001/RQ-002 with saved-response regression cases and explicit publication checks. Address presentation in RQ-005 without treating fewer report lines as a substitute for more reliable conclusions.

Passing protocol, cache, or citation-format tests does not establish review precision or recall. Quality claims require manually adjudicated cases, including unsupported findings and missed defects.

## Initial implementation

- RQ-001: confirmation now includes a support kind and evidence-backed premises. Assumptions, hardening and unresolved support cannot publish as confirmed failures. Explicit defensive requirements remain eligible with contract evidence. Verification and publication prompts challenge producer success transitions, stale-directory preconditions, and impact scope. A separate bounded decision-correction path can downgrade unsupported conclusions.
- RQ-002: acceptance items state `EXPLICIT` or `UNSPECIFIED` policy basis and explain it. Unspecified policy cannot receive a conclusive status; opposite statuses/policy bases for the same literal requirement and behavior are rejected. Independent audit specifically challenges strengthened paraphrases and numeric/overlap priorities.
- RQ-003: language-neutral tracked-directory/glob browsing is distinct from file reading and dependency loading. Legacy Java directory-index requests fall back to that browser. A mixed Python/Java fixture locates and reads the controller mappings without executing target programs or resolving Maven dependencies.
- RQ-004: native collectors retain evidence identities, roles, pinned revisions, file paths and excerpt offsets before rendering. Quotes bind to actual spans; finding anchors must be contained in quoted target code. Diff removals retain base paths/revisions, including renames. Published evidence references are derived from bound citations. `evidence-index.md` records provenance; legacy provider Markdown is adapted only at the compatibility boundary.
- RQ-005: the main report shows a concise acceptance list, with unmet/unresolved items first and all item IDs preserved. Full reasoning remains in `requirement-assessment.md`. Standard empty values are localized deterministically.

`ReviewQualityRegressionTests` contains minimized cases for these safeguards, plus decision-correction/resume checks. These tests do not invoke a real model. A model can still omit a premise, misclassify an inferred policy as explicit, or misread what a genuine quotation proves. The gates validate declared proof obligations and source grounding; they are not a semantic theorem prover. A fresh, manually checked model review of the reference change is still needed to measure improvement in actual findings and acceptance decisions.

Local run `20261112-212840-c21dbab92fd4` exposed a false rejection in publication task `REQ-A13`: an implementation premise cited a genuine new-file diff header to establish baseline absence, but the validator required target executable lines for every implementation premise. Baseline source, removed diff lines and genuine new-file headers now qualify as scope evidence. Target implementation, finding-location coverage and a target-backed reachable-failure premise remain required; explicit defensive support still needs a contract premise. Regression tests cover these distinctions, reject forged headers inside added code, and replay a cached correction without a backend call.

## Citation-source follow-up: CI example review

Reference case: local run `20261112-175437-c21dbab92fd4`, task `R1-2`. The verifier correctly left the external action-version premise unresolved, but used a full-file ID for diff metadata, then an unregistered `path diff` name during correction. It also cited the repository workflow diff for an unchanged `checkout@v4` line available only in the supplied full target file.

Unique `path diff` and `path [diff]` aliases now resolve only to indexed diff evidence; ambiguous aliases still fail. The citation inventory labels patch and base/target file sources separately. Quotation errors list already included exact-match source IDs and their provenance without automatically redirecting the citation or weakening source validation. The format/source-correction budget is unchanged.

Regression coverage includes this four-citation failure shape, wrong-kind rejection, ambiguous aliases, fabricated version quotations, one bounded source correction, and resume revalidation. Offline replay against the original evidence reproduced the remaining wrong `v4` source, returned the correct target-source ID, and validated the saved response after changing only that source reference in memory. The verdict stayed `INSUFFICIENT_EVIDENCE`; original artifacts were not modified, and no external version availability was assumed or verified.

## Citation transcription follow-up: S1-2

In the same local run, `S1-2` repeatedly quoted `runAssertionMapper.selectByRunNodeId(runId)` while the included target source contains `runAssertionMapper.selectByRunId(runId)`. The displayed `\n` sequences were diagnostic formatting; the saved response contains real newlines. A format or newline normalization would not repair the changed method name.

Source correction now provides the actual matching-prefix excerpt plus an explicit `source`/`lines` citation selector. `lines` uses one-based offsets within that immutable included block. The CLI extracts the text, retains its source identity, and maps selected offsets to file/revision or diff-side coordinates. This avoids asking the model to transcribe the same code again. Literal wrong quotes remain invalid; a response cannot combine a quote with a range to hide a mismatch. Unknown sources, malformed/reversed/out-of-block ranges, and ranges over 200 lines are rejected. Repeated text binds only to the explicitly selected occurrence.

`CitationRangeTests` covers the method-name error, generated feedback, bounded correction and resume, assessment citations, excerpt offsets, literal backslash-n, duplicate source lines, range boundaries, and added/removed diff provenance. Offline replay recreated both the saved initial and supplemental evidence byte-for-byte. Replacing only the bad citation in memory with the supplied selector (`EV-847be954a5b7ec37d12fb534`, block lines 752–762) passed source, provenance, and finding-location validation. Original artifacts were unchanged; no model, target build, or target tests were invoked. This verifies the citation mechanism, not the semantic correctness of the candidate's policy interpretation; independent adjudication still applies.

## Parser and candidate-isolation follow-up: R1-1

The next saved `R1-1` response replaced a closing quote tag with a MiniMax marker. Greedy scalar scanning then consumed later citations and produced a misleading unclosed-premise error. Parser hardening now shares one structural vocabulary across text decoding, schema validation and bounded recovery. Recovery inserts closing delimiters only when one candidate passes strict XML and structural checks; ambiguity, duplicate fields, unknown elements and truncated roots remain rejected. Delimiter changes are logged separately; corrupted quote text is preserved for exact-source rejection and source correction.

If an individual verification or publication reply still fails after bounded corrections, its candidate remains unverified and its peers finish. The final report receives a programmatic incomplete banner and `review-status.md` lists failed checks. A resumed valid verdict can clear that state; transport errors and required shared-stage failures still stop the run. This does not turn malformed responses into confirmed findings.

`ReviewXmlTests` covers missing scalar/container delimiters, sibling swallowing, ambiguity, duplicate fields, conflicting tags, truncated roots, CDATA and literal protocol tags, XML comments/declarations, fences/CRLF and preserved request boundaries. `ReviewTaskIsolationTests` covers concurrent peer completion, raw error preservation, partial reports and resume recovery. Offline inspection also found that finding lines 169–175 are jointly covered by excerpts 168–174 and 170–179: location validation now accepts their continuous union while rejecting gaps.

## Citation ergonomics and unmet-item closure

The completed partial reference run still had two invalid candidate checks. Publication `R1-1` repeatedly mistyped one character in a long EV hash; `S1-2` quoted a nonexistent cycle-check fragment. The prefix hint incorrectly offered an unrelated empty-node branch as a range selector. The acceptance audit also identified A41/A64 after discovery, but they never entered candidate verification, leaving unmet acceptance statements outside the formal findings path.

The evidence inventory now offers short `SRC-number` references mapped to immutable EV identities, with append-stable ordering and collision rejection. Feedback and grouped-decision prompts prefer those references. Range hints require a complete first source-line anchor; weak prefixes remain diagnostic context only.

Audited unmet items now become changed-code-anchored `REQ-A...` candidates. They share verification, evidence budgets, grouping and publication adjudication with initial findings. Final acceptance items are reconciled with the resulting verdicts; rejected/unverified items become insufficient evidence, not automatic passes. Unmapped items remain visible and incomplete. The original independent audit and item/candidate trace are preserved in separate artifacts.

`EvidenceReferenceTests` covers reference stability/roles/collisions and the misleading cycle-check hint. `AssessmentFindingsTests` includes an end-to-end empty-discovery case where a late requirement defect is verified, adjudicated and published, and a disproved unmet item is downgraded while preserving its original audit. This validates routing and safeguards with simulated replies; real-model semantic judgments still require review.

## A28 assessment-correction regression

Reference run `20261112-212840-c21dbab92fd4` reached the requirement audit. Its original A28 correctly quoted two adjacent requirement-table rows. While repairing unrelated source references, the model inserted an alignment separator between those rows, creating a new source mismatch and aborting the audit.

`AssessmentValidator` now keeps task-local, source-validated requirement identities stable across corrections and cache revalidation. Correction replies retain their raw artifacts; any restoration of a previously valid clause is logged and saved separately before the entire reply is validated again. Item omissions/additions cannot silently bypass review. Invalid initial quotations can instead use `requirementLines` references to the explicit primary document; no arbitrary Markdown rewriting is accepted.

`AssessmentCorrectionTests` covers the invented separator, primary-document range extraction, bounds and changed-rule rejection, criterion remapping/item-set guards, live correction, exact source entities and preserved request envelopes, plus cached recovery with a backend that must not be called. Offline replay reproduced all 43 saved evidence identities and validated the full 29-item saved audit after preserving its trusted clauses. A28 remained insufficient evidence with unspecified policy. The original run was not modified and no model or target program was invoked.
