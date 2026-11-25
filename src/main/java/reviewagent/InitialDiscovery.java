package reviewagent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Restart only the initial reviewer whose material check identified a decisive gap. */
final class InitialDiscovery {
    private static final Pattern TOOL_REPLY = Pattern.compile(
            "(?i)<tool_(?:call|use)(?=[\\s/>])|\"type\"\\s*:\\s*\"(?:tool_use|function_call)\"|\"tool_calls\"\\s*:\\s*\\[");

    private InitialDiscovery() { }

    static ReviewTasks.Validator validator(String id, List<String> changedFiles) {
        return new ReviewTasks.Validator() {
            private boolean restarted;

            @Override public void validate(String response) throws IOException {
                ReviewOutput.initialDiscovery(response, id, changedFiles);
            }

            @Override public boolean allowOutputCorrection() { return false; }

            @Override public boolean restartOnInvalidReply(String response, IOException failure) {
                if (restarted || !ReviewXml.missingResponseBlock(failure, "findings") || !TOOL_REPLY.matcher(response).find())
                    return false;
                restarted = true;
                return true;
            }
        };
    }

    record Request(ReviewTasks.Task task, EvidenceBundle view, EvidenceCatalog partition,
                   DiscoveryEvidence.Focus focus, boolean summarize) { }

    record Result(String id, Path directory, EvidenceCatalog evidence, ReviewTasks.Outcome outcome,
                  ReviewOutput.Discovery discovery, String gap) {
        boolean usable() { return discovery != null && discovery.evidenceNeeded() == null; }
    }

    static final class EarlySupplement {
        private final EvidenceBundle evidence;
        private final CliOptions options;
        private final GitClient git;
        private final int limit;
        private boolean considered;
        private Set<String> sources = Set.of();
        private String coverage = "";
        private ReviewTasks.Outcome outcome;

        EarlySupplement(EvidenceBundle evidence, CliOptions options, GitClient git, int limit) {
            this.evidence = evidence;
            this.options = options;
            this.git = git;
            this.limit = limit;
        }

        List<ReviewTasks.Job> ready(Iterable<Result> completed, EvidenceSession session, PromptFactory prompts,
                                    Path directory) throws IOException, InterruptedException {
            if (considered) return List.of();
            considered = true;
            var before = session.catalog().sources().stream().map(EvidenceSource::id).collect(Collectors.toSet());
            var notes = new StringBuilder();
            for (var result : completed) {
                if (result.usable()) notes.append(result.discovery().notes()).append('\n');
            }
            if (EvidenceRequest.parse(notes.toString()).isEmpty()) return List.of();
            session.load(notes.toString(), "Early supplemental discovery evidence requests");
            sources = session.catalog().sources().stream().map(EvidenceSource::id).filter(id -> !before.contains(id))
                    .collect(Collectors.toSet());
            if (sources.isEmpty()) return List.of();
            var augmented = new EvidenceBundle(evidence.scope(), evidence.changedFiles(), evidence.markdown() + session.markdown(),
                    evidence.coverage(), 0, evidence.scopedKnowledge(), session.catalog());
            var plan = DeepDiscovery.plan(augmented, options, git, prompts, sources, limit);
            if (plan.batches().size() != 1 || !plan.gaps().isEmpty()) return List.of();
            var view = plan.batches().getFirst();
            coverage = plan.coverage();
            session.recordEarlyDiscovery(sources);
            return List.of(new ReviewTasks.Job(directory, new ReviewTasks.Task("S1", prompts.supplementalDiscovery(view, sources),
                    ReviewTasks.Validator.singleResponse(reply -> ReviewOutput.discovery(reply, "S1", view.changedFiles())))));
        }

        ReviewTasks.Outcome outcome() { return outcome; }
        Set<String> pendingSources() { return outcome == null ? sources : Set.of(); }
        String coverage() { return coverage; }
    }

    private record Check(Request request, EvidenceBundle view, Set<String> requestedSources, List<String> pendingBatches,
                         Set<String> recoveries, int round) { }

