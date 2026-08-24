package de.fricke.pzstory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Deterministically turns two local snapshots into factual story events. */
public final class EventDetector {

    private static final int MAX_EVENTS_PER_OBSERVATION = 32;

    private EventDetector() {}

    public static List<StoryEvent.Draft> between(
            String beforeJson, String afterJson, String stamp) {
        if (beforeJson == null || beforeJson.isBlank()) return List.of();
        Map<String, Object> before = JsonParse.parseObject(beforeJson);
        Map<String, Object> after = JsonParse.parseObject(afterJson);
        List<StoryEvent.Draft> out = new ArrayList<>();

        PlaceRef from = PlaceRef.fromState(before), to = PlaceRef.fromState(after);
        movement(before, after, from, to, stamp, out);
        combat(before, after, to, stamp, out);
        inventory(before, after, to, stamp, out);
        body(before, after, to, stamp, out);
        skills(before, after, to, stamp, out);
        vehicle(before, after, to, stamp, out);
        utilities(before, after, to, stamp, out);
        noise(before, after, to, stamp, out);
        threat(before, after, to, stamp, out);
        shelter(before, after, to, stamp, out);
        weather(before, after, to, stamp, out);
        sleep(before, after, to, stamp, out);

        if (out.size() <= MAX_EVENTS_PER_OBSERVATION) return List.copyOf(out);
        return List.copyOf(out.subList(0, MAX_EVENTS_PER_OBSERVATION));
    }

    private static void movement(Map<String, Object> before,
                                 Map<String, Object> after,
                                 PlaceRef from, PlaceRef to, String stamp,
                                 List<StoryEvent.Draft> out) {
        if (from == null || to == null || from.id.equals(to.id)) return;
        Map<String, Object> pa = JsonParse.map(before, "position");
        Map<String, Object> pb = JsonParse.map(after, "position");
        String oldZone = pa == null ? "" : JsonParse.str(pa, "placeName", "");
        String newZone = pb == null ? "" : JsonParse.str(pb, "placeName", "");
        boolean settlementChanged = !newZone.isBlank()
                && !newZone.equalsIgnoreCase(oldZone);
        boolean floorChanged = pa != null && pb != null
                && JsonParse.num(pa, "z", 0) != JsonParse.num(pb, "z", 0);
        String summary;
        int importance;
        if (settlementChanged) {
            summary = "They reached " + to.label + ".";
            importance = 55;
        } else {
            summary = "They moved into " + article(to.label) + ".";
            importance = floorChanged ? 35 : 22;
        }
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("from", from.label);
        facts.put("to", to.label);
        if (floorChanged) facts.put("changed_floor", "yes");
        add(out, StoryEvent.PLACE_CHANGED, stamp, to, summary, importance, facts);
    }

    private static void combat(Map<String, Object> before,
                               Map<String, Object> after,
                               PlaceRef place, String stamp,
                               List<StoryEvent.Draft> out) {
        Map<String, Object> ca = JsonParse.map(before, "character");
        Map<String, Object> cb = JsonParse.map(after, "character");
        if (ca == null || cb == null) return;
        int oldKills = JsonParse.num(ca, "zombieKills", 0);
        int newKills = JsonParse.num(cb, "zombieKills", 0);
        int gained = newKills - oldKills;
        if (gained <= 0) return;
        String scale = gained == 1 ? "one" : gained <= 4 ? "a few" : "several";
        String summary = "They killed " + scale + " of the dead.";
        // A kill must outrank ambient noise. Live alpha testing showed a
        // repeated generic sound (82) displacing a kill (42) from the page.
        int importance = oldKills == 0 ? 88 : gained >= 5 ? 84 : gained > 1 ? 80 : 76;
        add(out, StoryEvent.KILL, stamp, place, summary, importance,
                Map.of("scale", scale, "first_kill", oldKills == 0 ? "yes" : "no"));
    }

