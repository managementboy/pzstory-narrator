package de.fricke.pzstory;

import java.util.Map;

import me.zed_0xff.zombie_buddy.Exposer;

/**
 * The Lua-facing surface of the mod, exposed as the global Lua table PZStory.
 *
 * Contract settled in Phase 0:
 *   - @Exposer.LuaClass(name = "PZStory") produces a global table whose static
 *     methods are callable as PZStory.foo().
 *   - The scan matches javaPkgName EXACTLY, so this class must stay directly
 *     in de.fricke.pzstory.
 *
 * Everything here returns instantly. Nothing blocks the game loop - the model
 * call happens on a background thread and Lua drains it with pollStream().
 */
@Exposer.LuaClass(name = "PZStory")
public class StoryAPI {

    /** Lightweight local observation cadence. It never starts a provider call. */
    private static final long OBSERVE_INTERVAL_NANOS = 5_000_000_000L;
    private static volatile long nextObservationNanos = 0;

    public StoryAPI() {
        // Kahlua wants a public no-arg constructor.
    }

    /** Human-facing release, e.g. "2.0.0". For display and bug reports. */
    public static String version() {
        return Version.RELEASE;
    }

    /**
     * Lua/Java bridge compatibility version.
     *
     * Lua compares this for EXACT equality. It used to prefix-match the
     * release string, which meant a JAR reporting "1.23.10" satisfied a Lua
     * that required "1.23.1" - a silently incompatible pairing.
     */
    public static String apiVersion() {
        return Version.API;
    }

    /**
     * Writes to console.txt. The dev-time log channel for Lua.
     *
     * Routed through Config.log so it gets the same redaction and control
     * character sanitising as everything else. It used to println directly,
     * which meant anything Lua passed - including a provider error body or a
     * profile name - reached the log raw, and could inject CR/LF to forge log
     * lines.
     */
    public static void log(String s) {
        Config.log("lua> " + s);
    }

    // --------------------------------------------------------- game state

    /** The live game state as a JSON string. Never nil; carries "error" instead. */
    public static String snapshot() {
        try {
            return StateReader.snapshot();
        } catch (Throwable t) {
            return "{\"error\":\"snapshot threw " + t.getClass().getSimpleName() + "\"}";
        }
    }

    /** Exactly the live-state projection placed in a provider request. */
    public static String providerPreview() {
        try {
            return NarrativeState.fromRaw(StateReader.snapshot());
        } catch (Throwable t) {
            return "{\"error\":\"provider preview failed\"}";
        }
    }

    /**
     * Called from the main-thread Lua tick. The fast path is only a clock
     * comparison; every five seconds it records a lightweight factual state.
     */
    public static void observeWorld() {
        long now = System.nanoTime();
        long next = nextObservationNanos;
        if (next != 0 && now < next) return;
        nextObservationNanos = now + OBSERVE_INTERVAL_NANOS;
        try {
            Campaign.observeState(StateReader.eventSnapshot(), stamp(), false);
        } catch (Throwable t) {
            Config.log("event observer skipped a sample: " + t.getClass().getSimpleName());
        }
    }

    /**
     * Supported Build 42 callbacks call this after a transient action. It
     * samples the same bounded local state immediately; it does not invent an
     * event and never starts a provider request.
     */
    public static void observeNow() {
        nextObservationNanos = 0;
        observeWorld();
    }

    /** Records only allow-listed actions after a supported completion hook. */
    public static boolean recordAction(String action, String detail) {
        ActionEventPolicy.Result event = ActionEventPolicy.resolve(action, detail);
        if (event == null) return false;
        try {
            String state = StateReader.eventSnapshot();
            PlaceRef place = PlaceRef.fromState(JsonParse.parseObject(state));
            return Campaign.recordEvent(event.type(), event.summary(),
                    event.importance(), stamp(),
                    place == null ? "" : place.id,
                    place == null ? "" : place.label, "game");
        } catch (Throwable t) {
            Config.log("action event skipped: " + t.getClass().getSimpleName());
            return false;
        }
    }

