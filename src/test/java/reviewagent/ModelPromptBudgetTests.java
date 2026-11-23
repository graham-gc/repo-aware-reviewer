package reviewagent;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

final class ModelPromptBudgetTests {
    static void run() throws Exception {
        var cache = Files.createTempDirectory("review-model-budget");
        try {
            var now = Clock.fixed(Instant.parse("2026-11-23T01:00:00Z"), ZoneOffset.UTC);
            var route = Map.of("ANTHROPIC_BASE_URL", "https://gateway.example/v1",
                    "ANTHROPIC_MODEL", "default-model", "ANTHROPIC_AUTH_TOKEN", "fixture-token");
            var command = List.of("claude", "-p", "--model", "review-model");
            var requests = new AtomicInteger();
            ModelPromptBudget.MetadataClient metadata = (uri, header, secret) -> {
                requests.incrementAndGet();
                check(uri.toString().equals("https://gateway.example/v1/models/review-model"), "use the actual model and gateway");
                check(header.equals("Authorization") && secret.equals("Bearer fixture-token"), "use the configured route credentials");
                return """
                        {"type":"model","id":"review-model","max_input_tokens":524288,"max_tokens":32768,"max_concurrent_requests":6}
                        """;
            };
            var fresh = new ModelPromptBudget(now, cache, route, metadata).resolve(command, 30_000);
            check(fresh.source() == ModelPromptBudget.Source.DISCOVERED && fresh.bytes() == 499_712
                    && fresh.capabilities().inputTokens() == 524_288 && fresh.capabilities().maxConcurrentRequests() == 6
                    && requests.get() == 1,
                    "confirmed model limits take priority over explicit fallback");
            var cached = new ModelPromptBudget(now, cache, route, metadata).resolve(command, 20_000);
            check(cached.source() == ModelPromptBudget.Source.CACHED && cached.bytes() == fresh.bytes()
                    && cached.capabilities().maxConcurrentRequests() == 6 && requests.get() == 1,
                    "a fresh process reuses limits for the same endpoint and model");
            var later = Clock.offset(now, Duration.ofHours(25));
            check(new ModelPromptBudget(later, cache, route, metadata).resolve(command, null).source()
                    == ModelPromptBudget.Source.DISCOVERED && requests.get() == 2, "stale entries are refreshed");

            var unavailable = new ModelPromptBudget(now, cache, Map.of("ANTHROPIC_BASE_URL", "https://other.example",
                    "ANTHROPIC_MODEL", "review-model", "ANTHROPIC_AUTH_TOKEN", "fixture-token"),
                    (uri, header, secret) -> {
                        check(uri.getHost().equals("other.example"), "an identical alias at another endpoint is queried separately");
                        throw new IOException("Unavailable");
                    });
            check(unavailable.resolve(command, 30_000).equals(ModelPromptBudget.Limit.fallback(30_000)),
                    "unavailable metadata falls back to the explicitly supplied value");
            check(unavailable.resolve(command, null).equals(ModelPromptBudget.Limit.fallback(null))
                    && ModelPromptBudget.Limit.fallback(null).bytes() == 320_000, "default applies without metadata or configuration");
            check(ModelPromptBudget.parse("{\"id\":\"review-model\",\"max_input_tokens\":128000}", "review-model") == null,
                    "incomplete metadata cannot establish a request budget");
            var concurrencyOnly = ModelPromptBudget.parse("{\"id\":\"review-model\",\"max_concurrent_requests\":3}", "review-model");
            check(concurrencyOnly.maxConcurrentRequests() == 3 && !concurrencyOnly.hasPromptLimits(),
                    "the service can advertise concurrency independently of token limits");
            check(new ModelPromptBudget(now, cache, Map.of("ANTHROPIC_API_KEY", "fixture-key",
                    "ANTHROPIC_MODEL", "review-model"), metadata).resolve(command, null).source()
                    == ModelPromptBudget.Source.DEFAULT && requests.get() == 2,
                    "credentials are not sent to an endpoint inferred from an incomplete configuration");
            check(CliOptions.parse(new String[]{"review", "--repo", ".", "--max-prompt-bytes", "410000"}).maxPromptBytes() == 410_000,
                    "the explicit fallback can exceed the built-in value");
        } finally {
            try (var entries = Files.walk(cache)) {
                for (var entry : entries.sorted(Comparator.reverseOrder()).toList()) Files.delete(entry);
            }
        }
    }

    private static void check(boolean condition, String expectation) {
        if (!condition) throw new AssertionError(expectation);
    }
}
