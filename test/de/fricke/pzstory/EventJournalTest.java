package de.fricke.pzstory;

import java.util.List;
import java.util.Map;

/** Event selection is deterministic, bounded, private and transactional. */
public final class EventJournalTest {

    public static void run() {
        T.group("Story events - strict immutable records");
        T.throwsWith("invalid type is refused", "type", () -> StoryEvent.draft(
                "BAD EVENT", "", "", "", "something happened", "game", 20));
        T.throwsWith("invalid source is refused", "source", () -> StoryEvent.draft(
                "test_event", "", "", "", "something happened", "remote", 20));
        T.throwsWith("importance is bounded", "1..100", () -> StoryEvent.draft(
                "test_event", "", "", "", "something happened", "game", 101));

        T.group("Event detector - meaningful changes become facts");
        List<StoryEvent.Draft> detected = EventDetector.between(before(), after(),
                "1993-07-11 09:15");
        T.ok("room transition recorded", has(detected, StoryEvent.PLACE_CHANGED));
        T.ok("first kill recorded", has(detected, StoryEvent.KILL));
        T.ok("a kill outranks ambient noise",
                importance(detected, StoryEvent.KILL)
                        > importance(detected, StoryEvent.NOISE_STARTED));
        T.ok("bite outranks everything", importance(detected, StoryEvent.BITTEN) == 100);
        T.ok("power failure recorded", has(detected, StoryEvent.POWER_LOST));
        T.ok("pursuit recorded", has(detected, StoryEvent.PURSUIT_STARTED));
        T.ok("identical snapshots produce no events",
                EventDetector.between(after(), after(), "1993-07-11 09:16").isEmpty());
        T.ok("moving viewpoint does not invent a broken window",
                !has(EventDetector.between(shelter(10, 0), shelter(11, 1),
                        "1993-07-11 09:17"), StoryEvent.WINDOW_BROKEN));
        T.ok("stable viewpoint detects an actual broken window",
                has(EventDetector.between(shelter(10, 0), shelter(10, 1),
                        "1993-07-11 09:18"), StoryEvent.WINDOW_BROKEN));

        T.group("Event detector - carried item changes");
        List<StoryEvent.Draft> acquired = EventDetector.between(
                inventory("Hammer", 1, "Bandage", 2),
                inventory("Hammer", 2, "Bandage", 2),
                "1993-07-11 09:19");
        T.ok("a newly carried item is recorded",
                has(acquired, StoryEvent.ITEM_ACQUIRED));
        T.ok("acquired quantity is factual",
                summary(acquired, StoryEvent.ITEM_ACQUIRED)
                        .equals("They acquired Hammer."));
        T.ok("unchanged carried multiset makes no event",
                EventDetector.between(inventory("Hammer", 1, "Bandage", 2),
                        inventory("Bandage", 2, "Hammer", 1),
                        "1993-07-11 09:20").isEmpty());
        String manyBefore = "{\"carriedItems\":{}}";
        String manyAfter = "{\"carriedItems\":{" +
                "\"A\":1,\"B\":1,\"C\":1,\"D\":1,\"E\":1,\"F\":1}}";
        T.eq("one sample emits at most four acquired-item events", 4,
                count(EventDetector.between(manyBefore, manyAfter,
                        "1993-07-11 09:21"), StoryEvent.ITEM_ACQUIRED));

        T.group("Event journal - capture and acknowledgement");
        EventJournal journal = new EventJournal();
        for (StoryEvent.Draft draft : detected) journal.record(draft);
        EventJournal.Capture capture = journal.capture();
        T.ok("pending events enter the prompt", !capture.ids.isEmpty()
                && capture.text.contains("RECORDED EVENTS"));
        T.eq("typed capture matches the acknowledged id batch",
                capture.ids, capture.events.stream().map(e -> e.id).toList());
        T.ok("provider text omits stable room id",
                !capture.text.contains("room:second-room-engine-id"));
        int capturedCount = capture.ids.size();
        journal.record(StoryEvent.draft("late_event", "1993-07-11 09:16",
                "private:late", "the kitchen", "A later event occurred.",
                "game", 30));
        T.eq("late event is pending beside captured batch",
                capturedCount + 1, journal.pendingCount());
        T.eq("captured events acknowledged exactly", capturedCount,
                journal.markNarrated(capture.ids, 1));
        T.eq("late event survives old page completion", 1, journal.pendingCount());

        Json encoded = new Json().obj();
        journal.write(encoded);
        Map<String, Object> parsed = JsonParse.parseObject(encoded.endObj().toString());
        EventJournal loaded = new EventJournal();
        loaded.load(parsed.get("eventJournal"));
        T.eq("event journal round trips", journal.size(), loaded.size());
        T.eq("pending state round trips", journal.pendingCount(), loaded.pendingCount());

        T.group("Event journal - prompt selection is bounded");
        EventJournal crowded = new EventJournal();
        for (int i = 1; i <= 30; i++) {
            crowded.record(StoryEvent.draft("event_" + i, "", "", "",
                    "Event number " + i + " happened.", "snapshot", i));
        }
        EventJournal.Capture selected = crowded.capture();
        T.eq("at most twelve events selected", EventJournal.MAX_EVENTS_PER_PAGE,
                selected.ids.size());
        T.ok("highest significance wins", selected.text.contains("Event number 30")
                && !selected.text.contains("Event number 1 happened"));
    }

