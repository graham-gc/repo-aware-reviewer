package reviewagent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Give each independent reviewer the same complete change, in bounded partitions when needed. */
record DeepDiscovery(List<EvidenceBundle> batches, EvidenceBundle evidence, List<String> gaps, String coverage) {
    private static final int MAX_OPTIONAL_SOURCE_BYTES = 48_000;

    static DeepDiscovery plan(EvidenceBundle original, CliOptions options, GitClient git, PromptFactory prompts,
                              Set<String> requiredSources) throws IOException, InterruptedException {
        return plan(original, options, git, prompts, requiredSources,
                ModelPromptBudget.Limit.fallback(options.maxPromptBytes()).bytes());
    }

    static DeepDiscovery plan(EvidenceBundle original, CliOptions options, GitClient git, PromptFactory prompts,
                              Set<String> requiredSources, int ceiling) throws IOException, InterruptedException {
        int limit = Math.min(ceiling, options.maxEvidenceBytes());
        var focuses = DiscoveryEvidence.Focus.active(options);
        var sources = original.catalog().sources();
        Set<String> scopedPaths = options.knowledgeMode() == CliOptions.KnowledgeMode.SCOPED
                ? git.repositoryPaths(options.knowledge()) : Set.of();
        var complete = sources.stream().filter(DeepDiscovery::completeDiff).map(EvidenceSource::path).collect(Collectors.toSet());
        if (requiredSources.isEmpty() && complete.containsAll(original.changedFiles().stream()
                .filter(path -> !scopedPaths.contains(path)).toList()) && fits(original, prompts, focuses, limit)) {
            return new DeepDiscovery(List.of(original), original, List.of(), "");
        }
        if (!requiredSources.isEmpty() && EvidenceText.bytes(
                prompts.supplementalDiscovery(original, requiredSources)) <= limit) {
            return new DeepDiscovery(List.of(original), original, List.of(), "");
        }

        var required = sources.stream().filter(source ->
                source.role(EvidenceSource.Role.CONTRACT) || requiredSources.contains(source.id())).toList();
        var optional = sources.stream().filter(source -> !source.role(EvidenceSource.Role.CONTRACT)
                && !source.role(EvidenceSource.Role.DIFF) && !requiredSources.contains(source.id())).toList();
        String heading = "Pinned base " + original.scope().baseCommit() + ", target " + original.scope().targetCommit() + ".\n\n"
                + "Review only the changed paths whose diff is supplied in this request. Other blocks provide context or rules. "
                + "Request specific tracked source ranges when missing context could change a conclusion.\n\n";
        var fixed = bundle(original, original.changedFiles(), heading, required);
        // Reserve language guidance for any source we might pack before budgeting diff and context bytes.
        var budgetBasis = new EvidenceBundle(fixed.scope(), fixed.changedFiles(), fixed.markdown(), fixed.coverage(),
                fixed.supplementBudget(), fixed.scopedKnowledge(), original.catalog());
        int room = limit - promptBytes(budgetBasis, prompts, focuses, requiredSources);
        if (room <= 0) {
            return new DeepDiscovery(List.of(), original,
                    List.of("Required review material exceeds the per-request evidence budget."), "");
        }

        var changes = git.changes(original.scope()).stream().filter(change -> !scopedPaths.contains(change.path())
                && (change.previousPath() == null || !scopedPaths.contains(change.previousPath()))).toList();
        var partitions = new ArrayList<List<EvidenceSource>>();
        var gaps = new ArrayList<String>();
        var current = new ArrayList<EvidenceSource>();
        int used = 0;
        for (var change : changes) {
            var whole = diff(original, change, change.patch());
            int patchBytes = EvidenceText.bytes(whole.markdown());
            List<String> patches;
            if (patchBytes <= room) {
                patches = List.of(change.patch());
            } else {
                int overhead = patchBytes - EvidenceText.bytes(change.patch());
                patches = FocusedPatch.partition(change.patch(), room - overhead);
            }
            if (change.patch().isBlank() || patches.isEmpty()) {
                gaps.add(EvidenceText.oneLine(change.path()) + " — a diff line or Git metadata exceeds the request budget or is unavailable.");
                continue;
            }
            for (String patch : patches) {
                var source = patch.equals(change.patch()) ? whole.throughEndOfFile() : diff(original, change, patch);
                int bytes = EvidenceText.bytes(source.markdown());
                if (used + bytes > room && !current.isEmpty()) {
                    partitions.add(List.copyOf(current));
                    current.clear();
                    used = 0;
                }
                current.add(source);
                used += bytes;
            }
        }
        if (!current.isEmpty() || partitions.isEmpty() && gaps.isEmpty()) partitions.add(List.copyOf(current));

        var batches = new ArrayList<EvidenceBundle>();
        var all = new LinkedHashMap<String, EvidenceSource>();
        var changedPaths = Set.copyOf(original.changedFiles());
        var coverage = new StringBuilder("\n## Diff partition coverage\n\n")
                .append("- Maximum initial prompt bytes per request: ").append(limit).append(".\n");
        for (var partition : partitions) {
            List<String> paths = partition.stream().map(EvidenceSource::path).distinct().toList();
            var selected = new ArrayList<>(required);
            selected.addAll(partition);
            int remaining = room - partition.stream().mapToInt(source -> EvidenceText.bytes(source.markdown())).sum();
            for (var source : optional) {
                if (source.role(EvidenceSource.Role.TARGET) && changedPaths.contains(source.path())
                        && !paths.contains(source.path())) continue;
                int bytes = EvidenceText.bytes(source.markdown());
                if (bytes > MAX_OPTIONAL_SOURCE_BYTES || bytes > remaining) continue;
                selected.add(source);
                remaining -= bytes;
            }
            var batch = bundle(original, paths, heading, selected);
            if (promptBytes(batch, prompts, focuses, requiredSources) > limit) {
                gaps.add("Evidence for " + String.join(", ", paths.stream().map(EvidenceText::oneLine).toList())
                        + " exceeds the request budget after rendering.");
                continue;
            }
            batches.add(batch);
            selected.forEach(source -> all.putIfAbsent(source.id(), source));
            coverage.append("- Partition ").append(batches.size()).append(": ")
                    .append(partition.stream().map(source -> EvidenceText.oneLine(source.path())
                            + (source.endOfFile() ? " (full diff)" : " (" + FocusedPatch.ranges(source.text()) + ")"))
                            .collect(Collectors.joining(", "))).append(".\n");
        }
        var unshown = optional.stream().filter(source -> !all.containsKey(source.id())).map(EvidenceSource::title).distinct().toList();
        for (String title : unshown) coverage.append("- Context available on request: ").append(EvidenceText.oneLine(title)).append(".\n");
        for (String gap : gaps) coverage.append("- Unreviewed: ").append(gap).append("\n");
        return new DeepDiscovery(List.copyOf(batches),
                bundle(original, original.changedFiles(), heading, List.copyOf(all.values())), List.copyOf(gaps), coverage.toString());
    }

