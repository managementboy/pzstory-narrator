package de.fricke.pzstory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Structured, local memory of places the survivor has actually occupied.
 *
 * Stable engine ids remain on disk and are used only to distinguish two rooms
 * with the same name. The provider projection contains human labels and
 * qualitative familiarity, never ids or coordinates.
 */
final class WorldMemory {

    static final int MAX_PLACES = 500;
    private static final int MAX_PROMPT_PLACES = 12;

    private static final class Place {
        final String id;
        String label;
        final String firstSeen;
        String lastSeen;
        int visits;

        Place(String id, String label, String firstSeen, String lastSeen, int visits) {
            this.id = id;
            this.label = label;
            this.firstSeen = firstSeen;
            this.lastSeen = lastSeen;
            this.visits = visits;
        }

        Place copy() {
            return new Place(id, label, firstSeen, lastSeen, visits);
        }
    }

    static final class Snapshot {
        final LinkedHashMap<String, Place> places = new LinkedHashMap<>();
        final String currentPlaceId;
        final long droppedPlaces;

        Snapshot(Map<String, Place> source, String currentPlaceId, long droppedPlaces) {
            for (Map.Entry<String, Place> entry : source.entrySet()) {
                places.put(entry.getKey(), entry.getValue().copy());
            }
            this.currentPlaceId = currentPlaceId;
            this.droppedPlaces = droppedPlaces;
        }
    }

    private final LinkedHashMap<String, Place> places = new LinkedHashMap<>();
    private String currentPlaceId = "";
    private long droppedPlaces = 0;

    void clear() {
        places.clear();
        currentPlaceId = "";
        droppedPlaces = 0;
    }

    Snapshot snapshot() {
        return new Snapshot(places, currentPlaceId, droppedPlaces);
    }

    void restore(Snapshot snapshot) {
        clear();
        if (snapshot == null) return;
        for (Map.Entry<String, Place> entry : snapshot.places.entrySet()) {
            places.put(entry.getKey(), entry.getValue().copy());
        }
        currentPlaceId = snapshot.currentPlaceId;
        droppedPlaces = snapshot.droppedPlaces;
    }

    /** Returns true only when durable memory changed. */
    boolean observe(String stateJson, String stamp) {
        Map<String, Object> state = JsonParse.parseObject(stateJson);
        PlaceRef ref = PlaceRef.fromState(state);
        if (ref == null) return false;
        String when = bounded(stamp, 128, true, "place stamp");

        if (ref.id.equals(currentPlaceId)) {
            Place current = places.get(ref.id);
            if (current != null && !current.label.equals(ref.label)) {
                current.label = ref.label;
                return true;
            }
            return false;
        }

        currentPlaceId = ref.id;
        Place known = places.remove(ref.id);
        if (known == null) {
            known = new Place(ref.id, ref.label, when, when, 1);
        } else {
            known.label = ref.label;
            known.lastSeen = when;
            if (known.visits < Integer.MAX_VALUE) known.visits++;
        }
        // Reinsertion makes iteration order a recency order.
        places.put(ref.id, known);
        enforceBound();
        return true;
    }

    private void enforceBound() {
        while (places.size() > MAX_PLACES) {
            String remove = null;
            for (String id : places.keySet()) {
                if (!id.equals(currentPlaceId)) {
                    remove = id;
                    break;
                }
            }
            if (remove == null) return;
            places.remove(remove);
            droppedPlaces++;
        }
    }

    String prompt() {
        if (places.isEmpty()) return "";
        List<Place> recent = new ArrayList<>(places.values());
        StringBuilder sb = new StringBuilder(1400);
        sb.append("### PLACES THEY REMEMBER\n");
        sb.append("Only places the survivor has physically occupied are listed. "
                + "Familiarity is memory, not proof that the place is unchanged. "
                + "Do not restore old furniture or objects unless the live state "
                + "shows them now.\n\n");
        int emitted = 0;
        for (int i = recent.size() - 1; i >= 0 && emitted < MAX_PROMPT_PLACES; i--) {
            Place place = recent.get(i);
            sb.append("- ").append(place.label);
            if (place.id.equals(currentPlaceId)) {
                sb.append(" — where they are now");
                if (place.visits >= 5) sb.append(", deeply familiar");
                else if (place.visits >= 2) sb.append(", somewhere they have returned to");
            } else if (place.visits >= 5) sb.append(" — deeply familiar");
            else if (place.visits >= 2) sb.append(" — somewhere they have returned to");
            else sb.append(" — visited once");
            sb.append('\n');
            emitted++;
        }
        if (places.size() > emitted) {
            sb.append("- and other older places omitted from this page's context\n");
        }
        sb.append('\n');
        return sb.toString();
    }

