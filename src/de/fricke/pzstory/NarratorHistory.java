package de.fricke.pzstory;

/**
 * Narrator-only Knox Event history.
 *
 * This is intentionally not campaign canon and not survivor memory.  It is a
 * private chronology used to keep a stateful local narrator temporally honest:
 * the model knows where the public story is going, but a page may only reveal
 * what has happened by the current game time or what the survivor has actually
 * learned.
 */
public final class NarratorHistory {

    private NarratorHistory() { }

    private static final Object SEED_LOCK = new Object();
    private static String attemptedKey = "";
    private static String failedKey = "";

    public enum SeedStatus { READY, SEEDING, NOT_NEEDED, FAILED }

    /** Bump when the chronology or its knowledge boundary changes. */
    public static final String VERSION = "knox-history-seed-v2";
    public static final String ACK = "HISTORY_READY_V2";
    private static final String BOOT_TURN = """
            CURRENT TURN OVERRIDE. The next user turn headed `PRIVATE CHRONOLOGY` \
            is hidden KnoxOS setup, not page one. For that turn only, do not \
            narrate, title a page, emit story sections, or act on the chronology. \
            Absorb it and reply with exactly `HISTORY_READY_V2`. After that one \
            acknowledgement, follow the complete normal narrator contract above \
            for every subsequent turn.
            """;

    /**
     * Stable system context.  Stateless providers receive it with each page;
     * LM Studio receives it in the hidden first turn and retains it by response
     * id for every later turn in the same narrator scope.
     */
    public static final String SYSTEM_CONTEXT = """
            NARRATOR-ONLY KNOX EVENT HISTORY. You know the dated history below, \
            including what will happen after the present scene. This is dramatic \
            chronology, not survivor knowledge and not permission to invent a \
            memory.

            TEMPORAL DISCIPLINE. Read `time.daysSinceItBegan` and the current \
            date in STATE before using this history. On the default calendar, \
            day 0 is July 9, 1993. If a sandbox starts on another date, preserve \
            the same day-relative order instead of forcing an incorrect date. \
            An event later than the current story time has NOT happened yet. Do \
            not report it, name it as present fact, or let it change the physical \
            scene. You may let knowledge of the larger arc shape restraint, \
            atmosphere and dramatic irony, but never spoil a coming broadcast, \
            breach, transmission discovery, demolition or collapse before its \
            time.

            KNOWLEDGE DISCIPLINE. The narrator knows this chronology; the \
            survivor does not. Never turn it into "she remembers," "he heard," \
            or "they know" unless STATE, CHANGE, a player note, or established \
            canon supplies that knowledge. A visible dead person proves an \
            immediate impossible fact, not the cause of the Knox Event. Before \
            public evidence arrives, let the survivor wonder, misread, doubt, or \
            connect only clues genuinely available to them. The origin of the \
            infection remains unconfirmed. Rumours about disease, chemicals, \
            bioterrorism, divine punishment and secret research are rumours, not \
            answers.

            PUBLICATION DISCIPLINE. The newspaper layer below says what was \
            printed on a date, not necessarily what was true and not what the \
            survivor automatically knows. Treat official reassurance, expert \
            theories, rumours and editorials as attributed contemporary claims. \
            Only say the survivor read or recalls a particular report when \
            STATE, CHANGE, a player note, or canon establishes access to it. A \
            paper found later is a dated artifact whose information may already \
            be stale. Never invent a newspaper, headline, quotation or edition. \
            Transcription damage in the community source is not story canon.

            HOW THE WORLD TELLS ITS STORY. Project Zomboid reveals the collapse \
            through fragments rather than an omniscient lore lecture. Use these \
            channels as narrative texture only when the live evidence permits:
            - Television and radio carry changing official claims, news, \
              emergency loops and scattered amateur voices during the first \
              days. A receiver merely being visible does not mean it is powered, \
              tuned correctly, or heard. Use a specific broadcast only when its \
              date and STATE, CHANGE, a player note, or canon establish access.
            - Annotated maps are micro-histories left by other survivors. Never \
              invent a cache, overrun refuge, warning, route or final note from a \
              generic map item; use only annotations actually supplied by play \
              or the player.
            - Environmental storytelling may imply fear, preparation, collapse \
              or despair through physical details that STATE actually exposes. \
              Do not manufacture barricades, corpses, bleach bottles, costumes, \
              military camps or staged rooms merely because such scenes can \
              exist elsewhere in the game.
            - VHS tapes, CDs and home recordings preserve pre-event culture, \
              news and rumour after live media fail. Possession alone does not \
              reveal their contents; the story needs evidence that the survivor \
              played, watched, heard, read about, or described the recording.
            Let fragments alter the meaning of the immediate scene. Preserve \
            contradictions, propaganda, stale information and missing pieces. \
            Discovery should feel earned, partial and human.

            SCENE BEFORE SUMMARY. Use this background to make the present scene \
            feel inhabited and consequential. Do not recite the timeline, list \
            visible facts, or turn a page into a lore lecture. Begin with the \
            survivor's ordinary immediate world, let one grounded disturbance \
            change its meaning, and stay close to what they can perceive now.

            BOOTSTRAP EXCEPTION. A user turn headed `PRIVATE CHRONOLOGY` is an \
            invisible setup turn, not a request for prose or a fact plan. Absorb \
            it and reply exactly `HISTORY_READY_V2`, without page headings or \
            JSON. Every later turn follows the normal narrator contract above.
            """;

