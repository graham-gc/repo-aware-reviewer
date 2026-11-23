package reviewagent;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Select complete, collector-owned blocks. No source text, IDs, coordinates or contracts are rewritten. */
record VerificationEvidence(EvidenceBundle bundle, EvidenceIndex index, int totalSources, boolean fallback) {
    private static final Pattern CALL = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]{3,})\\s*\\(");
    private static final Pattern IMPORT = Pattern.compile("(?m)^[ +\\-]*\\h*(?:from|import|using|#include)\\h+[^\\r\\n]+");
    private static final Set<String> COMMON = Set.of("main", "equals", "length", "size", "format", "valueOf", "toString",
            "println", "print", "stream", "filter", "collect", "return", "while", "switch", "catch", "assert");

    static VerificationEvidence select(EvidenceBundle original, EvidenceCatalog available, String supplemental,
                                       ReviewFinding candidate, Set<String> pinned, boolean full) {
        return select(original, available, supplemental, candidate, pinned, full, false);
    }

    static VerificationEvidence select(EvidenceBundle original, EvidenceCatalog available, String supplemental,
                                       ReviewFinding candidate, Set<String> pinned, boolean full, boolean compact) {
        String allMarkdown = original.markdown() + supplemental;
        if (full) return full(original, available, allMarkdown);
        var selected = new LinkedHashSet<String>();
        var sources = available.sources();
        String allegation = String.join("\n", candidate.title(), candidate.trigger(), candidate.impact(), candidate.evidence(), candidate.diffRelation());
        StringBuilder context = new StringBuilder(allegation);
        StringBuilder nearby = new StringBuilder();
        boolean anchor = false;
        for (EvidenceSource source : sources) {
            boolean ownFile = source.path().equals(candidate.path()) || source.title().equals(candidate.path())
                    || source.title().startsWith(candidate.path() + " at ")
                    || source.role(EvidenceSource.Role.DIFF) && source.baselinePath().equals(candidate.path());
            if (ownFile) {
                anchor = true;
                selected.add(source.id());
                context.append('\n').append(source.text());
                if (compact && source.role(EvidenceSource.Role.DIFF) && source.path().equals(candidate.path()))
                    nearby.append(FocusedPatch.atTarget(source.text(), candidate.line(), candidate.endLine()));
            }
            if (source.role(EvidenceSource.Role.CONTRACT) || source.role(EvidenceSource.Role.INDEX)
                    || source.role(EvidenceSource.Role.IMPLEMENTATION) && source.path().isEmpty()
                    || pinned.contains(source.id()) || mentioned(allegation, source.path())
                    || mentioned(allegation, source.id()) || mentioned(allegation, available.reference(source))) selected.add(source.id());
        }
        // A missing anchor or untyped implementation is not a reason to silently discard evidence.
        if (!anchor) return full(original, available, allMarkdown);

        var localLanguages = !nearby.isEmpty() && candidate.severity().compareTo(ReviewFinding.Severity.MEDIUM) >= 0
                ? LanguageRegistry.defaults().modules().stream().filter(module -> module.supports(candidate.path())).toList()
                : List.<LanguageModule>of();
        boolean anchored = !localLanguages.isEmpty();
        String primary = anchored ? allegation + "\n" + nearby : context.toString();
        String imports = anchored ? IMPORT.matcher(context).results().map(match -> match.group())
                .collect(java.util.stream.Collectors.joining("\n")) : "";
        var nameCounts = anchored ? sources.stream().map(EvidenceSource::path).filter(path -> !path.isEmpty()).distinct()
                .collect(java.util.stream.Collectors.groupingBy(VerificationEvidence::filename, java.util.stream.Collectors.counting()))
                : java.util.Map.<String, Long>of();
        Set<String> calls = new LinkedHashSet<>();
        var match = CALL.matcher(primary);
        while (match.find()) if (!COMMON.contains(match.group(1))) calls.add(match.group(1));
        // Rare call identifiers are navigation hints, not a language parser or proof of linkage.
        calls.removeIf(call -> sources.stream().filter(s -> mentioned(s.text(), call))
                .map(EvidenceSource::path).distinct().limit(5).count() > 4);
        String ownName = stem(candidate.path());
        for (EvidenceSource source : sources) {
            String name = stem(source.path());
            if (!source.role(EvidenceSource.Role.IMPLEMENTATION)) continue;
            String file = filename(source.path());
            boolean named = anchored && (mentioned(primary, source.path())
                    || nameCounts.getOrDefault(file, 0L) == 1 && mentioned(allegation, file));
            if (peerTest(source.path(), name, ownName) || named
                    || relatedLanguage(localLanguages, source.path()) && (mentioned(primary, name)
                        || explicitlyImported(imports, candidate.path(), source.path(), name)
                        || calls.stream().anyMatch(call -> mentioned(source.text(), call))))
                selected.add(source.id());
        }
        EvidenceCatalog catalog = available.select(selected);
        String markdown = "# Candidate evidence\n\nBase: `" + original.scope().baseCommit() + "`; target: `"
                + original.scope().targetCommit() + "`.\n\n"
                + "Only the following complete blocks are supplied for this task. Other collected material is not evidence here. "
                + "Request omitted callers, producers, guards or baseline code before confirming a claim that depends on them. "
                + "Existing SRC and EV identities and block-relative line numbers are unchanged.\n\n"
                + (anchored ? "Related context follows the cited files and the changed region at the candidate's location. "
                        + "Check missing cross-file guards or protocol behavior by requesting the specific source. "
                        + "Scope the verdict to the trigger and outcome the supplied sources can prove.\n\n" : "")
                + "Anchor the finding to the smallest decisive target span, preferably one line. Do not stretch line/endLine "
                + "across separate caller/producer locations; cite those separately as premises. Use short decisive contiguous "
                + "quotations. Missing policy or producer behavior requires more evidence or an insufficient verdict.\n\n"
                + catalog.sources().stream().map(EvidenceSource::markdown).collect(java.util.stream.Collectors.joining());
        EvidenceBundle view = new EvidenceBundle(original.scope(), original.changedFiles(), markdown, original.coverage(),
                original.supplementBudget(), original.scopedKnowledge(), catalog);
        return new VerificationEvidence(view, new EvidenceIndex(catalog, available), available.sources().size(), false);
    }

    private static boolean relatedLanguage(List<LanguageModule> local, String path) {
        return local.isEmpty() || local.stream().anyMatch(module -> module.supports(path));
    }

    private static boolean explicitlyImported(String imports, String anchor, String path, String name) {
        if (imports.isEmpty() || !mentioned(imports, name)) return false;
        int directory = path.lastIndexOf('/') + 1;
        if (anchor.substring(0, anchor.lastIndexOf('/') + 1).equals(path.substring(0, directory))) return true;
        if (directory == 0) return false;
        int parent = path.lastIndexOf('/', directory - 2) + 1;
        String module = path.substring(parent, directory - 1);
        return mentioned(imports, module + "." + name) || mentioned(imports, module + "/" + name);
    }

    private static VerificationEvidence full(EvidenceBundle original, EvidenceCatalog catalog, String markdown) {
        var bundle = new EvidenceBundle(original.scope(), original.changedFiles(), markdown, original.coverage(),
                original.supplementBudget(), original.scopedKnowledge(), catalog);
        return new VerificationEvidence(bundle, new EvidenceIndex(catalog), catalog.sources().size(), true);
    }
    private static String stem(String path) {
        String name = filename(path);
        int dot = name.indexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static String filename(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }
    private static boolean peerTest(String path, String name, String own) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        return own.length() >= 4 && (lower.contains("test") || lower.contains("spec"))
                && name.toLowerCase(java.util.Locale.ROOT).contains(own.toLowerCase(java.util.Locale.ROOT));
    }
    static boolean mentioned(String text, String token) {
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
