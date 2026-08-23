package de.fricke.pzstory;

import java.nio.charset.StandardCharsets;
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
        /** Extra budget for model reasoning. 0 disables it. See profiles.json docs. */
        public final int thinkingTokens;
        public final String systemMode;  // native | prepend_to_user | both
        public final String cacheTtl;    // "1h" | "5m" | "off"

        Profile(String name, Map<String, Object> m) {
            this.name = name;
            this.kind = JsonParse.str(m, "kind", "anthropic");
            this.model = JsonParse.str(m, "model", "");
            this.apiKey = JsonParse.str(m, "apiKey", "");
            this.baseUrl = JsonParse.str(m, "baseUrl", null);
            // Clamped, not trusted. A negative or absurd value from a
            // hand-edited profiles.json would otherwise reach the provider.
            this.maxTokens = clamp(JsonParse.num(m, "maxTokens", 2000), 256, 32000);
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
                    + " maxTokens=" + maxTokens + " " + keyState;
        }
    }

    private static final Map<String, Profile> PROFILES = new LinkedHashMap<>();
    private static String activeName = null;
    private static String loadError = null;
    private static Path path = null;

    private Config() {}

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
        PROFILES.clear();
        activeName = null;
        loadError = null;

        Path p = file();
        if (!Files.isRegularFile(p)) {
            loadError = "not found: " + p;
            return "PZStory: no profiles.json at " + p;
        }
        try {
            String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            Map<String, Object> root = JsonParse.parseObject(text);
            activeName = JsonParse.str(root, "activeProfile", null);

            Map<String, Object> profs = JsonParse.map(root, "profiles");
            if (profs == null) {
                loadError = "no \"profiles\" object";
                return "PZStory: profiles.json has no \"profiles\" object";
            }
            for (Map.Entry<String, Object> e : profs.entrySet()) {
                if (e.getValue() instanceof Map<?, ?>) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) e.getValue();
                    PROFILES.put(e.getKey(), new Profile(e.getKey(), m));
                }
            }
            // A profile chosen on the device beats the file's default, so
            // switching provider does not mean hand-editing JSON.
            String remembered = Settings.profile();
            if (remembered != null && PROFILES.containsKey(remembered)) {
                activeName = remembered;
            }
            if (activeName == null && !PROFILES.isEmpty()) {
                activeName = PROFILES.keySet().iterator().next();
            }
            Profile a = active();
            return "PZStory: loaded " + PROFILES.size() + " profile(s), active="
                    + activeName + (a == null ? " (MISSING)" : a.usable() ? " (ready)" : " (not usable)");
        } catch (Throwable t) {
            // A parse error is the single most likely thing to go wrong for a
            // player editing JSON by hand, so say exactly what and where.
            loadError = t.getClass().getSimpleName() + ": " + t.getMessage();
            return "PZStory: could not read profiles.json - " + loadError;
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
        System.out.println("[PZStory] " + redact(msg));
    }
}
