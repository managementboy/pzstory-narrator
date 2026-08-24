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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reproducible local-model grounding benchmark.
 *
 * Uses PZStory's production prompt builder and terminal reply validator. All
 * scenes are synthetic and every result is appended before the next request,
 * so an interrupted overnight run remains useful and can be resumed.
 */
public final class LocalModelBenchmark {

    private static final URI OLLAMA = URI.create(
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

    private record Answer(String text, double seconds) {}

    private static final List<Variant> ALL_VARIANTS = List.of(
            new Variant("baseline", false, false, false, 0.20, 0.90),
            new Variant("ledger", false, true, false, 0.20, 0.90),
            new Variant("ledger-cold", false, true, false, 0.05, 0.80),
            new Variant("compact-ledger", true, true, false, 0.20, 0.90),
            new Variant("compact-cold", true, true, false, 0.05, 0.80),
            new Variant("compact-repair", true, true, true, 0.05, 0.80));

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private List<String> physicalVocabulary;
    private List<String> actionVocabulary;
    private List<String> historyVocabulary;

    private LocalModelBenchmark() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> cli = arguments(args);
        Path scenesPath = Path.of(required(cli, "scenes"));
        Path output = Path.of(required(cli, "out"));
        String model = cli.getOrDefault("model", "pzstory-stheno:latest");
        String split = cli.getOrDefault("split", "all");
        List<String> sceneFilter = List.of(
                cli.getOrDefault("scene", "all").split(","));
        int repetitions = Integer.parseInt(cli.getOrDefault("repetitions", "1"));
        long seedBase = Long.parseLong(cli.getOrDefault("seed", "240826"));
        OffsetDateTime deadline = cli.containsKey("deadline")
                ? OffsetDateTime.parse(cli.get("deadline")) : null;
        List<String> wantedVariants = List.of(
                cli.getOrDefault("variants", "baseline").split(","));

        LocalModelBenchmark benchmark = new LocalModelBenchmark();
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

        List<Map<String, Object>> rows = new ArrayList<>();
        int requestNumber = 0;
        outer:
        for (Variant variant : variants) {
            for (Scene scene : scenes) {
                if (!"all".equals(split) && !split.equals(scene.split())) continue;
                if (!sceneFilter.contains("all") && !sceneFilter.contains(scene.id())) continue;
                for (int repetition = 0; repetition < repetitions; repetition++) {
                    if (deadline != null && OffsetDateTime.now().isAfter(deadline)) {
                        System.out.println("deadline reached before next case");
                        break outer;
                    }
                    long seed = seedBase + requestNumber++;
                    Map<String, Object> row = benchmark.run(
                            model, variant, scene, repetition, seed);
                    rows.add(row);
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
        boolean repaired = false;

        if (variant.repair() && (!evaluation.structureValid()
                || !evaluation.violations().isEmpty())) {
            messages.add(message("assistant", first.text()));
            messages.add(message("user", repairInstruction(evaluation, scene)));
            Answer second = call(model, messages, variant, seed + 10_000_000L);
            finalText = second.text();
            seconds += second.seconds();
            evaluation = evaluate(finalText, scene);
            repaired = true;
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("timestamp", OffsetDateTime.now().toString());
        row.put("model", model);
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
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("stream", false);
        request.put("max_tokens", 900);
        request.put("temperature", variant.temperature());
        request.put("top_p", variant.topP());
        request.put("seed", seed);
        request.put("messages", messages);
        long began = System.nanoTime();
        HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                .uri(OLLAMA).timeout(Duration.ofMinutes(4))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.of(request)))
                .build(), HttpResponse.BodyHandlers.ofString());
        double seconds = (System.nanoTime() - began) / 1_000_000_000.0;
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Ollama HTTP " + response.statusCode()
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
        return new Answer(content, seconds);
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
            if (contains(answer, action) && !scene.allowedActions().contains(action)) {
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
        return """

                ### CLOSED-WORLD GROUNDING LEDGER
                This ledger is exhaustive, not illustrative.
                Allowed named physical things: %s
                Completed survivor actions that may be described: %s
                Past history permitted beyond CHANGE: %s
                Do not name any other physical thing. Do not make the survivor
                perform a new action. Check every sentence against this ledger
                before returning the required headings and nothing after TODO.
                """.formatted(physical, actions,
                        scene.forbidHistory() ? "none" : "only what STATE or CHANGE says");
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
        meta.put("model", model);
        meta.put("modelDigest", modelDigest(model));
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
        out.append("# Local-model benchmark summary\n\n")
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

    private static boolean contains(String text, String phrase) {
        return Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])"
                + Pattern.quote(phrase) + "(?![\\p{L}\\p{N}])")
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
