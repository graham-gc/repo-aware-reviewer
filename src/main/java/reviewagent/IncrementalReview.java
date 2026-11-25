package reviewagent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Across Git revisions, only bounded checks with an equivalent input and dependency set are reusable. */
final class IncrementalReview {
    private static final String FORMAT = "1";
    private static final String DIRECTORY = "incremental-review";
    private static final String MANIFEST = "scope.txt";
    private static final Pattern DECLARATION = Pattern.compile(
            "(?m)\\b(?:def|func|function|class|interface|struct|enum|trait|type|const)\\h+([A-Za-z_$][A-Za-z0-9_$]*)"
                    + "|^\\h*(?:(?:public|protected|private|static|final|async|export)\\h+)+"
                    + "(?:[A-Za-z_$][A-Za-z0-9_$.<>\\[\\]]*\\h+)?([A-Za-z_$][A-Za-z0-9_$]*)\\h*\\(");
    private static final Pattern GENERATED = Pattern.compile("EV-[0-9a-f]{24}|[0-9a-f]{64}|[0-9a-f]{40}");

    record Unit(String path, String id, String dependencies, EvidenceBundle view) { }
    record Plan(List<Unit> units, String coverage) { }

    private final Path output;
    private final Path previous;
    private final String settings;
    private final ReviewScope scope;
    private final String previousHead;
    private final String history;
    private final Map<String, Unit> units = new LinkedHashMap<>();
    private boolean reuseUnavailable;

    IncrementalReview(CliOptions options, ReviewScope scope, Path run) throws IOException {
        this.scope = scope;
        output = Files.createDirectories(run.resolve(DIRECTORY));
        String identity = digest(options.repo().toRealPath() + "\n" + scope.baseCommit());
        String prefix = FORMAT + "\n" + identity + "\n";
        Path older = options.previousRun() == null ? null : options.previousRun().resolve(DIRECTORY);
        var previousScope = older == null ? null : older.resolve(MANIFEST);
        if (previousScope != null && Files.isRegularFile(previousScope, LinkOption.NOFOLLOW_LINKS)) {
            String saved = Files.readString(previousScope);
            String head = saved.startsWith(prefix) ? saved.substring(prefix.length()).strip() : "";
            boolean compatible = head.matches("[0-9a-f]{40}|[0-9a-f]{64}");
            previous = compatible ? older : null;
            previousHead = compatible ? head : "";
            history = compatible ? "Comparing saved target `" + head + "` with this target."
                    : "The previous record has a different repository, baseline or cache format.";
        } else {
            previous = null;
            previousHead = "";
            history = older == null ? "Recording checks for future reviews." : "The previous run had no incremental records.";
        }
        AtomicFiles.write(output.resolve(MANIFEST), prefix + scope.targetCommit() + "\n");
        settings = digest(List.of(options.agentCommand(), options.agentArgs(), options.discoveryModels(), options.knowledgeMode(),
                route("ANTHROPIC_BASE_URL"), route("ANTHROPIC_MODEL"), route("ANTHROPIC_DEFAULT_SONNET_MODEL"),
                route("ANTHROPIC_DEFAULT_OPUS_MODEL"), route("ANTHROPIC_DEFAULT_HAIKU_MODEL")).toString());
    }

