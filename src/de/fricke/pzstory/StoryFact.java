package de.fricke.pzstory;

import java.util.Locale;
import java.util.List;
import java.util.Set;

/** One bounded, typed claim in long-term story memory. */
public final class StoryFact {
    public static final List<String> TYPE_ORDER = List.of("world", "biography", "person",
            "possession", "injury", "knowledge", "belief", "promise", "thread");
    public static final Set<String> TYPES = Set.copyOf(TYPE_ORDER);
    public static final Set<String> SOURCES = Set.of("game", "player", "media",
            "narrator", "legacy");
    public static final int MAX_TEXT = 300;

    public final long id;
    public final String type;
    public final String key;
    public final String text;
    public final String source;
    public final int confidence;
    public final int page;
    public final long supersededBy;

    StoryFact(long id, String type, String key, String text, String source, int confidence,
              int page, long supersededBy) {
        if (id < 1) throw new IllegalArgumentException("fact id must be positive");
        if (!TYPES.contains(type)) throw new IllegalArgumentException("invalid fact type");
        if (!SOURCES.contains(source)) throw new IllegalArgumentException("invalid fact source");
        if (key == null || key.length() > 80 || key.matches(".*[\\p{Cntrl}].*")) {
            throw new IllegalArgumentException("invalid fact key");
        }
        if (text == null || text.isBlank() || text.length() > MAX_TEXT) {
            throw new IllegalArgumentException("invalid fact text");
        }
        if (confidence < 1 || confidence > 100) {
            throw new IllegalArgumentException("fact confidence must be 1..100");
        }
        if (page < 0 || supersededBy < 0) throw new IllegalArgumentException("invalid fact link");
        this.id = id;
        this.type = type;
        this.key = key;
        this.text = text.strip();
        this.source = source;
        this.confidence = confidence;
        this.page = page;
        this.supersededBy = supersededBy;
    }

    StoryFact superseded(long replacement) {
        return new StoryFact(id, type, key, text, source, confidence, page, replacement);
    }

    static Parsed parse(String raw, String fallbackType) {
        if (raw == null) return null;
        String text = raw.strip();
        String type = TYPES.contains(fallbackType) ? fallbackType : "knowledge";
        if (text.startsWith("[") && text.indexOf(']') > 1) {
            int close = text.indexOf(']');
            String candidate = text.substring(1, close).strip().toLowerCase(Locale.ROOT);
            if (TYPES.contains(candidate)) {
                type = candidate;
                text = text.substring(close + 1).strip();
            }
        }
        if (text.isEmpty() || text.length() > MAX_TEXT) return null;
        return new Parsed(type, text);
    }

    record Parsed(String type, String text) {}
}
