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
        T.ok("system names the exact game build and narrator release",
                Prompt.CHARTER.startsWith("GAME AND ROLE. The game is Project "
                        + "Zomboid Build 42.20.3. You are PZStory "
                        + Version.RELEASE));
        T.ok("false earlier prose is explicitly overruled",
                prompt.contains("even if an earlier page mistakenly said so"));

        String first = Prompt.userTurn(state, "", "", true, false);
        int finalFormat = first.lastIndexOf("FORMAT, and it is not optional");
        int premiseHeading = first.indexOf("### PREMISE", finalFormat);
        int titleHeading = first.indexOf("### TITLE", finalFormat);
        T.ok("final first-page format retains premise",
                finalFormat >= 0 && premiseHeading >= 0
                        && premiseHeading < titleHeading);
        String terminalFormat = first.substring(finalFormat);
        T.ok("terminal headings have no inline annotations",
                terminalFormat.contains("### TITLE\n<three or four word title")
                        && !terminalFormat.contains("### TITLE   ("));
        T.ok("terminal canon demonstrates required bullet and kind",
                terminalFormat.contains("### CANON\n- [world] <"));
        T.ok("terminal todo demonstrates required bullet",
                terminalFormat.contains("### TODO\n- <one concise task>"));
        T.ok("final tense reminder is pronoun-neutral",
                first.contains("The survivor is standing there right now"));
        T.ok("final grounding check treats state as complete",
                first.contains("STATE is the complete physical world"));
        T.ok("final grounding check repeats the terminal boundary",
                first.contains("End only after the exact ### TODO section"));
        T.ok("first-page premise has an explicit sentence and word contract",
                first.contains("PREMISE must contain 60-100 words")
                        && first.contains("three to five complete sentences"));
        T.ok("occupation cannot relocate the survivor to a workplace",
                first.contains("occupation is only the survivor's former job"));
        T.ok("hidden narrator history cannot become survivor memory",
                first.contains("Hidden Knox history is narrator knowledge"));
        T.ok("physical media must be present in state",
                first.contains("radio, television, newspaper, book"));
        T.ok("format no longer contains reusable sample story prose",
                !Prompt.CHARTER.contains("The Dead Grid"));
    }
}
