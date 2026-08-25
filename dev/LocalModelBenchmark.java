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
import java.util.LinkedHashSet;
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
                         List<String> expectedAny, List<String> expectedAll,
                         int minPageParagraphs, boolean forbidHistory) {}

    private record Variant(String name, boolean compact, boolean ledger,
                           boolean repair, boolean catalog,
                           double temperature, double topP) {}

    private record CatalogFact(String id, String sentence, boolean essential,
                               boolean selectable) {}

    private record Plan(List<String> focus, String mood, String title,
                        String todo, boolean valid, String raw) {}

    private record Evaluation(boolean structureValid, String structureError,
                              List<String> violations, int score) {
        boolean grounded() {
            return structureValid && violations.stream()
                    .noneMatch(v -> v.startsWith("unsupported ")
                            || v.startsWith("unplayed ")
                            || v.startsWith("false history")
                            || v.startsWith("explicitly forbidden")
                            || v.startsWith("production guard"));
        }
    }

    private record Answer(String text, double seconds, int inputTokens,
                          int outputTokens, int thoughtTokens, int totalTokens) {}

    private static final List<Variant> ALL_VARIANTS = List.of(
            new Variant("baseline", false, false, false, false, 0.20, 0.90),
            new Variant("ledger", false, true, false, false, 0.20, 0.90),
            new Variant("ledger-cold", false, true, false, false, 0.05, 0.80),
            new Variant("compact-ledger", true, true, false, false, 0.20, 0.90),
            new Variant("compact-cold", true, true, false, false, 0.05, 0.80),
            new Variant("compact-repair", true, true, true, false, 0.05, 0.80),
            new Variant("validated-catalog", true, false, false, true, 0.05, 0.80),
            new Variant("validated-catalog-warm", true, false, false, true, 0.80, 0.95),
            new Variant("validated-narrative", true, false, false, true, 0.15, 0.90),
            new Variant("production-safe", true, false, false, true, 0.15, 0.90));

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
        if (variant.catalog()) {
            return runValidatedCatalog(model, variant, scene, repetition, seed);
        }
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

    private Map<String, Object> runValidatedCatalog(
            String model, Variant variant, Scene scene, int repetition,
            long seed) throws Exception {
        if ("production-safe".equals(variant.name())) {
            return runProductionSafe(model, variant, scene, repetition, seed);
        }
        List<CatalogFact> facts = catalog(scene);
        String system = """
                You are a planning component, not a prose writer. Select only
                identifiers and enum values supplied by the user. Return one
                JSON object and nothing else. Never copy or invent story text.
                """;
        StringBuilder user = new StringBuilder("""
                Choose the four most narratively useful fact IDs. Also choose
                one mood, title, and todo enum. Return a JSON object with four
                keys: focus is an array of exactly four listed fact IDs; mood,
                title and todo are strings containing one allowed enum below.
                Choose according to the evidence instead of always taking the
                first option.
                mood: watchful | resolute | uncertain | restrained
                title: quiet | narrow | still | certain
                todo: certainty | composure | patience | restraint
                FACT CATALOG:
                """);
        for (CatalogFact fact : facts) {
            if (!fact.selectable()) continue;
            user.append(fact.id()).append(" | ").append(fact.sentence()).append('\n');
        }

        Answer answer = call(model,
                List.of(message("system", system), message("user", user.toString())),
                variant, seed);
        Plan plan = parsePlan(answer.text(), facts);
        String finalText = "validated-narrative".equals(variant.name())
                ? renderNarrative(scene, facts, plan, seed)
                : renderCatalog(scene, facts, plan);
        Evaluation evaluation = evaluate(finalText, scene);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("timestamp", OffsetDateTime.now().toString());
        row.put("model", model);
        if (!credentialLabel.isBlank()) row.put("credentialLabel", credentialLabel);
        row.put("variant", variant.name());
        row.put("pipeline", "validated-narrative".equals(variant.name())
                ? "validated fact catalog + controlled narrative renderer"
                : "validated fact catalog + deterministic renderer");
        row.put("scene", scene.id());
        row.put("split", scene.split());
        row.put("repetition", repetition);
        row.put("seed", seed);
        row.put("temperature", variant.temperature());
        row.put("topP", variant.topP());
        row.put("firstPage", scene.first());
        row.put("promptChars", system.length() + user.length());
        row.put("seconds", answer.seconds());
        row.put("inputTokens", answer.inputTokens());
        row.put("outputTokens", answer.outputTokens());
        row.put("thoughtTokens", answer.thoughtTokens());
        row.put("totalTokens", answer.totalTokens());
        row.put("repaired", false);
        row.put("plannerValid", plan.valid());
        row.put("plannerFocus", plan.focus());
        row.put("plannerReply", plan.raw());
        row.put("structureValid", evaluation.structureValid());
        row.put("structureError", evaluation.structureError());
        row.put("violations", evaluation.violations());
        row.put("grounded", evaluation.grounded());
        row.put("score", evaluation.score());
        row.put("reply", finalText);
        return row;
    }

    /** Exercises the actual experimental narrator used by StoryAPI. */
    private Map<String, Object> runProductionSafe(
            String model, Variant variant, Scene scene, int repetition,
            long seed) throws Exception {
        String state = Json.of(scene.state());
        ValidatedNarrator.Session session = ValidatedNarrator.prepare(
                state, List.of(), scene.change(), scene.first(), 200, seed,
                scene.notes(), "conspiracy");
        Answer answer = call(model, List.of(
                message("system", session.systemPrompt()),
                message("user", session.userPrompt())), variant, seed);
        String finalText = session.render(answer.text());
        Evaluation evaluation = evaluate(finalText, scene);
        try {
            PageResult parsed = PageResult.parse(finalText, scene.first(), 200);
            GroundingGuard.validate(parsed, state, scene.change(),
                    scene.first(), scene.stillStanding());
        } catch (PageResult.Invalid invalid) {
            List<String> faults = new ArrayList<>(evaluation.violations());
            faults.add("production guard: " + invalid.getMessage());
            evaluation = new Evaluation(evaluation.structureValid(),
                    evaluation.structureError(), List.copyOf(faults),
                    Math.max(0, evaluation.score() - 30));
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("timestamp", OffsetDateTime.now().toString());
        row.put("model", model);
        row.put("variant", variant.name());
        row.put("pipeline", "production ValidatedNarrator");
        row.put("scene", scene.id());
        row.put("split", scene.split());
        row.put("repetition", repetition);
        row.put("seed", seed);
        row.put("temperature", variant.temperature());
        row.put("topP", variant.topP());
        row.put("firstPage", scene.first());
        row.put("promptChars", session.systemPrompt().length()
                + session.userPrompt().length());
        row.put("seconds", answer.seconds());
        row.put("inputTokens", answer.inputTokens());
        row.put("outputTokens", answer.outputTokens());
        row.put("thoughtTokens", answer.thoughtTokens());
        row.put("totalTokens", answer.totalTokens());
        row.put("repaired", false);
        row.put("structureValid", evaluation.structureValid());
        row.put("structureError", evaluation.structureError());
        row.put("violations", evaluation.violations());
        row.put("grounded", evaluation.grounded());
        row.put("score", evaluation.score());
        row.put("rawPlan", answer.text());
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

    private List<CatalogFact> catalog(Scene scene) {
        Map<String, Object> state = objectMap(scene.state());
        Map<String, Object> character = JsonParse.map(state, "character");
        String name = character == null ? "The survivor"
                : JsonParse.str(character, "name", "The survivor");
        List<CatalogFact> facts = new ArrayList<>();

        Map<String, Object> time = JsonParse.map(state, "time");
        if (time != null) {
            String day = JsonParse.str(time, "date", "");
            String part = JsonParse.str(time, "timeOfDay", "");
            if (!day.isBlank() && !part.isBlank()) {
                addFact(facts, "It is " + part + " on " + day + ".");
            }
            addRecordedFact(facts, "Time survived",
                    JsonParse.str(time, "timeSurvived", ""));
            addRecordedFact(facts, "Time since it began",
                    JsonParse.str(time, "daysSinceItBegan", ""));
        }
        if (character != null) {
            addRecordedFact(facts, name + "'s occupation",
                    JsonParse.str(character, "occupation", ""));
            for (String trait : strings(character.get("traits"))) {
                addFact(facts, name + " has the recorded trait " + trait + ".");
            }
        }

        Map<String, Object> position = JsonParse.map(state, "position");
        if (position != null) {
            String place = JsonParse.str(position, "placeType", "");
            if (!place.isBlank() && safeLocationPhrase(place)) {
                addFact(facts, name + " is currently in " + place + ".");
            }
            addRecordedFact(facts, "The recorded floor",
                    JsonParse.str(position, "floor", ""));
            addRecordedFact(facts, "The recorded familiarity",
                    JsonParse.str(position, "familiarity", ""));
        }

        Map<String, Object> visible = JsonParse.map(state, "visible");
        if (visible != null) {
            for (Map.Entry<String, Object> entry : visible.entrySet()) {
                for (String item : strings(entry.getValue())) {
                    addFact(facts, "Present and visible: " + item + ".",
                            true, true);
                }
            }
        }
        for (Object entry : objects(state.get("inventory"))) {
            if (entry instanceof String item) {
                boolean relevant = contains(scene.change(), item);
                addFact(facts, "The recorded inventory contains " + item + ".",
                        relevant, relevant);
            } else if (entry instanceof Map<?, ?>) {
                Map<String, Object> container = objectMap(entry);
                String containerName = JsonParse.str(container, "container", "");
                List<String> items = strings(container.get("items"));
                if (!containerName.isBlank()) {
                    String sentence = items.isEmpty()
                            ? "The " + containerName + " is empty."
                            : "The " + containerName + " contains "
                                    + naturalList(items) + ".";
                    addFact(facts, sentence, true, true);
                }
            }
        }

        Map<String, Object> vehicle = JsonParse.map(state, "vehicle");
        if (vehicle != null) {
            addRecordedFact(facts, "The recorded vehicle",
                    JsonParse.str(vehicle, "name", ""), true, true);
            addRecordedFact(facts, "The recorded seat",
                    JsonParse.str(vehicle, "seat", ""), true, true);
            addRecordedFact(facts, "The recorded engine state",
                    JsonParse.str(vehicle, "engine", ""), true, true);
        }
        Map<String, Object> body = JsonParse.map(state, "body");
        if (body != null) {
            for (Map.Entry<String, Object> entry : body.entrySet()) {
                List<String> conditions = strings(entry.getValue());
                if (!conditions.isEmpty()) {
                    String part = splitCamel(entry.getKey());
                    addFact(facts, "The recorded " + part + " is "
                            + naturalList(conditions) + ".", true, true);
                }
            }
        }
        Map<String, Object> weather = JsonParse.map(state, "weather");
        if (weather != null) {
            addRecordedFact(facts, "The current weather",
                    JsonParse.str(weather, "conditions", ""), true, true);
            addRecordedFact(facts, "The recorded temperature",
                    JsonParse.str(weather, "temperature", ""), true, true);
        }
        Map<String, Object> utilities = JsonParse.map(state, "utilities");
        if (utilities != null) {
            addRecordedFact(facts, "Electrical power",
                    JsonParse.str(utilities, "power", ""), true, true);
            addRecordedFact(facts, "Water service",
                    JsonParse.str(utilities, "water", ""), true, true);
        }
        Map<String, Object> dead = JsonParse.map(state, "theDead");
        if (dead != null) {
            String nearby = JsonParse.str(dead, "nearbyBodies", "");
            if (!nearby.isBlank()) {
                addFact(facts, capitalize(nearby) + " body is recorded nearby.",
                        true, true);
            }
            String coming = JsonParse.str(dead, "comingForThem", "");
            if (!coming.isBlank()) {
                addFact(facts, capitalize(coming)
                        + " zombies are coming toward the survivor.", true, true);
            }
        }

        String change = scene.change().replace("### CHANGE", "").strip()
                .replaceFirst("(?is)^Since the last page:\\s*", "")
                .replaceAll("\\s+", " ");
        if (!change.isBlank()) {
            addFact(facts, summarizeChange(change), true, true);
        }
        return List.copyOf(facts);
    }

    private Plan parsePlan(String raw, List<CatalogFact> facts) {
        Set<String> known = new HashSet<>();
        for (CatalogFact fact : facts) {
            if (fact.selectable()) known.add(fact.id());
        }
        try {
            int first = raw.indexOf('{');
            int last = raw.lastIndexOf('}');
            if (first < 0 || last < first) throw new IllegalArgumentException("no object");
            Map<String, Object> parsed = JsonParse.parseObject(
                    raw.substring(first, last + 1));
            List<String> requested = strings(parsed.get("focus"));
            List<String> focus = requested.stream().filter(known::contains)
                    .distinct().limit(4).toList();
            String mood = enumValue(parsed, "mood", "watchful",
                    "watchful", "resolute", "uncertain", "restrained");
            String title = enumValue(parsed, "title", "quiet",
                    "quiet", "narrow", "still", "certain");
            String todo = enumValue(parsed, "todo", "certainty",
                    "certainty", "composure", "patience", "restraint");
            boolean valid = !focus.isEmpty() && focus.size() == requested.size()
                    && List.of("watchful", "resolute", "uncertain", "restrained")
                            .contains(JsonParse.str(parsed, "mood", ""))
                    && List.of("quiet", "narrow", "still", "certain")
                            .contains(JsonParse.str(parsed, "title", ""))
                    && List.of("certainty", "composure", "patience", "restraint")
                            .contains(JsonParse.str(parsed, "todo", ""));
            return new Plan(focus, mood, title, todo, valid, raw);
        } catch (RuntimeException invalid) {
            List<String> fallback = facts.stream().filter(CatalogFact::selectable)
                    .limit(4)
                    .map(CatalogFact::id).toList();
            return new Plan(fallback, "watchful", "quiet", "certainty",
                    false, raw);
        }
    }

    private String renderCatalog(Scene scene, List<CatalogFact> facts, Plan plan) {
        Map<String, Object> state = objectMap(scene.state());
        Map<String, Object> character = JsonParse.map(state, "character");
        String name = character == null ? "The survivor"
                : JsonParse.str(character, "name", "The survivor");
        String pronouns = character == null ? "they/them"
                : JsonParse.str(character, "pronouns", "they/them");
        String occupation = character == null ? "survivor"
                : JsonParse.str(character, "occupation", "survivor");
        String subject = subjectPronoun(pronouns);
        String possessive = possessivePronoun(pronouns);

        Map<String, String> titles = Map.of(
                "quiet", "The Quiet Present",
                "narrow", "A Narrow Certainty",
                "still", "The Still Moment",
                "certain", "The Certain Moment");
        Map<String, String> moods = Map.of(
                "watchful", "The moment feels watchful and tense.",
                "resolute", "Resolve gives the moment a firm emotional edge.",
                "uncertain", "Uncertainty weighs on the immediate moment.",
                "restrained", "Restraint keeps speculation outside this account.");
        Map<String, String> todos = Map.of(
                "certainty", "keep attention on what is certain",
                "composure", "preserve composure as uncertainty remains",
                "patience", "let patience govern the next decision",
                "restraint", "leave every unknown detail unnamed");

        Map<String, CatalogFact> byId = new LinkedHashMap<>();
        for (CatalogFact fact : facts) byId.put(fact.id(), fact);
        Set<String> order = new LinkedHashSet<>(plan.focus());
        for (CatalogFact fact : facts) {
            if (fact.essential()) order.add(fact.id());
        }

        StringBuilder page = new StringBuilder(moods.get(plan.mood()));
        for (String id : order) {
            CatalogFact fact = byId.get(id);
            if (fact != null) page.append(' ').append(fact.sentence());
        }
        List<String> groundingFillers = List.of(
                "The moment permits no assumptions beyond this evidence.",
                "Uncertainty remains, but it receives no invented shape.",
                "Attention stays narrow, calm, and faithful to the present.",
                "Every omitted detail remains unknown.");
        for (int i = 0; wordCount(page.toString()) < 65; i++) {
            page.append(' ').append(groundingFillers.get(i % groundingFillers.size()));
        }

        StringBuilder out = new StringBuilder();
        if (scene.first()) {
            out.append("### PREMISE\n")
                    .append(name).append("'s recorded occupation is ")
                    .append(occupation).append(". ")
                    .append(subject).append(" meet")
                    .append("They".equals(subject) ? "" : "s")
                    .append(" the present without a biography beyond the available record. ")
                    .append(possessive).append(" attention belongs to immediate certainty, ")
                    .append("while unknown details remain unnamed. Caution shapes ")
                    .append(possessive.toLowerCase(Locale.ROOT))
                    .append(" private response, and speculation has no authority over the account. ")
                    .append(possessive).append(" motive is simple: preserve composure, understand ")
                    .append("the current evidence, and let the next decision arise only from what is actually known.\n\n");
        }
        CatalogFact canon = facts.isEmpty()
                ? new CatalogFact("F00", "Only the present evidence is certain.",
                        true, true)
                : facts.get(0);
        out.append("### TITLE\n").append(titles.get(plan.title()))
                .append("\n### PAGE\n").append(page)
                .append("\n### CANON\n- [state] ").append(canon.sentence())
                .append("\n### TODO\n- ").append(todos.get(plan.todo()));
        return out.toString();
    }

    private String renderNarrative(Scene scene, List<CatalogFact> facts,
                                   Plan plan, long seed) {
        Map<String, Object> state = objectMap(scene.state());
        Map<String, Object> character = JsonParse.map(state, "character");
        String name = character == null ? "The survivor"
                : JsonParse.str(character, "name", "The survivor");
        String pronouns = character == null ? "they/them"
                : JsonParse.str(character, "pronouns", "they/them");
        String occupation = character == null ? "survivor"
                : JsonParse.str(character, "occupation", "survivor");
        String subject = subjectPronoun(pronouns);
        String possessive = possessivePronoun(pronouns);
        List<String> traits = character == null
                ? List.of() : strings(character.get("traits"));

        Map<String, Object> time = JsonParse.map(state, "time");
        String date = time == null ? "" : JsonParse.str(time, "date", "");
        String timeOfDay = time == null ? ""
                : JsonParse.str(time, "timeOfDay", "");
        String survived = time == null ? ""
                : JsonParse.str(time, "timeSurvived", "");
        Map<String, Object> position = JsonParse.map(state, "position");
        String place = position == null ? ""
                : JsonParse.str(position, "placeType", "");
        String familiarity = position == null ? ""
                : JsonParse.str(position, "familiarity", "");
        Map<String, Object> dead = JsonParse.map(state, "theDead");
        String approaching = dead == null ? ""
                : JsonParse.str(dead, "comingForThem", "");
        String nearbyBodies = dead == null ? ""
                : JsonParse.str(dead, "nearbyBodies", "");
        Map<String, Object> weather = JsonParse.map(state, "weather");
        Map<String, Object> utilities = JsonParse.map(state, "utilities");
        Map<String, Object> vehicle = JsonParse.map(state, "vehicle");
        Map<String, Object> body = JsonParse.map(state, "body");
        String change = scene.change().toLowerCase(Locale.ROOT);
        boolean nestedTransfer = change.contains("moved between two carried bags");
        boolean threat = !approaching.isBlank() || change.contains("coming toward");

        List<String> titles;
        if (threat) {
            titles = List.of("Danger Draws Near", "The Nearing Threat",
                    "No Time for Guesswork");
        } else if (nestedTransfer) {
            titles = List.of("Weight Between Bags", "Nothing Newly Gained",
                    "The Shifted Burden");
        } else if (utilities != null && "off".equals(
                JsonParse.str(utilities, "power", ""))) {
            titles = List.of("When Power Fails", "The Failed Current",
                    "Darkness Without Warning");
        } else if (weather != null) {
            titles = List.of("Rain Without Answer", "Weather Closing In",
                    "Under Heavy Rain");
        } else if (change.contains("killed")) {
            titles = List.of("After the Killing", "A Finished Violence",
                    "What the Knife Ended");
        } else if (scene.first()) {
            titles = List.of("The First Measure", "Only What Is Known",
                    "A Narrow Beginning");
        } else {
            Map<String, List<String>> byPlan = Map.of(
                    "quiet", List.of("The Quiet Present", "A Quiet Measure",
                            "The Weight of Quiet", "Quiet Without Answer"),
                    "narrow", List.of("A Narrow Certainty", "The Narrow Moment",
                            "A Smaller Horizon", "Only the Immediate"),
                    "still", List.of("The Still Moment", "Stillness Without Answer",
                            "A Measure of Stillness", "The Unmoving Present"),
                    "certain", List.of("The Certain Moment", "What Remains Certain",
                            "A Certain Measure", "The Known Present"));
            titles = byPlan.getOrDefault(plan.title(), byPlan.get("quiet"));
        }

        List<String> page = new ArrayList<>();
        if (threat) {
            page.add(pick(List.of(
                    "Urgency tightens the moment.",
                    "Danger gives the moment a sharp direction.",
                    "The immediate threat leaves little room for distraction."),
                    seed, 1));
        } else if (nestedTransfer) {
            page.add(pick(List.of(
                    "The carried weight has shifted, though nothing is newly gained.",
                    "A small transfer changes the order of what is carried.",
                    "Nothing is gained or lost, but the carried arrangement is different."),
                    seed, 2));
        } else if (!scene.change().isBlank()) {
            page.add(pick(List.of(
                    "A completed change gives the moment a clear edge.",
                    "What has just changed still carries weight.",
                    "The latest completed event defines the immediate moment."),
                    seed, 3));
        } else {
            Map<String, List<String>> openings = Map.of(
                    "watchful", List.of("Watchfulness gives the moment a quiet tension.",
                            "The moment feels alert without becoming hurried."),
                    "resolute", List.of("Resolve gives the moment a firm emotional edge.",
                            "A restrained resolve steadies the immediate moment."),
                    "uncertain", List.of("Uncertainty weighs on the immediate moment.",
                            "The unknown presses close, but remains unnamed."),
                    "restrained", List.of("Restraint keeps speculation outside the moment.",
                            "The moment remains narrow, measured, and restrained."));
            page.add(pick(openings.getOrDefault(plan.mood(), openings.get("watchful")),
                    seed, 4));
        }

        if (!date.isBlank() && !timeOfDay.isBlank()) {
            page.add(pick(List.of(
                    "It is " + timeOfDay + " on " + date + ".",
                    capitalize(timeOfDay) + " marks " + date + ".",
                    "The date is " + date + ", and the hour belongs to "
                            + timeOfDay + "."), seed, 5));
        }
        if (!survived.isBlank()) {
            page.add(pick(List.of(
                    "For " + name + ", survival now measures " + survived + ".",
                    survivedPhrase(name, survived),
                    "The known span of survival is " + survived + "."), seed, 6));
        }
        if (!place.isBlank() && safeLocationPhrase(place)) {
            page.add(pick(List.of(
                    name + " remains in " + placeWithArticle(place) + ".",
                    "The immediate place is " + placeWithArticle(place) + ".",
                    "The whole known scene is contained within "
                            + placeWithArticle(place) + "."), seed, 7));
        }

        List<String> visible = visibleStrings(state);
        if (!visible.isEmpty()) {
            String items = naturalList(visible);
            page.add(pick(List.of(
                    "The visible scene is limited to " + items + ".",
                    capitalize(items) + " define what is visible.",
                    "Only " + items + " can be confirmed in the immediate scene."),
                    seed, 8));
        }

        if (!approaching.isBlank()) {
            page.add(capitalize(approaching)
                    + " zombies are coming closer; they have already noticed "
                    + name + ".");
        }
        if (!nearbyBodies.isBlank()) {
            page.add(capitalize(nearbyBodies) + " body remains nearby.");
        }
        if (weather != null) {
            String conditions = JsonParse.str(weather, "conditions", "");
            String temperature = JsonParse.str(weather, "temperature", "");
            if (!conditions.isBlank()) {
                page.add("The current weather is " + conditions
                        + (temperature.isBlank() ? "." : ", with " + temperature
                                + " temperatures."));
            }
        }
        if (utilities != null) {
            String power = JsonParse.str(utilities, "power", "");
            String water = JsonParse.str(utilities, "water", "");
            if (!power.isBlank() || !water.isBlank()) {
                page.add("Electrical power is " + power + ", while water service is "
                        + water + ".");
            }
        }
        if (vehicle != null) {
            String vehicleName = JsonParse.str(vehicle, "name", "");
            String seat = JsonParse.str(vehicle, "seat", "");
            String engine = JsonParse.str(vehicle, "engine", "");
            page.add("The " + vehicleName + " is the recorded vehicle; the "
                    + seat + " seat is occupied and the engine is " + engine + ".");
        }
        if (body != null) {
            for (Map.Entry<String, Object> entry : body.entrySet()) {
                List<String> conditions = strings(entry.getValue());
                if (!conditions.isEmpty()) {
                    page.add(capitalize(possessive.toLowerCase(Locale.ROOT)) + " "
                            + splitCamel(entry.getKey()) + " is "
                            + naturalList(conditions) + ".");
                }
            }
        }

        page.addAll(nestedInventorySentences(state));
        for (Object entry : objects(state.get("inventory"))) {
            if (entry instanceof String item && contains(scene.change(), item)) {
                page.add("The current inventory still includes " + item + ".");
            }
        }
        if (!familiarity.isBlank()) {
            page.add("The place feels familiar because it is recorded as "
                    + familiarity + ".");
        }
        String changeSentence = narrativeChange(scene, state);
        if (!changeSentence.isBlank() && !threat) page.add(changeSentence);
        page.add(characterInterior(occupation, traits, threat, seed));

        List<String> fillers = threat
                ? List.of("The danger is immediate, and uncertainty now has a direction.",
                        "Patience still matters, but delay carries its own pressure.")
                : List.of(
                        "Silence carries its own quiet pressure.",
                        "Uncertainty gives every certain detail greater weight.",
                        "The next decision can wait until the present is understood.",
                        "What remains unknown stays beyond immediate attention.",
                        "Certainty is scarce enough to matter.",
                        "Each known detail offers a small point of balance.",
                        "The unknown remains present without taking shape.",
                        "Patience gives the moment room to settle.",
                        "Concern stays close to the immediate facts.",
                        "The situation feels narrow, but not empty.",
                        "Attention rests on what can be confirmed.",
                        "Unease remains controlled and specific.",
                        "The present carries enough weight on its own.",
                        "Every clear detail holds against the surrounding uncertainty.",
                        "Stillness makes the known facts feel sharper.",
                        "Composure offers a thin but useful boundary.");
        int fillerIndex = Math.floorMod(Long.hashCode(seed), fillers.size());
        while (wordCount(String.join(" ", page)) < 75) {
            page.add(fillers.get(fillerIndex++ % fillers.size()));
        }

        String todo;
        if (threat) todo = "keep attention on the approaching danger";
        else if (nestedTransfer) todo = "keep track of what each carried bag contains";
        else if (utilities != null && "off".equals(
                JsonParse.str(utilities, "power", ""))) {
            todo = "carry the failed power into the next decision";
        } else {
            Map<String, List<String>> todoChoices = Map.of(
                    "certainty", List.of("keep attention on what is certain",
                            "let the next choice begin with known facts",
                            "hold uncertainty apart from certainty",
                            "keep the immediate facts in clear order"),
                    "composure", List.of("preserve composure as uncertainty remains",
                            "keep the moment steady before deciding",
                            "let composure shape the next judgment",
                            "hold to calm while the unknown remains"),
                    "patience", List.of("let patience govern the next decision",
                            "keep the next decision measured",
                            "allow the moment to settle before judgment",
                            "hold to patience while uncertainty remains"),
                    "restraint", List.of("leave every unknown detail unnamed",
                            "keep speculation outside the next decision",
                            "let restraint shape what follows",
                            "carry only confirmed details forward"));
            todo = pick(todoChoices.getOrDefault(plan.todo(),
                    todoChoices.get("certainty")), seed, 30);
        }

        StringBuilder out = new StringBuilder();
        if (scene.first()) {
            out.append("### PREMISE\n")
                    .append(narrativePremise(name, pronouns, occupation, traits))
                    .append("\n\n");
        }
        CatalogFact canon = plan.focus().stream().map(id -> facts.stream()
                        .filter(f -> f.id().equals(id)).findFirst().orElse(null))
                .filter(java.util.Objects::nonNull).findFirst()
                .orElse(facts.isEmpty()
                        ? new CatalogFact("F00", "Only the present is certain.",
                                true, true)
                        : facts.get(0));
        out.append("### TITLE\n").append(pick(titles, seed, 9))
                .append("\n### PAGE\n").append(String.join(" ", page))
                .append("\n### CANON\n- [state] ").append(canon.sentence())
                .append("\n### TODO\n- ").append(todo);
        return out.toString();
    }

    private static String narrativePremise(String name, String pronouns,
                                           String occupation,
                                           List<String> traits) {
        String subject = subjectPronoun(pronouns);
        String possessive = possessivePronoun(pronouns);
        String role = "unemployed".equalsIgnoreCase(occupation)
                ? "an unemployed survivor"
                : articleFor(occupation) + " " + occupation.toLowerCase(Locale.ROOT);
        StringBuilder premise = new StringBuilder()
                .append(name).append(" is ").append(role)
                .append(" facing a world narrowed to immediate choices. ")
                .append(subject).append(" give")
                .append("They".equals(subject) ? "" : "s")
                .append(" full attention to what can be known now, allowing uncertainty ")
                .append("to remain unanswered. Caution shapes ")
                .append(possessive.toLowerCase(Locale.ROOT))
                .append(" judgment without deciding it. ")
                .append(possessive).append(" purpose is simple: preserve composure, ")
                .append("understand the moment honestly, and make the next decision from ")
                .append("present circumstances alone. Nothing beyond those circumstances ")
                .append("needs a name yet.");
        if (traits.stream().anyMatch(t -> "cowardly".equalsIgnoreCase(t))) {
            premise.append(" Fear is close, but it does not control every conclusion.");
        }
        return premise.toString();
    }

    private static String characterInterior(String occupation, List<String> traits,
                                            boolean threat, long seed) {
        if (traits.stream().anyMatch(t -> "cowardly".equalsIgnoreCase(t))) {
            return pick(List.of(
                    "Fear is present, but it does not need an invented cause.",
                    "Fear sharpens uncertainty without adding anything to the scene."),
                    seed, 20);
        }
        if (threat) {
            return pick(List.of(
                    "Concern narrows to the danger that is actually present.",
                    "Every other uncertainty feels smaller beside the approaching threat."),
                    seed, 21);
        }
        String role = occupation.toLowerCase(Locale.ROOT);
        if (role.contains("nurse") || role.contains("doctor")) {
            return pick(List.of(
                    "Professional caution gives the uncertainty a little order.",
                    "A measured frame of mind keeps the immediate facts distinct."),
                    seed, 22);
        }
        if (role.contains("mechanic") || role.contains("carpenter")
                || role.contains("construction")) {
            return pick(List.of(
                    "A practical frame of mind favors what can be confirmed.",
                    "Concrete details feel more useful than speculation."), seed, 23);
        }
        if (role.contains("ranger") || role.contains("veteran")
                || role.contains("guard") || role.contains("officer")) {
            return pick(List.of(
                    "Discipline narrows concern to the immediate problem.",
                    "Measured attention feels more useful than haste."), seed, 24);
        }
        if (role.contains("burglar")) {
            return pick(List.of(
                    "Caution feels more useful than haste.",
                    "Attention settles on the few details that can be trusted."), seed, 25);
        }
        return pick(List.of(
                "The emotional weight remains immediate and restrained.",
                "Attention stays close to what the moment can support."), seed, 26);
    }

    private static String narrativeChange(Scene scene, Map<String, Object> state) {
        String change = scene.change().toLowerCase(Locale.ROOT);
        if (change.isBlank()) return "";
        if (change.contains("moved between two carried bags")) {
            String item = "item";
            String destination = "carried bag";
            for (Object entry : objects(state.get("inventory"))) {
                if (entry instanceof Map<?, ?>) {
                    Map<String, Object> container = objectMap(entry);
                    List<String> items = strings(container.get("items"));
                    if (!items.isEmpty()) {
                        item = items.get(0);
                        destination = JsonParse.str(container, "container", destination);
                        break;
                    }
                }
            }
            return "The " + item + " is now in the " + destination
                    + " after a transfer between two carried bags; nothing was gained or lost.";
        }
        Map<String, Object> dead = JsonParse.map(state, "theDead");
        String coming = dead == null ? ""
                : JsonParse.str(dead, "comingForThem", "");
        if (!coming.isBlank() || change.contains("coming toward")) {
            String count = coming.isBlank() ? "several" : coming;
            return capitalize(count)
                    + " zombies are coming closer, while no survivor action is complete.";
        }
        if (change.contains("killed one zombie")) {
            return "The killing is complete: one zombie is down, and the kitchen knife is the recorded weapon.";
        }
        if (change.contains("bandaged a scratch")) {
            return "Treatment of the scratched left hand is complete.";
        }
        if (change.contains("picked up a can opener")) {
            return "The can opener is now recorded in the inventory; the acquisition is complete.";
        }
        if (change.contains("electrical power has failed")) {
            return "Electrical power has failed throughout the world.";
        }
        if (change.contains("returned to the same office")) {
            return "The familiar office is the same place visited before; the return is complete.";
        }
        if (change.contains("woken after sleeping")) {
            return "Sleep is over in this room, and waking is complete.";
        }
        if (change.contains("lit the campfire")) {
            return "The campfire is now lit with the recorded matches.";
        }
        if (change.contains("exited the")) {
            return "The recorded exit from the vehicle is complete.";
        }
        return "The latest completed change remains part of the immediate situation.";
    }

    private static List<String> visibleStrings(Map<String, Object> state) {
        Map<String, Object> visible = JsonParse.map(state, "visible");
        if (visible == null) return List.of();
        List<String> out = new ArrayList<>();
        for (Object value : visible.values()) out.addAll(strings(value));
        return List.copyOf(out);
    }

    private static List<String> nestedInventorySentences(Map<String, Object> state) {
        List<String> out = new ArrayList<>();
        for (Object entry : objects(state.get("inventory"))) {
            if (!(entry instanceof Map<?, ?>)) continue;
            Map<String, Object> container = objectMap(entry);
            String name = JsonParse.str(container, "container", "");
            if (name.isBlank()) continue;
            List<String> items = strings(container.get("items"));
            out.add(items.isEmpty() ? "The " + name + " is empty."
                    : "The " + name + " contains " + naturalList(items) + ".");
        }
        return out;
    }

    private static String survivedPhrase(String name, String survived) {
        return "The known span of survival for " + name + " is " + survived + ".";
    }

    private static String placeWithArticle(String place) {
        String lower = place.toLowerCase(Locale.ROOT);
        if (lower.startsWith("inside ") || lower.startsWith("outside ")) return place;
        return "the " + place;
    }

    private static String articleFor(String word) {
        if (word == null || word.isBlank()) return "a";
        char first = Character.toLowerCase(word.charAt(0));
        return "aeiou".indexOf(first) >= 0 ? "an" : "a";
    }

    private static String pick(List<String> values, long seed, int salt) {
        if (values == null || values.isEmpty()) return "";
        int index = Math.floorMod(Long.hashCode(seed * 31L + salt), values.size());
        return values.get(index);
    }

    private boolean safeLocationPhrase(String place) {
        return physicalVocabulary.stream().noneMatch(term -> contains(place, term));
    }

    private static void addFact(List<CatalogFact> facts, String sentence) {
        addFact(facts, sentence, false, true);
    }

    private static void addFact(List<CatalogFact> facts, String sentence,
                                boolean essential, boolean selectable) {
        String text = sentence == null ? "" : sentence.strip();
        if (text.isBlank()) return;
        facts.add(new CatalogFact(String.format(Locale.ROOT,
                "F%02d", facts.size() + 1), text, essential, selectable));
    }

    private static void addRecordedFact(List<CatalogFact> facts,
                                        String label, String value) {
        addRecordedFact(facts, label, value, false, true);
    }

    private static void addRecordedFact(List<CatalogFact> facts,
                                        String label, String value,
                                        boolean essential, boolean selectable) {
        if (value != null && !value.isBlank()) {
            addFact(facts, label + " is " + value + ".",
                    essential, selectable);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        return value instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
    }

    private static String enumValue(Map<String, Object> values, String key,
                                    String fallback, String... allowed) {
        String value = JsonParse.str(values, key, fallback);
        return List.of(allowed).contains(value) ? value : fallback;
    }

    private static String naturalList(List<String> values) {
        if (values.size() == 1) return values.get(0);
        if (values.size() == 2) return values.get(0) + " and " + values.get(1);
        return String.join(", ", values.subList(0, values.size() - 1))
                + ", and " + values.get(values.size() - 1);
    }

    private static String summarizeChange(String change) {
        String lower = change.toLowerCase(Locale.ROOT);
        if (lower.contains("killed one zombie")) {
            return "One killing with the kitchen knife is recorded as complete.";
        }
        if (lower.contains("bandaged a scratch")) {
            return "Treatment of the recorded left hand is complete.";
        }
        if (lower.contains("picked up a can opener")) {
            return "The can opener acquisition is complete.";
        }
        if (lower.contains("electrical power has failed")) {
            return "Electrical power has failed throughout the world.";
        }
        if (lower.contains("returned to the same office")) {
            return "The return to the familiar office is complete.";
        }
        if (lower.contains("woken after sleeping")) {
            return "Waking after sleep in this room is complete.";
        }
        if (lower.contains("moved between two carried bags")) {
            return "A carried item has moved between two bags without acquisition or loss.";
        }
        if (lower.contains("coming toward")) {
            return "The approaching threat is immediate; no survivor action is complete.";
        }
        if (lower.contains("lit the campfire")) {
            return "The campfire is lit with matches.";
        }
        if (lower.contains("exited the")) {
            return "The recorded vehicle exit is complete.";
        }
        return "A completed change is recorded in the current evidence.";
    }

    private static String splitCamel(String value) {
        return value.replaceAll("(?<=[a-z])(?=[A-Z])", " ")
                .toLowerCase(Locale.ROOT);
    }

    private static String capitalize(String value) {
        return value.isEmpty() ? value
                : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static String subjectPronoun(String pronouns) {
        if (pronouns.toLowerCase(Locale.ROOT).startsWith("she")) return "She";
        if (pronouns.toLowerCase(Locale.ROOT).startsWith("he")) return "He";
        return "They";
    }

    private static String possessivePronoun(String pronouns) {
        if (pronouns.toLowerCase(Locale.ROOT).startsWith("she")) return "Her";
        if (pronouns.toLowerCase(Locale.ROOT).startsWith("he")) return "His";
        return "Their";
    }

    private static int wordCount(String text) {
        String value = text.strip();
        return value.isEmpty() ? 0 : value.split("\\s+").length;
    }

    private Evaluation evaluate(String answer, Scene scene) {
        boolean structure = true;
        String structureError = "";
        PageResult parsed = null;
        try {
            parsed = PageResult.parse(answer, scene.first(), Settings.words());
        } catch (PageResult.Invalid invalid) {
            structure = false;
            structureError = invalid.getMessage();
        }

        List<String> violations = new ArrayList<>();
        String lexicalAnswer = maskStateBackedOccupation(answer, scene);
        for (String term : physicalVocabulary) {
            if (contains(lexicalAnswer, term)
                    && !scene.allowedPhysical().contains(term)) {
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
            if (contains(lexicalAnswer, term)) {
                violations.add("explicitly forbidden: " + term);
            }
        }
        if (!scene.expectedAny().isEmpty()
                && scene.expectedAny().stream().noneMatch(t -> contains(answer, t))) {
            violations.add("missed expected focus: " + scene.expectedAny());
        }
        for (String term : scene.expectedAll()) {
            if (!contains(answer, term)) {
                violations.add("missed required atmosphere/continuity phrase: " + term);
            }
        }
        if (parsed != null && scene.minPageParagraphs() > 0) {
            long paragraphs = Pattern.compile("\\R\\s*\\R")
                    .splitAsStream(parsed.page.strip()).filter(p -> !p.isBlank()).count();
            if (paragraphs < scene.minPageParagraphs()) {
                violations.add("insufficient atmosphere paragraphs: " + paragraphs
                        + " < " + scene.minPageParagraphs());
            }
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

    private static String maskStateBackedOccupation(String answer, Scene scene) {
        Map<String, Object> state = objectMap(scene.state());
        Map<String, Object> character = JsonParse.map(state, "character");
        String occupation = character == null ? ""
                : JsonParse.str(character, "occupation", "");
        if (occupation.isBlank()) return answer;
        return Pattern.compile(Pattern.quote(occupation),
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                .matcher(answer).replaceAll(" ");
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
                    strings(m.get("expectedAny")), strings(m.get("expectedAll")),
                    number(m, "minPageParagraphs"), bool(m, "forbidHistory")));
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
