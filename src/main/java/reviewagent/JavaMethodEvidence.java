package reviewagent;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
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
import java.util.LinkedHashMap;
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
        var constructors = new LinkedHashMap<ClassTree, List<Lines>>();
        var unit = syntax.unit();
        var lineMap = unit.getLineMap();
        new TreeScanner<Void, ClassTree>() {
            @Override
            public Void visitClass(ClassTree node, ClassTree owner) {
                return super.visitClass(node, node);
            }

            @Override
            public Void visitMethod(MethodTree node, ClassTree owner) {
                boolean constructor = owner != null && owner.getSimpleName().contentEquals(method)
                        && node.getName().contentEquals("<init>");
                if (constructor || node.getName().contentEquals(method)) {
                    long start = syntax.positions().getStartPosition(unit, node);
                    long end = syntax.positions().getEndPosition(unit, node);
                    if (start >= 0 && end > start) {
                        var lines = new Lines((int) lineMap.getLineNumber(start), (int) lineMap.getLineNumber(end - 1));
                        if (constructor) constructors.computeIfAbsent(owner, unused -> new ArrayList<>()).add(lines);
                        else declarations.add(lines);
                    }
                }
                return super.visitMethod(node, owner);
            }
        }.scan(unit, null);
        if (declarations.size() > 1) {
            throw new IOException("Java method name matches multiple declarations; request a line range instead");
        }
        if (!declarations.isEmpty()) return declarations.getFirst();
        if (constructors.size() > 1)
            throw new IOException("Java constructor name matches multiple types; request a line range instead");
        if (!constructors.isEmpty()) {
            var overloads = constructors.values().iterator().next();
            return new Lines(overloads.getFirst().first(), overloads.getLast().last());
        }
        throw new IOException("Java method declaration not found at that revision");
    }

    static List<LanguageModule.Lines> declarations(String source, String selector) throws IOException {
        String[] names = selector.split("#", -1);
        if (names.length > 2 || !SourceVersion.isName(names[0])
                || names.length == 2 && (!SourceVersion.isIdentifier(names[1]) || SourceVersion.isKeyword(names[1])))
            throw new IOException("Expected declaration:Type or declaration:Type#method for Java");

        var syntax = parse(source, true);
        var types = new ArrayList<ClassTree>();
        new TreeScanner<Void, String>() {
            @Override
            public Void visitClass(ClassTree node, String parent) {
                String name = node.getSimpleName().toString();
                String qualified = parent.isEmpty() ? name : parent + "." + name;
                if (names[0].equals(qualified) || names[0].equals(name)) types.add(node);
                for (Tree member : node.getMembers())
                    if (member instanceof ClassTree nested) scan(nested, qualified);
                return null;
            }
        }.scan(syntax.unit(), "");
        if (types.size() != 1) throw new IOException(types.isEmpty()
                ? "Requested Java type declaration was not found in the file"
                : "Java type name is ambiguous; use its enclosing type names");

        var type = types.getFirst();
        if (names.length == 1)
            return List.of(declarationLines(syntax, type, syntax.positions().getEndPosition(syntax.unit(), type) - 1));
        var matches = new ArrayList<LanguageModule.Lines>();
        for (Tree member : type.getMembers()) {
            if (member instanceof MethodTree method && method.getName().contentEquals(names[1])) {
                long last = method.getBody() == null ? syntax.positions().getEndPosition(syntax.unit(), method) - 1
                        : syntax.positions().getStartPosition(syntax.unit(), method.getBody());
                matches.add(declarationLines(syntax, method, last));
            }
        }
        if (matches.isEmpty()) throw new IOException("Requested Java method declaration was not found on that type");
        return List.copyOf(matches);
    }

    private static LanguageModule.Lines declarationLines(Syntax syntax, Tree declaration, long last) throws IOException {
        var unit = syntax.unit();
        long start = syntax.positions().getStartPosition(unit, declaration);
        if (start < 0 || last < start) throw new IOException("Java declaration has no usable source range");
        var lines = unit.getLineMap();
        return new LanguageModule.Lines((int) lines.getLineNumber(start), (int) lines.getLineNumber(last));
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
