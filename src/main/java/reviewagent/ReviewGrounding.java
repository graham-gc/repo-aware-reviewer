package reviewagent;

import static reviewagent.ReviewOutput.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Semantic publication rules, independent of XML decoding and Markdown rendering. */
final class ReviewGrounding {
    static Decision verification(String response, ReviewFinding candidate, EvidenceBundle evidence, EvidenceIndex index) throws IOException {
        Decision parsed = ReviewOutput.decision(response, candidate, evidence.changedFiles());
        Verification verdict = parsed.verdict();
        Relation relation = parsed.relation();
        index.validate(java.util.stream.Stream.concat(parsed.citations().stream(),
                parsed.support().premises().stream().flatMap(p -> p.citations().stream())).toList());
        List<EvidenceIndex.Citation> citations = index.bind(parsed.citations());
        String baseline = parsed.baseline(), contract = parsed.contract();
        var premises = new ArrayList<ReviewSupport.Premise>();
        for (var premise : parsed.support().premises()) {
            premises.add(new ReviewSupport.Premise(premise.statement(), premise.basis(), index.bind(premise.citations())));
        }
        ReviewSupport support = new ReviewSupport(parsed.support().kind(), List.copyOf(premises));
        citations = java.util.stream.Stream.concat(citations.stream(), premises.stream().flatMap(p -> p.citations().stream()))
                .distinct().toList();
        if (verdict.status() == Status.CONFIRMED) {
            if (!EvidenceRequest.parse(ReviewOutput.requestText(response, "verification")).isEmpty()) {
                throw new ReviewDecisionException("A finding with unresolved evidence requests must be INSUFFICIENT_EVIDENCE");
            }
            if (relation == Relation.UNRESOLVED || relation == Relation.PRE_EXISTING || baseline.isEmpty() || contract.isEmpty()) {
                throw new ReviewDecisionException("Confirmed findings require explicit change attribution, baseline comparison and applicable contract");
            }
            if (!index.hasImplementation(citations) || !index.hasTarget(citations)) {
                throw new ReviewDecisionException("Confirmed findings require quotations from included implementation evidence at the target revision");
            }
            if (relation != Relation.UNMET_REQUIREMENT && !index.hasBaseline(citations)) {
                throw new ReviewDecisionException("Introduced or exposed findings require baseline or diff evidence");
            }
            if (relation == Relation.UNMET_REQUIREMENT && !index.hasContract(citations)) {
                throw new ReviewDecisionException("An unmet requirement requires a quotation from a supplied requirement or trusted rule");
            }
            index.validateLocation(verdict.confirmed(), citations);
            validateSupport(support, index);
        }
        return new Decision(verdict, relation, baseline, contract, citations, support);
    }

    private static void validateSupport(ReviewSupport support, EvidenceIndex index) throws IOException {
        if (support.kind() == ReviewSupport.Kind.UNRESOLVED || support.kind() == ReviewSupport.Kind.HARDENING
                || support.premises().isEmpty()) {
            throw new ReviewDecisionException("Confirmation requires an explicit support kind and proved premises. "
                    + "Unresolved producer behavior or a hardening suggestion is not a confirmed reachable failure.");
        }
        for (var premise : support.premises()) {
            if (premise.basis() == ReviewSupport.Basis.ASSUMPTION
                    || premise.basis() == ReviewSupport.Basis.IMPLEMENTATION
                        && !index.hasTarget(premise.citations()) && !index.hasBaseline(premise.citations())
                    || premise.basis() == ReviewSupport.Basis.CONTRACT && !index.hasContract(premise.citations())) {
                throw new ReviewDecisionException("Unproved finding premise: " + premise.statement()
                        + ". Supply decisive implementation/contract quotations, or mark the finding INSUFFICIENT_EVIDENCE.");
            }
        }
        ReviewSupport.Basis required = support.kind() == ReviewSupport.Kind.EXPLICIT_DEFENSIVE_REQUIREMENT
                ? ReviewSupport.Basis.CONTRACT : ReviewSupport.Basis.IMPLEMENTATION;
        // Baseline and diff metadata can prove change attribution, but cannot prove a target failure path.
        if (support.premises().stream().noneMatch(p -> p.basis() == required
                && (required != ReviewSupport.Basis.IMPLEMENTATION || index.hasTarget(p.citations())))) {
            throw new ReviewDecisionException("Finding support kind requires a proved " + required
                    + (required == ReviewSupport.Basis.IMPLEMENTATION ? " premise at the target revision" : " premise"));
        }
    }

