package reviewagent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

final class GoPackageEvidence {
    interface Tree {
        List<String> files(String directory) throws IOException, InterruptedException;
        String read(String path, int maxBytes) throws IOException, InterruptedException;
        String title(String path, String source);
    }
    record Package(Tree tree, String directory, String provenance) { }

    static LanguageModule.Evidence evidence(Package pkg, String selector, int maxBytes) throws IOException, InterruptedException {
        try {
            return new LanguageModule.Evidence(render(pkg, selector, maxBytes), selector.equals("index")
                    ? "INDEX_ONLY (package navigation; implementation not supplied)" : "INCLUDED");
        } catch (IOException missing) {
            if (!selector.startsWith("symbol:") || !(missing.getMessage().contains("200 lines")
                    || missing.getMessage().contains("ambiguous") || missing.getMessage().contains("symbol is absent"))) throw missing;
            String note = "Requested Go declaration was not supplied: " + EvidenceText.oneLine(missing.getMessage())
                    + ". Navigation index supplied instead; request a specific file/range.\n\n";
            String index = render(pkg, "index", maxBytes - EvidenceText.bytes(note));
            return new LanguageModule.Evidence(bounded(note + index, maxBytes), "INDEX_ONLY (" + missing.getMessage() + ")");
        }
    }

    static Tree repository(LanguageModule.Repository repository, String revision) {
        return new Tree() {
            public List<String> files(String directory) throws IOException, InterruptedException {
                return repository.paths(revision).stream().filter(path -> GoModuleFile.directory(path).equals(directory)).sorted().toList();
            }
            public String read(String path, int maxBytes) throws IOException, InterruptedException { return repository.read(revision, path, maxBytes); }
            public String title(String path, String source) { return path + " at " + revision; }
        };
    }