    private static void body(Map<String, Object> before,
                             Map<String, Object> after,
                             PlaceRef place, String stamp,
                             List<StoryEvent.Draft> out) {
        Map<String, Object> a = JsonParse.map(before, "health");
        Map<String, Object> b = JsonParse.map(after, "health");
        if (a == null || b == null) return;

        int bitesA = JsonParse.num(a, "partsBitten", 0);
        int bitesB = JsonParse.num(b, "partsBitten", 0);
        if (bitesB > bitesA) {
            add(out, StoryEvent.BITTEN, stamp, place,
                    "They were bitten.", 100, Map.of("new_bite", "yes"));
        }

        int scratchesA = JsonParse.num(a, "partsScratched", 0);
        int scratchesB = JsonParse.num(b, "partsScratched", 0);
        int bleedingA = JsonParse.num(a, "partsBleeding", 0);
        int bleedingB = JsonParse.num(b, "partsBleeding", 0);
        Double healthA = number(a.get("overall"));
        Double healthB = number(b.get("overall"));
        boolean newScratch = scratchesB > scratchesA;
        boolean newBleeding = bleedingB > bleedingA;
        boolean healthDrop = healthA != null && healthB != null && healthB < healthA - 2;
        if (bitesB <= bitesA && (newScratch || newBleeding || healthDrop)) {
            String summary = newScratch ? "They were scratched and newly hurt."
                    : newBleeding ? "They began bleeding from a new wound."
                    : "They were hurt worse than before.";
            add(out, StoryEvent.WOUNDED, stamp, place, summary,
                    newBleeding ? 72 : 60, Map.of());
        }

        boolean bleedingImproved = bleedingB < bleedingA;
        boolean healthImproved = healthA != null && healthB != null && healthB > healthA + 5;
        if (bleedingImproved || healthImproved) {
            add(out, StoryEvent.RECOVERED, stamp, place,
                    bleedingImproved
                            ? "A wound stopped bleeding or was brought under control."
                            : "Their injuries noticeably improved.",
                    28, Map.of());
        }
    }

    private static void inventory(Map<String, Object> before,
                                  Map<String, Object> after,
                                  PlaceRef place, String stamp,
                                  List<StoryEvent.Draft> out) {
        Map<String, Object> a = JsonParse.map(before, "carriedItems");
        Map<String, Object> b = JsonParse.map(after, "carriedItems");
        if (a == null || b == null) return;
        int emitted = 0;
        for (Map.Entry<String, Object> entry : b.entrySet()) {
            if (emitted >= 4) break;
            int oldCount = JsonParse.num(a, entry.getKey(), 0);
            int newCount = entry.getValue() instanceof Number n ? n.intValue() : 0;
            if (newCount <= oldCount) continue;
            int gained = newCount - oldCount;
            String item = safeLabel(entry.getKey(), "an item");
            String summary = gained == 1
                    ? "They acquired " + item + "."
                    : "They acquired " + gained + " " + item + ".";
            add(out, StoryEvent.ITEM_ACQUIRED, stamp, place, summary, 46,
                    Map.of("item", item, "count", Integer.toString(gained)));
            emitted++;
        }
    }

    private static void skills(Map<String, Object> before,
                               Map<String, Object> after,
                               PlaceRef place, String stamp,
                               List<StoryEvent.Draft> out) {
        Map<String, Object> a = JsonParse.map(before, "skills");
        Map<String, Object> b = JsonParse.map(after, "skills");
        if (a == null || b == null) return;
        int emitted = 0;
        for (Map.Entry<String, Object> entry : b.entrySet()) {
            if (emitted >= 5 || !(entry.getValue() instanceof Map<?, ?> now)) continue;
            Object oldValue = a.get(entry.getKey());
            if (!(oldValue instanceof Map<?, ?> old)) continue;
            int oldLevel = JsonParse.num(old, "level", 0);
            int newLevel = JsonParse.num(now, "level", 0);
            if (newLevel <= oldLevel) continue;
            String skill = safeLabel(entry.getKey(), "a skill");
            add(out, StoryEvent.SKILL_IMPROVED, stamp, place,
                    skill + " noticeably improved.", 38,
                    Map.of("skill", skill));
            emitted++;
        }
    }

