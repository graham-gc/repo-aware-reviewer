package reviewagent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Static language capabilities. Providers are registered through Java ServiceLoader. */
public interface LanguageModule {
    String id();
    boolean supports(String path);
    default int sourcePriority(String path) { return supports(path) ? 10 : -1; }
    default boolean implementationEvidence(String path) { return supports(path); }
    enum Capability { TEXT_EVIDENCE, FILE_INDEX, DECLARATION_LOOKUP, DEPENDENCY_EVIDENCE }
    default java.util.Set<Capability> capabilities() { return java.util.Set.of(Capability.TEXT_EVIDENCE); }
    default boolean isText(String path) { return supports(path); }
    default boolean reviewAppliesTo(String path) { return isText(path); }
    default Path defaultCache() { return null; }
    default List<String> manifests(Repository repository, String revision, List<String> changedPaths)
            throws IOException, InterruptedException { return List.of(); }
    default String context(Repository repository, String revision, List<String> changedPaths, int maxBytes)
            throws IOException, InterruptedException { return ""; }
    default Lines locate(String source, String symbol) throws IOException {
        throw new IOException("Symbol lookup is unavailable for language module " + id());
    }
    default String load(Request request, Environment environment, int maxBytes)
            throws IOException, InterruptedException {
        throw new IOException("This evidence request is unavailable for language module " + id());
    }
    default Session open(Environment environment) {
        return (request, maxBytes) -> new Evidence(load(request, environment, maxBytes), "INCLUDED");
    }
    default List<String> legacyRequests() { return List.of(); }
    default String instructions() { return ""; }
    default String requestInstructions() { return ""; }
    static String block(String title, String language, String content) { return EvidenceText.block(title, language, content); }

    record Lines(int first, int last) { }
    record Request(String revision, String context, String resource, String selector) { }
    record Environment(Repository repository, Path moduleCache, Commands commands) { }
    record Result(int exitCode, String stdout, String stderr) { }
    record Evidence(String markdown, String status) { }
    @FunctionalInterface interface Session {
        Evidence load(Request request, int maxBytes) throws IOException, InterruptedException;
    }

    interface Repository {
        List<String> paths(String revision) throws IOException, InterruptedException;
        String read(String revision, String path, int maxBytes) throws IOException, InterruptedException;
    }
    @FunctionalInterface interface Commands {
        Result run(List<String> command, Path directory, java.time.Duration timeout) throws IOException, InterruptedException;
    }
}
