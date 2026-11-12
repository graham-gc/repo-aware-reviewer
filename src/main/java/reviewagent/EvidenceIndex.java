package reviewagent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Validates quotations and binds them to collector-owned identities and source spans. */
final class EvidenceIndex {
    record Citation(String source, String quote, String sourceId, List<EvidenceSource.Location> locations) {
        Citation(String source, String quote) { this(source, quote, "", List.of()); }
        Citation { locations = List.copyOf(locations); }
    }
    private final EvidenceCatalog catalog;

    EvidenceIndex(EvidenceCatalog catalog) { this.catalog = catalog; }
    EvidenceIndex(EvidenceBundle evidence, String supplemental) {
        this(evidence.catalog().plus(LegacyEvidenceAdapter.read(supplemental, evidence.scope(), LanguageRegistry.defaults())));
    }
    String inventory() { return catalog.inventory(); }
    void validate(List<Citation> citations) throws IOException { bind(citations); }

    List<Citation> bind(List<Citation> citations) throws IOException {
        var errors = new ArrayList<String>();
        var bound = new ArrayList<Citation>();
        for (Citation citation : citations) {
            EvidenceSource source = catalog.source(citation.source());
            String quote = normalize(citation.quote());
            if (source == null || quote.length() < 12 || !normalize(source.text()).contains(quote)) {
                String cause = source == null ? "unknown or ambiguous source reference"
                        : quote.length() < 12 ? "quote is too short; copy a longer contiguous excerpt (minimum 12 normalized characters)"
                        : "quote is absent from this source";
                errors.add(EvidenceText.oneLine(citation.source()) + "; " + cause
                        + "; invalid quote: " + EvidenceText.oneLine(citation.quote())
                        + (source != null && quote.length() >= 12 ? sourceHint(source.text(), citation.quote()) : ""));
            } else {
                bound.add(new Citation(source.title(), citation.quote(), source.id(), source.locations(citation.quote())));
            }
        }
        if (!errors.isEmpty()) throw new SourceQuotationException("Citation must quote an included evidence block verbatim:\n"
                + String.join("\n", errors));
        return List.copyOf(bound);
    }

    private static String sourceHint(String source, String quotation) {
        String first = normalize(quotation.lines().findFirst().orElse(""));
        List<String> lines = source.lines().toList();
        int best = -1, bestPrefix = 11;
        boolean ambiguous = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = normalize(lines.get(i));
            int prefix = 0;
            while (prefix < Math.min(first.length(), line.length()) && first.charAt(prefix) == line.charAt(prefix)) prefix++;
            if (prefix > bestPrefix) {
                best = i;
                bestPrefix = prefix;
                ambiguous = false;
            } else if (prefix == bestPrefix) ambiguous = true;
        }
        if (best < 0 || ambiguous) return "";
        String hint = String.join("\n", lines.subList(best, Math.min(best + 3, lines.size())));
        return "\nProvided source with a matching prefix (hint only; the quotation still must match verbatim): "
                + EvidenceText.oneLine(hint.substring(0, Math.min(hint.length(), 600)));
    }


    boolean hasImplementation(List<Citation> citations) { return hasRole(citations, EvidenceSource.Role.IMPLEMENTATION); }
    boolean hasContract(List<Citation> citations) { return hasRole(citations, EvidenceSource.Role.CONTRACT); }
    private boolean hasRole(List<Citation> citations, EvidenceSource.Role role) {
        return citations.stream().map(c -> catalog.source(c.source())).anyMatch(s -> s != null && s.role(role));
    }
    boolean hasBaseline(List<Citation> citations) {
        return citations.stream().anyMatch(c -> {
            EvidenceSource source = catalog.source(c.source());
            return source != null && source.role(EvidenceSource.Role.BASELINE) && source.role(EvidenceSource.Role.IMPLEMENTATION)
                    && (!source.role(EvidenceSource.Role.DIFF) || source.text().contains("new file mode ") || quotesDiffSide(c, source, '-'));
        });
    }
    boolean hasTarget(List<Citation> citations) {
        return citations.stream().anyMatch(c -> {
            EvidenceSource source = catalog.source(c.source());
            return source != null && source.role(EvidenceSource.Role.TARGET) && source.role(EvidenceSource.Role.IMPLEMENTATION)
                    && (!source.role(EvidenceSource.Role.DIFF) || quotesDiffSide(c, source, '+'));
        });
    }
    private static boolean quotesDiffSide(Citation citation, EvidenceSource source, char marker) {
        String quote = normalize(citation.quote());
        return source.text().lines().filter(line -> line.length() > 1 && line.charAt(0) == marker
                        && !line.startsWith("" + marker + marker + marker))
                .map(line -> normalize(line.substring(1))).filter(line -> line.length() >= 12)
                .anyMatch(line -> line.contains(quote) || quote.contains(line));
    }

    void validateLocation(ReviewFinding finding, List<Citation> citations) throws IOException {
        // Legacy providers without location metadata retain quote validation; native file sources must ground the anchor.
        var candidates = citations.stream().filter(c -> {
            EvidenceSource source = catalog.source(c.source());
            return source != null && source.authoritative() && source.role(EvidenceSource.Role.IMPLEMENTATION)
                    && source.role(EvidenceSource.Role.TARGET);
        }).flatMap(c -> c.locations().stream())
                .filter(l -> l.path().equals(finding.path()) && l.side() != EvidenceSource.Side.REMOVED).toList();
        boolean nativePath = catalog.sources().stream().anyMatch(s -> s.authoritative() && s.path().equals(finding.path())
                && s.role(EvidenceSource.Role.TARGET) && s.role(EvidenceSource.Role.IMPLEMENTATION));
        if (nativePath && candidates.stream().noneMatch(l -> finding.line() >= l.firstLine() && finding.endLine() <= l.lastLine())) {
            throw new ReviewDecisionException("Finding line range must be contained in a quoted target source span. Actual quoted spans: "
                    + candidates.stream().map(EvidenceSource.Location::reference).toList());
        }
    }

    static String normalize(String text) { return EvidenceSource.normalize(text); }
}