    /** Local-only diagnostics; includes event ids and must not be sent remotely. */
    public static String eventJournal() {
        return Campaign.eventsJson();
    }

    /** Runs synthetic gameplay transitions entirely in memory; never touches a save. */
    public static String testLabScenario(String scenario) {
        return TestLabDiagnostics.run(scenario);
    }

    /** Local-only diagnostics; stable place ids never enter provider prompts. */
    public static String worldMemory() {
        return Campaign.worldMemoryJson();
    }

    /** Local-only typed story facts, including provenance and confidence. */
    public static String factMemory() {
        return Campaign.factMemoryJson();
    }

    /** Local-only deliberate setup/payoff records. */
    public static String threadMemory() {
        return Campaign.threadMemoryJson();
    }

    /** Local ids and counts behind qualitative familiarity projections. */
    public static String continuityMemory() {
        return Campaign.continuityMemoryJson();
    }

    public static int pendingEvents() {
        return Campaign.pendingEventCount();
    }

    /**
     * "none" | "analog" | "digital" - what the survivor can tell about the
     * time. The book's header obeys this: no watch, no clock.
     */
    public static String timepiece() {
        return StateReader.timepiece();
    }

    // ------------------------------------------------------------- config

    /** Re-reads profiles.json. Returns a human-readable status line. */
    public static String reloadConfig() {
        String s = Config.reload();
        Config.log(s);
        return s;
    }

    /** Profile list as JSON for the options dropdown. Never contains a key. */
    public static String profiles() {
        return Config.profilesJson();
    }

    public static boolean setProfile(String name) {
        boolean ok = Config.setActive(name);
        if (ok) Settings.setProfileName(name);
        Config.log("setProfile(" + name + ") -> " + ok);
        return ok;
    }

    /** Switches to the next usable profile. Returns its name. */
    public static String nextProfile() {
        java.util.List<String> names = Config.profileNames();
        if (names.isEmpty()) return "";
        Config.Profile cur = Config.active();
        int at = cur == null ? -1 : names.indexOf(cur.name);
        for (int i = 1; i <= names.size(); i++) {
            String candidate = names.get((at + i) % names.size());
            Config.Profile p = Config.profile(candidate);
            if (p != null && p.usable()) {
                setProfile(candidate);
                return candidate;
            }
        }
        return cur == null ? "" : cur.name;
    }

    // ---------------------------------------------------------- the model

    /**
     * Starts a streaming request. Returns null on success, or the reason it
     * could not start. Returns immediately either way.
     */
    public static String requestPage(String system, String user) {
        String err = Llm.start(system, user);
        if (err != null) Config.log("requestPage refused: " + err);
        return err;
    }

    /** Drains new text. JSON: status, delta, chars, done, error, timings, tokens. */
    public static String pollStream() {
        return Llm.poll();
    }

    /** The whole page so far. */
    public static String streamText() {
        return Llm.text();
    }

    public static void cancelPage() {
        Llm.cancel();
    }

