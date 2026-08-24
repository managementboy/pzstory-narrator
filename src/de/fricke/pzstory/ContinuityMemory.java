package de.fricke.pzstory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded local evidence for familiarity and routine, projected without ids. */
final class ContinuityMemory {
    static final int MAX_ENTRIES = 200;
    private static final int MAX_KEY = 240;
    private static final int MAX_LABEL = 192;
    record Entry(String kind, String key, String label, int occurrences,
                 String firstSeen, String lastSeen) {}
    record Snapshot(List<Entry> entries) {}

    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();

    void clear() { entries.clear(); }
    Snapshot snapshot() { return new Snapshot(List.copyOf(entries.values())); }
    void restore(Snapshot s) { entries.clear(); for (Entry e : s.entries) entries.put(slot(e.kind, e.key), e); }

    boolean record(String kind, String key, String label, String stamp) {
        if (!List.of("weapon", "vehicle", "routine", "rest").contains(kind)) return false;
        key = bounded(key, MAX_KEY, false); label = bounded(label, MAX_LABEL, false);
        stamp = bounded(stamp, 80, true);
        String slot = slot(kind, key);
        Entry old = entries.get(slot);
        if (old == null) {
            if (entries.size() >= MAX_ENTRIES) entries.remove(entries.keySet().iterator().next());
            entries.put(slot, new Entry(kind, key, label, 1, stamp, stamp));
        } else {
            entries.put(slot, new Entry(kind, key, label,
                    Math.min(1_000_000, old.occurrences + 1), old.firstSeen, stamp));
        }
        return true;
    }

    String prompt() {
        StringBuilder out = new StringBuilder(2048);
        for (Entry e : entries.values()) {
            int threshold = "routine".equals(e.kind) ? 3 : 2;
            if (e.occurrences < threshold) continue;
            if (out.length() == 0) {
                out.append("### EVIDENCE OF FAMILIARITY AND ROUTINE\n")
                        .append("These are repeated game observations, not ownership or safety claims.\n\n");
            }
            switch (e.kind) {
                case "weapon" -> out.append("- They have killed with ").append(e.label)
                        .append(" more than once; it is becoming familiar.\n");
                case "vehicle" -> out.append("- They have returned to the same ")
                        .append(e.label).append(" more than once. The game has not proved ownership.\n");
                case "rest" -> out.append("- They have slept at ").append(e.label)
                        .append(" more than once. Call it familiar shelter, not necessarily safe.\n");
                case "routine" -> out.append("- They have repeatedly ").append(e.label).append(".\n");
                default -> { }
            }
        }
        if (out.length() > 0) out.append('\n');
        return out.toString();
    }

    void write(Json j) {
        j.objKey("continuityMemory"); j.arrKey("entries");
        for (Entry e : entries.values()) {
            j.obj(); j.put("kind", e.kind); j.put("key", e.key); j.put("label", e.label);
            j.put("occurrences", e.occurrences); j.put("firstSeen", e.firstSeen);
            j.put("lastSeen", e.lastSeen); j.endObj();
        }
        j.endArr(); j.endObj();
    }

    void load(Object value) {
        clear();
        if (!(value instanceof Map<?, ?> map)) throw new IllegalStateException("continuityMemory is not an object");
        Object rows = map.get("entries");
        if (!(rows instanceof List<?> list) || list.size() > MAX_ENTRIES) {
            throw new IllegalStateException("continuityMemory entries are invalid");
        }
        for (Object row : list) {
            if (!(row instanceof Map<?, ?>)) throw new IllegalStateException("continuity entry is not an object");
            String kind = JsonParse.str(row, "kind", "");
            String key = bounded(JsonParse.str(row, "key", ""), MAX_KEY, false);
            String label = bounded(JsonParse.str(row, "label", ""), MAX_LABEL, false);
            int count = JsonParse.num(row, "occurrences", 0);
            if (!List.of("weapon", "vehicle", "routine", "rest").contains(kind)
                    || count < 1 || count > 1_000_000 || entries.containsKey(slot(kind, key))) {
                throw new IllegalStateException("continuity entry is invalid");
            }
            Entry e = new Entry(kind, key, label, count,
                    bounded(JsonParse.str(row, "firstSeen", ""), 80, true),
                    bounded(JsonParse.str(row, "lastSeen", ""), 80, true));
            entries.put(slot(kind, key), e);
        }
    }

    String json() { Json j = new Json().obj(); write(j); return j.endObj().toString(); }

    private static String slot(String kind, String key) { return kind + "\u0000" + key; }
    private static String bounded(String value, int max, boolean emptyOkay) {
        if (value == null) value = "";
        String clean = value.replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").strip();
        if ((!emptyOkay && clean.isEmpty()) || clean.length() > max) {
            throw new IllegalArgumentException("invalid continuity text");
        }
        return clean;
    }
}
