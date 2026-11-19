package reviewagent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class SupplementalEvidence {
    private static final int MAX_SOURCE_BYTES = 1_000_000;

    record Result(String markdown, String coverage, boolean hasMaterial, EvidenceCatalog catalog,
                  java.util.Map<String, Set<String>> resolvedSources) {
    }

    private record Excerpt(String markdown, String status, EvidenceCatalog catalog) {
        Excerpt(String markdown, String status) { this(markdown, status, EvidenceCatalog.empty()); }
        Excerpt(EvidenceSource source, String status) { this(source.markdown(), status, new EvidenceCatalog(List.of(source), true)); }
    }

    private final GitClient git;
    private final ProcessExecutor executor;
    private final LanguageRegistry languages;
    private final LanguageModule.Repository repository;
    private final java.util.Map<String, Path> caches;
    private final java.util.Map<String, LanguageModule.Session> sessions = new java.util.LinkedHashMap<>();

    SupplementalEvidence(GitClient git, ProcessExecutor executor, java.util.Map<String, Path> caches) {
        this(git, executor, caches, LanguageRegistry.defaults());
    }

    SupplementalEvidence(GitClient git, ProcessExecutor executor, java.util.Map<String, Path> caches, LanguageRegistry languages) {
        this.git = git;
        this.executor = executor;
        this.languages = languages;
        this.repository = LanguageRegistry.repository(git);
        this.caches = java.util.Map.copyOf(caches);
    }

    boolean requestOnly(EvidenceBundle evidence, String response) {
        List<String> lines = response.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
        if (lines.isEmpty() || !lines.equals(EvidenceRequest.parse(response).stream().map(EvidenceRequest::text).toList())) return false;
        for (String line : lines) {
            if (line.startsWith("REQUEST_KNOWLEDGE|")) {
                String[] fields = line.split("\\|", 3);
                if (evidence.scopedKnowledge() == null || fields.length != 3 || fields[1].isBlank() || fields[2].isBlank()) return false;
            } else {
                try { EvidenceQuery.parse(line, languages); }
                catch (IOException invalid) { return false; }
            }
        }
        return true;
    }

    Set<String> reusable(EvidenceBundle evidence, String request) {
        if (request.startsWith("REQUEST_KNOWLEDGE|") && evidence.scopedKnowledge() != null)
            return evidence.scopedKnowledge().knownSources(request, evidence.catalog(), true);
        try {
            return reusable(evidence, EvidenceQuery.parse(request, languages));
        } catch (IOException invalid) { return Set.of(); }
    }

    private Set<String> reusable(EvidenceBundle evidence, EvidenceQuery query) {
        if (!eligiblePath(query.resource()) || evidence.scopedKnowledge() != null && evidence.scopedKnowledge().isSourcePath(query.resource())) return Set.of();
        return FileEvidenceCache.covering(query, evidence.scope(), evidence.catalog());
    }

    Result collect(EvidenceBundle evidence, String candidates, int maxRequests, int maxKnowledgeQueries)
            throws InterruptedException {
        var requests = new LinkedHashMap<List<String>, EvidenceQuery>();
        var requestKeys = new LinkedHashMap<List<String>, String>();
        var invalidLines = new ArrayList<Integer>();
        List<EvidenceRequest> parsed = EvidenceRequest.parse(candidates);
        for (EvidenceRequest request : parsed) {
            String line = request.text();
            if (line.startsWith("REQUEST_KNOWLEDGE|")) continue;
            try {
                EvidenceQuery query = EvidenceQuery.parse(line, languages);
                requests.putIfAbsent(query.key(), query);
                requestKeys.putIfAbsent(query.key(), request.key());
            } catch (IOException invalid) {
                invalidLines.add(request.line());
            }
        }

        ScopedKnowledge.Result knowledge = evidence.scopedKnowledge() == null
                ? new ScopedKnowledge.Result("", "", 0)
                : evidence.scopedKnowledge().supplemental(parsed, evidence.supplementBudget() / 2, maxKnowledgeQueries);
        String heading = "\n## Supplemental evidence requested by reviewers\n\n";
        StringBuilder coverage = new StringBuilder(heading);
        StringBuilder prompt = new StringBuilder(heading);
        int remaining = evidence.supplementBudget() - EvidenceText.bytes(heading + knowledge.markdown()) - 220;
        int processed = 0;
        int total = maxRequests == 0 ? requests.size() : Math.min(requests.size(), maxRequests);
        int included = 0;
        boolean material = knowledge.suppliedSections() > 0;
        EvidenceCatalog catalog = knowledge.catalog();
        var resolved = new LinkedHashMap<String, Set<String>>();
        for (EvidenceRequest request : parsed) if (request.text().startsWith("REQUEST_KNOWLEDGE|")) {
            resolved.put(request.key(), evidence.scopedKnowledge() == null ? Set.of()
                    : evidence.scopedKnowledge().knownSources(request.text(), evidence.catalog().plus(knowledge.catalog()), false));
        }
        for (EvidenceQuery query : requests.values()) {
            if (processed == total) {
                break;
            }
            int quota = Math.min(remaining, Math.max(256, remaining / (total - processed++)));
            String revision = query.side().equals("base") ? evidence.scope().baseCommit() : evidence.scope().targetCommit();
            String label = EvidenceText.oneLine(query.label());
            String status;
            try {
                EvidenceCatalog available = evidence.catalog().plus(catalog);
                Set<String> cached = reusable(new EvidenceBundle(evidence.scope(), evidence.changedFiles(), "", "", 0,
                        evidence.scopedKnowledge(), available), query);
                if (query.command().equals("REQUEST_FILE") && !cached.isEmpty()) {
                    resolved.put(requestKeys.get(query.key()), cached);
                    included++;
                    coverage.append("- ").append(label).append(": REUSED (already collected file coverage).\n");
                    continue;
                }
                if (quota < 256) {
                    throw new IOException("No room in the supplemental evidence budget");
                }
                Excerpt excerpt;
                if (query.operation() == EvidenceQuery.Operation.FIND_FILES) {
                    excerpt = new Excerpt(new RepositoryBrowser(repository).source(revision, query.resource(), quota), "INDEX_ONLY");
                } else if (query.operation() == EvidenceQuery.Operation.READ_FILE) {
                    if (evidence.scopedKnowledge() != null && evidence.scopedKnowledge().isSourcePath(query.resource())) {
                        throw new IOException("Use a knowledge query for the scoped knowledge document");
                    }
                    excerpt = repositoryFile(evidence.scope(), revision, query.resource(), query.selector(), quota);
                } else {
                    LanguageModule module = languages.module(query.language());
                    var browser = new RepositoryBrowser(repository);
                    if (query.selector().equals("index") && !module.capabilities().contains(LanguageModule.Capability.FILE_INDEX)
                            && browser.directory(revision, query.resource())) {
                        excerpt = new Excerpt(browser.source(revision, query.resource(), quota), "INDEX_ONLY (repository navigation fallback)");
                    } else {
                        Path cache = caches.getOrDefault(module.id(), module.defaultCache());
                        LanguageModule.Session session = sessions.computeIfAbsent(module.id(), ignored ->
                                module.open(LanguageRegistry.environment(repository, executor, cache)));
                        LanguageModule.Evidence loaded = session.load(new LanguageModule.Request(revision, query.context(), query.resource(), query.selector()), quota);
                        excerpt = new Excerpt(loaded.markdown(), EvidenceText.oneLine(loaded.status()),
                                LegacyEvidenceAdapter.read(loaded.markdown(), evidence.scope(), languages));
                    }
                }
                if (excerpt.markdown().isBlank() || excerpt.status().isBlank()) throw new IOException("Language module returned empty evidence or coverage status");
                int bytes = EvidenceText.bytes(excerpt.markdown());
                if (bytes > quota) {
                    throw new IOException("Requested material exceeds the supplemental evidence budget");
                }
                prompt.append(excerpt.markdown());
                catalog = catalog.plus(excerpt.catalog());
                resolved.put(requestKeys.get(query.key()), excerpt.catalog().sources().stream().map(EvidenceSource::id)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()));
                material = true;
                remaining -= bytes;
                included++;
                status = excerpt.status();
            } catch (IOException unavailable) {
                status = "UNAVAILABLE (" + EvidenceText.oneLine(unavailable.getMessage()) + ")";
                String note = "- " + label + ": " + status + "\n";
                if (EvidenceText.bytes(note) <= quota) {
                    prompt.append(note);
                    remaining -= EvidenceText.bytes(note);
                }
            }
            coverage.append("- ").append(label).append(": ").append(status).append(". Reason: ")
                    .append(EvidenceText.oneLine(query.reason())).append('\n');
        }
        for (int line : invalidLines) {
            coverage.append("- Invalid file or language request on reviewer output line ").append(line).append(".\n");
        }
        String summary = "\nSupplemental requests included: " + included + "/" + processed + ". Invalid: " + invalidLines.size()
                + ". Skipped by request limit: " + (requests.size() - processed) + ". See `coverage.md` for details.\n";
        return new Result(prompt.append(summary).append(knowledge.markdown()).toString(),
                coverage.append(summary).append(knowledge.coverage()).toString(), material, catalog, java.util.Map.copyOf(resolved));
    }

    private Excerpt repositoryFile(ReviewScope scope, String revision, String path, String selector, int quota)
            throws IOException, InterruptedException {
        if (!eligiblePath(path)) {
            throw new IOException("Path is not eligible tracked source, test, schema, or documentation");
        }
        String title = path + " at " + revision;
        if (selector.equals("all")) {
            return new Excerpt(EvidenceSources.file(revision, path, 1, title, git.regularBlobAt(revision, path, quota), scope, languages).throughEndOfFile(), "INCLUDED");
        }
        int from;
        int to;
        String source;
        boolean bySymbol = selector.startsWith("symbol:");
        if (bySymbol) {
            source = git.regularBlobAt(revision, path, MAX_SOURCE_BYTES);
            LanguageModule.Lines lines = languages.forPath(path).locate(source, selector.substring("symbol:".length()));
            from = lines.first();
            to = lines.last();
        } else {
            if (!selector.matches("[1-9][0-9]{0,6}:[1-9][0-9]{0,6}")) {
                throw new IOException("Expected all, a line range such as 10:90, or a registered language's symbol selector");
            }
            String[] endpoints = selector.split(":");
            from = Integer.parseInt(endpoints[0]);
            to = Integer.parseInt(endpoints[1]);
            if (to < from || to - from >= 200) {
                throw new IOException("Requested range must contain between 1 and 200 lines");
            }
            source = git.regularBlobAt(revision, path, MAX_SOURCE_BYTES);
        }
        if (bySymbol && to - from >= 200) {
            throw new IOException("Method spans more than 200 lines; request the relevant line range instead");
        }
        List<String> lines = source.lines().skip(from - 1L).limit(to - from + 1L).toList();
        if (lines.isEmpty()) {
            throw new IOException("Requested range is beyond the end of the file");
        }
        String location = "lines " + from + "–" + (from + lines.size() - 1);
        EvidenceSource sourceBlock = EvidenceSources.file(revision, path, from, title + " (" + location + ")",
                String.join("\n", lines), scope, languages);
        if (from + lines.size() - 1 == source.lines().count()) sourceBlock = sourceBlock.throughEndOfFile();
        return new Excerpt(sourceBlock, bySymbol ? "INCLUDED (" + location + ")" : "INCLUDED");
    }

    private boolean eligiblePath(String path) {
        return RepositoryPathPolicy.allowed(path) && languages.recognizesText(path);
    }

}
