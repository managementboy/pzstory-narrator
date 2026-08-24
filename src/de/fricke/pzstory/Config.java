package de.fricke.pzstory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import zombie.ZomboidFileSystem;

/**
 * Provider profiles, read from Zomboid/pzstory/profiles.json.
 *
 * The file lives OUTSIDE the mod folder on purpose. Anything under
 * Zomboid/mods/PZStory/ can be swept up by a Workshop upload, a mod backup or
 * a git commit, and this file holds an API key.
 *
 * Nothing here ever logs a key. {@link #redact(String)} is applied to every
 * message the mod prints, so console.txt stays safe to paste into a bug report.
 */
public final class Config {

    private static final int MAX_PROFILES_BYTES = 1024 * 1024;
    private static final int MAX_PROFILES = 64;

    /** Bounds a configured number. Every numeric setting goes through this. */
    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** One provider configuration. */
    public static final class Profile {
        public final String name;
        public final String kind;        // "anthropic" | "openai-compatible" | "gemini"
        public final String model;
        public final String apiKey;
        public final String baseUrl;     // null for anthropic (fixed endpoint)
        public final int maxTokens;
        /** Total prompt characters accepted before a request is started. */
        public final int maxInputChars;
        /** UTF-8 request-body ceiling after provider-specific JSON encoding. */
        public final int maxRequestBytes;
        /** Extra budget for model reasoning. 0 disables it. See profiles.json docs. */
        public final int thinkingTokens;
        public final String systemMode;  // native | prepend_to_user | both
        public final String cacheTtl;    // "1h" | "5m" | "off"
        /** OpenAI-compatible servers vary: opt in only when supported. */
        public final boolean streamUsage;
        /** max_tokens for compatibility, max_completion_tokens for newer APIs. */
        public final String openAiTokenField;

        Profile(String name, Map<String, Object> m) {
            this.name = name;
            this.kind = JsonParse.str(m, "kind", "anthropic");
            this.model = JsonParse.str(m, "model", "");
            this.apiKey = JsonParse.str(m, "apiKey", "");
            this.baseUrl = JsonParse.str(m, "baseUrl", null);
            // Clamped, not trusted. A negative or absurd value from a
            // hand-edited profiles.json would otherwise reach the provider.
            this.maxTokens = clamp(JsonParse.num(m, "maxTokens", 2000), 256, 32000);
            // Input is billable too, and a campaign grows for as long as its
            // save exists. Never let "hosted models get the whole book" mean
            // an unlimited request. These are user-tunable soft ceilings under
            // hard implementation maxima.
            this.maxInputChars = clamp(
                    JsonParse.num(m, "maxInputChars", 300000), 24000, 1000000);
            this.maxRequestBytes = clamp(
                    JsonParse.num(m, "maxRequestBytes", 1000000), 131072, 2000000);
            // Reasoning budget, OFF by default and never applied implicitly.
            // Anthropic bills thinking tokens against max_tokens, so the old
            // code added 8000 to whatever the player had configured - a cap
            // that silently became 8000 higher than the number they typed.
            // Now it is a field they set on purpose, and 0 means no thinking.
            this.thinkingTokens = clamp(JsonParse.num(m, "thinkingTokens", 0), 0, 24000);
            this.systemMode = JsonParse.str(m, "systemMode", "native");
            // 1h by default: pages are written minutes apart, and the 5-minute
            // default would miss between most of them. Cache writes cost 2x,
            // reads are a fraction of base - so a hit pays for several misses.
            this.cacheTtl = JsonParse.str(m, "cacheTtl", "1h");
            this.streamUsage = bool(m, "streamUsage", false);
            this.openAiTokenField = JsonParse.str(
                    m, "openAiTokenField", "max_tokens");

            requireLength("profile name", name, 1, 64);
            requireOneOf("kind", kind, "anthropic", "openai-compatible", "gemini");
            requireLength("model", model, 1, 256);
            requireLength("apiKey", apiKey, 0, 4096);
            if (baseUrl != null) requireLength("baseUrl", baseUrl, 1, Endpoint.MAX_URL_CHARS);
            requireOneOf("systemMode", systemMode, "native", "prepend_to_user", "both");
            requireOneOf("cacheTtl", cacheTtl, "1h", "5m", "off");
            requireOneOf("openAiTokenField", openAiTokenField,
                    "max_tokens", "max_completion_tokens");
        }