    private static EvidenceSource diff(EvidenceBundle original, GitClient.Change change, String patch) {
        return EvidenceSource.diff(change.path(), change.previousPath(), patch, original.scope(),
                EvidenceCatalog.implementation(LanguageRegistry.defaults(), change.path()));
    }

    private static boolean completeDiff(EvidenceSource source) {
        return source.role(EvidenceSource.Role.DIFF) && (source.endOfFile() || source.text().contains("\nBinary files ")
                || source.text().contains("\nGIT binary patch\n"));
    }

    private static EvidenceBundle bundle(EvidenceBundle original, List<String> paths, String heading, List<EvidenceSource> sources) {
        return new EvidenceBundle(original.scope(), paths,
                heading + sources.stream().map(EvidenceSource::markdown).collect(Collectors.joining()), original.coverage(),
                original.supplementBudget(), original.scopedKnowledge(), new EvidenceCatalog(sources, true));
    }

    private static boolean fits(EvidenceBundle evidence, PromptFactory prompts, List<DiscoveryEvidence.Focus> focuses, int limit) {
        return promptBytes(evidence, prompts, focuses, Set.of()) <= limit;
    }

    private static int promptBytes(EvidenceBundle evidence, PromptFactory prompts, List<DiscoveryEvidence.Focus> focuses,
                                   Set<String> requiredSources) {
        // Allow the longest active assignment to receive the full partition.
        int initial = focuses.stream().mapToInt(focus -> EvidenceText.bytes(
                prompts.independentDiscovery(evidence, focus, focus == DiscoveryEvidence.Focus.CORRECTNESS)))
                .max().orElseThrow();
        return Math.max(initial, EvidenceText.bytes(prompts.supplementalDiscovery(evidence, requiredSources)));
    }
}