    private static void vehicle(Map<String, Object> before,
                                Map<String, Object> after,
                                PlaceRef place, String stamp,
                                List<StoryEvent.Draft> out) {
        Map<String, Object> a = JsonParse.map(before, "inAVehicle");
        Map<String, Object> b = JsonParse.map(after, "inAVehicle");
        if (a == null && b != null) {
            String model = safeLabel(JsonParse.str(b, "model", "a vehicle"), "a vehicle");
            add(out, StoryEvent.VEHICLE_ENTERED, stamp, place,
                    "They got into " + article(model) + ".", 58,
                    Map.of("vehicle", model));
            return;
        }
        if (a != null && b == null) {
            String model = safeLabel(JsonParse.str(a, "model", "the vehicle"), "the vehicle");
            add(out, StoryEvent.VEHICLE_EXITED, stamp, place,
                    "They left " + article(model) + " and returned to their feet.",
                    32, Map.of("vehicle", model));
            return;
        }
        if (a == null) return;
        boolean oldEngine = Boolean.TRUE.equals(a.get("engineRunning"));
        boolean newEngine = Boolean.TRUE.equals(b.get("engineRunning"));
        String model = safeLabel(JsonParse.str(b, "model", "the vehicle"), "the vehicle");
        if (!oldEngine && newEngine) {
            add(out, StoryEvent.ENGINE_STARTED, stamp, place,
                    "The engine of " + article(model) + " started.", 52,
                    Map.of("vehicle", model));
        } else if (oldEngine && !newEngine) {
            add(out, StoryEvent.ENGINE_STOPPED, stamp, place,
                    "The engine of " + article(model) + " stopped.", 30,
                    Map.of("vehicle", model));
        }
    }

    private static void utilities(Map<String, Object> before,
                                  Map<String, Object> after,
                                  PlaceRef place, String stamp,
                                  List<StoryEvent.Draft> out) {
        Map<String, Object> a = JsonParse.map(before, "utilities");
        Map<String, Object> b = JsonParse.map(after, "utilities");
        if (a == null || b == null) return;
        Boolean oldPower = bool(a.get("mainsPower"));
        Boolean newPower = bool(b.get("mainsPower"));
        if (Boolean.TRUE.equals(oldPower) && Boolean.FALSE.equals(newPower)) {
            add(out, StoryEvent.POWER_LOST, stamp, place,
                    "The mains electricity failed.", 95, Map.of());
        } else if (Boolean.FALSE.equals(oldPower) && Boolean.TRUE.equals(newPower)) {
            add(out, StoryEvent.POWER_RESTORED, stamp, place,
                    "Electrical power became available here again.", 50, Map.of());
        }
        Boolean oldWater = bool(a.get("mainsWater"));
        Boolean newWater = bool(b.get("mainsWater"));
        if (Boolean.TRUE.equals(oldWater) && Boolean.FALSE.equals(newWater)) {
            add(out, StoryEvent.WATER_LOST, stamp, place,
                    "The mains water supply stopped.", 95, Map.of());
        }
    }

    private static void noise(Map<String, Object> before,
                              Map<String, Object> after,
                              PlaceRef place, String stamp,
                              List<StoryEvent.Draft> out) {
        Map<String, Object> a = JsonParse.map(before, "noise");
        Map<String, Object> b = JsonParse.map(after, "noise");
        if (a == null && b != null) {
            String what = safeLabel(JsonParse.str(b, "what", "a loud noise"), "a loud noise");
            add(out, StoryEvent.NOISE_STARTED, stamp, place,
                    what + " began nearby.", 34, Map.of("sound", what));
        } else if (a != null && b == null) {
            String what = safeLabel(JsonParse.str(a, "what", "the noise"), "the noise");
            add(out, StoryEvent.NOISE_STOPPED, stamp, place,
                    what + " stopped.", 12,
                    Map.of("sound", what));
        } else if (a != null) {
            String oldSound = JsonParse.str(a, "what", "");
            String newSound = JsonParse.str(b, "what", "");
            if (!newSound.isBlank() && !newSound.equals(oldSound)) {
                add(out, StoryEvent.NOISE_STARTED, stamp, place,
                        safeLabel(newSound, "A different noise") + " replaced the earlier noise.",
                        38, Map.of("sound", safeLabel(newSound, "a different noise")));
            }
        }
    }

