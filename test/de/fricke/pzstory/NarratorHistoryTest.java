package de.fricke.pzstory;

/** Narrator history knows the arc without leaking it into survivor knowledge. */
public final class NarratorHistoryTest {
    public static void run() {
        T.group("Narrator history - private temporal backstory");
        String rules = NarratorHistory.SYSTEM_CONTEXT;
        String timeline = NarratorHistory.TIMELINE;

        T.ok("Steam guide is attributed by title and workshop id",
                timeline.contains("Project Zomboid Lore Guide")
                        && timeline.contains("3490625605"));
        T.ok("newspaper guide is attributed by title and workshop id",
                timeline.contains("Full list of newspapers")
                        && timeline.contains("3389064477"));
        T.ok("default day zero is explicit",
                rules.contains("day 0 is July 9, 1993")
                        && timeline.contains("DAY 0 — JULY 9"));
        T.ok("future collapse remains dated narrator knowledge",
                timeline.contains("Day 5 / July 14")
                        && timeline.contains("Day 7 / July 16")
                        && timeline.contains("Days 8–9 / July 17–18"));
        T.ok("future events are forbidden as present facts",
                rules.contains("has NOT happened yet")
                        && rules.contains("never spoil a coming broadcast"));
        T.ok("history is not survivor memory",
                rules.contains("The narrator knows this chronology; the survivor does not")
                        && rules.contains("Never turn it into \"she remembers,\""));
        T.ok("printed claims are dated access-gated evidence",
                rules.contains("PUBLICATION DISCIPLINE")
                        && rules.contains("not necessarily what was true")
                        && rules.contains("automatically knows")
                        && rules.contains("information may already be stale"));
        T.ok("newspapers preserve the ordinary world's narrowing atmosphere",
                timeline.contains("THE ORDINARY WORLD NARROWS")
                        && timeline.contains("telephone and early internet outage")
                        && timeline.contains("foul regional smell")
                        && timeline.contains("severe flu-like cases"));
        T.ok("missing editions are not invented",
                timeline.contains("no dated editions for July 8–12")
                        && timeline.contains("invent missing headlines"));
        T.ok("broadcasts require a dated accessible receiver",
                rules.contains("Television and radio")
                        && rules.contains("receiver merely being visible")
                        && rules.contains("Use a specific broadcast")
                        && rules.contains("canon establish access"));
        T.ok("annotated maps cannot create imaginary survivor caches",
                rules.contains("Annotated maps are micro-histories")
                        && rules.contains("invent a cache")
                        && rules.contains("generic map item"));
        T.ok("environmental clues must exist in live state",
                rules.contains("Environmental storytelling")
                        && rules.contains("physical details that STATE actually exposes")
                        && rules.contains("Do not manufacture barricades"));
        T.ok("physical media contents require actual access",
                rules.contains("VHS tapes, CDs and home recordings")
                        && rules.contains("Possession alone does not")
                        && rules.contains("played, watched, heard"));
        T.ok("lore arrives as partial human discovery",
                rules.contains("Discovery should feel earned, partial and human"));
        T.ok("late newspaper knowledge stays date gated",
                timeline.contains("NEWSPAPERS AFTER DAY 0")
                        && timeline.contains("July 13: print reports")
                        && timeline.contains("July 16: the Kentucky Herald"));
        T.ok("infection origin stays unresolved",
                rules.contains("origin of the infection remains unconfirmed")
                        && timeline.contains("do not prove the infection's origin"));
        T.ok("history asks for atmosphere rather than a lore list",
                rules.contains("SCENE BEFORE SUMMARY")
                        && rules.contains("Do not recite the timeline"));
        T.ok("safe planner receives the same private history boundary",
                ValidatedNarrator.plannerSystemPrompt().contains(
                        "NARRATOR-ONLY KNOX EVENT HISTORY"));
        T.eq("bootstrap acknowledgement version follows the expanded seed",
                "HISTORY_READY_V2", NarratorHistory.ACK);
        T.ok("bootstrap uses a tight output cap",
                source().contains("TIMELINE, 24,"));
        T.ok("hidden boot override is last in the retained system prompt",
                source().contains("narratorSystem + \"\\n\\n\" + BOOT_TURN")
                        && source().contains("safeScope, seedSystem, \"\", TIMELINE"));
    }

    private static String source() {
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of(
                    "src/de/fricke/pzstory/NarratorHistory.java"));
        } catch (java.io.IOException e) {
            return "";
        }
    }
}
