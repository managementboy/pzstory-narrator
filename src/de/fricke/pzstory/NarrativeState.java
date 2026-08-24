package de.fricke.pzstory;

import java.util.List;
import java.util.Map;

/**
 * Data-minimised projection of the exact local game snapshot.
 *
 * The raw snapshot remains on the player's machine for delta calculation and
 * save continuity. Only this semantic projection is placed in a provider
 * request: account names, exact coordinates, engine ids, diagnostics, and raw
 * stat telemetry do not help write prose and therefore do not leave the game.
 */
public final class NarrativeState {

    private NarrativeState() {}

    public static String fromRaw(String rawJson) {
        Map<String, Object> root = JsonParse.parseObject(rawJson);
        Object snapshotError = root.get("error");
        if (snapshotError instanceof String message && !message.isBlank()) {
            throw new IllegalStateException("the game-state snapshot reported an error");
        }
        if (map(root, "character") == null || map(root, "position") == null) {
            throw new IllegalStateException(
                    "the game-state snapshot is missing character or position data");
        }
        root.remove("modVersion");
        root.remove("wallClock");
        root.remove("readErrors");
        root.remove("stats");
        root.remove("threat");
        root.remove("nutrition");

        Map<String, Object> time = map(root, "time");
        if (time != null) time.remove("worldAgeHours");

        Map<String, Object> character = map(root, "character");
        if (character != null) {
            character.remove("username");
            character.remove("female"); // pronouns are the authoritative field
            Number hours = number(character.remove("hoursSurvived"));
            if (hours != null) {
                double value = hours.doubleValue();
                character.put("timeSurvived", value < 24 ? "less than a day"
                        : value < 24 * 7 ? "several days"
                        : value < 24 * 30 ? "several weeks"
                        : value < 24 * 180 ? "several months" : "a long time");
            }
            Number kills = number(character.remove("zombieKills"));
            if (kills != null) {
                int value = kills.intValue();
                character.put("experienceWithTheDead", value <= 0 ? "none"
                        : value < 5 ? "a little"
                        : value < 25 ? "seasoned"
                        : value < 100 ? "hardened" : "extensive");
            }
            character.remove("inventoryWeight");
        }

        Map<String, Object> position = map(root, "position");
        if (position != null) {
            for (String key : new String[] {
                    "x", "y", "z", "chunkX", "chunkY", "cellX", "cellY", "roomId"
            }) position.remove(key);
            if (position.remove("building") != null) {
                position.put("insideBuilding", Boolean.TRUE);
            }
        }

        Map<String, Object> weather = map(root, "weather");
        if (weather != null) weather.remove("temperatureC");

        Object bags = root.get("bags");
        if (bags instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> raw) raw.remove("id");
            }
        }

        minimiseHealth(map(root, "health"));
        minimiseSkills(map(root, "skills"));
        return Json.of(root);
    }

    private static void minimiseHealth(Map<String, Object> health) {
        if (health == null) return;
        for (String key : new String[] {
                "overall", "apparentInfectionLevel", "partsBleeding",
                "partsBitten", "partsScratched"
        }) health.remove(key);

        Object wounds = health.get("wounds");
        if (!(wounds instanceof List<?> list)) return;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> wound = (Map<String, Object>) raw;
            if (wound.remove("health") != null) wound.put("injured", Boolean.TRUE);
            if (wound.remove("pain") != null) wound.put("painful", Boolean.TRUE);
            if (wound.remove("stiffness") != null) wound.put("stiff", Boolean.TRUE);
        }
    }

    private static void minimiseSkills(Map<String, Object> skills) {
        if (skills == null) return;
        for (Object value : skills.values()) {
            if (!(value instanceof Map<?, ?> raw)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> skill = (Map<String, Object>) raw;
            Object levelValue = skill.remove("level");
            skill.remove("xp");
            if (skill.remove("fromTheirTrade") != null) {
                skill.put("fromTheirTrade", Boolean.TRUE);
            }
            if (levelValue instanceof Number n) {
                int level = n.intValue();
                skill.put("ability", level <= 0 ? "just starting"
                        : level <= 2 ? "beginner"
                        : level <= 4 ? "practised"
                        : level <= 6 ? "capable"
                        : level <= 8 ? "expert" : "masterful");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> root, String key) {
        Object value = root.get(key);
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
    }

    private static Number number(Object value) {
        return value instanceof Number n ? n : null;
    }
}
