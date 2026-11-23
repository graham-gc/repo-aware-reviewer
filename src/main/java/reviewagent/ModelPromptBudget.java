package reviewagent;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Resolve a byte planning budget from authenticated model metadata, where exposed by the configured route. */
final class ModelPromptBudget {
    private static final int DEFAULT_BYTES = 320_000;
    private static final Duration MAX_AGE = Duration.ofHours(24);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(4);
    private final Clock clock;
    private final Path cache;
    private final Map<String, String> environment;
    private final MetadataClient metadata;

    enum Source { CACHED, DISCOVERED, CONFIGURED, DEFAULT }

    record Limit(int bytes, Source source, Capabilities capabilities) {
        static Limit fallback(Integer configuredBytes) {
            return configuredBytes == null ? new Limit(DEFAULT_BYTES, Source.DEFAULT, null)
                    : new Limit(configuredBytes, Source.CONFIGURED, null);
        }
    }

    record Capabilities(int inputTokens, int outputTokens, int maxConcurrentRequests) {
        boolean hasPromptLimits() { return inputTokens > 0 && outputTokens > 0; }

        int planningBytes() {
            // Plan conservatively at one UTF-8 byte per available input token. Leave room for
            // Claude Code's own context and the result when the service shares the context window.
            int outputRoom = Math.min(outputTokens, Math.min(16_384, inputTokens / 4));
            int runtimeRoom = Math.min(8_192, inputTokens / 8);
            return inputTokens - outputRoom - runtimeRoom;
        }
    }

    ModelPromptBudget(Clock clock) {
        this(clock, cacheDirectory(), System.getenv(), http());
    }

    ModelPromptBudget(Clock clock, Path cache, Map<String, String> environment, MetadataClient metadata) {
        this.clock = clock;
        this.cache = cache;
        this.environment = environment;
        this.metadata = metadata;
    }

    @FunctionalInterface
    interface MetadataClient {
        String fetch(URI endpoint, String header, String credential) throws IOException, InterruptedException;
    }

    Limit resolve(List<String> invocation, Integer configuredBytes) throws InterruptedException {
        var fallback = Limit.fallback(configuredBytes);
        if (!Path.of(invocation.getFirst()).getFileName().toString().equals("claude")) return fallback;
        if (!resolvable(invocation)) return fallback;
        String model = model(invocation);
        if (model == null || !model.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")) return fallback;
        String base = nonBlank(environment.get("ANTHROPIC_BASE_URL"));
        // Send credentials only to the explicitly selected endpoint. An unset environment variable
        // doesn't establish where Claude Code resolves its endpoint from user or managed settings.
        if (base == null) return fallback;
        URI endpoint = metadataUri(base, model);
        if (endpoint == null) return fallback;

        String token = nonBlank(environment.get("ANTHROPIC_AUTH_TOKEN"));
        String apiKey = nonBlank(environment.get("ANTHROPIC_API_KEY"));
        // Claude Code may use stored login credentials. A metadata lookup only uses credentials
        // explicitly provided for this route rather than reading Claude Code's credential store.
        String credential = token == null ? apiKey : token;
        if (credential == null) return fallback;
        String scheme = token == null ? "x-api-key" : "Authorization";
        Path entry = cache.resolve(InputDocument.sha256(
                (endpoint + "\n" + scheme + "\n" + credential).getBytes(StandardCharsets.UTF_8)) + ".txt");
        var known = cached(entry);
        if (known != null) return withCapabilities(known, Source.CACHED, fallback);
        var discovered = query(endpoint, scheme, token == null ? apiKey : "Bearer " + token, model);
        if (discovered == null) return fallback;
        remember(entry, discovered);
        return withCapabilities(discovered, Source.DISCOVERED, fallback);
    }

    private static Limit withCapabilities(Capabilities limits, Source source, Limit fallback) {
        return limits.hasPromptLimits() ? new Limit(limits.planningBytes(), source, limits)
                : new Limit(fallback.bytes(), fallback.source(), limits);
    }

    private boolean resolvable(List<String> invocation) {
        // These modes can select an upstream or a saved session independently of the process environment.
        if (List.of("CLAUDE_CODE_USE_BEDROCK", "CLAUDE_CODE_USE_VERTEX", "CLAUDE_CODE_USE_FOUNDRY")
                .stream().anyMatch(key -> "1".equals(environment.get(key)) || "true".equalsIgnoreCase(environment.get(key))))
            return false;
        return invocation.stream().noneMatch(arg -> List.of("--settings", "--fallback-model", "--resume", "--continue",
                "-r", "-c", "--cloud", "--teleport").contains(arg.split("=", 2)[0]));
    }

    private String model(List<String> invocation) {
        String model = nonBlank(environment.get("ANTHROPIC_MODEL"));
        for (int i = 1; i < invocation.size(); i++) {
            String arg = invocation.get(i);
            if (arg.equals("--model") && i + 1 < invocation.size()) model = invocation.get(++i);
            else if (arg.startsWith("--model=")) model = arg.substring("--model=".length());
        }
        return nonBlank(model);
    }

    private Capabilities query(URI endpoint, String header, String credential, String model) throws InterruptedException {
        try {
            String body = metadata.fetch(endpoint, header, credential);
            return body == null || body.length() > 65_536 ? null : parse(body, model);
        } catch (IOException | IllegalArgumentException unavailable) {
            return null;
        }
    }

    private static MetadataClient http() {
        var client = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER).build();
        return (endpoint, header, credential) -> {
            var request = HttpRequest.newBuilder(endpoint).timeout(REQUEST_TIMEOUT)
                    .header("anthropic-version", "2023-06-01").header(header, credential).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return response.statusCode() == 200 ? response.body() : null;
        };
    }

