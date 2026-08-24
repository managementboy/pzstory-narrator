package de.fricke.pzstory;

import java.util.Map;

/** Place memory keeps identity local and sends only qualitative familiarity. */
public final class WorldMemoryTest {

    public static void run() {
        T.group("World memory - stable places and return visits");
        WorldMemory memory = new WorldMemory();
        T.ok("first place changes memory", memory.observe(place("room-secret-a", "kitchen"),
                "1993-07-09 09:00"));
        T.ok("same place does not cause a disk-worthy change",
                !memory.observe(place("room-secret-a", "kitchen"),
                        "1993-07-09 09:01"));
        T.ok("second place changes memory", memory.observe(place("room-secret-b", "bedroom"),
                "1993-07-09 09:02"));
        T.ok("return changes memory", memory.observe(place("room-secret-a", "kitchen"),
                "1993-07-09 09:03"));
        T.eq("two stable places retained", 2, memory.size());
        String prompt = memory.prompt();
        T.ok("return is expressed qualitatively", prompt.contains("returned to"));
        T.ok("current place is identified", prompt.contains("where they are now"));
        T.ok("provider memory omits engine ids", !prompt.contains("room-secret"));

        Json json = new Json().obj();
        memory.write(json);
        Map<String, Object> parsed = JsonParse.parseObject(json.endObj().toString());
        WorldMemory loaded = new WorldMemory();
        loaded.load(parsed.get("worldMemory"));
        T.eq("world memory round trips", 2, loaded.size());
        T.ok("round-trip prompt remains private", !loaded.prompt().contains("room-secret"));
    }

    private static String place(String id, String room) {
        return "{\"position\":{\"x\":10,\"y\":20,\"z\":0,"
                + "\"room\":\"" + room + "\",\"roomId\":\"" + id
                + "\",\"floor\":\"on the ground floor\"}}";
    }
}
