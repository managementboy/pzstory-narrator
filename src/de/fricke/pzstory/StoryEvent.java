package de.fricke.pzstory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One factual thing that happened in the running game.
 *
 * Events are deliberately immutable. A provider request captures exact event
 * ids, and completion may happen on another thread much later; mutating the
 * captured objects would recreate the same cross-request race that the page
 * transaction removed in 1.25.
 */
public final class StoryEvent {

    public static final String PLACE_CHANGED    = "place_changed";
    public static final String ROOM_SEEN        = "room_seen";
    public static final String KILL             = "kill";
    public static final String BITTEN           = "bitten";
    public static final String WOUNDED          = "wounded";
    public static final String RECOVERED        = "recovered";
    public static final String SKILL_IMPROVED   = "skill_improved";
    public static final String VEHICLE_ENTERED  = "vehicle_entered";
    public static final String VEHICLE_EXITED   = "vehicle_exited";
    public static final String ENGINE_STARTED   = "engine_started";
    public static final String ENGINE_STOPPED   = "engine_stopped";
    public static final String POWER_LOST       = "power_lost";
    public static final String POWER_RESTORED   = "power_restored";
    public static final String WATER_LOST       = "water_lost";
    public static final String NOISE_STARTED    = "noise_started";
    public static final String NOISE_STOPPED    = "noise_stopped";
    public static final String PURSUIT_STARTED  = "pursuit_started";
    public static final String PURSUIT_ENDED    = "pursuit_ended";
    public static final String WINDOW_BROKEN    = "window_broken";
    public static final String WINDOW_BARRICADED = "window_barricaded";
    public static final String DOOR_SECURED     = "door_secured";
    public static final String WEATHER_CHANGED  = "weather_changed";
    public static final String SLEEP_STARTED    = "sleep_started";
    public static final String WOKE_UP          = "woke_up";

    public static final int MAX_SUMMARY_CHARS = 320;
    public static final int MAX_PLACE_CHARS = 192;
    public static final int MAX_FACTS = 16;

    public final long id;
    public final String type;
    public final String stamp;
    /** Local stable identity. Never rendered into a provider prompt. */
    public final String placeId;
    /** Human-readable, privacy-safe location label. */
    public final String place;
    public final String summary;
    public final String source;
    public final int importance;
    /** Zero while pending; otherwise the archive page that consumed it. */
    public final int narratedPage;
    public final Map<String, String> facts;

    /** A not-yet-numbered event emitted by a game hook or snapshot detector. */
    public static final class Draft {
        public final String type;
        public final String stamp;
        public final String placeId;
        public final String place;
        public final String summary;
        public final String source;
        public final int importance;
        public final Map<String, String> facts;

        private Draft(String type, String stamp, String placeId, String place,
                      String summary, String source, int importance,
                      Map<String, String> facts) {
            this.type = checkedType(type);
            this.stamp = oneLine(stamp, 128, true, "stamp");
            this.placeId = oneLine(placeId, MAX_PLACE_CHARS, true, "placeId");
            this.place = oneLine(place, MAX_PLACE_CHARS, true, "place");
            this.summary = oneLine(summary, MAX_SUMMARY_CHARS, false, "summary");
            this.source = checkedSource(source);
            this.importance = checkedImportance(importance);
            this.facts = checkedFacts(facts);
        }
    }

    private StoryEvent(long id, Draft draft, int narratedPage) {
        if (id <= 0) throw new IllegalArgumentException("event id must be positive");
        if (narratedPage < 0) {
            throw new IllegalArgumentException("narrated page cannot be negative");
        }
        this.id = id;
        this.type = draft.type;
        this.stamp = draft.stamp;
        this.placeId = draft.placeId;
        this.place = draft.place;
        this.summary = draft.summary;
        this.source = draft.source;
        this.importance = draft.importance;
        this.narratedPage = narratedPage;
        this.facts = draft.facts;
    }

    public static Draft draft(String type, String stamp, String placeId,
                              String place, String summary, String source,
                              int importance) {
        return new Draft(type, stamp, placeId, place, summary, source,
                importance, Map.of());
    }

    public static Draft draft(String type, String stamp, String placeId,
                              String place, String summary, String source,
                              int importance, Map<String, String> facts) {
        return new Draft(type, stamp, placeId, place, summary, source,
                importance, facts);
    }

    static StoryEvent numbered(long id, Draft draft) {
        return new StoryEvent(id, draft, 0);
    }

    StoryEvent narratedOn(int page) {
        if (page <= 0) throw new IllegalArgumentException("page must be positive");
        if (narratedPage != 0) return this;
        return new StoryEvent(id, new Draft(type, stamp, placeId, place,
                summary, source, importance, facts), page);
    }