    static Tree cached(Path root, String coordinate, String revision) throws IOException {
        Path canonicalRoot = root.toRealPath();
        return new Tree() {
            private Path safe(String path) throws IOException {
                Path candidate = canonicalRoot.resolve(path).normalize();
                if (!candidate.startsWith(canonicalRoot) || Files.isSymbolicLink(candidate)) throw new IOException("Go cache path escapes the module or is a symlink");
                if (!candidate.toRealPath().startsWith(canonicalRoot)) throw new IOException("Go cache path resolves outside the selected module");
                return candidate;
            }
            public List<String> files(String directory) throws IOException {
                Path folder = safe(directory);
                if (!Files.isDirectory(folder)) throw new IOException("Requested Go dependency package is not cached");
                try (var paths = Files.list(folder)) {
                    return paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                            .map(path -> canonicalRoot.relativize(path).toString().replace('\\', '/')).sorted().toList();
                }
            }
            public String read(String path, int maxBytes) throws IOException {
                Path file = safe(path);
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > maxBytes)
                    throw new IOException("Cached Go source is unavailable or exceeds the evidence read limit");
                byte[] bytes;
                try (var input = Files.newInputStream(file)) { bytes = input.readNBytes(maxBytes + 1); }
                if (bytes.length > maxBytes) throw new IOException("Cached Go source exceeds the evidence read limit");
                String text = new String(bytes, StandardCharsets.UTF_8);
                if (text.indexOf('\0') >= 0 || text.indexOf('\ufffd') >= 0) throw new IOException("Cached Go source is not valid UTF-8 text");
                return text;
            }
            public String title(String path, String source) {
                return "Go dependency " + coordinate + " · " + path + " · context at " + revision + " · SHA-256 " + sha256(source);
            }
        };
    }

    static String render(Package pkg, String selector, int maxBytes) throws IOException, InterruptedException {
        List<String> files = pkg.tree().files(pkg.directory()).stream().filter(path -> path.endsWith(".go"))
                .filter(path -> { String name = path.substring(path.lastIndexOf('/') + 1); return !name.startsWith(".") && !name.startsWith("_"); }).toList();
        String note = "\nGo source provenance: " + EvidenceText.oneLine(pkg.provenance())
                + ". Build constraints, platform suffixes and test variants have not been evaluated. No Go command was run.\n\n";
        if (selector.startsWith("file:")) {
            String[] fields = selector.substring(5).split(":", -1);
            String name = fields[0];
            if (!safeRelative(name) || name.startsWith("_") || name.contains("/") || !(name.endsWith(".go") || name.endsWith(".proto")))
                throw new IOException("Expected a .go or .proto filename; select its directory with the resource path");
            String path = GoModuleFile.join(pkg.directory(), name);
            String source = pkg.tree().read(path, 1_000_000);
            String language = name.endsWith(".proto") ? "proto" : "go";
            if (fields.length == 1) return bounded(note + EvidenceText.block(pkg.tree().title(path, source), language, source), maxBytes);
            if (fields.length != 3) throw new IOException("Expected file:name.go or file:name.go:first:last");
            return bounded(note + range(pkg.tree().title(path, source), source, fields[1], fields[2], language), maxBytes);
        }
        if (files.isEmpty()) throw new IOException("No Go source files in the requested package");
        if (selector.equals("all")) {
            StringBuilder output = new StringBuilder(note);
            for (String path : files) {
                String source = pkg.tree().read(path, Math.max(0, maxBytes - EvidenceText.bytes(output.toString())));
                output.append(EvidenceText.block(pkg.tree().title(path, source), "go", source));
                bounded(output.toString(), maxBytes);
            }
            return output.toString();
        }
        if (!selector.equals("index") && !selector.startsWith("symbol:")) throw new IOException("Expected index, symbol:Name, symbol:Receiver.Method, file:name.go[:first:last], or all");
        String wanted = selector.startsWith("symbol:") ? selector.substring(7) : "";
        var matches = new ArrayList<String>();
        var index = new StringBuilder("Package declarations (syntax only; no type checking or build selection):\n");
        int readBudget = Math.min(8_000_000, Math.max(256_000, maxBytes * 16));
        for (String path : files) {
            String source = pkg.tree().read(path, Math.min(1_000_000, readBudget));
            readBudget -= EvidenceText.bytes(source);
            if (readBudget < 0) throw new IOException("Package index read budget exhausted; request an exact file");
            GoSource.Parsed parsed = GoSource.parse(source);
            index.append(path).append(" [package ").append(parsed.packageName()).append("] ").append(parsed.constraints()).append('\n');
            for (GoSource.Declaration declaration : parsed.declarations()) {
                index.append("  ").append(declaration.kind()).append(' ').append(declaration.name()).append(" lines ")
                        .append(declaration.first()).append(':').append(declaration.last()).append('\n');
                if (!wanted.isEmpty() && (declaration.name().equals(wanted) || (!wanted.contains(".") && declaration.name().endsWith("." + wanted)))) {
                    if (declaration.last() - declaration.first() >= 200) throw new IOException("Go declaration spans more than 200 lines; request a range");
                    matches.add(range(pkg.tree().title(path, source), source,
                            Integer.toString(declaration.first()), Integer.toString(declaration.last())));
                }
            }
        }
        if (wanted.isEmpty()) return bounded(note + EvidenceText.block("Go package index — " + pkg.provenance(), "index", index.toString()), maxBytes);
        if (matches.isEmpty()) throw new IOException("Go symbol is absent from this package; request index to inspect declarations");
        if (matches.size() != 1) throw new IOException("Go symbol is ambiguous across files/build variants; request a specific file or Receiver.Method");
        return bounded(note + matches.getFirst(), maxBytes);
    }

    static String range(String title, String source, String first, String last) throws IOException {
        return range(title, source, first, last, "go");
    }

    private static String range(String title, String source, String first, String last, String language) throws IOException {
        if (!first.matches("[1-9][0-9]{0,6}") || !last.matches("[1-9][0-9]{0,6}")) throw new IOException("Invalid source line range");
        int from = Integer.parseInt(first), to = Integer.parseInt(last);
        if (to < from || to - from >= 200) throw new IOException("Requested range must contain between 1 and 200 lines");
        var lines = source.lines().skip(from - 1L).limit(to - from + 1L).toList();
        if (lines.isEmpty()) throw new IOException("Requested range is beyond the end of the file");
        return EvidenceText.block(title + " (lines " + from + "–" + (from + lines.size() - 1) + ")", language, String.join("\n", lines));
    }

    static boolean safeRelative(String path) {
        return !path.isBlank() && !path.startsWith("/") && !path.contains("\\") && !path.chars().anyMatch(Character::isISOControl)
                && java.util.Arrays.stream(path.split("/", -1)).noneMatch(part -> part.isEmpty() || part.equals(".") || part.equals("..") || part.startsWith("."));
    }
    static String bounded(String text, int maxBytes) throws IOException {
        if (EvidenceText.bytes(text) > maxBytes) throw new IOException("Requested Go evidence exceeds the remaining byte budget; request an index, symbol or smaller range");
        return text;
    }
    static String sha256(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
