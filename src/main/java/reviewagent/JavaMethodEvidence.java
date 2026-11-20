package reviewagent;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;

import javax.lang.model.SourceVersion;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

final class JavaMethodEvidence {
    record Lines(int first, int last) {
    }

    record Syntax(CompilationUnitTree unit, SourcePositions positions) { }

    static Lines locate(String source, String method) throws IOException {
        if (!SourceVersion.isIdentifier(method) || SourceVersion.isKeyword(method)) {
            throw new IOException("Expected a Java method name after symbol:");
        }
        var syntax = parse(source, false);
        var declarations = new ArrayList<Lines>();
        var unit = syntax.unit();
        var lineMap = unit.getLineMap();
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitMethod(MethodTree node, Void unused) {
                if (node.getName().contentEquals(method)) {
                    long start = syntax.positions().getStartPosition(unit, node);
                    long end = syntax.positions().getEndPosition(unit, node);
                    if (start >= 0 && end > start) {
                        declarations.add(new Lines((int) lineMap.getLineNumber(start),
                                (int) lineMap.getLineNumber(end - 1)));
                    }
                }
                return super.visitMethod(node, unused);
            }
        }.scan(unit, null);
        if (declarations.isEmpty()) {
            throw new IOException("Java method declaration not found at that revision");
        }
        if (declarations.size() > 1) {
            throw new IOException("Java method name matches multiple declarations; request a line range instead");
        }
        return declarations.getFirst();
    }

    static Syntax parse(String source, boolean requireValid) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IOException("Java method lookup requires a JDK; request a line range instead");
        }
        var input = new SimpleJavaFileObject(URI.create("memory:///Source.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var task = (JavacTask) compiler.getTask(null, null, diagnostics, List.of("-proc:none"), null, List.of(input));
        // Parse syntax only. There is no compilation, class loading, or project dependency resolution.
        var units = task.parse().iterator();
        if (!units.hasNext() || requireValid && diagnostics.getDiagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR))
            throw new IOException("Java source could not be parsed");
        return new Syntax(units.next(), Trees.instance(task).getSourcePositions());
    }
}
