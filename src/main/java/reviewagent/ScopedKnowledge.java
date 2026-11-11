package reviewagent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class ScopedKnowledge {
    private static final int MAX_BYTES_PER_PASS = 24_000;
    private static final int MAX_MATCHES_PER_QUERY = 3;
    private static final String REQUEST = "REQUEST_KNOWLEDGE|";
    private static final Pattern LINE = Pattern.compile("[^\\r\\n]*(?:\\r\\n|\\r|\\n|$)");
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(\\x60{3,}|~{3,})(.*)$");
    private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})[ \\t]+(.+?)(?:[ \\t]+#+)?$");
    private static final Pattern UNDERLINE = Pattern.compile("^ {0,3}(=+|-+)$");
    private static final Pattern SETEXT_TITLE = Pattern.compile("^ {0,3}(?![>#+*~-]|\\d+[.)]\\s)\\S.*$");
    private static final Pattern CODE_SYMBOL = Pattern.compile(
            "\\b[A-Za-z][A-Za-z0-9]*(?:[a-z0-9][A-Z]|_[A-Za-z0-9])[A-Za-z0-9_]*\\b");
    private static final Pattern ENDPOINT = Pattern.compile(
            "(?<![\\p{L}\\p{N}_./:#-])/(?:[A-Za-z0-9_{}:-]+/)*[A-Za-z0-9_{}:-]+(?![\\p{L}\\p{N}_./-])");
    private static final Pattern PROSE_FILE = Pattern.compile("(?i).+\\.(?:md|mdx|markdown|rst|adoc|txt)$");
    private static final Pattern TERM = Pattern.compile("\\p{IsHan}+|[\\p{L}\\p{N}_$&&[^\\p{IsHan}]]+");
    private static final Set<String> GLOBAL_TITLES = Set.of("global", "global rules", "cross-cutting rules",
            "全局规则", "全局业务规则", "全局约束", "通用规则");
    private static final Set<String> GENERIC_FILE_STEMS = Set.of("main", "index", "test", "build", "settings");
    private static final Set<String> STOP_TERMS = Set.of("and", "are", "for", "the", "that", "this",
            "from", "into", "with", "when", "must", "should", "change", "changes", "behavior", "rule",
            "rules", "业务", "规则", "变更", "适用", "必须", "需要", "以及", "是否");
    private static final Set<String> GENERIC_TERMS = Set.of("run", "running", "status", "request", "response", "data",
            "result", "运行", "状态", "请求", "响应", "数据", "结果", "流程", "通过");

    record Result(String markdown, String coverage, int suppliedSections) {
    }

    private record Heading(int level, String title, int line, int offset) {
    }

    private record Section(String id, String title, int firstLine, int lastLine, String original,
                           Set<String> terms, Set<String> titleTerms, Set<String> endpoints, Set<String> identifiers) {
        String label() {
            return id + " — " + EvidenceText.oneLine(title) + " (lines " + firstLine + "–" + lastLine + ")";
        }
    }

    private record Match(Section section, int score, String reason) {
    }

    private record Lookup(String query, String reason, List<Match> matches) {
    }

    private final InputDocument document;
    private final Set<String> sourcePaths;
    private final List<Section> sections;
    private final Map<String, Integer> termFrequency = new HashMap<>();
    private final Set<String> supplied = new HashSet<>();

    ScopedKnowledge(InputDocument document, Set<String> sourcePaths) {
        this.document = document;
        this.sourcePaths = Set.copyOf(sourcePaths);
        this.sections = sections(document.content());
        for (Section section : sections) {
            section.terms().forEach(term -> termFrequency.merge(term, 1, Integer::sum));
        }
    }

    boolean isSourcePath(String path) {
        return sourcePaths.contains(path);
    }

    boolean containsHanText() {
        return document.content().codePoints()
                .anyMatch(value -> Character.UnicodeScript.of(value) == Character.UnicodeScript.HAN);
    }

    Result initial(List<GitClient.Change> changes, int budget) {
        var matches = new LinkedHashMap<String, Match>();
        for (Section section : sections) {
            if (GLOBAL_TITLES.contains(normalize(section.title()))) {
                matches.put(section.id(), new Match(section, 40, "explicit global heading"));
            }
        }
        StringBuilder coverage = new StringBuilder("\n## Scoped knowledge — initial selection\n\n")
                .append("Indexed sections: ").append(sections.size()).append(".\n\n");
        for (GitClient.Change change : changes) {
            Pattern paths = changedPaths(change);
            Set<String> symbols = new LinkedHashSet<>();
            Set<String> endpoints = Set.of();
            boolean prose = PROSE_FILE.matcher(change.path()).matches();
            if (!prose) {
                String edited = changedLines(change.patch());
                endpoints = matches(ENDPOINT, edited);
                addFileStem(symbols, change.path());
                if (change.previousPath() != null) {
                    addFileStem(symbols, change.previousPath());
                }
                CODE_SYMBOL.matcher(edited).results().map(match -> normalize(match.group()))
                        .distinct().limit(128).forEach(symbols::add);
            }
            int matched = 0;
            for (Section section : sections) {
                var path = paths.matcher(section.original());
                Match hit;
                if (path.find()) {
                    hit = new Match(section, prose ? 15 : 30, "changed path " + EvidenceText.oneLine(path.group(1)));
                } else {
                    String endpoint = endpoints.stream().filter(section.endpoints()::contains).sorted().findFirst().orElse(null);
                    if (endpoint != null) {
                        hit = new Match(section, 20, "changed endpoint " + endpoint);
                    } else {
                        String symbol = symbols.stream().filter(section.terms()::contains)
                                .filter(term -> weight(term) > 0).max(Comparator.comparingInt(this::weight)).orElse(null);
                        if (symbol == null) {
                            continue;
                        }
                        hit = new Match(section, 10 + weight(symbol), "changed identifier " + symbol);
                    }
                }
                matches.merge(section.id(), hit, (first, second) -> first.score() >= second.score() ? first : second);
                matched++;
            }
            coverage.append("- ").append(EvidenceText.oneLine(change.path())).append(": ");
            if (matched == 0) {
                coverage.append("NO DIRECT MATCH. Behavior queries can still find relevant rules.\n");
            } else {
                coverage.append(matched).append(" direct section match(es) before budget limits.\n");
            }
        }
        String heading = "## Human-maintained knowledge base (scoped)\n\n- SHA-256: " + document.sha256() + "\n\n";
        Selection selection = new Selection(heading, budget);
        for (Match match : matches.values().stream().sorted(Comparator.comparingInt(Match::score).reversed()).toList()) {
            String status = selection.offer(match.section());
            coverage.append("- ").append(match.section().label()).append(": ").append(status)
                    .append(". Match: ").append(match.reason()).append(".\n");
        }
        String summary = "\nInitial rule excerpts supplied: " + selection.count
                + ". Review coverage is limited to supplied excerpts.\n\n";
        return new Result(selection.markdown(summary), coverage.append(summary).toString(), selection.count);
    }

    Result supplemental(List<EvidenceRequest> requests, int budget, int maxQueries) {
        var queries = new LinkedHashMap<String, String>();
        var invalidLines = new ArrayList<Integer>();
        for (EvidenceRequest parsed : requests) {
            String request = parsed.text();
            if (!request.startsWith(REQUEST)) {
                continue;
            }
            String[] fields = request.split("\\|", 3);
            if (fields.length != 3 || fields[1].isBlank() || fields[1].length() > 160
                    || fields[2].isBlank() || request.length() > 700) {
                invalidLines.add(parsed.line());
                continue;
            }
            queries.putIfAbsent(normalize(fields[1]), fields[2].strip());
        }
        String heading = "\n## Scoped knowledge — behavior queries\n\n";
        Selection selection = new Selection(heading, budget);
        StringBuilder coverage = new StringBuilder(heading);
        StringBuilder notes = new StringBuilder("\n### Knowledge lookup outcomes\n\n");
        List<Lookup> lookups = queries.entrySet().stream().limit(maxQueries == 0 ? Long.MAX_VALUE : maxQueries)
                .map(query -> new Lookup(query.getKey(), query.getValue(), search(query.getKey()))).toList();
        var outcomes = new LinkedHashMap<String, String>();
        // Give each behavior a chance to supply its best match before loading alternatives.
        for (int rank = 0; rank < MAX_MATCHES_PER_QUERY; rank++) {
            for (Lookup lookup : lookups) {
                if (rank < lookup.matches().size()) {
                    Section section = lookup.matches().get(rank).section();
                    outcomes.computeIfAbsent(section.id(), id -> selection.offer(section));
                }
            }
        }
        for (Lookup lookup : lookups) {
            String label = EvidenceText.oneLine(lookup.query());
            String reason = EvidenceText.oneLine(lookup.reason());
            if (lookup.matches().isEmpty()) {
                String note = "- " + label + ": NO MATCH. Business rule coverage insufficient.";
                notes.append(note).append('\n');
                coverage.append(note).append(" Reason: ").append(reason).append(".\n");
                continue;
            }
            for (Match match : lookup.matches()) {
                String note = "- " + label + " → " + match.section().label() + ": " + outcomes.get(match.section().id()) + ".";
                notes.append(note).append('\n');
                coverage.append(note).append(" Match: ").append(match.reason()).append(". Reason: ").append(reason).append(".\n");
            }
        }
        long uncovered = lookups.stream().filter(lookup -> lookup.matches().stream()
                .noneMatch(match -> supplied.contains(match.section().id()))).count();
        for (int line : invalidLines) {
            coverage.append("- Invalid knowledge request on discovery output line ").append(line).append(".\n");
        }
        String summary = "\nBehavior queries processed: " + lookups.size() + ". Without supplied rules: " + uncovered
                + ". Invalid: " + invalidLines.size()
                + ". Skipped by query limit: " + (queries.size() - lookups.size())
                + ". Supplemental rule excerpts supplied: " + selection.count + ".\n";
        if (lookups.isEmpty()) {
            summary += "No usable behavior queries. Rule coverage is limited to initial matches.\n";
        }
        return new Result(selection.markdown(summary + notes), coverage.append(summary).toString(), selection.count);
    }

    private List<Match> search(String query) {
        Set<String> queryTerms = terms(query);
        Set<String> endpoints = matches(ENDPOINT, query);
        long meaningfulTerms = queryTerms.stream().filter(term -> weight(term) > 0).count();
        boolean byId = query.matches("kb-[0-9]+");
        var matches = new ArrayList<Match>();
        for (Section section : sections) {
            if (byId) {
                if (section.id().equalsIgnoreCase(query)) {
                    return List.of(new Match(section, 1, "section ID"));
                }
                continue;
            }
            var overlap = queryTerms.stream().filter(section.terms()::contains).filter(term -> weight(term) > 0)
                    .sorted(Comparator.comparingInt(this::weight).reversed().thenComparing(Comparator.naturalOrder())).toList();
            String endpoint = endpoints.stream().filter(section.endpoints()::contains).sorted().findFirst().orElse(null);
            String identifier = overlap.stream().filter(section.identifiers()::contains).findFirst().orElse(null);
            if (endpoint == null && identifier == null
                    && (overlap.isEmpty() || overlap.size() < Math.min(2, meaningfulTerms))) {
                continue;
            }
            int score = overlap.stream().mapToInt(term -> weight(term) * (section.titleTerms().contains(term) ? 2 : 1)).sum();
            if (queryTerms.stream().anyMatch(term -> GENERIC_TERMS.contains(term) && section.terms().contains(term))) {
                score++;
            }
            String reason = String.join(", ", overlap.stream().limit(6).toList());
            if (endpoint != null) {
                score += 400;
                reason = "exact endpoint " + endpoint;
            } else if (identifier != null) {
                score += 100;
                reason = "exact identifier " + identifier;
            }
            matches.add(new Match(section, score, reason));
        }
        int best = matches.stream().mapToInt(Match::score).max().orElse(0);
        return matches.stream().filter(match -> match.score() * 2 >= best)
                .sorted(Comparator.comparingInt(Match::score).reversed()).limit(MAX_MATCHES_PER_QUERY).toList();
    }

    private int weight(String term) {
        if (GENERIC_TERMS.contains(term)) {
            return 0;
        }
        return termFrequency.getOrDefault(term, 0) <= Math.max(1, sections.size() / 3) ? 3 : 1;
    }

    private final class Selection {
        private final StringBuilder output = new StringBuilder();
        private final int noteBudget;
        private int remaining;
        private int count;

        Selection(String heading, int budget) {
            int space = Math.min(MAX_BYTES_PER_PASS, budget) - EvidenceText.bytes(heading);
            if (space >= 0) {
                output.append(heading);
            }
            noteBudget = Math.min(2_000, Math.max(0, space / 3));
            remaining = space - noteBudget;
        }

        String offer(Section section) {
            if (supplied.contains(section.id())) {
                return "ALREADY INCLUDED";
            }
            String block = "### " + section.label() + "\n\n" + section.original() + "\n\n";
            int bytes = EvidenceText.bytes(block);
            if (bytes > remaining) {
                return "OMITTED (byte limit)";
            }
            output.append(block);
            remaining -= bytes;
            count++;
            supplied.add(section.id());
            return "INCLUDED";
        }

        String markdown(String notes) {
            return output.append(EvidenceText.linePrefix(notes, noteBudget)).toString();
        }
    }

    private static Pattern changedPaths(GitClient.Change change) {
        String alternatives = Pattern.quote(change.path());
        if (change.previousPath() != null) {
            alternatives += "|" + Pattern.quote(change.previousPath());
        }
        return Pattern.compile("(?<![\\p{L}\\p{N}_./-])(?:\\.\\.?/)*(" + alternatives + ")(?![\\p{L}\\p{N}_./-])");
    }

    private static String changedLines(String patch) {
        return patch.lines().filter(line -> line.startsWith("@@")
                        || (line.startsWith("+") && !line.startsWith("+++"))
                        || (line.startsWith("-") && !line.startsWith("---")))
                .collect(Collectors.joining("\n"));
    }

    private static Set<String> matches(Pattern pattern, String text) {
        return pattern.matcher(text).results().map(match -> normalize(match.group())).collect(Collectors.toSet());
    }

    private static void addFileStem(Set<String> symbols, String path) {
        String file = path.substring(path.lastIndexOf('/') + 1);
        int extension = file.lastIndexOf('.');
        String stem = normalize(extension > 0 ? file.substring(0, extension) : file);
        if (stem.length() >= 4 && !GENERIC_FILE_STEMS.contains(stem)) {
            symbols.add(stem);
        }
    }

    private static Set<String> terms(String text) {
        var terms = new HashSet<String>();
        var matcher = TERM.matcher(normalize(text));
        while (matcher.find()) {
            String term = matcher.group();
            if (Character.UnicodeScript.of(term.codePointAt(0)) == Character.UnicodeScript.HAN) {
                int[] chars = term.codePoints().toArray();
                for (int i = 0; i + 1 < chars.length; i++) {
                    String pair = new String(chars, i, 2);
                    if (!STOP_TERMS.contains(pair)) {
                        terms.add(pair);
                    }
                }
            } else if (term.length() >= 3 && !STOP_TERMS.contains(term)) {
                terms.add(term);
            }
        }
        return terms;
    }

    private static String normalize(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }

    private static List<Section> sections(String content) {
        var headings = new ArrayList<Heading>();
        var lines = LINE.matcher(content);
        String openFence = "";
        String previous = "";
        int previousOffset = 0;
        int line = 0;
        while (lines.find() && lines.start() != lines.end()) {
            line++;
            String text = lines.group().stripTrailing();
            var fence = FENCE.matcher(text);
            if (fence.matches()) {
                String marker = fence.group(1);
                if (openFence.isEmpty()) {
                    openFence = marker;
                } else if (marker.startsWith(openFence) && fence.group(2).isBlank()) {
                    openFence = "";
                }
                previous = "";
                continue;
            }
            if (!openFence.isEmpty()) {
                continue;
            }
            var heading = HEADING.matcher(text);
            var underline = UNDERLINE.matcher(text);
            if (heading.matches()) {
                headings.add(new Heading(heading.group(1).length(), heading.group(2), line, lines.start()));
                previous = "";
            } else if (underline.matches() && SETEXT_TITLE.matcher(previous).matches()) {
                headings.add(new Heading(text.strip().startsWith("=") ? 1 : 2,
                        previous.strip(), line - 1, previousOffset));
                previous = "";
            } else {
                previous = text;
                previousOffset = lines.start();
            }
        }
        int level = headings.stream().mapToInt(Heading::level).min().orElse(1);
        if (level == 1 && headings.size() > 1 && headings.getFirst().level() == 1
                && headings.stream().filter(h -> h.level() == 1).count() == 1
                && !GLOBAL_TITLES.contains(normalize(headings.getFirst().title()))) {
            level = headings.stream().mapToInt(Heading::level).filter(value -> value > 1).min().orElse(1);
        }
        int chapterLevel = level;
        var starts = new ArrayList<>(headings.stream().filter(h -> h.level() == chapterLevel).toList());
        if (starts.isEmpty() || starts.getFirst().offset() > 0) {
            starts.addFirst(new Heading(level, "Document preamble", 1, 0));
        }
        var sections = new ArrayList<Section>();
        for (int i = 0; i < starts.size(); i++) {
            Heading start = starts.get(i);
            Heading end = i + 1 < starts.size() ? starts.get(i + 1) : new Heading(0, "", line + 1, content.length());
            String original = content.substring(start.offset(), end.offset());
            if (!original.isBlank()) {
                sections.add(new Section("KB-" + start.line(), start.title(), start.line(), end.line() - 1,
                        original, terms(original), terms(start.title()), matches(ENDPOINT, original),
                        matches(CODE_SYMBOL, original)));
            }
        }
        return List.copyOf(sections);
    }
}
