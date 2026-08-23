package de.fricke.pzstory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import zombie.ZomboidFileSystem;

/**
 * The campaign store: every page ever written, plus the canon that accumulates
 * around them.
 *
 * Lives INSIDE the save folder (Zomboid/Saves/.../<save>/pzstory/) rather than
 * beside profiles.json. A campaign belongs to a save: copy the save and the
 * story travels with it, delete the save and the story goes too, start a new
 * game and you get a new book rather than inheriting a stranger's.
 *
 * Writes are atomic - temp file then move - because the game can be killed at
 * any moment and a half-written page would poison every later prompt.
 */
public final class Campaign {

    /** One written page. */
    public static final class Page {
        public final int number;
        public final String title;
        public final String text;
        public final String stamp;      // in-game date and time when written

        Page(int number, String title, String text, String stamp) {
            this.number = number;
            this.title = title;
            this.text = text;
            this.stamp = stamp;
        }
    }

    private static Path root;
    private static final List<Page> PAGES = new ArrayList<>();
    private static final LinkedHashSet<String> CANON = new LinkedHashSet<>();

    // The player note channel. Three types, three lifetimes - getting the
    // lifetime wrong is the whole failure mode, so they are stored apart
    // rather than tagged in one list.
    //   observation -> folded straight into CANON, permanent
    //   direction   -> queued here, spent on the next page
    //   standing    -> in force until the player removes it
    private static final List<String> DIRECTIONS = new ArrayList<>();
    private static final LinkedHashSet<String> STANDING = new LinkedHashSet<>();

    private static boolean loaded = false;

    /**
     * Which campaign this is, counting from process start.
     *
     * Loading a different save resets every field in this class, but a model
     * request already in flight knows nothing about that: its callback would
     * happily write page 14 of the OLD book into the NEW one, along with its
     * canon, its directions and its lastState. That is silent cross-save
     * corruption and it needs no unusual timing - just a save loaded while a
     * page is being written.
     *
     * A request captures this number when it starts and is dropped on
     * completion if the number has moved.
     */
    private static final java.util.concurrent.atomic.AtomicLong GENERATION =
            new java.util.concurrent.atomic.AtomicLong(1);

    /** The current campaign generation. Cheap; safe from any thread. */
    public static long generation() {
        return GENERATION.get();
    }

    private Campaign() {}

    // ----------------------------------------------------------------- paths

    private static Path root() {
        if (root == null) {
            String dir = null;
            try {
                dir = ZomboidFileSystem.instance.getFileNameInCurrentSave("pzstory");
            } catch (Throwable ignored) { }
            if (dir == null || dir.isEmpty()) {
                // No save loaded (main menu). Park it somewhere harmless rather
                // than throwing; the book simply has no history there.
                dir = ZomboidFileSystem.instance.getCacheDir() + "/pzstory/no-save";
            }
            root = Paths.get(dir);
        }
        return root;
    }

    /** Call when a save is loaded - the path is save-specific. */
    public static synchronized void reset() {
        // Bump FIRST. Anything in flight is now writing into a book that no
        // longer exists, and will be discarded when it tries to commit.
        GENERATION.incrementAndGet();
        root = null;
        PAGES.clear();
        CANON.clear();
        DIRECTIONS.clear();
        STANDING.clear();
        SEEN.clear();
        OPENING = "";
        SCENARIO = "";
        PREMISE = "";
        LAST_STATE = "";
        TODO.clear();
        ACHIEVED.clear();
        DECLINED.clear();
        loaded = false;
    }

    // ------------------------------------------------------------------ load

