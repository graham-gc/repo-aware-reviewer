package reviewagent;

import java.util.BitSet;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Coverage of native immutable file reads, distinct from request spelling and presentation IDs. */
final class FileEvidenceCache {
    private record Part(EvidenceSource source, int first, int last) { }

    static boolean alreadySupplied(EvidenceSource excerpt, EvidenceCatalog supplied) {
        if (excerpt == null || !nativeFile(excerpt)) return false;
        var lines = excerpt.text().lines().toList();
        var visible = new BitSet(lines.size());
        boolean knownEnd = !excerpt.endOfFile();
        for (var source : supplied.sources()) {
            if (!nativeFile(source) || !source.path().equals(excerpt.path()) || !source.revision().equals(excerpt.revision())
                    || !source.roles().equals(excerpt.roles())) continue;
            int offset = source.firstLine() - excerpt.firstLine();
            var existing = source.text().lines().toList();
            int sourceEnd = offset + existing.size();
            int first = Math.max(0, offset);
            int last = Math.min(lines.size(), sourceEnd);
            if (first > last || !existing.subList(first - offset, last - offset).equals(lines.subList(first, last))) continue;
            visible.set(first, last);
            if (source.endOfFile() && sourceEnd == lines.size()) knownEnd = true;
            if (knownEnd && visible.nextClearBit(0) >= lines.size()) return true;
        }
        return false;
    }

    static Set<String> covering(EvidenceQuery query, ReviewScope scope, EvidenceCatalog catalog) {
        if (query.operation() != EvidenceQuery.Operation.READ_FILE) return Set.of();
        boolean whole = query.selector().equals("all");
        int first = 1, last = Integer.MAX_VALUE;
        if (!whole) {
            if (!query.selector().matches("[1-9][0-9]{0,6}:[1-9][0-9]{0,6}")) return Set.of();
            String[] range = query.selector().split(":");
            first = Integer.parseInt(range[0]); last = Integer.parseInt(range[1]);
            if (last < first || last - first >= 200) return Set.of();
        }
        String revision = query.side().equals("base") ? scope.baseCommit() : scope.targetCommit();
        List<Part> parts = catalog.sources().stream()
                .filter(source -> nativeFile(source) && source.path().equals(query.resource()) && source.revision().equals(revision))
                .map(source -> new Part(source, source.firstLine(), source.firstLine() + (int) source.text().lines().count() - 1)).toList();
        final int from = first, to = last;
        // Prefer the smallest already collected block that satisfies the request.
        var single = parts.stream().filter(part -> part.first() <= from && (whole
                    ? part.first() == 1 && part.source().endOfFile()
                    : part.last() >= from && (part.last() >= to || part.source().endOfFile())))
                .min(Comparator.comparingInt(part -> EvidenceText.bytes(part.source().text())));
        if (single.isPresent()) return Set.of(single.get().source().id());
        var result = new LinkedHashSet<String>();
        int cursor = first;
        while (cursor <= last) {
            final int next = cursor;
            var match = parts.stream().filter(part -> part.first() <= next && part.last() >= next)
                    .max(Comparator.comparingInt(Part::last));
            if (match.isEmpty()) return Set.of();
            Part part = match.get(); result.add(part.source().id());
            if (part.source().endOfFile() || !whole && part.last() >= last) return java.util.Collections.unmodifiableSet(result);
            cursor = part.last() + 1;
        }
        return java.util.Collections.unmodifiableSet(result);
    }

    private static boolean nativeFile(EvidenceSource source) {
        return source.authoritative() && !source.path().isEmpty() && source.firstLine() > 0
                && (source.role(EvidenceSource.Role.BASELINE) || source.role(EvidenceSource.Role.TARGET))
                && !source.role(EvidenceSource.Role.DIFF) && !source.role(EvidenceSource.Role.INDEX)
                && !source.role(EvidenceSource.Role.CONTRACT);
    }
}
