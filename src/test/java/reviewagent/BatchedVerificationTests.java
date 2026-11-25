package reviewagent;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

final class BatchedVerificationTests {
    static void run() throws Exception {
        compareRequestPlans();
        isolateVerdicts();
        continueWhileAnotherTaskIsRunning();
    }

    private static void compareRequestPlans() {
        var first = member("R1-1");
        var second = member("R2-1");
        var members = List.of(first, second);
        var broad = new VerificationBatch.Plan(members, List.of(new VerificationBatch(members, "Shared broad evidence")));
        var separated = new VerificationBatch.Plan(members, List.of());
        var compact = new VerificationBatch.Plan(members, List.of(new VerificationBatch(members, "Shorter")));
        require(separated.inputBytes() < broad.inputBytes() && !separated.improvesOn(broad),
                "smaller prompts must not turn a shared request into more individual calls");
        require(compact.improvesOn(broad) && !broad.improvesOn(compact),
                "equally batched plans are compared by transmitted input");
    }

    private static void isolateVerdicts() throws Exception {
        var batch = new VerificationBatch(List.of(member("R1-1"), member("R2-1"), member("R3-1")), "");
        String requests = "REQUEST_FILE|target|input.py|all|Find the exact caller";
        String reply = """
                <verifications>
                  <verification id="R1-1"><status>REJECTED</status><reason>Guard is present.</reason></verification>
                  <verification id="R2-1"><status>INSUFFICIENT_EVIDENCE</status><reason>Need caller.</reason>
                    <nextCheck>Does the caller allow an empty input?</nextCheck><requests>%s</requests>
                  </verification>
                  <verification id="R3-1"><status>CONFIRMED</status><reason>No proof supplied.</reason></verification>
                  <verification id="unowned"><status>INSUFFICIENT_EVIDENCE</status><reason>Not a candidate.</reason>
                    <nextCheck>Unknown input.</nextCheck><requests>REQUEST_FILE|target|unrelated.py|all|Read it</requests>
                  </verification>
                </verifications>
                """.formatted(requests);
        var decoded = batch.responses(reply);
        var results = decoded.accepted();
        require(results.keySet().equals(Set.of("R1-1", "R2-1")) && decoded.evidenceGaps().isEmpty(),
                "keep valid members and isolate the invalid one");
        require(EvidenceRequest.parse(results.get("R1-1")).isEmpty(), "a sibling's requests do not cross candidate boundaries");
        require(ReviewOutput.verificationFollowup(results.get("R2-1")).requests().strip().equals(requests),
                "deliver only the requesting candidate's valid follow-up");

        var duplicate = reply.replace("</verifications>", """
                <verification id="R1-1"><status>REJECTED</status><reason>Ambiguous verdict.</reason></verification>
                </verifications>
                """);
        require(batch.responses(duplicate).accepted().keySet().equals(Set.of("R2-1")), "a repeated ID invalidates only its own verdict");

        var invalidPending = reply.replace("<nextCheck>Does the caller", "<relation>未确定</relation>\n<nextCheck>Does the caller");
        var recoverable = batch.responses(invalidPending);
        require(recoverable.accepted().keySet().equals(Set.of("R1-1"))
                && recoverable.evidenceGaps().equals(java.util.Map.of("R2-1",
                new VerificationBatch.EvidenceGap("Does the caller allow an empty input?", requests))),
                "an invalid verdict yields only the candidate's explicit evidence question");
        var ambiguous = invalidPending.replace("</verifications>", """
                <verification id="R2-1"><status>REJECTED</status><reason>Ambiguous owner.</reason></verification>
                </verifications>
                """);
        require(batch.responses(ambiguous).evidenceGaps().isEmpty(), "an ambiguous candidate cannot authorize recovery of an evidence question");
    }

    private static VerificationBatch.Member member(String id) {
        var candidate = new ReviewFinding(id, "Candidate", ReviewFinding.Severity.MEDIUM, "runner.py", 1, 1,
                "Trigger", "Impact", "Evidence", "Changed behavior");
        return new VerificationBatch.Member(candidate, null, new ReviewTasks.Task(id, "", reply ->
                ReviewOutput.decision(reply, candidate, List.of("runner.py"))));
    }

    private static void continueWhileAnotherTaskIsRunning() throws Exception {
        var directory = Files.createTempDirectory("verification-ready-");
        var agent = new AgentRunner(new ProcessExecutor(), "/bin/sh", List.of("-c", """
                task=$(cat)
                case "$task" in
                    slow) while [ ! -e continued ]; do sleep 0.02; done ;;
                    followup) touch continued ;;
                    queued) test -e continued || exit 5 ;;
                esac
                printf done
                """), directory, Duration.ofSeconds(5));
        var fast = job(directory, "fast");
        var slow = job(directory, "slow");
        var queued = job(directory, "queued");
        var followup = job(directory.resolve("round-2"), "followup");
        try (var progress = new CommandProgress("verification", new PrintStream(OutputStream.nullOutputStream()))) {
            ReviewTasks.runReady(List.of(fast, slow, queued), agent, directory, 2, progress, 1, 1,
                    "Verify candidates", false, (job, outcome) -> {
                        require(outcome.valid(), "a ready follow-up runs before the blocked sibling and its queued peers");
                        return job.equals(fast) ? List.of(followup) : List.of();
                    });
        }
        require(Files.exists(directory.resolve("continued")), "the earlier result released its continuation");
    }

    private static ReviewTasks.Job job(Path directory, String id) {
        return new ReviewTasks.Job(directory, new ReviewTasks.Task(id, id, ignored -> { }));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