    static StoryEvent fromJson(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalStateException("events contains a non-object entry");
        }
        long id = integer(map.get("id"), "event id");
        int narrated = optionalInt(map.get("narratedPage"), 0, "narratedPage");
        Draft draft = new Draft(
                JsonParse.str(map, "type", ""),
                JsonParse.str(map, "stamp", ""),
                JsonParse.str(map, "placeId", ""),
                JsonParse.str(map, "place", ""),
                JsonParse.str(map, "summary", ""),
                JsonParse.str(map, "source", "snapshot"),
                optionalInt(map.get("importance"), 1, "importance"),
                stringMap(map.get("facts")));
        return new StoryEvent(id, draft, narrated);
    }

    void write(Json j) {
        j.obj();
        j.put("id", id);
        j.put("type", type);
        j.put("stamp", stamp);
        j.put("placeId", placeId);
        j.put("place", place);
        j.put("summary", summary);
        j.put("source", source);
        j.put("importance", importance);
        if (narratedPage > 0) j.put("narratedPage", narratedPage);
        if (!facts.isEmpty()) {
            j.objKey("facts");
            for (Map.Entry<String, String> fact : facts.entrySet()) {
                j.put(fact.getKey(), fact.getValue());
            }
            j.endObj();
        }
        j.endObj();
    }

    /** Safe line for the provider: no ids, coordinates, or engine values. */
    String promptLine() {
        StringBuilder line = new StringBuilder(summary.length() + place.length() + 48);
        if (!stamp.isEmpty()) line.append(stamp).append(" — ");
        if (!place.isEmpty()) line.append(place).append(": ");
        line.append(summary);
        return line.toString();
    }

    private static String checkedType(String value) {
        String type = oneLine(value, 48, false, "type").toLowerCase(Locale.ROOT);
        if (!type.matches("[a-z][a-z0-9_]*")) {
            throw new IllegalArgumentException("invalid event type");
        }
        return type;
    }

    private static String checkedSource(String value) {
        String source = oneLine(value, 16, false, "source").toLowerCase(Locale.ROOT);
        if (!source.equals("game") && !source.equals("snapshot")
                && !source.equals("player") && !source.equals("extension")) {
            throw new IllegalArgumentException("invalid event source");
        }
        return source;
    }

    private static int checkedImportance(int value) {
        if (value < 1 || value > 100) {
            throw new IllegalArgumentException("event importance must be 1..100");
        }
        return value;
    }

    private static Map<String, String> checkedFacts(Map<String, String> input) {
        if (input == null || input.isEmpty()) return Map.of();
        if (input.size() > MAX_FACTS) {
            throw new IllegalArgumentException("event has too many facts");
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : input.entrySet()) {
            String key = checkedType(entry.getKey());
            String value = oneLine(entry.getValue(), 160, false, "fact");
            if (out.put(key, value) != null) {
                throw new IllegalArgumentException("duplicate event fact");
            }
        }
        return Collections.unmodifiableMap(out);
    }

    private static Map<String, String> stringMap(Object value) {
        if (value == null) return Map.of();
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalStateException("event facts is not an object");
        }
        if (map.size() > MAX_FACTS) {
            throw new IllegalStateException("event facts has too many entries");
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)
                    || !(entry.getValue() instanceof String text)) {
                throw new IllegalStateException("event facts must contain strings");
            }
            out.put(key, text);
        }
        return out;
    }

    private static long integer(Object value, String field) {
        if (!(value instanceof Number number)) {
            throw new IllegalStateException(field + " is not a number");
        }
        double raw = number.doubleValue();
        if (!Double.isFinite(raw) || raw != Math.rint(raw)
                || raw < 1 || raw > Long.MAX_VALUE) {
            throw new IllegalStateException(field + " is not a positive integer");
        }
        return number.longValue();
    }

    private static int optionalInt(Object value, int fallback, String field) {
        if (value == null) return fallback;
        if (!(value instanceof Number number)) {
            throw new IllegalStateException(field + " is not a number");
        }
        double raw = number.doubleValue();
        if (!Double.isFinite(raw) || raw != Math.rint(raw)
                || raw < Integer.MIN_VALUE || raw > Integer.MAX_VALUE) {
            throw new IllegalStateException(field + " is not an integer");
        }
        return number.intValue();
    }

    private static String oneLine(String value, int max, boolean emptyOkay,
                                  String field) {
        if (value == null) value = "";
        StringBuilder clean = new StringBuilder(value.length());
        boolean spaced = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c)) {
                if (!spaced) clean.append(' ');
                spaced = true;
            } else {
                clean.append(c);
                spaced = Character.isWhitespace(c);
            }
        }
        String out = clean.toString().strip();
        if (!emptyOkay && out.isEmpty()) {
            throw new IllegalArgumentException(field + " cannot be empty");
        }
        if (out.length() > max) {
            throw new IllegalArgumentException(field + " exceeds " + max + " characters");
        }
        return out;
    }
}
