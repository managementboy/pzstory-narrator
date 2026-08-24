package de.fricke.pzstory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reproducible narrator-model grounding benchmark.
 *
 * Uses PZStory's production prompt builder and terminal reply validator. All
 * scenes are synthetic and every result is appended before the next request,
 * so an interrupted overnight run remains useful and can be resumed.
 */
public final class LocalModelBenchmark {

    private static final URI DEFAULT_ENDPOINT = URI.create(
            "http://127.0.0.1:11434/v1/chat/completions");

    private static final String COMPACT_CHARTER = """
            You are the narrator of a truthful Project Zomboid survival story.
            The game state is a CLOSED WORLD: it is the complete inventory of
            physical facts available for this page. Never add an object,
            furnishing, opening, vehicle, person, corpse, location or item that
            is absent from STATE. Never make the survivor move, touch, use,
            drink, eat, open, discover or remember anything unless CHANGE says
            that action already finished. The paused survivor does not act.

            You may invent only interior experience: mood, motive, sensory
            atmosphere and interpretation that do not imply a new physical
            fact or past event. Never invent biography, earlier encounters,
            travel history, other survivors or how the survivor arrived.
            Prefer omission over completion. Sparse state means sparse prose.

            Write in present tense, using the stated name and pronouns. Follow
            the required headings and bullet syntax exactly. Return only the
            requested page; never add commentary, footnotes or instructions.
            """;

    private record Scene(String id, String split, boolean first,
                         boolean stillStanding, String change, String notes,
                         Object state, List<String> allowedPhysical,
                         List<String> allowedActions, List<String> forbidden,
                         List<String> expectedAny, boolean forbidHistory) {}

    private record Variant(String name, boolean compact, boolean ledger,
                           boolean repair, double temperature, double topP) {}

    private record Evaluation(boolean structureValid, String structureError,
                              List<String> violations, int score) {
        boolean grounded() {
            return structureValid && violations.stream()
                    .noneMatch(v -> v.startsWith("unsupported ")
                            || v.startsWith("unplayed ")
                            || v.startsWith("false history")
                            || v.startsWith("explicitly forbidden"));
        }
    }

    private record Answer(String text, double seconds, int inputTokens,
                          int outputTokens, int thoughtTokens, int totalTokens) {}

    private static final List<Variant> ALL_VARIANTS = List.of(
            new Variant("baseline", false, false, false, 0.20, 0.90),
            new Variant("ledger", false, true, false, 0.20, 0.90),
            new Variant("ledger-cold", false, true, false, 0.05, 0.80),
            new Variant("compact-ledger", true, true, false, 0.20, 0.90),
            new Variant("compact-cold", true, true, false, 0.05, 0.80),
            new Variant("compact-repair", true, true, true, 0.05, 0.80));

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final String provider;
    private final URI endpoint;
    private final String apiKey;
    private final String credentialLabel;
    private final int maxTokens;
    private final int thinkingTokens;
    private final long minRequestIntervalMillis;
    private long lastRequestStartedNanos;
    private List<String> physicalVocabulary;
    private List<String> actionVocabulary;
    private List<String> historyVocabulary;