    public static synchronized void load() {
        if (loaded) return;
        loaded = true;
        try {
            Path p = root().resolve("campaign.json");
            if (!Files.isRegularFile(p)) {
                Config.log("campaign: new book at " + root());
                return;
            }
            Map<String, Object> m = JsonParse.parseObject(
                    new String(Files.readAllBytes(p), StandardCharsets.UTF_8));

            Object canon = m.get("canon");
            if (canon instanceof List<?> l) {
                for (Object o : l) if (o instanceof String s) CANON.add(s);
            }
            OPENING = JsonParse.str(m, "opening", "");
            // Self-heal a campaign opened by 1.15.0/1.15.1, where the trait
            // list had become objects but the opening builder still called
            // String.valueOf() on them and wrote "He is {name=keen cook,
            // kind=..., means=...}" into a field that is FIXED for the life of
            // the book and re-read on every single page. Clearing it makes the
            // next page rebuild it correctly from the same character.
            if (OPENING.contains("{name=") || OPENING.contains("{name =")) {
                Config.log("campaign: opening was written by a broken build "
                        + "- clearing it so the next page rewrites it");
                OPENING = "";
            }
            SCENARIO = JsonParse.str(m, "scenario", "");
            PREMISE  = JsonParse.str(m, "premise", "");
            LAST_STATE = JsonParse.str(m, "lastState", "");
            Object td = m.get("todo");
            if (td instanceof List<?> l) {
                for (Object o : l) if (o instanceof String s3) TODO.add(s3);
            }
            Object ac = m.get("achieved");
            if (ac instanceof List<?> l) {
                for (Object o : l) if (o instanceof String s4) ACHIEVED.add(s4);
            }
            Object dc = m.get("declined");
            if (dc instanceof List<?> l) {
                for (Object o : l) if (o instanceof String s5) DECLINED.add(s5);
            }
            Object seen = m.get("seen");
            if (seen instanceof List<?> l) {
                for (Object o : l) if (o instanceof String s2) SEEN.add(s2);
            }
            Object dir = m.get("directions");
            if (dir instanceof List<?> l) {
                for (Object o : l) if (o instanceof String s) DIRECTIONS.add(s);
            }
            Object st = m.get("standing");
            if (st instanceof List<?> l) {
                for (Object o : l) if (o instanceof String s) STANDING.add(s);
            }
            Object pages = m.get("pages");
            if (pages instanceof List<?> l) {
                for (Object o : l) {
                    if (o instanceof Map<?, ?>) {
                        PAGES.add(new Page(
                                JsonParse.num(o, "n", PAGES.size() + 1),
                                JsonParse.str(o, "title", ""),
                                JsonParse.str(o, "text", ""),
                                JsonParse.str(o, "stamp", "")));
                    }
                }
            }
            Config.log("campaign: loaded " + PAGES.size() + " page(s), "
                    + CANON.size() + " canon entries from " + root());
        } catch (Throwable t) {
            // A corrupt store must not stop the mod. Better a fresh book than
            // no book, and the old file stays on disk to be recovered by hand.
            Config.log("campaign: could not read the store (" + t + ") - starting fresh");
        }
    }