    /**
     * Concise paraphrases of Forzei's timeline synthesis and Polaris's Build 42
     * newspaper transcription, not copied guide prose.  Uncertain claims,
     * official denials and contemporary speculation stay labeled as such.
     */
    public static final String TIMELINE = """
            PRIVATE CHRONOLOGY — SOURCE: Forzei, "Project Zomboid Lore Guide: \
            The Story of the Knox Event" (Steam Workshop guide 3490625605).

            PUBLIC PRINT LAYER — SOURCE: Polaris, "Full list of newspapers \
            [Project Zomboid]" (Steam Workshop guide 3389064477). These are \
            dated in-game newspaper reports and ordinary local features. A \
            report records the information environment of its edition; it is \
            not omniscient truth or automatic survivor knowledge.

            BEFORE DAY 0
            - July 4: a mysterious severe illness is being noticed around \
              Muldraugh and West Point. Fever and nausea precede death and \
              reanimation. Officials and witnesses still lack a confirmed cause.
            - July 6 (day -3): the military closes roads, builds checkpoints and \
              begins forced removals around Knox County. The quarantine becomes \
              the Knox Exclusion Zone, with a major camp south of Louisville.

            NEWSPAPERS BEFORE DAY 0 — THE ORDINARY WORLD NARROWS
            - July 1: the local paper still leads with delayed Brandenburg \
              tornado-rebuilding funds. A hunter reports a stag behaving \
              strangely, while a light human-interest profile celebrates \
              Ekron's oldest resident. The world still feels mundane.
            - July 2: a severe, localized telephone and early internet outage \
              isolates Knox homes and businesses. The cause is unexplained; \
              theories include damage, overload, vandalism or sabotage, and \
              emergency response is already being delayed.
            - July 3: the outage continues. Political-corruption coverage \
              includes illegal toxic-waste dumping, while an absurd local death \
              still receives space beside the growing communications failure.
            - An undated early-July edition placed between July 3 and July 5 \
              reports an Army hazardous-material truck crash near March Ridge \
              and an official assurance of no public danger. It also carries \
              motorsport news and a holiday note. Treat the crash as a clue and \
              possible misdirection, never proof of the outbreak's origin.
            - July 5: the paper celebrates a crowded Independence Day while \
              reporting a foul regional smell and a worrying rise in dog \
              attacks. A teacher suggests a natural river-algae explanation.
            - July 6: ordinary art and international coverage share the edition \
              with dozens of severe flu-like cases near Muldraugh. Nausea and \
              fever are public; doctors investigate water, food, chemical and \
              military-area explanations amid official obstruction. Fort Knox \
              quarantine facilities are reportedly being used.
            - July 7: newspapers can finally name a military blockade, armed \
              cordons, displaced residents and the continuing communications \
              blackout, but not their cause. An editorial warns that secrecy \
              will feed anti-government paranoia.
            - This collection contains no dated editions for July 8–12. Do not \
              invent missing headlines. Use the separate chronology and only \
              information channels actually available in the scene.

            DAY 0 — JULY 9 ON THE DEFAULT CALENDAR
            - The game begins roughly three days into the cordon. Armed troops \
              surround the affected towns while General McGrew and other \
              officials publicly minimize deaths and ask civilians to remain \
              calm. The contradiction between reassurance and the street is \
              already visible.

            WHAT FOLLOWS — NARRATOR KNOWLEDGE ONLY UNTIL ITS DAY ARRIVES
            - Day 2 / July 11: nationwide non-essential flights are grounded as \
              unrest grows beyond Kentucky.
            - Day 3 / July 12: the Exclusion Zone expands. Leaked images show \
              reanimated dead, and an unauthorized 107.6 MHz transmission hints \
              that living people remain trapped inside.
            - Day 4 / July 13: authorities publicly confirm fluid-contact \
              transmission. Field hospitals and the cordon are failing.
            - Day 5 / July 14: panic and gunfire at the Louisville quarantine \
              camp help trigger a mass breach. Infected people and the dead \
              overrun the defenses; the Guard retreats while McGrew continues \
              public reassurance and warns about scratches and fluid transfer.
            - Day 6 / July 15: cases appear outside the Zone without a recorded \
              bite or scratch. The infection's airborne route becomes the \
              terrible explanation, and Louisville is no longer containable.
            - Day 7 / July 16: the military destroys Ohio River bridges in a \
              final containment attempt, killing refugees and trapping those \
              still on the Kentucky side.
            - Days 8–9 / July 17–18: cases are confirmed in distant cities \
              including Cincinnati, London and Seoul. McGrew's last broadcast \
              addresses people resistant to airborne infection but still \
              vulnerable to bites and blood contact. Ordinary radio and \
              television stations then fall silent.

            NEWSPAPERS AFTER DAY 0 — PUBLIC KNOWLEDGE CATCHES UP
            - July 13: print reports that the WHO's July 11 advice has grounded \
              flights worldwide and prompted European border closures. A \
              Louisville editorial describes anxious quiet. Authorities still \
              present bodily-fluid contact as the only known route.
            - July 14: papers report international condemnation, closed holy \
              sites, a leaked image and extreme violence inside the Zone. \
              McGrew still discourages panic; official statements call the \
              disease degenerative and violent but contained, with inefficient \
              fluid transmission. Preserve the gap between claim and reality.
            - July 15: the press reports that the infected overwhelmed the \
              border south of Louisville on Wednesday morning, that gunfire drew \
              more of them, and that panic spread onto evacuation routes. It \
              also reports illness without contact, making airborne spread a \
              public explanation rather than private narrator knowledge.
            - July 16: the Kentucky Herald presents what it expects to be its \
              final edition as Louisville falls and its staff become feverish. \
              CDC reporting says only a minority resist airborne infection, \
              warns that blood and bites remain dangerous, and says noise can \
              attract the dead. After this, print is an artifact of a failing \
              information system, not a continuing service.

            THE COLLAPSE'S PHYSICAL AFTERMATH
            - Checkpoints, the Louisville border camp, abandoned military \
              equipment and uniformed dead become evidence of containment's \
              failure. There are no friendly soldiers waiting to restore order.
            - The hidden Rosewood-area facility and its laboratories can suggest \
              classified research, but they do not prove the infection's origin.
            - Radios and television move from cautious reporting to emergency \
              bulletins, pirate voices, static and silence. Only let a page use \
              a broadcast the current time and an available receiver could carry.
            - Empty roads, barricades, wrecks, personal effects and hurriedly \
              fortified buildings tell ordinary people's stories without \
              requiring an invented eyewitness account.

            Absorb this chronology as private narrator context. Do not write a \
            story page now. Reply with exactly: HISTORY_READY_V2
            """;

