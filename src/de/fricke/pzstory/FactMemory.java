package de.fricke.pzstory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashSet;

/** Bounded fact store with provenance and conservative contradiction handling. */
final class FactMemory {
    static final int MAX_FACTS = 2000;
    private final ArrayList<StoryFact> facts = new ArrayList<>();
    private long nextId = 1;

    record Snapshot(List<StoryFact> facts, long nextId) {}

    Snapshot snapshot() { return new Snapshot(List.copyOf(facts), nextId); }
    void restore(Snapshot s) { facts.clear(); facts.addAll(s.facts); nextId = s.nextId; }
    void clear() { facts.clear(); nextId = 1; }
    int size() { return facts.size(); }

    boolean add(String raw, String fallbackType, String source, int confidence, int page) {
        StoryFact.Parsed parsed = StoryFact.parse(raw, fallbackType);
        if (parsed == null || !StoryFact.SOURCES.contains(source)) return false;
        String exact = normalize(parsed.text());
        for (StoryFact fact : facts) {
            if (fact.supersededBy == 0 && normalize(fact.text).equals(exact)) return false;
        }

        long id = nextId;
        StoryFact incoming = new StoryFact(id, parsed.type(), "", parsed.text(), source,
                confidence, page, 0);
        String contradiction = contradictionKey(incoming.text);
        if (contradiction != null) {
            for (StoryFact old : facts) {
                if (old.supersededBy == 0 && !samePolarity(old.text, incoming.text)
                        && contradiction.equals(contradictionKey(old.text))
                        && authority(incoming) <= authority(old)) return false;
            }
            for (int i = 0; i < facts.size(); i++) {
                StoryFact old = facts.get(i);
                if (old.supersededBy != 0 || samePolarity(old.text, incoming.text)) continue;
                if (contradiction.equals(contradictionKey(old.text))
                        && authority(incoming) >= authority(old)) {
                    facts.set(i, old.superseded(id));
                }
            }
        }
        nextId++;
        facts.add(incoming);
        while (facts.size() > MAX_FACTS) facts.remove(0);
        return true;
    }

    /** Replaces the active value for one game-proven semantic slot. */
    boolean upsert(String key, String raw, String type, String source,
                   int confidence, int page) {
        if (key == null || key.isBlank() || key.length() > 80) return false;
        StoryFact.Parsed parsed = StoryFact.parse(raw, type);
        if (parsed == null || !StoryFact.SOURCES.contains(source)) return false;
        StoryFact old = null;
        int oldAt = -1;
        for (int i = 0; i < facts.size(); i++) {
            StoryFact candidate = facts.get(i);
            if (candidate.supersededBy == 0 && key.equals(candidate.key)) {
                old = candidate; oldAt = i; break;
            }
        }
        if (old != null && normalize(old.text).equals(normalize(parsed.text()))) return false;
        StoryFact incoming = new StoryFact(nextId, parsed.type(), key, parsed.text(),
                source, confidence, page, 0);
        if (old != null && authority(incoming) < authority(old)) return false;
        nextId++;
        if (old != null) facts.set(oldAt, old.superseded(incoming.id));
        facts.add(incoming);
        while (facts.size() > MAX_FACTS) facts.remove(0);
        return true;
    }

    List<String> activeText() {
        ArrayList<String> out = new ArrayList<>();
        for (StoryFact fact : facts) if (fact.supersededBy == 0) out.add(fact.text);
        return out;
    }

    String prompt() {
        if (facts.isEmpty()) return "";
        StringBuilder out = new StringBuilder(4096);
        out.append("### STRUCTURED STORY MEMORY\n");
        out.append("Active facts grouped by kind. Player and game facts outrank narrator interpretation.\n\n");
        for (String type : StoryFact.TYPE_ORDER) {
            boolean heading = false;
            for (StoryFact fact : facts) {
                if (fact.supersededBy != 0 || !type.equals(fact.type)) continue;
                if (!heading) { out.append(type.toUpperCase(Locale.ROOT)).append(":\n"); heading = true; }
                out.append("- ").append(fact.text).append(" [")
                        .append(fact.source).append(", confidence ")
                        .append(fact.confidence).append("]\n");
            }
            if (heading) out.append('\n');
        }
        return out.toString();
    }

