package de.fricke.pzstory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Live-like six-page Safe-mode acceptance run over LM Studio's native stored
 * response chain. Unlike the isolated corpus benchmark, every planner turn
 * inherits the hidden Knox history seed and the previous planner responses.
 */
public final class StatefulConspiracySequenceBenchmark {
    private static final URI ENDPOINT = URI.create("http://127.0.0.1:1234/api/v1/chat");
    private static final String MODEL = "qwen2.5-3b-instruct";
    private static final String BOOT_OVERRIDE = """
            CURRENT TURN OVERRIDE. The next user turn headed PRIVATE CHRONOLOGY
            is hidden KnoxOS setup, not page one. For that turn only, absorb it
            and reply with exactly HISTORY_READY_V2. After that acknowledgement,
            follow the normal planner contract for every subsequent turn.
            """;

    private record Step(String id, String state, String change, String note,
                        boolean first, boolean stillStanding,
                        List<String> expected) {}

    private record Reply(String id, String text, double seconds) {}

    private StatefulConspiracySequenceBenchmark() {}

    public static void main(String[] args) throws Exception {
        Path out = args.length > 0 ? Path.of(args[0])
                : Path.of("dev/local-model-eval/runs/overnight-stateful-sequence");
        int repetitions = args.length > 1 ? Integer.parseInt(args[1]) : 10;
        Files.createDirectories(out);
        Path rows = out.resolve("results.jsonl");
        Files.deleteIfExists(rows);

        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build();
        List<Step> steps = steps();
        int passed = 0;
        int total = 0;
        double seconds = 0;
        List<String> failures = new ArrayList<>();

        for (int repetition = 0; repetition < repetitions; repetition++) {
            ValidatedNarrator.Session seedSession = session(steps.get(0), "", 1,
                    267000L + repetition * 100L);
            Reply seed = send(http, seedSession.systemPrompt() + "\n\n" + BOOT_OVERRIDE,
                    NarratorHistory.TIMELINE, "", 32);
            if (!NarratorHistory.ACK.equals(seed.text().strip())) {
                throw new IllegalStateException("history seed rejected: " + seed.text());
            }
            String previous = seed.id();
            String recent = "";
            Set<String> titles = new HashSet<>();
            Set<String> openings = new HashSet<>();

            for (int i = 0; i < steps.size(); i++) {
                Step step = steps.get(i);
                long seedValue = 267000L + repetition * 100L + i;
                ValidatedNarrator.Session session = session(step, recent, i + 1,
                        seedValue);
                Reply planner = send(http, "", session.userPrompt(), previous, 96);
                previous = planner.id();
                String rendered = session.render(planner.text());
                PageResult page = PageResult.parse(rendered, step.first(), 200);
                GroundingGuard.validate(page, step.state(), step.change(), step.first(),
                        step.stillStanding());

                List<String> faults = new ArrayList<>();
                for (String expected : step.expected()) {
                    if (!rendered.toLowerCase().contains(expected.toLowerCase())) {
                        faults.add("missing: " + expected);
                    }
                }
                String titleKey = RepetitionGuard.titleKey(page.title);
                String openingKey = RepetitionGuard.openingKey(page.page);
                if (!titles.add(titleKey)) faults.add("repeated title: " + page.title);
                if (!openings.add(openingKey)) faults.add("repeated opening: " + openingKey);

                total++;
                seconds += planner.seconds();
                boolean ok = faults.isEmpty();
                if (ok) passed++; else failures.add("rep " + repetition + " "
                        + step.id() + " -> " + faults);
                append(rows, repetition, i + 1, step.id(), planner, page, rendered,
                        faults, ok);
                recent += "- title: " + page.title + " | opening: " + openingKey + "\n";
            }
        }

        String summary = """
                # Stateful Conspiracy sequence benchmark

                Completed: %d six-page cases across %d stored-response chains.  
                Passed: **%d/%d**.  
                Mean planner latency: **%.2f seconds**.  
                Hidden Knox history seed: accepted in every chain.  
                Duplicate titles/openings: **%d**.  

                %s
                """.formatted(total, repetitions, passed, total,
                total == 0 ? 0 : seconds / total, failures.size(),
                failures.isEmpty() ? "No failures." : String.join("\n", failures));
        Files.writeString(out.resolve("summary.md"), summary, StandardCharsets.UTF_8);
        System.out.print(summary);
        if (!failures.isEmpty()) System.exit(1);
    }

    private static ValidatedNarrator.Session session(Step step, String recent,
                                                       int page, long seed) {
        String guidance = recent.isBlank() ? "" : "### RECENT WORDING TO AVOID\n"
                + "Do not reuse these titles or begin with these same words.\n" + recent;
        return ValidatedNarrator.prepare(step.state(), List.of(), step.change(),
                step.first(), 200, seed, step.note(), "conspiracy", guidance, page);
    }

