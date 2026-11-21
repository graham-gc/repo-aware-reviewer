package reviewagent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class SupplementalEvidence {
    private static final int MAX_SOURCE_BYTES = 1_000_000;
    private static final int MAX_REFERENCE_FILES = 12;
    private static final int MAX_REFERENCE_EXCERPTS = 6;
    private static final int REFERENCE_CONTEXT = 10;

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
        var covering = FileEvidenceCache.covering(query, evidence.scope(), evidence.catalog());
        return covering.isEmpty() && !query.fallback().isEmpty()
                ? FileEvidenceCache.covering(query.fallbackQuery(), evidence.scope(), evidence.catalog()) : covering;
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
        int total = maxRequests == 0 ? requests.size() : Math.min(requests.size(), maxRequests);
        var pending = new ArrayList<>(requests.values().stream().limit(total).toList());
        int included = 0;
        boolean material = knowledge.suppliedSections() > 0;
        EvidenceCatalog catalog = knowledge.catalog();
        var resolved = new LinkedHashMap<String, Set<String>>();
        for (EvidenceRequest request : parsed) if (request.text().startsWith("REQUEST_KNOWLEDGE|")) {
            resolved.put(request.key(), evidence.scopedKnowledge() == null ? Set.of()
                    : evidence.scopedKnowledge().knownSources(request.text(), evidence.catalog().plus(knowledge.catalog()), false));
        }
        for (int i = 0; i < pending.size(); i++) {
            var query = pending.get(i);
            boolean deferred = i >= total;
            int quota = deferred ? remaining : Math.min(remaining, Math.max(256, remaining / (total - i)));
            boolean canDefer = !deferred && quota < remaining && query.operation() == EvidenceQuery.Operation.READ_FILE
                    && (query.selector().equals("all") || !query.fallback().isEmpty());
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
                } else if (query.operation() == EvidenceQuery.Operation.FIND_REFERENCES) {
                    excerpt = references(evidence, revision, query, quota);
                } else if (query.operation() == EvidenceQuery.Operation.READ_FILE) {
                    if (evidence.scopedKnowledge() != null && evidence.scopedKnowledge().isSourcePath(query.resource())) {
                        throw new IOException("Use a knowledge query for the scoped knowledge document");
                    }
                    excerpt = repositoryFile(evidence.scope(), revision, query, quota);
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
                    if (canDefer) {
                        pending.add(query);
                        continue;
                    }
                    throw new IOException("Requested material exceeds the supplemental evidence budget");
                }
                prompt.append(excerpt.markdown());
                catalog = catalog.plus(excerpt.catalog());
                resolved.put(requestKeys.get(query.key()), excerpt.catalog().sources().stream().map(EvidenceSource::id)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()));
                material = true;
                remaining -= bytes;
                included++;
                status = excerpt.status() + (deferred ? " (used remaining batch budget)" : "");
            } catch (IOException unavailable) {
                // Give every admitted request its initial share before spending unclaimed room on whole files.
                if (canDefer && unavailable instanceof GitClient.BlobTooLargeException) {
                    pending.add(query);
                    continue;
                }
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
        String summary = "\nSupplemental requests included: " + included + "/" + total + ". Invalid: " + invalidLines.size()
                + ". Skipped by request limit: " + (requests.size() - total) + ". See `coverage.md` for details.\n";
        return new Result(prompt.append(summary).append(knowledge.markdown()).toString(),
                coverage.append(summary).append(knowledge.coverage()).toString(), material, catalog, java.util.Map.copyOf(resolved));
    }

    private Excerpt references(EvidenceBundle evidence, String revision, EvidenceQuery query, int quota)
            throws IOException, InterruptedException {
        var inScope = RepositoryBrowser.pathsMatching(query.resource());
        String reference = query.reference();
        boolean construction = reference.startsWith("new ");
        String needle = construction ? reference.substring("new ".length()) : reference;
        var exact = Pattern.compile("(?<![\\p{L}\\p{N}_$-])" + (construction ? "new\\s+" : "")
                + Pattern.quote(needle) + (construction ? "(?=\\s*[<(])" : "(?![\\p{L}\\p{N}_$-])"));
        var matches = git.filesMatching(revision, List.of(needle), RepositoryBrowser.searchPathspec(query.resource())).stream()
                .filter(path -> path.equals(query.resource()) || inScope.test(path))
                .filter(this::eligiblePath)
                .filter(path -> evidence.scopedKnowledge() == null || !evidence.scopedKnowledge().isSourcePath(path))
                .sorted().toList();
        var sources = new ArrayList<EvidenceSource>();
        var blocks = new StringBuilder();
        boolean limited = matches.size() > MAX_REFERENCE_FILES;
        var excerptsByFile = new ArrayList<List<EvidenceSource>>();
        for (String path : matches.stream().limit(MAX_REFERENCE_FILES).toList()) {
            List<String> lines;
            try { lines = git.regularBlobAt(revision, path, MAX_SOURCE_BYTES).lines().toList(); }
            catch (IOException unavailable) { limited = true; continue; }
            var excerpts = new ArrayList<EvidenceSource>();
            for (int line = 0; line < lines.size(); line++) {
                if (!exact.matcher(lines.get(line)).find()) continue;
                if (excerpts.size() >= MAX_REFERENCE_EXCERPTS) {
                    limited = true;
                    break;
                }
                int first = Math.max(1, line + 1 - REFERENCE_CONTEXT);
                int last = Math.min(lines.size(), line + 1 + REFERENCE_CONTEXT);
                excerpts.add(fileExcerpt(evidence.scope(), revision, path, lines, first, last));
                line = last - 1;
            }
            if (!excerpts.isEmpty()) excerptsByFile.add(excerpts);
        }
        // An occurrence in a different file can establish a caller or guard that more hits in one file cannot.
        for (int i = 0; i < MAX_REFERENCE_EXCERPTS; i++) {
            for (var excerpts : excerptsByFile) {
                if (i >= excerpts.size()) continue;
                var source = excerpts.get(i);
                String block = source.markdown();
                int bytes = EvidenceText.bytes(block);
                if (sources.size() >= MAX_REFERENCE_EXCERPTS || bytes > quota) {
                    limited = true;
                    continue;
                }
                sources.add(source);
                blocks.append(block);
                quota -= bytes;
            }
        }
        if (sources.isEmpty()) throw new IOException("No readable pinned excerpts matched the literal reference within budget");
        return new Excerpt(blocks.toString(), "INCLUDED (literal reference excerpts; verify binding"
                + "; " + sources.stream().map(EvidenceSource::path).distinct().count() + " files"
                + (limited ? "; partial search coverage" : "") + ")", new EvidenceCatalog(sources, true));
    }

    private Excerpt repositoryFile(ReviewScope scope, String revision, EvidenceQuery query, int quota)
            throws IOException, InterruptedException {
        if (query.fallback().isEmpty()) return repositoryFile(scope, revision, query.resource(), query.selector(), quota);
        try {
            var requested = repositoryFile(scope, revision, query.resource(), query.selector(), quota);
            if (EvidenceText.bytes(requested.markdown()) <= quota) return requested;
        } catch (IOException unavailable) {
            // The request explicitly names a smaller location that can answer the same question.
        }
        var fallback = repositoryFile(scope, revision, query.resource(), query.fallback(), quota);
        return new Excerpt(fallback.markdown(), "INCLUDED (fallback " + query.fallback() + ")", fallback.catalog());
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
            if (!EvidenceQuery.LINES.matcher(selector).matches()) {
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
        List<String> lines = source.lines().toList();
        if (from > lines.size()) {
            throw new IOException("Requested range is beyond the end of the file");
        }
        int last = Math.min(to, lines.size());
        String location = "lines " + from + "–" + last;
        var sourceBlock = fileExcerpt(scope, revision, path, lines, from, last);
        return new Excerpt(sourceBlock, bySymbol ? "INCLUDED (" + location + ")" : "INCLUDED");
    }

    private EvidenceSource fileExcerpt(ReviewScope scope, String revision, String path, List<String> lines, int first, int last) {
        var source = EvidenceSources.file(revision, path, first, path + " at " + revision + " (lines " + first + "–" + last + ")",
                String.join("\n", lines.subList(first - 1, last)), scope, languages);
        return last == lines.size() ? source.throughEndOfFile() : source;
    }

    private boolean eligiblePath(String path) {
        return RepositoryPathPolicy.allowed(path) && languages.recognizesText(path);
    }

}
