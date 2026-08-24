package de.fricke.pzstory;

import java.util.Locale;

/** Exact, deterministic repetition signals; no fuzzy false-positive guessing. */
final class RepetitionGuard {
    private RepetitionGuard() {}

    static String titleKey(String title) { return normalized(title, Integer.MAX_VALUE); }
    static String openingKey(String page) { return normalized(page, 10); }

    private static String normalized(String value, int maxWords) {
        if (value == null) return "";
        String clean = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}']+", " ").strip();
        if (clean.isEmpty()) return "";
        String[] words = clean.split("\\s+");
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < Math.min(words.length, maxWords); i++) {
            if (i > 0) key.append(' ');
            key.append(words[i]);
        }
        return key.toString();
    }
}