    /**
     * Asks for a real story page from the current game state.
     *
     * @param notes player notes to hand over, or null/"" for none.
     * @return null on success, or the reason it could not start.
     */
    public static String requestStoryPage(String notes) {
        final boolean firstPage = Campaign.pageCount() == 0;
        if (!firstPage && (notes == null || notes.isBlank())) {
            return "Tell the narrator what matters before continuing.";
        }
        NarratorHistory.SeedStatus historySeed = NarratorHistory.ensureSeeded(
                narratorScope(), narratorSystemPrompt());
        if (historySeed == NarratorHistory.SeedStatus.SEEDING) {
            return "the narrator is still learning the Knox history - try WRITE "
                    + "again in a moment";
        }
        if (historySeed == NarratorHistory.SeedStatus.FAILED) {
            return "the narrator could not preserve its Knox history yet - try WRITE "
                    + "again to retry";
        }

        String state;
        try {
            state = StateReader.snapshot();
        } catch (Throwable t) {
            return "could not read the game state: " + t.getClass().getSimpleName();
        }
        final String pageStamp = stamp();
        // Keep the observer schema consistent regardless of the player's
        // provider-knowledge dial. A full KNOW_CARRIED snapshot intentionally
        // omits room structure; replacing the observer baseline with it would
        // make the next shelter transition disappear.
        if (!Campaign.observeState(StateReader.eventSnapshot(), pageStamp, true)) {
            return "could not save the local event journal; no provider request was made";
        }
        final String narrativeState;
        try {
            narrativeState = NarrativeState.fromRaw(state);
        } catch (Throwable t) {
            return "could not prepare a privacy-safe game state: "
                    + t.getClass().getSimpleName();
        }

        // New-game handshake, captured once from the very first page's state.
        if (!Campaign.hasOpening()) Campaign.openIfNew(opening(state));

        Config.Profile p = Config.active();

        // The player's voice: standing preferences restated every time, plus
        // the exact batch of one-shot directions this request will consume.
        // A direction filed while the provider is working belongs to the next
        // page and survives this one's commit.
        Campaign.PromptNotes capturedNotes = Campaign.promptNotes();
        EventJournal.Capture capturedEvents = Campaign.promptEvents();
        String voice = Campaign.todoForPrompt() + capturedNotes.text;

        // A note the player has only just written gets its own heading. Folded
        // in with the rest it would be one bullet among many - and the whole
        // point of writing it was to be answered NOW.
        if (notes != null && !notes.isBlank()) {
            String boundedNote = notes.strip();
            if (boundedNote.length() > 500) boundedNote = boundedNote.substring(0, 500);
            String fresh = "The player has just written this in their notebook, "
                    + "moments ago, and is waiting to see it answered:\n\n"
                    + boundedNote
                    + "\n\nLet this page take it up directly. Do not quote it back "
                    + "or acknowledge it as a message; simply let the story show "
                    + "that it landed.\n";
            voice = voice.isEmpty() ? fresh : voice + "\n" + fresh;
        }

        // The interval, not the instant. Without this every page
        // re-describes the room because nothing in the snapshot says
        // what is new.
        String change = capturedEvents.text
                + Delta.between(Campaign.lastState(), state)
                + Campaign.repetitionGuidance();

        final int targetWords = Settings.words();
        final String stateNow = state;

        if (Settings.NARRATOR_VALIDATED.equals(Settings.narratorMode())) {
            if ("director".equals(Campaign.mode())) {
                return "safe experimental narrator currently supports chronicler mode only";
            }
            final ValidatedNarrator.Session session;
            try {
                long seed = ((long) pageStamp.hashCode() << 32)
                        ^ Integer.toUnsignedLong(state.hashCode());
                session = ValidatedNarrator.prepare(
                        narrativeState, capturedEvents.events, change,
                        firstPage, targetWords, seed, voice,
                        Campaign.scenario() == null ? "" : Campaign.scenario().id,
                        Campaign.repetitionGuidance(), Campaign.pageCount() + 1);
            } catch (Throwable t) {
                return "could not prepare the validated narrator: "
                        + t.getClass().getSimpleName();
            }
            // TEMPORARY local evaluation trace. Safe mode sends a compact
            // planner contract rather than prose, so record both that raw
            // exchange and the controlled page Java renders from it.
            LiveTrace.request(session.systemPrompt(), "", session.userPrompt());
            String err = Llm.startBufferedScoped(
                    Llm.SCOPE_SAFE,
                    session.systemPrompt(), "", session.userPrompt(),
                    (generation, plannerReply) -> {
                        LiveTrace.reply(plannerReply);
                        final String rendered;
                        final PageResult result;
                        try {
                            rendered = session.render(plannerReply);
                            result = PageResult.parse(rendered, firstPage, targetWords);
                            GroundingGuard.validate(result, narrativeState, change,
                                    firstPage,
                                    Delta.stillStanding(Campaign.lastState(), state));
                        } catch (Throwable invalid) {
                            LiveTrace.validation("SAFE RENDER REJECTED | "
                                    + invalid.getMessage());
                            return Llm.CompletionResult.failure("invalid_output",
                                    "the validated page could not be rendered: "
                                            + invalid.getMessage());
                        }
                        boolean stored = Campaign.commitGeneratedPage(
                                generation,
                                result.premise,
                                result.title,
                                result.page,
                                pageStamp,
                                result.canon,
                                result.todo,
                                stateNow,
                                capturedNotes.directionCount,
                                capturedEvents.ids,
                                "lmstudio-stateful".equals(p.kind) ? p.name : null,
                                "lmstudio-stateful".equals(p.kind) ? p.model : null,
                                "lmstudio-stateful".equals(p.kind)
                                        ? Llm.SCOPE_SAFE : null,
                                "lmstudio-stateful".equals(p.kind)
                                         ? Llm.pendingResponseId() : null);
                        LiveTrace.validation((stored
                                ? "SAFE PLAN ACCEPTED AND PAGE SAVED"
                                : "SAFE PAGE VALID BUT SAVE FAILED")
                                + "\n\n===== CONTROLLED RENDERED PAGE =====\n"
                                + rendered);
                        return stored ? Llm.CompletionResult.success(rendered)
                                : Llm.CompletionResult.failure("save",
                                        "the page was valid but the campaign file could not "
                                                + "be saved; the old campaign is unchanged");
                    });
            if (err != null) Config.log("requestStoryPage refused: " + err);
            return err;
        }

        String systemPrompt = classicSystemPrompt();
        String tailPrompt = Prompt.userTurn(
                narrativeState, voice, change, firstPage,
                Delta.stillStanding(Campaign.lastState(), state));

        // Input tokens cost money and the archive grows indefinitely. Reserve
        // room for the fixed system and live state, then spend only what is
        // left on history. Local laptop models retain their tighter budget;
        // hosted profiles use the explicit maxInputChars setting rather than
        // the old unlimited-history sentinel.
        int inputLimit = p == null ? 48000 : p.maxInputChars;
        if (p != null && "openai-compatible".equals(p.kind)
                && Endpoint.isLocal(p.baseUrl)) {
            // The fixed narrator charter, world rules and live turn now exceed
            // 24k characters before a single history page is attached. A 48k
            // character envelope remains comfortable inside a 16k-token local
            // context while leaving room for visible output.
            inputLimit = Math.min(inputLimit, 48000);
        }
        int historyBudget = Math.max(0,
                inputLimit - systemPrompt.length() - tailPrompt.length());
        String history = historyBudget == 0 ? "" : Campaign.history(historyBudget);

        // TEMPORARY local Qwen evaluation trace. It contains private prompt
        // state but no provider credentials and is removed after this test.
        LiveTrace.request(systemPrompt, history, tailPrompt);

        final int[] invalidAttempts = { 0 };
        String err = Llm.startScoped(
                Llm.SCOPE_CLASSIC,
                systemPrompt,
                history,                              // cached prefix
                tailPrompt,
                (generation, all) -> {
                    LiveTrace.reply(all);
                    // The completed text arrives as an argument. It used to be
                    // fetched with Llm.text(), which read whatever the global
                    // buffer held at the moment the callback ran - a later
                    // request could have replaced it.
                    // All campaign consequences cross one generation check
                    // and one persistence boundary. A save load cannot land
                    // between the check and these mutations.
                    final PageResult result;
                    try {
                        result = PageResult.parse(all, firstPage, targetWords);
                        GroundingGuard.validate(result, narrativeState, change,
                                firstPage,
                                Delta.stillStanding(Campaign.lastState(), state));
                    } catch (PageResult.Invalid invalid) {
                        invalidAttempts[0]++;
                        LiveTrace.validation("REJECTED ATTEMPT "
                                + invalidAttempts[0] + " | " + invalid.getMessage());
                        if (invalidAttempts[0] == 1) {
                            return Llm.CompletionResult.retry(
                                    systemPrompt,
                                    history,
                                    repairTurn(invalid.getMessage(), all,
                                            "lmstudio-stateful".equals(p.kind)
                                                    ? "" : tailPrompt));
                        }
                        return Llm.CompletionResult.failure("invalid_output",
                                "the model reply and its corrective retry were not saved: "
                                        + invalid.getMessage());
                    }
                    boolean stored = Campaign.commitGeneratedPage(
                            generation,
                            result.premise,
                            result.title,
                            result.page,
                            pageStamp,
                            result.canon,
                            result.todo,
                            stateNow,
                            capturedNotes.directionCount,
                            capturedEvents.ids,
                            "lmstudio-stateful".equals(p.kind) ? p.name : null,
                            "lmstudio-stateful".equals(p.kind) ? p.model : null,
                            "lmstudio-stateful".equals(p.kind)
                                    ? Llm.SCOPE_CLASSIC : null,
                            "lmstudio-stateful".equals(p.kind)
                                    ? Llm.pendingResponseId() : null);
                    LiveTrace.validation(stored
                            ? "ACCEPTED AND SAVED" : "VALID BUT SAVE FAILED");
                    return stored ? null : Llm.CompletionResult.failure("save",
                            "the page was valid but the campaign file could not be saved; "
                                    + "the old campaign is unchanged");
                });
        if (err != null) Config.log("requestStoryPage refused: " + err);
        return err;
    }

