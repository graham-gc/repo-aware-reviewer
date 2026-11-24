package reviewagent;

import java.io.IOException;
import java.nio.file.Path;
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

    private record Check(Request request, EvidenceBundle view, Set<String> requestedSources, String pending,
                         Set<String> recoveries, int round) { }

    static List<Result> run(List<Request> requests, EvidenceSession session, PromptFactory prompts, CliOptions options,
                            AgentRunner agent, Path directory, int roles, int limit, CommandProgress progress,
                            int stages, boolean resume) throws IOException, InterruptedException {
        var checks = new LinkedHashMap<String, Check>();
        var results = new LinkedHashMap<String, Result>();
        for (var request : requests)
            checks.put(request.task().id(), new Check(request, request.view(), Set.of(), "", Set.of(), 1));
        var jobs = requests.stream().map(request -> new ReviewTasks.Job(directory, request.task())).toList();
        try (var concurrency = agent.phaseConcurrency(jobs, roles, options, 2, "DISCOVERY", directory.getParent().resolve(".discovery-parallelism"))) {
            ReviewTasks.runReady(jobs, agent, directory, concurrency.maximum(), progress, 2, stages,
                    "Independent reviewers", resume, concurrency.admission(), (job, outcome) -> {
                String id = job.task().id();
                var check = checks.get(id);
                var view = check.view();
                var output = outcome.valid() ? ReviewOutput.initialDiscovery(outcome.response(), id, view.changedFiles()) : null;
                var needed = output == null ? null : output.evidenceNeeded();
                var outstanding = session.outstandingDiscovery(check.pending(), view.catalog());
                if (needed == null) {
                    var delivered = check.requestedSources();
                    var supplied = delivered.isEmpty() ? check.request().partition()
                            : check.request().partition().plus(view.catalog().select(delivered));
                    String detail = outstanding.isEmpty() ? "" : unavailable(outstanding);
                    if (!detail.isEmpty()) session.recordFollowup(id, check.pending(), Set.of(), detail);
                    results.put(id, new Result(id, job.directory(), supplied, outcome, output, detail));
                    return List.of();
                }

                String gap;
                if (check.round() >= options.agentPolicy().maxCalls()) {
                    gap = "Initial reviewer reached its evidence preflight round limit";
                } else {
                    session.loadDiscovery(needed.requests(), "Initial discovery evidence requested by " + id);
                    String pending = check.pending() + "\n" + needed.requests();
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
                        added.addAll(delivery.sources());
                        var retry = withEvidence(view, evidence, needed.question(), delivery.missing(), recoveries,
                                !check.pending().isEmpty());
                        var request = check.request();
                        String prompt = prompts.independentDiscovery(retry, request.focus(), request.summarize());
                        if (EvidenceText.bytes(prompt) > limit) {
                            retry = withoutOptionalIndexes(retry, added);
                            prompt = prompts.independentDiscovery(retry, request.focus(), request.summarize());
                        }
                        if (EvidenceText.bytes(prompt) <= limit) {
                            int round = check.round() + 1;
                            var noted = new LinkedHashSet<>(check.recoveries());
                            noted.addAll(recoveries);
                            checks.put(id, new Check(request, retry, Set.copyOf(added),
                                    delivery.missing().isEmpty() ? "" : pending, Set.copyOf(noted), round));
                            session.recordFollowup(id, needed.requests(), delivery.sources(), delivery.missing().isEmpty()
                                    ? "Restarted initial review with requested evidence."
                                    : "Restarted initial review with new material. " + unavailable(delivery.missing()) + ".");
                            var task = new ReviewTasks.Task(id, prompt, job.task().validator(), job.task().model());
                            return List.of(new ReviewTasks.Job(directory.resolve("round-" + round), task));
                        }
                        gap = "Required evidence and the initial review scope exceed the prompt budget";
                    }
                }
                String detail = gap + ". Unanswered question: " + EvidenceText.oneLine(needed.question());
                if (!outstanding.isEmpty() && !gap.startsWith("Required evidence unavailable:"))
                    detail += ". " + unavailable(outstanding);
                session.recordFollowup(id, needed.requests(), Set.of(), detail);
                results.put(id, new Result(id, job.directory(), view.catalog(), outcome, output, detail));
                return List.of();
            });
        }
        return requests.stream().map(request -> results.get(request.task().id())).toList();
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

    private static EvidenceBundle withoutOptionalIndexes(EvidenceBundle view, Set<String> requested) {
        var retained = view.catalog().sources().stream().map(EvidenceSource::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        var blocks = EvidenceText.SOURCE_BLOCK.matcher(view.markdown());
        var markdown = new StringBuilder();
        while (blocks.find()) {
            var source = view.catalog().source(blocks.group(1));
            if (source != null && source.role(EvidenceSource.Role.INDEX) && !requested.contains(source.id())
                    && blocks.group().equals(source.markdown().stripTrailing() + "\n")) {
                retained.remove(source.id());
                blocks.appendReplacement(markdown, "");
            }
        }
        return new EvidenceBundle(view.scope(), view.changedFiles(), blocks.appendTail(markdown).toString(), view.coverage(),
                view.supplementBudget(), view.scopedKnowledge(), view.catalog().select(retained));
    }
}