    static List<Result> run(List<Request> requests, EvidenceSession session, PromptFactory prompts, CliOptions options,
                            AgentRunner agent, Path directory, int roles, int limit, CommandProgress progress,
                            int stages, boolean resume, EarlySupplement early) throws IOException, InterruptedException {
        var checks = new LinkedHashMap<String, Check>();
        var results = new LinkedHashMap<String, Result>();
        for (var request : requests)
            checks.put(request.task().id(), new Check(request, request.view(), Set.of(), List.of(), Set.of(), 1));
        var jobs = requests.stream().map(request -> new ReviewTasks.Job(directory, request.task())).toList();
        var firstReplies = new LinkedHashSet<String>();
        try (var concurrency = agent.phaseConcurrency(jobs, roles, options, 2, "DISCOVERY", directory.getParent().resolve(".discovery-parallelism"))) {
            ReviewTasks.runReady(jobs, agent, directory, concurrency.maximum(), progress, 2, stages,
                    "Independent reviewers", resume, concurrency.admission(), (job, outcome) -> {
                String id = job.task().id();
                if (id.equals("S1")) {
                    early.outcome = outcome;
                    return List.of();
                }
                firstReplies.add(id);
                var check = checks.get(id);
                var view = check.view();
                var output = outcome.valid() ? ReviewOutput.initialDiscovery(outcome.response(), id, view.changedFiles()) : null;
                var needed = output == null ? null : output.evidenceNeeded();
                String previousRequests = String.join("\n", check.pendingBatches());
                if (needed == null) {
                    var outstanding = output == null ? session.outstandingDiscovery(previousRequests, view.catalog())
                            : session.outstandingDiscovery(check.pendingBatches(), view.catalog(), output);
                    var delivered = check.requestedSources();
                    var supplied = delivered.isEmpty() ? check.request().partition()
                            : check.request().partition().plus(view.catalog().select(delivered));
                    String detail = outstanding.isEmpty() ? "" : unavailable(outstanding);
                    if (!detail.isEmpty()) session.recordFollowup(id, previousRequests, Set.of(), detail);
                    results.put(id, new Result(id, job.directory(), supplied, outcome, output, detail));
                    return earlySupplement(early, requests.size(), firstReplies, results, session, prompts, directory);
                }

                String gap;
                if (check.round() >= options.agentPolicy().maxCalls()) {
                    gap = "Initial reviewer reached its evidence preflight round limit";
                } else {
                    session.loadDiscovery(needed.requests(), "Initial discovery evidence requested by " + id, previousRequests);
                    String pending = previousRequests + "\n" + needed.requests();
                    var delivery = session.discoveryDelivery(pending, view.catalog());
                    var recoveries = delivery.recoveries().stream().filter(note -> !check.recoveries().contains(note)).toList();
                    var evidence = session.catalog().select(delivery.sources());
                    boolean useful = !recoveries.isEmpty() || evidence.sources().stream()
                            .anyMatch(source -> delivery.missing().isEmpty() || !source.role(EvidenceSource.Role.INDEX));
                    if (!useful) {
                        gap = delivery.missing().isEmpty() ? "Initial reviewer requested evidence already supplied in its prompt"
                                : unavailable(delivery.missing());
                    } else {
                        var added = new LinkedHashSet<>(check.requestedSources());
                        added.addAll(delivery.requestedSources());
                        var retry = withEvidence(view, evidence, needed.question(), delivery.missing(), recoveries,
                                !check.pendingBatches().isEmpty());
                        var request = check.request();
                        String prompt = prompts.independentDiscovery(retry, request.focus(), request.summarize());
                        List<String> omitted = List.of();
                        if (EvidenceText.bytes(prompt) > limit) {
                            var fitted = fitFollowup(retry, session.catalog().select(added), request, prompts, limit);
                            retry = fitted.view();
                            prompt = fitted.prompt();
                            omitted = fitted.omitted();
                        }
                        if (EvidenceText.bytes(prompt) <= limit) {
                            int round = check.round() + 1;
                            var noted = new LinkedHashSet<>(check.recoveries());
                            noted.addAll(recoveries);
                            var batches = new ArrayList<>(check.pendingBatches());
                            batches.add(needed.requests());
                            checks.put(id, new Check(request, retry, Set.copyOf(added),
                                    delivery.missing().isEmpty() ? List.of() : List.copyOf(batches), Set.copyOf(noted), round));
                            session.recordFollowup(id, needed.requests(), delivery.sources(), delivery.missing().isEmpty()
                                    ? "Restarted initial review with requested evidence."
                                    : "Restarted initial review with new material. " + unavailable(delivery.missing()) + ".");
                            if (!omitted.isEmpty()) session.recordRepackedDiscovery(id, omitted);
                            var task = new ReviewTasks.Task(id, prompt, job.task().validator(), job.task().model());
                            var ready = new ArrayList<ReviewTasks.Job>();
                            ready.add(new ReviewTasks.Job(directory.resolve("round-" + round), task));
                            ready.addAll(earlySupplement(early, requests.size(), firstReplies, results, session, prompts, directory));
                            return ready;
                        }
                        gap = "Required evidence and the initial review scope exceed the prompt budget";
                    }
                }
                String detail = gap + ". Unanswered question: " + EvidenceText.oneLine(needed.question());
                var outstanding = session.outstandingDiscovery(previousRequests, view.catalog());
                if (!outstanding.isEmpty() && !gap.startsWith("Required evidence unavailable:"))
                    detail += ". " + unavailable(outstanding);
                session.recordFollowup(id, needed.requests(), Set.of(), detail);
                results.put(id, new Result(id, job.directory(), view.catalog(), outcome, output, detail));
                return earlySupplement(early, requests.size(), firstReplies, results, session, prompts, directory);
            });
        }
        return requests.stream().map(request -> results.get(request.task().id())).toList();
    }