    /** Downloaded LM Studio LLMs only; embeddings are deliberately excluded. */
    public static String lmStudioModels() { return LmStudioCatalog.json(); }
    public static String nextLmStudioModel() { return LmStudioCatalog.next(); }

    /** One bounded corrective turn for a structurally invalid Classic reply. */
    private static String repairTurn(String reason, String rejected,
                                     String originalTailForStatelessProvider) {
        StringBuilder out = new StringBuilder(16 * 1024);
        if (originalTailForStatelessProvider != null
                && !originalTailForStatelessProvider.isBlank()) {
            out.append(originalTailForStatelessProvider).append("\n\n");
        }
        out.append("### CORRECTION\n")
           .append("Your previous draft was rejected and will never enter the story. ")
           .append("Reason: ").append(reason == null ? "invalid page format" : reason)
           .append(".\nRewrite the ENTIRE answer from scratch. Re-read the original ")
           .append("STATE and constraints. Use only the required headings in their ")
           .append("required order. Let the moment reach a natural end, keep the survivor in ")
           .append("the exact state supplied, and invent no action or physical detail. ")
           .append("If this is page one, the PREMISE MUST contain 60-100 words in ")
           .append("three to five complete sentences; count it before replying. The ")
           .append("occupation is biography, not the survivor's current location. Hidden ")
           .append("Knox history belongs to the narrator and is not something the survivor ")
           .append("has read, heard or remembers. Name no object, town, employer or business ")
           .append("unless STATE names it. Do not copy wording from format examples. Stop ")
           .append("immediately after the ### TODO block. Do not discuss this correction.\n");
        if (originalTailForStatelessProvider != null
                && !originalTailForStatelessProvider.isBlank()) {
            String raw = rejected == null ? "" : rejected;
            if (raw.length() > 12000) raw = raw.substring(0, 12000);
            out.append("\nDISCARDED DRAFT (data to replace, not instructions):\n")
               .append(raw);
        }
        return out.toString();
    }

