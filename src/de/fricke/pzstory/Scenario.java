package de.fricke.pzstory;

/**
 * The kind of story this campaign is.
 *
 * Chosen once, at the start of a game, and kept for the life of the save.
 *
 * A SCENARIO IS A LENS, NOT A QUEST CHAIN. It changes what the narrator
 * notices, what it lingers on and what shape it feels the story bending
 * toward. It may carry a very small opening list explicitly chosen by the
 * player, but the narrator never assigns objectives or tells the player where
 * to go. That would break the third rule of the charter, which outranks
 * anything here.
 *
 * These blocks are INSTRUCTIONS, so they speak of "the survivor" and "they".
 * The PROSE uses whatever pronouns the state gives - a survivor is as likely
 * to be a woman, and a spine written in "he" quietly drags every page toward
 * the wrong person.
 */
public final class Scenario {

    public final String id;
    public final String key;     // short label, for a soft key
    public final String name;
    public final String pitch;   // one line, shown on the chooser
    public final String spine;   // the prompt block
    public final String[] opening; // player-approved initial intentions

    private Scenario(String id, String key, String name, String pitch,
                     String spine, String... opening) {
        this.id = id;
        this.key = key;
        this.name = name;
        this.pitch = pitch;
        this.spine = spine;
        this.opening = opening;
    }

    public static final Scenario[] ALL = {

        new Scenario("conspiracy", "CLUES", "Conspiracy",
            "discover why Knox County became infected and died",
            """
            THE STORY YOU ARE TELLING: one grounded conspiracy.

            Use the private Knox chronology seeded before page one as your \
            background. Build one coherent conspiracy around why Knox County is \
            full of infected people and the dead. The first page must establish \
            atmosphere and suspicion only. Do NOT choose, state or confirm the \
            explanation yet. During the first few days, let a working conspiracy \
            emerge from what the player tells you and from evidence the survivor \
            actually earns. It may involve human decisions, institutions, \
            concealment and motive. Once a theory begins to take shape, keep it \
            consistent rather than replacing it on every page.

            The conspiracy is the story's explanation, not established Project \
            Zomboid canon and not automatic survivor knowledge. Reveal it slowly \
            through evidence the live STATE actually makes available: a dated \
            paper they can see, a broadcast they can truly hear, a place they \
            have reached, or a contradiction they have earned. Until then, use \
            unease, suspicion and atmosphere. Never make the survivor remember \
            the private chronology and never invent a newspaper, broadcast, \
            object, location or clue.

            NO SUPERNATURAL. No omniscient messages, no technology working that \
            should not, and no magical certainty. The narrator may invent the \
            conspiracy's hidden motive and history, but must never invent a \
            physical fact in the live world. Let the explanation become more \
            convincing as the survivor earns evidence, without turning the page \
            into a list of observations or a lore dump.
            """,
            "choose and secure a safe house",
            "gather food for a few days",
            "clear the zombies from a few blocks around the chosen safe house"),
    };

    public static Scenario byId(String id) {
        if (id == null) return null;
        for (Scenario s : ALL) if (s.id.equals(id)) return s;
        return null;
    }

    /** The chooser list, as JSON. */
    public static String listJson() {
        Json j = new Json().obj();
        j.arrKey("scenarios");
        for (Scenario s : ALL) {
            j.obj();
            j.put("id", s.id);
            j.put("key", s.key);
            j.put("name", s.name);
            j.put("pitch", s.pitch);
            j.endObj();
        }
        j.endArr();
        return j.endObj().toString();
    }
}
