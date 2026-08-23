package de.fricke.pzstory;

/**
 * The kind of story this campaign is.
 *
 * Chosen once, at the start of a game, and kept for the life of the save.
 *
 * A SCENARIO IS A LENS, NOT A QUEST CHAIN. It changes what the narrator
 * notices, what it lingers on and what shape it feels the story bending
 * toward. It never issues an objective, never sets a task and never tells the
 * player where to go - that would break the third rule of the charter, which
 * outranks anything here. A thriller foreshadows. A survival story weighs
 * weather and stores. A conspiracy reads the paper on the floor twice.
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
    public final String[] opening;  // what goes on the list on day one

    private Scenario(String id, String key, String name, String pitch,
                     String spine, String... opening) {
        this.id = id;
        this.key = key;
        this.name = name;
        this.pitch = pitch;
        this.spine = spine;
        this.opening = opening;
    }

    /**
     * The three things anybody wants in the first day, whatever story this is.
     *
     * Appended to every scenario's own list. Deliberately short and physical -
     * a to-do list nobody can act on in the first hour is decoration.
     */
    public static final String[] FIRST_DAYS = {
        "find a bag worth carrying",
        "find something to swing that will not break",
        "get a door that locks between me and the street",
    };

    public static final Scenario[] ALL = {

        new Scenario("road", "ROAD", "A Long Way Through Knox",
            "a road story - town by town, west to east",
            """
            THE STORY YOU ARE TELLING: a road story.

            This survivor does not stay. Whatever they build is a waypoint, and \
            the shape of this story is distance covered - Muldraugh, West Point, \
            Rosewood, Riverside, March Ridge, Ekron, and Louisville last of all \
            if they ever get that far.

            So: notice the roads. Notice fuel, and tyres, and how far the light \
            will last. Notice what a town is like before they are in it and what \
            it was like after they leave. Let places have a character worth \
            comparing - the one that was quiet wrong, the one that had been \
            fought over. Distance and weariness are the recurring notes.

            You never give an order. But this is a story about LEAVING, and the \
            pull of the road is allowed to be felt on any page - in a house \
            that is not theirs, in a garage with somebody else's tools, in the \
            quiet before they have gone anywhere. A survivor packing a bag in a \
            stranger's kitchen is already halfway out of the door in his head, \
            and the page may say so. What it may not do is tell the player to \
            drive.
            """,
            "find a map of the county",
            "find something that runs, and the keys for it",
            "learn the name of the next town along",
            "keep a full water bottle on me at all times",
            "find out how far the cordon actually goes"),

        new Scenario("survival", "YEAR", "One Year",
            "hold out - a base, the seasons, and time",
            """
            THE STORY YOU ARE TELLING: an endurance story.

            The arc is TIME. Not a journey, not a mystery - just whether they \
            are still here in a year. The enemy is attrition: the roof that \
            leaks, the tin that runs out, the winter that is still four months \
            away and coming anyway.

            So: notice stores and their bottom. Notice weather and the turn of \
            the season. Notice the difference between a place they sleep and a \
            place they have made theirs. Notice repetition - the same walk, the \
            same door, the same sound at night - because repetition is what a \
            year actually feels like, and small changes inside it carry enormous \
            weight. A door they have fixed is worth a paragraph.

            Let time be a character. Say what day it is when it matters.
            """,
            "pick a house worth defending and stop moving",
            "get something across the ground-floor windows",
            "work out where the water comes from when the mains stop",
            "put a week of tinned food in one place",
            "find seeds, and somewhere with light to plant them"),

        new Scenario("conspiracy", "CLUES", "What Happened Here",
            "grounded conspiracy - paper, broadcasts, places",
            """
            THE STORY YOU ARE TELLING: a grounded conspiracy.

            Something happened in Knox County before they woke, and the evidence \
            is lying around in plain sight: newspapers, flyers, television, \
            radio, the things people left on their kitchen tables. They are not \
            solving it. They are someone who keeps noticing that the pieces do \
            not fit.

            So: read the room like a document. Notice what is written down, what \
            is dated, what contradicts what. Notice absences - the house packed \
            in a hurry, the roadblock facing the wrong way. Notice institutions: \
            who was supposed to be in charge here, and what they did.

            NO SUPERNATURAL. No omniscient messages, no technology working that \
            should not, no voice that knows more than a person could. The horror \
            is that it was all done by people. Build only on what the world \
            actually contains; invent motive and history, never facts.
            """,
            "read every newspaper I find, in date order",
            "find a working radio and a spare battery",
            "get to March Ridge and look at where the truck went over",
            "write down what the broadcasts say, and what I can see",
            "find out who was supposed to be in charge here"),

        new Scenario("character", "WHO", "Whoever They Are",
            "let their trade and their nature set the story",
            """
            THE STORY YOU ARE TELLING: a character study.

            There is no external arc. The spine is the survivor in the state \
            block - the occupation and the traits are not statistics, they are a \
            biography, and this story is about what the end of the world does to \
            someone shaped like that.

            So: let their trade decide what they see first. An electrician \
            counts outlets. A nurse reads a wound before a face. A burglar reads \
            locks. Let traits be habits and fears rather than modifiers - \
            someone claustrophobic does not "suffer a penalty indoors", they \
            find a reason to be outside and do not say why. Let a strength be \
            something they are quietly proud of, and a weakness something they \
            work around without naming.

            Return to the same handful of things about them. A character study \
            deepens; it does not accumulate.
            """,
            "find the tools of my own trade",
            "sleep a whole night somewhere and wake up in it",
            "put a name to one of the dead",
            "find out whether anyone I knew got out"),
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