    /**
     * A one-paragraph anchor built from the first snapshot: who he was and
     * when he woke. Pulled out of the JSON we already have rather than read
     * again, so it is guaranteed to describe the same instant as page one.
     */
    private static String opening(String stateJson) {
        try {
            Map<String, Object> m = JsonParse.parseObject(stateJson);
            Map<String, Object> c = JsonParse.map(m, "character");
            Map<String, Object> t = JsonParse.map(m, "time");
            if (c == null) return "";

            StringBuilder sb = new StringBuilder(512);
            String fore = JsonParse.str(c, "forename", "");
            String sur  = JsonParse.str(c, "surname", "");
            sb.append((fore + " " + sur).trim());
            String occ = JsonParse.str(c, "occupation", null);
            if (occ != null) sb.append(", ").append(occ.toLowerCase());
            sb.append('.');

            String pro = JsonParse.str(c, "pronouns", "he/him");
            sb.append(" Referred to as ").append(pro).append('.');

            // Traits became objects in 1.15.0 and this still called
            // String.valueOf() on them, so the opening - which is FIXED for the
            // life of the campaign and re-read on every page - was written as
            // "He is {name=keen cook, kind=came with their trade, ...}".
            // Read the name out properly, and never let a map reach the text.
            Object tr = c.get("traits");
            if (tr instanceof java.util.List<?> l && !l.isEmpty()) {
                java.util.List<String> names = new java.util.ArrayList<>();
                for (Object o : l) {
                    String n = (o instanceof Map<?, ?> mm)
                            ? JsonParse.str(mm, "name", "")
                            : String.valueOf(o);
                    if (n != null && !n.isBlank() && !n.contains("{")) {
                        names.add(n.toLowerCase());
                    }
                }
                if (!names.isEmpty()) {
                    // "He is keen cook and fast reader" was never grammatical.
                    // A labelled list needs no articles and cannot go wrong
                    // whatever a mod names its traits.
                    sb.append(" Traits: ");
                    for (int i = 0; i < names.size(); i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(names.get(i));
                    }
                    sb.append('.');
                }
            }
            if (t != null) {
                sb.append(" The story opens on ")
                  .append(JsonParse.num(t, "day", 0)).append('/')
                  .append(JsonParse.num(t, "month", 0)).append('/')
                  .append(JsonParse.num(t, "year", 1993))
                  .append(", Knox County, Kentucky.");
            }
            return sb.toString();
        } catch (Throwable e) {
            return "";
        }
    }

