package de.fricke.pzstory;

/** The private plan exposes only its current objective and revealed facts. */
public final class DirectorBibleTest {
    public static void run() {
        T.group("Campaign Director - private frozen plan");
        DirectorBible bible = new DirectorBible();
        bible.freeze(Scenario.byId("road"), "They need to find their family.");
        T.ok("first page freezes a director bible", bible.frozen());
        String prompt = bible.publicPrompt();
        T.ok("provider sees exactly one active objective",
                prompt.contains("One major objective is active"));
        T.ok("hidden revelation is not provider-visible",
                !prompt.contains("first apparent explanation"));
        DirectorBible.Snapshot frozen = bible.snapshot();
        bible.freeze(Scenario.byId("survival"), "A different campaign.");
        T.eq("frozen plan cannot be regenerated", frozen, bible.snapshot());

        Json json = new Json().obj();
        bible.write(json); json.endObj();
        DirectorBible loaded = new DirectorBible();
        loaded.load(JsonParse.parseObject(json.toString()).get("directorBible"));
        T.eq("private plan survives save and load", bible.snapshot(), loaded.snapshot());
    }
}
