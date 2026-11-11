package reviewagent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class GoWorkspace {
    record Main(String root, String manifest, GoModuleFile file) { }
    final LanguageModule.Repository repository;
    final String revision;
    final Set<String> paths;
    final List<Main> mains;
    final String workPath;
    final GoModuleFile work;
    final Main current;

    private GoWorkspace(LanguageModule.Repository repository, String revision, Set<String> paths, List<Main> mains,
                        String workPath, GoModuleFile work, Main current) {
        this.repository = repository; this.revision = revision; this.paths = paths; this.mains = mains;
        this.workPath = workPath; this.work = work; this.current = current;
    }

    static GoWorkspace open(LanguageModule.Repository repository, String revision, String context) throws IOException, InterruptedException {
        if (!GoPackageEvidence.safeRelative(context)) throw new IOException("Expected a tracked Go context file inside the repository");
        Set<String> paths = Set.copyOf(repository.paths(revision));
        if (!paths.contains(context)) throw new IOException("Go context file is not a regular tracked file at the selected revision");
        String manifest = nearest(paths, context, "go.mod");
        if (manifest == null) throw new IOException("No pinned go.mod for this file; GOPATH-mode dependency resolution is unavailable");
        String workPath = nearest(paths, context, "go.work");
        GoModuleFile work = workPath == null ? null : GoModuleFile.parse(repository.read(revision, workPath, 256_000), workPath);
        var manifests = new java.util.LinkedHashSet<String>();
        if (work == null) manifests.add(manifest);
        else for (String use : work.uses()) manifests.add(GoModuleFile.join(GoModuleFile.relative(workPath, use), "go.mod"));
        if (!manifests.contains(manifest)) throw new IOException("The context module is not listed in the pinned go.work file");
        var mains = new ArrayList<Main>();
        var names = new java.util.HashSet<String>();
        Main current = null;
        for (String file : manifests) {
            GoModuleFile parsed = GoModuleFile.parse(repository.read(revision, file, 256_000), file);
            if (parsed.module().isEmpty() || !names.add(parsed.module())) throw new IOException("Missing or repeated workspace module path");
            Main main = new Main(GoModuleFile.directory(file), file, parsed);
            mains.add(main);
            if (file.equals(manifest)) current = main;
        }
        return new GoWorkspace(repository, revision, paths, List.copyOf(mains), workPath, work, current);
    }

    static String nearest(Set<String> paths, String context, String name) {
        String directory = GoModuleFile.directory(context);
        while (true) {
            String candidate = GoModuleFile.join(directory, name);
            if (paths.contains(candidate)) return candidate;
            if (directory.isEmpty()) return null;
            directory = GoModuleFile.directory(directory);
        }
    }

    GoModuleFile.Replacement replacement(String module, String version) throws IOException {
        if (work != null) {
            var found = choose(work.replacements(), module, version);
            if (found != null) return found;
        }
        GoModuleFile.Replacement selected = null;
        for (Main main : mains) {
            var candidate = choose(main.file().replacements(), module, version);
            if (candidate != null) {
                if (selected != null && !effect(selected).equals(effect(candidate))) throw new IOException("Conflicting workspace replacements for " + module);
                selected = candidate;
            }
        }
        return selected;
    }

    private static GoModuleFile.Replacement choose(List<GoModuleFile.Replacement> replacements, String module, String version) throws IOException {
        var matches = replacements.stream().filter(replacement -> replacement.matches(module, version)).toList();
        var exact = matches.stream().filter(replacement -> !replacement.version().isEmpty()).toList();
        List<GoModuleFile.Replacement> selected = exact.isEmpty() ? matches : exact;
        if (selected.size() > 1) throw new IOException("Repeated matching Go replacement");
        return selected.isEmpty() ? null : selected.getFirst();
    }

    private static String effect(GoModuleFile.Replacement replacement) throws IOException {
        return replacement.local() ? GoModuleFile.relative(replacement.origin(), replacement.target())
                : replacement.target() + "@" + replacement.targetVersion();
    }

    boolean excluded(String module, String version) {
        return mains.stream().anyMatch(main -> main.file().excludes().contains(module + "@" + version));
    }

    String vendorManifest() throws IOException {
        if (workPath != null) {
            String vendor = GoModuleFile.join(GoModuleFile.directory(workPath), "vendor/modules.txt");
            if (paths.contains(vendor)) {
                String[] version = work.goVersion().split("\\.");
                if (Integer.parseInt(version[0]) == 1 && Integer.parseInt(version[1]) < 22)
                    throw new IOException("Workspace vendoring requires go 1.22 or newer; build mode is unresolved");
            }
            return paths.contains(vendor) ? vendor : null;
        }
        String vendor = GoModuleFile.join(current.root(), "vendor/modules.txt");
        String[] go = current.file().goVersion().split("\\.");
        boolean automatic = Integer.parseInt(go[0]) > 1 || Integer.parseInt(go[1]) >= 14;
        return automatic && paths.contains(vendor) ? vendor : null;
    }
}