    private static Reply send(HttpClient http, String system, String input,
                              String previous, int maxTokens) throws Exception {
        Json body = new Json().obj();
        body.put("model", MODEL).put("stream", false).put("store", true)
                .put("max_output_tokens", maxTokens);
        if (!system.isBlank()) body.put("system_prompt", system);
        body.put("input", input);
        if (!previous.isBlank()) body.put("previous_response_id", previous);
        String json = body.endObj().toString();
        long started = System.nanoTime();
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(ENDPOINT)
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json,
                                StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("LM Studio HTTP " + response.statusCode());
        }
        Map<String, Object> parsed = JsonParse.parseObject(response.body());
        String id = JsonParse.str(parsed, "response_id", "");
        String text = "";
        Object output = parsed.get("output");
        if (output instanceof List<?> list && !list.isEmpty()
                && list.get(0) instanceof Map<?, ?> raw) {
            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) raw;
            text = JsonParse.str(message, "content", "");
        }
        if (id.isBlank() || text.isBlank()) {
            throw new IllegalStateException("LM Studio omitted response id or content");
        }
        return new Reply(id, text, seconds);
    }

    private static void append(Path path, int repetition, int pageNumber,
                               String step, Reply planner, PageResult page,
                               String rendered, List<String> faults, boolean passed)
            throws Exception {
        Json row = new Json().obj()
                .put("timestamp", OffsetDateTime.now().toString())
                .put("repetition", repetition).put("page", pageNumber)
                .put("step", step).put("seconds", planner.seconds())
                .put("plannerReply", planner.text()).put("title", page.title)
                .put("openingKey", RepetitionGuard.openingKey(page.page))
                .put("rendered", rendered).put("passed", passed)
                .arrKey("faults");
        for (String fault : faults) row.val(fault);
        row.endArr().endObj();
        Files.writeString(path, row + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static List<Step> steps() {
        String home = state("Caryn Emanuel", "she/her", "Burger Flipper",
                "living room", "[\"couch\",\"coffee table\",\"refrigerator\"]",
                "\"theDead\":{\"withinSight\":\"one\",\"note\":\"In view but not yet aware.\"},");
        String kitchen = state("Caryn Emanuel", "she/her", "Burger Flipper",
                "kitchen", "[\"counter\",\"sink\",\"refrigerator\"]", "");
        String bedroom = state("Caryn Emanuel", "she/her", "Burger Flipper",
                "bedroom", "[\"bed\",\"wardrobe\"]", "");
        String road = """
                {"character":{"name":"Caryn Emanuel","pronouns":"she/her",
                "occupation":"Burger Flipper","timeSurvived":"less than a day"},
                "time":{"date":"July 9, 1993","timeOfDay":"afternoon"},
                "position":{"placeType":"residential road","indoors":false},
                "visible":{"terrain":["road","yard","fence"],
                "bodies":["one zombie body"]},"inHisHands":{"primary":"hammer"}}
                """;
        String evidence = state("Caryn Emanuel", "she/her", "Burger Flipper",
                "living room", "[\"couch\",\"coffee table\",\"radio\",\"newspaper\"]", "")
                .replace("July 9, 1993", "July 11, 1993");
        return List.of(
                new Step("opening", home, "", "", true, false,
                        List.of("not an investigator", "not noticed her yet",
                                "proof of danger, not an explanation")),
                new Step("suspicion", home, "",
                        "The military knew this was coming. The official story does not fit.",
                        false, true, List.of("suspicion is not proof",
                                "official account is incomplete")),
                new Step("food", kitchen,
                        "### WHAT HAS CHANGED SINCE THE LAST PAGE\n"
                                + "- Caryn moved into the kitchen.\n"
                                + "- Caryn acquired canned food. These actions are complete.",
                        "Count the food for a few days.", false, false,
                        List.of("has moved into the kitchen", "has acquired canned food",
                                "refuge without provisions")),
                new Step("safehouse", bedroom, "",
                        "This will be the safe house if the doors hold.", false, true,
                        List.of("locked door can be tested", "already safe")),
                new Step("perimeter", road,
                        "### WHAT HAS CHANGED SINCE THE LAST PAGE\n"
                                + "- Caryn has killed one zombie with the hammer. "
                                + "The action is complete.",
                        "One less nearby. Clear the blocks around the safe house.",
                        false, false, List.of("has killed one zombie with the hammer",
                                "safety means more than one locked room")),
                new Step("evidence", evidence, "",
                        "Compare the radio and newspaper dates. Keep evidence separate.",
                        false, true, List.of("dates, words, and contradictions",
                                "still not evidence", "compare dated reports")));
    }

    private static String state(String name, String pronouns, String occupation,
                                String place, String furniture, String extra) {
        return "{\"character\":{\"name\":\"" + name + "\",\"pronouns\":\""
                + pronouns + "\",\"occupation\":\"" + occupation
                + "\",\"timeSurvived\":\"less than a day\","
                + "\"experienceWithTheDead\":\"none\"},"
                + "\"time\":{\"date\":\"July 9, 1993\",\"timeOfDay\":\"morning\"},"
                + "\"position\":{\"placeType\":\"" + place
                + "\",\"indoors\":true},\"here\":{\"furniture\":" + furniture
                + ",\"doors\":{\"total\":1,\"locked\":1}},"
                + "\"inHisHands\":{\"nothing\":true}," + extra
                + "\"weather\":{\"feels\":\"cool\",\"light\":\"bright\"}}";
    }
}
