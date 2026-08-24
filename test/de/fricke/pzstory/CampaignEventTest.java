package de.fricke.pzstory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** 2.0 events, pages and migration share one durable campaign boundary. */
public final class CampaignEventTest {

    public static void run() {
        T.group("Campaign 2.0 - transactional event consumption");
        Path fixture = null;
        try {
            fixture = Files.createTempDirectory("pzstory-event-campaign-");
            System.setProperty("pzstory.test.root", fixture.toString());
            Campaign.reset();
            Path store = fixture.resolve("pzstory/campaign.json");

            T.ok("first observation creates baseline",
                    Campaign.observeState(before(), "1993-07-11 09:00"));
            T.eq("baseline creates no fictional event", 0, Campaign.pendingEventCount());
            String baselineDisk = Files.readString(store, StandardCharsets.UTF_8);
            T.ok("background low-value observation stays in memory",
                    Campaign.observeState(lowChange(), "1993-07-11 09:01", false));
            T.eq("low-value observer does not rewrite whole book immediately",
                    baselineDisk, Files.readString(store, StandardCharsets.UTF_8));
            T.eq("batched event is available in memory", 1, Campaign.pendingEventCount());
            T.ok("forced checkpoint before request succeeds",
                    Campaign.observeState(lowChange(), "1993-07-11 09:01", true));
            T.ok("forced checkpoint reaches disk",
                    !baselineDisk.equals(Files.readString(store, StandardCharsets.UTF_8)));
            T.ok("changed observation is durable",
                    Campaign.observeState(after(), "1993-07-11 09:05"));
            T.ok("game wound enters typed fact memory",
                    Campaign.factMemoryJson().contains("game:injury:partsBitten")
                            && Campaign.factMemoryJson().contains("\"source\":\"game\""));
            T.ok("second kill with held item is observed",
                    Campaign.observeState(armed(2), "1993-07-11 09:05:10"));
            T.ok("third kill with same item is observed",
                    Campaign.observeState(armed(3), "1993-07-11 09:05:20"));
            T.ok("repeated kills establish weapon familiarity without exposing id",
                    Campaign.history(20000).contains("becoming familiar")
                            && !Campaign.history(20000).contains("item-445"));
            T.ok("meaningful changes are pending", Campaign.pendingEventCount() >= 3);
            EventJournal.Capture captured = Campaign.promptEvents();
            int capturedCount = captured.ids.size();
            T.ok("request captures exact event ids", capturedCount >= 3);

            T.ok("late hook event is saved", Campaign.recordEvent(
                    "late_hook", "Something happened after WRITE was pressed.", 40,
                    "1993-07-11 09:06", "local:late", "the bedroom", "game"));
            boolean committed = Campaign.commitGeneratedPage(
                    Campaign.generation(), words("premise", 30), "Events Remembered",
                    words("page", 60), "1993-07-11 09:07", List.of(), "",
                    after(), 0, captured.ids);
            T.ok("page and captured events commit together", committed);
            T.eq("late event survives completion", 1, Campaign.pendingEventCount());

            int pagesBeforeStale = Campaign.pageCount();
            T.ok("unknown captured event id rejects page",
                    !Campaign.commitGeneratedPage(
                            Campaign.generation(), "", "Stale Event Page",
                            words("stale", 60), "1993-07-11 09:08", List.of(), "",
                            after(), 0, List.of(999_999L)));
            T.eq("stale event rejection rolls page back",
                    pagesBeforeStale, Campaign.pageCount());
            T.eq("stale event rejection preserves pending event",
                    1, Campaign.pendingEventCount());

            Map<String, Object> disk = JsonParse.parseObject(
                    Files.readString(store, StandardCharsets.UTF_8));
            T.eq("successful mutation upgrades store to schema 7", 7,
                    JsonParse.num(disk, "schema", 0));
            T.ok("event journal is embedded in campaign transaction",
                    disk.get("eventJournal") instanceof Map<?, ?>);
            T.ok("world memory is embedded in campaign transaction",
                    disk.get("worldMemory") instanceof Map<?, ?>);
            Campaign.sawRoom("kitchen", "12345");
            Campaign.sawRoom("kitchen", "67890");
            String seenPrompt = Campaign.seenForPrompt();
            T.ok("legacy seen-room prompt hides engine ids",
                    !seenPrompt.contains("12345") && !seenPrompt.contains("67890"));
            T.ok("same-named room distinction remains semantic",
                    seenPrompt.contains("more than one different room"));

            Campaign.reset();
            Campaign.load();
            T.eq("pending event survives reload", 1, Campaign.pendingEventCount());
            T.eq("committed page survives reload", 1, Campaign.pageCount());

            int pendingBeforeFailure = Campaign.pendingEventCount();
            String memoryBeforeFailure = Campaign.worldMemoryJson();
            String continuityBeforeFailure = Campaign.continuityMemoryJson();
            Path blockedTmp = store.resolveSibling("campaign.json.tmp");
            Files.createDirectories(blockedTmp);
            Files.writeString(blockedTmp.resolve("guard"), "x", StandardCharsets.UTF_8);
            T.ok("failed observation reports failure",
                    !Campaign.observeState(third(), "1993-07-11 09:10"));
            T.eq("failed observation rolls events back", pendingBeforeFailure,
                    Campaign.pendingEventCount());
            T.eq("failed observation rolls world memory back", memoryBeforeFailure,
                    Campaign.worldMemoryJson());
            T.eq("failed observation rolls continuity evidence back",
                    continuityBeforeFailure, Campaign.continuityMemoryJson());
            deleteTree(blockedTmp);
        } catch (Throwable t) {
            T.ok("event campaign fixture completed: " + t, false);
        } finally {
            Campaign.reset();
            System.clearProperty("pzstory.test.root");
            deleteTree(fixture);
        }

        migration();
        schema2Migration();
        schema3Migration();
    }