    private static List<ReviewTasks.Job> earlySupplement(EarlySupplement early, int total, Set<String> firstReplies,
            LinkedHashMap<String, Result> results, EvidenceSession session, PromptFactory prompts, Path directory)
            throws IOException, InterruptedException {
        // Aggregate the first wave before launching supplemental discovery. A reviewer waiting for its
        // own preflight evidence is independent of the material already requested by completed reviews.
        return early != null && firstReplies.size() == total && !results.isEmpty() && results.size() < total
                ? early.ready(results.values(), session, prompts, directory) : List.of();
    }

    private static String unavailable(List<String> requests) {
        return "Required evidence unavailable: " + String.join(", ", requests);
    }

    private static EvidenceBundle withEvidence(EvidenceBundle view, EvidenceCatalog material, String question,
                                               List<String> missing, List<String> recoveries, boolean previouslyIncomplete) {
        String notes = java.util.stream.Stream.concat(recoveries.stream(), missing.stream().map(request -> "Unavailable: " + request))
                .collect(Collectors.joining("\n"));
        if (previouslyIncomplete && missing.isEmpty()) notes += "\nAll currently requested sources were supplied.";
        if (!notes.isEmpty()) notes = EvidenceText.block("Requested sources: delivery update", "text", notes);
        String markdown = view.markdown() + "\n" + EvidenceText.block("Previous material check (question only)", "text", question)
                + notes
                + material.sources().stream().map(EvidenceSource::markdown).collect(Collectors.joining());
        return new EvidenceBundle(view.scope(), view.changedFiles(), markdown, view.coverage(), view.supplementBudget(),
                view.scopedKnowledge(), view.catalog().plus(material));
    }

    private record OptionalBlock(EvidenceSource source, int bytes) { }
    private record FollowupPrompt(EvidenceBundle view, String prompt, List<String> omitted) { }

    private static FollowupPrompt fitFollowup(EvidenceBundle view, EvidenceCatalog requested, Request request,
                                             PromptFactory prompts, int limit) {
        var requestedIds = requested.sources().stream().map(EvidenceSource::id).collect(Collectors.toSet());
        var requestedPaths = requested.sources().stream().filter(source -> !source.role(EvidenceSource.Role.INDEX))
                .map(EvidenceSource::path).filter(path -> !path.isEmpty()).collect(Collectors.toSet());
        var optional = new ArrayList<OptionalBlock>();
        var blocks = EvidenceText.SOURCE_BLOCK.matcher(view.markdown());
        while (blocks.find()) {
            var source = view.catalog().source(blocks.group(1));
            if (source == null || requestedIds.contains(source.id()) || requestedPaths.contains(source.path())
                    || source.role(EvidenceSource.Role.DIFF) || source.role(EvidenceSource.Role.CONTRACT)
                    || !blocks.group().equals(source.markdown().stripTrailing() + "\n")) continue;
            optional.add(new OptionalBlock(source, EvidenceText.bytes(blocks.group())));
        }
        optional.sort(Comparator.comparingInt((OptionalBlock block) -> block.source().role(EvidenceSource.Role.INDEX) ? 0 : 1)
                .thenComparing(Comparator.comparingInt(OptionalBlock::bytes).reversed()));
        var omitted = new LinkedHashSet<String>();
        var labels = new ArrayList<String>();
        var fitted = view;
        String prompt = prompts.independentDiscovery(view, request.focus(), request.summarize());
        int remaining = EvidenceText.bytes(prompt) - limit;
        for (var block : optional) {
            if (!omitted.add(block.source().id())) continue;
            labels.add(block.source().title());
            remaining -= block.bytes();
            if (remaining > 0) continue;
            fitted = withoutSources(view, omitted);
            prompt = prompts.independentDiscovery(fitted, request.focus(), request.summarize());
            remaining = EvidenceText.bytes(prompt) - limit;
            if (remaining <= 0) return new FollowupPrompt(fitted, prompt, List.copyOf(labels));
        }
        return new FollowupPrompt(fitted, prompt, List.copyOf(labels));
    }

    private static EvidenceBundle withoutSources(EvidenceBundle view, Set<String> omitted) {
        var retained = view.catalog().sources().stream().map(EvidenceSource::id)
                .filter(id -> !omitted.contains(id)).collect(Collectors.toSet());
        var blocks = EvidenceText.SOURCE_BLOCK.matcher(view.markdown());
        var markdown = new StringBuilder();
        while (blocks.find()) {
            var source = view.catalog().source(blocks.group(1));
            if (source != null && omitted.contains(source.id()) && blocks.group().equals(source.markdown().stripTrailing() + "\n"))
                blocks.appendReplacement(markdown, "");
        }
        return new EvidenceBundle(view.scope(), view.changedFiles(), blocks.appendTail(markdown).toString(), view.coverage(),
                view.supplementBudget(), view.scopedKnowledge(), view.catalog().select(retained));
    }
}
