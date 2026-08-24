package de.fricke.pzstory;

/** Fresh saves must not inherit Build 42's misleading night counter. */
public final class PromptFreshStartTest {

    public static void run() {
        T.group("Prompt - fresh-start survival chronology");
        String state = "{\"character\":{\"pronouns\":\"he/him\","
                + "\"timeSurvived\":\"less than a day\"},"
                + "\"position\":{\"outdoors\":true}}";
        String prompt = Prompt.userTurn(state, "", "", false, false);
        T.ok("opening morning is not called a survived night",
                prompt.contains("HAS NOT LIVED THROUGH A NIGHT YET"));
        T.ok("false earlier prose is explicitly overruled",
                prompt.contains("even if an earlier page mistakenly said so"));

        String first = Prompt.userTurn(state, "", "", true, false);
        int finalFormat = first.lastIndexOf("FORMAT, and it is not optional");
        int premiseHeading = first.indexOf("### PREMISE", finalFormat);
        int titleHeading = first.indexOf("### TITLE", finalFormat);
        T.ok("final first-page format retains premise",
                finalFormat >= 0 && premiseHeading >= 0
                        && premiseHeading < titleHeading);
        T.ok("final tense reminder is pronoun-neutral",
                first.contains("The survivor is standing there right now"));
        T.ok("final grounding check treats state as complete",
                first.contains("STATE is the complete physical world"));
        T.ok("final grounding check repeats the terminal boundary",
                first.contains("End only after the exact ### TODO section"));
    }
}
