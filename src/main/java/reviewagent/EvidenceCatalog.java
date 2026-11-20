package reviewagent;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Catalog lookup uses stable identities; presentation aliases cannot grant a source a different role. */
final class EvidenceCatalog {
    private final Map<String, EvidenceSource> sources;
    private final Map<String, String> aliases = new LinkedHashMap<>();
    private final Map<String, String> references = new LinkedHashMap<>();
    private final boolean authoritative;

    EvidenceCatalog(List<EvidenceSource> entries, boolean authoritative) {
        var items = new LinkedHashMap<String, EvidenceSource>();
        Set<String> ambiguous = new java.util.HashSet<>();
        Pattern kb = Pattern.compile("^((KB-[1-9][0-9]*) — .+) \\(lines [1-9][0-9]*–[1-9][0-9]*\\)$");
        for (EvidenceSource entry : entries) {
            items.merge(entry.id(), entry, (existing, incoming) -> existing.authoritative() ? existing : incoming);
            alias(entry.id(), entry.id(), ambiguous);
            alias(entry.title(), entry.id(), ambiguous);
            alias(entry.id() + ": " + entry.title(), entry.id(), ambiguous);
            if (entry.role(EvidenceSource.Role.DIFF) && !entry.path().isEmpty()) {
                alias(entry.path() + " diff", entry.id(), ambiguous);
                alias(entry.path() + " [diff]", entry.id(), ambiguous);
            }
            var match = kb.matcher(entry.title());
            if (entry.role(EvidenceSource.Role.CONTRACT) && match.matches()) {
                alias(match.group(1), entry.id(), ambiguous); alias(match.group(2), entry.id(), ambiguous);
            }
        }
        int sequence = 1;
        for (EvidenceSource source : items.values()) {
            String reference = "SRC-" + sequence++;
            alias(reference, source.id(), ambiguous);
            references.put(source.id(), reference);
        }
        ambiguous.forEach(aliases::remove);
        sources = java.util.Collections.unmodifiableMap(items);
        this.authoritative = authoritative;
    }

    private void alias(String name, String id, Set<String> ambiguous) {
        String previous = aliases.putIfAbsent(name, id);
        if (previous != null && !previous.equals(id)) ambiguous.add(name);
    }

    static EvidenceCatalog empty() { return new EvidenceCatalog(List.of(), false); }
    private EvidenceCatalog(EvidenceCatalog original, Set<String> included) {
        var selected = new LinkedHashMap<String, EvidenceSource>();
        original.sources.forEach((id, source) -> { if (included.contains(id)) selected.put(id, source); });
        sources = java.util.Collections.unmodifiableMap(selected);
        original.aliases.forEach((alias, id) -> { if (included.contains(id)) aliases.put(alias, id); });
        original.references.forEach((id, reference) -> { if (included.contains(id)) references.put(id, reference); });
        authoritative = original.authoritative;
    }

    /** Keep original SRC aliases and ambiguity decisions; never renumber a task's subset. */
    EvidenceCatalog select(Set<String> included) { return new EvidenceCatalog(this, included); }
    List<EvidenceSource> sources() { return List.copyOf(sources.values()); }
    boolean authoritative() { return authoritative; }
    EvidenceCatalog plus(EvidenceCatalog other) {
        var all = new java.util.ArrayList<>(sources()); all.addAll(other.sources());
        return new EvidenceCatalog(all, authoritative || other.authoritative);
    }
    EvidenceSource source(String name) {
        EvidenceSource direct = sources.get(aliases.get(name));
        if (direct != null) return direct;
        int suffix = name.lastIndexOf(" [");
        if (suffix < 0 || !name.endsWith("]")) return null;
        String role = name.substring(suffix + 2, name.length() - 1);
        if (!role.equals("source") && !role.equals("contract")) return null;
        EvidenceSource indexed = sources.get(aliases.get(name.substring(0, suffix)));
        return indexed != null && indexed.role(EvidenceSource.Role.CONTRACT) == role.equals("contract") ? indexed : null;
    }

