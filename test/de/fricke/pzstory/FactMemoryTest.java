package de.fricke.pzstory;

import java.util.Map;

/** Typed story facts remain bounded, sourced and conservatively superseded. */
public final class FactMemoryTest {
    public static void run() {
        T.group("Story facts - type, provenance and confidence");
        FactMemory memory = new FactMemory();
        T.ok("typed narrator fact accepted",
                memory.add("[belief] she trusts the garage", "knowledge",
                        "narrator", 55, 2));
        T.ok("untyped fact uses fallback kind",
                memory.add("the radio mentioned Louisville", "knowledge",
                        "media", 75, 3));
        T.ok("exact duplicate is refused",
                !memory.add("THE RADIO MENTIONED LOUISVILLE.", "world",
                        "narrator", 55, 4));
        String prompt = memory.prompt();
        T.ok("prompt groups typed facts", prompt.contains("BELIEF:")
                && prompt.contains("KNOWLEDGE:"));
        T.ok("prompt exposes provenance without local ids",
                prompt.contains("[media, confidence 75]")
                        && !prompt.contains("nextId"));

        T.group("Story facts - conservative contradictions");
        FactMemory contradictions = new FactMemory();
        contradictions.add("[world] the garage is locked", "world",
                "narrator", 55, 1);
        contradictions.add("[world] the garage is not locked", "world",
                "player", 95, 2);
        T.eq("stronger opposite supersedes old active fact", 1,
                contradictions.activeText().size());
        T.eq("player correction remains active", "the garage is not locked",
                contradictions.activeText().get(0));
        T.ok("weaker opposite is refused",
                !contradictions.add("[world] the garage is locked", "world",
                        "legacy", 40, 0));
        T.eq("weaker opposite cannot coexist with player fact", 1,
                contradictions.activeText().size());

        T.group("Story facts - durable bounded representation");
        String json = contradictions.json();
        Map<String, Object> root = JsonParse.parseObject(json);
        FactMemory loaded = new FactMemory();
        loaded.load(root.get("factMemory"));
        T.eq("fact memory round trips", contradictions.activeText(), loaded.activeText());
        T.ok("fact text length is bounded",
                !loaded.add("x".repeat(StoryFact.MAX_TEXT + 1), "world",
                        "player", 100, 1));
        T.ok("unknown provenance is refused",
                !loaded.add("a fact", "world", "remote", 50, 1));

        T.group("Story facts - keyed game-state history");
        FactMemory state = new FactMemory();
        T.ok("first held item becomes active",
                state.upsert("game:primary-hand", "currently holds a hammer",
                        "possession", "game", 100, 1));
        T.ok("unchanged held item is ignored",
                !state.upsert("game:primary-hand", "currently holds a hammer",
                        "possession", "game", 100, 2));
        T.ok("changed held item supersedes old slot",
                state.upsert("game:primary-hand", "currently holds an axe",
                        "possession", "game", 100, 3));
        T.eq("only latest keyed state remains active",
                java.util.List.of("currently holds an axe"), state.activeText());
        T.ok("superseded history remains in diagnostics",
                state.json().contains("\"supersededBy\":2"));
    }
}