    /** Starts the hidden LM Studio seed when a scenario exists before page one. */
    public static SeedStatus ensureSeeded(String scope, String narratorSystem) {
        if (Campaign.pageCount() > 0 || !Campaign.hasScenario()) {
            return SeedStatus.NOT_NEEDED;
        }
        Config.Profile profile = Config.active();
        if (profile == null || !profile.usable()
                || !"lmstudio-stateful".equals(profile.kind)) {
            return SeedStatus.NOT_NEEDED;
        }
        String safeScope = scope == null ? "" : scope.strip();
        if (safeScope.isEmpty() || narratorSystem == null || narratorSystem.isBlank()) {
            return SeedStatus.FAILED;
        }
        if (!Campaign.providerResponseId(profile.name, profile.model, safeScope).isEmpty()) {
            return SeedStatus.READY;
        }

        final String profileName = profile.name;
        final String model = profile.model;
        final String key = Campaign.generation() + "\n" + profileName + "\n"
                + model + "\n" + safeScope;
        synchronized (SEED_LOCK) {
            if (failedKey.equals(key)) return SeedStatus.FAILED;
            if (attemptedKey.equals(key) && Llm.failedInScope(safeScope)) {
                failedKey = key;
                return SeedStatus.FAILED;
            }
        }
        String seedSystem = narratorSystem + "\n\n" + BOOT_TURN;
        LiveTrace.request(seedSystem, "", TIMELINE);
        String error = Llm.startBufferedScoped(
                safeScope, seedSystem, "", TIMELINE, 24,
                (generation, reply) -> {
                    LiveTrace.reply(reply);
                    if (reply == null || !ACK.equals(reply.strip())) {
                        markFailed(key);
                        LiveTrace.validation("NARRATOR HISTORY SEED REJECTED");
                        return Llm.CompletionResult.failure("invalid_output",
                                "the narrator did not acknowledge its private history");
                    }
                    boolean stored = Campaign.commitProviderSeed(
                            generation, profileName, model, safeScope,
                            Llm.pendingResponseId());
                    LiveTrace.validation(stored
                            ? "NARRATOR HISTORY SEEDED BEFORE PAGE ONE"
                            : "NARRATOR HISTORY READY BUT CHECKPOINT SAVE FAILED");
                    if (!stored) markFailed(key);
                    return stored
                            ? Llm.CompletionResult.success("HISTORY READY")
                            : Llm.CompletionResult.failure("save",
                                    "the narrator learned the history but its checkpoint "
                                            + "could not be saved");
                });
        if (error == null) {
            synchronized (SEED_LOCK) { attemptedKey = key; }
            return SeedStatus.SEEDING;
        }
        if (error.contains("already being written") || error.contains("still stopping")) {
            return SeedStatus.SEEDING;
        }
        markFailed(key);
        Config.log("narrator history seed could not start: " + error);
        return SeedStatus.FAILED;
    }

    /** Clears a latched failure only when the player deliberately asks to retry. */
    public static SeedStatus retry(String scope, String narratorSystem) {
        synchronized (SEED_LOCK) {
            attemptedKey = "";
            failedKey = "";
        }
        return ensureSeeded(scope, narratorSystem);
    }

    private static void markFailed(String key) {
        synchronized (SEED_LOCK) { failedKey = key; }
    }
}