    private static synchronized void save() {
        try {
            Files.createDirectories(root());
            Json j = new Json().obj();
            j.put("schema", 1);
            j.arrKey("canon");
            for (String c : CANON) j.val(c);
            j.endArr();
            j.put("opening", OPENING);
            j.put("scenario", SCENARIO);
            j.put("premise", PREMISE);
            j.put("lastState", LAST_STATE);
            j.arrKey("todo");
            for (String t : TODO) j.val(t);
            j.endArr();
            j.arrKey("achieved");
            for (String t : ACHIEVED) j.val(t);
            j.endArr();
            j.arrKey("declined");
            for (String t : DECLINED) j.val(t);
            j.endArr();
            j.arrKey("seen");
            for (String c : SEEN) j.val(c);
            j.endArr();
            j.arrKey("directions");
            for (String c : DIRECTIONS) j.val(c);
            j.endArr();
            j.arrKey("standing");
            for (String c : STANDING) j.val(c);
            j.endArr();
            j.arrKey("pages");
            for (Page p : PAGES) {
                j.obj();
                j.put("n", p.number);
                j.put("title", p.title);
                j.put("text", p.text);
                j.put("stamp", p.stamp);
                j.endObj();
            }
            j.endArr();
            j.endObj();

            // Atomic: a page half-written by a crash would poison every later
            // prompt, and the player would never know why the story drifted.
            Path tmp = root().resolve("campaign.json.tmp");
            Files.write(tmp, j.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, root().resolve("campaign.json"),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable t) {
            Config.log("campaign: SAVE FAILED - " + t);
        }
    }

    // ----------------------------------------------------------------- pages

    public static synchronized int pageCount() {
        load();
        return PAGES.size();
    }

    public static synchronized Page page(int oneBased) {
        load();
        if (oneBased < 1 || oneBased > PAGES.size()) return null;
        return PAGES.get(oneBased - 1);
    }

    public static synchronized void addPage(String title, String text, String stamp) {
        load();
        if (!addPageInMemory(title, text, stamp)) return;
        save();
        Config.log("campaign: page " + PAGES.size() + " kept (" + text.length() + " chars)");
    }

    private static boolean addPageInMemory(String title, String text, String stamp) {
        if (text == null || text.isBlank()) return false;
        // A page with no title shows as "NO PAGE" on the device and as a blank
        // line in the archive, which reads like the page itself failed. The
        // prompt asks for one; this makes sure the book always has something
        // to put in the index when it does not arrive.
        String t = title == null ? "" : title.trim();
        if (t.isEmpty()) t = "Page " + (PAGES.size() + 1);
        PAGES.add(new Page(PAGES.size() + 1, t, text.trim(),
                stamp == null ? "" : stamp));
        return true;
    }

    public static synchronized void addCanon(List<String> entries) {
        if (entries == null || entries.isEmpty()) return;
        load();
        if (addCanonInMemory(entries)) save();
    }

    private static boolean addCanonInMemory(List<String> entries) {
        if (entries == null || entries.isEmpty()) return false;
        int before = CANON.size();
        for (String e : entries) {
            if (e == null) continue;
            String s = e.trim();
            // A canon file is where an invented door would quietly become
            // permanent, so keep entries short and drop anything empty.
            if (s.length() > 2 && s.length() <= 300) CANON.add(s);
        }
        return CANON.size() != before;
    }

    public static synchronized List<String> canon() {
        load();
        return new ArrayList<>(CANON);
    }

    // ----------------------------------------------------------- opening

    private static String OPENING = "";
    private static String SCENARIO = "";
    private static String PREMISE = "";
    private static String LAST_STATE = "";

    /**
     * The to-do list.
     *
     * Two sources, one list: the player writes items, and the narrator
     * proposes one at the end of a page. That is what makes direction possible
     * without orders - a proposal sits on a list the player owns and can strike
     * out, where an instruction in the prose could only be obeyed or ignored.
     *
     * Stored as "d|s|text": done flag, source, then the text.
     */
    private static final List<String> TODO = new ArrayList<>();

    /**
     * What the list has taught us.
     *
     * Ticking an item off says it mattered; striking it out says it never did.
     * Both are the player telling us something about the story they want, and
     * throwing either away would waste the clearest preference signal in the
     * whole mod.
     */
    private static final List<String> ACHIEVED = new ArrayList<>();
    private static final List<String> DECLINED = new ArrayList<>();

    /**
     * The new-game handshake: who he was and where he woke, captured ONCE
     * at the first page and never rebuilt. Later pages can drift a long
     * way from the start; this is the anchor that keeps the story about
     * the same man.
     */
    public static synchronized void openIfNew(String text) {
        load();
        if (!OPENING.isEmpty() || text == null || text.isBlank()) return;
        OPENING = text.strip();
        save();
        Config.log("campaign: opening recorded");
    }

    /** The chosen kind of story, or null until the player picks one. */
    public static synchronized Scenario scenario() {
        load();
        return Scenario.byId(SCENARIO);
    }

    public static synchronized boolean hasScenario() {
        load();
        return Scenario.byId(SCENARIO) != null;
    }

    /**
     * Set once at the start of a campaign. Changing it mid-story would leave
     * the pages already written pulling one way and every later page another,
     * so it is deliberately a one-time choice.
     */
    public static synchronized boolean setScenario(String id) {
        load();
        Scenario sc = Scenario.byId(id);
        if (sc == null) return false;
        SCENARIO = id;

        // Seed the list. An empty checklist on the first screen tells the
        // player nothing about what kind of story they just chose, and waiting
        // for the narrator to propose one item per page means the list is bare
        // for the whole first session. These are not orders - they are the
        // obvious opening wants of someone in THIS story, and the player owns
        // every one of them: tick it, or strike it out and we learn something.
        for (String s : sc.opening) addTodo(s, "story");
        for (String s : Scenario.FIRST_DAYS) addTodo(s, "story");

        save();
        Config.log("campaign: story kind set to " + id
                + " (" + TODO.size() + " opening items)");
        return true;
    }

    /**
     * Why this survivor is living this story. Written by the model on the first
     * page and then FIXED - it is the one thing a campaign must never drift
     * away from, so it leads the cached prefix and is never rewritten.
     */
    public static synchronized void setPremise(String text) {
        load();
        if (!PREMISE.isEmpty() || text == null || text.isBlank()) return;
        PREMISE = text.strip();
        save();
        Config.log("campaign: premise fixed (" + PREMISE.length() + " chars)");
    }

    /** The snapshot as it stood when the last page was written. */
    public static synchronized String lastState() {
        load();
        return LAST_STATE;
    }

    public static synchronized void rememberState(String snapshot) {
        load();
        LAST_STATE = Delta.keep(snapshot);
        save();
    }

    public static synchronized String premise() {
        load();
        return PREMISE;
    }

    public static synchronized boolean hasOpening() {
        load();
        return !OPENING.isEmpty();
    }

    // -------------------------------------------------------- places seen

    private static final LinkedHashSet<String> SEEN = new LinkedHashSet<>();

    /**
     * Records a room the character has laid eyes on.
     *
     * Driven by the game's own OnSeeNewRoom event, so it is literally "what he
     * has seen" rather than what the engine has loaded. This is the second
     * half of Elkin's rule: a glance at the room he is in, plus memory of the
     * rooms he has already walked through.
     */
    public static synchronized void sawRoom(String room, String building) {
        if (room == null || room.isBlank()) return;
        load();
        String key = building == null || building.isBlank()
                ? room.trim()
                : room.trim() + " (" + building.trim() + ")";
        // Bounded: a long campaign should not grow this without limit.
        if (SEEN.size() > 400) return;
        if (SEEN.add(key)) save();
    }

    public static synchronized String seenForPrompt() {
        load();
        // Memory of past rooms is the top notch of the player's dial.
        if (Settings.knowledge() < Settings.KNOW_MEMORY) return "";
        if (SEEN.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(1024);
        sb.append("### PLACES THEY HAVE SEEN\n");
        sb.append("Rooms the survivor has already walked through. They may ");
        sb.append("remember these; ");
        sb.append("anywhere not listed they have never laid eyes on.\n\n");
        for (String s : SEEN) sb.append("- ").append(s).append('\n');
        sb.append('\n');
        return sb.toString();
    }

    // ------------------------------------------------------------- to-do

    // Row format: "<state>|<source>|<text>".
    //
    // A list needs THREE answers, not two. "Done" says it mattered and
    // "struck out" says never again, but the most common thing a player
    // actually thinks is "good idea, not now" - and with only two gestures
    // that thought had nowhere to go. Deferring is not refusing: a shelved
    // item stays in the book, stops counting against the proposal cap, and
    // can be pulled back the moment it becomes relevant.
    static final int OPEN = 0, DONE = 1, LATER = 2;

    private static String enc(int state, String src, String text) {
        return state + "|" + src + "|" + text;
    }

    private static int stateOf(String row) {
        if (row.startsWith("1|")) return DONE;
        if (row.startsWith("2|")) return LATER;
        return OPEN;
    }

    private static boolean isDone(String row)  { return stateOf(row) == DONE; }
    private static boolean isLater(String row) { return stateOf(row) == LATER; }

    private static String srcOf(String row) {
        int a = row.indexOf('|'), b = row.indexOf('|', a + 1);
        return (a < 0 || b < 0) ? "player" : row.substring(a + 1, b);
    }

    private static String textOf(String row) {
        int a = row.indexOf('|'), b = row.indexOf('|', a + 1);
        return b < 0 ? row : row.substring(b + 1);
    }

    public static synchronized boolean addTodo(String text, String source) {
        load();
        if (!addTodoInMemory(text, source)) return false;
        save();
        return true;
    }

    private static boolean addTodoInMemory(String text, String source) {
        if (text == null) return false;
        String t = text.strip();
        if (t.isEmpty() || t.length() > 160) return false;

        // The narrator proposed one item on every page regardless of being
        // told most pages should propose none, and a list that grows faster
        // than it is worked through stops being a list. The prompt asks; this
        // decides. A hard ceiling on OPEN story-proposed items, with the
        // player's own additions never blocked.
        if ("story".equals(source)) {
            int openStory = 0;
            for (String row : TODO) {
                if (stateOf(row) == OPEN && "story".equals(srcOf(row))) openStory++;
            }
            if (openStory >= 8) {
                // Silent rejection looked like the narrator had stopped
                // proposing anything. Say so, or the next hour is spent
                // debugging a prompt that is working perfectly.
                Config.log("campaign: story to-do refused, " + openStory
                        + " already open and unworked - \"" + t + "\"");
                return false;
            }
        }
        // The narrator proposes the same want on consecutive pages; that should
        // not stack up three identical lines.
        for (String row : TODO) {
            if (textOf(row).equalsIgnoreCase(t)) return false;
        }
        if (TODO.size() >= 40) return false;
        TODO.add(enc(OPEN, source == null ? "player" : source, t));
        return true;
    }

    /**
     * Commits every consequence of one successful model response as a single
     * campaign transaction.
     *
     * The expected generation is checked while this class' monitor is held.
     * reset() uses the same monitor, so either this method finishes against the
     * old save before reset begins, or reset wins and this method changes
     * nothing. There is no check-then-act window and no partially committed
     * page for a successor request to observe.
     */
    public static synchronized boolean commitGeneratedPage(
            long expectedGeneration,
            String premise,
            String title,
            String text,
            String stamp,
            List<String> canon,
            String todo,
            String state) {
        if (GENERATION.get() != expectedGeneration) {
            Config.log("campaign: dropping completed page for stale generation "
                    + expectedGeneration + " (current " + GENERATION.get() + ")");
            return false;
        }
        load();
        if (text == null || text.isBlank()) return false;

        if (PREMISE.isEmpty() && premise != null && !premise.isBlank()) {
            PREMISE = premise.strip();
        }
        addPageInMemory(title, text, stamp);
        addCanonInMemory(canon);
        addTodoInMemory(todo, "story");
        DIRECTIONS.clear();
        LAST_STATE = Delta.keep(state);
        save();

        Config.log("campaign: page " + PAGES.size() + " committed atomically ("
                + text.length() + " chars, generation " + expectedGeneration + ")");
        return true;
    }

    public static synchronized void toggleTodo(int oneBased) {
        load();
        if (oneBased < 1 || oneBased > TODO.size()) return;
        String row = TODO.get(oneBased - 1);
        // Ticking a deferred item brings it straight back as done - the player
        // did the thing they had shelved, which is the happy path.
        TODO.set(oneBased - 1, enc(isDone(row) ? OPEN : DONE, srcOf(row), textOf(row)));
        save();
    }

    /** Ticked items graduate to the record of what she has actually done. */
    public static synchronized void clearDoneTodo() {
        load();
        boolean any = false;
        for (String row : new ArrayList<>(TODO)) {
            if (!isDone(row)) continue;
            remember(ACHIEVED, textOf(row));
            TODO.remove(row);
            any = true;
        }
        if (any) save();
    }

    /**
     * Shelved. A good idea, but not now.
     *
     * The third answer. Unlike a strike-out this teaches the narrator nothing
     * about what the player dislikes - it only says "not this hour". The item
     * stays in the book, stops counting against the proposal cap so the story
     * can offer something else, and comes back the moment it is tapped again.
     */
    public static synchronized void laterTodo(int oneBased) {
        load();
        if (oneBased < 1 || oneBased > TODO.size()) return;
        String row = TODO.get(oneBased - 1);
        int now = stateOf(row) == LATER ? OPEN : LATER;
        TODO.set(oneBased - 1, enc(now, srcOf(row), textOf(row)));
        save();
    }

    /**
     * Struck out without being done. Remembered as a refusal, so the narrator
     * stops offering this and learns the shape of what she will not do.
     */
    public static synchronized void dropTodo(int oneBased) {
        load();
        if (oneBased < 1 || oneBased > TODO.size()) return;
        String row = TODO.remove(oneBased - 1);
        if (isDone(row)) remember(ACHIEVED, textOf(row));
        else remember(DECLINED, textOf(row));
        save();
    }

    private static void remember(List<String> into, String text) {
        if (text == null || text.isBlank()) return;
        for (String t : into) if (t.equalsIgnoreCase(text)) return;
        into.add(text);
        // Bounded, oldest first: the recent refusals are the informative ones.
        while (into.size() > 40) into.remove(0);
    }

    public static synchronized String todoJson() {
        load();
        Json j = new Json().obj();
        j.arrKey("todo");
        for (String row : TODO) {
            j.obj();
            j.put("done", isDone(row));
            j.put("later", isLater(row));
            j.put("source", srcOf(row));
            j.put("text", textOf(row));
            j.endObj();
        }
        j.endArr();
        return j.endObj().toString();
    }

    /** The list, and everything the list has taught us. */
    public static synchronized String todoForPrompt() {
        load();
        StringBuilder open = new StringBuilder(), done = new StringBuilder(),
                      later = new StringBuilder();
        for (String row : TODO) {
            StringBuilder into = isDone(row) ? done : isLater(row) ? later : open;
            into.append("- ").append(textOf(row)).append('\n');
        }
        if (open.length() == 0 && done.length() == 0 && later.length() == 0
                && ACHIEVED.isEmpty() && DECLINED.isEmpty()) return "";

        StringBuilder sb = new StringBuilder(1024);
        sb.append("### THEIR LIST\n");
        sb.append("The survivor's own to-do list on the device. Intentions "
                + "they have already "
                + "formed - things they are carrying around, not tasks you "
                + "assign.\n\n");
        if (open.length() > 0) sb.append("Still open:\n").append(open).append('\n');
        if (done.length() > 0) sb.append("Ticked off, not yet cleared:\n").append(done).append('\n');
        if (later.length() > 0) {
            // NOT a refusal. The difference matters: a struck item must never
            // be raised again, a shelved one is simply waiting for its moment.
            sb.append("SHELVED FOR NOW. Good ideas the player has put off - "
                    + "not refused, just not this hour. Do not steer toward "
                    + "these and never end a page on one. But they are still "
                    + "true about what this person means to do eventually, and "
                    + "if the world puts one of them right in front of them, "
                    + "the page may notice:\n").append(later).append('\n');
        }

        if (!ACHIEVED.isEmpty()) {
            sb.append("Done and behind them. They may look back on these with "
                    + "some satisfaction; never raise them as open again:\n");
            for (String t : ACHIEVED) sb.append("- ").append(t).append('\n');
            sb.append('\n');
        }
        if (!DECLINED.isEmpty()) {
            // The strongest preference signal the player ever gives us.
            sb.append("STRUCK OFF WITHOUT DOING. The player decided against these. Do "
                    + "not propose them again, and take the hint about the KIND "
                    + "of thing they do not want from this story - if they have "
                    + "struck off three errands in a row, stop offering "
                    + "errands:\n");
            for (String t : DECLINED) sb.append("- ").append(t).append('\n');
            sb.append('\n');
        }
        return sb.toString();
    }

    // ----------------------------------------------------------- the notes

    /**
     * Files a player note by type. The type decides its LIFETIME, which is the
     * whole point of asking the player to choose one.
     *
     * @return a short confirmation for the device screen.
     */
    public static synchronized String addNote(String type, String text) {
        load();
        if (text == null) return "nothing written";
        String s = text.strip();
        if (s.isEmpty()) return "nothing written";
        if (s.length() > 500) s = s.substring(0, 500);

        switch (type == null ? "" : type) {
            case "observation" -> {
                // Permanent. Marked so a later page can tell the player's
                // colour apart from the narrator's own inventions.
                addCanon(List.of("(the player observes) " + s));
                return "kept as canon";
            }
            case "direction" -> {
                DIRECTIONS.add(s);
                save();
                return "will steer the next page";
            }
            case "standing" -> {
                STANDING.add(s);
                save();
                return "in force until you remove it";
            }
            default -> {
                return "unknown note type";
            }
        }
    }

    public static synchronized List<String> directions() {
        load();
        return new ArrayList<>(DIRECTIONS);
    }

    /** Called only after a page is successfully written: directions are spent. */
    public static synchronized void clearDirections() {
        if (DIRECTIONS.isEmpty()) return;
        DIRECTIONS.clear();
        save();
    }

    public static synchronized List<String> standing() {
        load();
        return new ArrayList<>(STANDING);
    }

    public static synchronized boolean removeStanding(int oneBased) {
        load();
        List<String> l = new ArrayList<>(STANDING);
        if (oneBased < 1 || oneBased > l.size()) return false;
        STANDING.remove(l.get(oneBased - 1));
        save();
        return true;
    }

    /**
     * The player's voice, as the prompt sees it.
     *
     * Standing preferences are restated EVERY time rather than remembered from
     * an earlier page: "don't hurry me" honoured once and then forgotten is
     * worse than not having the feature at all.
     */
    public static synchronized String notesForPrompt() {
        load();
        if (STANDING.isEmpty() && DIRECTIONS.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(1024);
        if (!STANDING.isEmpty()) {
            sb.append("Standing instructions, in force until the player changes them:\n");
            for (String s : STANDING) sb.append("- ").append(s).append('\n');
            sb.append('\n');
        }
        if (!DIRECTIONS.isEmpty()) {
            sb.append("For this page specifically:\n");
            for (String s : DIRECTIONS) sb.append("- ").append(s).append('\n');
        }
        return sb.toString();
    }

    public static synchronized String notesJson() {
        load();
        Json j = new Json().obj();
        j.arrKey("standing");
        for (String s : STANDING) j.val(s);
        j.endArr();
        j.arrKey("directions");
        for (String s : DIRECTIONS) j.val(s);
        j.endArr();
        return j.endObj().toString();
    }

    /**
     * The part of a campaign that never changes: what kind of story it is, why
     * she is doing it, and who she was at the start. Stable for the life of
     * the save, so it belongs in the cached system block rather than in the
     * history, which grows.
     */
    public static synchronized String fixedSpine() {
        load();
        StringBuilder sb = new StringBuilder(4096);
        Scenario sc = Scenario.byId(SCENARIO);
        if (sc != null) sb.append(sc.spine).append("\n\n");
        if (!OPENING.isEmpty()) {
            sb.append("### HOW THIS BEGAN\n").append(OPENING).append("\n\n");
        }
        if (!PREMISE.isEmpty()) {
            sb.append("### WHY THEY ARE DOING THIS\n");
            sb.append("Fixed at the start of this campaign. It does not change, ");
            sb.append("and no page may contradict it.\n\n");
            sb.append(PREMISE).append("\n\n");
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- prompt

    /**
     * Everything written so far, for the prompt.
     *
     * A 300-word page is about 400 tokens, so thirty chapters is ~12k and even
     * three hundred is ~120k. Every profile now declares an input budget: paid
     * input and model context are finite even when the model is hosted. Within
     * that budget we retain the newest pages; recent events matter more to the
     * next page than the first day does, while canon carries durable facts.
     *
     * @param charBudget approximate characters allowed, or <= 0 for unlimited.
     */
    public static synchronized String history(int charBudget) {
        load();
        if (PAGES.isEmpty() && CANON.isEmpty() && SEEN.isEmpty()
                && OPENING.isEmpty() && SCENARIO.isEmpty() && PREMISE.isEmpty()) return "";

        // The scenario spine, the premise and the opening are NOT repeated
        // here. They live in fixedSpine(), inside the cached system block, and
        // emitting them again cost a full copy on every single request - with
        // the two copies disagreeing about the survivor's pronoun, which is
        // the loudest possible way to make the model doubt the one it was
        // given. This block is the part of the campaign that GROWS.
        StringBuilder sb = new StringBuilder(8192);
        sb.append(seenForPrompt());
        if (!CANON.isEmpty()) {
            sb.append("### CANON SO FAR\n");
            sb.append("Established facts of this story. Stay consistent with them.\n\n");
            for (String c : CANON) sb.append("- ").append(c).append('\n');
            sb.append('\n');
        }

        int first = 0;
        if (charBudget > 0) {
            int total = 0;
            for (int i = PAGES.size() - 1; i >= 0; i--) {
                total += PAGES.get(i).text.length() + 64;
                if (total > charBudget) { first = i + 1; break; }
            }
        }

        if (first < PAGES.size()) {
            sb.append("### THE STORY SO FAR\n");
            if (first > 0) {
                sb.append("(earlier pages omitted for length; the canon above still holds)\n");
            }
            sb.append('\n');
            for (int i = first; i < PAGES.size(); i++) {
                Page p = PAGES.get(i);
                sb.append("-- Page ").append(p.number);
                if (!p.title.isEmpty()) sb.append(": ").append(p.title);
                if (!p.stamp.isEmpty()) sb.append("  (").append(p.stamp).append(')');
                sb.append('\n').append(p.text).append("\n\n");
            }
        }
        String out = sb.toString();
        if (charBudget > 0 && out.length() > charBudget) {
            String marker = "(older history omitted to respect the profile's input limit)\n\n";
            if (charBudget <= marker.length()) return "";
            // The end contains the newest pages. Canon may be clipped here
            // only when it alone has grown beyond the entire request budget;
            // retaining recent events is safer than rejecting every future
            // page with an oversized prompt.
            return marker + out.substring(out.length() - (charBudget - marker.length()));
        }
        return out;
    }

    /** Archive listing for the book's page selector. */
    public static synchronized String indexJson() {
        load();
        Json j = new Json().obj();
        j.put("count", PAGES.size());
        j.arrKey("pages");
        for (Page p : PAGES) {
            j.obj();
            j.put("n", p.number);
            j.put("title", p.title);
            j.put("stamp", p.stamp);
            j.endObj();
        }
        j.endArr();
        return j.endObj().toString();
    }
}