    private static String stamp() {
        try {
            var gt = zombie.GameTime.getInstance();
            if (gt == null) return "";
            return String.format("%04d-%02d-%02d %02d:%02d",
                    gt.getYear(), gt.getMonth() + 1, gt.getDay() + 1,
                    gt.getHour(), gt.getMinutes());
        } catch (Throwable t) {
            return "";
        }
    }

    // ------------------------------------------------------------- the page

    /** Title of the page currently streaming (or the last one written). */
    public static String pageTitle() {
        return Prompt.title(Llm.text());
    }

    /** Prose of the page currently streaming. Parsing lives in Java, not Lua. */
    public static String pageBody() {
        return Prompt.body(Llm.text());
    }

    // --------------------------------------------------------- the archive

    public static int archiveCount() {
        return Campaign.pageCount();
    }

    /** One stored page as JSON: n, title, text, stamp. Empty object if absent. */
    public static String archivePage(int n) {
        Campaign.Page p = Campaign.page(n);
        if (p == null) return "{}";
        Json j = new Json().obj();
        j.put("n", p.number);
        j.put("title", p.title);
        j.put("text", p.text);
        j.put("stamp", p.stamp);
        return j.endObj().toString();
    }

    public static String archiveIndex() {
        return Campaign.indexJson();
    }

    // ------------------------------------------------------------- notes

    /**
     * Files a player note.
     * @param type "observation" (permanent canon), "direction" (next page
     *             only) or "standing" (in force until removed).
     */
    public static String addNote(String type, String text) {
        String r = Campaign.addNote(type, text);
        Config.log("note [" + type + "] " + r);
        return r;
    }

    /** {"standing":[...], "directions":[...]} - what the player is under. */
    public static String notes() {
        return Campaign.notesJson();
    }

    public static boolean removeStanding(int oneBased) {
        return Campaign.removeStanding(oneBased);
    }

