package de.fricke.pzstory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/**
 * Player-facing settings, edited on the device's SETUP screen.
 *
 * Deliberately NOT a dependency on a mod-options framework: B42 has no native
 * one, the popular Mod Options is a workshop mod, and PZStory already asks the
 * player to install ZombieBuddy. A second required framework before we publish
 * is a worse trade than a settings screen we own.
 *
 * Lives beside profiles.json in Zomboid/pzstory/ - it is per-player taste, not
 * per-campaign, so it does NOT belong in the save folder.
 */
public final class Settings {

    private static final int MAX_SETTINGS_BYTES = 64 * 1024;

    /**
     * How much of the world the narrator is allowed to see.
     *
     * Elkin's rule is level 3, and it is the default: a glance at the room he
     * stands in, plus memory of rooms he has walked through. The lower levels
     * exist because this is taste, not correctness - some players want the
     * story to know only what is in their hands.
     */
    public static final int KNOW_CARRIED = 1;   // player only
    public static final int KNOW_GLANCE  = 2;   // + this room, at a glance
    public static final int KNOW_MEMORY  = 3;   // + rooms he has already seen

    public static final String[] KNOW_LABELS = {
        "", "just me", "a glance", "glance + memory"
    };

    /**
     * How much the narrator leans on the fact that this ends badly.
     *
     * Project Zomboid's own thesis is "this is how you died", and a narrator
     * that does not know it writes adventure fiction by default. But it is a
     * colour, not a plot: a page must never foreshadow THIS death, and the
     * player who wants a hopeful story is entitled to one - which is why this
     * is a dial rather than a cardinal rule.
     */
    public static final int DOOM_HOPEFUL   = 1;
    public static final int DOOM_AMBIGUOUS = 2;
    public static final int DOOM_INEVITABLE = 3;

    private static int zoom = 0;                // 0 = pick from screen height
    private static int knowledge = KNOW_MEMORY;
    private static int words = 200;
    private static boolean pauseOnOpen = true;
    private static String profile = "";
    private static int nudge = 2;   // 1 none, 2 a hint, 3 plainly
    private static int doom = DOOM_INEVITABLE;
    private static boolean loaded = false;

    private Settings() {}

    private static Path file() {
        return Config.file().getParent().resolve("settings.json");
    }

    public static synchronized void load() {
        if (loaded) return;
        try {
            Path p = file();
            if (!Files.isRegularFile(p)) { loaded = true; return; }
            Map<String, Object> m = JsonParse.parseObject(
                    BoundedFiles.readUtf8(p, MAX_SETTINGS_BYTES));

            int nextZoom = JsonParse.num(m, "zoom", 0);
            int nextKnowledge = clampKnow(JsonParse.num(m, "knowledge", KNOW_MEMORY));
            int nextWords = Math.max(100, Math.min(400, JsonParse.num(m, "words", 200)));
            boolean nextPause = !"false".equals(JsonParse.str(m, "pauseOnOpen", "true"));
            String nextProfile = JsonParse.str(m, "profile", "");
            int nextNudge = Math.max(1, Math.min(3, JsonParse.num(m, "nudge", 2)));
            int nextDoom = Math.max(1, Math.min(3,
                    JsonParse.num(m, "doom", DOOM_INEVITABLE)));
            if (nextProfile.length() > 64) {
                throw new IllegalArgumentException("profile name is longer than 64 characters");
            }

            zoom = nextZoom;
            knowledge = nextKnowledge;
            words = nextWords;
            pauseOnOpen = nextPause;
            profile = nextProfile;
            nudge = nextNudge;
            doom = nextDoom;
            loaded = true;
            Config.log("settings: knowledge=" + KNOW_LABELS[knowledge]
                    + " words=" + words + " pause=" + pauseOnOpen + " zoom=" + zoom);
        } catch (Throwable t) {
            loaded = true;
            Config.log("settings: unreadable (" + t + ") - using defaults");
        }
    }

    private static synchronized void save() {
        try {
            Json j = new Json().obj();
            j.put("zoom", zoom);
            j.put("knowledge", knowledge);
            j.put("words", words);
            j.put("pauseOnOpen", pauseOnOpen ? "true" : "false");
            j.put("profile", profile);
            j.put("nudge", nudge);
            j.put("doom", doom);
            j.endObj();
            Path p = file();
            Files.createDirectories(p.getParent());
            Path tmp = p.resolveSibling("settings.json.tmp");
            Files.write(tmp, j.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable t) {
            Config.log("settings: SAVE FAILED - " + t);
        }
    }

    private static int clampKnow(int k) {
        return k < KNOW_CARRIED ? KNOW_CARRIED : (k > KNOW_MEMORY ? KNOW_MEMORY : k);
    }

    public static synchronized int zoom()        { load(); return zoom; }
    public static synchronized int knowledge()   { load(); return knowledge; }
    public static synchronized int words()       { load(); return words; }
    public static synchronized boolean pause()   { load(); return pauseOnOpen; }

    public static synchronized void setZoom(int z) {
        load();
        if (z == zoom) return;
        zoom = z; save();
    }

    public static synchronized void setKnowledge(int k) {
        load(); knowledge = clampKnow(k); save();
    }

    public static synchronized void setWords(int w) {
        load(); words = Math.max(100, Math.min(400, w)); save();
    }

    /** The chosen provider, remembered across sessions. */
    public static synchronized String profile() { load(); return profile; }

    public static synchronized void setProfileName(String name) {
        load();
        if (name == null) return;
        profile = name;
        save();
    }

    /** How hard a page pushes toward the next thing: 1 none, 2 a hint, 3 plainly. */
    public static synchronized int nudge() { load(); return nudge; }

    public static synchronized void setNudge(int n) {
        load(); nudge = Math.max(1, Math.min(3, n)); save();
    }

    /** 1 hopeful, 2 ambiguous, 3 inevitable. See DOOM_*. */
    public static synchronized int doom() { load(); return doom; }

    public static synchronized void setDoom(int d) {
        load(); doom = Math.max(1, Math.min(3, d)); save();
    }

    public static synchronized void setPause(boolean b) {
        load(); pauseOnOpen = b; save();
    }

    /** Everything the SETUP screen needs, as JSON. */
    public static synchronized String json() {
        load();
        Json j = new Json().obj();
        j.put("zoom", zoom);
        j.put("knowledge", knowledge);
        j.put("knowledgeLabel", KNOW_LABELS[knowledge]);
        j.put("words", words);
        j.put("pause", pauseOnOpen);
        j.put("nudge", nudge);
        j.put("doom", doom);
        Config.Profile p = Config.active();
        j.put("profile", p == null ? "none" : p.name);
        j.put("model", p == null ? "" : p.model);
        return j.endObj().toString();
    }
}
