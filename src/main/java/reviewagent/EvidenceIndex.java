package reviewagent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Validates quotations and binds them to collector-owned identities and source spans. */
final class EvidenceIndex {
    record Citation(String source, String quote, String sourceId, List<EvidenceSource.Location> locations, String excerptLines) {
        Citation(String source, String quote) { this(source, quote, "", List.of(), ""); }
        static Citation lines(String source, String selector) { return new Citation(source, "", "", List.of(), selector); }
        Citation { locations = List.copyOf(locations); }
    }
    private final EvidenceCatalog catalog;

    EvidenceIndex(EvidenceCatalog catalog) { this.catalog = catalog; }
    EvidenceIndex(EvidenceBundle evidence, String supplemental) {
        this(evidence.catalog().plus(LegacyEvidenceAdapter.read(supplemental, evidence.scope(), LanguageRegistry.defaults())));
    }
    String inventory() { return catalog.inventory(); }
    String reference(String id) {
        EvidenceSource source = catalog.source(id);
        return source == null ? id : catalog.reference(source);
    }
    void validate(List<Citation> citations) throws IOException { bind(citations); }

    List<Citation> bind(List<Citation> citations) throws IOException {
        var errors = new ArrayList<String>();
        var bound = new ArrayList<Citation>();
        for (Citation citation : citations) {
            EvidenceSource source = source(citation);
            String original = citation.quote();
            List<EvidenceSource.Location> selectedLocations = null;
            if (source != null && !citation.excerptLines().isEmpty()) {
                try {
                    EvidenceSource.Excerpt excerpt = source.excerpt(citation.excerptLines());
                    if (!original.isEmpty() && !normalize(original).equals(normalize(excerpt.text()))) {
                        throw new IOException("Quotation does not match the selected source lines");
                    }
                    original = excerpt.text();
                    selectedLocations = excerpt.locations();
                } catch (IOException invalid) {
                    errors.add(EvidenceText.oneLine(citation.source()) + "; invalid citation range: "
                            + EvidenceText.oneLine(citation.excerptLines()) + "; " + invalid.getMessage());
                    continue;
                }
            }
            String quote = normalize(original);
            if (source == null || quote.length() < 12 || !normalize(source.text()).contains(quote)) {
                String cause = source == null ? "unknown or ambiguous source reference"
                        : quote.length() < 12 ? "quote is too short; copy a longer contiguous excerpt (minimum 12 normalized characters)"
                        : "quote is absent from this source";
                errors.add(EvidenceText.oneLine(citation.source()) + "; " + cause
                        + "; invalid quote: " + EvidenceText.oneLine(citation.quote())
                        + (source != null && quote.length() >= 12 ? sourceHint(source, citation.quote()) : "")
                        + (quote.length() >= 12 ? referenceHint(source, quote) : ""));
            } else {
                bound.add(new Citation(source.title(), original, source.id(),
                        selectedLocations == null ? source.locations(original) : selectedLocations, citation.excerptLines()));
            }
        }
        if (!errors.isEmpty()) throw new SourceQuotationException("Citation must quote an included evidence block verbatim:\n"
                + String.join("\n", errors));
        return List.copyOf(bound);
    }