    /**
     * Records a room the character has just laid eyes on.
     *
     * Wired to the game's OnSeeNewRoom event, so what accumulates is literally
     * what he has seen - not what the engine happened to load.
     */
    public static void sawRoom(String room, String building) {
        Campaign.sawRoom(room, building);
    }

    // ---------------------------------------------------------- scenarios

    /** The choosable kinds of story, as JSON. */
    public static String scenarios() {
        return Scenario.listJson();
    }

    /** The chosen kind for this campaign, or "" if none picked yet. */
    public static String scenario() {
        Scenario s = Campaign.scenario();
        return s == null ? "" : s.id;
    }

    /** Why this campaign exists, as written on page one. "" until then. */
    public static String premise() {
        return Campaign.premise();
    }

    public static boolean setScenario(String id) {
        boolean stored = Campaign.setScenario(id);
        if (stored && Campaign.pageCount() == 0) {
            NarratorHistory.ensureSeeded(narratorScope(), narratorSystemPrompt());
        }
        return stored;
    }

    /** Readable setup status; calling it also repairs a missing pre-page seed. */
    public static String historySeedStatus() {
        return NarratorHistory.ensureSeeded(
                narratorScope(), narratorSystemPrompt()).name().toLowerCase();
    }

    /** Explicit KnoxOS retry; ordinary status polling never resends a failed seed. */
    public static String retryHistorySeed() {
        return NarratorHistory.retry(
                narratorScope(), narratorSystemPrompt()).name().toLowerCase();
    }

    /**
     * Read-only boot telemetry for KnoxOS. Every value comes from the active
     * profile, history transaction, or LLM request; the UI invents no progress
     * bars or percentages. Calling this also keeps a failed seed retryable.
     */
    public static String knoxOsStatus() {
        NarratorHistory.SeedStatus seed = NarratorHistory.ensureSeeded(
                narratorScope(), narratorSystemPrompt());
        Map<String, Object> lm = JsonParse.parseObject(Llm.snapshot());
        Config.Profile profile = Config.active();
        String lmStatus = String.valueOf(lm.getOrDefault("status", "IDLE"));
        boolean repairing = Boolean.TRUE.equals(lm.get("repairing"));
        String phase;
        if (seed == NarratorHistory.SeedStatus.SEEDING) phase = "HISTORY";
        else if (seed == NarratorHistory.SeedStatus.FAILED) phase = "ERROR";
        else if (Campaign.pageCount() > 0) phase = "READY";
        else if (repairing) phase = "CORRECTING";
        else if ("CONNECTING".equals(lmStatus) || "STREAMING".equals(lmStatus)
                || "RECEIVED".equals(lmStatus) || "COMMITTING".equals(lmStatus)) {
            phase = "OPENING";
        } else if (lm.get("error") != null) phase = "ERROR";
        else phase = "HISTORY_READY";

        Json j = new Json().obj()
                .put("os", "KnoxOS")
                .put("release", Version.RELEASE)
                .put("phase", phase)
                .put("history", seed.name())
                .put("lmStatus", lmStatus)
                .put("pageCount", Campaign.pageCount());
        if (profile != null) {
            j.put("profile", profile.name)
             .put("provider", profile.kind)
             .put("model", profile.model);
        }
        putLong(j, lm, "elapsedMs");
        putLong(j, lm, "firstTokenMs");
        putLong(j, lm, "inputTokens");
        putLong(j, lm, "outputTokens");
        putLong(j, lm, "cacheRead");
        putLong(j, lm, "cacheWrite");
        putLong(j, lm, "chars");
        if (repairing) j.put("repairing", true);
        if (lm.get("buffered") instanceof Boolean b) j.put("buffered", b);
        if (lm.get("error") != null) j.put("error", String.valueOf(lm.get("error")));
        if (lm.get("failKind") != null) j.put("failKind", String.valueOf(lm.get("failKind")));
        return j.endObj().toString();
    }