        /** True when this profile could actually be used for a call. */
        public boolean usable() {
            if (model.isEmpty()) return false;
            if ("openai-compatible".equals(kind)) return baseUrl != null && !baseUrl.isEmpty();
            return !apiKey.isEmpty() && !apiKey.contains("PASTE");
        }

        public String describe() {
            String keyState = apiKey.isEmpty() ? "no key"
                    : apiKey.contains("PASTE") ? "PLACEHOLDER - not filled in"
                    : "key set (" + apiKey.length() + " chars)";
            return name + " [" + kind + "] model=" + model
                    + " maxTokens=" + maxTokens
                    + " maxInputChars=" + maxInputChars + " " + keyState;
        }
    }

    private static final Map<String, Profile> PROFILES = new LinkedHashMap<>();
    private static String activeName = null;
    private static String loadError = null;
    private static Path path = null;

    private Config() {}

    private static void requireLength(String field, String value, int min, int max) {
        int n = value == null ? 0 : value.length();
        if (n < min || n > max) {
            throw new IllegalArgumentException(field + " length " + n
                    + " is outside " + min + ".." + max);
        }
    }

    private static void requireOneOf(String field, String value, String... allowed) {
        for (String candidate : allowed) if (candidate.equals(value)) return;
        throw new IllegalArgumentException("unsupported " + field + " value");
    }