    Plan plan(GitClient git, DeepDiscovery review, PromptFactory prompts, CliOptions options, int limit)
            throws IOException, InterruptedException {
        var evidence = review.evidence();
        if (!review.gaps().isEmpty() || evidence.scopedKnowledge() != null)
            return fallback("The diff or scoped knowledge required shared review planning.");

        Map<String, GitClient.TrackedFile> target = tree(git, scope.targetCommit());
        Map<String, GitClient.TrackedFile> base = tree(git, scope.baseCommit());
        Set<String> delta = new LinkedHashSet<>();
        boolean previousAvailable = false;
        if (!previousHead.isEmpty()) {
            try {
                var older = tree(git, previousHead);
                delta.addAll(older.keySet());
                delta.addAll(target.keySet());
                delta.removeIf(path -> java.util.Objects.equals(older.get(path), target.get(path)));
                previousAvailable = true;
            } catch (IOException unavailable) {
                // A shallow checkout may have lost the old head. Review the current scope in that case.
            }
        }

        var sources = evidence.catalog().sources();
        var rules = sources.stream().filter(source -> source.role(EvidenceSource.Role.CONTRACT)).toList();
        var shared = target.values().stream().filter(file -> !sourceFile(file) || controlFile(file.path()))
                .map(IncrementalReview::fileIdentity).sorted().collect(Collectors.joining("\n"));
        String model = options.discoveryModels().isEmpty() ? null : options.discoveryModels().getFirst();
        for (String path : evidence.changedFiles()) {
            var diff = sources.stream().filter(source -> source.role(EvidenceSource.Role.DIFF)
                    && source.path().equals(path) && source.endOfFile()).findFirst().orElse(null);
            var file = target.get(path);
            if (diff == null || file == null || !sourceFile(file) || controlFile(path) || file.size() > limit
                    || !diff.baselinePath().equals(path)) continue;

            var local = new ArrayList<EvidenceSource>(rules);
            local.add(diff);
            try {
                local.add(wholeFile(git, file, scope.targetCommit(), limit));
                var original = base.get(path);
                if (original != null) local.add(wholeFile(git, original, scope.baseCommit(), limit));
            } catch (IOException unavailable) {
                continue;
            }
            var view = new EvidenceBundle(scope, List.of(path), "This task covers the assigned changed file. "
                    + "Independent cross-file reviewers cover interactions with other changes. Request any necessary callers or sources.\n\n"
                    + local.stream().map(EvidenceSource::markdown).collect(Collectors.joining()),
                    evidence.coverage(), evidence.supplementBudget(), null, new EvidenceCatalog(local, true));
            String prompt = prompts.independentDiscovery(view, DiscoveryEvidence.Focus.CORRECTNESS, false);
            if (EvidenceText.bytes(prompt) > limit) continue;

            String parent = path.substring(0, path.lastIndexOf('/') + 1);
            var names = declarationNames(path, local);
            var dependencies = new LinkedHashSet<String>();
            if (!parent.isEmpty()) target.keySet().stream().filter(candidate -> candidate.startsWith(parent)).forEach(dependencies::add);
            String ownCode = local.stream().filter(source -> source.path().equals(path) && !source.role(EvidenceSource.Role.DIFF))
                    .map(EvidenceSource::text).collect(Collectors.joining("\n"));
            target.values().stream().filter(IncrementalReview::sourceFile)
                    .filter(candidate -> VerificationEvidence.mentioned(ownCode, stem(candidate.path())))
                    .map(GitClient.TrackedFile::path).forEach(dependencies::add);
            // Literal matches conservatively invalidate a same-name consumer, including a newly added one.
            // Bound command size by using the whole target tree for unusually large declaration sets.
            if (names.stream().mapToInt(EvidenceText::bytes).sum() + names.size() * 4 > limit / 8) {
                dependencies.addAll(target.keySet());
            } else {
                try {
                    dependencies.addAll(git.checkedReferences(scope.targetCommit(), List.copyOf(names)));
                } catch (IOException unavailable) {
                    dependencies.addAll(target.keySet());
                }
            }
            String fingerprint = digest(shared + "\n" + dependencies.stream().map(target::get)
                    .filter(java.util.Objects::nonNull).map(IncrementalReview::fileIdentity).sorted().collect(Collectors.joining("\n")));
            String id = "R1-P" + Long.toUnsignedString(Long.parseUnsignedLong(digest(path).substring(0, 16), 16));
            if (units.values().stream().anyMatch(unit -> unit.id().equals(id))) return fallback("File check identifiers collide.");
            units.put(path, new Unit(path, id, fingerprint, view));
        }
        if (units.isEmpty()) return fallback("No changed path admits a complete file-local check.");
        String coverage = heading() + (previousAvailable
                ? "- Paths changed since the previous head: " + delta.size() + ".\n"
                : previous == null ? "" : "- The previous Git target is unavailable. These checks run fresh.\n")
                + "- File-local correctness checks: " + units.size() + ". Cross-file discovery runs on the current cumulative diff.\n";
        if (!previousAvailable && previous != null) reuseUnavailable = true;
        return new Plan(List.copyOf(units.values()), coverage);
    }

    Entry entry(Unit unit, String task, String prompt, String model, EvidenceCatalog supplied, boolean verification) {
        var currentTokens = new HashMap<String, String>();
        currentTokens.put(scope.targetCommit(), "@review-target@");
        for (var source : supplied.sources()) {
            String content = digest(source.path() + "\0" + source.firstLine() + "\0" + source.roles().stream().sorted().toList()
                    + "\0" + currentTokens.getOrDefault(source.revision(), source.revision())
                    + "\0" + source.baselineRevision() + "\0" + source.baselinePath()
                    + "\0" + source.title().replace(scope.targetCommit(), "@review-target@") + "\0" + source.text());
            currentTokens.put(source.id(), "@review-source-" + content + "@");
        }
        String normalized = normalize(prompt, currentTokens);
        String key = digest(settings + "\n" + scope.baseCommit() + "\n" + unit.path() + "\n" + unit.dependencies()
                + "\n" + task + "\n" + model + "\n" + normalized);
        return new Entry(output, reuseUnavailable ? null : previous, key, Map.copyOf(currentTokens), verification);
    }