    String numberedMarkdown(String markdown) {
        var byTitle = sources.values().stream().collect(java.util.stream.Collectors.groupingBy(EvidenceSource::title));
        var blocks = EvidenceText.SOURCE_BLOCK.matcher(markdown);
        var rendered = new StringBuilder(markdown.length());
        while (blocks.find()) {
            String format = blocks.group(3).strip();
            String body = blocks.group(4);
            var matches = byTitle.getOrDefault(blocks.group(1), List.of()).stream()
                    .filter(source -> source.format().equals(format)
                            && (body.equals(source.text()) || body.equals(source.text() + "\n"))).limit(2).toList();
            if (matches.size() == 1) {
                var source = matches.getFirst();
                blocks.appendReplacement(rendered, java.util.regex.Matcher.quoteReplacement(numberedBlock(source).stripTrailing() + "\n"));
            }
        }
        return blocks.appendTail(rendered).toString();
    }

    private String numberedBlock(EvidenceSource source) {
        var lines = source.text().lines().toList();
        return EvidenceText.block(reference(source) + " — " + source.title() + " (" + provenance(source)
                + "; " + lines.size() + " block lines)", source.format(), EvidenceText.numberedLines(lines));
    }

    String inventory() {
        return sources.values().stream().map(source -> "- " + reference(source) + ": " + source.title() + " ["
                + (source.role(EvidenceSource.Role.CONTRACT) ? "contract" : "source") + "]\n  " + provenance(source)
                + "; block lines: " + source.text().lines().count() + "; evidence-id: " + source.id() + "\n")
                .collect(java.util.stream.Collectors.joining());
    }

    String reference(EvidenceSource source) {
        String reference = references.get(source.id());
        return source.id().equals(aliases.get(reference)) ? reference : source.id();
    }

    String describe(EvidenceSource source) {
        return reference(source) + ": " + source.title() + " (" + provenance(source) + "; evidence-id: " + source.id() + ")";
    }

    private static String provenance(EvidenceSource source) {
        if (source.role(EvidenceSource.Role.DIFF)) return "Kind: DIFF; base: " + source.baselineRevision()
                + "; target: " + source.revision();
        String kind = source.role(EvidenceSource.Role.CONTRACT) ? "CONTRACT"
                : source.role(EvidenceSource.Role.INDEX) ? "INDEX"
                : source.role(EvidenceSource.Role.TARGET) ? "TARGET_SOURCE"
                : source.role(EvidenceSource.Role.BASELINE) ? "BASE_SOURCE" : "SOURCE";
        return "Kind: " + kind + "; revision: " + source.revision();
    }

    String manifest() {
        StringBuilder out = new StringBuilder("# Evidence provenance\n\nNative file locations are bound to collector metadata. "
                + "Legacy provider blocks retain quotation validation; location metadata may be unavailable.\n\n");
        for (EvidenceSource source : sources.values()) {
            out.append("- ").append(source.id()).append(" — ").append(EvidenceText.oneLine(source.title()))
                    .append("\n  - Citation reference: ").append(reference(source))
                    .append("\n  - Roles: ").append(source.roles().stream().sorted().toList())
                    .append("; provenance: ").append(source.authoritative() ? "NATIVE" : "LEGACY_PROVIDER")
                    .append("; revision: ").append(source.revision())
                    .append("; path: ").append(EvidenceText.oneLine(source.path()));
            if (!source.role(EvidenceSource.Role.DIFF) && source.firstLine() > 0) {
                out.append("; first line: ").append(source.firstLine()).append("; last line: ")
                        .append(source.firstLine() + Math.max(1L, source.text().lines().count()) - 1);
            }
            if (source.role(EvidenceSource.Role.DIFF) && !source.baselineRevision().isEmpty()) {
                out.append("; base revision: ").append(source.baselineRevision()).append("; base path: ")
                        .append(EvidenceText.oneLine(source.baselinePath()));
            }
            out.append('\n');
        }
        return out.toString();
    }

    static boolean implementation(LanguageRegistry languages, String path) {
        try { return languages.forPath(path).implementationEvidence(path); }
        catch (IOException missing) { return !path.matches("(?i).*\\.(md|mdx|txt|rst|adoc)$"); }
    }
}
