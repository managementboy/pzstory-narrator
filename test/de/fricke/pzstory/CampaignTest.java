package de.fricke.pzstory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Campaign mutations either reach durable storage together or do not exist. */
public final class CampaignTest {

    public static void run() {
        T.group("Campaign - transactional generation and recovery");
        Path fixture = null;
        try {
            fixture = Files.createTempDirectory("pzstory-campaign-test-");
            System.setProperty("pzstory.test.root", fixture.toString());
            Campaign.reset();

            T.ok("scenario is stored once", Campaign.setScenario("road"));
            T.ok("scenario cannot change mid-book", !Campaign.setScenario("survival"));
            T.eq("first NEXT note stored", "will steer the next page",
                    Campaign.addNote("direction", "first direction"));
            Campaign.PromptNotes captured = Campaign.promptNotes();
            T.eq("one direction captured", 1, captured.directionCount);
            T.eq("late NEXT note stored", "will steer the next page",
                    Campaign.addNote("direction", "late direction"));

            boolean poisonedState = Campaign.commitGeneratedPage(
                    Campaign.generation(), words("premise", 30), "Must Not Commit",
                    words("page", 60), "1993-07-10 08:59",
                    List.of("must not remain"), "must not remain", "{broken",
                    captured.directionCount);
            T.ok("malformed continuity state rejects the whole page", !poisonedState);
            T.eq("malformed state leaves archive empty", 0, Campaign.pageCount());
            T.eq("malformed state consumes no direction", 2, Campaign.directions().size());

            boolean committed = Campaign.commitGeneratedPage(
                    Campaign.generation(), words("premise", 30), "Safe Page",
                    words("page", 60), "1993-07-10 09:00",
                    List.of("they now distrust the quiet"), "check the road",
                    "{\"position\":{\"x\":1,\"y\":2,\"z\":0}}",
                    captured.directionCount);
            T.ok("valid page commits", committed);
            T.eq("one page visible after commit", 1, Campaign.pageCount());
            T.eq("only captured direction is consumed",
                    List.of("late direction"), Campaign.directions());
            T.eq("canon committed with page", 1, Campaign.canon().size());
            T.ok("typed canon is projected into structured prompt",
                    Campaign.history(10000).contains("STRUCTURED STORY MEMORY"));
            int beforeRepeat = Campaign.pageCount();
            T.ok("repeated title is rejected before mutation",
                    !Campaign.commitGeneratedPage(Campaign.generation(), "",
                            "safe page", words("different", 60),
                            "1993-07-10 09:30", List.of("must not remain"), "",
                            "{\"position\":{\"x\":1,\"y\":2,\"z\":0}}", 0));
            T.eq("title rejection keeps archive unchanged", beforeRepeat,
                    Campaign.pageCount());
            T.ok("title rejection keeps fact memory unchanged",
                    !Campaign.canon().contains("must not remain"));
            T.ok("recent wording guidance names prior title",
                    Campaign.repetitionGuidance().contains("Safe Page"));
            Campaign.addCanon(List.of(
                    "[thread] setup red-radio: the red radio repeats a name"));
            T.ok("setup enters deliberate thread memory",
                    Campaign.threadMemoryJson().contains("\"status\":\"open\""));
            Campaign.addCanon(List.of(
                    "[thread] payoff red-radio: the caller was identified"));
            T.ok("matching payoff closes the setup",
                    Campaign.threadMemoryJson().contains("\"status\":\"paid\""));
            for (int i = 0; i < 3; i++) {
                T.ok("routine action " + (i + 1) + " is stored", Campaign.recordEvent(
                        StoryEvent.CRAFTED, "They crafted a spear.", 58,
                        "1993-07-10 09:" + (40 + i), "room:garage", "the garage", "game"));
            }
            T.ok("three same-place actions become routine evidence",
                    Campaign.history(20000).contains("repeatedly crafted at the garage"));
            T.ok("routine prompt hides local place identity",
                    !Campaign.history(20000).contains("room:garage"));

            Path store = fixture.resolve("pzstory/campaign.json");
            Path backup = fixture.resolve("pzstory/campaign.json.bak");
            T.ok("campaign exists", Files.isRegularFile(store));
            T.ok("last-known-good backup exists", Files.isRegularFile(backup));

            Path blockedTmp = store.resolveSibling("campaign.json.tmp");
            Files.createDirectories(blockedTmp);
            Files.writeString(blockedTmp.resolve("guard"), "x", StandardCharsets.UTF_8);
            String factsBeforeFailure = Campaign.factMemoryJson();
            String threadsBeforeFailure = Campaign.threadMemoryJson();
            String continuityBeforeFailure = Campaign.continuityMemoryJson();
            boolean failedCommit = Campaign.commitGeneratedPage(
                    Campaign.generation(), "", "Must Roll Back",
                    words("later", 60), "1993-07-10 10:00",
                    List.of("must not remain",
                            "[thread] setup rollback-key: must not remain"), "must not remain",
                    "{\"position\":{\"x\":2,\"y\":2,\"z\":0}}", 1);
            T.ok("disk failure rejects generated page", !failedCommit);
            T.eq("failed commit leaves archive unchanged", 1, Campaign.pageCount());
            T.eq("failed commit preserves NEXT note",
                    List.of("late direction"), Campaign.directions());
            T.ok("failed commit rolls canon back",
                    !Campaign.canon().contains("must not remain"));
            T.eq("failed commit rolls structured facts back",
                    factsBeforeFailure, Campaign.factMemoryJson());
            T.eq("failed commit rolls deliberate threads back",
                    threadsBeforeFailure, Campaign.threadMemoryJson());
            T.eq("failed commit leaves continuity evidence unchanged",
                    continuityBeforeFailure, Campaign.continuityMemoryJson());
            String toggleBefore = Campaign.todoJson();
            T.ok("failed task edit reports failure", !Campaign.toggleTodo(1));
            T.eq("failed task edit rolls memory back", toggleBefore,
                    Campaign.todoJson());
            deleteTree(blockedTmp);

            String todoBefore = Campaign.todoJson();
            Files.createDirectories(blockedTmp);
            Files.writeString(blockedTmp.resolve("guard"), "x", StandardCharsets.UTF_8);
            T.ok("failed ordinary mutation reports failure",
                    !Campaign.addTodo("this write must fail", "player"));
            T.eq("failed ordinary mutation rolls memory back",
                    todoBefore, Campaign.todoJson());
            deleteTree(blockedTmp);

            // One successful write after the page makes that page the backup
            // generation, then an unreadable primary should recover it.
            T.ok("checkpoint write succeeds", Campaign.addTodo("checkpoint", "player"));
            Files.writeString(store, "{broken", StandardCharsets.UTF_8);
            Campaign.reset();
            Campaign.load();
            T.eq("backup recovery retains last complete page", 1, Campaign.pageCount());
            T.eq("backup recovery retains unspent direction",
                    List.of("late direction"), Campaign.directions());
            T.ok("unreadable primary is preserved", hasCorruptCopy(store.getParent()));
        } catch (Throwable t) {
            T.ok("campaign fixture completed: " + t, false);
        } finally {
            Campaign.reset();
            System.clearProperty("pzstory.test.root");
            deleteTree(fixture);
        }
    }

    private static boolean hasCorruptCopy(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            return files.anyMatch(path -> path.getFileName().toString()
                    .startsWith("campaign.json.corrupt-"));
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