    private LocalModelBenchmark(String provider, URI endpoint, String apiKey,
                                int maxTokens, int thinkingTokens,
                                long minRequestIntervalMillis,
                                String credentialLabel) {
        this.provider = provider;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.credentialLabel = credentialLabel;
        this.maxTokens = maxTokens;
        this.thinkingTokens = thinkingTokens;
        this.minRequestIntervalMillis = minRequestIntervalMillis;
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> cli = arguments(args);
        Path scenesPath = Path.of(required(cli, "scenes"));
        Path output = Path.of(required(cli, "out"));
        String model = cli.getOrDefault("model", "pzstory-stheno:latest");
        String provider = cli.getOrDefault("provider", "openai-compatible");
        URI endpoint = URI.create(cli.getOrDefault(
                "endpoint", DEFAULT_ENDPOINT.toString()));
        String keyEnv = cli.getOrDefault("api-key-env", "");
        String apiKey = keyEnv.isBlank() ? "" : System.getenv(keyEnv);
        String credentialLabel = cli.getOrDefault("credential-label", "");
        if (!keyEnv.isBlank() && (apiKey == null || apiKey.isBlank())) {
            throw new IllegalArgumentException("environment variable " + keyEnv
                    + " is missing or empty");
        }
        int maxTokens = Integer.parseInt(cli.getOrDefault("max-tokens", "900"));
        int thinkingTokens = Integer.parseInt(
                cli.getOrDefault("thinking-tokens", "0"));
        long minRequestIntervalMillis = Long.parseLong(
                cli.getOrDefault("min-request-interval-ms", "0"));
        if (!List.of("openai-compatible", "gemini").contains(provider)) {
            throw new IllegalArgumentException("unsupported provider " + provider);
        }
        if (maxTokens < 1 || maxTokens > 32000
                || thinkingTokens < 0 || thinkingTokens > 24000
                || minRequestIntervalMillis < 0
                || minRequestIntervalMillis > 300000) {
            throw new IllegalArgumentException("invalid token limit");
        }
        if ("gemini".equals(provider) && apiKey.isBlank()) {
            throw new IllegalArgumentException("Gemini requires --api-key-env");
        }
        String split = cli.getOrDefault("split", "all");
        List<String> sceneFilter = List.of(
                cli.getOrDefault("scene", "all").split(","));
        int repetitions = Integer.parseInt(cli.getOrDefault("repetitions", "1"));
        long seedBase = Long.parseLong(cli.getOrDefault("seed", "240826"));
        OffsetDateTime deadline = cli.containsKey("deadline")
                ? OffsetDateTime.parse(cli.get("deadline")) : null;
        List<String> wantedVariants = List.of(
                cli.getOrDefault("variants", "baseline").split(","));

        LocalModelBenchmark benchmark = new LocalModelBenchmark(
                provider, endpoint, apiKey == null ? "" : apiKey,
                maxTokens, thinkingTokens, minRequestIntervalMillis,
                credentialLabel);
        List<Scene> scenes = benchmark.loadScenes(scenesPath);
        List<Variant> variants = ALL_VARIANTS.stream()
                .filter(v -> wantedVariants.contains(v.name())).toList();
        if (variants.size() != wantedVariants.size()) {
            throw new IllegalArgumentException("unknown variant in " + wantedVariants);
        }

        Files.createDirectories(output);
        Path results = output.resolve("results.jsonl");
        Path summary = output.resolve("summary.md");
        benchmark.writeMetadata(output, model, scenesPath, variants,
                repetitions, seedBase, deadline);

        List<Map<String, Object>> rows = benchmark.loadExisting(results);
        Set<String> completed = new HashSet<>();
        for (Map<String, Object> row : rows) completed.add(caseKey(row));
        if (!rows.isEmpty()) {
            System.out.println("resuming after " + rows.size() + " completed case(s)");
        }
        int requestNumber = 0;
        outer:
        for (Variant variant : variants) {
            for (Scene scene : scenes) {
                if (!"all".equals(split) && !split.equals(scene.split())) continue;
                if (!sceneFilter.contains("all") && !sceneFilter.contains(scene.id())) continue;
                for (int repetition = 0; repetition < repetitions; repetition++) {
                    long seed = seedBase + requestNumber++;
                    String key = caseKey(variant.name(), scene.id(), repetition);
                    if (completed.contains(key)) continue;
                    if (deadline != null && OffsetDateTime.now().isAfter(deadline)) {
                        System.out.println("deadline reached before next case");
                        break outer;
                    }
                    Map<String, Object> row = benchmark.run(
                            model, variant, scene, repetition, seed);
                    rows.add(row);
                    completed.add(key);
                    Files.writeString(results, Json.of(row) + System.lineSeparator(),
                            StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND);
                    System.out.printf(Locale.ROOT,
                            "%s %-22s rep=%d score=%d grounded=%s seconds=%.1f%n",
                            variant.name(), scene.id(), repetition,
                            ((Number) row.get("score")).intValue(),
                            row.get("grounded"),
                            ((Number) row.get("seconds")).doubleValue());
                }
            }
        }
        benchmark.writeSummary(summary, rows, model, split);
        System.out.println("results=" + results);
        System.out.println("summary=" + summary);
    }