    private static void putLong(Json out, Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value instanceof Number n) out.put(key, n.longValue());
    }

    public static String campaignMode() { return Campaign.mode(); }
    public static boolean setCampaignMode(String mode) { return Campaign.setMode(mode); }
    public static String directorStatus() { return Campaign.directorStatusJson(); }

    // -------------------------------------------------------------- to-do

    public static String todo()                    { return Campaign.todoJson(); }
    public static boolean addTodo(String text)     { return Campaign.addTodo(text, "player"); }
    public static boolean toggleTodo(int oneBased) { return Campaign.toggleTodo(oneBased); }
    public static boolean clearDoneTodo()          { return Campaign.clearDoneTodo(); }
    public static boolean dropTodo(int oneBased)   { return Campaign.dropTodo(oneBased); }
    public static boolean laterTodo(int oneBased)  { return Campaign.laterTodo(oneBased); }

    // ----------------------------------------------------------- settings

    /** Everything the SETUP screen shows. Never contains a key. */
    public static String settings() {
        return Settings.json();
    }

    public static void setKnowledge(int level) { Settings.setKnowledge(level); }
    public static void setWords(int words)     { Settings.setWords(words); }
    public static void setPause(boolean b)     { Settings.setPause(b); }
    public static void setNudge(int n)         { Settings.setNudge(n); }
    public static void setDoom(int d)          { Settings.setDoom(d); }
    public static void setNarratorMode(String mode) {
        Settings.setNarratorMode(mode);
        if (Campaign.hasScenario() && Campaign.pageCount() == 0) {
            NarratorHistory.ensureSeeded(narratorScope(), narratorSystemPrompt());
        }
    }
    public static void setZoom(int z)          { Settings.setZoom(z); }
    public static int  getZoom()               { return Settings.zoom(); }
    public static boolean pauseOnOpen()        { return Settings.pause(); }

    /** Called when a save loads: the campaign store is per-save. */
    public static void onGameStart() {
        // Order matters. A request started against the PREVIOUS save is still
        // in flight here, and its callback would write that save's page, canon,
        // directions and lastState into the book we are about to load. Cancel
        // it first; Campaign.reset() then bumps the generation, so even a
        // worker that is mid-socket-read will be discarded when it tries to
        // commit. Neither call blocks the game thread.
        Llm.invalidateForSaveChange();
        Campaign.reset();
        Campaign.load();
        LmStudioCatalog.refresh();
        nextObservationNanos = 0;
        if (Campaign.hasScenario() && Campaign.pageCount() == 0) {
            NarratorHistory.ensureSeeded(narratorScope(), narratorSystemPrompt());
        }
    }

    private static String narratorScope() {
        return Settings.NARRATOR_VALIDATED.equals(Settings.narratorMode())
                ? Llm.SCOPE_SAFE : Llm.SCOPE_CLASSIC;
    }

    /**
     * Exact first-turn system contract. LM Studio retains this hidden seed turn,
     * so the first visible page can send only its live state as the next turn.
     */
    private static String narratorSystemPrompt() {
        return Settings.NARRATOR_VALIDATED.equals(Settings.narratorMode())
                ? ValidatedNarrator.plannerSystemPrompt()
                : classicSystemPrompt();
    }

    private static String classicSystemPrompt() {
        return Prompt.CHARTER + "\n\n" + Prompt.tone() + "\n\n"
                + World.RULES + "\n\n" + World.KNOX + "\n\n"
                + NarratorHistory.SYSTEM_CONTEXT + "\n\n"
                + StateReader.sandbox() + "\n\n" + Campaign.fixedSpine();
    }

    /**
     * Phase 2 smoke test: one tiny call with a fixed prompt, streamed to
     * console. Proves key, network, SSE parsing and threading in one keypress,
     * for a fraction of a cent.
     */
    public static String selfTest() {
        return Llm.start(
                "You are a terse test harness. Reply with exactly five short words.",
                "Say five words confirming the connection works.");
    }
}