    private static boolean has(List<StoryEvent.Draft> events, String type) {
        for (StoryEvent.Draft event : events) if (event.type.equals(type)) return true;
        return false;
    }

    private static int importance(List<StoryEvent.Draft> events, String type) {
        for (StoryEvent.Draft event : events) {
            if (event.type.equals(type)) return event.importance;
        }
        return 0;
    }

    private static int count(List<StoryEvent.Draft> events, String type) {
        int count = 0;
        for (StoryEvent.Draft event : events) if (event.type.equals(type)) count++;
        return count;
    }

    private static String summary(List<StoryEvent.Draft> events, String type) {
        for (StoryEvent.Draft event : events) {
            if (event.type.equals(type)) return event.summary;
        }
        return "";
    }

    private static String inventory(String first, int firstCount,
                                    String second, int secondCount) {
        return "{\"carriedItems\":{\"" + first + "\":" + firstCount
                + ",\"" + second + "\":" + secondCount + "}}";
    }

    private static String before() {
        return """
                {"position":{"x":10,"y":20,"z":0,"room":"livingroom",
                              "roomId":"first-room-engine-id","floor":"on the ground floor"},
                 "character":{"zombieKills":0,"asleep":false},
                 "health":{"overall":100,"partsBitten":0,"partsScratched":0,
                           "partsBleeding":0},
                 "utilities":{"mainsPower":true,"mainsWater":true},
                 "theDead":{},
                 "skills":{"Carpentry":{"level":1}},
                 "weather":{"light":"bright"}}
                """;
    }

    private static String after() {
        return """
                {"position":{"x":12,"y":20,"z":1,"room":"bedroom",
                              "roomId":"second-room-engine-id","floor":"one floor up"},
                 "character":{"zombieKills":1,"asleep":false},
                 "health":{"overall":82,"partsBitten":1,"partsScratched":0,
                           "partsBleeding":1},
                 "utilities":{"mainsPower":false,"mainsWater":true},
                 "noise":{"what":"a noise close by"},
                 "theDead":{"comingForThem":"one or two"},
                 "skills":{"Carpentry":{"level":2}},
                 "weather":{"light":"bright"}}
                """;
    }

    private static String shelter(int x, int smashed) {
        return "{\"position\":{\"x\":" + x + ",\"y\":20,\"z\":0,"
                + "\"room\":\"livingroom\",\"roomId\":\"same-room\"},"
                + "\"here\":{\"windows\":{\"total\":1,\"smashed\":"
                + smashed + "}}}";
    }
}
