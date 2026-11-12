package reviewagent;

import java.util.List;

/** Explicit proof obligations; an assumption cannot become a published failure premise. */
record ReviewSupport(Kind kind, List<Premise> premises) {
    enum Kind { REACHABLE_FAILURE, EXPLICIT_DEFENSIVE_REQUIREMENT, HARDENING, UNRESOLVED }
    enum Basis { IMPLEMENTATION, CONTRACT, ASSUMPTION }
    record Premise(String statement, Basis basis, List<EvidenceIndex.Citation> citations) { }
    static ReviewSupport unresolved() { return new ReviewSupport(Kind.UNRESOLVED, List.of()); }
}
