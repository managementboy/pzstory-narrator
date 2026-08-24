package de.fricke.pzstory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    private static final int CURRENT_SCHEMA = 7;
    private static final int MAX_CAMPAIGN_BYTES = 32 * 1024 * 1024;
    private static final int MAX_LAST_STATE_CHARS = 1024 * 1024;
    private static final int MAX_PAGES_ON_DISK = 5000;
    private static final int MAX_CANON = 2000;
    private static final int MAX_SEEN = 400;
    private static final int MAX_DIRECTIONS = 20;
    private static final int MAX_STANDING = 20;
    private static final long EVENT_CHECKPOINT_NANOS = 60_000_000_000L;

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

    /**
     * The note text and the exact number of one-shot directions included in a
     * prompt.  Directions added after this capture belong to the next page and
     * must not be consumed by an older request finishing in the background.
     */
    public static final class PromptNotes {
        public final String text;
        public final int directionCount;

        PromptNotes(String text, int directionCount) {
            this.text = text;
            this.directionCount = directionCount;
        }
    }

    private static Path root;
    private static final List<Page> PAGES = new ArrayList<>();
    private static final LinkedHashSet<String> CANON = new LinkedHashSet<>();
    /** Schema-3 authority; CANON remains a compatibility projection. */
    private static final FactMemory FACTS = new FactMemory();
    private static final ThreadMemory THREADS = new ThreadMemory();
    private static final ContinuityMemory CONTINUITY = new ContinuityMemory();
    private static final EventJournal EVENTS = new EventJournal();
    private static final WorldMemory MEMORY = new WorldMemory();
    private static final DirectorBible DIRECTOR = new DirectorBible();
    private static String MODE = "chronicler";

    // The player note channel. Three types, three lifetimes - getting the
    // lifetime wrong is the whole failure mode, so they are stored apart
    // rather than tagged in one list.
    //   observation -> folded straight into CANON, permanent
    //   direction   -> queued here, spent on the next page
    //   standing    -> in force until the player removes it
    private static final List<String> DIRECTIONS = new ArrayList<>();
    private static final LinkedHashSet<String> STANDING = new LinkedHashSet<>();

    private static boolean loaded = false;
    /** True only when a corrupt store could not be backed up safely. */
    private static boolean persistenceBlocked = false;
    private static boolean backupRecoveryAttempted = false;
    private static boolean eventDirty = false;
    private static long nextEventCheckpointNanos = 0;

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
        FACTS.clear();
        THREADS.clear();
        CONTINUITY.clear();
        DIRECTIONS.clear();
        STANDING.clear();
        SEEN.clear();
        EVENTS.clear();
        MEMORY.clear();
        DIRECTOR.clear();
        MODE = "chronicler";
        OPENING = "";
        SCENARIO = "";
        PREMISE = "";
        LAST_STATE = "";
        OBSERVED_STATE = "";
        TODO.clear();
        ACHIEVED.clear();
        DECLINED.clear();
        loaded = false;
        persistenceBlocked = false;
        backupRecoveryAttempted = false;
        eventDirty = false;
        nextEventCheckpointNanos = 0;
    }

    // ------------------------------------------------------------------ load

    public static synchronized void load() {
        if (loaded) return;
        Path p = root().resolve("campaign.json");
        try {
            if (!Files.isRegularFile(p)) {
                loaded = true;
                Config.log("campaign: new book at " + root());
                return;
            }
            Map<String, Object> m = JsonParse.parseObject(
                    BoundedFiles.readUtf8(p, MAX_CAMPAIGN_BYTES));
            int schema = schema(m.get("schema"));
            if (schema < 1 || schema > CURRENT_SCHEMA) {
                throw new IllegalStateException("unsupported campaign schema " + schema);
            }

            LinkedHashSet<String> nextCanon = new LinkedHashSet<>(
                    stringList(m.get("canon"), "canon", 10000, 300));
            FactMemory nextFacts = new FactMemory();
            if (schema >= 3) {
                nextFacts.load(m.get("factMemory"));
                nextCanon.clear();
                nextCanon.addAll(nextFacts.activeText());
            } else {
                for (String legacy : nextCanon) {
                    String playerPrefix = "(the player observes) ";
                    boolean player = legacy.startsWith(playerPrefix);
                    String text = player ? legacy.substring(playerPrefix.length()) : legacy;
                    nextFacts.add(text, "knowledge", player ? "player" : "legacy",
                            player ? 95 : 40, 0);
                }
            }
            ThreadMemory nextThreads = new ThreadMemory();
            if (schema >= 4) nextThreads.load(m.get("threadMemory"));
            ContinuityMemory nextContinuity = new ContinuityMemory();
            if (schema >= 5) nextContinuity.load(m.get("continuityMemory"));
            String nextOpening = field(m, "opening", 32000);
            // Self-heal a campaign opened by 1.15.0/1.15.1, where the trait
            // list had become objects but the opening builder still called
            // String.valueOf() on them and wrote "He is {name=keen cook,
            // kind=..., means=...}" into a field that is FIXED for the life of
            // the book and re-read on every single page. Clearing it makes the
            // next page rebuild it correctly from the same character.
            if (nextOpening.contains("{name=") || nextOpening.contains("{name =")) {
                Config.log("campaign: opening was written by a broken build "
                        + "- clearing it so the next page rewrites it");
                nextOpening = "";
            }
            String nextScenario = field(m, "scenario", 64);
            String nextMode = schema >= 6 ? field(m, "mode", 16) : "chronicler";
            if (!nextMode.equals("chronicler") && !nextMode.equals("director"))
                throw new IllegalStateException("unknown campaign mode " + nextMode);
            DirectorBible nextDirector = new DirectorBible();
            if (schema >= 6) nextDirector.load(
                    m.get("directorBible"), Scenario.byId(nextScenario));
            if (nextMode.equals("chronicler") && nextDirector.frozen())
                throw new IllegalStateException("chronicler campaign has a director bible");
            String nextPremise = field(m, "premise", 32000);
            String nextLastState = field(m, "lastState", MAX_LAST_STATE_CHARS);
            String nextObservedState = schema >= 2
                    ? field(m, "observedState", MAX_LAST_STATE_CHARS)
                    : nextLastState;
            if (!nextObservedState.isEmpty() && Delta.keep(nextObservedState) == null) {
                throw new IllegalStateException("observedState is not valid JSON state");
            }
            EventJournal nextEvents = new EventJournal();
            WorldMemory nextMemory = new WorldMemory();
            if (schema >= 2) {
                nextEvents.load(m.get("eventJournal"));
                nextMemory.load(m.get("worldMemory"));
            } else if (!nextObservedState.isEmpty()) {
                // Migration is best-effort: a 1.x continuity checkpoint was
                // never required to contain a position, and the book itself
                // must not be rejected merely because no place can be derived.
                try { nextMemory.observe(nextObservedState, ""); }
                catch (Throwable ignored) { nextMemory.clear(); }
            }
            List<String> nextTodo = stringList(m.get("todo"), "todo", 100, 512);
            List<String> nextAchieved = stringList(
                    m.get("achieved"), "achieved", 100, 512);
            List<String> nextDeclined = stringList(
                    m.get("declined"), "declined", 100, 512);
            LinkedHashSet<String> nextSeen = new LinkedHashSet<>(
                    stringList(m.get("seen"), "seen", 1000, 512));
            List<String> nextDirections = stringList(
                    m.get("directions"), "directions", 100, 500);
            LinkedHashSet<String> nextStanding = new LinkedHashSet<>(
                    stringList(m.get("standing"), "standing", 100, 500));

            List<Page> nextPages = new ArrayList<>();
            Object pages = m.get("pages");
            if (pages instanceof List<?> l) {
                if (l.size() > MAX_PAGES_ON_DISK) {
                    throw new IllegalStateException("pages has more than "
                            + MAX_PAGES_ON_DISK + " entries");
                }
                for (Object o : l) {
                    if (!(o instanceof Map<?, ?>)) {
                        throw new IllegalStateException("pages contains a non-object entry");
                    }
                    nextPages.add(new Page(
                            JsonParse.num(o, "n", nextPages.size() + 1),
                            field(o, "title", 512),
                            field(o, "text", 128 * 1024),
                            field(o, "stamp", 128)));
                }
            }

            // Publish only after every field and collection passed validation.
            PAGES.clear(); PAGES.addAll(nextPages);
            CANON.clear(); CANON.addAll(nextCanon);
            FACTS.restore(nextFacts.snapshot());
            THREADS.restore(nextThreads.snapshot());
            CONTINUITY.restore(nextContinuity.snapshot());
            DIRECTIONS.clear(); DIRECTIONS.addAll(nextDirections);
            STANDING.clear(); STANDING.addAll(nextStanding);
            SEEN.clear(); SEEN.addAll(nextSeen);
            EVENTS.restore(nextEvents.snapshot());
            MEMORY.restore(nextMemory.snapshot());
            DIRECTOR.restore(nextDirector.snapshot());
            MODE = nextMode;
            TODO.clear(); TODO.addAll(nextTodo);
            ACHIEVED.clear(); ACHIEVED.addAll(nextAchieved);
            DECLINED.clear(); DECLINED.addAll(nextDeclined);
            OPENING = nextOpening;
            SCENARIO = nextScenario;
            PREMISE = nextPremise;
            LAST_STATE = nextLastState;
            OBSERVED_STATE = nextObservedState;
            trimOldest(CANON, MAX_CANON);
            trimOldest(SEEN, MAX_SEEN);
            trimOldest(DIRECTIONS, MAX_DIRECTIONS);
            trimOldest(STANDING, MAX_STANDING);
            loaded = true;
            persistenceBlocked = false;
            eventDirty = false;
            nextEventCheckpointNanos = System.nanoTime() + EVENT_CHECKPOINT_NANOS;
            Config.log("campaign: loaded " + PAGES.size() + " page(s), "
                    + CANON.size() + " canon entries, " + EVENTS.pendingCount()
                    + " pending event(s) from " + root()
                    + (schema < CURRENT_SCHEMA ? " (migrating schema " + schema
                            + " -> " + CURRENT_SCHEMA + ")" : ""));
        } catch (Throwable t) {
            clearLoadedData();
            loaded = true;
            Path corruptCopy = p.resolveSibling(
                    "campaign.json.corrupt-" + System.currentTimeMillis());
            boolean preserved = false;
            try {
                Files.copy(p, corruptCopy);
                preserved = true;
                Config.log("campaign: preserved unreadable store as " + corruptCopy);
            } catch (Throwable copyFailure) {
                // Do not overwrite the only remaining copy on the next save.
                persistenceBlocked = true;
                Config.log("campaign: could not preserve unreadable store ("
                        + copyFailure + "); persistence is blocked for this session");
            }
            Path lastGood = p.resolveSibling("campaign.json.bak");
            if (preserved && !backupRecoveryAttempted && Files.isRegularFile(lastGood)) {
                backupRecoveryAttempted = true;
                try {
                    AtomicFiles.writeUtf8(p,
                            BoundedFiles.readUtf8(lastGood, MAX_CAMPAIGN_BYTES));
                    loaded = false;
                    persistenceBlocked = false;
                    Config.log("campaign: attempting recovery from campaign.json.bak");
                    load();
                    return;
                } catch (Throwable recoveryFailure) {
                    Config.log("campaign: backup recovery failed (" + recoveryFailure + ")");
                }
            }
            Config.log("campaign: could not read the store (" + t
                    + ") - starting fresh in memory");
        }
    }

    private static void clearLoadedData() {
        PAGES.clear();
        CANON.clear();
        FACTS.clear();
        THREADS.clear();
        CONTINUITY.clear();
        DIRECTIONS.clear();
        STANDING.clear();
        SEEN.clear();
        EVENTS.clear();
        MEMORY.clear();
        DIRECTOR.clear();
        MODE = "chronicler";
        TODO.clear();
        ACHIEVED.clear();
        DECLINED.clear();
        OPENING = "";
        SCENARIO = "";
        PREMISE = "";
        LAST_STATE = "";
        OBSERVED_STATE = "";
        eventDirty = false;
        nextEventCheckpointNanos = 0;
    }

    private static String field(Object object, String key, int maxChars) {
        String value = JsonParse.str(object, key, "");
        if (value.length() > maxChars) {
            throw new IllegalStateException(key + " exceeds " + maxChars + " characters");
        }
        return value;
    }

    private static int schema(Object value) {
        if (value == null) return 1;
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("campaign schema is not a number");
        }
        double raw = number.doubleValue();
        if (!Double.isFinite(raw) || raw != Math.rint(raw)
                || raw < Integer.MIN_VALUE || raw > Integer.MAX_VALUE) {
            throw new IllegalStateException("campaign schema is not an integer");
        }
        return number.intValue();
    }

    private static List<String> stringList(
            Object value, String field, int maxEntries, int maxChars) {
        List<String> out = new ArrayList<>();
        if (value == null) return out;
        if (!(value instanceof List<?> list)) {
            throw new IllegalStateException(field + " is not an array");
        }
        if (list.size() > maxEntries) {
            throw new IllegalStateException(field + " has more than "
                    + maxEntries + " entries");
        }
        for (Object entry : list) {
            if (!(entry instanceof String text)) {
                throw new IllegalStateException(field + " contains a non-string entry");
            }
            if (text.length() > maxChars) {
                throw new IllegalStateException(field + " entry exceeds "
                        + maxChars + " characters");
            }
            out.add(text);
        }
        return out;
    }

    private static void trimOldest(java.util.Collection<?> values, int limit) {
        while (values.size() > limit) {
            java.util.Iterator<?> iterator = values.iterator();
            iterator.next();
            iterator.remove();
        }
    }

    private static synchronized boolean save() {
        if (persistenceBlocked) {
            Config.log("campaign: SAVE REFUSED - unreadable store was not preserved");
            return false;
        }
        try {
            Json j = new Json().obj();
            j.put("schema", CURRENT_SCHEMA);
            j.arrKey("canon");
            for (String c : FACTS.activeText()) j.val(c);
            j.endArr();
            FACTS.write(j);
            THREADS.write(j);
            CONTINUITY.write(j);
            j.put("opening", OPENING);
            j.put("scenario", SCENARIO);
            j.put("mode", MODE);
            DIRECTOR.write(j);
            j.put("premise", PREMISE);
            j.put("lastState", LAST_STATE);
            j.put("observedState", OBSERVED_STATE);
            EVENTS.write(j);
            MEMORY.write(j);
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

            Path target = root().resolve("campaign.json");
            AtomicFiles.writeUtf8(target, j.toString(),
                    target.resolveSibling("campaign.json.bak"), MAX_CAMPAIGN_BYTES);
            eventDirty = false;
            nextEventCheckpointNanos = System.nanoTime() + EVENT_CHECKPOINT_NANOS;
            return true;
        } catch (Throwable t) {
            Config.log("campaign: SAVE FAILED - " + t);
            return false;
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
        if (!save()) {
            PAGES.remove(PAGES.size() - 1);
            return;
        }
        Config.log("campaign: page " + PAGES.size() + " kept (" + text.length() + " chars)");
    }

    private static boolean addPageInMemory(String title, String text, String stamp) {
        if (text == null || text.isBlank()) return false;
        if (text.length() > 128 * 1024) return false;
        if (PAGES.size() >= MAX_PAGES_ON_DISK) {
            Config.log("campaign: page refused - archive reached "
                    + MAX_PAGES_ON_DISK + " pages");
            return false;
        }
        // A page with no title shows as "NO PAGE" on the device and as a blank
        // line in the archive, which reads like the page itself failed. The
        // prompt asks for one; this makes sure the book always has something
        // to put in the index when it does not arrive.
        String t = title == null ? "" : title.trim();
        if (t.isEmpty()) t = "Page " + (PAGES.size() + 1);
        if (t.length() > 512 || (stamp != null && stamp.length() > 128)) return false;
        PAGES.add(new Page(PAGES.size() + 1, t, text.trim(),
                stamp == null ? "" : stamp));
        return true;
    }

    public static synchronized void addCanon(List<String> entries) {
        if (entries == null || entries.isEmpty()) return;
        load();
        LinkedHashSet<String> old = new LinkedHashSet<>(CANON);
        FactMemory.Snapshot oldFacts = FACTS.snapshot();
        ThreadMemory.Snapshot oldThreads = THREADS.snapshot();
        if (addCanonInMemory(entries) && !save()) {
            CANON.clear(); CANON.addAll(old);
            FACTS.restore(oldFacts);
            THREADS.restore(oldThreads);
        }
    }

    private static boolean addCanonInMemory(List<String> entries) {
        if (entries == null || entries.isEmpty()) return false;
        boolean changed = false;
        for (String e : entries) {
            if (e == null) continue;
            String s = e.trim();
            // A canon file is where an invented door would quietly become
            // permanent, so keep entries short and drop anything empty.
            if (s.length() > 2 && s.length() <= 300 && !CANON.contains(s)) {
                String prefix = "(the player observes) ";
                boolean player = s.startsWith(prefix);
                String factText = player ? s.substring(prefix.length()) : s;
                String source = player ? "player" : "narrator";
                if (ThreadMemory.looksLikeCommand(factText)
                        && !THREADS.apply(factText, source, PAGES.size())) {
                    Config.log("campaign: malformed or stale thread command refused");
                    continue;
                }
                if (CANON.size() >= MAX_CANON) {
                    java.util.Iterator<String> oldest = CANON.iterator();
                    if (oldest.hasNext()) { oldest.next(); oldest.remove(); changed = true; }
                }
                changed |= CANON.add(s);
                FACTS.add(factText, "knowledge", source,
                        player ? 95 : 55, PAGES.size());
            }
        }
        return changed;
    }

    public static synchronized List<String> canon() {
        load();
        return FACTS.activeText();
    }

    /** Local diagnostics for tomorrow's migration and contradiction testing. */
    public static synchronized String factMemoryJson() {
        load();
        return FACTS.json();
    }

    public static synchronized String threadMemoryJson() {
        load();
        return THREADS.json();
    }

    public static synchronized String continuityMemoryJson() {
        load();
        return CONTINUITY.json();
    }

    // ----------------------------------------------------------- opening

    private static String OPENING = "";
    private static String SCENARIO = "";
    private static String PREMISE = "";
    private static String LAST_STATE = "";
    /** Most recent local observation, independent of when a page was written. */
    private static String OBSERVED_STATE = "";

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
        if (text.length() > 32_000) return;
        String old = OPENING;
        OPENING = text.strip();
        if (!save()) {
            OPENING = old;
            return;
        }
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
        if (!SCENARIO.isEmpty()) return SCENARIO.equals(id);
        String oldScenario = SCENARIO;
        List<String> oldTodo = new ArrayList<>(TODO);
        SCENARIO = id;

        // Seed the list. An empty checklist on the first screen tells the
        // player nothing about what kind of story they just chose, and waiting
        // for the narrator to propose one item per page means the list is bare
        // for the whole first session. These are not orders - they are the
        // obvious opening wants of someone in THIS story, and the player owns
        // every one of them: tick it, or strike it out and we learn something.
        for (String s : sc.opening) addTodoInMemory(s, "story");
        for (String s : Scenario.FIRST_DAYS) addTodoInMemory(s, "story");

        if (!save()) {
            SCENARIO = oldScenario;
            TODO.clear(); TODO.addAll(oldTodo);
            return false;
        }
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
        String old = PREMISE;
        PREMISE = text.strip();
        if (PREMISE.length() > 4 * 1024 || !save()) {
            PREMISE = old;
            return;
        }
        Config.log("campaign: premise fixed (" + PREMISE.length() + " chars)");
    }

    /** The snapshot as it stood when the last page was written. */
    public static synchronized String lastState() {
        load();
        return LAST_STATE;
    }

    public static synchronized void rememberState(String snapshot) {
        load();
        String old = LAST_STATE;
        String next = Delta.keep(snapshot);
        if (next == null || next.length() > MAX_LAST_STATE_CHARS) return;
        LAST_STATE = next;
        if (!save()) LAST_STATE = old;
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
        String roomName = bounded(room.trim(), 192);
        String buildingName = building == null ? "" : bounded(building.trim(), 48);
        String key = buildingName.isBlank()
                ? roomName
                : roomName + " (" + buildingName + ")";
        LinkedHashSet<String> old = new LinkedHashSet<>(SEEN);
        if (!SEEN.contains(key) && SEEN.size() >= MAX_SEEN) {
            java.util.Iterator<String> oldest = SEEN.iterator();
            if (oldest.hasNext()) { oldest.next(); oldest.remove(); }
        }
        if (SEEN.add(key) && !save()) {
            SEEN.clear(); SEEN.addAll(old);
        }
    }

    private static String bounded(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
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
        // The suffix is a LOCAL building id used to distinguish same-named
        // rooms. Older builds rendered it directly into the provider prompt.
        // Grouping preserves the important meaning without exporting an
        // engine identifier.
        LinkedHashMap<String, Integer> labels = new LinkedHashMap<>();
        for (String seen : SEEN) labels.merge(seenLabel(seen), 1, Integer::sum);
        for (Map.Entry<String, Integer> entry : labels.entrySet()) {
            sb.append("- ").append(entry.getKey());
            if (entry.getValue() > 1) {
                sb.append(" — more than one different room has this name");
            }
            sb.append('\n');
        }
        sb.append('\n');
        return sb.toString();
    }

    private static String seenLabel(String stored) {
        int open = stored.lastIndexOf(" (");
        if (open < 0 || !stored.endsWith(")")) return stored;
        String suffix = stored.substring(open + 2, stored.length() - 1);
        return suffix.matches("-?[0-9]+") ? stored.substring(0, open) : stored;
    }

    // ----------------------------------------------------------- 2.0 events

    /**
     * Observes a local snapshot and atomically checkpoints every event derived
     * from the interval plus the structured place memory.
     *
     * This method never sends anything over the network. Raw engine ids and
     * coordinates remain inside the save and are stripped from prompt output
     * by StoryEvent and WorldMemory projections.
     */
    public static synchronized boolean observeState(String state, String stamp) {
        return observeState(state, stamp, true);
    }

    /**
     * @param forceSave true before a provider request; false for background
     *                  samples, which batch low-value disk checkpoints.
     */
    public static synchronized boolean observeState(
            String state, String stamp, boolean forceSave) {
        load();
        String kept = Delta.keep(state);
        if (kept == null || kept.length() > MAX_LAST_STATE_CHARS) return false;

        String oldObserved = OBSERVED_STATE;
        EventJournal.Snapshot oldEvents = EVENTS.snapshot();
        WorldMemory.Snapshot oldMemory = MEMORY.snapshot();
        FactMemory.Snapshot oldFacts = FACTS.snapshot();
        ContinuityMemory.Snapshot oldContinuity = CONTINUITY.snapshot();
        DirectorBible.Snapshot oldDirector = DIRECTOR.snapshot();
        boolean oldDirty = eventDirty;
        long oldNextCheckpoint = nextEventCheckpointNanos;
        try {
            List<StoryEvent.Draft> detected = OBSERVED_STATE.isEmpty()
                    ? List.of()
                    : EventDetector.between(OBSERVED_STATE, kept, stamp);
            boolean memoryChanged = MEMORY.observe(kept, stamp);
            for (StoryEvent.Draft event : detected) {
                long id = EVENTS.record(event);
                if ("director".equals(MODE)) DIRECTOR.observe(id, event.type, event.summary);
            }
            boolean factsChanged = updateGameFacts(OBSERVED_STATE, kept,
                    detected, stamp);
            boolean firstObservation = OBSERVED_STATE.isEmpty();
            OBSERVED_STATE = kept;
            boolean materialChange = firstObservation || !detected.isEmpty()
                    || memoryChanged || factsChanged;
            if (materialChange) eventDirty = true;

            // A no-news sample updates the in-memory baseline. Low-value events
            // are batched so a long archive is not rewritten once per room at
            // five-second cadence. Decisive events are durable immediately,
            // and requestStoryPage always force-flushes before network work.
            boolean decisive = false;
            for (StoryEvent.Draft event : detected) {
                if (event.importance >= 75) { decisive = true; break; }
            }
            long now = System.nanoTime();
            boolean checkpointDue = eventDirty
                    && (nextEventCheckpointNanos == 0 || now >= nextEventCheckpointNanos);
            if (!forceSave && !decisive && !checkpointDue) return true;
            // A quiet sample still has to flush an earlier batched event once
            // its checkpoint comes due. Testing only materialChange here left
            // low-value events in memory forever until the next page request.
            if (!eventDirty) return true;
            if (save()) return true;
        } catch (Throwable t) {
            Config.log("campaign: event observation rejected - " + t);
        }

        OBSERVED_STATE = oldObserved;
        EVENTS.restore(oldEvents);
        MEMORY.restore(oldMemory);
        FACTS.restore(oldFacts);
        CONTINUITY.restore(oldContinuity);
        DIRECTOR.restore(oldDirector);
        eventDirty = oldDirty;
        nextEventCheckpointNanos = oldNextCheckpoint;
        return false;
    }

    public static synchronized String mode() { load(); return MODE; }

    /** One-time opt-in/out before page one. */
    public static synchronized boolean setMode(String mode) {
        load();
        if (!"chronicler".equals(mode) && !"director".equals(mode)) return false;
        if (!PAGES.isEmpty() || DIRECTOR.frozen()) return MODE.equals(mode);
        String old = MODE;
        MODE = mode;
        if (save()) return true;
        MODE = old;
        return false;
    }

    public static synchronized String directorStatusJson() {
        load();
        return DIRECTOR.statusJson(MODE);
    }

    /** Maintains a few current-state slots without inferring ownership or safety. */
    private static boolean updateGameFacts(String beforeJson, String afterJson,
                                           List<StoryEvent.Draft> detected,
                                           String stamp) {
        Map<String, Object> before = beforeJson == null || beforeJson.isEmpty()
                ? Map.of() : JsonParse.parseObject(beforeJson);
        Map<String, Object> after = JsonParse.parseObject(afterJson);
        boolean changed = false;

        Map<String, Object> oldCharacter = JsonParse.map(before, "character");
        Map<String, Object> character = JsonParse.map(after, "character");
        String oldHand = oldCharacter == null ? ""
                : JsonParse.str(oldCharacter, "primaryHand", "");
        String hand = character == null ? "" : JsonParse.str(character, "primaryHand", "");
        if (!hand.equals(oldHand)) {
            String text = hand.isEmpty() ? "the survivor's primary hand is empty"
                    : "the survivor currently holds " + hand + " in their primary hand";
            changed |= FACTS.upsert("game:primary-hand", text, "possession",
                    "game", 100, PAGES.size());
        }

        Map<String, Object> oldVehicle = JsonParse.map(before, "inAVehicle");
        Map<String, Object> vehicle = JsonParse.map(after, "inAVehicle");
        String oldModel = oldVehicle == null ? "" : JsonParse.str(oldVehicle, "model", "a vehicle");
        String model = vehicle == null ? "" : JsonParse.str(vehicle, "model", "a vehicle");
        if (!model.equals(oldModel)) {
            String text = model.isEmpty() ? "the survivor is currently on foot"
                    : "the survivor is currently inside " + article(model);
            changed |= FACTS.upsert("game:vehicle-occupancy", text, "possession",
                    "game", 100, PAGES.size());
        }

        Map<String, Object> oldHealth = JsonParse.map(before, "health");
        Map<String, Object> health = JsonParse.map(after, "health");
        for (String[] wound : new String[][] {
                {"partsBitten", "bite wound"}, {"partsScratched", "scratch"},
                {"partsBleeding", "bleeding wound"}
        }) {
            int oldCount = oldHealth == null ? 0 : JsonParse.num(oldHealth, wound[0], 0);
            int count = health == null ? 0 : JsonParse.num(health, wound[0], 0);
            if (count == oldCount) continue;
            String text = count == 0 ? "the survivor currently has no active " + wound[1]
                    : "the survivor currently has " + count + " active "
                            + wound[1] + (count == 1 ? "" : "s");
            changed |= FACTS.upsert("game:injury:" + wound[0], text, "injury",
                    "game", 100, PAGES.size());
        }

        for (StoryEvent.Draft event : detected) {
            if (StoryEvent.KILL.equals(event.type) && character != null) {
                String itemId = JsonParse.str(character, "primaryHandId", "");
                String item = JsonParse.str(character, "primaryHand", "");
                int kills = 1;
                try { kills = Math.max(1, Integer.parseInt(event.facts.getOrDefault("count", "1"))); }
                catch (NumberFormatException ignored) { }
                for (int i = 0; i < Math.min(kills, 20) && !itemId.isEmpty() && !item.isEmpty(); i++) {
                    changed |= CONTINUITY.record("weapon", itemId, item, stamp);
                }
            } else if (StoryEvent.VEHICLE_ENTERED.equals(event.type) && vehicle != null) {
                String vehicleId = JsonParse.str(vehicle, "vehicleId", "");
                if (!vehicleId.isEmpty()) {
                    changed |= CONTINUITY.record("vehicle", vehicleId,
                            JsonParse.str(vehicle, "model", "vehicle"), stamp);
                }
            } else if (StoryEvent.SLEEP_STARTED.equals(event.type)
                    && !event.placeId.isEmpty() && !event.place.isEmpty()) {
                changed |= CONTINUITY.record("rest", event.placeId, event.place, stamp);
            }
        }
        return changed;
    }

    private static String article(String label) {
        if (label == null || label.isBlank()) return "a vehicle";
        String lower = label.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("a ") || lower.startsWith("an ")
                || lower.startsWith("the ") ? label : "a " + label;
    }

    /** Records a validated game/mod hook event under the campaign transaction. */
    public static synchronized boolean recordEvent(
            String type, String summary, int importance,
            String stamp, String placeId, String place, String source) {
        load();
        EventJournal.Snapshot old = EVENTS.snapshot();
        ContinuityMemory.Snapshot oldContinuity = CONTINUITY.snapshot();
        DirectorBible.Snapshot oldDirector = DIRECTOR.snapshot();
        try {
            long eventId = EVENTS.record(StoryEvent.draft(type, stamp, placeId, place,
                    summary, source, importance));
            if ("director".equals(MODE)) DIRECTOR.observe(eventId, type, summary);
            if (isRoutineAction(type) && placeId != null && !placeId.isEmpty()) {
                CONTINUITY.record("routine", type + "@" + placeId,
                        routineLabel(type, place), stamp);
            }
            if (save()) return true;
        } catch (Throwable t) {
            Config.log("campaign: event rejected - " + t);
        }
        EVENTS.restore(old);
        CONTINUITY.restore(oldContinuity);
        DIRECTOR.restore(oldDirector);
        return false;
    }

    private static boolean isRoutineAction(String type) {
        return StoryEvent.CRAFTED.equals(type) || StoryEvent.REPAIRED.equals(type)
                || StoryEvent.FARMED.equals(type) || StoryEvent.FIRE_STARTED.equals(type);
    }

    private static String routineLabel(String type, String place) {
        String verb = switch (type) {
            case StoryEvent.CRAFTED -> "crafted";
            case StoryEvent.REPAIRED -> "made repairs";
            case StoryEvent.FARMED -> "tended crops";
            case StoryEvent.FIRE_STARTED -> "lit a fire";
            default -> "worked";
        };
        return verb + " at " + ((place == null || place.isBlank()) ? "the same place" : place);
    }

    /** Exact pending batch captured for one provider request. */
    static synchronized EventJournal.Capture promptEvents() {
        load();
        return EVENTS.capture();
    }

    public static synchronized int pendingEventCount() {
        load();
        return EVENTS.pendingCount();
    }

    /** Local diagnostics. Includes local ids and must not be sent to providers. */
    public static synchronized String eventsJson() {
        load();
        return EVENTS.json();
    }

    /** Local diagnostics. Includes local place ids and must stay on the device. */
    public static synchronized String worldMemoryJson() {
        load();
        return MEMORY.json();
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
        if (!save()) {
            TODO.remove(TODO.size() - 1);
            return false;
        }
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
            String state,
            int consumedDirections) {
        return commitGeneratedPage(expectedGeneration, premise, title, text,
                stamp, canon, todo, state, consumedDirections, List.of());
    }

    /** 2.0 overload: page commit also acknowledges its exact event batch. */
    public static synchronized boolean commitGeneratedPage(
            long expectedGeneration,
            String premise,
            String title,
            String text,
            String stamp,
            List<String> canon,
            String todo,
            String state,
            int consumedDirections,
            List<Long> consumedEventIds) {
        if (GENERATION.get() != expectedGeneration) {
            Config.log("campaign: dropping completed page for stale generation "
                    + expectedGeneration + " (current " + GENERATION.get() + ")");
            return false;
        }
        load();
        if (title == null || title.isBlank() || title.length() > 120) return false;
        if (text == null || text.isBlank() || text.length() > 32 * 1024) return false;
        if (premise != null && premise.length() > 4 * 1024) return false;
        if (PAGES.isEmpty() && PREMISE.isEmpty()
                && (premise == null || premise.isBlank())) return false;
        String titleKey = RepetitionGuard.titleKey(title);
        String openingKey = RepetitionGuard.openingKey(text);
        for (Page old : PAGES) {
            if (!titleKey.isEmpty() && titleKey.equals(RepetitionGuard.titleKey(old.title))) {
                Config.log("campaign: page refused - title repeats page " + old.number);
                return false;
            }
            if (!openingKey.isEmpty()
                    && openingKey.equals(RepetitionGuard.openingKey(old.text))) {
                Config.log("campaign: page refused - opening repeats page " + old.number);
                return false;
            }
        }
        String keptState = Delta.keep(state);
        if (keptState == null || keptState.length() > MAX_LAST_STATE_CHARS) return false;

        // Save failures are not commits. Keep a small in-memory undo record so
        // the UI, a successor request, and the disk all continue to see the
        // same old campaign when persistence is unavailable.
        List<Page> oldPages = new ArrayList<>(PAGES);
        LinkedHashSet<String> oldCanon = new LinkedHashSet<>(CANON);
        FactMemory.Snapshot oldFacts = FACTS.snapshot();
        ThreadMemory.Snapshot oldThreads = THREADS.snapshot();
        List<String> oldTodo = new ArrayList<>(TODO);
        List<String> oldDirections = new ArrayList<>(DIRECTIONS);
        EventJournal.Snapshot oldEvents = EVENTS.snapshot();
        DirectorBible.Snapshot oldDirector = DIRECTOR.snapshot();
        String oldPremise = PREMISE;
        String oldLastState = LAST_STATE;

        if (!addPageInMemory(title, text, stamp)) return false;
        if (PREMISE.isEmpty() && premise != null && !premise.isBlank()) {
            PREMISE = premise.strip();
        }
        if ("director".equals(MODE) && !DIRECTOR.frozen()) {
            DIRECTOR.freeze(Scenario.byId(SCENARIO), PREMISE);
        }
        addCanonInMemory(canon);
        addTodoInMemory(todo, "story");
        int spent = Math.max(0, Math.min(consumedDirections, DIRECTIONS.size()));
        if (spent > 0) DIRECTIONS.subList(0, spent).clear();
        int narratedEvents = EVENTS.markNarrated(consumedEventIds, PAGES.size());
        if (consumedEventIds != null && narratedEvents != consumedEventIds.size()) {
            PAGES.clear(); PAGES.addAll(oldPages);
            CANON.clear(); CANON.addAll(oldCanon);
            FACTS.restore(oldFacts);
            THREADS.restore(oldThreads);
            TODO.clear(); TODO.addAll(oldTodo);
            DIRECTIONS.clear(); DIRECTIONS.addAll(oldDirections);
            EVENTS.restore(oldEvents);
            DIRECTOR.restore(oldDirector);
            PREMISE = oldPremise;
            LAST_STATE = oldLastState;
            Config.log("campaign: page refused - captured event batch is stale");
            return false;
        }
        LAST_STATE = keptState;
        boolean stored = save();

        if (!stored) {
            PAGES.clear(); PAGES.addAll(oldPages);
            CANON.clear(); CANON.addAll(oldCanon);
            FACTS.restore(oldFacts);
            THREADS.restore(oldThreads);
            TODO.clear(); TODO.addAll(oldTodo);
            DIRECTIONS.clear(); DIRECTIONS.addAll(oldDirections);
            EVENTS.restore(oldEvents);
            DIRECTOR.restore(oldDirector);
            PREMISE = oldPremise;
            LAST_STATE = oldLastState;
            Config.log("campaign: generated page rolled back after save failure");
            return false;
        }

        Config.log("campaign: page " + PAGES.size() + " committed atomically ("
                + text.length() + " chars, generation " + expectedGeneration
                + ", " + spent + " direction(s), " + narratedEvents
                + " event(s) consumed)");
        return true;
    }

    public static synchronized boolean toggleTodo(int oneBased) {
        load();
        if (oneBased < 1 || oneBased > TODO.size()) return false;
        String row = TODO.get(oneBased - 1);
        // Ticking a deferred item brings it straight back as done - the player
        // did the thing they had shelved, which is the happy path.
        TODO.set(oneBased - 1, enc(isDone(row) ? OPEN : DONE, srcOf(row), textOf(row)));
        if (save()) return true;
        TODO.set(oneBased - 1, row);
        return false;
    }

    /** Ticked items graduate to the record of what she has actually done. */
    public static synchronized boolean clearDoneTodo() {
        load();
        List<String> oldTodo = new ArrayList<>(TODO);
        List<String> oldAchieved = new ArrayList<>(ACHIEVED);
        boolean any = false;
        for (String row : new ArrayList<>(TODO)) {
            if (!isDone(row)) continue;
            remember(ACHIEVED, textOf(row));
            TODO.remove(row);
            any = true;
        }
        if (any && !save()) {
            TODO.clear(); TODO.addAll(oldTodo);
            ACHIEVED.clear(); ACHIEVED.addAll(oldAchieved);
            return false;
        }
        return true;
    }

    /**
     * Shelved. A good idea, but not now.
     *
     * The third answer. Unlike a strike-out this teaches the narrator nothing
     * about what the player dislikes - it only says "not this hour". The item
     * stays in the book, stops counting against the proposal cap so the story
     * can offer something else, and comes back the moment it is tapped again.
     */
    public static synchronized boolean laterTodo(int oneBased) {
        load();
        if (oneBased < 1 || oneBased > TODO.size()) return false;
        String row = TODO.get(oneBased - 1);
        int now = stateOf(row) == LATER ? OPEN : LATER;
        TODO.set(oneBased - 1, enc(now, srcOf(row), textOf(row)));
        if (save()) return true;
        TODO.set(oneBased - 1, row);
        return false;
    }

    /**
     * Struck out without being done. Remembered as a refusal, so the narrator
     * stops offering this and learns the shape of what she will not do.
     */
    public static synchronized boolean dropTodo(int oneBased) {
        load();
        List<String> oldTodo = new ArrayList<>(TODO);
        List<String> oldAchieved = new ArrayList<>(ACHIEVED);
        List<String> oldDeclined = new ArrayList<>(DECLINED);
        if (oneBased < 1 || oneBased > TODO.size()) return false;
        String row = TODO.remove(oneBased - 1);
        if (isDone(row)) remember(ACHIEVED, textOf(row));
        else remember(DECLINED, textOf(row));
        if (!save()) {
            TODO.clear(); TODO.addAll(oldTodo);
            ACHIEVED.clear(); ACHIEVED.addAll(oldAchieved);
            DECLINED.clear(); DECLINED.addAll(oldDeclined);
            return false;
        }
        return true;
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
                String prefix = "(the player observes) ";
                if (s.length() > 300 - prefix.length()) {
                    s = s.substring(0, 300 - prefix.length());
                }
                LinkedHashSet<String> old = new LinkedHashSet<>(CANON);
                FactMemory.Snapshot oldFacts = FACTS.snapshot();
                ThreadMemory.Snapshot oldThreads = THREADS.snapshot();
                boolean added = addCanonInMemory(List.of(prefix + s));
                if (added && !save()) {
                    CANON.clear(); CANON.addAll(old);
                    FACTS.restore(oldFacts);
                    THREADS.restore(oldThreads);
                    return "could not save that note";
                }
                return added ? "kept as canon" : "already kept as canon";
            }
            case "direction" -> {
                if (DIRECTIONS.size() >= MAX_DIRECTIONS) {
                    return "too many NEXT notes - use or remove one first";
                }
                DIRECTIONS.add(s);
                if (!save()) {
                    DIRECTIONS.remove(DIRECTIONS.size() - 1);
                    return "could not save that note";
                }
                return "will steer the next page";
            }
            case "standing" -> {
                if (!STANDING.contains(s) && STANDING.size() >= MAX_STANDING) {
                    return "too many ALWAYS notes - remove one first";
                }
                boolean added = STANDING.add(s);
                if (added && !save()) {
                    STANDING.remove(s);
                    return "could not save that note";
                }
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
        load();
        if (DIRECTIONS.isEmpty()) return;
        List<String> old = new ArrayList<>(DIRECTIONS);
        DIRECTIONS.clear();
        if (!save()) DIRECTIONS.addAll(old);
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
        if (save()) return true;
        STANDING.clear(); STANDING.addAll(l);
        return false;
    }

    /**
     * The player's voice, as the prompt sees it.
     *
     * Standing preferences are restated EVERY time rather than remembered from
     * an earlier page: "don't hurry me" honoured once and then forgotten is
     * worse than not having the feature at all.
     */
    public static synchronized String notesForPrompt() {
        return promptNotes().text;
    }

    /** Captures prompt text and one-shot direction count under one lock. */
    public static synchronized PromptNotes promptNotes() {
        load();
        if (STANDING.isEmpty() && DIRECTIONS.isEmpty()) {
            return new PromptNotes("", 0);
        }
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
        return new PromptNotes(sb.toString(), DIRECTIONS.size());
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
        if ("director".equals(MODE)) sb.append(DIRECTOR.publicPrompt());
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
        if (PAGES.isEmpty() && FACTS.size() == 0 && SEEN.isEmpty()
                && MEMORY.size() == 0
                && OPENING.isEmpty() && SCENARIO.isEmpty() && PREMISE.isEmpty()) return "";

        // The scenario spine, the premise and the opening are NOT repeated
        // here. They live in fixedSpine(), inside the cached system block, and
        // emitting them again cost a full copy on every single request - with
        // the two copies disagreeing about the survivor's pronoun, which is
        // the loudest possible way to make the model doubt the one it was
        // given. This block is the part of the campaign that GROWS.
        StringBuilder sb = new StringBuilder(8192);
        if (Settings.knowledge() >= Settings.KNOW_MEMORY) sb.append(MEMORY.prompt());
        sb.append(seenForPrompt());
        sb.append(FACTS.prompt());
        sb.append(THREADS.prompt(PAGES.size()));
        sb.append(CONTINUITY.prompt());

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

    /** Small recent anti-repetition index placed beside the next-page request. */
    public static synchronized String repetitionGuidance() {
        load();
        if (PAGES.isEmpty()) return "";
        StringBuilder out = new StringBuilder(1200);
        out.append("### RECENT WORDING TO AVOID\n");
        out.append("Do not reuse these titles or begin with these same words.\n");
        int first = Math.max(0, PAGES.size() - 12);
        for (int i = first; i < PAGES.size(); i++) {
            Page page = PAGES.get(i);
            out.append("- title: ").append(page.title).append(" | opening: ")
                    .append(RepetitionGuard.openingKey(page.text)).append('\n');
        }
        return out.append('\n').toString();
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
