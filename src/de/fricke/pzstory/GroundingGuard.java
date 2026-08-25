package de.fricke.pzstory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Deterministic last gate for Classic prose.
 *
 * The small local model can write attractive prose while ignoring a rule near
 * the end of a long prompt. These checks cover claims whose falsity can be
 * established from the snapshot without attempting general natural-language
 * understanding. A false positive costs one corrective turn; a false negative
 * would poison the accepted stateful conversation.
 */
public final class GroundingGuard {
    private GroundingGuard() { }

    private static final Pattern MOVE = Pattern.compile(
            "\\b(walks?|walked|steps?|stepped|crosses?|crossed|approaches?|"
            + "approached|goes|went|moves? (?:to|toward|towards|across))\\b");
    private static final Pattern INTERACT = Pattern.compile(
            "\\b(opens?|opened|closes|closed|dials?|dialed|calls?|called|"
            + "presses?|pressed|picks? up|picked up|turns? on|turned on|"
            + "turns? off|turned off|lifts? (?:the )?receiver|"
            + "fumbles? with (?:the )?receiver)\\b");

    private static final String[] PLACE_NAMES = {
        "muldraugh", "west point", "rosewood", "riverside", "louisville",
        "march ridge", "ekron", "brandenburg", "knoxville"
    };
    private static final String[] ACCESS_GATED_OBJECTS = {
        "telephone", "phone", "receiver", "radio", "television", "tv",
        "newspaper", "book", "letter", "notepad", "map", "vhs"
    };
    private static final String[] SPECIFIC_SOUNDS = {
        "static", "crackling", "whisper", "whispers", "voice", "voices",
        "footsteps", "banging", "knocking", "scratching", "gunshot", "scream"
    };

    public static void validate(PageResult result, String stateJson, String change,
                                boolean firstPage, boolean stillStanding) {
        if (result == null) throw new PageResult.Invalid("no parsed page was available");
        String state = stateJson == null ? "" : stateJson;
        String evidence = (state + "\n" + (change == null ? "" : change))
                .toLowerCase(Locale.ROOT);
        String page = result.page.toLowerCase(Locale.ROOT);
        List<String> faults = new ArrayList<>();

        if (firstPage) {
            int words = words(result.premise);
            int sentences = sentences(result.premise);
            if (words < 60 || words > 100 || sentences < 3 || sentences > 5) {
                faults.add("PREMISE must be 60-100 words in 3-5 sentences; got "
                        + words + " words and " + sentences + " sentences");
            }
        }

        if (mandatoryDead(state) && !containsAny(page,
                "dead", "undead", "zombie", "corpse", "body", "figure")) {
            faults.add("the visible dead person is mandatory but absent from PAGE");
        }

        for (String place : PLACE_NAMES) {
            if (page.contains(place) || lower(result.premise).contains(place)) {
                if (!evidence.contains(place)) {
                    faults.add("unsupported named place: " + place);
                }
            }
        }

        for (String object : ACCESS_GATED_OBJECTS) {
            if (word(page, object) && !word(evidence, object)) {
                faults.add("unsupported object or medium: " + object);
            }
        }

        if (genericNoise(state)) {
            for (String sound : SPECIFIC_SOUNDS) {
                if (word(page, sound) && !word(evidence, sound)) {
                    faults.add("the generic recorded noise was invented as: " + sound);
                }
            }
        }

        if ((firstPage || stillStanding) && MOVE.matcher(page).find()) {
            faults.add("movement was narrated although no movement was recorded");
        }
        if (INTERACT.matcher(page).find() && !INTERACT.matcher(lower(change)).find()) {
            faults.add("an object interaction was narrated without a recorded action");
        }

        int drawn = nestedInt(state, "here", "windows", "curtainsDrawn");
        if (drawn == 0 && (page.contains("curtains are drawn")
                || page.contains("curtains that are drawn")
                || page.contains("drawn curtains") || page.contains("curtains drawn"))) {
            faults.add("curtains were described as drawn but STATE records none drawn");
        }

        for (String item : stowedItems(state)) {
            String key = itemKey(item);
            if (key.isEmpty()) continue;
            for (String sentence : result.page.split("(?<=[.!?])\\s+")) {
                String s = lower(sentence);
                if (word(s, key) && containsAny(s, " on the ", " rests ", " lies ",
                        " sitting ", " sits ", " floor", " table", " counter")) {
                    faults.add("stowed item was relocated into the scene: " + key);
                }
            }
        }

        if (!faults.isEmpty()) {
            throw new PageResult.Invalid("grounding failed: " + String.join("; ", faults));
        }
    }

    private static boolean mandatoryDead(String state) {
        try {
            Map<String, Object> root = JsonParse.parseObject(state);
            Map<String, Object> dead = JsonParse.map(root, "theDead");
            return dead != null && !JsonParse.str(dead, "withinSight", "").isBlank();
        } catch (Throwable ignored) { return false; }
    }

    private static boolean genericNoise(String state) {
        try {
            Map<String, Object> root = JsonParse.parseObject(state);
            Map<String, Object> noise = JsonParse.map(root, "noise");
            String what = noise == null ? "" : lower(JsonParse.str(noise, "what", ""));
            return what.equals("a noise") || what.startsWith("a noise ");
        } catch (Throwable ignored) { return false; }
    }

    private static int nestedInt(String json, String... keys) {
        try {
            Object value = JsonParse.parseObject(json);
            for (String key : keys) {
                if (!(value instanceof Map<?, ?> raw)) return -1;
                @SuppressWarnings("unchecked") Map<String, Object> map =
                        (Map<String, Object>) raw;
                value = map.get(key);
            }
            return value instanceof Number n ? n.intValue() : -1;
        } catch (Throwable ignored) { return -1; }
    }

    private static List<String> stowedItems(String json) {
        List<String> out = new ArrayList<>();
        try {
            Object raw = JsonParse.parseObject(json).get("stowedOnHim");
            if (raw instanceof List<?> list) {
                for (Object value : list) if (value instanceof String s) out.add(s);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    private static String itemKey(String item) {
        String s = lower(item);
        if (s.startsWith("id card")) return "id card";
        if (s.contains("key ring")) return "key ring";
        int colon = s.indexOf(':');
        return (colon >= 0 ? s.substring(0, colon) : s).strip();
    }

    private static int words(String text) {
        String s = text == null ? "" : text.strip();
        return s.isEmpty() ? 0 : s.split("\\s+").length;
    }

    private static int sentences(String text) {
        String s = text == null ? "" : text.strip();
        if (s.isEmpty()) return 0;
        int count = 0;
        for (String part : s.split("(?<=[.!?])(?:[\\\"'’”)]*)\\s+")) {
            if (!part.isBlank()) count++;
        }
        return count;
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) if (text.contains(needle)) return true;
        return false;
    }

    private static boolean word(String text, String value) {
        if (value.contains(" ")) return text.contains(value);
        return Pattern.compile("(?<![a-z0-9])" + Pattern.quote(value)
                + "(?![a-z0-9])").matcher(text).find();
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