    private Map<String, Object> run(String model, Variant variant, Scene scene,
                                    int repetition, long seed) throws Exception {
        String state = Json.of(scene.state());
        String system = variant.compact()
                ? COMPACT_CHARTER
                : Prompt.CHARTER + "\n\n" + Prompt.tone()
                        + "\n\n" + World.RULES + "\n\n" + World.KNOX;
        String user = Prompt.userTurn(state, scene.notes(), scene.change(),
                scene.first(), scene.stillStanding());
        if (variant.ledger()) user += ledger(scene);

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(message("system", system));
        messages.add(message("user", user));
        Answer first = call(model, messages, variant, seed);
        Evaluation evaluation = evaluate(first.text(), scene);
        String finalText = first.text();
        double seconds = first.seconds();
        int inputTokens = first.inputTokens();
        int outputTokens = first.outputTokens();
        int thoughtTokens = first.thoughtTokens();
        int totalTokens = first.totalTokens();
        boolean repaired = false;

        if (variant.repair() && (!evaluation.structureValid()
                || !evaluation.violations().isEmpty())) {
            messages.add(message("assistant", first.text()));
            messages.add(message("user", repairInstruction(evaluation, scene)));
            Answer second = call(model, messages, variant, seed + 10_000_000L);
            finalText = second.text();
            seconds += second.seconds();
            inputTokens += second.inputTokens();
            outputTokens += second.outputTokens();
            thoughtTokens += second.thoughtTokens();
            totalTokens += second.totalTokens();
            evaluation = evaluate(finalText, scene);
            repaired = true;
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("timestamp", OffsetDateTime.now().toString());
        row.put("model", model);
        if (!credentialLabel.isBlank()) {
            row.put("credentialLabel", credentialLabel);
        }
        row.put("variant", variant.name());
        row.put("scene", scene.id());
        row.put("split", scene.split());
        row.put("repetition", repetition);
        row.put("seed", seed);
        row.put("temperature", variant.temperature());
        row.put("topP", variant.topP());
        row.put("firstPage", scene.first());
        row.put("promptChars", system.length() + user.length());
        row.put("seconds", seconds);
        row.put("inputTokens", inputTokens);
        row.put("outputTokens", outputTokens);
        row.put("thoughtTokens", thoughtTokens);
        row.put("totalTokens", totalTokens);
        row.put("repaired", repaired);
        row.put("structureValid", evaluation.structureValid());
        row.put("structureError", evaluation.structureError());
        row.put("violations", evaluation.violations());
        row.put("grounded", evaluation.grounded());
        row.put("score", evaluation.score());
        row.put("reply", finalText);
        return row;
    }

    private Answer call(String model, List<Map<String, String>> messages,
                        Variant variant, long seed) throws Exception {
        return "gemini".equals(provider)
                ? callGemini(model, messages, variant, seed)
                : callOpenAi(model, messages, variant, seed);
    }

    private Answer callOpenAi(String model, List<Map<String, String>> messages,
                              Variant variant, long seed) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("stream", false);
        request.put("max_tokens", maxTokens);
        request.put("temperature", variant.temperature());
        request.put("top_p", variant.topP());
        request.put("seed", seed);
        request.put("messages", messages);
        long began = System.nanoTime();
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(endpoint).timeout(Duration.ofMinutes(4))
                .header("Content-Type", "application/json");
        if (!apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        HttpResponse<String> response = http.send(builder
                .POST(HttpRequest.BodyPublishers.ofString(Json.of(request)))
                .build(), HttpResponse.BodyHandlers.ofString());
        double seconds = (System.nanoTime() - began) / 1_000_000_000.0;
        if (response.statusCode() != 200) {
            throw new IllegalStateException("provider HTTP " + response.statusCode()
                    + ": " + response.body());
        }
        Map<String, Object> root = JsonParse.parseObject(response.body());
        List<Object> choices = objects(root.get("choices"));
        if (choices == null || choices.isEmpty()
                || !(choices.get(0) instanceof Map<?, ?> rawChoice)) {
            throw new IllegalStateException("Ollama response had no choices");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> choice = (Map<String, Object>) rawChoice;
        Map<String, Object> message = JsonParse.map(choice, "message");
        String content = message == null ? null
                : JsonParse.str(message, "content", null);
        if (content == null) throw new IllegalStateException("no assistant content");
        Map<String, Object> usage = JsonParse.map(root, "usage");
        int input = number(usage, "prompt_tokens");
        int output = number(usage, "completion_tokens");
        int total = number(usage, "total_tokens");
        return new Answer(content, seconds, input, output, 0, total);
    }

    private Answer callGemini(String model, List<Map<String, String>> messages,
                              Variant variant, long seed) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        List<Map<String, Object>> contents = new ArrayList<>();
        for (Map<String, String> message : messages) {
            String role = message.get("role");
            String content = message.get("content");
            if ("system".equals(role)) {
                request.put("systemInstruction", Map.of(
                        "parts", List.of(Map.of("text", content))));
            } else {
                contents.add(Map.of(
                        "role", "assistant".equals(role) ? "model" : "user",
                        "parts", List.of(Map.of("text", content))));
            }
        }
        request.put("contents", contents);
        Map<String, Object> generation = new LinkedHashMap<>();
        generation.put("maxOutputTokens", Math.min(32000,
                maxTokens + thinkingTokens));
        generation.put("temperature", variant.temperature());
        generation.put("topP", variant.topP());
        generation.put("seed", seed);
        generation.put("thinkingConfig", Map.of(
                "thinkingBudget", thinkingTokens,
                "includeThoughts", false));
        request.put("generationConfig", generation);

        String base = endpoint.toString().replaceFirst("/+$", "");
        URI requestUri = URI.create(base + "/models/"
                + Endpoint.encodeSegment(model) + ":generateContent");
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(requestUri).timeout(Duration.ofMinutes(4))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(Json.of(request)))
                .build();
        long began = System.nanoTime();
        HttpResponse<String> response = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            paceRequests();
            response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 429
                    || response.body().contains("GenerateRequestsPerDay")
                    || attempt == 5) break;
            int waitSeconds = retrySeconds(response);
            System.out.println("Gemini quota pause: " + waitSeconds + "s");
            Thread.sleep(waitSeconds * 1000L);
        }
        double seconds = (System.nanoTime() - began) / 1_000_000_000.0;
        if (response == null) throw new IllegalStateException("no Gemini response");
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Gemini HTTP " + response.statusCode()
                    + ": " + response.body());
        }
        Map<String, Object> root = JsonParse.parseObject(response.body());
        List<Object> candidates = objects(root.get("candidates"));
        if (candidates.isEmpty() || !(candidates.get(0) instanceof Map<?, ?> raw)) {
            throw new IllegalStateException("Gemini response had no candidates");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> candidate = (Map<String, Object>) raw;
        Map<String, Object> candidateContent = JsonParse.map(candidate, "content");
        StringBuilder text = new StringBuilder();
        if (candidateContent != null) {
            for (Object item : objects(candidateContent.get("parts"))) {
                if (item instanceof Map<?, ?> rawPart) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> part = (Map<String, Object>) rawPart;
                    if (!Boolean.TRUE.equals(part.get("thought"))) {
                        String value = JsonParse.str(part, "text", "");
                        text.append(value);
                    }
                }
            }
        }
        if (text.isEmpty()) throw new IllegalStateException("no Gemini text content");
        Map<String, Object> usage = JsonParse.map(root, "usageMetadata");
        int input = number(usage, "promptTokenCount");
        int output = number(usage, "candidatesTokenCount");
        int thoughts = number(usage, "thoughtsTokenCount");
        int total = number(usage, "totalTokenCount");
        return new Answer(text.toString(), seconds, input, output, thoughts, total);
    }

    private Evaluation evaluate(String answer, Scene scene) {
        boolean structure = true;
        String structureError = "";
        try {
            PageResult.parse(answer, scene.first(), Settings.words());
        } catch (PageResult.Invalid invalid) {
            structure = false;
            structureError = invalid.getMessage();
        }

        List<String> violations = new ArrayList<>();
        for (String term : physicalVocabulary) {
            if (contains(answer, term) && !scene.allowedPhysical().contains(term)) {
                violations.add("unsupported physical fact: " + term);
            }
        }
        for (String action : actionVocabulary) {
            if (containsAction(answer, action)
                    && !scene.allowedActions().contains(action)) {
                violations.add("unplayed survivor action: " + action);
            }
        }
        if (scene.forbidHistory()) {
            for (String history : historyVocabulary) {
                if (contains(answer, history)) {
                    violations.add("false history: " + history);
                }
            }
        }
        for (String term : scene.forbidden()) {
            if (contains(answer, term)) {
                violations.add("explicitly forbidden: " + term);
            }
        }
        if (!scene.expectedAny().isEmpty()
                && scene.expectedAny().stream().noneMatch(t -> contains(answer, t))) {
            violations.add("missed expected focus: " + scene.expectedAny());
        }

        int score = 100;
        if (!structure) score -= 40;
        for (String violation : violations) {
            if (violation.startsWith("missed expected")) score -= 10;
            else score -= 15;
        }
        return new Evaluation(structure, structureError, List.copyOf(violations),
                Math.max(0, score));
    }

    private static String ledger(Scene scene) {
        String physical = scene.allowedPhysical().isEmpty()
                ? "none" : String.join(", ", scene.allowedPhysical());
        String actions = scene.allowedActions().isEmpty()
                ? "none" : String.join(", ", scene.allowedActions());
        String first = scene.first() ? "### PREMISE\n60-100 words\n" : "";
        return """

                ### CLOSED-WORLD GROUNDING LEDGER
                This ledger is exhaustive, not illustrative.
                Allowed named physical things: %s
                Completed survivor actions that may be described: %s
                Past history permitted beyond CHANGE: %s
                Do not name any other physical thing. Do not make the survivor
                perform a new action. Check every sentence against this ledger
                before returning the required headings and nothing after TODO.

                OUTPUT TEMPLATE -- headings are literal and alone on their line:
                %s### TITLE
                Three or four words
                ### PAGE
                40-300 words of prose
                ### CANON
                - [kind] one fact, or leave this section empty
                ### TODO
                - one unfinished intention, or leave this section empty
                Never put a title, annotation or parentheses on a heading line.
                Every non-empty CANON or TODO line starts with a hyphen. Return
                the complete template and no text after the TODO content.
                """.formatted(physical, actions,
                        scene.forbidHistory() ? "none" : "only what STATE or CHANGE says",
                        first);

    }

    private static String repairInstruction(Evaluation evaluation, Scene scene) {
        String problems = evaluation.structureValid() ? ""
                : "Structure: " + evaluation.structureError() + "\n";
        if (!evaluation.violations().isEmpty()) {
            problems += "Violations:\n- "
                    + String.join("\n- ", evaluation.violations()) + "\n";
        }
        return """
                Your reply was rejected and will not be saved.
                %s
                Rewrite the COMPLETE reply from the first required heading.
                Remove every unsupported claim; do not explain the correction.
                Use only this exhaustive ledger:%s
                Return nothing after the TODO section.
                """.formatted(problems, ledger(scene));
    }

    @SuppressWarnings("unchecked")
    private List<Scene> loadScenes(Path path) throws Exception {
        Map<String, Object> root = JsonParse.parseObject(Files.readString(path));
        physicalVocabulary = strings(root.get("physicalVocabulary"));
        actionVocabulary = strings(root.get("actionVocabulary"));
        historyVocabulary = strings(root.get("historyVocabulary"));
        List<Scene> out = new ArrayList<>();
        for (Object item : objects(root.get("scenes"))) {
            Map<String, Object> m = (Map<String, Object>) item;
            out.add(new Scene(
                    JsonParse.str(m, "id", ""), JsonParse.str(m, "split", ""),
                    bool(m, "first"), bool(m, "stillStanding"),
                    value(m, "change"), value(m, "notes"), m.get("state"),
                    strings(m.get("allowedPhysical")),
                    strings(m.get("allowedActions")),
                    strings(m.get("forbidden")),
                    strings(m.get("expectedAny")), bool(m, "forbidHistory")));
        }
        return out;
    }

    private void writeMetadata(Path output, String model, Path scenes,
                               List<Variant> variants, int repetitions,
                               long seed, OffsetDateTime deadline) throws Exception {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("started", OffsetDateTime.now().toString());
        meta.put("provider", provider);
        meta.put("endpoint", endpoint.toString());
        if (!credentialLabel.isBlank()) {
            meta.put("credentialLabel", credentialLabel);
        }
        meta.put("model", model);
        meta.put("modelDigest", "openai-compatible".equals(provider)
                ? modelDigest(model) : "managed-online-model");
        meta.put("maxTokens", maxTokens);
        meta.put("thinkingTokens", thinkingTokens);
        meta.put("minRequestIntervalMillis", minRequestIntervalMillis);
        meta.put("scenes", scenes.toAbsolutePath().toString());
        meta.put("scenesSha256", sha256(Files.readAllBytes(scenes)));
        meta.put("variants", variants.stream().map(Variant::name).toList());
        meta.put("repetitions", repetitions);
        meta.put("seedBase", seed);
        meta.put("deadline", deadline == null ? "" : deadline.toString());
        meta.put("pZStoryRelease", Version.RELEASE);
        meta.put("bridgeApi", Version.API);
        Files.writeString(output.resolve("metadata.json"), Json.of(meta) + "\n");
    }

    private List<Map<String, Object>> loadExisting(Path results) throws Exception {
        if (!Files.isRegularFile(results)) return new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String line : Files.readAllLines(results, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) rows.add(JsonParse.parseObject(line));
        }
        return rows;
    }

    @SuppressWarnings("unchecked")
    private String modelDigest(String model) {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:11434/api/tags"))
                    .timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            Map<String, Object> root = JsonParse.parseObject(response.body());
            for (Object item : objects(root.get("models"))) {
                Map<String, Object> m = (Map<String, Object>) item;
                if (model.equals(JsonParse.str(m, "name", ""))) {
                    return JsonParse.str(m, "digest", "unknown");
                }
            }
        } catch (Exception ignored) {}
        return "unknown";
    }

    private void writeSummary(Path path, List<Map<String, Object>> rows,
                              String model, String split) throws Exception {
        StringBuilder out = new StringBuilder();
        out.append("# Narrator-model benchmark summary\n\n")
                .append("Provider: `").append(provider).append("`  \n")
                .append("Model: `").append(model).append("`  \n")
                .append("Split: `").append(split).append("`  \n")
                .append("Completed: ").append(rows.size()).append(" cases\n\n")
                .append("| Variant | Cases | Structure | Grounded | Mean score | Mean seconds |\n")
                .append("|---|---:|---:|---:|---:|---:|\n");
        for (Variant variant : ALL_VARIANTS) {
            List<Map<String, Object>> group = rows.stream()
                    .filter(r -> variant.name().equals(r.get("variant"))).toList();
            if (group.isEmpty()) continue;
            long structure = group.stream().filter(r -> Boolean.TRUE.equals(
                    r.get("structureValid"))).count();
            long grounded = group.stream().filter(r -> Boolean.TRUE.equals(
                    r.get("grounded"))).count();
            double score = group.stream().mapToDouble(r ->
                    ((Number) r.get("score")).doubleValue()).average().orElse(0);
            double seconds = group.stream().mapToDouble(r ->
                    ((Number) r.get("seconds")).doubleValue()).average().orElse(0);
            out.append(String.format(Locale.ROOT,
                    "| %s | %d | %.1f%% | %.1f%% | %.1f | %.1f |%n",
                    variant.name(), group.size(), 100.0 * structure / group.size(),
                    100.0 * grounded / group.size(), score, seconds));
        }
        Files.writeString(path, out.toString(), StandardCharsets.UTF_8);
    }

    private static int number(Map<String, Object> values, String key) {
        if (values == null) return 0;
        Object value = values.get(key);
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static String caseKey(Map<String, Object> row) {
        return caseKey(String.valueOf(row.get("variant")),
                String.valueOf(row.get("scene")),
                ((Number) row.get("repetition")).intValue());
    }

    private static String caseKey(String variant, String scene, int repetition) {
        return variant + "\n" + scene + "\n" + repetition;
    }

    private static int retrySeconds(HttpResponse<String> response) {
        String header = response.headers().firstValue("Retry-After").orElse("");
        try {
            return Math.max(15, Math.min(90, Integer.parseInt(header) + 15));
        } catch (NumberFormatException ignored) {}
        Matcher match = Pattern.compile("\\\"retryDelay\\\"\\s*:\\s*\\\"(\\d+)s\\\"")
                .matcher(response.body());
        if (match.find()) {
            return Math.max(15, Math.min(90,
                    Integer.parseInt(match.group(1)) + 15));
        }
        return 75;
    }

    private synchronized void paceRequests() throws InterruptedException {
        if (minRequestIntervalMillis == 0 || lastRequestStartedNanos == 0) {
            lastRequestStartedNanos = System.nanoTime();
            return;
        }
        long intervalNanos = minRequestIntervalMillis * 1_000_000L;
        long remaining = lastRequestStartedNanos + intervalNanos - System.nanoTime();
        if (remaining > 0) {
            long millis = (remaining + 999_999L) / 1_000_000L;
            Thread.sleep(millis);
        }
        lastRequestStartedNanos = System.nanoTime();
    }

    private static boolean contains(String text, String phrase) {
        return Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])"
                + Pattern.quote(phrase) + "(?![\\p{L}\\p{N}])")
                .matcher(text).find();
    }

    /** Action fixtures use neutral "they" but replies must be checked for all pronouns. */
    private static boolean containsAction(String text, String phrase) {
        if (!phrase.startsWith("they ")) return contains(text, phrase);
        if (phrase.startsWith("they have ")) {
            String predicate = phrase.substring("they have ".length());
            return Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])"
                    + "(?:they\\s+have|he\\s+has|she\\s+has)\\s+"
                    + Pattern.quote(predicate) + "(?![\\p{L}\\p{N}])")
                    .matcher(text).find();
        }
        String predicate = phrase.substring("they ".length());
        return Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])(?:they|he|she)\\s+"
                + Pattern.quote(predicate) + "(?![\\p{L}\\p{N}])")
                .matcher(text).find();
    }

    private static Map<String, String> message(String role, String content) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    private static String value(Map<String, Object> m, String key) {
        String value = JsonParse.str(m, key, null);
        return value == null ? "" : value;
    }

    private static boolean bool(Map<String, Object> m, String key) {
        return Boolean.TRUE.equals(m.get(key));
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(String.class::isInstance)
                .map(String.class::cast).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> objects(Object value) {
        return value instanceof List<?> list
                ? (List<Object>) list : List.of();
    }

    private static Map<String, String> arguments(String[] args) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 >= args.length || !args[i].startsWith("--")) {
                throw new IllegalArgumentException("arguments are --name value pairs");
            }
            out.put(args[i].substring(2), args[i + 1]);
        }
        return out;
    }

    private static String required(Map<String, String> args, String key) {
        String value = args.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing --" + key);
        }
        return value;
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder out = new StringBuilder();
        for (byte b : hash) out.append(String.format("%02x", b));
        return out.toString();
    }
}
