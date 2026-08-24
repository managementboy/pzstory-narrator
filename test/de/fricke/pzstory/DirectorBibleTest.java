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

        DirectorBible succeeded = new DirectorBible();
        succeeded.freeze(Scenario.byId("road"), "Find family.");
        T.ok("unknown objective state is rejected",
                !succeeded.transition("paused", "not a real state"));
        T.ok("blank transition evidence is rejected",
                !succeeded.transition("succeeded", "  "));
        T.ok("active objective can succeed",
                succeeded.transition("succeeded", "The destination was reached."));
        T.ok("terminal objective cannot reopen",
                !succeeded.transition("failed", "Too late."));
        T.ok("terminal state survives JSON", roundTrip(succeeded).snapshot()
                .equals(succeeded.snapshot()));

        for (String terminal : new String[] { "failed", "impossible" }) {
            DirectorBible candidate = new DirectorBible();
            candidate.freeze(Scenario.byId("survival"), "Keep going.");
            T.ok("active objective can become " + terminal,
                    candidate.transition(terminal, "Deterministic test evidence."));
        }

        DirectorBible evidence = new DirectorBible();
        evidence.freeze(Scenario.byId("conspiracy"), "Find the truth.");
        T.ok("private clue is absent before evidence",
                !evidence.publicPrompt().contains("first apparent explanation"));
        T.ok("unrelated event supplies no objective evidence",
                !evidence.observe(1, StoryEvent.KILL, "They killed one zombie."));
        T.ok("wrong item supplies no keyword evidence",
                !evidence.observe(2, StoryEvent.ITEM_ACQUIRED, "They acquired an axe."));
        T.ok("matching event supplies evidence",
                evidence.observe(3, StoryEvent.ITEM_ACQUIRED, "They acquired a newspaper."));
        T.ok("matching evidence completes objective",
                evidence.statusJson().contains("\"objectiveState\":\"succeeded\""));
        T.ok("matching evidence reveals exactly one clue",
                evidence.statusJson().contains("\"revealed\":1")
                        && evidence.publicPrompt().contains("first apparent explanation"));
        T.ok("later clue remains hidden",
                !evidence.publicPrompt().contains("later discovery must connect"));
        T.ok("same event cannot count twice",
                !evidence.observe(3, StoryEvent.ITEM_ACQUIRED, "They acquired a newspaper."));
        T.eq("evidence survives save and load", evidence.snapshot(), roundTrip(evidence).snapshot());

        DirectorBible rerouted = new DirectorBible();
        rerouted.freeze(Scenario.byId("road"), "Cross Knox County.");
        String fixedTruth = rerouted.snapshot().truth();
        T.ok("impossible objective fails forward",
                rerouted.failForward("The only vehicle was destroyed."));
        T.ok("replacement is the only active objective",
                rerouted.statusJson().contains("\"objectiveState\":\"active\"")
                        && rerouted.statusJson().contains("\"previousObjectives\":1"));
        T.eq("fail-forward preserves fixed truth", fixedTruth, rerouted.snapshot().truth());
        T.ok("old-route evidence cannot complete replacement",
                !rerouted.observe(20, StoryEvent.VEHICLE_ENTERED, "They entered a wreck."));
        T.ok("replacement evidence can complete replacement",
                rerouted.observe(21, StoryEvent.PLACE_CHANGED, "They reached another place."));
        T.ok("fail-forward history survives persistence",
                roundTrip(rerouted).snapshot().equals(rerouted.snapshot()));

        String diagnostic = rerouted.statusJson("director");
        T.ok("diagnostic exposes objective operations",
                diagnostic.contains("\"mode\":\"director\"")
                        && diagnostic.contains("\"evidenceCount\":1")
                        && diagnostic.contains("\"transitionReason\"")
                        && diagnostic.contains("\"revealed\":1"));
        T.ok("diagnostic hides private plan",
                !diagnostic.contains("underlying")
                        && !diagnostic.contains("later discovery must connect"));

        String legacy = "{\"frozen\":true,\"truth\":\"fixed truth\","
                + "\"resolution\":\"fixed resolution\",\"objective\":\"find a map\","
                + "\"objectiveState\":\"active\",\"hiddenRevelations\":[\"private clue\"],"
                + "\"revealedFacts\":[]}";
        DirectorBible migrated = new DirectorBible();
        migrated.load(JsonParse.parse(legacy), Scenario.byId("road"));
        T.ok("alpha 7 Director bible gains an evidence rule",
                migrated.statusJson().contains("\"evidenceType\":\"vehicle_entered\"")
                        && migrated.statusJson().contains("\"evidenceRequired\":1"));
        T.eq("alpha 7 migration preserves fixed truth", "fixed truth",
                migrated.snapshot().truth());
    }

    private static DirectorBible roundTrip(DirectorBible bible) {
        Json json = new Json().obj();
        bible.write(json); json.endObj();
        DirectorBible loaded = new DirectorBible();
        loaded.load(JsonParse.parseObject(json.toString()).get("directorBible"));
        return loaded;
    }
}
