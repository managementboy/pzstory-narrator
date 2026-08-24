package de.fricke.pzstory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Offline acceptance check for a local OpenAI-compatible narrator.
 *
 * This deliberately uses the production charter, production user-turn
 * builder and production terminal reply validator. It never reads a save or
 * provider key and sends only a synthetic survivor state to loopback.
 */
public final class LocalModelAcceptance {

    private static final String STATE = """
            {
              "character": {
                "name": "Alex Morgan",
                "pronouns": "they/them",
                "occupation": "Unemployed",
                "traits": []
              },
              "time": {
                "date": "July 9, 1993",
                "timeOfDay": "morning",
                "timeSurvived": "less than a day",
                "daysSinceItBegan": "less than one day"
              },
              "position": {
                "placeType": "garage storage",
                "indoors": true,
                "floor": "ground floor"
              },
              "visible": {
                "furniture": ["wooden table", "stool", "metal shelves"],
                "doors": ["closed garage door"]
              },
              "inventory": ["hammer", "bottle of water"],
              "theDead": { "nearbyBodies": "one" }
            }
            """;

    private static final String[] UNSUPPORTED_WORLD = {
        "window", "car engine", "bird", "refrigerator", "bed", "cabinet"
    };

    private static final String[] UNPLAYED_ACTION = {
        "they sit", "they drink", "they reach", "they walk", "they open",
        "they step", "they take a sip", "they pick up"
    };

    private static final String[] FALSE_HISTORY = {
        "last night", "previous night", "yesterday", "before the outbreak",
        "survived the night", "survived one night"
    };

    private LocalModelAcceptance() {}

    public static void main(String[] args) throws Exception {
        String model = args.length == 0 ? "pzstory-stheno" : args[0];
        String system = Prompt.CHARTER + "\n\n" + Prompt.tone()
                + "\n\n" + World.RULES + "\n\n" + World.KNOX;
        String user = Prompt.userTurn(STATE, "", "", true, false);
        String request = request(model, system, user);

        long began = System.nanoTime();
        HttpResponse<String> response = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build()
                .send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:11434/v1/chat/completions"))
                        .timeout(Duration.ofMinutes(5))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(request))
                        .build(), HttpResponse.BodyHandlers.ofString());
        double seconds = (System.nanoTime() - began) / 1_000_000_000.0;
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Ollama returned HTTP " + response.statusCode());
        }

        String answer = answer(response.body());
        List<String> failures = new ArrayList<>();
        try {
            PageResult.parse(answer, true, Settings.words());
        } catch (PageResult.Invalid invalid) {
            failures.add("reply contract: " + invalid.getMessage());
        }
        find(failures, answer, UNSUPPORTED_WORLD, "unsupported world detail");
        find(failures, answer, UNPLAYED_ACTION, "unplayed survivor action");
        find(failures, answer, FALSE_HISTORY, "false history");

        System.out.printf("model=%s elapsed=%.1fs inputChars=%d outputChars=%d%n",
                model, seconds, system.length() + user.length(), answer.length());
        if (failures.isEmpty()) {
            System.out.println("PASS: reply is structurally valid and passed grounding checks");
            return;
        }
        System.out.println("FAIL:");
        failures.forEach(failure -> System.out.println("- " + failure));
        System.out.println("--- MODEL REPLY ---");
        System.out.println(answer);
        System.exit(1);
    }

    private static String request(String model, String system, String user) {
        Json json = new Json().obj();
        json.put("model", model);
        json.put("stream", false);
        json.put("max_tokens", 1200);
        json.arrKey("messages");
        json.obj();
        json.put("role", "system");
        json.put("content", system);
        json.endObj();
        json.obj();
        json.put("role", "user");
        json.put("content", user);
        json.endObj();
        json.endArr();
        return json.endObj().toString();
    }

    private static String answer(String body) {
        Map<String, Object> root = JsonParse.parseObject(body);
        if (!(root.get("choices") instanceof List<?> choices) || choices.isEmpty()
                || !(choices.get(0) instanceof Map<?, ?> choice)
                || !(choice.get("message") instanceof Map<?, ?> message)
                || !(message.get("content") instanceof String content)) {
            throw new IllegalStateException("Ollama response had no assistant content");
        }
        return content;
    }

    private static void find(List<String> failures, String answer,
                             String[] needles, String kind) {
        String lower = answer.toLowerCase(java.util.Locale.ROOT);
        for (String needle : needles) {
            if (lower.contains(needle)) failures.add(kind + ": " + needle);
        }
    }
}