    static List<AssessmentItem> assessment(String response, InputDocument requirement, EvidenceIndex index,
                                           List<AssessmentItem> previous) throws IOException {
        List<AssessmentItem> parsed = new ArrayList<>();
        var items = new ArrayList<AssessmentItem>();
        var quoteErrors = new ArrayList<String>();
        String original = EvidenceIndex.normalize(requirement.content());
        for (AssessmentItem raw : ReviewOutput.assessmentItems(response)) {
            AssessmentItem item = raw;
            try { item = RequirementReference.resolve(raw, requirement); }
            catch (IOException invalid) { quoteErrors.add(raw.id() + " <requirementLines>: " + invalid.getMessage()); }
            parsed.add(item);
            if (item.requirementLines().isEmpty() && !RequirementReference.contains(original, item.requirement())) {
                quoteErrors.add(item.id() + " <requirement>: " + EvidenceText.oneLine(item.requirement()) + RequirementReference.hint(requirement, item.requirement()));
            }
            try { index.validate(item.citations()); }
            catch (SourceQuotationException invalid) { quoteErrors.add(item.id() + " " + invalid.getMessage()); }
        }
        if (!quoteErrors.isEmpty()) throw new SourceQuotationException(
                "Assessment requirement and citation quotations must match supplied sources verbatim.\n"
                + String.join("\n", quoteErrors) + "\nRe-copy applicable contiguous excerpts from the External requirement block. "
                + "Preserve original conjunctions, punctuation and strength. Put paraphrases only in requirementText.");
        for (AssessmentItem item : parsed) {
            var citations = index.bind(item.citations());
            if (item.policyBasis() == PolicyBasis.UNSPECIFIED && item.status() != AssessmentStatus.INSUFFICIENT_EVIDENCE) {
                throw new ReviewDecisionException(item.id() + ": unspecified policy must remain INSUFFICIENT_EVIDENCE. "
                        + "Do not promote a missing priority, numeric exit code or overlap rule into a requirement violation.");
            }
            if (item.status() != AssessmentStatus.INSUFFICIENT_EVIDENCE && (!index.hasImplementation(citations) || !index.hasTarget(citations))) {
                throw new ReviewDecisionException("A conclusive requirement assessment requires implementation quotations");
            }
            items.add(new AssessmentItem(item.id(), item.requirement(), item.requirementText(), item.behavior(), item.status(),
                    item.reasoning(), item.boundaries(), item.gap(), citations, item.policyBasis(), item.policyReason(), item.requirementLines()));
        }
        for (AssessmentItem item : items) {
            boolean conflicting = items.stream().anyMatch(other -> !other.id().equals(item.id())
                    && EvidenceIndex.normalize(other.requirement()).equals(EvidenceIndex.normalize(item.requirement()))
                    && EvidenceIndex.normalize(other.behavior()).equals(EvidenceIndex.normalize(item.behavior()))
                    && (other.policyBasis() != item.policyBasis() || other.status() != item.status()));
            if (conflicting) throw new ReviewDecisionException("Conflicting assessments of the same requirement behavior: " + item.id()
                    + ". Reconcile the verdicts or distinguish the observable behaviors explicitly.");
        }
        for (AssessmentItem old : previous) {
            if (items.stream().noneMatch(item -> item.id().equals(old.id())
                    && EvidenceIndex.normalize(item.requirement()).equals(EvidenceIndex.normalize(old.requirement())))) {
                throw new ReviewDecisionException("Requirement audit omitted or remapped an existing assessment item: " + old.id());
            }
        }
        if (!EvidenceRequest.parse(ReviewOutput.requestText(response, "assessment")).isEmpty()
                && items.stream().noneMatch(item -> item.status() == AssessmentStatus.INSUFFICIENT_EVIDENCE)) {
            throw new ReviewDecisionException("Unresolved requirement evidence requests require an INSUFFICIENT_EVIDENCE item");
        }
        return List.copyOf(items);
    }
}
