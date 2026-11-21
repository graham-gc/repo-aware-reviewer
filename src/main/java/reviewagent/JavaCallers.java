package reviewagent;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreeScanner;
import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Navigate to direct, syntactically linked Java callers without compiling the target project. */
record JavaCallers(List<String> paths, Set<JavaCallers.Method> methods) {
    record Method(String owner, String packageName, String name, boolean isStatic) {
        String qualifiedOwner() { return packageName.isEmpty() ? owner : packageName + "." + owner; }
    }

    static JavaCallers find(GitClient git, String revision, List<String> seeds, Map<String, EvidenceSource> targets,
                            Map<String, EvidenceSource> baselines) throws IOException, InterruptedException {
        var methods = new LinkedHashSet<Method>();
        for (String path : seeds) {
            var old = baselines.get(path);
            if (old == null) continue;
            try {
                var after = methods(JavaMethodEvidence.parse(targets.get(path).text(), true).unit());
                var before = methods(JavaMethodEvidence.parse(old.text(), true).unit());
                after.forEach((method, declarations) -> {
                    if (!before.getOrDefault(method, Set.of()).equals(declarations)) methods.add(method);
                });
                before.keySet().stream().filter(method -> !after.containsKey(method)).forEach(methods::add);
            } catch (IOException unavailable) {
                // A syntax error or a runtime without the Java compiler leaves normal review available.
            }
        }
        if (methods.isEmpty() || methods.size() > 16) return null;
        var types = methods.stream().map(Method::owner).distinct().toList();
        var paths = git.filesMatching(revision, types, ":(top,glob)**/*.java");
        return new JavaCallers(paths, methods);
    }

    private static Map<Method, Set<String>> methods(CompilationUnitTree unit) {
        var methods = new LinkedHashMap<Method, Set<String>>();
        for (Tree declaration : unit.getTypeDecls()) {
            if (!(declaration instanceof ClassTree type) || type.getSimpleName().isEmpty()) continue;
            for (Tree member : type.getMembers()) {
                if (!(member instanceof MethodTree method) || method.getModifiers().getFlags().contains(Modifier.PRIVATE)
                        || method.getName().contentEquals("<init>")) continue;
                var key = new Method(type.getSimpleName().toString(), packageName(unit), method.getName().toString(),
                        method.getModifiers().getFlags().contains(Modifier.STATIC));
                methods.computeIfAbsent(key, unused -> new LinkedHashSet<>()).add(method.toString());
            }
        }
        return methods;
    }

    boolean matches(EvidenceSource source) {
        try {
            var unit = JavaMethodEvidence.parse(source.text(), true).unit();
            var receivers = new HashMap<String, Set<String>>();
            var calls = new ArrayList<MethodInvocationTree>();
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitVariable(VariableTree node, Void unused) {
                    receivers.computeIfAbsent(node.getName().toString(), ignored -> new LinkedHashSet<>()).add(typeName(node.getType()));
                    return super.visitVariable(node, unused);
                }

                @Override
                public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                    calls.add(node);
                    return super.visitMethodInvocation(node, unused);
                }
            }.scan(unit, null);
            // Ambiguous variable types anywhere in the file are left to explicit evidence requests.
            return calls.stream().anyMatch(call -> methods.stream().anyMatch(method -> matches(unit, call, method, receivers)));
        } catch (IOException unavailable) {
            return false;
        }
    }

    private static boolean matches(CompilationUnitTree unit, MethodInvocationTree call, Method method, Map<String, Set<String>> receivers) {
        var select = call.getMethodSelect();
        if (select instanceof IdentifierTree name && name.getName().contentEquals(method.name()) && method.isStatic()) {
            return unit.getImports().stream().anyMatch(imported -> imported.isStatic()
                    && imported.getQualifiedIdentifier().toString().equals(method.qualifiedOwner() + "." + method.name()));
        }
        if (!(select instanceof MemberSelectTree member) || !member.getIdentifier().contentEquals(method.name())) return false;
        Tree receiver = member.getExpression();
        if (receiver instanceof NewClassTree constructed) return typeMatches(unit, typeName(constructed.getIdentifier()), method);
        String name = receiver.toString();
        var types = receivers.get(name.startsWith("this.") ? name.substring(5) : name);
        if (types != null) return types.size() == 1 && typeMatches(unit, types.iterator().next(), method);
        return method.isStatic() && typeMatches(unit, name, method);
    }

    private static boolean typeMatches(CompilationUnitTree unit, String name, Method method) {
        if (name.equals(method.qualifiedOwner()) && !method.packageName().isEmpty()) return true;
        if (!name.equals(method.owner())) return false;
        var importedOwners = unit.getImports().stream().filter(imported -> !imported.isStatic())
                .map(imported -> imported.getQualifiedIdentifier().toString()).filter(imported -> imported.endsWith("." + name)).toList();
        return importedOwners.isEmpty() ? packageName(unit).equals(method.packageName())
                : importedOwners.equals(List.of(method.qualifiedOwner()));
    }

    private static String typeName(Tree type) {
        return type instanceof ParameterizedTypeTree parameterized ? parameterized.getType().toString() : type == null ? "" : type.toString();
    }

    private static String packageName(CompilationUnitTree unit) {
        return unit.getPackageName() == null ? "" : unit.getPackageName().toString();
    }
}
