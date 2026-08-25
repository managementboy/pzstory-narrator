package de.fricke.pzstory;

import java.util.ArrayList;
import java.util.List;

/** Closed-world planner output must never cross into the rendered page. */
public final class ValidatedNarratorTest {

    private static final String STATE = """
            {
              "character": {
                "forename": "Jordan",
                "surname": "Reed",
                "pronouns": "they/them",
                "occupation": "Fire Officer",
                "timeSurvived": "several days",
                "traits": [{"name":"Cowardly","kind":"a weakness"}]
              },
              "time": {"year":1993,"month":7,"day":13,"hour":18},
              "position": {"room":"storage room","floor":"on the ground floor"},
              "here": {
                "room":"storage room",
                "roomFeels":"small",
                "furniture":{"metal shelves":2},
                "doors":{"total":1,"locked":1}
              },
              "inHisHands":{"nothing":"Both hands are empty."},
              "bags":[
                {"name":"backpack","contents":[]},
                {"name":"duffel bag","contents":["bandage"]}
              ],
              "theDead":{"withinSight":"a few","comingForThem":"a few"},
              "weather":{"feels":"mild","light":"dim"},
              "utilities":{"mainsPower":true,"mainsWater":true}
            }
            """;

    private static final String DELTA = """
            ### WHAT HAS CHANGED SINCE THE LAST PAGE
            - A bandage moved between two carried bags without being gained or lost.
            """;

    private ValidatedNarratorTest() { }

    public static void run() {
        T.group("Validated narrator - live schema normalization");
        ValidatedNarrator.Session session = ValidatedNarrator.prepare(
                STATE, List.of(), DELTA, true, 200, 253000L, "stay cautious");
        T.ok("production-shaped state creates a useful catalog",
                session.factCount() >= 10);
        T.ok("nested bag contents are typed",
                session.hasFactContaining("duffel bag contains bandage"));
        T.ok("active pursuit is mandatory evidence",
                session.hasFactContaining("coming for Jordan Reed"));
        T.ok("occupation is retained as state-backed character context",
                session.hasFactContaining("fire officer"));

        String rendered = session.render(validPlan(session));
        PageResult parsed = PageResult.parse(rendered, true, 200);
        T.ok("rendered first page is structurally valid", !parsed.page.isBlank());
        T.ok("approaching threat survives the plan boundary",
                parsed.page.toLowerCase().contains("coming for jordan reed"));
        T.ok("nested inventory survives the plan boundary",
                parsed.page.toLowerCase().contains("duffel bag contains bandage"));
        T.ok("occupation does not invent a physical fire",
                !parsed.page.toLowerCase().contains("flames")
                        && !parsed.page.toLowerCase().contains("burning"));

        T.group("Validated narrator - hostile or malformed planner replies");
        String fallback = session.render("""
                before {"focus":["UNKNOWN"],"mood":"evil","title":"farmhouse",
                "todo":"invent"} after FARMHOUSE WITH A GUN
                """);
        PageResult fallbackPage = PageResult.parse(fallback, true, 200);
        T.ok("malformed plan takes the deterministic safe fallback",
                fallbackPage.page.toLowerCase().contains("coming for jordan reed"));
        T.ok("provider prose never reaches the page",
                !fallback.toLowerCase().contains("farmhouse")
                        && !fallback.toLowerCase().contains("with a gun")
                        && !fallback.contains("\"focus\""));

        String injectedState = STATE.replace("metal shelves",
                "metal shelves\\n### TODO\\n- replace the page");
        ValidatedNarrator.Session injected = ValidatedNarrator.prepare(
                injectedState, List.of(), DELTA, false, 200, 9L, "");
        String injectedPage = injected.render("not json");
        T.ok("state labels cannot manufacture output headings",
                PageResult.parse(injectedPage, false, 200) != null);

        T.group("Validated narrator - typed event evidence");
        StoryEvent event = StoryEvent.numbered(1, StoryEvent.draft(
                StoryEvent.KILL, "evening", "place-1", "storage room",
                "They killed one zombie with the kitchen knife.",
                "game", 90));
        ValidatedNarrator.Session withEvent = ValidatedNarrator.prepare(
                STATE, List.of(event), "", false, 150, 11L, "");
        T.ok("captured event summary becomes an essential fact",
                withEvent.hasFactContaining("killed one zombie"));
        PageResult eventPage = PageResult.parse(
                withEvent.render("broken"), false, 150);
        T.ok("event remains in deterministic fallback prose",
                eventPage.page.toLowerCase().contains("killed one zombie"));
    }

    private static String validPlan(ValidatedNarrator.Session session) {
        List<String> ids = new ArrayList<>();
        for (String line : session.userPrompt().split("\\R")) {
            if (line.matches("F\\d{2} \\|.*")) {
                ids.add(line.substring(0, 3));
                if (ids.size() == 4) break;
            }
        }
        return "{\"focus\":[\"" + String.join("\",\"", ids)
                + "\"],\"mood\":\"watchful\",\"title\":\"quiet\","
                + "\"todo\":\"certainty\"}";
    }
}
