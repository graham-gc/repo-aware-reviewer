package reviewagent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Validates quotations and binds them to collector-owned identities and source spans. */
final class EvidenceIndex {
    static final String LINE_CITATION_GUIDANCE = """
            Cite a numbered evidence block by copying its SRC reference from the block heading and the printed
            line numbers before the pipe into <lines>first:last</lines>. These numbers belong to that block.
            Use actual target-file coordinates for a finding's line and endLine. For verbatim quote citations,
            copy the source text after the pipe. Use either quote or lines for each citation.

            """;

    record Citation(String source, String quote, String sourceId, List<EvidenceSource.Location> locations, String excerptLines) {
        Citation(String source, String quote) { this(source, quote, "", List.of(), ""); }
        static Citation lines(String source, String selector) { return new Citation(source, "", "", List.of(), selector); }
        Citation { locations = List.copyOf(locations); }
    }
    private final EvidenceCatalog catalog;
    private final java.util.Set<String> nativeTargetPaths;

    EvidenceIndex(EvidenceCatalog catalog) { this(catalog, catalog); }
    EvidenceIndex(EvidenceCatalog provided, EvidenceCatalog available) {
        catalog = provided;
        // Metadata can tighten location policy, but omitted sources can never supply quotations.
        nativeTargetPaths = available.sources().stream().filter(s -> s.authoritative() && s.role(EvidenceSource.Role.TARGET)
                && s.role(EvidenceSource.Role.IMPLEMENTATION)).map(EvidenceSource::path).collect(java.util.stream.Collectors.toSet());
    }
    EvidenceIndex(EvidenceBundle evidence, String supplemental) {
        this(evidence.catalog().plus(LegacyEvidenceAdapter.read(supplemental, evidence.scope(), LanguageRegistry.defaults())));
    }
    String inventory() { return catalog.inventory(); }
    String numberedMarkdown(String markdown) { return catalog.numberedMarkdown(markdown); }

    String verificationReferences(String path) {
        var sources = catalog.sources();
        var patches = sources.stream().filter(source -> source.role(EvidenceSource.Role.IMPLEMENTATION)
                && source.role(EvidenceSource.Role.DIFF) && path.equals(source.path())).toList();
        var baselinePaths = new java.util.HashSet<String>();
        baselinePaths.add(path);
        patches.forEach(source -> baselinePaths.add(source.baselinePath()));
        var standalone = sources.stream().filter(source -> source.role(EvidenceSource.Role.IMPLEMENTATION)
                && !source.role(EvidenceSource.Role.DIFF)).toList();
        var baseline = standalone.stream().filter(source -> source.role(EvidenceSource.Role.BASELINE)
                && baselinePaths.contains(source.path())).toList();
        var hints = new StringBuilder();
        appendReferences(hints, "DIFF", patches, EvidenceSource.Role.DIFF);
        appendReferences(hints, "BASE_SOURCE", baseline, EvidenceSource.Role.BASELINE);
        appendReferences(hints, "TARGET_SOURCE", standalone.stream()
                .filter(source -> path.equals(source.path())).toList(), EvidenceSource.Role.TARGET);
        appendReferences(hints, "CONTRACT", sources, EvidenceSource.Role.CONTRACT);
        if (!hints.isEmpty() && sources.stream().noneMatch(EvidenceIndex::hasBaselineMaterial)) {
            hints.append("No supplied block can provide baseline evidence for change attribution. ")
                    .append("For INTRODUCED or EXPOSED_BY_CHANGE, request the necessary BASE_SOURCE excerpt before confirming. ")
                    .append("UNMET_REQUIREMENT uses the applicable CONTRACT clause and target evidence.\n");
        }
        if (hints.isEmpty()) return "";
        return "CITATION REFERENCES FOR THIS CANDIDATE\n"
                + "Copy the reference before the parentheses into <source>. Inspect the actual lines and use the full inventory for related files.\n"
                + hints + "\n";
    }

    private void appendReferences(StringBuilder hints, String label, List<EvidenceSource> sources, EvidenceSource.Role role) {
        String references = sources.stream().filter(source -> source.role(role)).limit(5)
                .map(catalog::reference).collect(java.util.stream.Collectors.joining(", "));
        if (!references.isEmpty()) hints.append("- ").append(references).append(" (").append(label).append(")\n");
    }

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
            String selector = citation.excerptLines();
            if (source != null && !selector.isEmpty()) {
                try {
                    EvidenceSource.Excerpt excerpt = source.excerpt(selector);
                    if (!original.isEmpty() && !normalize(original).equals(normalize(excerpt.text()))) {
                        throw new IOException("Quotation does not match the selected source lines");
                    }
                    original = excerpt.text();
                    selectedLocations = excerpt.locations();
                    selector = excerpt.selector();
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
                        selectedLocations == null ? source.locations(original) : selectedLocations, selector));
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
                        || newFileDiff(source) || quotesDiffSide(c, source, '-'));
        });
    }
    boolean hasTarget(List<Citation> citations) {
        return citations.stream().anyMatch(c -> {
            EvidenceSource source = source(c);
            return source != null && source.role(EvidenceSource.Role.TARGET) && source.role(EvidenceSource.Role.IMPLEMENTATION)
                    && (!source.role(EvidenceSource.Role.DIFF) || quotesDiffSide(c, source, '+'));
        });
    }
    boolean hasPinnedTarget(List<Citation> citations) {
        return hasTarget(citations.stream().filter(citation -> {
            var source = source(citation);
            return source != null && source.authoritative();
        }).toList());
    }
    private static boolean quotesDiffSide(Citation citation, EvidenceSource source, char marker) {
        String quote = normalize(citation.quote());
        if (quote.length() < 12) return false;
        return diffSide(source, marker).anyMatch(line -> line.contains(quote) || quote.contains(line));
    }

    private static boolean newFileDiff(EvidenceSource source) {
        return source.text().lines().anyMatch(line -> line.matches("new file mode [0-7]{6}"));
    }

    private static boolean hasBaselineMaterial(EvidenceSource source) {
        return source.role(EvidenceSource.Role.BASELINE) && source.role(EvidenceSource.Role.IMPLEMENTATION)
                && (source.role(EvidenceSource.Role.DIFF)
                        ? newFileDiff(source) || diffSide(source, '-').findAny().isPresent()
                        : normalize(source.text()).length() >= 12);
    }

    private static java.util.stream.Stream<String> diffSide(EvidenceSource source, char marker) {
        return source.text().lines().filter(line -> line.length() > 1 && line.charAt(0) == marker
                        && !line.startsWith("" + marker + marker + marker))
                .map(line -> normalize(line.substring(1))).filter(line -> line.length() >= 12);
    }

    void validateLocation(ReviewFinding finding, List<Citation> citations) throws IOException {
        // Legacy providers without location metadata retain quote validation; native file sources must ground the anchor.
        var candidates = citations.stream().filter(c -> {
            EvidenceSource source = source(c);
            return source != null && source.authoritative() && source.role(EvidenceSource.Role.IMPLEMENTATION)
                    && source.role(EvidenceSource.Role.TARGET);
        }).flatMap(c -> c.locations().stream())
                .filter(l -> l.path().equals(finding.path()) && l.side() != EvidenceSource.Side.REMOVED).toList();
        boolean nativePath = nativeTargetPaths.contains(finding.path());
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
