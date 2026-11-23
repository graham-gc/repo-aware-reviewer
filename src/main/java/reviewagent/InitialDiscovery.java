package reviewagent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Restart only the initial reviewer whose material check identified a decisive gap. */
final class InitialDiscovery {
    private InitialDiscovery() { }

    record Request(ReviewTasks.Task task, EvidenceBundle view, EvidenceCatalog partition,
                   DiscoveryEvidence.Focus focus, boolean summarize) { }

    record Result(String id, Path directory, EvidenceCatalog evidence, ReviewTasks.Outcome outcome,
                  ReviewOutput.Discovery discovery, String gap) {
        boolean completed() { return outcome.valid() && gap.isEmpty(); }
    }

    private record Check(Request request, EvidenceBundle view, Set<String> requestedSources, int round) { }

    static List<Result> run(List<Request> requests, EvidenceSession session, PromptFactory prompts, CliOptions options,
                            AgentRunner agent, Path directory, int roles, int limit, CommandProgress progress,
                            int stages, boolean resume) throws IOException, InterruptedException {
        var checks = new LinkedHashMap<String, Check>();
        var results = new LinkedHashMap<String, Result>();
        for (var request : requests) checks.put(request.task().id(), new Check(request, request.view(), Set.of(), 1));
        var jobs = requests.stream().map(request -> new ReviewTasks.Job(directory, request.task())).toList();
        try (var concurrency = agent.phaseConcurrency(jobs, roles, options, 2, "DISCOVERY", directory.getParent().resolve(".discovery-parallelism"))) {
            ReviewTasks.runReady(jobs, agent, directory, concurrency.maximum(), progress, 2, stages,
                    "Independent reviewers", resume, concurrency.admission(), (job, outcome) -> {
                String id = job.task().id();
                var check = checks.get(id);
                var view = check.view();
                var output = outcome.valid() ? ReviewOutput.initialDiscovery(outcome.response(), id, view.changedFiles()) : null;
                var needed = output == null ? null : output.evidenceNeeded();
                if (needed == null) {
                    var delivered = check.requestedSources();
                    var supplied = delivered.isEmpty() ? check.request().partition()
                            : check.request().partition().plus(view.catalog().select(delivered));
                    results.put(id, new Result(id, job.directory(), supplied, outcome, output, ""));
                    return List.of();
                }

                String gap;
                if (check.round() >= options.agentPolicy().maxCalls()) {
                    gap = "Initial reviewer reached its evidence preflight round limit";
                } else if (!session.requestOnly(needed.requests())) {
                    gap = "Initial reviewer did not provide valid requests for the missing evidence";
                } else {
                    session.load(needed.requests(), "Initial discovery evidence requested by " + id);
                    var delivery = session.discoveryDelivery(needed.requests(), view.catalog());
                    if (!delivery.missing().isEmpty()) {
                        gap = "Required evidence unavailable: " + String.join(", ", delivery.missing());
                    } else if (delivery.sources().isEmpty()) {
                        gap = "Initial reviewer requested evidence already supplied in its prompt";
                    } else {
                        var added = new LinkedHashSet<>(check.requestedSources());
                        added.addAll(delivery.sources());
                        var evidence = session.catalog().select(delivery.sources());
                        var retry = withEvidence(view, evidence, needed.question());
                        var request = check.request();
                        String prompt = prompts.independentDiscovery(retry, request.focus(), request.summarize());
                        if (EvidenceText.bytes(prompt) > limit) {
                            retry = withoutOptionalIndexes(retry, added);
                            prompt = prompts.independentDiscovery(retry, request.focus(), request.summarize());
                        }
                        if (EvidenceText.bytes(prompt) <= limit) {
                            int round = check.round() + 1;
                            checks.put(id, new Check(request, retry, Set.copyOf(added), round));
                            session.recordFollowup(id, needed.requests(), Set.of(), "Restarted initial review with requested evidence.");
                            var task = new ReviewTasks.Task(id, prompt, job.task().validator(), job.task().model());
                            return List.of(new ReviewTasks.Job(directory.resolve("round-" + round), task));
                        }
                        gap = "Required evidence and the initial review scope exceed the prompt budget";
                    }
                }
                String detail = gap + ". Unanswered question: " + EvidenceText.oneLine(needed.question());
                session.recordFollowup(id, needed.requests(), Set.of(), detail);
                results.put(id, new Result(id, job.directory(), view.catalog(), outcome, output, detail));
                return List.of();
            });
        }
        return requests.stream().map(request -> results.get(request.task().id())).toList();
    }

    private static EvidenceBundle withEvidence(EvidenceBundle view, EvidenceCatalog material, String question) {
        String markdown = view.markdown() + "\n" + EvidenceText.block("Previous material check (question only)", "text", question)
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