    private static void migration() {
        T.group("Campaign 2.0 - schema 1 migration");
        Path fixture = null;
        try {
            fixture = Files.createTempDirectory("pzstory-schema1-");
            Path dir = fixture.resolve("pzstory");
            Files.createDirectories(dir);
            String old = "{\"schema\":1,\"canon\":[\"old fact\"],"
                    + "\"lastState\":" + quote(before()) + ",\"pages\":[]}";
            Files.writeString(dir.resolve("campaign.json"), old, StandardCharsets.UTF_8);
            System.setProperty("pzstory.test.root", fixture.toString());
            Campaign.reset();
            Campaign.load();
            T.eq("schema 1 canon loads", List.of("old fact"), Campaign.canon());
            T.ok("legacy last state seeds structured place memory",
                    Campaign.worldMemoryJson().contains("livingroom"));
            T.ok("first mutation after migration saves", Campaign.addTodo("migrated", "player"));
            Map<String, Object> upgraded = JsonParse.parseObject(Files.readString(
                    dir.resolve("campaign.json"), StandardCharsets.UTF_8));
            T.eq("schema 1 saves forward as schema 7", 7,
                    JsonParse.num(upgraded, "schema", 0));
            T.ok("legacy canon becomes typed fact memory",
                    upgraded.get("factMemory") instanceof Map<?, ?>);
        } catch (Throwable t) {
            T.ok("schema migration fixture completed: " + t, false);
        } finally {
            Campaign.reset();
            System.clearProperty("pzstory.test.root");
            deleteTree(fixture);
        }
    }

    private static void schema2Migration() {
        T.group("Campaign 2.0 - schema 2 story-memory migration");
        Path fixture = null;
        try {
            fixture = Files.createTempDirectory("pzstory-schema2-");
            Path dir = fixture.resolve("pzstory");
            Files.createDirectories(dir);
            String old = "{\"schema\":2,\"canon\":["
                    + "\"(the player observes) the garage is home\","
                    + "\"the quiet feels unsafe\"],\"pages\":[]}";
            Files.writeString(dir.resolve("campaign.json"), old,
                    StandardCharsets.UTF_8);
            System.setProperty("pzstory.test.root", fixture.toString());
            Campaign.reset(); Campaign.load();
            String facts = Campaign.factMemoryJson();
            T.ok("schema 2 player observation keeps player provenance",
                    facts.contains("\"source\":\"player\"")
                            && facts.contains("the garage is home"));
            T.ok("schema 2 narrator canon keeps legacy provenance",
                    facts.contains("\"source\":\"legacy\"")
                            && facts.contains("the quiet feels unsafe"));
            T.ok("migration is saved on next mutation",
                    Campaign.addTodo("checkpoint", "player"));
            Map<String, Object> upgraded = JsonParse.parseObject(Files.readString(
                    dir.resolve("campaign.json"), StandardCharsets.UTF_8));
            T.eq("schema 2 saves forward as schema 7", 7,
                    JsonParse.num(upgraded, "schema", 0));
        } catch (Throwable t) {
            T.ok("schema 2 migration fixture completed: " + t, false);
        } finally {
            Campaign.reset();
            System.clearProperty("pzstory.test.root");
            deleteTree(fixture);
        }
    }