    private String referenceHint(EvidenceSource selected, String quote) {
        var matches = catalog.sources().stream()
                .filter(source -> selected == null || !source.id().equals(selected.id()))
                .filter(source -> normalize(source.text()).contains(quote))
                .sorted(java.util.Comparator.comparingInt(source -> selected != null && !selected.path().isEmpty()
                        && source.path().equals(selected.path()) ? 0 : 1)).limit(3).toList();
        if (matches.isEmpty()) return "";
        return "\nReference correction candidates (already included sources containing this exact quote; "
                + "not automatically accepted). Choose the intended file/revision/kind and copy its SRC reference:\n"
                + matches.stream().map(source -> "- " + catalog.describe(source))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private String sourceHint(EvidenceSource source, String quotation) {
        String first = normalize(quotation.lines().findFirst().orElse(""));
        List<String> lines = source.text().lines().toList();
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
        String anchor = normalize(lines.get(best));
        boolean exactAnchor = anchor.length() >= 12 && first.startsWith(anchor);
        if (!exactAnchor) {
            return "\nProvided source with a matching prefix (hint only; no excerpt selector is offered because the first source line differs): "
                    + EvidenceText.oneLine(String.join("\n", lines.subList(best, Math.min(best + 3, lines.size()))))
                    + "\nRe-read the actual condition, method, arguments and values; a shared prefix does not establish equivalent behavior.";
        }
        int count = Math.min(200, Math.max(3, (int) quotation.lines().count()));
        int last = Math.min(best + count, lines.size());
        String selector = (best + 1) + ":" + last;
        String hint = String.join("\n", lines.subList(best, last));
        return "\nProvided source with a matching prefix (hint only; the proposed quotation remains invalid). "
                + "Re-evaluate the claim against the actual excerpt. To cite it without retyping code, replace the "
                + "entire citation with this exact selector (omit quote):\n<citation><source>" + catalog.reference(source)
                + "</source><lines>" + selector + "</lines></citation>\n"
                + "Line numbers are relative to this included block, not repository file coordinates. Actual excerpt:\n"
                + EvidenceText.block("Source correction candidate " + source.id() + " (block lines " + selector + ")", "text",
                    EvidenceText.linePrefix(hint, 6_000));
    }

    private EvidenceSource source(Citation citation) {
        return catalog.source(citation.sourceId().isEmpty() ? citation.source() : citation.sourceId());
    }

    List<EvidenceSource.Location> targetLocations(List<Citation> citations) {
        return citations.stream().filter(citation -> {
            EvidenceSource source = source(citation);
            return source != null && source.role(EvidenceSource.Role.IMPLEMENTATION) && source.role(EvidenceSource.Role.TARGET);
        }).flatMap(citation -> citation.locations().stream()).filter(location -> location.side() != EvidenceSource.Side.REMOVED).toList();
    }

    boolean hasImplementation(List<Citation> citations) { return hasRole(citations, EvidenceSource.Role.IMPLEMENTATION); }
    boolean hasContract(List<Citation> citations) { return hasRole(citations, EvidenceSource.Role.CONTRACT); }
    private boolean hasRole(List<Citation> citations, EvidenceSource.Role role) {
        return citations.stream().map(this::source).anyMatch(s -> s != null && s.role(role));
    }
    boolean hasBaseline(List<Citation> citations) {
        return citations.stream().anyMatch(c -> {
            EvidenceSource source = source(c);
            return source != null && source.role(EvidenceSource.Role.BASELINE) && source.role(EvidenceSource.Role.IMPLEMENTATION)
                    && (!source.role(EvidenceSource.Role.DIFF)
                        || source.text().lines().anyMatch(line -> line.matches("new file mode [0-7]{6}"))
                        || quotesDiffSide(c, source, '-'));
        });
    }
    boolean hasTarget(List<Citation> citations) {
        return citations.stream().anyMatch(c -> {
            EvidenceSource source = source(c);
            return source != null && source.role(EvidenceSource.Role.TARGET) && source.role(EvidenceSource.Role.IMPLEMENTATION)
                    && (!source.role(EvidenceSource.Role.DIFF) || quotesDiffSide(c, source, '+'));
        });
    }
    private static boolean quotesDiffSide(Citation citation, EvidenceSource source, char marker) {
        String quote = normalize(citation.quote());
        if (quote.length() < 12) return false;
        return source.text().lines().filter(line -> line.length() > 1 && line.charAt(0) == marker
                        && !line.startsWith("" + marker + marker + marker))
                .map(line -> normalize(line.substring(1))).filter(line -> line.length() >= 12)
                .anyMatch(line -> line.contains(quote) || quote.contains(line));
    }

    void validateLocation(ReviewFinding finding, List<Citation> citations) throws IOException {
        // Legacy providers without location metadata retain quote validation; native file sources must ground the anchor.
        var candidates = citations.stream().filter(c -> {
            EvidenceSource source = source(c);
            return source != null && source.authoritative() && source.role(EvidenceSource.Role.IMPLEMENTATION)
                    && source.role(EvidenceSource.Role.TARGET);
        }).flatMap(c -> c.locations().stream())
                .filter(l -> l.path().equals(finding.path()) && l.side() != EvidenceSource.Side.REMOVED).toList();
        boolean nativePath = catalog.sources().stream().anyMatch(s -> s.authoritative() && s.path().equals(finding.path())
                && s.role(EvidenceSource.Role.TARGET) && s.role(EvidenceSource.Role.IMPLEMENTATION));
        long covered = finding.line();
        for (var span : candidates.stream().sorted(java.util.Comparator.comparingInt(EvidenceSource.Location::firstLine)).toList()) {
            if (span.lastLine() < covered) continue;
            if (span.firstLine() > covered) break;
            covered = Math.max(covered, (long) span.lastLine() + 1);
        }
        if (nativePath && covered <= finding.endLine()) {
            throw new ReviewDecisionException("Finding line range must be fully covered by quoted target source spans without gaps. Actual quoted spans: "
                    + candidates.stream().map(EvidenceSource.Location::reference).toList());
        }
    }

    static String normalize(String text) { return EvidenceSource.normalize(text); }
}
