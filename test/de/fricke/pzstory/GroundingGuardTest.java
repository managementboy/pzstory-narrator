package de.fricke.pzstory;

/** Classic prose cannot be committed when it contradicts facts we can prove. */
public final class GroundingGuardTest {
    private static final String PREMISE = "Murray knows kitchens and ordinary work. "
            + "This morning offers neither comfort nor explanation. "
            + "A nearby noise makes the familiar room feel staged. "
            + "He needs shelter, food, and enough quiet to understand what has changed. "
            + "His habit of noticing small inconsistencies gives him a reason to keep looking, "
            + "while for now suspicion is more useful than certainty and every answer must be earned.";
    private static final String STATE = """
            {"character":{"forename":"Murray","surname":"Wild"},
             "position":{"room":"livingroom"},
             "theDead":{"withinSight":"one"},
             "noise":{"what":"a noise close by"},
             "stowedOnHim":["ID Card: Murray Wild","Murray Wild's Key Ring"],
             "here":{"furniture":["sidetable","fridge"],
                     "windows":{"total":2,"withCurtains":1,"curtainsDrawn":0}}}
            """;

    public static void run() {
        T.group("Grounding guard - provable contradictions stop prose");
        PageResult good = parse("The dead figure remains visible while Murray listens "
                + "to the nearby noise. He rubs his eyes and breathes slowly. The "
                + "fridge and side table make the room feel painfully ordinary. "
                + "Nothing explains the shape outside or the sound close at hand, "
                + "and he refuses to decide what either one means yet.");
        GroundingGuard.validate(good, STATE, "", true, false);
        T.ok("grounded page passes", true);

        reject("mandatory dead cannot disappear",
                "Murray listens to the nearby noise. ".repeat(12), "visible dead");
        reject("generic noise cannot become radio static",
                "The dead figure waits while radio static crackles nearby. ".repeat(8),
                "invented as");
        reject("unsupported telephone is refused",
                "The dead figure waits while Murray studies the telephone. ".repeat(8),
                "telephone");
        reject("unrecorded walking is refused",
                "The dead figure waits. Murray walks to the window and listens. ".repeat(8),
                "movement");
        reject("curtain contradiction is refused",
                "The dead figure waits beyond curtains that are drawn. ".repeat(8),
                "none drawn");
        reject("stowed ID cannot rest on furniture",
                "The dead figure waits. His ID card sits on the side table. ".repeat(8),
                "stowed item");
        reject("unsupported named town is refused",
                "The dead figure makes him think the Knoxville quarantine is near. ".repeat(8),
                "knoxville");

        PageResult shortPremise = PageResult.parse(reply(
                "Murray knows ordinary work and familiar kitchens. This morning offers "
                        + "no comfort or explanation. He needs enough quiet to decide "
                        + "what matters.",
                "The dead figure remains visible while Murray listens. ".repeat(8)),
                true, 200);
        T.throwsWith("first premise contract is locally enforced", "60-100 words",
                () -> GroundingGuard.validate(shortPremise, STATE, "", true, false));
    }

    private static PageResult parse(String page) {
        return PageResult.parse(reply(PREMISE, page), true, 200);
    }

    private static String reply(String premise, String page) {
        return "### PREMISE\n" + premise + "\n### TITLE\nA Grounded Morning\n"
                + "### PAGE\n" + page + "\n### CANON\n\n### TODO\n";
    }

    private static void reject(String name, String page, String message) {
        PageResult result = parse(page);
        T.throwsWith(name, message,
                () -> GroundingGuard.validate(result, STATE, "", true, false));
    }
}
