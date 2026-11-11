package extension;

import reviewagent.LanguageModule;
import java.io.IOException;
import java.util.List;

/** An external-package provider tests the public extension boundary without changing production code. */
public final class FixtureLanguageModule implements LanguageModule {
    public String id() { return "fixture"; }
    public boolean supports(String path) { return path.endsWith(".fixture"); }
    public java.util.Set<Capability> capabilities() { return java.util.Set.of(Capability.TEXT_EVIDENCE, Capability.DECLARATION_LOOKUP, Capability.DEPENDENCY_EVIDENCE); }
    public List<String> manifests(Repository repo, String revision, List<String> changed) { return List.of("fixture.config"); }
    public Lines locate(String source, String symbol) throws IOException {
        if (!symbol.equals("entry")) throw new IOException("Unknown fixture symbol");
        return new Lines(1, 1);
    }
    public String load(Request request, Environment environment, int maxBytes) throws IOException, InterruptedException {
        return LanguageModule.block("Fixture dependency at " + request.revision(), "text",
                environment.repository().read(request.revision(), "fixture.config", maxBytes));
    }
    public String instructions() { return "Fixture-specific syntax is supplied by the extension."; }
}