    private static void threat(Map<String, Object> before,
                               Map<String, Object> after,
                               PlaceRef place, String stamp,
                               List<StoryEvent.Draft> out) {
        Map<String, Object> a = JsonParse.map(before, "theDead");
        Map<String, Object> b = JsonParse.map(after, "theDead");
        boolean oldPursuit = pursuing(a), newPursuit = pursuing(b);
        if (!oldPursuit && newPursuit) {
            add(out, StoryEvent.PURSUIT_STARTED, stamp, place,
                    "The dead began pursuing them.", 86, Map.of());
        } else if (oldPursuit && !newPursuit) {
            add(out, StoryEvent.PURSUIT_ENDED, stamp, place,
                    "They escaped the immediate pursuit.", 48, Map.of());
        }
    }

    private static void shelter(Map<String, Object> before,
                                Map<String, Object> after,
                                PlaceRef place, String stamp,
                                List<StoryEvent.Draft> out) {
        Map<String, Object> pa = JsonParse.map(before, "position");
        Map<String, Object> pb = JsonParse.map(after, "position");
        // Visibility is viewpoint-dependent. Moving across the same room can
        // reveal an already-broken window; that is not a new break. Compare a
        // shelter fingerprint only when both observations share one tile.
        if (!sameRoom(pa, pb) || !sameViewpoint(pa, pb)) return;
        Map<String, Object> a = JsonParse.map(before, "here");
        Map<String, Object> b = JsonParse.map(after, "here");
        if (a == null || b == null) return;
        Map<String, Object> wa = JsonParse.map(a, "windows");
        Map<String, Object> wb = JsonParse.map(b, "windows");
        if (wa != null && wb != null
                && JsonParse.num(wa, "total", -1) == JsonParse.num(wb, "total", -2)) {
            if (JsonParse.num(wb, "smashed", 0) > JsonParse.num(wa, "smashed", 0)) {
                add(out, StoryEvent.WINDOW_BROKEN, stamp, place,
                        "A window here was broken.", 68, Map.of());
            }
            if (JsonParse.num(wb, "barricaded", 0)
                    > JsonParse.num(wa, "barricaded", 0)) {
                add(out, StoryEvent.WINDOW_BARRICADED, stamp, place,
                        "They barricaded a window.", 42, Map.of());
            }
        }
        Map<String, Object> da = JsonParse.map(a, "doors");
        Map<String, Object> db = JsonParse.map(b, "doors");
        if (da != null && db != null
                && JsonParse.num(da, "total", -1) == JsonParse.num(db, "total", -2)
                && JsonParse.num(db, "locked", 0) > JsonParse.num(da, "locked", 0)) {
            add(out, StoryEvent.DOOR_SECURED, stamp, place,
                    "They secured a door.", 36, Map.of());
        }
    }

    private static void weather(Map<String, Object> before,
                                Map<String, Object> after,
                                PlaceRef place, String stamp,
                                List<StoryEvent.Draft> out) {
        Map<String, Object> a = JsonParse.map(before, "weather");
        Map<String, Object> b = JsonParse.map(after, "weather");
        if (a == null || b == null) return;
        boolean oldRain = Boolean.TRUE.equals(a.get("raining"));
        boolean newRain = Boolean.TRUE.equals(b.get("raining"));
        boolean oldSnow = Boolean.TRUE.equals(a.get("snowing"));
        boolean newSnow = Boolean.TRUE.equals(b.get("snowing"));
        String oldFog = JsonParse.str(a, "fog", "");
        String newFog = JsonParse.str(b, "fog", "");
        String summary = "";
        if (!oldRain && newRain) summary = "Rain began.";
        else if (oldRain && !newRain) summary = "The rain stopped.";
        else if (!oldSnow && newSnow) summary = "Snow began.";
        else if (oldSnow && !newSnow) summary = "The snow stopped.";
        else if (!newFog.isBlank() && !newFog.equals(oldFog)) {
            summary = "The fog grew " + safeLabel(newFog, "thicker") + ".";
        }
        if (!summary.isEmpty()) {
            add(out, StoryEvent.WEATHER_CHANGED, stamp, place, summary, 24, Map.of());
        }
    }

