package de.fricke.pzstory;

import java.util.List;
import java.util.Map;

/** Private engine telemetry stays local while story-relevant facts survive. */
public final class NarrativeStateTest {

    public static void run() {
        T.group("NarrativeState - provider data minimisation");
        String raw = """
                {
                  "schema":1,
                  "modVersion":"1.0",
                  "wallClock":123,
                  "time":{"year":1993,"worldAgeHours":87,"daysSinceItBegan":3},
                  "readErrors":["private path"],
                  "character":{"username":"account","forename":"Sam",
                               "female":false,"pronouns":"they/them",
                               "hoursSurvived":87,"zombieKills":8,"inventoryWeight":12.4},
                  "position":{"x":100,"y":200,"z":1,"chunkX":12,"cellY":3,
                              "floor":"one floor up","room":"kitchen","roomId":"17",
                              "building":{"id":88,"x":90,"y":190,"w":10,"h":20}},
                  "stats":{"hunger":0.4},
                  "threat":{"zombiesVisible":99},
                  "theDead":{"withinSight":"a few"},
                  "nutrition":{"calories":-1200},
                  "weather":{"temperatureC":7,"feels":"cold"},
                  "bags":[{"name":"Duffel Bag","id":"481","contents":["Map"]}],
                  "health":{"overall":72,"infected":false,"partsBleeding":1,
                            "wounds":[{"part":"hand","health":70,"pain":3,
                                       "scratched":true}]},
                  "skills":{"Carpentry":{"level":4,"xp":821,
                                           "fromTheirTrade":2}}
                }
                """;
        Map<String, Object> sent = JsonParse.parseObject(NarrativeState.fromRaw(raw));
        T.ok("wall clock removed", !sent.containsKey("wallClock"));
        T.ok("diagnostics removed", !sent.containsKey("readErrors"));
        T.ok("raw stats removed", !sent.containsKey("stats"));
        T.ok("duplicate threat counters removed", !sent.containsKey("threat"));
        T.ok("semantic threat retained", sent.containsKey("theDead"));

        Map<String, Object> time = JsonParse.map(sent, "time");
        T.ok("exact world age removed", !time.containsKey("worldAgeHours"));
        T.eq("calendar retained", 1993.0, time.get("year"));

        Map<String, Object> character = JsonParse.map(sent, "character");
        T.ok("username removed", character != null && !character.containsKey("username"));
        T.eq("pronouns retained", "they/them", character.get("pronouns"));
        T.ok("raw survivor counters removed",
                !character.containsKey("hoursSurvived")
                        && !character.containsKey("zombieKills")
                        && !character.containsKey("inventoryWeight"));
        T.eq("survival time becomes semantic", "several days",
                character.get("timeSurvived"));
        T.eq("kill count becomes semantic", "seasoned",
                character.get("experienceWithTheDead"));

        Map<String, Object> position = JsonParse.map(sent, "position");
        T.ok("exact coordinates removed", position != null && !position.containsKey("x"));
        T.ok("room engine id removed", !position.containsKey("roomId"));
        T.eq("semantic room retained", "kitchen", position.get("room"));
        T.eq("building reduced to presence", Boolean.TRUE, position.get("insideBuilding"));

        Map<String, Object> weather = JsonParse.map(sent, "weather");
        T.ok("exact temperature removed", !weather.containsKey("temperatureC"));
        T.eq("semantic weather retained", "cold", weather.get("feels"));
        T.ok("nutrition telemetry removed", !sent.containsKey("nutrition"));

        @SuppressWarnings("unchecked")
        Map<String, Object> bag = (Map<String, Object>) ((List<?>) sent.get("bags")).get(0);
        T.ok("bag engine id removed", !bag.containsKey("id"));
        T.eq("bag contents retained", "Map", ((List<?>) bag.get("contents")).get(0));

        Map<String, Object> health = JsonParse.map(sent, "health");
        @SuppressWarnings("unchecked")
        Map<String, Object> wound = (Map<String, Object>) ((List<?>) health.get("wounds")).get(0);
        T.eq("wound meaning retained", Boolean.TRUE, wound.get("scratched"));
        T.eq("raw pain becomes semantic", Boolean.TRUE, wound.get("painful"));
        T.ok("raw health removed", !wound.containsKey("health"));

        Map<String, Object> skills = JsonParse.map(sent, "skills");
        Map<String, Object> carpentry = JsonParse.map(skills, "Carpentry");
        T.eq("skill level becomes a band", "practised", carpentry.get("ability"));
        T.ok("raw XP removed", !carpentry.containsKey("xp"));
        T.eq("trade becomes semantic", Boolean.TRUE, carpentry.get("fromTheirTrade"));

        T.group("NarrativeState - core snapshot failures are refused");
        T.throwsWith("reported reader error", "reported an error", () ->
                NarrativeState.fromRaw("{\"error\":\"no player\"}"));
        T.throwsWith("missing position", "missing character or position", () ->
                NarrativeState.fromRaw("{\"character\":{}}"));
    }
}