    void load(Object value) {
        clear();
        if (value == null) return;
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalStateException("worldMemory is not an object");
        }
        currentPlaceId = bounded(JsonParse.str(map, "currentPlaceId", ""),
                StoryEvent.MAX_PLACE_CHARS, true, "currentPlaceId");
        droppedPlaces = nonNegativeLong(map.get("droppedPlaces"), 0, "droppedPlaces");
        Object rows = map.get("places");
        if (rows != null && !(rows instanceof List<?>)) {
            throw new IllegalStateException("worldMemory.places is not an array");
        }
        List<?> list = rows instanceof List<?> l ? l : List.of();
        if (list.size() > MAX_PLACES) {
            throw new IllegalStateException("worldMemory has too many places");
        }
        for (Object row : list) {
            if (!(row instanceof Map<?, ?> place)) {
                throw new IllegalStateException("worldMemory contains a non-object place");
            }
            String id = bounded(JsonParse.str(place, "id", ""),
                    StoryEvent.MAX_PLACE_CHARS, false, "place id");
            String label = bounded(JsonParse.str(place, "label", ""),
                    StoryEvent.MAX_PLACE_CHARS, false, "place label");
            String first = bounded(JsonParse.str(place, "firstSeen", ""),
                    128, true, "firstSeen");
            String last = bounded(JsonParse.str(place, "lastSeen", ""),
                    128, true, "lastSeen");
            int visits = integer(place.get("visits"), 1, "place visits");
            if (visits < 1) throw new IllegalStateException("place visits must be positive");
            if (places.put(id, new Place(id, label, first, last, visits)) != null) {
                throw new IllegalStateException("worldMemory contains duplicate place ids");
            }
        }
        if (!currentPlaceId.isEmpty() && !places.containsKey(currentPlaceId)) {
            throw new IllegalStateException("currentPlaceId is not in worldMemory.places");
        }
    }

    void write(Json j) {
        j.objKey("worldMemory");
        j.put("currentPlaceId", currentPlaceId);
        if (droppedPlaces > 0) j.put("droppedPlaces", droppedPlaces);
        j.arrKey("places");
        for (Place place : places.values()) {
            j.obj();
            j.put("id", place.id);
            j.put("label", place.label);
            j.put("firstSeen", place.firstSeen);
            j.put("lastSeen", place.lastSeen);
            j.put("visits", place.visits);
            j.endObj();
        }
        j.endArr();
        j.endObj();
    }

    String json() {
        Json j = new Json().obj();
        j.put("count", places.size());
        j.put("currentPlaceId", currentPlaceId);
        j.put("droppedPlaces", droppedPlaces);
        j.arrKey("places");
        for (Place place : places.values()) {
            j.obj();
            j.put("id", place.id);
            j.put("label", place.label);
            j.put("firstSeen", place.firstSeen);
            j.put("lastSeen", place.lastSeen);
            j.put("visits", place.visits);
            j.endObj();
        }
        j.endArr();
        return j.endObj().toString();
    }

    int size() {
        return places.size();
    }

    private static String bounded(String value, int max, boolean emptyOkay,
                                  String field) {
        if (value == null) value = "";
        StringBuilder normalised = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            normalised.append(Character.isISOControl(c) ? ' ' : c);
        }
        String clean = normalised.toString().strip();
        if (!emptyOkay && clean.isEmpty()) {
            throw new IllegalStateException(field + " cannot be empty");
        }
        if (clean.length() > max) {
            throw new IllegalStateException(field + " exceeds " + max + " characters");
        }
        return clean;
    }

    private static int integer(Object value, int fallback, String field) {
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

    private static long nonNegativeLong(Object value, long fallback, String field) {
        if (value == null) return fallback;
        if (!(value instanceof Number number)) {
            throw new IllegalStateException(field + " is not a number");
        }
        double raw = number.doubleValue();
        if (!Double.isFinite(raw) || raw != Math.rint(raw)
                || raw < 0 || raw > Long.MAX_VALUE) {
            throw new IllegalStateException(field + " is not a non-negative integer");
        }
        return number.longValue();
    }
}