    private static void sleep(Map<String, Object> before,
                              Map<String, Object> after,
                              PlaceRef place, String stamp,
                              List<StoryEvent.Draft> out) {
        Map<String, Object> a = JsonParse.map(before, "character");
        Map<String, Object> b = JsonParse.map(after, "character");
        if (a == null || b == null) return;
        boolean oldSleep = Boolean.TRUE.equals(a.get("asleep"));
        boolean newSleep = Boolean.TRUE.equals(b.get("asleep"));
        if (!oldSleep && newSleep) {
            add(out, StoryEvent.SLEEP_STARTED, stamp, place,
                    "They fell asleep here.", 18, Map.of());
        } else if (oldSleep && !newSleep) {
            add(out, StoryEvent.WOKE_UP, stamp, place,
                    "They woke here.", 26, Map.of());
        }
    }

    private static void add(List<StoryEvent.Draft> out, String type, String stamp,
                            PlaceRef place, String summary, int importance,
                            Map<String, String> facts) {
        if (out.size() >= MAX_EVENTS_PER_OBSERVATION) return;
        out.add(StoryEvent.draft(type, stamp,
                place == null ? "" : place.id,
                place == null ? "" : place.label,
                summary, "snapshot", importance, facts));
    }

    private static boolean sameRoom(Map<String, Object> a, Map<String, Object> b) {
        if (a == null || b == null) return false;
        String ida = JsonParse.str(a, "roomId", "");
        String idb = JsonParse.str(b, "roomId", "");
        if (!ida.isBlank() && !idb.isBlank()) return ida.equals(idb);
        return JsonParse.num(a, "z", 0) == JsonParse.num(b, "z", 0)
                && JsonParse.str(a, "room", "").equals(JsonParse.str(b, "room", ""));
    }

    private static boolean sameViewpoint(Map<String, Object> a, Map<String, Object> b) {
        if (a == null || b == null) return false;
        return JsonParse.num(a, "x", Integer.MIN_VALUE)
                        == JsonParse.num(b, "x", Integer.MAX_VALUE)
                && JsonParse.num(a, "y", Integer.MIN_VALUE)
                        == JsonParse.num(b, "y", Integer.MAX_VALUE)
                && JsonParse.num(a, "z", Integer.MIN_VALUE)
                        == JsonParse.num(b, "z", Integer.MAX_VALUE);
    }

    private static boolean pursuing(Map<String, Object> dead) {
        return dead != null && (dead.containsKey("comingForThem")
                || dead.containsKey("comingForHer"));
    }

    private static Boolean bool(Object value) {
        return value instanceof Boolean b ? b : null;
    }

    private static Double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static String article(String text) {
        if (text == null || text.isBlank()) return "that place";
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.startsWith("the ") || lower.startsWith("a ")
                || lower.startsWith("an ")) return text;
        char c = lower.charAt(0);
        return ("aeiou".indexOf(c) >= 0 ? "an " : "a ") + text;
    }

    private static String safeLabel(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        StringBuilder clean = new StringBuilder(Math.min(value.length(), 160));
        for (int i = 0; i < value.length() && clean.length() < 160; i++) {
            char c = value.charAt(i);
            clean.append(Character.isISOControl(c) ? ' ' : c);
        }
        String result = clean.toString().strip();
        return result.isEmpty() ? fallback : result;
    }

}