    private static void schema3Migration() {
        T.group("Campaign 2.0 - schema 3 thread-memory migration");
        Path fixture = null;
        try {
            fixture = Files.createTempDirectory("pzstory-schema3-");
            Path dir = fixture.resolve("pzstory"); Files.createDirectories(dir);
            String old = "{\"schema\":3,\"canon\":[\"the garage feels familiar\"],"
                    + "\"factMemory\":{\"nextId\":2,\"facts\":[{\"id\":1,"
                    + "\"type\":\"belief\",\"text\":\"the garage feels familiar\","
                    + "\"source\":\"narrator\",\"confidence\":55,\"page\":1,"
                    + "\"supersededBy\":0}]},\"pages\":[]}";
            Files.writeString(dir.resolve("campaign.json"), old, StandardCharsets.UTF_8);
            System.setProperty("pzstory.test.root", fixture.toString());
            Campaign.reset(); Campaign.load();
            T.eq("schema 3 typed facts survive", List.of("the garage feels familiar"),
                    Campaign.canon());
            T.ok("schema 3 begins with empty thread memory",
                    Campaign.threadMemoryJson().contains("\"threads\":[]"));
            T.ok("schema 3 migration checkpoints", Campaign.addTodo("checkpoint", "player"));
            Map<String, Object> upgraded = JsonParse.parseObject(Files.readString(
                    dir.resolve("campaign.json"), StandardCharsets.UTF_8));
            T.eq("schema 3 saves forward as schema 7", 7,
                    JsonParse.num(upgraded, "schema", 0));
            T.ok("thread memory is added transactionally",
                    upgraded.get("threadMemory") instanceof Map<?, ?>);
            T.ok("continuity memory is added transactionally",
                    upgraded.get("continuityMemory") instanceof Map<?, ?>);
        } catch (Throwable t) {
            T.ok("schema 3 migration fixture completed: " + t, false);
        } finally {
            Campaign.reset(); System.clearProperty("pzstory.test.root"); deleteTree(fixture);
        }
    }

    private static String before() {
        return """
                {"position":{"x":10,"y":20,"z":0,"room":"livingroom",
                              "roomId":"room-a","floor":"on the ground floor"},
                 "character":{"zombieKills":0,"asleep":false},
                 "health":{"overall":100,"partsBitten":0,"partsScratched":0,
                           "partsBleeding":0},
                 "utilities":{"mainsPower":true,"mainsWater":true}}
                """;
    }

    private static String after() {
        return """
                {"position":{"x":12,"y":20,"z":1,"room":"bedroom",
                              "roomId":"room-b","floor":"one floor up"},
                 "character":{"zombieKills":1,"asleep":false},
                 "health":{"overall":80,"partsBitten":1,"partsScratched":0,
                           "partsBleeding":1},
                 "utilities":{"mainsPower":false,"mainsWater":true},
                 "theDead":{"comingForThem":"one or two"}}
                """;
    }

    private static String lowChange() {
        return """
                {"position":{"x":11,"y":20,"z":0,"room":"kitchen",
                              "roomId":"room-low","floor":"on the ground floor"},
                 "character":{"zombieKills":0,"asleep":false},
                 "health":{"overall":100,"partsBitten":0,"partsScratched":0,
                           "partsBleeding":0},
                 "utilities":{"mainsPower":true,"mainsWater":true}}
                """;
    }

    private static String third() {
        return """
                {"position":{"x":15,"y":20,"z":1,"room":"bathroom",
                              "roomId":"room-c","floor":"one floor up"},
                 "character":{"zombieKills":1,"asleep":false},
                 "health":{"overall":75,"partsBitten":1,"partsScratched":1,
                           "partsBleeding":1},
                 "utilities":{"mainsPower":false,"mainsWater":false},
                 "theDead":{}}
                """;
    }

    private static String armed(int kills) {
        return "{\"position\":{\"x\":12,\"y\":20,\"z\":1,\"room\":\"bedroom\","
                + "\"roomId\":\"room-b\",\"floor\":\"one floor up\"},"
                + "\"character\":{\"zombieKills\":" + kills
                + ",\"asleep\":false,\"primaryHand\":\"Hammer\","
                + "\"primaryHandId\":\"item-445\"},"
                + "\"health\":{\"overall\":80,\"partsBitten\":1,"
                + "\"partsScratched\":0,\"partsBleeding\":1},"
                + "\"utilities\":{\"mainsPower\":false,\"mainsWater\":true}}";
    }

    private static String quote(String value) {
        return Json.of(value);
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
