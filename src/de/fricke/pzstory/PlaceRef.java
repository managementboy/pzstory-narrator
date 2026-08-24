package de.fricke.pzstory;

import java.util.Locale;
import java.util.Map;

/** Local stable place identity plus the only label providers are allowed to see. */
final class PlaceRef {
    final String id;
    final String label;

    private PlaceRef(String id, String label) {
        this.id = id;
        this.label = label;
    }

    static PlaceRef fromState(Map<String, Object> root) {
        Map<String, Object> position = JsonParse.map(root, "position");
        if (position == null) return null;
        String room = safeLabel(JsonParse.str(position, "room", ""), "");
        String roomId = JsonParse.str(position, "roomId", "");
        String zone = safeLabel(JsonParse.str(position, "placeName", ""), "");
        String floor = safeLabel(JsonParse.str(position, "floor", ""), "");
        String id;
        if (!roomId.isBlank()) {
            id = "room:" + boundedId(roomId);
        } else {
            Map<String, Object> building = JsonParse.map(position, "building");
            if (building != null && building.get("id") instanceof Number number) {
                id = "building:" + number.longValue() + ":z"
                        + JsonParse.num(position, "z", 0);
            } else if (!zone.isBlank()) {
                id = "zone:" + boundedId(zone.toLowerCase(Locale.ROOT)) + ":"
                        + JsonParse.num(position, "cellX", 0) + ":"
                        + JsonParse.num(position, "cellY", 0);
            } else {
                id = "cell:" + JsonParse.num(position, "cellX", 0) + ":"
                        + JsonParse.num(position, "cellY", 0) + ":z"
                        + JsonParse.num(position, "z", 0);
            }
        }
        StringBuilder label = new StringBuilder();
        if (!zone.isBlank()) label.append(zone);
        if (!room.isBlank()) {
            if (label.length() > 0) label.append(", ");
            label.append(room);
        } else if (Boolean.TRUE.equals(position.get("outdoors")) && label.length() == 0) {
            label.append("the open air");
        }
        if (!floor.isBlank() && !room.isBlank()) label.append(" (").append(floor).append(')');
        if (label.length() == 0) label.append("this part of Knox County");
        return new PlaceRef(boundedId(id), safeLabel(label.toString(), "this place"));
    }

    static String safeLabel(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        StringBuilder clean = new StringBuilder(Math.min(value.length(), 192));
        for (int i = 0; i < value.length() && clean.length() < 192; i++) {
            char c = value.charAt(i);
            clean.append(Character.isISOControl(c) ? ' ' : c);
        }
        String result = clean.toString().strip();
        return result.isEmpty() ? fallback : result;
    }

    private static String boundedId(String value) {
        String clean = safeLabel(value, "unknown");
        return clean.length() <= StoryEvent.MAX_PLACE_CHARS
                ? clean : clean.substring(0, StoryEvent.MAX_PLACE_CHARS);
    }
}
