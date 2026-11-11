package reviewagent;

import java.io.IOException;
import java.util.List;

public final class JavaLanguageModule implements LanguageModule {
    public String id() { return "java"; }
    public boolean supports(String path) { return path.endsWith(".java"); }
    public java.util.Set<Capability> capabilities() { return java.util.Set.of(Capability.TEXT_EVIDENCE, Capability.DECLARATION_LOOKUP, Capability.DEPENDENCY_EVIDENCE); }
    public boolean isText(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT);
        return supports(path.toLowerCase(java.util.Locale.ROOT)) || List.of("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts").contains(name);
    }
    public java.nio.file.Path defaultCache() { return java.nio.file.Path.of(System.getProperty("user.home"), ".m2", "repository"); }
    public List<String> manifests(Repository repository, String revision, List<String> changed) { return List.of("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"); }
    public Lines locate(String source, String symbol) throws IOException {
        var lines = JavaMethodEvidence.locate(source, symbol);
        return new Lines(lines.first(), lines.last());
    }
    public List<String> legacyRequests() { return List.of("REQUEST_MAVEN"); }
    public String load(Request request, Environment environment, int maxBytes) throws IOException, InterruptedException {
        return new MavenEvidence(environment.repository(), environment.commands(), environment.moduleCache())
                .load(request.revision(), request.resource(), request.selector(), maxBytes);
    }
    public String instructions() {
        return "Java module: declaration lookup and cached direct-JAR evidence are available.\n";
    }
    public String requestInstructions() {
        return """
                Java helpers: REQUEST_FILE|target|src/File.java|symbol:methodName|reason
                Symbol lookup requires a unique Java method name in that file and returns at most 200 lines.
                REQUEST_LANGUAGE|target|java|pom.xml|group:artifact:exactVersion|package.Type|reason
                Java dependency requests require an exact fixed direct dependency in the root POM and a local
                cached JAR. A .proto archive path may replace package.Type. Public signatures do not prove behavior.
                Legacy REQUEST_MAVEN|target|group:artifact:exactVersion|package.Type|reason is also accepted.
                """;
    }
}
