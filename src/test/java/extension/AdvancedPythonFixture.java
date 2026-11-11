package extension;

import reviewagent.TextLanguageModule;
import java.util.Set;

/** A semantic provider can reuse its declarative formats and metadata without changing framework code. */
public final class AdvancedPythonFixture extends TextLanguageModule {
    public AdvancedPythonFixture() { super(AdvancedPythonFixture.class, "/reviewagent/languages/python.properties"); }
    public Set<Capability> capabilities() { return Set.of(Capability.TEXT_EVIDENCE, Capability.FILE_INDEX, Capability.DECLARATION_LOOKUP); }
    public Lines locate(String source, String symbol) { return new Lines(2, 2); }
    public String requestInstructions() { return "Advanced Python fixture declaration helper is available.\n" + super.requestInstructions(); }
}
