package de.fricke.pzstory;

import java.util.Map;

/** Familiarity is evidence-based, qualitative and private-id-safe. */
public final class ContinuityMemoryTest {
    public static void run() {
        T.group("Continuity evidence - thresholds and privacy");
        ContinuityMemory memory = new ContinuityMemory();
        memory.record("weapon", "item-local-445", "a hammer", "09:00");
        T.eq("one weapon use is not familiarity", "", memory.prompt());
        memory.record("weapon", "item-local-445", "a hammer", "09:05");
        T.ok("two weapon kills become qualitative familiarity",
                memory.prompt().contains("killed with a hammer more than once"));
        T.ok("local item identity never enters prompt",
                !memory.prompt().contains("item-local-445"));
        memory.record("vehicle", "vehicle-local-7", "Chevalier Nyala", "10:00");
        memory.record("vehicle", "vehicle-local-7", "Chevalier Nyala", "11:00");
        T.ok("vehicle return disclaims ownership",
                memory.prompt().contains("game has not proved ownership"));
        memory.record("rest", "room-local-3", "the upstairs bedroom", "day 1");
        memory.record("rest", "room-local-3", "the upstairs bedroom", "day 2");
        T.ok("repeated sleep does not invent safety",
                memory.prompt().contains("not necessarily safe"));
        memory.record("routine", "craft@room-local-3", "crafted at the upstairs bedroom", "1");
        memory.record("routine", "craft@room-local-3", "crafted at the upstairs bedroom", "2");
        T.ok("two actions are not yet a routine", !memory.prompt().contains("repeatedly crafted"));
        memory.record("routine", "craft@room-local-3", "crafted at the upstairs bedroom", "3");
        T.ok("three actions establish a routine", memory.prompt().contains("repeatedly crafted"));

        T.group("Continuity evidence - durable representation");
        Map<String, Object> root = JsonParse.parseObject(memory.json());
        ContinuityMemory loaded = new ContinuityMemory();
        loaded.load(root.get("continuityMemory"));
        T.eq("continuity memory round trips", memory.json(), loaded.json());
        T.ok("diagnostics retain local identity", memory.json().contains("item-local-445"));
    }
}
