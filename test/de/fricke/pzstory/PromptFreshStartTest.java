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
    }
}