    private static boolean bool(Map<String, Object> values, String key, boolean fallback) {
        Object value = values.get(key);
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) return Boolean.parseBoolean(s);
        return fallback;
    }

    public static Path file() {
        if (path == null) {
            String base;
            try {
                base = ZomboidFileSystem.instance.getCacheDir();
            } catch (Throwable t) {
                base = System.getProperty("user.home") + "/Zomboid";
            }
            path = Paths.get(base, "pzstory", "profiles.json");
        }
        return path;
    }

    /**
     * (Re)reads the file. Safe to call repeatedly - it is how the player picks
     * up an edit without restarting the game.
     *
     * @return a human-readable status line, never null, never containing a key.
     */
    public static synchronized String reload() {
        Path p = file();
        if (!Files.isRegularFile(p)) {
            loadError = "not found: " + p;
            return "PZStory: no profiles.json at " + p
                    + (PROFILES.isEmpty() ? "" : " - keeping the last valid configuration");
        }
        try {
            String text = BoundedFiles.readUtf8(p, MAX_PROFILES_BYTES);
            Map<String, Object> root = JsonParse.parseObject(text);
            String nextActive = JsonParse.str(root, "activeProfile", null);
            Map<String, Profile> next = new LinkedHashMap<>();

            Map<String, Object> profs = JsonParse.map(root, "profiles");
            if (profs == null) {
                throw new IllegalArgumentException("no \"profiles\" object");
            }
            if (profs.size() > MAX_PROFILES) {
                throw new IllegalArgumentException("more than " + MAX_PROFILES + " profiles");
            }
            for (Map.Entry<String, Object> e : profs.entrySet()) {
                if (e.getValue() instanceof Map<?, ?>) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) e.getValue();
                    next.put(e.getKey(), new Profile(e.getKey(), m));
                }
            }
            if (next.isEmpty()) throw new IllegalArgumentException("no valid profiles");
            // A profile chosen on the device beats the file's default, so
            // switching provider does not mean hand-editing JSON.
            String remembered = Settings.profile();
            if (remembered != null && next.containsKey(remembered)) {
                nextActive = remembered;
            }
            if (nextActive == null || !next.containsKey(nextActive)) {
                nextActive = next.keySet().iterator().next();
            }

            // Publish only after the entire document and every profile passed.
            PROFILES.clear();
            PROFILES.putAll(next);
            activeName = nextActive;
            loadError = null;
            Profile a = PROFILES.get(activeName);
            return "PZStory: loaded " + PROFILES.size() + " profile(s), active="
                    + activeName + (a == null ? " (MISSING)" : a.usable() ? " (ready)" : " (not usable)");
        } catch (Throwable t) {
            // A parse error is the single most likely thing to go wrong for a
            // player editing JSON by hand, so say exactly what and where.
            loadError = t.getClass().getSimpleName() + ": " + t.getMessage();
            return "PZStory: could not read profiles.json - " + loadError
                    + (PROFILES.isEmpty() ? "" : " - keeping the last valid configuration");
        }
    }

    public static synchronized Profile active() {
        if (PROFILES.isEmpty() && loadError == null) reload();
        return activeName == null ? null : PROFILES.get(activeName);
    }

    public static synchronized boolean setActive(String name) {
        if (!PROFILES.containsKey(name)) return false;
        activeName = name;
        return true;
    }

    public static synchronized Profile profile(String name) {
        if (PROFILES.isEmpty() && loadError == null) reload();
        return PROFILES.get(name);
    }

    public static synchronized List<String> profileNames() {
        return new ArrayList<>(PROFILES.keySet());
    }

    /** Profile list as JSON for the Lua options dropdown - never includes keys. */
    public static synchronized String profilesJson() {
        if (PROFILES.isEmpty() && loadError == null) reload();
        Json j = new Json().obj();
        j.put("file", file().toString());
        if (loadError != null) j.put("error", loadError);
        j.put("active", activeName);
        j.arrKey("profiles");
        for (Profile p : PROFILES.values()) {
            j.obj();
            j.put("name", p.name);
            j.put("kind", p.kind);
            j.put("model", p.model);
            j.put("usable", p.usable());
            j.endObj();
        }
        j.endArr();
        return j.endObj().toString();
    }

    // ------------------------------------------------------------- redaction

    /**
     * Removes anything key-shaped from a string before it is logged.
     *
     * Two passes: the exact keys we hold (so a key echoed back in an error body
     * is caught), then a generic sweep for common key prefixes in case a
     * provider quotes it in a shape we did not anticipate.
     */
    public static synchronized String redact(String text) {
        if (text == null) return null;
        String out = text;
        for (Profile p : PROFILES.values()) {
            if (p.apiKey != null && p.apiKey.length() > 8) {
                out = out.replace(p.apiKey, "***REDACTED***");
            }
        }
        out = out.replaceAll("(sk-[A-Za-z0-9_\\-]{6})[A-Za-z0-9_\\-]{8,}", "$1***REDACTED***");
        out = out.replaceAll("(AIza)[A-Za-z0-9_\\-]{20,}", "$1***REDACTED***");
        return out;
    }

    /** Every log line from the mod goes through here. */
    public static void log(String msg) {
        System.out.println("[PZStory] " + safeForLog(msg));
    }

    /**
     * A bounded diagnostic safe to return across the Lua bridge. Provider
     * errors can echo credentials and terminal control characters; the device
     * should never display either even though it is not a conventional log.
     */
    public static String safeForDisplay(String text) {
        String raw = redact(String.valueOf(text));
        final int max = 800;
        final String marker = "...[truncated]";
        StringBuilder out = new StringBuilder(Math.min(raw.length(), max));
        int i = 0;
        for (; i < raw.length() && out.length() < max; i++) {
            char c = raw.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') {
                out.append(' ');
            } else if (c >= 0x20 && c != 0x7f) {
                out.append(c);
            }
        }
        if (i < raw.length()) {
            int end = Math.max(0, max - marker.length());
            if (end > 0 && end <= out.length()
                    && Character.isHighSurrogate(out.charAt(end - 1))) end--;
            out.setLength(end);
            out.append(marker);
        }
        return out.toString().strip();
    }

    /**
     * One physical log record, even when a provider or another Lua mod passes
     * CR/LF, terminal escapes, NULs, or a very large string. Redaction runs on
     * the bounded candidate before anything reaches stdout.
     */
    private static String safeForLog(String text) {
        String raw = String.valueOf(text);
        if (raw.length() > 16384) raw = raw.substring(0, 16384) + "...[input truncated]";
        raw = redact(raw);

        StringBuilder out = new StringBuilder(Math.min(raw.length() + 32, 4096));
        for (int i = 0; i < raw.length() && out.length() < 4096; i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x7f) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        if (out.length() >= 4096) {
            out.setLength(Math.max(0, 4096 - "...[truncated]".length()));
            out.append("...[truncated]");
        }
        return out.toString();
    }
}