    Unit unit(ReviewFinding finding) {
        var unit = units.get(finding.path());
        return unit != null && finding.id().startsWith(unit.id() + "-") ? unit : null;
    }

    record Entry(Path output, Path previous, String key, Map<String, String> tokens, boolean verification) {
        String read() throws IOException {
            if (previous == null) return null;
            Path response = previous.resolve(key + ".response");
            if (!Files.isRegularFile(response, LinkOption.NOFOLLOW_LINKS)) return null;
            String reply = Files.readString(response);
            for (var token : tokens.entrySet()) reply = reply.replace(token.getValue(), token.getKey());
            return reply;
        }

        void save(String reply) throws IOException {
            if (!EvidenceRequest.parse(reply).isEmpty() || reply.contains(ReviewOutput.Discovery.NEEDS_EVIDENCE_PREFIX)
                    || verification && (!reply.matches("(?s).*<status>\\s*(?:CONFIRMED|REJECTED)\\s*</status>.*")
                    || reply.contains("<nextCheck"))) return;
            AtomicFiles.write(output.resolve(key + ".response"), normalize(reply, tokens));
        }
    }

    Plan fallback(String reason) {
        units.clear();
        return new Plan(List.of(), heading() + "- Shared discovery used. " + reason + "\n");
    }

    static String fileCoverage(List<Unit> units, List<InitialDiscovery.Result> responses, Path directory) {
        var byId = responses.stream().collect(Collectors.toMap(InitialDiscovery.Result::id, result -> result));
        StringBuilder text = new StringBuilder("\n### File-local check coverage\n\n");
        for (var unit : units) {
            var status = byId.get(unit.id());
            boolean reused = Files.isRegularFile(directory.resolve(unit.id() + "-incremental.txt"));
            text.append("- ").append(EvidenceText.oneLine(unit.path())).append(" — ")
                    .append(!status.usable() || !status.gap().isEmpty() ? "INCOMPLETE" : reused ? "REUSED" : "REVIEWED")
                    .append(" (").append(unit.id()).append(").\n");
        }
        return text.toString();
    }

    private String heading() { return "\n## Incremental review\n\n- " + history + "\n"; }

    private EvidenceSource wholeFile(GitClient git, GitClient.TrackedFile file, String revision, int limit)
            throws IOException, InterruptedException {
        if (!file.regular()) throw new IOException("Nonregular Git file");
        return EvidenceSources.file(revision, file.path(), 1, file.path() + " at " + revision,
                git.regularBlobAt(revision, file.path(), limit), scope, LanguageRegistry.defaults()).throughEndOfFile();
    }

    private static Set<String> declarationNames(String path, List<EvidenceSource> sources) {
        var names = new LinkedHashSet<String>();
        names.add(stem(path));
        for (var source : sources) {
            if (!source.path().equals(path) || source.role(EvidenceSource.Role.DIFF)) continue;
            var matches = DECLARATION.matcher(source.text());
            while (matches.find()) names.add(matches.group(1) == null ? matches.group(2) : matches.group(1));
        }
        return names;
    }

    private static String stem(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.substring(0, name.lastIndexOf('.'));
    }

    private static boolean sourceFile(GitClient.TrackedFile file) {
        if (!file.regular() || !RepositoryPathPolicy.allowed(file.path())) return false;
        try {
            var language = LanguageRegistry.defaults().forPath(file.path());
            return !language.id().equals("configuration") && language.implementationEvidence(file.path())
                    && file.path().substring(file.path().lastIndexOf('/') + 1).lastIndexOf('.') > 0;
        } catch (IOException unsupported) {
            return false;
        }
    }

    private static boolean controlFile(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT);
        return name.startsWith("config.") || name.endsWith(".config." + name.substring(name.lastIndexOf('.') + 1))
                || Set.of("__init__.py", "settings.py", "setup.py", "conftest.py", "module-info.java").contains(name);
    }

    private static Map<String, GitClient.TrackedFile> tree(GitClient git, String revision) throws IOException, InterruptedException {
        return git.trackedFiles(revision).stream().sorted(Comparator.comparing(GitClient.TrackedFile::path))
                .collect(Collectors.toMap(GitClient.TrackedFile::path, file -> file, (left, right) -> left, LinkedHashMap::new));
    }

    private static String normalize(String text, Map<String, String> tokens) {
        return GENERATED.matcher(text).replaceAll(match ->
                java.util.regex.Matcher.quoteReplacement(tokens.getOrDefault(match.group(), match.group())));
    }

    private static String fileIdentity(GitClient.TrackedFile file) {
        return file.path() + "\0" + file.mode() + "\0" + file.objectId() + "\0";
    }

    private static String route(String variable) { return System.getenv().getOrDefault(variable, ""); }

    private static String digest(String text) { return InputDocument.sha256(text.getBytes(StandardCharsets.UTF_8)); }
}
