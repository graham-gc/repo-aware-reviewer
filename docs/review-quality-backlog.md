# Review quality backlog

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
