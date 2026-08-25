package de.fricke.pzstory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** LM Studio checkpoints follow accepted campaign pages, never raw replies. */
public final class StatefulSessionTest {
    public static void run() {
        T.group("LM Studio state - transactional campaign checkpoint");
        Path fixture = null;
        try {
            fixture = Files.createTempDirectory("pzstory-stateful-test-");
            System.setProperty("pzstory.test.root", fixture.toString());
            Campaign.reset();
            T.ok("scenario exists before hidden narrator seed",
                    Campaign.setScenario("conspiracy"));
            boolean seeded = Campaign.commitProviderSeed(
                    Campaign.generation(), "lm-local", "qwen",
                    Llm.SCOPE_CLASSIC, "resp_history_seed");
            T.ok("hidden history seed stores a provider checkpoint", seeded);
            T.eq("history seed creates no visible page", 0, Campaign.pageCount());
            T.eq("first classic page can branch from history seed", "resp_history_seed",
                    Campaign.providerResponseId("lm-local", "qwen", Llm.SCOPE_CLASSIC));
            T.eq("safe narrator cannot inherit classic history contract", "",
                    Campaign.providerResponseId("lm-local", "qwen", Llm.SCOPE_SAFE));
            boolean first = Campaign.commitGeneratedPage(
                    Campaign.generation(), "A grounded opening premise", "First",
                    words("grounded", 60), "1993-07-09 09:00", List.of(), "",
                    "{\"position\":{\"x\":1,\"y\":1,\"z\":0}}", 0, List.of(),
                    "lm-local", "qwen", Llm.SCOPE_CLASSIC, "resp_accepted_one");
            T.ok("accepted page stores stateful checkpoint", first);
            T.eq("checkpoint is scoped to profile and model", "resp_accepted_one",
                    Campaign.providerResponseId("lm-local", "qwen", Llm.SCOPE_CLASSIC));
            T.eq("other model cannot inherit checkpoint", "",
                    Campaign.providerResponseId("lm-local", "other", Llm.SCOPE_CLASSIC));
            T.eq("safe planner cannot inherit classic checkpoint", "",
                    Campaign.providerResponseId("lm-local", "qwen", Llm.SCOPE_SAFE));
            Map<String, Object> stored = JsonParse.parseObject(Files.readString(
                    fixture.resolve("pzstory/campaign.json"), StandardCharsets.UTF_8));
            T.eq("schema records narrator-scoped checkpoints", 9,
                    JsonParse.num(stored, "schema", -1));
            T.eq("checkpoint scope is durable", Llm.SCOPE_CLASSIC,
                    JsonParse.str(stored, "providerSessionScope", ""));
            T.ok("hidden seed never entered canon",
                    !stored.toString().contains("HISTORY_READY_V2"));

            Campaign.reset(); Campaign.load();
            T.eq("checkpoint survives save reload", "resp_accepted_one",
                    Campaign.providerResponseId("lm-local", "qwen", Llm.SCOPE_CLASSIC));

            Path blocked = fixture.resolve("pzstory/campaign.json.tmp");
            Files.createDirectories(blocked);
            Files.writeString(blocked.resolve("guard"), "x", StandardCharsets.UTF_8);
            boolean failed = Campaign.commitGeneratedPage(
                    Campaign.generation(), "", "Second", words("later", 60),
                    "1993-07-09 09:30", List.of(), "",
                    "{\"position\":{\"x\":2,\"y\":1,\"z\":0}}", 0, List.of(),
                    "lm-local", "qwen", Llm.SCOPE_SAFE, "resp_must_not_commit");
            T.ok("save failure rejects stateful page", !failed);
            T.eq("save failure rolls checkpoint back", "resp_accepted_one",
                    Campaign.providerResponseId("lm-local", "qwen", Llm.SCOPE_CLASSIC));
            T.ok("history cannot reseed after page one",
                    !Campaign.commitProviderSeed(Campaign.generation(),
                            "lm-local", "qwen", Llm.SCOPE_CLASSIC,
                            "resp_illegal_reseed"));
        } catch (Throwable t) {
            T.ok("stateful fixture completed: " + t, false);
        } finally {
            Campaign.reset();
            System.clearProperty("pzstory.test.root");
            deleteTree(fixture);
        }
    }

    private static String words(String word, int count) {
        return (word + " ").repeat(count).strip();
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }
}