    void write(Json j) {
        j.objKey("factMemory");
        j.put("nextId", nextId);
        j.arrKey("facts");
        for (StoryFact fact : facts) {
            j.obj();
            j.put("id", fact.id); j.put("type", fact.type); j.put("text", fact.text);
            if (!fact.key.isEmpty()) j.put("key", fact.key);
            j.put("source", fact.source); j.put("confidence", fact.confidence);
            j.put("page", fact.page); j.put("supersededBy", fact.supersededBy);
            j.endObj();
        }
        j.endArr(); j.endObj();
    }

    void load(Object value) {
        clear();
        if (!(value instanceof Map<?, ?> map)) throw new IllegalStateException("factMemory is not an object");
        Object rows = map.get("facts");
        if (!(rows instanceof List<?> list) || list.size() > MAX_FACTS) {
            throw new IllegalStateException("factMemory facts are invalid");
        }
        long highest = 0;
        long previous = 0;
        for (Object row : list) {
            if (!(row instanceof Map<?, ?>)) throw new IllegalStateException("fact is not an object");
            long id = longNum(row, "id", 0), superseded = longNum(row, "supersededBy", 0);
            StoryFact fact = new StoryFact(id, JsonParse.str(row, "type", ""),
                    JsonParse.str(row, "key", ""),
                    JsonParse.str(row, "text", ""), JsonParse.str(row, "source", ""),
                    JsonParse.num(row, "confidence", 0), JsonParse.num(row, "page", 0), superseded);
            if (id <= previous) throw new IllegalStateException("fact ids are not increasing");
            facts.add(fact); highest = id; previous = id;
        }
        HashSet<Long> ids = new HashSet<>();
        for (StoryFact fact : facts) ids.add(fact.id);
        for (StoryFact fact : facts) {
            if (fact.supersededBy != 0
                    && (fact.supersededBy <= fact.id || !ids.contains(fact.supersededBy))) {
                throw new IllegalStateException("fact supersession link is invalid");
            }
        }
        nextId = longNum(map, "nextId", highest + 1);
        if (nextId <= highest) throw new IllegalStateException("factMemory nextId is stale");
    }

    String json() { Json j = new Json().obj(); write(j); return j.endObj().toString(); }

    private static int authority(StoryFact f) {
        int source = switch (f.source) { case "game" -> 500; case "player" -> 400;
            case "media" -> 300; case "narrator" -> 200; default -> 100; };
        return source + f.confidence;
    }
    private static boolean samePolarity(String a, String b) { return negated(a) == negated(b); }
    private static boolean negated(String s) { return normalize(s).matches(".*\\b(no|not|never|cannot|can't|isn't|doesn't|hasn't|without)\\b.*"); }
    private static String contradictionKey(String s) {
        String n = normalize(s);
        boolean negative = negated(n);
        String core = n.replaceAll("\\b(no|not|never|cannot|can't|isn't|doesn't|hasn't|without)\\b", "")
                .replaceAll("\\s+", " ").strip();
        return negative && core.length() >= 8 ? core : (containsNegativeCounterpartToken(n) ? core : null);
    }
    private static boolean containsNegativeCounterpartToken(String s) {
        // Positive claims participate only if a later negative claim has the same core.
        return !s.isBlank();
    }
    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}']+", " ").strip();
    }
    private static long longNum(Object object, String key, long fallback) {
        if (!(object instanceof Map<?, ?> map) || !(map.get(key) instanceof Number n)) return fallback;
        double d = n.doubleValue();
        if (!Double.isFinite(d) || d != Math.rint(d) || d < 0 || d > Long.MAX_VALUE) {
            throw new IllegalStateException(key + " is not a non-negative integer");
        }
        return n.longValue();
    }
}
