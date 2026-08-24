package de.fricke.pzstory;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/** Request-shape controls must be explicit and provider-specific. */
public final class ProviderCompatibilityTest {

    public static void run() {
        T.group("Provider profiles - explicit compatibility controls");

        Config.Profile defaults = profile("openai-compatible", Map.of(
                "baseUrl", "http://127.0.0.1:11434/v1"));
        T.ok("stream usage is opt-in", !defaults.streamUsage);
        T.eq("legacy token field is the compatibility default",
                "max_tokens", defaults.openAiTokenField);
        T.eq("reasoning is off by default", 0, defaults.thinkingTokens);

        Map<String, Object> newerValues = new LinkedHashMap<>();
        newerValues.put("baseUrl", "https://api.example.test/v1");
        newerValues.put("streamUsage", true);
        newerValues.put("openAiTokenField", "max_completion_tokens");
        newerValues.put("maxTokens", 1200.0);
        Config.Profile newer = profile("openai-compatible", newerValues);
        Map<String, Object> openAi = body("openaiBody", newer);
        T.eq("selected completion field is emitted", 1200,
                JsonParse.num(openAi, "max_completion_tokens", -1));
        T.ok("unselected token field is absent", !openAi.containsKey("max_tokens"));
        Map<String, Object> streamOptions = JsonParse.map(openAi, "stream_options");
        T.ok("usage option is emitted only when selected",
                streamOptions != null && Boolean.TRUE.equals(streamOptions.get("include_usage")));

        Map<String, Object> legacyBody = body("openaiBody", defaults);
        T.ok("legacy-compatible body omits stream_options",
                !legacyBody.containsKey("stream_options"));
        T.ok("legacy-compatible body uses max_tokens", legacyBody.containsKey("max_tokens"));

        Config.Profile gemini = profile("gemini", Map.of("thinkingTokens", 512.0));
        Map<String, Object> geminiBody = body("geminiBody", gemini);
        Map<String, Object> generation = JsonParse.map(geminiBody, "generationConfig");
        Map<String, Object> thinking = generation == null
                ? null : JsonParse.map(generation, "thinkingConfig");
        T.ok("Gemini thinking budget is explicit",
                thinking != null && JsonParse.num(thinking, "thinkingBudget", -1) == 512);
        T.ok("Gemini thought summaries are explicitly excluded",
                thinking != null && Boolean.FALSE.equals(thinking.get("includeThoughts")));

        T.throwsWith("unsupported completion field is rejected", "unsupported", () ->
                profile("openai-compatible", Map.of(
                        "baseUrl", "http://127.0.0.1:11434/v1",
                        "openAiTokenField", "output_tokens")));

        T.group("Provider diagnostics - safe display boundary");
        // Assemble at runtime so the repository's secret scanner never has a
        // key-shaped literal to mistake for a committed credential.
        String secret = "s" + "k-" + "abcdefghijklmnopqrstuvwxyz";
        String display = Config.safeForDisplay("first\nsecond\u0000 " + secret
                + " " + "x".repeat(1000));
        T.ok("display errors are one physical line",
                display.indexOf('\n') < 0 && display.indexOf('\r') < 0
                        && display.indexOf('\u0000') < 0);
        T.ok("display errors redact key-shaped strings", !display.contains(secret));
        T.ok("display errors obey the exact limit", display.length() <= 800);
        T.ok("truncated display errors say so", display.endsWith("...[truncated]"));
    }

    private static Config.Profile profile(String kind, Map<String, Object> extra) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("kind", kind);
        values.put("model", "test-model");
        values.put("apiKey", "test-key");
        values.putAll(extra);
        return new Config.Profile("test", values);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(String method, Config.Profile profile) {
        try {
            Method builder = Llm.class.getDeclaredMethod(method,
                    Config.Profile.class, String.class, String.class, String.class);
            builder.setAccessible(true);
            String json = (String) builder.invoke(null, profile, "system", "history", "tail");
            return JsonParse.parseObject(json);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
