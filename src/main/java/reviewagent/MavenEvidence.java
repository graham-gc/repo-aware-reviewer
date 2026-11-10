package reviewagent;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

final class MavenEvidence {
    private static final Pattern COORDINATE_PART = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]*");
    private static final Pattern JAVA_TYPE = Pattern.compile("(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)+[A-Za-z_$][A-Za-z0-9_$]*");
    private static final Pattern PROTO_ENTRY = Pattern.compile("(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_.-]+\\.proto");

    private final GitClient git;
    private final ProcessExecutor executor;
    private final Path cache;

    MavenEvidence(GitClient git, ProcessExecutor executor, Path cache) {
        this.git = git;
        this.executor = executor;
        this.cache = cache;
    }

    String load(String revision, String coordinate, String symbol, int maxBytes)
            throws IOException, InterruptedException {
        String[] parts = coordinate.split(":", -1);
        if (parts.length != 3 || List.of(parts).stream().anyMatch(p -> !COORDINATE_PART.matcher(p).matches()
                || p.contains("..") || p.endsWith("."))
                || parts[2].toUpperCase(Locale.ROOT).endsWith("-SNAPSHOT")) {
            throw new IOException("Expected exact, non-SNAPSHOT Maven coordinates");
        }
        boolean proto = PROTO_ENTRY.matcher(symbol).matches() && !symbol.contains("..");
        if (!proto && !JAVA_TYPE.matcher(symbol).matches()) {
            throw new IOException("Expected a fully qualified Java type or a .proto archive entry");
        }
        String pom = git.regularBlobAt(revision, "pom.xml", 256_000);
        if (!declaredDependency(pom, parts)) {
            throw new IOException("Coordinates are not an exact direct JAR dependency in the root POM at that revision");
        }

        if (!Files.isDirectory(cache)) {
            throw new IOException("Local Maven cache is unavailable");
        }
        Path cacheRoot = cache.toRealPath();
        Path jar = cacheRoot.resolve(parts[0].replace('.', '/')).resolve(parts[1]).resolve(parts[2])
                .resolve(parts[1] + "-" + parts[2] + ".jar");
        if (!Files.isRegularFile(jar)) {
            throw new IOException("Exact artifact is not in the local Maven cache");
        }
        jar = jar.toRealPath();
        if (!jar.startsWith(cacheRoot)) {
            throw new IOException("Artifact resolves outside the local Maven cache");
        }

        String entryName = proto ? symbol : symbol.replace('.', '/') + ".class";
        String content;
        try (ZipFile archive = new ZipFile(jar.toFile())) {
            var entry = archive.getEntry(entryName);
            if (entry == null || entry.isDirectory()) {
                throw new IOException("Requested symbol is absent from the exact cached artifact");
            }
            if (proto) {
                try (var input = archive.getInputStream(entry)) {
                    byte[] bytes = input.readNBytes(maxBytes + 1);
                    if (bytes.length > maxBytes) {
                        throw new IOException("Proto definition exceeds the remaining evidence budget");
                    }
                    content = new String(bytes, StandardCharsets.UTF_8);
                }
            } else {
                if (entry.getSize() > 1_000_000) {
                    throw new IOException("Class file exceeds the inspection size limit");
                }
                content = signatures(cacheRoot, jar, entryName);
            }
        }
        return EvidenceText.block(coordinate + " · " + entryName + " · POM at " + revision
                + " · JAR SHA-256 " + sha256(jar), proto ? "proto" : "java", content);
    }

    private String signatures(Path directory, Path jar, String entry) throws IOException, InterruptedException {
        Path javap = Path.of(System.getProperty("java.home"), "bin", "javap");
        if (!Files.isExecutable(javap)) {
            javap = javap.resolveSibling("javap.exe");
        }
        if (!Files.isExecutable(javap)) {
            throw new IOException("JDK javap is unavailable");
        }
        String location = "jar:" + jar.toUri().toASCIIString() + "!/" + entry;
        CommandResult result = executor.run(List.of(javap.toString(), "-public", location),
                directory, null, Duration.ofSeconds(15));
        if (result.exitCode() != 0 || result.stdout().isBlank()) {
            throw new IOException("Could not inspect the class signature with javap");
        }
        return result.stdout();
    }

    private static boolean declaredDependency(String pom, String[] coordinate) throws IOException {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var parser = factory.newDocumentBuilder();
            parser.setErrorHandler(new DefaultHandler());
            Element project = parser.parse(new InputSource(new StringReader(pom))).getDocumentElement();
            if (!"project".equals(project.getLocalName())) {
                throw new IOException("Root POM is missing its project element");
            }
            Element dependencies = child(project, "dependencies");
            if (dependencies == null) {
                return false;
            }
            Element properties = child(project, "properties");
            for (var node = dependencies.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (!(node instanceof Element dep) || !"dependency".equals(dep.getLocalName())) {
                    continue;
                }
                String version = value(dep, "version");
                if (version.matches("\\$\\{[A-Za-z0-9_.-]+}")) {
                    version = value(properties, version.substring(2, version.length() - 1));
                }
                String type = value(dep, "type");
                if (coordinate[0].equals(value(dep, "groupId")) && coordinate[1].equals(value(dep, "artifactId"))
                        && coordinate[2].equals(version) && (type.isEmpty() || "jar".equals(type))
                        && value(dep, "classifier").isEmpty() && !"system".equals(value(dep, "scope"))) {
                    return true;
                }
            }
            return false;
        } catch (ParserConfigurationException | SAXException error) {
            throw new IOException("Cannot safely parse the root POM", error);
        }
    }

    private static Element child(Element parent, String name) {
        if (parent != null) {
            for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element element && name.equals(element.getLocalName())) {
                    return element;
                }
            }
        }
        return null;
    }

    private static String value(Element parent, String name) {
        Element element = child(parent, name);
        return element == null ? "" : element.getTextContent().strip();
    }

    private static String sha256(Path jar) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = new DigestInputStream(Files.newInputStream(jar), digest)) {
                input.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
