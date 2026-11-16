package reviewagent;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Select complete, collector-owned blocks. No source text, IDs, coordinates or contracts are rewritten. */
record VerificationEvidence(EvidenceBundle bundle, EvidenceIndex index, int totalSources, boolean fallback) {
    private static final Pattern CALL = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]{3,})\\s*\\(");
    private static final Set<String> COMMON = Set.of("main", "equals", "length", "size", "format", "valueOf", "toString",
            "println", "print", "stream", "filter", "collect", "return", "while", "switch", "catch", "assert");

    static VerificationEvidence select(EvidenceBundle original, EvidenceCatalog available, String supplemental,
                                       ReviewFinding candidate, Set<String> pinned, boolean full) {
        String allMarkdown = original.markdown() + supplemental;
        if (full) return full(original, available, allMarkdown);
        var selected = new LinkedHashSet<String>();
        String allegation = String.join("\n", candidate.title(), candidate.trigger(), candidate.impact(), candidate.evidence(), candidate.diffRelation());
        StringBuilder context = new StringBuilder(allegation);
        boolean anchor = false;
        for (EvidenceSource source : available.sources()) {
            boolean ownFile = source.path().equals(candidate.path()) || source.title().equals(candidate.path())
                    || source.title().startsWith(candidate.path() + " at ")
                    || source.role(EvidenceSource.Role.DIFF) && source.baselinePath().equals(candidate.path());
            if (ownFile) {
                anchor = true;
                selected.add(source.id());
                context.append('\n').append(source.text());
            }
            if (source.role(EvidenceSource.Role.CONTRACT) || source.role(EvidenceSource.Role.INDEX)
                    || source.role(EvidenceSource.Role.IMPLEMENTATION) && source.path().isEmpty()
                    || pinned.contains(source.id()) || mentioned(allegation, source.path())
                    || mentioned(allegation, source.id()) || mentioned(allegation, available.reference(source))) selected.add(source.id());
        }
        // A missing anchor or untyped implementation is not a reason to silently discard evidence.
        if (!anchor) return full(original, available, allMarkdown);

        String primary = context.toString();
        Set<String> calls = new LinkedHashSet<>();
        var match = CALL.matcher(primary);
        while (match.find()) if (!COMMON.contains(match.group(1))) calls.add(match.group(1));
        // Rare call identifiers are navigation hints, not a language parser or proof of linkage.
        calls.removeIf(call -> available.sources().stream().filter(s -> mentioned(s.text(), call))
                .map(EvidenceSource::path).distinct().limit(5).count() > 4);
        String ownName = stem(candidate.path());
        for (EvidenceSource source : available.sources()) {
            String name = stem(source.path());
            if (source.role(EvidenceSource.Role.IMPLEMENTATION) && (mentioned(primary, name)
                    || peerTest(source.path(), name, ownName) || calls.stream().anyMatch(call -> mentioned(source.text(), call))))
                selected.add(source.id());
        }
        EvidenceCatalog catalog = available.select(selected);
        String markdown = "# Candidate evidence\n\nBase: `" + original.scope().baseCommit() + "`; target: `"
                + original.scope().targetCommit() + "`.\n\n"
                + "Only the following complete blocks are supplied for this task. Other collected material is not evidence here. "
                + "Request omitted callers, producers, guards or baseline code before confirming a claim that depends on them. "
                + "Existing SRC and EV identities and block-relative line numbers are unchanged.\n\n"
                + "Anchor the finding to the smallest decisive target span, preferably one line. Do not stretch line/endLine "
                + "across separate caller/producer locations; cite those separately as premises. Use short decisive contiguous "
                + "quotations. Missing policy or producer behavior requires more evidence or an insufficient verdict.\n\n"
                + catalog.sources().stream().map(EvidenceSource::markdown).collect(java.util.stream.Collectors.joining());
        EvidenceBundle view = new EvidenceBundle(original.scope(), original.changedFiles(), markdown, original.coverage(),
                original.supplementBudget(), original.scopedKnowledge(), catalog);
        return new VerificationEvidence(view, new EvidenceIndex(catalog, available), available.sources().size(), false);
    }

    private static VerificationEvidence full(EvidenceBundle original, EvidenceCatalog catalog, String markdown) {
        var bundle = new EvidenceBundle(original.scope(), original.changedFiles(), markdown, original.coverage(),
                original.supplementBudget(), original.scopedKnowledge(), catalog);
        return new VerificationEvidence(bundle, new EvidenceIndex(catalog), catalog.sources().size(), true);
    }
    private static String stem(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.indexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
    private static boolean peerTest(String path, String name, String own) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        return own.length() >= 4 && (lower.contains("test") || lower.contains("spec"))
                && name.toLowerCase(java.util.Locale.ROOT).contains(own.toLowerCase(java.util.Locale.ROOT));
    }
    private static boolean mentioned(String text, String token) {
        if (token.isEmpty()) return false;
        int offset = 0;
        while ((offset = text.indexOf(token, offset)) >= 0) {
            int end = offset + token.length();
            if ((offset == 0 || !Character.isJavaIdentifierPart(text.charAt(offset - 1)))
                    && (end == text.length() || !Character.isJavaIdentifierPart(text.charAt(end)))) return true;
            offset++;
        }
        return false;
    }

    String manifest(int fullPromptBytes, int selectedPromptBytes) {
        return "# Verification evidence selection\n\n- Mode: " + (fallback ? "full" : "scoped")
                + "\n- Full prompt bytes: " + fullPromptBytes + "\n- Selected prompt bytes: " + selectedPromptBytes
                + "\n- Provided sources: " + bundle.catalog().sources().size() + " / " + totalSources
                + "\n\nOnly listed sources can support citations in this task. Whole blocks retain their original contents and offsets.\n\n"
                + bundle.catalog().manifest();
    }
}