    static Capabilities parse(String body, String model) throws IOException {
        var fields = JsonReader.object(body);
        if (!(fields.get("id") instanceof String id) || id.isBlank()) return null;
        int input = positiveInteger(fields.get("max_input_tokens"));
        int output = positiveInteger(fields.get("max_tokens"));
        if (output == 0 && id.equals(model)) output = positiveInteger(fields.get("max_output_tokens"));
        int concurrency = positiveInteger(fields.get("max_concurrent_requests"));
        return concurrency == 0 && (input == 0 || output == 0) ? null : new Capabilities(input, output, concurrency);
    }

    private Capabilities cached(Path entry) {
        try {
            if (Files.size(entry) > 128) return null;
            var fields = Files.readString(entry).strip().split(" ", -1);
            if (fields.length != 4) return null;
            long age = clock.millis() - Long.parseLong(fields[0]);
            int input = Integer.parseInt(fields[1]);
            int output = Integer.parseInt(fields[2]);
            int concurrency = Integer.parseInt(fields[3]);
            return age >= 0 && age < MAX_AGE.toMillis() && input >= 0 && output >= 0 && concurrency >= 0
                    && (concurrency > 0 || input > 0 && output > 0)
                    ? new Capabilities(input, output, concurrency) : null;
        } catch (IOException | NumberFormatException unavailable) {
            return null;
        }
    }

    private void remember(Path entry, Capabilities limits) {
        try {
            Files.createDirectories(cache);
            AtomicFiles.write(entry, clock.millis() + " " + limits.inputTokens() + " " + limits.outputTokens()
                    + " " + limits.maxConcurrentRequests() + "\n");
        } catch (IOException unavailable) {
            // Filesystems without a writable cache can still use the confirmed limits for this run.
        }
    }

    private static URI metadataUri(String base, String model) {
        try {
            URI uri = URI.create(base);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null) return null;
            String url = base.replaceFirst("/+$", "");
            if (!url.endsWith("/v1")) url += "/v1";
            return URI.create(url + "/models/" + model);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static int positiveInteger(Object value) {
        if (!(value instanceof BigDecimal number)) return 0;
        try { return Math.max(0, number.intValueExact()); }
        catch (ArithmeticException invalid) { return 0; }
    }

    private static String nonBlank(String text) { return text == null || text.isBlank() ? null : text; }

    private static Path cacheDirectory() {
        String xdg = nonBlank(System.getenv("XDG_CACHE_HOME"));
        Path root = xdg == null ? Path.of(System.getProperty("user.home"), ".cache") : Path.of(xdg);
        return root.resolve("repo-aware-reviewer").resolve("model-limits");
    }
}
